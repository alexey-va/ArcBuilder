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
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import ru.arc.core.LifecycleTaskScope
import ru.arc.text.LocalizedMiniMessage
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

internal data class BuilderPlacementAssistTarget(
    val position: BuilderBlockPos,
    val support: BuilderBlockPos?,
    val face: BlockFace?,
    val blocked: String? = null,
    val row: Boolean = false,
)

internal data class BuilderPlacementAssistOffer(
    val lock: BuilderPlacementAssistLock,
    val slot: Int,
    val item: ItemStack,
    val target: BuilderPlacementAssistTarget,
    val vanillaHit: BuilderPlacementAssistHit?,
    val createdAt: Long = System.nanoTime(),
)

/** Owns persistent preferences and one transient geometric constraint. Ticks never place. */
internal class BuilderPlacementAssist(
    private val plugin: JavaPlugin,
    private val renderer: BuilderDisplayRenderer,
    private val messages: LocalizedMiniMessage,
    private val taskScope: LifecycleTaskScope,
    private val isContextAllowed: (Player) -> Boolean,
    private val canBuild: (Player, Block, Material) -> Boolean,
    private val canUseSupport: (Block) -> Boolean,
    private val sendMessage: (Player, Component) -> Unit = Player::sendMessage,
    private val nativePlacement: (Player, BuilderBlockPos, BlockFace, Int) -> Unit = PaperBuilderPlacementAssistNative::place,
    private val openPackets: ((UUID, BuilderPlacementAssistHit?) -> BuilderPlacementAssistOffer?, (BuilderPlacementAssistInput) -> Unit) -> AutoCloseable = ::BuilderPlacementAssistPackets,
    private val connection: (Player) -> Any? = { PacketEvents.getAPI()?.playerManager?.getChannel(it) },
) : Listener, AutoCloseable {
    private data class State(val lock: BuilderPlacementAssistLock, val slot: Int, val item: ItemStack, var refusedUntil: Long = 0L)
    private data class Resolved(val target: BuilderPlacementAssistTarget, val vanillaHit: BuilderPlacementAssistHit?)
    private data class Placement(val event: BlockPlaceEvent, val slot: Int, val item: ItemStack)
    private class NativeAttempt(val playerId: UUID, val target: BuilderBlockPos) { var placed: Placement? = null }
    private data class Hud(val key: String, val tick: Long)
    private val preferenceKey = NamespacedKey(plugin, "placement_assist")
    private val modeKey = NamespacedKey(plugin, "placement_assist_mode_v2")
    private val states = mutableMapOf<UUID, State>()
    private val offers = ConcurrentHashMap<UUID, BuilderPlacementAssistOffer>()
    private val pendingInputs = ConcurrentHashMap<UUID, BuilderPlacementAssistInput>()
    private val pendingPlacements = mutableMapOf<UUID, MutableList<Placement>>()
    private val attemptedThisTick = mutableSetOf<UUID>()
    private val hud = mutableMapOf<UUID, Hud>()
    private var nativeAttempt: NativeAttempt? = null
    private var tickNumber = 0L
    private var packets: AutoCloseable? = null
    private var unavailable = false
    @Volatile private var closed = false

    init { plugin.server.pluginManager.registerEvents(this, plugin) }

    fun isEnabled(player: Player) = player.persistentDataContainer.get(preferenceKey, PersistentDataType.BYTE) == 1.toByte()
    internal fun mode(player: Player) = when (player.persistentDataContainer.get(modeKey, PersistentDataType.STRING)) {
        "line" -> BuilderPlacementAssistMode.LINE
        "plane" -> BuilderPlacementAssistMode.PLANE
        else -> BuilderPlacementAssistMode.AUTO
    }

    fun command(player: Player, args: List<String>) {
        if (args.size > 1) { send(player, "assist.controls-v3"); return }
        val action = args.firstOrNull()?.lowercase()
        if (action == "reset") {
            clearPlayer(player.uniqueId)
            send(player, "assist.reset")
            if (isEnabled(player)) send(player, hint(player))
            return
        }
        val enabled = when (action) {
            null -> !isEnabled(player)
            "on", "line", "plane" -> true
            "off" -> false
            else -> { send(player, "assist.controls-v3"); return }
        }
        if (enabled) player.persistentDataContainer.set(modeKey, PersistentDataType.STRING,
            if (action == "line" || action == "plane") action else "auto")
        player.persistentDataContainer.set(preferenceKey, PersistentDataType.BYTE, if (enabled) 1.toByte() else 0.toByte())
        clearPlayer(player.uniqueId)
        send(player, if (enabled) "assist.enabled" else "assist.disabled-v2")
        if (enabled) send(player, hint(player))
    }

    private fun hint(player: Player) = when (mode(player)) {
        BuilderPlacementAssistMode.AUTO -> "assist.auto-hint"
        BuilderPlacementAssistMode.LINE -> "assist.line-hint-v2"
        BuilderPlacementAssistMode.PLANE -> "assist.plane-hint-v2"
    }

    fun tick() {
        if (closed) return
        tickNumber++
        attemptedThisTick.clear()
        states.keys.toList().forEach { id ->
            val player = Bukkit.getPlayer(id)
            val state = states[id] ?: return@forEach
            if (player == null || !eligible(player, state)) clearPlayer(id) else refresh(player, state)
        }
    }

    private fun available(player: Player) = !closed && !unavailable && player.isOnline && !player.isDead &&
        isEnabled(player) && isContextAllowed(player) && player.openInventory.type in setOf(InventoryType.CRAFTING, InventoryType.CREATIVE)

    private fun eligible(player: Player, state: State): Boolean = available(player) &&
        player.world.uid == state.lock.anchor.worldId && player.inventory.heldItemSlot == state.slot &&
        player.inventory.itemInMainHand.isSimilar(state.item) && player.inventory.itemInMainHand.amount > 0

    private fun refresh(player: Player, state: State) {
        val id = player.uniqueId
        val resolved = resolve(player, state, player.eyeLocation)
        if (resolved == null) {
            offers.remove(id)
            renderer.clearPlacementAssist(id)
            clearHud(id)
            return
        }
        val target = resolved.target
        renderer.placementAssist(player, target.position, target.blocked != null)
        // An orange cell owns the same aimed gesture: consuming it must not build
        // an unhighlighted vanilla background block or a layer above this plane.
        if (id !in attemptedThisTick && !pendingInputs.containsKey(id))
            offers[id] = BuilderPlacementAssistOffer(state.lock, state.slot, state.item, target, resolved.vanillaHit)
        else offers.remove(id)
        if (renderer.placementAssistReady(player, target.position, target.blocked != null))
            showHud(player, state, target.row, target.blocked ?: if (target.row) "assist.row-ready-v2" else "assist.plane-ready-v2")
        else clearHud(id)
    }

    private fun resolve(player: Player, state: State, eye: Location): Resolved? {
        if (!state.lock.active || eye.world != player.world) return null
        val range = min(8.0, player.getAttribute(Attribute.BLOCK_INTERACTION_RANGE)?.value
            ?: if (player.gameMode == GameMode.CREATIVE) 5.0 else 4.5)
        if (!range.isFinite() || range <= 0) return null
        val eyeVector = eye.toVector()
        val direction = eye.direction
        // Do not replace normal interaction with a chest, machine, sign or another unsafe block.
        if (!loadedPath(eye, eyeVector.clone().add(direction.clone().multiply(range)))) return null
        val vanilla = player.world.rayTraceBlocks(eye, direction, range, FluidCollisionMode.NEVER, false)
        val hitBlock = vanilla?.hitBlock
        if (hitBlock != null && (hitBlock.type.isInteractable || !canUseSupport(hitBlock))) return null
        val vanillaHit = if (hitBlock != null && vanilla.hitBlockFace != null)
            BuilderPlacementAssistHit(hitBlock.x, hitBlock.y, hitBlock.z, vanilla.hitBlockFace!!) else null

        val normal = state.lock.planeNormal
        val mode = mode(player)
        val candidates = buildList {
            if (mode != BuilderPlacementAssistMode.PLANE) state.lock.next?.let { add(it to true) }
            if (mode != BuilderPlacementAssistMode.LINE && normal != null) {
                BuilderPlacementAssistGeometry.projectPlane(eyeVector, direction, state.lock, range)?.let {
                    add(BuilderPlacementAssistGeometry.projectedCell(it, state.lock) to false)
                }
            }
        }
        for ((position, row) in candidates) {
            val entry = BuilderPlacementAssistGeometry.aimDistance(eyeVector, direction, position, range) ?: continue
            // The real gaze must enter this exact cell before any foreground shape.
            // A solid target itself is allowed here so it can show a truthful blocked frame.
            if (vanilla != null && eyeVector.distanceSquared(vanilla.hitPosition) + 1e-6 < entry * entry) continue
            val targetBlock = block(player, position) ?: continue
            // In automatic mode a deliberate click on an existing block must remain
            // ordinary placement, so the player can start another layer without a command.
            if (mode == BuilderPlacementAssistMode.AUTO && !targetBlock.type.isAir && hitBlock != null &&
                hitBlock.x == position.x && hitBlock.y == position.y && hitBlock.z == position.z) return null
            val supportFaces = if (row) listOf(checkNotNull(state.lock.direction)) else {
                val planeNormal = checkNotNull(normal)
                BuilderPlacementAssistGeometry.cardinalFaces.filter { it.modX * planeNormal.modX + it.modY * planeNormal.modY + it.modZ * planeNormal.modZ == 0 }
            }
            val face = supportFaces.firstOrNull { candidate ->
                val supportPosition = position.relative(candidate.oppositeFace)
                val source = block(player, supportPosition)
                source != null && supportAllowed(source) &&
                    (!targetBlock.type.isAir || visibleSupport(eye, supportPosition, candidate, range))
            } ?: continue
            val reason = when {
                !targetBlock.type.isAir -> "assist.occupied-v2"
                !canBuild(player, targetBlock, state.item.type) -> "assist.protected-v2"
                player.boundingBox.overlaps(BoundingBox(position.x.toDouble(), position.y.toDouble(), position.z.toDouble(),
                    position.x + 1.0, position.y + 1.0, position.z + 1.0)) -> "assist.collision-v2"
                tickNumber < state.refusedUntil -> "assist.refused"
                else -> null
            }
            return Resolved(BuilderPlacementAssistTarget(position, position.relative(face.oppositeFace), face, reason, row), vanillaHit)
        }
        return null
    }

    private fun block(player: Player, position: BuilderBlockPos): Block? {
        val world = player.world
        if (position.worldId != world.uid || position.y !in world.minHeight until world.maxHeight ||
            !world.isChunkLoaded(position.x shr 4, position.z shr 4) ||
            !world.worldBorder.isInside(Location(world, position.x + .5, position.y + .5, position.z + .5))) return null
        return world.getBlockAt(position.x, position.y, position.z)
    }

    private fun loadedPath(eye: Location, end: Vector): Boolean {
        val world = eye.world ?: return false
        for (x in (floor(min(eye.x, end.x) - 1e-5).toInt() shr 4)..(floor(max(eye.x, end.x) + 1e-5).toInt() shr 4)) {
            for (z in (floor(min(eye.z, end.z) - 1e-5).toInt() shr 4)..(floor(max(eye.z, end.z) + 1e-5).toInt() shr 4)) {
                if (!world.isChunkLoaded(x, z)) return false
            }
        }
        return true
    }

    private fun visibleSupport(eye: Location, position: BuilderBlockPos, face: BlockFace, range: Double): Boolean {
        val point = BuilderPlacementAssistGeometry.faceCenter(position, face)
        val delta = point.clone().subtract(eye.toVector())
        val distance = delta.length()
        if (distance < 1e-6 || distance > range || !loadedPath(eye, point)) return false
        val hit = eye.world!!.rayTraceBlocks(eye, delta.multiply(1.0 / distance), distance, FluidCollisionMode.NEVER, false) ?: return true
        // Reaching around the support's own edge is useful for bridges; a different
        // block before that support is an obstruction, not an alternative click target.
        val block = hit.hitBlock
        return block != null && block.x == position.x && block.y == position.y && block.z == position.z ||
            eye.toVector().distanceSquared(hit.hitPosition) + 1e-6 >= distance * distance
    }

    private fun admit(playerId: UUID, hit: BuilderPlacementAssistHit?): BuilderPlacementAssistOffer? {
        if (closed) return null
        var admitted: BuilderPlacementAssistOffer? = null
        offers.computeIfPresent(playerId) { _, offer ->
            if (hit == null || offer.vanillaHit == hit) { admitted = offer; null } else offer
        }
        return admitted
    }

    private fun enqueue(input: BuilderPlacementAssistInput) {
        // At most one cancelled packet is awaiting the owning thread for this player.
        if (pendingInputs.putIfAbsent(input.playerId, input) != null) return
        // The transport serializes this registration with close(). Keep a rejected
        // scheduler's record for the main-thread close/reset drain; never use Bukkit here.
        if (!closed) runCatching { taskScope.runSync {
            if (pendingInputs.remove(input.playerId, input)) accept(input)
        } }
    }

    internal fun accept(input: BuilderPlacementAssistInput) {
        val player = Bukkit.getPlayer(input.playerId) ?: return
        var success = false
        try {
            if (closed || System.nanoTime() - input.offer.createdAt > 1_000_000_000L || connection(player) !== input.connection) return
            val state = states[input.playerId] ?: return
            if (state.lock !== input.offer.lock || !eligible(player, state) || input.playerId in attemptedThisTick ||
                state.slot != input.offer.slot || !state.item.isSimilar(input.offer.item)) return
            val current = resolve(player, state, player.eyeLocation) ?: return
            if (current.target != input.offer.target) return
            val eyeAtInput = player.eyeLocation
            if (input.click != null) {
                if (current.vanillaHit != input.click.hit) return
                eyeAtInput.direction = input.click.location().subtract(eyeAtInput.toVector())
            } else {
                eyeAtInput.yaw = input.yaw
                eyeAtInput.pitch = input.pitch
            }
            if (resolve(player, state, eyeAtInput)?.target != current.target) return
            attemptedThisTick += input.playerId
            offers.remove(input.playerId)
            if (current.target.blocked != null) return
            if (!renderer.placementAssistReady(player, current.target.position, false)) {
                clearHud(input.playerId)
                return
            }
            try {
                val attempt = NativeAttempt(player.uniqueId, current.target.position)
                nativeAttempt = attempt
                try {
                    nativePlacement(player, checkNotNull(current.target.support), checkNotNull(current.target.face), input.sequence)
                } finally { nativeAttempt = null }
                success = attempt.placed?.let(::commitPlacement) == true
                if (!success && states[input.playerId] === state) {
                    state.refusedUntil = tickNumber + 20L
                    refresh(player, state)
                }
            } catch (failure: Throwable) {
                unavailable = true
                states.keys.toList().forEach(::clearPlayer)
                plugin.logger.log(Level.WARNING, "Placement assist disabled for this runtime: native placement failed for ${player.uniqueId}", failure)
                send(player, "assist.unavailable")
            }
        } finally {
            correctPrediction(input)
        }
    }

    private fun correctPrediction(input: BuilderPlacementAssistInput) {
        val click = input.click ?: return
        val player = Bukkit.getPlayer(input.playerId) ?: return
        if (!player.isOnline || connection(player) !== input.connection) return
        val worldId = input.offer.lock.anchor.worldId
        if (player.world.uid != worldId) return
        player.updateInventory()
        val clicked = BuilderBlockPos(worldId, click.hit.x, click.hit.y, click.hit.z)
        for (position in listOf(clicked, clicked.relative(click.hit.face))) {
            block(player, position)?.let { player.sendBlockChange(it.location, it.blockData) }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        val player = event.player
        if (!event.canBuild() || event is BlockMultiPlaceEvent || event.hand != EquipmentSlot.HAND ||
            !available(player) || !supported(event.itemInHand) || !fullCube(event.blockPlaced)) return
        val id = player.uniqueId
        val placed = Placement(event, player.inventory.heldItemSlot, event.itemInHand.clone().apply { amount = 1 })
        nativeAttempt?.takeIf { it.playerId == id }?.let { attempt ->
            val block = event.blockPlaced
            if (attempt.target == BuilderBlockPos(block.world.uid, block.x, block.y, block.z)) attempt.placed = placed
            return
        }
        val queue = pendingPlacements[id]
        if (queue != null) {
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
        if (!available(player) || player.world != block.world || player.inventory.heldItemSlot != placement.slot ||
            !player.inventory.itemInMainHand.isSimilar(placement.item)) {
            clearPlayer(player.uniqueId)
            return true
        }
        val id = player.uniqueId
        val position = BuilderBlockPos(block.world.uid, block.x, block.y, block.z)
        val previous = states[id]?.takeIf { it.slot == placement.slot && it.item.isSimilar(placement.item) }
        val against = event.blockAgainst
        val normal = BuilderPlacementAssistGeometry.faceBetween(BuilderBlockPos(against.world.uid, against.x, against.y, against.z), position)
        val lock = previous?.lock?.placed(position, normal) ?: BuilderPlacementAssistLock(position, planeNormal = normal)
        if (previous != null && lock === previous.lock) return true
        states[id] = State(lock, placement.slot, placement.item)
        offers.remove(id)
        if (lock.active && packets == null) {
            try { packets = openPackets(::admit, ::enqueue) }
            catch (failure: Throwable) {
                unavailable = true
                states.keys.toList().forEach(::clearPlayer)
                plugin.logger.log(Level.WARNING, "Placement assist disabled for this runtime: PacketEvents input is unavailable", failure)
                send(player, "assist.unavailable")
            }
        }
        return !unavailable
    }

    private fun supported(item: ItemStack) = item.amount > 0 && item.type.isBlock && item.type.isSolid &&
        !item.type.isInteractable && item.isSimilar(ItemStack(item.type))
    private fun supportAllowed(block: Block) = !block.type.isAir && !block.type.isInteractable && fullCube(block) && canUseSupport(block)
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
    fun onInventory(event: InventoryOpenEvent) { (event.player as? Player)?.let { clearPlayer(it.uniqueId) } }
    @EventHandler fun onWorld(event: PlayerChangedWorldEvent) = clearPlayer(event.player.uniqueId)
    @EventHandler fun onQuit(event: PlayerQuitEvent) = clearPlayer(event.player.uniqueId)
    @EventHandler fun onDeath(event: PlayerDeathEvent) = clearPlayer(event.entity.uniqueId)

    fun clearPlayer(playerId: UUID) {
        states.remove(playerId)
        pendingPlacements.remove(playerId)
        offers.remove(playerId)
        pendingInputs.remove(playerId)?.let(::correctPrediction)
        renderer.clearPlacementAssist(playerId)
        clearHud(playerId)
    }

    private fun clearHud(playerId: UUID) {
        if (hud.remove(playerId) != null) Bukkit.getPlayer(playerId)?.sendActionBar(Component.empty())
    }

    private fun showHud(player: Player, state: State, row: Boolean, key: String) {
        val normal = if (row) null else state.lock.planeNormal
        val orientation = if (row) "assist.direction.${state.lock.direction!!.name.lowercase()}" else
            "assist.plane.${if (normal!!.modY != 0) "floor" else if (normal.modX != 0) "x" else "z"}"
        val coordinate = when {
            normal == null -> 0
            normal.modX != 0 -> state.lock.anchor.x
            normal.modY != 0 -> state.lock.anchor.y
            else -> state.lock.anchor.z
        }
        val hudKey = "$key/$orientation/$coordinate"
        val previous = hud[player.uniqueId]
        if (previous?.key != hudKey || tickNumber - previous.tick >= 20L) {
            hud[player.uniqueId] = Hud(hudKey, tickNumber)
            val locale = BuilderLocalePolicy.localeTag(player)
            player.sendActionBar(messages.render(key, locale, mapOf(
                "orientation" to messages.render(orientation, locale), "coordinate" to Component.text(coordinate))))
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
        pendingInputs.values.toList().forEach(::correctPrediction)
        pendingInputs.clear()
        pendingPlacements.clear()
        offers.clear()
    }
}
