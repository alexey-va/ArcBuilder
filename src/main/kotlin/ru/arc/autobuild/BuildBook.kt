package ru.arc.autobuild

import de.tr7zw.changeme.nbtapi.NBT
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.util.TextUtil.strip
import ru.arc.buildertools.BuilderMoney
import java.util.UUID

data class BuildBookTransform(
    val rotation: Int = 0,
    val offsetX: Int = 0,
    val offsetY: Int = 0,
    val offsetZ: Int = 0,
) {
    fun validated(maxOffset: Int = BuildBookSettings.maxOffset): BuildBookTransform = apply {
        require(rotation in CARDINAL_ROTATIONS) { "Build-book rotation must be cardinal" }
        require(offsetX in -maxOffset..maxOffset) { "Build-book X offset is outside its safety bound" }
        require(offsetY in -maxOffset..maxOffset) { "Build-book Y offset is outside its safety bound" }
        require(offsetZ in -maxOffset..maxOffset) { "Build-book Z offset is outside its safety bound" }
    }

    fun rotate(delta: Int): BuildBookTransform =
        copy(rotation = normalizeRotation(rotation + delta)).validated()

    fun offset(dx: Int = 0, dy: Int = 0, dz: Int = 0): BuildBookTransform =
        copy(
            offsetX = (offsetX + dx).coerceIn(-BuildBookSettings.maxOffset, BuildBookSettings.maxOffset),
            offsetY = (offsetY + dy).coerceIn(-BuildBookSettings.maxOffset, BuildBookSettings.maxOffset),
            offsetZ = (offsetZ + dz).coerceIn(-BuildBookSettings.maxOffset, BuildBookSettings.maxOffset),
        ).validated()

    /** Rotates a local book offset into the same world axes as the structure. */
    fun rotatedOffset(fullRotation: Int): Triple<Int, Int, Int> =
        when (normalizeRotation(fullRotation)) {
            90 -> Triple(-offsetZ, offsetY, offsetX)
            180 -> Triple(-offsetX, offsetY, -offsetZ)
            270 -> Triple(offsetZ, offsetY, -offsetX)
            else -> Triple(offsetX, offsetY, offsetZ)
        }

    companion object {
        val CARDINAL_ROTATIONS = setOf(0, 90, 180, 270)

        fun normalizeRotation(rotation: Int): Int = ((rotation % 360) + 360) % 360

        fun parseLegacy(rotation: String?, yOffset: String?): BuildBookTransform? {
            val parsedRotation = rotation?.toDoubleOrNull()?.toInt() ?: 0
            val parsedYOffset = yOffset?.toDoubleOrNull()?.toInt() ?: 0
            return runCatching {
                BuildBookTransform(
                    rotation = normalizeRotation(parsedRotation),
                    offsetY = parsedYOffset,
                ).validated()
            }.getOrNull()
        }
    }
}

data class BuildBookData(
    val buildingId: String,
    val title: String,
    val transform: BuildBookTransform = BuildBookTransform(),
    val sourceRotation: Int = 0,
    val playerCreated: Boolean = false,
    val creatorId: UUID? = null,
    val creatorName: String? = null,
    val blueprintId: UUID? = null,
    val instanceId: UUID? = null,
    val instanceGeneration: Int? = null,
    val issuePriceMinor: Long? = null,
    val contentSha256: String? = null,
    val schematicSha256: String? = null,
    val deliveryPending: Boolean = false,
    val blockCount: Int? = null,
    val cooldownSeconds: Long? = null,
) {
    fun validated(): BuildBookData = apply {
        require(BUILDING_ID.matches(buildingId)) { "Build-book building id is invalid" }
        require(title.isNotBlank() && title.length <= 48 && title.none(Char::isISOControl)) { "Build-book title is invalid" }
        transform.validated()
        require(sourceRotation in BuildBookTransform.CARDINAL_ROTATIONS) {
            "Build-book source rotation must be cardinal"
        }
        require(blockCount == null || blockCount in 1..10_000) { "Build-book block count is invalid" }
        require(cooldownSeconds == null || cooldownSeconds in 0..BuildCooldownPolicy.MAX_SECONDS) {
            "Build-book cooldown is invalid"
        }
        require(!playerCreated || creatorId != null) { "Player-created build books require a creator" }
        creatorName?.let { require(CREATOR_NAME.matches(it)) { "Build-book creator name is invalid" } }
        require(instanceId == null || blueprintId != null) { "Build-book instance requires a blueprint" }
        require((instanceId == null) == (instanceGeneration == null)) {
            "Build-book instance and generation must be present together"
        }
        instanceGeneration?.let { require(it > 0) { "Build-book instance generation is invalid" } }
        require(!deliveryPending || instanceId != null) { "Only an issued build-book instance may await delivery" }
        require(issuePriceMinor == null || issuePriceMinor in 1..100_000_000_000L) { "Build-book price is invalid" }
        require((contentSha256 == null) == (schematicSha256 == null)) {
            "Build-book content digests must be present together"
        }
        contentSha256?.let { require(SHA256.matches(it)) { "Build-book content digest is invalid" } }
        schematicSha256?.let { require(SHA256.matches(it)) { "Build-book schematic digest is invalid" } }
        if (playerCreated && blueprintId != null) {
            require(contentSha256 != null && schematicSha256 != null) {
                "Player-created build-book lacks immutable content digests"
            }
        }
        if (instanceId != null) {
            require(playerCreated && creatorName != null && issuePriceMinor != null) {
                "Registered build-book instance lacks authoritative display fields"
            }
        }
    }

    val registered: Boolean get() = playerCreated && blueprintId != null && instanceId != null && issuePriceMinor != null
    val draft: Boolean get() = playerCreated && blueprintId != null && instanceId == null
    val available: Boolean get() = registered && !deliveryPending

    companion object {
        private val BUILDING_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,159}")
        private val CREATOR_NAME = Regex("[A-Za-z0-9_]{1,16}")
        private val SHA256 = Regex("[a-f0-9]{64}")
    }
}

object BuildBookSettings {
    private const val CONFIG_FILE = "auto-build.yml"
    private val config: Config get() = ConfigManager.ofModule(ARC.instance.dataPath, CONFIG_FILE)

    val maxOffset: Int get() = config.integer("build-book.player-copy.max-offset", 16)
    val maxBooksPerPlayer: Int get() = config.integer("build-book.player-copy.max-per-player", 24)
    val customModelData: Int get() = config.integer("build-book.player-copy.custom-model-data", 0)
    val defaultTitle: String get() = config.string("build-book.player-copy.default-name", "Моя постройка")

    fun validate() {
        config.mergeMissingFromBundled(ConfigManager.bundledModuleResource(CONFIG_FILE))
        require(maxOffset in 0..64) { "Build-book max-offset must be between 0 and 64" }
        require(maxBooksPerPlayer in 1..100) { "Build-book max-per-player must be between 1 and 100" }
        require(customModelData >= 0) { "Build-book custom-model-data cannot be negative" }
        require(defaultTitle.isNotBlank() && defaultTitle.length <= 48 && defaultTitle.none(Char::isISOControl)) {
            "Build-book default name is invalid"
        }
        REQUIRED_SCALARS.forEach { path -> require(config.stringOrNull(path) != null) { "Missing build-book text '$path'" } }
        REQUIRED_LISTS.forEach { path -> require(config.stringListOrNull(path)?.isNotEmpty() == true) { "Missing build-book lore '$path'" } }
    }

    private val REQUIRED_SCALARS = setOf(
        "build-book.display-name",
        "build-book.received",
        "build-book.editor.title",
        "build-book.editor.invalid",
        "build-book.editor.no-permission",
        "build-book.editor.preview-inactive.name",
        "build-book.editor.preview-book-mismatch.name",
        "build-book.editor.preview-protection-denied.name",
        "build-book.editor.overview.name",
        "build-book.editor.axis-x.name",
        "build-book.editor.axis-y.name",
        "build-book.editor.axis-z.name",
        "build-book.editor.rotation.name",
        "build-book.editor.reset.name",
    )
    private val REQUIRED_LISTS = setOf(
        "build-book.lore",
        "build-book.editor.overview.lore",
        "build-book.editor.axis-x.lore",
        "build-book.editor.axis-y.lore",
        "build-book.editor.axis-z.lore",
        "build-book.editor.rotation.lore",
        "build-book.editor.reset.lore",
        "build-book.editor.preview-inactive.lore",
        "build-book.editor.preview-book-mismatch.lore",
        "build-book.editor.preview-protection-denied.lore",
    )
}

object BuildBookCodec {
    private const val SCHEMA_VERSION = 4
    // Durable books already issued by ARC use the `arc` namespace. Keep it
    // stable after extraction so moving the feature cannot invalidate items.
    @Suppress("DEPRECATION")
    private fun key(value: String) = NamespacedKey("arc", value)
    private val schemaKey get() = key("build_book_schema")
    private val buildingKey get() = key("build_book_id")
    private val titleKey get() = key("build_book_title")
    private val rotationKey get() = key("build_book_rotation")
    private val offsetXKey get() = key("build_book_offset_x")
    private val offsetYKey get() = key("build_book_offset_y")
    private val offsetZKey get() = key("build_book_offset_z")
    private val sourceRotationKey get() = key("build_book_source_rotation")
    private val playerCreatedKey get() = key("build_book_player_created")
    private val creatorKey get() = key("build_book_creator")
    private val creatorNameKey get() = key("build_book_creator_name")
    private val blueprintKey get() = key("build_book_blueprint_uuid")
    private val instanceKey get() = key("build_book_instance_uuid")
    private val instanceGenerationKey get() = key("build_book_instance_generation")
    private val issuePriceKey get() = key("build_book_issue_price_minor")
    private val contentShaKey get() = key("build_book_content_sha256")
    private val schematicShaKey get() = key("build_book_schematic_sha256")
    private val deliveryPendingKey get() = key("build_book_delivery_pending")
    private val blockCountKey get() = key("build_book_block_count")
    private val cooldownKey get() = key("build_book_cooldown_seconds")

    fun read(item: ItemStack): BuildBookData? {
        if (item.type != Material.BOOK) return null
        val pdc = item.itemMeta?.persistentDataContainer ?: return null
        val buildingId = pdc.get(buildingKey, PersistentDataType.STRING)
        if (buildingId != null) {
            val schema = pdc.get(schemaKey, PersistentDataType.INTEGER) ?: return null
            if (schema !in 1..SCHEMA_VERSION) return null
            return runCatching {
                val instanceId = pdc.get(instanceKey, PersistentDataType.STRING)?.let(UUID::fromString)
                BuildBookData(
                    buildingId = buildingId,
                    title = pdc.get(titleKey, PersistentDataType.STRING)?.takeIf(String::isNotBlank) ?: buildingId,
                    transform = BuildBookTransform(
                        rotation = pdc.get(rotationKey, PersistentDataType.INTEGER) ?: 0,
                        offsetX = pdc.get(offsetXKey, PersistentDataType.INTEGER) ?: 0,
                        offsetY = pdc.get(offsetYKey, PersistentDataType.INTEGER) ?: 0,
                        offsetZ = pdc.get(offsetZKey, PersistentDataType.INTEGER) ?: 0,
                    ),
                    sourceRotation = pdc.get(sourceRotationKey, PersistentDataType.INTEGER) ?: 0,
                    playerCreated = (pdc.get(playerCreatedKey, PersistentDataType.BYTE) ?: 0) != 0.toByte(),
                    creatorId = pdc.get(creatorKey, PersistentDataType.STRING)?.let(UUID::fromString),
                    creatorName = pdc.get(creatorNameKey, PersistentDataType.STRING),
                    blueprintId = pdc.get(blueprintKey, PersistentDataType.STRING)?.let(UUID::fromString),
                    instanceId = instanceId,
                    instanceGeneration = pdc.get(instanceGenerationKey, PersistentDataType.INTEGER)
                        ?: instanceId?.let { INITIAL_REGISTERED_GENERATION },
                    issuePriceMinor = pdc.get(issuePriceKey, PersistentDataType.LONG),
                    contentSha256 = pdc.get(contentShaKey, PersistentDataType.STRING),
                    schematicSha256 = pdc.get(schematicShaKey, PersistentDataType.STRING),
                    deliveryPending = (pdc.get(deliveryPendingKey, PersistentDataType.BYTE) ?: 0) != 0.toByte(),
                    blockCount = pdc.get(blockCountKey, PersistentDataType.INTEGER),
                    cooldownSeconds = pdc.get(cooldownKey, PersistentDataType.LONG),
                ).validated()
            }.getOrNull()
        }
        return readLegacy(item)
    }

    fun write(item: ItemStack, data: BuildBookData) {
        val checked = data.validated()
        item.editMeta { meta ->
            val pdc = meta.persistentDataContainer
            pdc.set(schemaKey, PersistentDataType.INTEGER, SCHEMA_VERSION)
            pdc.set(buildingKey, PersistentDataType.STRING, checked.buildingId)
            pdc.set(titleKey, PersistentDataType.STRING, checked.title)
            pdc.set(rotationKey, PersistentDataType.INTEGER, checked.transform.rotation)
            pdc.set(offsetXKey, PersistentDataType.INTEGER, checked.transform.offsetX)
            pdc.set(offsetYKey, PersistentDataType.INTEGER, checked.transform.offsetY)
            pdc.set(offsetZKey, PersistentDataType.INTEGER, checked.transform.offsetZ)
            pdc.set(sourceRotationKey, PersistentDataType.INTEGER, checked.sourceRotation)
            pdc.set(playerCreatedKey, PersistentDataType.BYTE, (if (checked.playerCreated) 1 else 0).toByte())
            pdc.setOrRemove(creatorKey, PersistentDataType.STRING, checked.creatorId?.toString())
            pdc.setOrRemove(creatorNameKey, PersistentDataType.STRING, checked.creatorName)
            pdc.setOrRemove(blueprintKey, PersistentDataType.STRING, checked.blueprintId?.toString())
            pdc.setOrRemove(instanceKey, PersistentDataType.STRING, checked.instanceId?.toString())
            pdc.setOrRemove(instanceGenerationKey, PersistentDataType.INTEGER, checked.instanceGeneration)
            pdc.setOrRemove(issuePriceKey, PersistentDataType.LONG, checked.issuePriceMinor)
            pdc.setOrRemove(contentShaKey, PersistentDataType.STRING, checked.contentSha256)
            pdc.setOrRemove(schematicShaKey, PersistentDataType.STRING, checked.schematicSha256)
            pdc.set(deliveryPendingKey, PersistentDataType.BYTE, (if (checked.deliveryPending) 1 else 0).toByte())
            pdc.setOrRemove(blockCountKey, PersistentDataType.INTEGER, checked.blockCount)
            pdc.setOrRemove(cooldownKey, PersistentDataType.LONG, checked.cooldownSeconds)
        }
    }

    fun update(item: ItemStack, data: BuildBookData): ItemStack = item.clone().also { updated ->
        write(updated, data)
        BuildBookItems.refreshAppearance(updated, data)
    }

    fun matches(item: ItemStack, expected: BuildBookData): Boolean = read(item) == expected

    private fun readLegacy(item: ItemStack): BuildBookData? = runCatching {
        NBT.get<BuildBookData?>(item) { nbt ->
            val buildingId = nbt.getString("arc:building_key").takeIf(String::isNotBlank) ?: return@get null
            val transform = BuildBookTransform.parseLegacy(
                nbt.getString("arc:rotation").takeIf { nbt.hasTag("arc:rotation") },
                nbt.getString("arc:y_offset").takeIf { nbt.hasTag("arc:y_offset") },
            ) ?: return@get null
            val cooldown = nbt.getString("arc:cooldown_seconds")
                .takeIf { nbt.hasTag("arc:cooldown_seconds") }
                ?.toLongOrNull()
            BuildBookData(
                buildingId = buildingId,
                title = buildingId,
                transform = transform,
                cooldownSeconds = cooldown,
            ).validated()
        }
    }.getOrNull()

    private fun <P, C : Any> org.bukkit.persistence.PersistentDataContainer.setOrRemove(
        key: NamespacedKey,
        type: PersistentDataType<P, C>,
        value: C?,
    ) {
        if (value == null) remove(key) else set(key, type, value)
    }

    private const val INITIAL_REGISTERED_GENERATION = 1
}

object BuildBookItems {
    internal fun compactTitle(title: String, maximumCodePoints: Int = 28): String {
        require(maximumCodePoints > 0) { "Build-book display title limit must be positive" }
        if (title.codePointCount(0, title.length) <= maximumCodePoints) return title
        val end = title.offsetByCodePoints(0, maximumCodePoints)
        return title.substring(0, end).trimEnd() + "…"
    }

    fun create(data: BuildBookData, modelId: Int = BuildBookSettings.customModelData): ItemStack =
        ItemStack(Material.BOOK).also { item ->
            BuildBookCodec.write(item, data)
            refreshAppearance(item, data, modelId)
        }

    fun refreshAppearance(item: ItemStack, data: BuildBookData, modelId: Int? = null) {
        val config = ConfigManager.ofModule(ARC.instance.dataPath, "auto-build.yml")
        item.editMeta { meta ->
            strip(
                config.component("build-book.display-name", "<#92bed8><bold><name>") {
                    tag("name", Component.text(compactTitle(data.title)))
                },
            )?.let(meta::displayName)
            meta.lore(
                config.componentList("build-book.lore") {
                    tag("name", Component.text(data.title))
                    tag("rotation", Component.text(data.transform.rotation))
                    tag("offset_x", Component.text(data.transform.offsetX))
                    tag("offset_y", Component.text(data.transform.offsetY))
                    tag("offset_z", Component.text(data.transform.offsetZ))
                    tag("blocks", Component.text((data.blockCount ?: "?").toString()))
                    tag("creator", Component.text(data.creatorName ?: "RusCrafting"))
                    tag(
                        "state",
                        Component.text(
                            when {
                                data.deliveryPending -> "подтверждается"
                                data.registered -> "активна"
                                data.draft -> "черновик"
                                else -> "служебная"
                            },
                        ),
                    )
                    tag(
                        "price",
                        data.issuePriceMinor?.let { priceMinor ->
                            Component.text(BuilderMoney.decimal(priceMinor).toPlainString())
                                .append(Component.space())
                                .append(Component.text("💰", NamedTextColor.WHITE))
                        } ?: Component.text("после проверки"),
                    )
                    tag("instance", Component.text(data.instanceId?.toString()?.take(8) ?: "после активации"))
                }.mapNotNull(::strip),
            )
            @Suppress("DEPRECATION")
            if (modelId != null && modelId > 0) meta.setCustomModelData(modelId)
        }
    }
}
