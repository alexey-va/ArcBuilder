package ru.arc.buildertools

import com.github.retrooper.packetevents.PacketEvents
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockMultiPlaceEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.BoundingBox
import kotlin.math.abs
import ru.arc.core.LifecycleTaskScope
import ru.arc.text.LocalizedMiniMessage
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

internal data class BuilderPlacementAssistOffer(
    val line: BuilderPlacementAssistLine,
    val slot: Int,
    val item: ItemStack,
    val createdAt: Long = System.nanoTime(),
)

/** Player preference and transient line owner. Tick only previews; fresh input alone may place. */
internal class BuilderPlacementAssist(
    private val plugin: JavaPlugin,
    private val renderer: BuilderDisplayRenderer,
    private val messages: LocalizedMiniMessage,
    private val taskScope: LifecycleTaskScope,
    private val isContextAllowed: (Player) -> Boolean,
    private val canBuild: (Player, Block, Material) -> Boolean,
    private val sendMessage: (Player, Component) -> Unit = Player::sendMessage,
    private val nativePlacement: (Player, BuilderBlockPos, BlockFace, Int) -> Unit = PaperBuilderPlacementAssistNative::place,
    private val openPackets: ((UUID) -> BuilderPlacementAssistOffer?, (BuilderPlacementAssistInput) -> Unit) -> AutoCloseable = ::BuilderPlacementAssistPackets,
    private val connection: (Player) -> Any? = { PacketEvents.getAPI()?.playerManager?.getChannel(it) },
) : Listener, AutoCloseable {
    private data class State(val line: BuilderPlacementAssistLine, val slot: Int, val item: ItemStack, var refusedUntil: Long = 0L)
    private data class Target(val position: BuilderBlockPos, val blocked: String? = null)
    private data class Placement(val event: BlockPlaceEvent, val slot: Int, val item: ItemStack)
    private class NativeAttempt(val playerId: UUID, val target: BuilderBlockPos) { var placed: Placement? = null }
    private data class Hud(val key: String, val tick: Long)
    private val preferenceKey = NamespacedKey(plugin, "placement_assist")
    private val states = mutableMapOf<UUID, State>()
    private val offers = ConcurrentHashMap<UUID, BuilderPlacementAssistOffer>()
    private val pendingPlacements = mutableMapOf<UUID, MutableList<Placement>>()
    private val hud = mutableMapOf<UUID, Hud>()
    private var nativeAttempt: NativeAttempt? = null
    private var tickNumber = 0L
    private var packets: AutoCloseable? = null
    private var unavailable = false
    @Volatile private var closed = false

    init { plugin.server.pluginManager.registerEvents(this, plugin) }

    fun isEnabled(player: Player) = player.persistentDataContainer.get(preferenceKey, PersistentDataType.BYTE) == 1.toByte()

    fun command(player: Player, args: List<String>) {
        val enabled = when {
            args.isEmpty() -> !isEnabled(player)
            args.size == 1 && args[0].equals("on", true) -> true
            args.size == 1 && args[0].equals("off", true) -> false
            else -> { send(player, "assist.usage"); return }
        }
        player.persistentDataContainer.set(preferenceKey, PersistentDataType.BYTE, if (enabled) 1.toByte() else 0.toByte())
        clearPlayer(player.uniqueId)
        send(player, if (enabled) "assist.enabled" else "assist.disabled")
        if (enabled) send(player, "assist.hint")
    }

    fun tick() {
        if (closed) return
        tickNumber++
        states.keys.toList().forEach { id ->
            val player = Bukkit.getPlayer(id)
            val state = states[id] ?: return@forEach
            if (player == null || !eligible(player, state)) {
                clearPlayer(id)
                return@forEach
            }
            refresh(player, state)
        }
    }

    private fun eligible(player: Player, state: State): Boolean = !closed && !unavailable && player.isOnline &&
        !player.isDead && !player.isSneaking && isEnabled(player) && isContextAllowed(player) &&
        player.world.uid == state.line.last.worldId && player.inventory.heldItemSlot == state.slot &&
        player.inventory.itemInMainHand.isSimilar(state.item) && player.inventory.itemInMainHand.amount > 0

    private fun refresh(player: Player, state: State) {
        val id = player.uniqueId
        val target = resolve(player, state, player.eyeLocation)
        if (target == null) {
            offers.remove(id)
            renderer.clearPlacementAssist(id)
            clearHud(id)
            return
        }
        renderer.placementAssist(player, target.position, target.blocked != null)
        showHud(player, target.blocked ?: "assist.ready")
        if (target.blocked == null) offers[id] = BuilderPlacementAssistOffer(state.line, state.slot, state.item)
        else offers.remove(id)
    }

    private fun resolve(player: Player, state: State, eye: Location): Target? {
        val next = state.line.next ?: return null
        val support = state.line.last
        val face = state.line.direction ?: return null
        val world = player.world
        if (eye.world != world || listOf(support, next).any {
                it.worldId != world.uid || it.y !in world.minHeight until world.maxHeight ||
                    !world.isChunkLoaded(it.x shr 4, it.z shr 4) ||
                    !world.worldBorder.isInside(Location(world, it.x + .5, it.y + .5, it.z + .5))
            }) return null
        val range = min(8.0, player.getAttribute(Attribute.BLOCK_INTERACTION_RANGE)?.value
            ?: if (player.gameMode == GameMode.CREATIVE) 5.0 else 4.5)
        val direction = eye.direction
        val distance = BuilderPlacementAssistGeometry.aimDistance(eye.toVector(), direction, next, range) ?: return null
        // The actual native click is on the supporting block, not on an arbitrary distant air cell.
        if (eye.toVector().distanceSquared(BuilderPlacementAssistGeometry.faceCenter(support, face)) > range * range) return null
        val end = eye.clone().add(direction.clone().multiply(distance + 1e-5))
        for (x in (floor(min(eye.x, end.x) - 1e-5).toInt() shr 4)..(floor(max(eye.x, end.x) + 1e-5).toInt() shr 4)) {
            for (z in (floor(min(eye.z, end.z) - 1e-5).toInt() shr 4)..(floor(max(eye.z, end.z) + 1e-5).toInt() shr 4)) {
                if (!world.isChunkLoaded(x, z)) return null
            }
        }
        val hit = world.rayTraceBlocks(eye, direction, distance + 1e-5, FluidCollisionMode.NEVER, false)
        if (hit != null && eye.toVector().distanceSquared(hit.hitPosition) + 1e-6 < distance * distance) return null
        val source = world.getBlockAt(support.x, support.y, support.z)
        if (source.type != state.item.type || !fullCube(source)) return null
        val target = world.getBlockAt(next.x, next.y, next.z)
        if (!target.type.isAir) return Target(next, "assist.blocked-occupied")
        if (!canBuild(player, target, state.item.type)) return Target(next, "assist.blocked-protected")
        if (player.boundingBox.overlaps(BoundingBox(next.x.toDouble(), next.y.toDouble(), next.z.toDouble(),
                next.x + 1.0, next.y + 1.0, next.z + 1.0))) return Target(next, "assist.blocked-collision")
        if (tickNumber < state.refusedUntil) return Target(next, "assist.refused")
        return Target(next)
    }

    private fun enqueue(input: BuilderPlacementAssistInput) {
        if (closed) return
        // One removed offer admits one callback. No held-button timer or unbounded input queue.
        taskScope.runSync { accept(input) }
    }

    internal fun accept(input: BuilderPlacementAssistInput) {
        if (closed || System.nanoTime() - input.offer.createdAt > 1_000_000_000L) return
        val player = Bukkit.getPlayer(input.playerId) ?: return
        val state = states[input.playerId] ?: return
        if (state.line !== input.offer.line || !eligible(player, state) || connection(player) !== input.connection ||
            state.slot != input.offer.slot || !state.item.isSimilar(input.offer.item)) return
        val eyeAtInput = player.eyeLocation.apply { yaw = input.yaw; pitch = input.pitch }
        val current = resolve(player, state, player.eyeLocation) ?: return
        if (current.blocked != null || resolve(player, state, eyeAtInput)?.takeIf { it.blocked == null }?.position != current.position) return
        offers.remove(input.playerId)
        val previous = state.line
        try {
            // No future native mutation is queued: toggles, reload and slot/world exits cannot
            // leave a placement waiting on a second network/server handoff.
            val attempt = NativeAttempt(player.uniqueId, current.position)
            nativeAttempt = attempt
            try {
                nativePlacement(player, previous.last, checkNotNull(previous.direction), input.sequence)
            } finally {
                nativeAttempt = null
            }
            if (attempt.placed?.let(::commitPlacement) != true) {
                state.refusedUntil = tickNumber + 20L
                refresh(player, state)
            }
        } catch (failure: Throwable) {
            unavailable = true
            states.keys.toList().forEach(::clearPlayer)
            plugin.logger.log(Level.WARNING, "Placement assist disabled for this runtime: native placement failed for ${player.uniqueId}", failure)
            send(player, "assist.unavailable")
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        val player = event.player
        if (closed || unavailable || !event.canBuild() || event is BlockMultiPlaceEvent || event.hand != EquipmentSlot.HAND ||
            !isEnabled(player) || player.isSneaking || !isContextAllowed(player) || !supported(event.itemInHand) || !fullCube(event.blockPlaced)
        ) return
        val id = player.uniqueId
        val placed = Placement(event, player.inventory.heldItemSlot, event.itemInHand.clone().apply { amount = 1 })
        nativeAttempt?.takeIf { it.playerId == id }?.let { attempt ->
            val block = event.blockPlaced
            if (attempt.target == BuilderBlockPos(block.world.uid, block.x, block.y, block.z)) attempt.placed = placed
            return
        }
        val queue = pendingPlacements[id]
        if (queue != null) {
            // The last two successes establish a direction; keep native packet bursts bounded.
            if (queue.size == 2) queue.removeAt(0)
            queue += placed
        } else {
            val pending = mutableListOf(placed)
            pendingPlacements[id] = pending
            taskScope.runLater(1L) {
                if (!closed && pendingPlacements.remove(id, pending)) pending.forEach(::commitPlacement)
            }
        }
    }

    private fun commitPlacement(placement: Placement): Boolean {
        val event = placement.event
        val player = event.player
        val block = event.blockPlaced
        if (event.isCancelled || !event.canBuild() || !block.world.isChunkLoaded(block.x shr 4, block.z shr 4) ||
            block.type != placement.item.type) return false
        // A consumed final item or context change ends the line, but does not turn an
        // already successful native placement into a refusal.
        if (closed || unavailable || !player.isOnline || player.isDead || player.world != block.world ||
            player.isSneaking || !isEnabled(player) || !isContextAllowed(player) ||
            player.inventory.heldItemSlot != placement.slot || !player.inventory.itemInMainHand.isSimilar(placement.item)
        ) {
            clearPlayer(player.uniqueId)
            return true
        }
        val id = player.uniqueId
        val position = BuilderBlockPos(block.world.uid, block.x, block.y, block.z)
        val previous = states[id]?.takeIf {
            it.slot == placement.slot && it.item.isSimilar(placement.item)
        }
        val line = previous?.line?.placed(position) ?: BuilderPlacementAssistLine(position)
        states[id] = State(line, placement.slot, placement.item)
        offers.remove(id)
        if (line.direction != null && packets == null) {
            try {
                packets = openPackets({ playerId -> if (closed) null else offers.remove(playerId) }, ::enqueue)
            } catch (failure: Throwable) {
                unavailable = true
                states.keys.toList().forEach(::clearPlayer)
                plugin.logger.log(Level.WARNING, "Placement assist disabled for this runtime: PacketEvents input is unavailable", failure)
                send(player, "assist.unavailable")
            }
        }
        return !unavailable
    }

    private fun supported(item: ItemStack): Boolean {
        val type = item.type
        return item.amount > 0 && type.isBlock && type.isSolid && !type.isInteractable &&
            item.isSimilar(ItemStack(type))
    }

    private fun fullCube(block: Block): Boolean {
        val box = block.collisionShape.boundingBoxes.singleOrNull() ?: return false
        return abs(box.minX) < 1e-6 && abs(box.minY) < 1e-6 && abs(box.minZ) < 1e-6 &&
            abs(box.maxX - 1.0) < 1e-6 && abs(box.maxY - 1.0) < 1e-6 && abs(box.maxZ - 1.0) < 1e-6
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSlot(event: PlayerItemHeldEvent) = clearPlayer(event.player.uniqueId)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSwap(event: PlayerSwapHandItemsEvent) = clearPlayer(event.player.uniqueId)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) = clearPlayer(event.player.uniqueId)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSneak(event: PlayerToggleSneakEvent) { if (event.isSneaking) clearPlayer(event.player.uniqueId) }
    @EventHandler fun onWorld(event: PlayerChangedWorldEvent) = clearPlayer(event.player.uniqueId)
    @EventHandler fun onQuit(event: PlayerQuitEvent) = clearPlayer(event.player.uniqueId)
    @EventHandler fun onDeath(event: PlayerDeathEvent) = clearPlayer(event.entity.uniqueId)

    fun clearPlayer(playerId: UUID) {
        states.remove(playerId)
        pendingPlacements.remove(playerId)
        offers.remove(playerId)
        renderer.clearPlacementAssist(playerId)
        clearHud(playerId)
    }

    private fun clearHud(playerId: UUID) {
        if (hud.remove(playerId) != null) Bukkit.getPlayer(playerId)?.sendActionBar(Component.empty())
    }

    private fun showHud(player: Player, key: String) {
        val previous = hud[player.uniqueId]
        if (previous?.key != key || tickNumber - previous.tick >= 20L) {
            hud[player.uniqueId] = Hud(key, tickNumber)
            player.sendActionBar(messages.render(key, BuilderLocalePolicy.localeTag(player)))
        }
    }

    private fun send(player: Player, key: String) = sendMessage(player, messages.render(key, BuilderLocalePolicy.localeTag(player)))

    override fun close() {
        if (closed) return
        closed = true
        packets?.close()
        packets = null
        HandlerList.unregisterAll(this)
        states.keys.toList().forEach(::clearPlayer)
        pendingPlacements.clear()
        offers.clear()
    }
}
