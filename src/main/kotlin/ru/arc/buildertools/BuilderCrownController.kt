package ru.arc.buildertools

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.arc.text.LocalizedMiniMessage
import java.util.Locale
import java.util.UUID

/** Shared transaction and protection boundary used by the crown lifecycle. */
internal interface BuilderCrownHost {
    fun operationLocked(playerId: UUID): Boolean
    fun ensureAvailable(player: Player)
    fun ensurePermission(player: Player)
    fun ensureMutable(player: Player, block: Block)
    fun placementData(material: Material): BlockData
    fun materialLabel(player: Player, material: Material): Component
    fun setFirstPosition(player: Player, location: Location)
    fun createPlan(player: Player, changes: List<BuilderBlockChange>, costs: List<BuilderItemAmount>): BuilderPlan
    fun preparePlan(player: Player, plan: BuilderPlan)
    fun confirmPlan(player: Player)
    fun prepareUndo(player: Player)
    fun cancelPlan(player: Player)
    fun showPlanStatus(player: Player)
    fun discardPendingCrown(playerId: UUID)
    fun runEventAction(player: Player, action: () -> Unit)
    fun fail(path: String, values: Map<String, Component> = emptyMap()): Nothing
}

/**
 * Main-thread owner of the complete non-durable crown workflow.
 *
 * The controller owns player preferences, brush identity, click anchors and
 * its listener lifecycle. The runtime remains the sole owner of generic plan
 * preflight, Lands checks, confirmation, journaling and mutation.
 */
internal class BuilderCrownController(
    plugin: Plugin,
    private val messages: LocalizedMiniMessage,
    private val safety: BuilderBlockSafety,
    private val selections: BuilderSelectionController,
    private val maximumChanges: Int,
    private val host: BuilderCrownHost,
) : Listener, AutoCloseable {
    private val brushKey = NamespacedKey(plugin, "crown_brush")
    private val sessions = BuilderCrownSessions()
    private val brushAnchors = mutableMapOf<UUID, BuilderBlockPos>()
    private var closed = false

    init {
        require(maximumChanges in 1..BuilderPlan.ABSOLUTE_MAX_CHANGES) {
            "Builder crown maximum changes must stay inside the absolute plan bound"
        }
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    fun tabComplete(args: Array<out String>): List<String> {
        if (args.size == 2) {
            return filterPrefix(
                listOf("help", "wand", "palette", "shape", "radius", "density", "noise", "reroll", "place", "undo", "cancel", "status") + leafNames(),
                args.lastOrNull(),
            )
        }
        if (args.size != 3) return emptyList()
        return when (args[1].lowercase(Locale.ROOT)) {
            "shape" -> filterPrefix(BuilderCrownShape.entries.map { it.name.lowercase(Locale.ROOT) }, args.last())
            "radius" -> filterPrefix((MINIMUM_RADIUS..MAXIMUM_RADIUS).map(Int::toString), args.last())
            "density" -> filterPrefix(BuilderCrownDensity.entries.map { it.name.lowercase(Locale.ROOT) }, args.last())
            "noise" -> filterPrefix(BuilderCrownNoise.entries.map { it.name.lowercase(Locale.ROOT) }, args.last())
            "palette" -> filterPrefix(leafNames(), args.last())
            else -> if (args[1].lowercase(Locale.ROOT) in leafNames()) {
                filterPrefix((MINIMUM_RADIUS..MAXIMUM_RADIUS).map(Int::toString), args.last())
            } else {
                emptyList()
            }
        }
    }

    fun handle(player: Player, args: List<String>) {
        host.ensurePermission(player)
        when (args.firstOrNull()?.lowercase(Locale.ROOT)) {
            "confirm", "place" -> return host.confirmPlan(player)
            "undo" -> return host.prepareUndo(player)
            "cancel", "stop" -> return host.cancelPlan(player)
            "status" -> return showStatus(player)
            "brush", "wand" -> return giveBrush(player)
            "palette" -> return updatePalette(player, args.getOrNull(1))
            "shape" -> return updateEnum<BuilderCrownShape>(player, "shape", args.getOrNull(1)) { settings, value -> settings.copy(shape = value) }
            "radius" -> {
                val radius = radius(args.getOrNull(1))
                return updateSettings(player, "radius", radius.toString(), settings(player).copy(radius = radius))
            }
            "density" -> return updateEnum<BuilderCrownDensity>(player, "density", args.getOrNull(1)) { settings, value -> settings.copy(density = value) }
            "noise" -> return updateEnum<BuilderCrownNoise>(player, "noise", args.getOrNull(1)) { settings, value -> settings.copy(noise = value) }
            "reroll" -> return prepare(player, settings(player), reroll = true)
            "help", "settings" -> {
                messages.renderLines("crown.help", locale(player)).forEach(player::sendMessage)
                return
            }
        }
        val current = settings(player)
        val requested = if (args.isEmpty()) {
            current
        } else {
            val material = materialArgument(player, args[0])
            if (!safety.isLeaf(material)) failure("errors.material")
            current.copy(
                palette = listOf(BuilderCrownPaletteEntry(material.name.lowercase(Locale.ROOT), 1)),
                radius = args.getOrNull(1)?.let(::radius) ?: current.radius,
            )
        }
        prepare(player, requested)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onInteract(event: PlayerInteractEvent) {
        val item = event.item ?: return
        if (!isBrush(item)) return
        val player = event.player
        val clicked = event.clickedBlock ?: return
        event.isCancelled = true
        if (host.operationLocked(player.uniqueId)) return
        host.runEventAction(player) {
            host.ensureAvailable(player)
            host.ensurePermission(player)
            when (event.action) {
                Action.LEFT_CLICK_BLOCK -> {
                    host.setFirstPosition(player, clicked.getRelative(event.blockFace).location)
                    prepare(player, settings(player))
                    brushAnchors[player.uniqueId] = checkNotNull(selections.first(player.uniqueId, player.world.uid))
                }
                Action.RIGHT_CLICK_BLOCK -> {
                    val relative = clicked.getRelative(event.blockFace)
                    val actual = BuilderBlockPos(relative.world.uid, relative.x, relative.y, relative.z)
                    if (actual != brushAnchors[player.uniqueId]) failure("crown.same-face")
                    host.confirmPlan(player)
                }
                else -> Unit
            }
        }
    }

    fun clearAnchor(playerId: UUID) {
        brushAnchors.remove(playerId)
    }

    fun clearPlayer(playerId: UUID) {
        brushAnchors.remove(playerId)
        sessions.clear(playerId)
    }

    internal fun settings(playerId: UUID): BuilderCrownSettings = sessions.settings(playerId)

    internal fun anchor(playerId: UUID): BuilderBlockPos? = brushAnchors[playerId]

    internal fun isBrush(item: ItemStack): Boolean {
        if (item.itemMeta?.persistentDataContainer?.has(brushKey, PersistentDataType.BYTE) == true) return true
        return item.type == Material.BRUSH && plainDisplayName(item) == "Кисть крон"
    }

    internal fun styleBrush(item: ItemStack, player: Player): ItemStack = item.apply {
        editMeta { meta ->
            BuilderItemPresentation.apply(
                meta,
                messages.render("crown-brush.name", locale(player)),
                messages.renderLines("crown-brush.lore", locale(player)),
            )
            meta.persistentDataContainer.set(brushKey, PersistentDataType.BYTE, 1)
        }
    }

    private fun radius(raw: String?): Int {
        val radius = raw?.toIntOrNull() ?: failure("errors.crown-setting")
        if (radius !in MINIMUM_RADIUS..MAXIMUM_RADIUS) failure("errors.crown-setting")
        return radius
    }

    private fun settings(player: Player): BuilderCrownSettings = settings(player.uniqueId)

    private fun updatePalette(player: Player, raw: String?) {
        val parsed = try {
            BuilderCrownPaletteParser.parse(raw ?: throw IllegalArgumentException("missing palette"))
        } catch (_: IllegalArgumentException) {
            failure("errors.crown-setting")
        }
        parsed.forEach { entry ->
            val material = Material.matchMaterial(entry.materialName) ?: failure("errors.material")
            if (!safety.isLeaf(material)) failure("errors.material")
        }
        storeSettings(player, settings(player).copy(palette = parsed))
        send(player, "crown.palette-updated", mapOf("count" to messages.literal(parsed.size)))
    }

    private inline fun <reified T : Enum<T>> updateEnum(
        player: Player,
        key: String,
        raw: String?,
        update: (BuilderCrownSettings, T) -> BuilderCrownSettings,
    ) {
        val value = enumValues<T>().firstOrNull { it.name.equals(raw, true) } ?: failure("errors.crown-setting")
        updateSettings(player, key, value.name.lowercase(Locale.ROOT), update(settings(player), value))
    }

    private fun updateSettings(player: Player, key: String, value: String, updated: BuilderCrownSettings) {
        storeSettings(player, updated)
        send(
            player,
            "crown.settings-updated",
            mapOf(
                "setting" to crownSettingLabel(player, key),
                "value" to crownValueLabel(player, key, value),
            ),
        )
    }

    private fun storeSettings(player: Player, updated: BuilderCrownSettings) {
        sessions.update(player.uniqueId, updated)
        host.discardPendingCrown(player.uniqueId)
    }

    private fun showStatus(player: Player) {
        val settings = settings(player)
        val values = mapOf(
            "shape" to crownValueLabel(player, "shape", settings.shape.name.lowercase(Locale.ROOT)),
            "radius" to messages.literal(settings.radius),
            "density" to crownValueLabel(player, "density", settings.density.name.lowercase(Locale.ROOT)),
            "noise" to crownValueLabel(player, "noise", settings.noise.name.lowercase(Locale.ROOT)),
        )
        messages.renderLines("crown.status", locale(player), values).forEach(player::sendMessage)
        settings.palette.forEach { entry ->
            send(
                player,
                "crown.palette-row",
                mapOf(
                    "material" to host.materialLabel(
                        player,
                        Material.matchMaterial(entry.materialName) ?: failure("errors.material"),
                    ),
                    "weight" to messages.literal(entry.weight),
                ),
            )
        }
        host.showPlanStatus(player)
    }

    private fun giveBrush(player: Player) {
        if (isBrush(player.inventory.itemInMainHand)) {
            player.inventory.setItemInMainHand(styleBrush(player.inventory.itemInMainHand.clone(), player))
            send(player, "crown-brush.received")
            return
        }
        val brush = styleBrush(ItemStack(Material.BRUSH), player)
        when (BuilderOwnedToolExchange.replaceOnePlainHeld(player, Material.BRUSH, brush)) {
            BuilderOwnedToolExchangeResult.REPLACED -> Unit
            BuilderOwnedToolExchangeResult.WRONG_ITEM -> failure("crown-brush.material-required")
            BuilderOwnedToolExchangeResult.INVENTORY_FULL -> failure("crown-brush.inventory-full")
        }
        send(player, "crown-brush.received")
    }

    private fun prepare(player: Player, rawSettings: BuilderCrownSettings, reroll: Boolean = false) {
        val settings = try {
            rawSettings.validated()
        } catch (_: IllegalArgumentException) {
            failure("errors.crown-setting")
        }
        val center = selections.first(player.uniqueId, player.world.uid) ?: failure("errors.selection-missing")
        val seed = sessions.seed(player.uniqueId, center, settings, reroll)
        clearAnchor(player.uniqueId)
        host.preparePlan(player, plan(player, center, settings, seed))
    }

    private fun plan(
        player: Player,
        center: BuilderBlockPos,
        settings: BuilderCrownSettings,
        seed: Long,
    ): BuilderPlan {
        val world = Bukkit.getWorld(center.worldId) ?: failure("errors.world-not-allowed")
        val materialByName = settings.palette.associate { entry ->
            val material = Material.matchMaterial(entry.materialName) ?: failure("errors.material")
            if (!safety.isLeaf(material)) failure("errors.material")
            entry.materialName to material
        }
        val dataByMaterial = materialByName.values.associateWith(host::placementData)
        val costs = mutableListOf<ItemStack>()
        val changes = BuilderCrownGeometry.offsets(settings, seed).mapNotNull { (dx, dy, dz) ->
            val position = BuilderBlockPos(center.worldId, center.x + dx, center.y + dy, center.z + dz).validated()
            val block = world.getBlockAt(position.x, position.y, position.z)
            val material = materialByName.getValue(settings.materialAt(dx, dy, dz, seed))
            val data = dataByMaterial.getValue(material)
            if (block.blockData.asString == data.asString) return@mapNotNull null
            if (!safety.isReplaceable(block)) return@mapNotNull null
            host.ensureMutable(player, block)
            if (BuilderGameModePolicy.usesInventory(player.gameMode)) costs += ItemStack(material)
            BuilderBlockChange(position, block.blockData.asString, data.asString)
        }.take(maximumChanges + 1).toList()
        if (changes.isEmpty()) failure("errors.nothing-to-change")
        if (changes.size > maximumChanges) failure("errors.selection-too-large")
        return host.createPlan(player, changes, BuilderItemCodec.aggregate(costs))
    }

    private fun materialArgument(player: Player, raw: String?): Material {
        if (raw == null) {
            return player.inventory.itemInMainHand.type.takeUnless(Material::isAir) ?: failure("errors.material")
        }
        return Material.matchMaterial(raw)
            ?: Material.matchMaterial(raw.uppercase(Locale.ROOT))
            ?: failure("errors.material")
    }

    private fun send(player: Player, path: String, values: Map<String, Component> = emptyMap()) {
        player.sendMessage(messages.render(path, locale(player), values))
    }

    private fun crownSettingLabel(player: Player, key: String): Component =
        messages.render("crown.labels.settings.$key", locale(player))

    private fun crownValueLabel(player: Player, key: String, value: String): Component =
        if (key == "radius") messages.literal(value) else messages.render("crown.labels.$key.$value", locale(player))

    private fun locale(player: Player): String = player.locale().toLanguageTag()

    private fun leafNames(): List<String> = Material.entries.asSequence()
        .filter(safety::isLeaf)
        .map { it.name.lowercase(Locale.ROOT) }
        .toList()

    private fun filterPrefix(values: List<String>, raw: String?): List<String> {
        val prefix = raw.orEmpty().lowercase(Locale.ROOT)
        return values.filter { it.startsWith(prefix) }.take(100)
    }

    private fun plainDisplayName(item: ItemStack): String? = item.itemMeta?.displayName()?.let {
        PlainTextComponentSerializer.plainText().serialize(it)
    }

    private fun failure(path: String, values: Map<String, Component> = emptyMap()): Nothing = host.fail(path, values)

    override fun close() {
        if (closed) return
        closed = true
        HandlerList.unregisterAll(this)
        brushAnchors.clear()
        sessions.clear()
    }

    private companion object {
        const val MINIMUM_RADIUS = 3
        const val MAXIMUM_RADIUS = 10
    }
}
