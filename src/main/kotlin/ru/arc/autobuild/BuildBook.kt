package ru.arc.autobuild

import de.tr7zw.changeme.nbtapi.NBT
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataType
import ru.arc.ARC
import ru.arc.buildertools.BuilderCurrencyPresentation
import ru.arc.buildertools.BuilderMoney
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.util.TextUtil.strip
import java.util.UUID

data class BuildBookMaterialRequirement(
    val material: Material,
    val amount: Int,
) {
    fun validated(): BuildBookMaterialRequirement = apply {
        require(material.name !in AIR_MATERIAL_NAMES && !material.name.startsWith("LEGACY_")) {
            "Build-book player material must be a modern non-air material"
        }
        require(amount in 1..1_000_000) { "Build-book player material amount is outside its safety bound" }
    }

    val materialKey: String get() = material.key.toString()

    private companion object {
        val AIR_MATERIAL_NAMES = setOf("AIR", "CAVE_AIR", "VOID_AIR")
    }
}

object BuildBookMaterialRequirements {
    fun normalize(requirements: Iterable<BuildBookMaterialRequirement>): List<BuildBookMaterialRequirement> = requirements
        .groupingBy(BuildBookMaterialRequirement::material)
        .fold(0L) { total, requirement -> Math.addExact(total, requirement.validated().amount.toLong()) }
        .entries
        .sortedBy { it.key.key.toString() }
        .map { (material, amount) ->
            require(amount <= 1_000_000L) { "Build-book player material amount is outside its safety bound" }
            BuildBookMaterialRequirement(material, amount.toInt()).validated()
        }
        .also { normalized ->
            require(normalized.size <= 256) { "Build-book player material type count is outside its safety bound" }
            require(normalized.sumOf { it.amount.toLong() } <= 2_000_000L) {
                "Build-book player material total is outside its safety bound"
            }
        }

    fun encode(requirements: Iterable<BuildBookMaterialRequirement>): String = normalize(requirements)
        .joinToString("\n") { requirement -> "${requirement.materialKey}=${requirement.amount}" }

    fun decode(encoded: String?): List<BuildBookMaterialRequirement> {
        if (encoded.isNullOrEmpty()) return emptyList()
        require(encoded.length <= 32_768) { "Build-book player material payload is outside its safety bound" }
        return normalize(
            encoded.lineSequence().map { line ->
                val split = line.lastIndexOf('=')
                require(split in 1 until line.lastIndex) { "Build-book player material entry is invalid" }
                val material = Material.matchMaterial(line.substring(0, split))
                    ?: error("Build-book player material is unknown")
                val amount = line.substring(split + 1).toIntOrNull()
                    ?: error("Build-book player material amount is invalid")
                BuildBookMaterialRequirement(material, amount).validated()
            }.toList(),
        )
    }
}

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
    val playerMaterials: List<BuildBookMaterialRequirement> = emptyList(),
    val systemMaterialsIncluded: Boolean? = null,
    val selectableBuildingIds: List<String> = emptyList(),
) {
    fun validated(): BuildBookData = apply {
        require(BUILDING_ID.matches(buildingId)) { "Build-book building id is invalid" }
        require(title.isNotBlank() && title.length <= 48 && title.none(Char::isISOControl)) { "Build-book title is invalid" }
        require(selectableBuildingIds.isEmpty() || (
            !playerCreated && selectableBuildingIds.size in 2..256 &&
                selectableBuildingIds.distinct().size == selectableBuildingIds.size &&
                buildingId in selectableBuildingIds && selectableBuildingIds.all(BUILDING_ID::matches)
            )) { "Build-book selector options are invalid" }
        transform.validated()
        require(sourceRotation in BuildBookTransform.CARDINAL_ROTATIONS) {
            "Build-book source rotation must be cardinal"
        }
        require(blockCount == null || blockCount in 1..10_000) { "Build-book block count is invalid" }
        require(cooldownSeconds == null || cooldownSeconds in 0..BuildCooldownPolicy.MAX_SECONDS) {
            "Build-book cooldown is invalid"
        }
        require(!playerCreated || creatorId != null) { "Player-created build books require a creator" }
        require(!playerCreated || systemMaterialsIncluded == null) {
            "Player-created build books cannot carry a system material policy"
        }
        creatorName?.let { require(CREATOR_NAME.matches(it)) { "Build-book creator name is invalid" } }
        require(instanceId == null || blueprintId != null) { "Build-book instance requires a blueprint" }
        require((instanceId == null) == (instanceGeneration == null)) {
            "Build-book instance and generation must be present together"
        }
        instanceGeneration?.let { require(it > 0) { "Build-book instance generation is invalid" } }
        require(!deliveryPending || instanceId != null) { "Only an issued build-book instance may await delivery" }
        require(issuePriceMinor == null || issuePriceMinor in 0..100_000_000_000L) { "Build-book price is invalid" }
        require(playerMaterials == BuildBookMaterialRequirements.normalize(playerMaterials)) {
            "Build-book player materials are not canonical"
        }
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
    val draftCustomModelData: Int
        get() = config.integer("build-book.player-copy.draft-custom-model-data", customModelData)
    val activeCustomModelData: Int
        get() = config.integer("build-book.player-copy.active-custom-model-data", customModelData)
    val activeCustomModelDataPool: List<Int>
        get() = config.stringList("build-book.player-copy.active-custom-model-data-pool")
            .map(String::toInt)
    val defaultTitle: String get() = config.string("build-book.player-copy.default-name", "Моя постройка")
    val tooltipStyle: NamespacedKey
        get() = checkNotNull(NamespacedKey.fromString(config.string("build-book.tooltip-style", "lzblocks:tooltip/rare"))) {
            "Build-book tooltip style is invalid"
        }

    fun customModelData(data: BuildBookData): Int = when {
        data.draft -> draftCustomModelData
        activeCustomModelDataPool.isNotEmpty() -> BuildBookVariantModels.select(data, activeCustomModelDataPool)
        else -> activeCustomModelData
    }

    fun validate() = validate(config, mergeForward = true)

    internal fun mergeBundledDefaults(dataRoot: java.nio.file.Path): Boolean =
        Config(dataRoot, ConfigManager.moduleYamlRelative(dataRoot, CONFIG_FILE))
            .mergeMissingFromBundled(ConfigManager.bundledModuleResource(CONFIG_FILE))

    internal fun validate(source: Config, mergeForward: Boolean) {
        if (mergeForward) source.mergeMissingFromBundled(ConfigManager.bundledModuleResource(CONFIG_FILE))
        val maxOffset = source.integer("build-book.player-copy.max-offset", 16)
        val maxBooksPerPlayer = source.integer("build-book.player-copy.max-per-player", 24)
        val customModelData = source.integer("build-book.player-copy.custom-model-data", 0)
        val draftCustomModelData = source.integer("build-book.player-copy.draft-custom-model-data", customModelData)
        val activeCustomModelData = source.integer("build-book.player-copy.active-custom-model-data", customModelData)
        val activeCustomModelDataPool = source.stringList("build-book.player-copy.active-custom-model-data-pool")
            .map { value -> value.toIntOrNull() ?: error("Build-book active model pool contains a non-integer") }
        val defaultTitle = source.string("build-book.player-copy.default-name", "Моя постройка")
        require(maxOffset in 0..64) { "Build-book max-offset must be between 0 and 64" }
        require(maxBooksPerPlayer in 1..100) { "Build-book max-per-player must be between 1 and 100" }
        require(customModelData >= 0) { "Build-book custom-model-data cannot be negative" }
        require(draftCustomModelData >= 0) { "Build-book draft custom-model-data cannot be negative" }
        require(activeCustomModelData >= 0) { "Build-book active custom-model-data cannot be negative" }
        require(activeCustomModelDataPool.size <= 100 && activeCustomModelDataPool.distinct().size == activeCustomModelDataPool.size) {
            "Build-book active custom-model-data pool must contain at most 100 unique entries"
        }
        require(activeCustomModelDataPool.all { it > 0 }) {
            "Build-book active custom-model-data pool entries must be positive"
        }
        require(defaultTitle.isNotBlank() && defaultTitle.length <= 48 && defaultTitle.none(Char::isISOControl)) {
            "Build-book default name is invalid"
        }
        checkNotNull(NamespacedKey.fromString(source.string("build-book.tooltip-style", "lzblocks:tooltip/rare"))) {
            "Build-book tooltip style is invalid"
        }
        REQUIRED_SCALARS.forEach { path -> require(source.stringOrNull(path) != null) { "Missing build-book text '$path'" } }
        REQUIRED_LISTS.forEach { path -> require(source.stringListOrNull(path)?.isNotEmpty() == true) { "Missing build-book lore '$path'" } }
    }

    private val REQUIRED_SCALARS = setOf(
        "build-book.display-name",
        "build-book.tooltip-style",
        "build-book.states.delivery-pending",
        "build-book.states.active",
        "build-book.states.draft",
        "build-book.states.system",
        "build-book.price.draft",
        "build-book.price.system",
        "build-book.player-materials.draft",
        "build-book.player-materials.included",
        "build-book.player-materials.system-required",
        "build-book.player-materials.heading",
        "build-book.player-materials.row",
        "build-book.player-materials.more",
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
        "build-book.editor.copy.name",
    )
    private val REQUIRED_LISTS = setOf(
        "build-book.lore",
        "build-book.footer",
        "build-book.editor.overview.lore",
        "build-book.editor.axis-x.lore",
        "build-book.editor.axis-y.lore",
        "build-book.editor.axis-z.lore",
        "build-book.editor.rotation.lore",
        "build-book.editor.reset.lore",
        "build-book.editor.copy.lore",
        "build-book.editor.preview-inactive.lore",
        "build-book.editor.preview-book-mismatch.lore",
        "build-book.editor.preview-protection-denied.lore",
    )
}

/** Picks one durable visual variant without storing cosmetic state in the authoritative book contract. */
internal object BuildBookVariantModels {
    fun select(data: BuildBookData, variants: List<Int>): Int {
        require(variants.isNotEmpty()) { "Build-book model variant pool cannot be empty" }
        require(variants.all { it > 0 }) { "Build-book model variants must be positive" }
        val identity = data.instanceId?.toString()
            ?: data.blueprintId?.toString()
            ?: data.schematicSha256
            ?: data.buildingId
        return variants[Math.floorMod(identity.hashCode(), variants.size)]
    }
}

object BuildBookCodec {
    private const val SCHEMA_VERSION = 6
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
    private val playerMaterialsKey get() = key("build_book_player_materials")
    private val selectorKey get() = key("build_book_selector_options")
    private val systemMaterialsIncludedKey get() = key("build_book_system_materials_included")

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
                    playerMaterials = BuildBookMaterialRequirements.decode(
                        pdc.get(playerMaterialsKey, PersistentDataType.STRING),
                    ),
                    selectableBuildingIds = pdc.get(selectorKey, PersistentDataType.STRING)?.split(',') ?: emptyList(),
                    systemMaterialsIncluded = pdc.get(systemMaterialsIncludedKey, PersistentDataType.BYTE)?.let { it != 0.toByte() },
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
            pdc.setOrRemove(selectorKey, PersistentDataType.STRING, checked.selectableBuildingIds.takeIf { it.isNotEmpty() }?.joinToString(","))
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
            pdc.set(
                playerMaterialsKey,
                PersistentDataType.STRING,
                BuildBookMaterialRequirements.encode(checked.playerMaterials),
            )
            pdc.setOrRemove(
                systemMaterialsIncludedKey,
                PersistentDataType.BYTE,
                checked.systemMaterialsIncluded?.let { included -> (if (included) 1 else 0).toByte() },
            )
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
    private const val MAX_VISIBLE_PLAYER_MATERIALS = 6
    internal fun compactTitle(title: String, maximumCodePoints: Int = 28): String {
        require(maximumCodePoints > 0) { "Build-book display title limit must be positive" }
        if (title.codePointCount(0, title.length) <= maximumCodePoints) return title
        val end = title.offsetByCodePoints(0, maximumCodePoints)
        return title.substring(0, end).trimEnd() + "…"
    }

    fun create(data: BuildBookData, modelId: Int = BuildBookSettings.customModelData(data)): ItemStack =
        ItemStack(Material.BOOK).also { item ->
            BuildBookCodec.write(item, data)
            refreshAppearance(item, data, modelId)
        }

    fun refreshAppearance(item: ItemStack, data: BuildBookData, modelId: Int = BuildBookSettings.customModelData(data)) {
        val config = ConfigManager.ofModule(ARC.instance.dataPath, "auto-build.yml")
        item.editMeta { meta -> applyAppearance(meta, data, modelId, config) }
    }

    internal fun applyAppearance(
        meta: ItemMeta,
        data: BuildBookData,
        modelId: Int = BuildBookSettings.customModelData(data),
        config: Config = ConfigManager.ofModule(ARC.instance.dataPath, "auto-build.yml"),
    ) {
        strip(
            config.component("build-book.display-name", "<#d48763><bold><name>") {
                tag("name", Component.text(compactTitle(data.title)))
            },
        )?.let(meta::displayName)
        val commonLore = config.componentList("build-book.lore") {
            tag("name", Component.text(data.title))
            tag("rotation", Component.text(data.transform.rotation))
            tag("offset_x", Component.text(data.transform.offsetX))
            tag("offset_y", Component.text(data.transform.offsetY))
            tag("offset_z", Component.text(data.transform.offsetZ))
            tag("blocks", Component.text((data.blockCount ?: "?").toString()))
            tag("creator", Component.text(data.creatorName ?: "RusCrafting"))
            tag(
                "state",
                config.component(
                    when {
                        data.deliveryPending -> "build-book.states.delivery-pending"
                        data.registered -> "build-book.states.active"
                        data.draft -> "build-book.states.draft"
                        else -> "build-book.states.system"
                    },
                    "<#e6fff3>Готова",
                ),
            )
            tag(
                "price",
                data.issuePriceMinor?.let { priceMinor ->
                    BuilderCurrencyPresentation.amountWithCoin(
                        Component.text(BuilderMoney.decimal(priceMinor).toPlainString()),
                    )
                } ?: config.component(
                    if (data.draft) "build-book.price.draft" else "build-book.price.system",
                    "<#969696>Недоступно",
                ),
            )
            tag("instance", Component.text(data.instanceId?.toString()?.take(8) ?: "после активации"))
        }.mapNotNull(::strip)
        val footer = config.componentList("build-book.footer").mapNotNull(::strip)
        val selectorLore = if (data.selectableBuildingIds.isEmpty()) emptyList() else
            config.componentList("build-book.selector-lore") {
                tag("name", Component.text(data.title))
                tag("count", Component.text(data.selectableBuildingIds.size))
            }.mapNotNull(::strip)
        meta.lore(commonLore + playerMaterialLore(config, data) + selectorLore + footer)
        @Suppress("DEPRECATION")
        meta.setCustomModelData(modelId.takeIf { it > 0 })
        meta.tooltipStyle = BuildBookSettings.tooltipStyle
    }

    private fun playerMaterialLore(config: Config, data: BuildBookData): List<Component> {
        if (data.draft) {
            return listOfNotNull(strip(config.component("build-book.player-materials.draft", "<#8c8c8c>Материалы: <#ffb142>после расчёта цены")))
        }
        if (!data.playerCreated && data.systemMaterialsIncluded != true) {
            return listOfNotNull(
                strip(
                    config.component(
                        "build-book.player-materials.system-required",
                        "<#8c8c8c>Материалы: <#ffb142>понадобятся во время строительства",
                    ),
                ),
            )
        }
        if (data.playerMaterials.isEmpty()) {
            return listOfNotNull(
                strip(config.component("build-book.player-materials.included", "<#8c8c8c>Материалы: <#2bba43>включены в стоимость")),
            )
        }
        val visible = data.playerMaterials.take(MAX_VISIBLE_PLAYER_MATERIALS)
        return buildList {
            strip(config.component("build-book.player-materials.heading", "<#ffb142>Принести с собой"))?.let(::add)
            visible.forEach { requirement ->
                strip(
                    config.component("build-book.player-materials.row", "<#8c8c8c>   <#e6fff3><amount>× <material>") {
                        tag("amount", Component.text(requirement.amount))
                        tag("material", Component.translatable(requirement.material.translationKey()))
                    },
                )?.let(::add)
            }
            val hidden = data.playerMaterials.size - visible.size
            if (hidden > 0) {
                strip(config.component("build-book.player-materials.more", "<#8c8c8c>   <#969696>И ещё <count> видов") {
                    tag("count", Component.text(hidden))
                })?.let(::add)
            }
        }
    }
}
