package ru.arc.buildertools.display

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.RayTraceResult
import org.bukkit.util.Vector
import org.joml.Matrix4f
import org.joml.Vector3d
import org.joml.Vector3f
import ru.arc.buildertools.BuilderMaterialArguments
import ru.arc.paper.menu.DialogTextLayout
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Level
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.round

/**
 * Permanent, owner-tagged decorations, with a separate private draft and no item economy.
 * All entry points run on Paper's primary thread. The runtime owns calling [tick] and [close].
 */
internal class BuilderDisplayEditor(
    private val plugin: JavaPlugin,
    private val text: (Player, String, Map<String, String>) -> Component,
    private val canBuild: (Player, Block) -> Boolean,
    private val dialogs: PaperDialogRuntime = PaperDialogRuntime(plugin),
    private val traceBlocks: (World, Location, Vector, Double) -> RayTraceResult? = { world, eye, direction, range ->
        world.rayTraceBlocks(eye, direction, range, FluidCollisionMode.NEVER, true)
    },
    private val spawnDisplay: (World, Location, Class<out Display>, (Display) -> Unit) -> Display = { world, at, type, configure ->
        world.spawn(at, type) { configure(it) }
    },
    private val axes: DisplayEditorAxes = DisplayEditorAxes(plugin),
) : Listener, AutoCloseable {
    internal enum class Kind { BLOCK, ITEM, TEXT }

    internal data class Content(
        val kind: Kind,
        val shape: DisplayEditorShape,
        val block: String? = null,
        val item: ItemStack? = null,
        val text: String? = null,
    ) {
        fun mesh() = shape.mesh
        fun centre() = shape.centre()
    }

    internal data class Snapshot(val world: UUID, val owner: UUID, val pose: DisplayEditorPose, val content: Content)
    private data class Undo(val entityId: UUID?, val before: Snapshot?, val after: Snapshot?)
    private class Drag(
        val sourceId: UUID?, val before: Snapshot?, var draft: Snapshot, val preview: Display,
        var baseline: DisplayEditorPose, var distance: Double, var grabOffset: Vector3d,
        var previousYaw: Float, var previousPitch: Float,
        var yaw: Double = 0.0, var pitch: Double = 0.0, var wheel: Double = 0.0,
        val axisHandle: Boolean = false,
        var axisGesture: DisplayEditorAxisGesture? = null,
        var axisDelta: Double = 0.0,
        var axisPaused: Boolean = false,
    )
    private class Session {
        var enabled = false
        var heldSlot = -1
        var heldItem: ItemStack? = null
        var selected: UUID? = null
        var drag: Drag? = null
        var mode = DisplayEditorMode.MOVE
        var axis = DisplayEditorAxis.FREE
        var pendingAxis: DisplayEditorAxis? = null
        var hoveredAxis: DisplayEditorAxis? = null
        val undo = ArrayDeque<Undo>()
        var revision = 0L
        var lastAction = 0L
        var lastClick = -1L
    }

    private val marker = NamespacedKey(plugin, "display_editor")
    private val ownerKey = NamespacedKey(plugin, "display_owner")
    private val previewKey = NamespacedKey(plugin, "display_draft")
    private val sessions = linkedMapOf<UUID, Session>()
    private val locks = DisplayEditorLocks()
    // Initialize the bundled vanilla model inventory before accepting any interaction.
    private val shapes = DisplayEditorShapes
    private var ticks = 0L
    private var closed = false

    init { plugin.server.pluginManager.registerEvents(this, plugin) }

    fun isEditing(player: Player): Boolean = !closed && sessions[player.uniqueId]?.enabled == true
    fun hasSession(player: Player): Boolean = !closed && player.uniqueId in sessions

    fun command(player: Player, args: List<String>): Boolean {
        if (!allowed(player)) { forget(player); tell(player, "no-permission"); return true }
        val action = args.firstOrNull()?.lowercase(Locale.ROOT).orEmpty()
        if (action == "exit") { forget(player); tell(player, "exited"); return true }
        val session = session(player) ?: return true
        when (action) {
            "", "menu" -> openMenu(player, session, true)
            "tool", "select" -> if (enable(player, session)) { dialogs.close(player); tell(player, "entered") }
            "block" -> createBlock(player, session, args.getOrNull(1))
            "item" -> createItem(player, session, args.getOrNull(1))
            "text" -> if (args.size > 1) createText(player, session, args.drop(1).joinToString(" ")) else openText(player, session, false)
            "move", "rotate", "scale" -> setMode(player, session, DisplayEditorMode.valueOf(action.uppercase(Locale.ROOT)))
            "axis" -> args.getOrNull(1)?.let { raw -> DisplayEditorAxis.entries.firstOrNull { it.name.equals(raw, true) } }
                ?.let { setAxis(player, session, it) } ?: openAxes(player, session, begin = true)
            "confirm" -> commit(player, session)
            "cancel" -> cancel(player, session)
            "undo" -> undo(player, session)
            "duplicate" -> duplicate(player, session)
            "delete" -> openDelete(player, session)
            else -> tell(player, "command-help")
        }
        return true
    }

    fun tabComplete(args: List<String>): List<String> {
        val options = when {
            args.size <= 1 -> listOf("menu", "tool", "block", "item", "text", "move", "rotate", "scale", "axis", "confirm", "cancel", "undo", "duplicate", "delete", "exit")
            args.size == 2 && args.first().equals("axis", true) -> DisplayEditorAxis.entries.map { it.name.lowercase() }
            args.size == 2 && args.first() in listOf("block", "item") -> MATERIALS.filter {
                if (args.first() == "block") it.isBlock && !it.isAir else it.isItem && !it.isAir
            }.map { it.name.lowercase() }
            else -> emptyList()
        }
        return options.filter { it.startsWith(args.lastOrNull().orEmpty(), true) }.take(80)
    }

    /** One cheap capture per editor; footprint protection scans occur only at commit, never per frame. */
    fun tick() {
        if (closed) return
        ticks++
        sessions.toMap().forEach { (id, session) ->
            val player = Bukkit.getPlayer(id)
            if (player == null) { forget(id); return@forEach }
            if (!allowed(player) || ticks - session.lastAction > SESSION_TTL) { forget(player); return@forEach }
            if (!session.enabled) return@forEach
            if (!holding(player, session)) { forget(player); return@forEach }
            val selected = session.selected?.let(::entity)
            val selectedState = selected?.let(::capture)
            if (session.selected != null && (selected == null || !mayEdit(player, selected) || !centreAccessible(player, selectedState))) {
                forget(player); tell(player, "access-lost"); return@forEach
            }
            val drag = session.drag
            if (drag != null) {
                if (!drag.preview.isValid || !centreAccessible(player, drag.draft) ||
                    drag.before?.let { !centreAccessible(player, it) } == true) {
                    cancel(player, session); tell(player, "access-lost"); return@forEach
                }
                updateDrag(player, session, drag)
            }
            val snapshot = session.drag?.draft ?: selectedState
            session.hoveredAxis = if (session.drag == null && selectedState != null) axisHit(player, session, selectedState)?.axis else null
            val frame = snapshot?.let { DisplayEditorAxesGeometry.frame(it.pose, it.content.mesh(), session.axis, session.hoveredAxis) }
                ?: cursorAxes(player, session.axis)
            if (frame == null) axes.clear(id) else axes.update(player, frame)
            if (ticks % 4L == 0L) {
                hud(player, session)
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onInteract(event: PlayerInteractEvent) {
        if (!isEditing(event.player)) return
        event.isCancelled = true
        if (event.hand != EquipmentSlot.HAND) return
        when (event.action) {
            Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK -> primary(event.player)
            Action.LEFT_CLICK_AIR, Action.LEFT_CLICK_BLOCK -> secondary(event.player)
            else -> Unit
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onInteractEntity(event: PlayerInteractEntityEvent) {
        if (!isEditing(event.player)) return
        event.isCancelled = true
        if (event.hand == EquipmentSlot.HAND) primary(event.player)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val player = event.damager as? Player ?: return
        if (!isEditing(player)) return
        event.isCancelled = true
        secondary(player)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onSwap(event: PlayerSwapHandItemsEvent) {
        val player = event.player
        val session = sessions[player.uniqueId]?.takeIf { it.enabled } ?: return
        event.isCancelled = true
        if (!allowed(player) || !holding(player, session)) { forget(player); return }
        if (player.isSneaking) openAxes(player, session, begin = true)
        else setMode(player, session, DisplayEditorMode.entries[(session.mode.ordinal + 1) % DisplayEditorMode.entries.size])
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onWheel(event: PlayerItemHeldEvent) {
        val session = sessions[event.player.uniqueId]?.takeIf { it.enabled } ?: return
        val drag = session.drag
        if (drag == null) { forget(event.player); return }
        event.isCancelled = true
        val delta = ((event.newSlot - event.previousSlot + 13) % 9) - 4
        val direction = if (delta > 0) 1 else -1
        if (session.mode == DisplayEditorMode.MOVE && (!drag.axisHandle || session.axis == DisplayEditorAxis.FREE)) drag.distance = (drag.distance + direction * .25).coerceIn(.5, 10.0)
        else drag.wheel += direction
        session.lastAction = ticks
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDrop(event: PlayerDropItemEvent) {
        val session = sessions[event.player.uniqueId]?.takeIf { it.enabled } ?: return
        event.isCancelled = true
        openMenu(event.player, session, true)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) { forget(event.player) }
    @EventHandler fun onWorld(event: PlayerChangedWorldEvent) { forget(event.player) }
    @EventHandler fun onQuit(event: PlayerQuitEvent) { forget(event.player) }
    @EventHandler fun onDeath(event: PlayerDeathEvent) { forget(event.entity) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventory(event: InventoryOpenEvent) { (event.player as? Player)?.takeIf(::isEditing)?.let(::forget) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onChunkUnload(event: ChunkUnloadEvent) {
        sessions.toMap().forEach { (id, session) ->
            val relevant = listOfNotNull(session.drag?.preview, session.selected?.let(::entity))
            if (relevant.any { it.world.uid == event.world.uid && it.location.blockX shr 4 == event.chunk.x && it.location.blockZ shr 4 == event.chunk.z }) forget(id)
        }
    }

    private fun primary(player: Player) {
        val session = sessions[player.uniqueId] ?: return
        if (session.lastClick == ticks) return
        session.lastClick = ticks
        if (!allowed(player) || !holding(player, session)) { forget(player); return }
        session.lastAction = ticks
        if (session.drag != null) { commit(player, session); return }
        val selected = session.selected?.let(::entity)?.takeIf { mayEdit(player, it) && locks.heldBy(it.uniqueId, player.uniqueId) }
        val selectedState = selected?.let(::capture)
        if (selectedState != null && centreAccessible(player, selectedState)) {
            val handle = axisHit(player, session, selectedState)
            if (handle != null) {
                if (!accessible(player, selectedState)) { tell(player, "no-access"); return }
                session.axis = handle.axis
                startDrag(player, session, selectedState, selected.uniqueId, selectedState, handle.distance, axisHandle = true)
                return
            }
        }
        val picked = pick(player) ?: run { tell(player, "no-target"); return }
        if (!mayEdit(player, picked.first)) { tell(player, "not-owner"); return }
        if (!locks.available(picked.first.uniqueId, player.uniqueId)) { tell(player, "locked"); return }
        val before = capture(picked.first) ?: return
        if (!accessible(player, before)) { tell(player, "no-access"); return }
        deselect(player, session)
        locks.acquire(picked.first.uniqueId, player.uniqueId)
        session.selected = picked.first.uniqueId
        startDrag(player, session, before, picked.first.uniqueId, before, picked.second)
    }

    private fun secondary(player: Player) {
        val session = sessions[player.uniqueId] ?: return
        if (session.lastClick == ticks) return
        session.lastClick = ticks
        cancel(player, session)
    }

    private fun startDrag(player: Player, session: Session, snapshot: Snapshot, sourceId: UUID?, before: Snapshot?, hitDistance: Double? = null, axisHandle: Boolean = false) {
        if (!enable(player, session)) return
        cancelDraft(player, session)
        if (!centreAccessible(player, snapshot) || before?.let { !centreAccessible(player, it) } == true) { tell(player, "no-access"); return }
        // A handle constrains only its own gesture. Explicit menu/command choices
        // apply once; ordinary model grabs return to free XYZ afterwards.
        session.axis = if (axisHandle) session.axis else session.pendingAxis ?: DisplayEditorAxis.FREE
        session.pendingAxis = null
        val eye = player.eyeLocation
        val distance = (hitDistance ?: snapshot.pose.position().distance(eye.toVector().toVector3d())).coerceIn(.5, 10.0)
        val preview = spawn(snapshot, true) ?: run { tell(player, "failed"); return }
        player.showEntity(plugin, preview)
        sourceId?.let(::entity)?.let { player.hideEntity(plugin, it) }
        session.drag = Drag(
            sourceId, before, snapshot, preview, snapshot.pose, distance,
            snapshot.pose.position().sub(eye.toVector().toVector3d().fma(distance, eye.direction.toVector3d())),
            eye.yaw, eye.pitch,
            axisHandle = axisHandle,
            axisGesture = if (axisHandle) DisplayEditorGeometry.axisGesture(snapshot.pose, session.axis, eye.toVector().toVector3d(), eye.direction.toVector3d()) else null,
        )
        session.hoveredAxis = null
        session.lastAction = ticks
        dialogs.close(player)
        hud(player, session)
    }

    private fun updateDrag(player: Player, session: Session, drag: Drag) {
        val eye = player.eyeLocation
        drag.yaw += DisplayEditorGeometry.yawDelta(drag.previousYaw, eye.yaw)
        drag.pitch += eye.pitch - drag.previousPitch
        drag.previousYaw = eye.yaw
        drag.previousPitch = eye.pitch
        val directAxis = drag.axisHandle && session.axis != DisplayEditorAxis.FREE && session.mode != DisplayEditorMode.ROTATE
        if (directAxis) {
            if (drag.axisGesture == null) {
                DisplayEditorGeometry.axisGesture(drag.draft.pose, session.axis, eye.toVector().toVector3d(), eye.direction.toVector3d())?.let {
                    // Recover from a head-on view without applying an accumulated cursor jump.
                    drag.baseline = drag.draft.pose
                    drag.wheel = 0.0
                    drag.axisDelta = 0.0
                    drag.axisGesture = it
                }
            }
            var displacement = drag.axisGesture?.let { DisplayEditorGeometry.axisDisplacement(it, eye.toVector().toVector3d(), eye.direction.toVector3d()) }
            if (drag.axisPaused && displacement != null) {
                drag.baseline = drag.draft.pose
                drag.wheel = 0.0
                drag.axisDelta = 0.0
                drag.axisGesture = DisplayEditorGeometry.axisGesture(drag.baseline, session.axis, eye.toVector().toVector3d(), eye.direction.toVector3d())
                displacement = 0.0
            }
            drag.axisPaused = displacement == null
            if (displacement != null) drag.axisDelta = displacement
        } else drag.axisPaused = false
        val pose = when (session.mode) {
            DisplayEditorMode.MOVE -> {
                var next = if (directAxis) drag.baseline.at(drag.baseline.position().fma(drag.axisDelta + drag.wheel * .25, DisplayEditorGeometry.axisVector(session.axis)))
                else drag.draft.pose.at(DisplayEditorGeometry.constrain(
                    eye.toVector().toVector3d().fma(drag.distance, eye.direction.toVector3d()).add(drag.grabOffset),
                    drag.baseline.position(), session.axis,
                ))
                if (player.isSneaking && !directAxis) trace(player)?.let { hit -> hit.hitBlockFace?.let { face ->
                    next = DisplayEditorGeometry.onSurface(next, drag.draft.content.mesh(), hit.hitPosition.toVector3d(), face.direction.toVector3d())
                } }
                next
            }
            DisplayEditorMode.ROTATE -> {
                fun angle(degrees: Double): Float = Math.toRadians(if (player.isSneaking) round(degrees / 15) * 15 else degrees).toFloat()
                val primary = angle((if (session.axis == DisplayEditorAxis.X) drag.pitch else -drag.yaw) + drag.wheel * 5)
                val rotation = when (session.axis) {
                    DisplayEditorAxis.FREE -> Matrix4f().rotateY(primary).rotateX(angle(drag.pitch))
                    DisplayEditorAxis.X -> Matrix4f().rotateX(primary)
                    DisplayEditorAxis.Y -> Matrix4f().rotateY(primary)
                    DisplayEditorAxis.Z -> Matrix4f().rotateZ(primary)
                }
                drag.baseline.withMatrix(rotation.mul(drag.baseline.matrix()))
            }
            DisplayEditorMode.SCALE -> {
                var factor = if (directAxis) ((1 + drag.axisDelta / (drag.axisGesture?.scaleSpan ?: 1.0)).coerceAtLeast(.01) * exp((drag.wheel * .05).coerceIn(-4.0, 4.0))).toFloat()
                    else exp((-drag.pitch * .025 + drag.wheel * .05).coerceIn(-4.0, 4.0)).toFloat()
                if (player.isSneaking) factor = (round(factor * 10) / 10).coerceAtLeast(.1f)
                DisplayEditorGeometry.worldScale(drag.baseline, session.axis, factor)
            }
        }
        val next = drag.draft.copy(pose = pose)
        if (!DisplayEditorGeometry.valid(pose, next.content.mesh()) || !worldSafe(player, next)) return
        if (!centreAccessible(player, next)) {
            cancelDraft(player, session); tell(player, "no-access"); return
        }
        if (samePose(drag.draft.pose, pose)) return
        if (applyPose(drag.preview, next)) { drag.draft = next; session.lastAction = ticks }
    }

    private fun commit(player: Player, session: Session) {
        val drag = session.drag ?: run { tell(player, "nothing-to-confirm"); return }
        val source = drag.sourceId?.let(::entity)
        if (!canCommit(player, session, drag.sourceId, source, drag.before, drag.draft)) {
            cancelDraft(player, session); tell(player, "stale-or-denied"); return
        }
        val saved = if (source == null) spawn(drag.draft, false) else source.takeIf { applyPose(it, drag.draft) }
        if (saved == null) { tell(player, "failed"); return }
        val after = capture(saved) ?: run { tell(player, "failed"); return }
        remember(session, Undo(saved.uniqueId, drag.before, after))
        cancelDraft(player, session)
        locks.release(player.uniqueId)
        locks.acquire(saved.uniqueId, player.uniqueId)
        session.selected = saved.uniqueId
        tell(player, "saved")
    }

    private fun canCommit(player: Player, session: Session, sourceId: UUID?, source: Display?, before: Snapshot?, after: Snapshot): Boolean =
        allowed(player) && holding(player, session) &&
            (sourceId == null || (session.selected == sourceId && source != null && mayEdit(player, source) &&
                locks.heldBy(sourceId, player.uniqueId) && same(capture(source), before))) &&
            before?.let { accessible(player, it) } != false && accessible(player, after)

    private fun createBlock(player: Player, session: Session, materialName: String?) {
        val material = materialName?.let(BuilderMaterialArguments::parse) ?: player.inventory.itemInMainHand.type
        if (material.isAir || !material.isBlock || material.isLegacy) { tell(player, "need-block"); return }
        create(player, session, blockContent(material.createBlockData()))
    }

    private fun createItem(player: Player, session: Session, materialName: String?) {
        val stack = if (materialName == null) player.inventory.itemInMainHand.clone() else {
            val material = BuilderMaterialArguments.parse(materialName)
            if (material == null || !material.isItem || material.isAir || material.isLegacy) { tell(player, "need-item"); return }
            ItemStack(material)
        }
        if (stack.type.isAir) { tell(player, "need-item"); return }
        stack.amount = 1
        create(player, session, itemContent(stack))
    }

    private fun createText(player: Player, session: Session, raw: String) {
        val value = validatedText(raw) ?: run { tell(player, "invalid-text"); return }
        create(player, session, textContent(value))
    }

    private fun create(player: Player, session: Session, content: Content) {
        if (!enable(player, session)) return
        if (!rayReady(player)) { tell(player, "no-access"); return }
        deselect(player, session)
        session.mode = DisplayEditorMode.MOVE
        session.axis = DisplayEditorAxis.FREE
        session.pendingAxis = null
        val eye = player.eyeLocation
        val point = eye.toVector().toVector3d().fma(3.0, eye.direction.toVector3d())
        var pose = DisplayEditorPose(point.x, point.y, point.z)
        if (content.kind == Kind.TEXT) pose = pose.withMatrix(Matrix4f().rotateY(Math.toRadians(-eye.yaw.toDouble()).toFloat()))
        trace(player)?.let { hit -> hit.hitBlockFace?.let { face ->
            pose = DisplayEditorGeometry.onSurface(pose, content.mesh(), hit.hitPosition.toVector3d(), face.direction.toVector3d())
        } }
        startDrag(player, session, Snapshot(player.world.uid, player.uniqueId, pose, content), null, null)
    }

    private fun duplicate(player: Player, session: Session) {
        val source = selected(player, session) ?: return
        val snapshot = capture(source) ?: return
        if (!accessible(player, snapshot)) { tell(player, "no-access"); return }
        startDrag(player, session, snapshot.copy(owner = player.uniqueId), null, null)
    }

    private fun undo(player: Player, session: Session) {
        cancelDraft(player, session)
        val change = session.undo.lastOrNull() ?: run { tell(player, "nothing-to-undo"); return }
        val current = change.entityId?.let(::entity)
        if (!allowed(player) || change.entityId?.let { !locks.available(it, player.uniqueId) } == true ||
            (change.after == null && current != null) ||
            change.after?.let { current == null || !mayEdit(player, current) || !same(capture(current), it) || !accessible(player, it) } == true ||
            change.before?.let { !DisplayEditorAuthority.mayEdit(true, it.owner, player.uniqueId, allowed(player), player.hasPermission(ADMIN)) || !accessible(player, it) } == true) {
            tell(player, "stale-or-denied"); return
        }
        val restored = when {
            change.before == null -> { current?.remove(); null }
            current == null -> spawn(change.before, false) ?: run { tell(player, "failed"); return }
            else -> {
                if (!applyPose(current, change.before)) { tell(player, "failed"); return }
                applyContent(current, change.before.content)
                current
            }
        }
        session.undo.removeLast()
        if (current == null && restored != null && change.entityId != null) {
            val earlier = session.undo.map { if (it.entityId == change.entityId) it.copy(entityId = restored.uniqueId) else it }
            session.undo.clear()
            session.undo.addAll(earlier)
        }
        deselect(player, session)
        if (restored != null) { locks.acquire(restored.uniqueId, player.uniqueId); session.selected = restored.uniqueId }
        tell(player, "undone")
    }

    private fun openMenu(player: Player, session: Session, begin: Boolean = false) {
        cancelDraft(player, session)
        if (begin) dialogs.beginFlow(player)
        val selected = session.selected?.let(::entity)?.takeIf { mayEdit(player, it) }
        show(player, session, { fresh -> DisplayEditorMenu.root(
            { tr(player, it) }, session.mode, selected != null, selected is TextDisplay, session.undo.isNotEmpty(),
            selected?.let(::capture)?.let { selectionLabel(player, it) },
        ) { action -> if (fresh()) when (action) {
            "block" -> createBlock(player, session, null)
            "item" -> createItem(player, session, null)
            "text" -> openText(player, session, false)
            "edit_text" -> openText(player, session, true)
            "move", "rotate", "scale" -> setMode(player, session, DisplayEditorMode.valueOf(action.uppercase()))
            "axis" -> openAxes(player, session)
            "enter" -> if (enable(player, session)) { dialogs.close(player); hud(player, session) }
            "exit" -> { forget(player); tell(player, "exited") }
            "duplicate" -> duplicate(player, session)
            "delete" -> openDelete(player, session)
            "undo" -> { undo(player, session); openMenu(player, session) }
        } } }, reopen = { openMenu(player, session) })
    }

    private fun openAxes(player: Player, session: Session, begin: Boolean = false, scaleDraft: DisplayEditorScale? = null, feedback: String? = null) {
        cancelDraft(player, session)
        if (begin) dialogs.beginFlow(player)
        val target = session.selected?.let(::entity)?.takeIf { mayEdit(player, it) && locks.heldBy(it.uniqueId, player.uniqueId) }
        val before = target?.let(::capture)
        val scale = before?.let { scaleDraft ?: DisplayEditorGeometry.scale(it.pose) }
        show(player, session, { fresh ->
            DisplayEditorMenu.axes(
                { tr(player, it) }, session.axis, scale, before?.let { selectionLabel(player, it) }, feedback?.let { tr(player, it) },
                chooseAxis = { axis, values -> if (fresh()) {
                    setAxis(player, session, axis)
                    openAxes(player, session, scaleDraft = values ?: scale)
                } },
                applyScale = { values -> if (fresh() && target != null && before != null) {
                    applyMenuPose(player, session, target, before, values?.let { DisplayEditorGeometry.withScale(before.pose, it) })
                } },
                reset = { rotation -> if (fresh() && target != null && before != null) {
                    val pose = if (rotation) DisplayEditorGeometry.withoutRotation(before.pose)
                        else DisplayEditorGeometry.withScale(before.pose, DisplayEditorScale(1f, 1f, 1f))
                    applyMenuPose(player, session, target, before, pose)
                } },
            )
        })
    }

    private fun applyMenuPose(player: Player, session: Session, target: Display, before: Snapshot, pose: DisplayEditorPose?) {
        if (pose == null) { openAxes(player, session, feedback = "invalid-scale"); return }
        val after = before.copy(pose = pose)
        if (!canCommit(player, session, target.uniqueId, target, before, after)) {
            openAxes(player, session, feedback = "stale-or-denied"); return
        }
        if (!samePose(before.pose, pose)) {
            if (!applyPose(target, after)) { openAxes(player, session, feedback = "failed"); return }
            remember(session, Undo(target.uniqueId, before, capture(target)))
        }
        openAxes(player, session, feedback = "saved")
    }

    private fun openText(player: Player, session: Session, editing: Boolean, initial: String? = null, invalid: Boolean = false) {
        cancelDraft(player, session)
        val target = if (editing) selected(player, session)?.takeIf { it is TextDisplay } ?: return else null
        val before = target?.let(::capture)
        show(player, session, { fresh -> DisplayEditorMenu.textForm({ tr(player, it) }, initial ?: before?.content?.text.orEmpty(), editing, invalid) { raw ->
            if (!fresh()) return@textForm
            val value = validatedText(raw) ?: run { openText(player, session, editing, raw, true); return@textForm }
            if (target == null) createText(player, session, value)
            else {
                val after = before!!.copy(content = textContent(value))
                if (!mayEdit(player, target) || !locks.heldBy(target.uniqueId, player.uniqueId) || !same(capture(target), before) || !accessible(player, before) || !accessible(player, after)) {
                    tell(player, "stale-or-denied"); openMenu(player, session); return@textForm
                }
                if (!applyPose(target, after)) { tell(player, "failed"); return@textForm }
                applyContent(target, after.content)
                remember(session, Undo(target.uniqueId, before, capture(target)))
                tell(player, "saved")
                openMenu(player, session)
            }
        } })
    }

    private fun openDelete(player: Player, session: Session) {
        cancelDraft(player, session)
        val target = selected(player, session) ?: return
        val before = capture(target) ?: return
        show(player, session, { fresh -> DisplayEditorMenu.deletion({ tr(player, it) }, selectionLabel(player, before)) {
            if (!fresh()) return@deletion
            if (!mayEdit(player, target) || !locks.heldBy(target.uniqueId, player.uniqueId) || !same(capture(target), before) || !accessible(player, before)) {
                tell(player, "stale-or-denied"); openMenu(player, session); return@deletion
            }
            target.remove()
            remember(session, Undo(target.uniqueId, before, null))
            deselect(player, session)
            tell(player, "deleted")
            openMenu(player, session)
        } })
    }

    private fun show(player: Player, session: Session, screen: ((() -> Boolean) -> PaperDialogScreen), reopen: (() -> Unit)? = null) {
        val revision = ++session.revision
        session.lastAction = ticks
        dialogs.open(player, screen { !closed && sessions[player.uniqueId] === session && session.revision == revision && allowed(player) }, reopen, {})
    }

    private fun setMode(player: Player, session: Session, mode: DisplayEditorMode) {
        if (!enable(player, session)) return
        session.mode = mode
        session.axis = DisplayEditorAxis.FREE
        session.pendingAxis = null
        rebase(player, session)
        dialogs.close(player)
        hud(player, session)
    }

    private fun setAxis(player: Player, session: Session, axis: DisplayEditorAxis) {
        if (!allowed(player)) return
        session.axis = axis
        session.pendingAxis = axis.takeIf { session.drag == null && it != DisplayEditorAxis.FREE }
        session.lastAction = ticks
        rebase(player, session)
        hud(player, session)
    }

    private fun rebase(player: Player, session: Session) {
        session.drag?.let { drag ->
            val eye = player.eyeLocation
            drag.baseline = drag.draft.pose
            drag.yaw = 0.0; drag.pitch = 0.0; drag.wheel = 0.0
            drag.previousYaw = eye.yaw; drag.previousPitch = eye.pitch
            drag.grabOffset = drag.draft.pose.position().sub(eye.toVector().toVector3d().fma(drag.distance, eye.direction.toVector3d()))
            drag.axisDelta = 0.0
            drag.axisPaused = false
            drag.axisGesture = if (drag.axisHandle) DisplayEditorGeometry.axisGesture(drag.baseline, session.axis, eye.toVector().toVector3d(), eye.direction.toVector3d()) else null
        }
    }

    private fun session(player: Player): Session? {
        sessions[player.uniqueId]?.let { it.lastAction = ticks; return it }
        if (sessions.size >= MAX_SESSIONS) { tell(player, "busy"); return null }
        return Session().also { it.lastAction = ticks; sessions[player.uniqueId] = it }
    }

    private fun enable(player: Player, session: Session): Boolean {
        if (!allowed(player)) { forget(player); return false }
        val held = player.inventory.itemInMainHand
        if (held.type.isAir) { tell(player, "need-hand"); return false }
        session.enabled = true
        session.heldSlot = player.inventory.heldItemSlot
        session.heldItem = held.clone()
        session.lastAction = ticks
        player.setMetadata(INPUT_METADATA, FixedMetadataValue(plugin, true))
        return true
    }

    private fun holding(player: Player, session: Session): Boolean =
        player.inventory.heldItemSlot == session.heldSlot && session.heldItem?.isSimilar(player.inventory.itemInMainHand) == true

    private fun allowed(player: Player) = !closed && player.isOnline && !player.isDead && player.hasPermission(PERMISSION)
    private fun entity(id: UUID) = Bukkit.getEntity(id) as? Display
    private fun mayEdit(player: Player, display: Display): Boolean = display.isValid && DisplayEditorAuthority.mayEdit(
        display.persistentDataContainer.get(marker, PersistentDataType.BYTE) == 1.toByte(),
        owner(display), player.uniqueId, allowed(player), player.hasPermission(ADMIN),
    )
    private fun owner(display: Display): UUID? = display.persistentDataContainer.get(ownerKey, PersistentDataType.STRING)
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private fun selected(player: Player, session: Session): Display? = session.selected?.let(::entity)
        ?.takeIf { mayEdit(player, it) && locks.heldBy(it.uniqueId, player.uniqueId) }
        .also { if (it == null) tell(player, "select-first") }

    private fun axisHit(player: Player, session: Session, state: Snapshot): DisplayEditorAxisHit? {
        val selectedId = session.selected ?: return null
        if (session.drag != null || !locks.heldBy(selectedId, player.uniqueId) || !rayReady(player)) return null
        val eye = player.eyeLocation
        val origin = eye.toVector().toVector3d()
        val direction = eye.direction.toVector3d()
        val obstruction = minOf(
            trace(player)?.hitPosition?.distance(eye.toVector()) ?: DisplayEditorGeometry.RANGE,
            DisplayEditorGeometry.rayDistance(origin, direction, state.pose, state.content.mesh()) ?: DisplayEditorGeometry.RANGE,
        )
        // Derive picking from the stable frame, never the enlarged hover frame.
        return DisplayEditorAxesGeometry.pick(DisplayEditorAxesGeometry.frame(state.pose, state.content.mesh(), session.axis), origin, direction, obstruction)
    }

    private fun pick(player: Player): Pair<Display, Double>? {
        if (!rayReady(player)) return null
        val eye = player.eyeLocation
        val maxDistance = trace(player)?.hitPosition?.distance(eye.toVector())?.plus(.015) ?: DisplayEditorGeometry.RANGE
        // ponytail: one native AABB query per click is indivisible; refuse dense scenes before transforming candidates.
        val nearby = player.world.getNearbyEntitiesByType(Display::class.java, eye, DisplayEditorGeometry.RANGE + DisplayEditorGeometry.MAX_EXTENT)
        if (nearby.size > MAX_CANDIDATES) { tell(player, "crowded"); return null }
        return nearby.asSequence().filter {
            it.persistentDataContainer.get(marker, PersistentDataType.BYTE) == 1.toByte()
        }.mapNotNull { display ->
            val state = capture(display) ?: return@mapNotNull null
            DisplayEditorGeometry.rayDistance(eye.toVector().toVector3d(), eye.direction.toVector3d(), state.pose, state.content.mesh())
                ?.takeIf { it <= maxDistance }?.let { display to it }
        }.minByOrNull { it.second }
    }

    private fun trace(player: Player): RayTraceResult? {
        if (!rayReady(player)) return null
        val eye = player.eyeLocation
        return traceBlocks(player.world, eye, eye.direction, DisplayEditorGeometry.RANGE)
    }

    private fun rayReady(player: Player): Boolean {
        val eye = player.eyeLocation
        val end = eye.clone().add(eye.direction.multiply(DisplayEditorGeometry.RANGE))
        return eye.world == player.world && listOf(eye, end).all {
            it.y >= player.world.minHeight && it.y < player.world.maxHeight && player.world.worldBorder.isInside(it)
        } && DisplayEditorGeometry.rayChunksLoaded(eye.toVector().toVector3d(), eye.direction.toVector3d()) { x, z -> player.world.isChunkLoaded(x, z) }
    }

    private fun worldSafe(player: Player, snapshot: Snapshot): Boolean {
        if (player.world.uid != snapshot.world || !DisplayEditorGeometry.valid(snapshot.pose, snapshot.content.mesh())) return false
        val world = player.world
        val eye = player.eyeLocation.toVector().toVector3d()
        return DisplayEditorGeometry.corners(snapshot.pose, snapshot.content.mesh()).all { point ->
            point.distanceSquared(eye) <= MAX_REACH_SQUARED && point.y >= world.minHeight && point.y < world.maxHeight &&
                abs(point.x) <= 29_999_984 && abs(point.z) <= 29_999_984 &&
                world.isChunkLoaded(floor(point.x).toInt() shr 4, floor(point.z).toInt() shr 4) &&
                world.worldBorder.isInside(Location(world, point.x, point.y, point.z))
        }
    }

    private fun centreAccessible(player: Player, snapshot: Snapshot?): Boolean {
        if (snapshot == null || !allowed(player) || !worldSafe(player, snapshot)) return false
        val pose = snapshot.pose
        return runCatching { canBuild(player, player.world.getBlockAt(floor(pose.x).toInt(), floor(pose.y).toInt(), floor(pose.z).toInt())) }.getOrDefault(false)
    }

    private fun accessible(player: Player, snapshot: Snapshot): Boolean {
        if (!allowed(player) || !worldSafe(player, snapshot)) return false
        val cells = DisplayEditorGeometry.cells(snapshot.pose, snapshot.content.mesh()) ?: return false
        return cells.all { (x, y, z) ->
            player.world.isChunkLoaded(x shr 4, z shr 4) && runCatching { canBuild(player, player.world.getBlockAt(x, y, z)) }.getOrDefault(false)
        }
    }

    internal fun capture(display: Display): Snapshot? {
        val owner = owner(display) ?: return null
        if (display.persistentDataContainer.get(marker, PersistentDataType.BYTE) != 1.toByte() || display.billboard != Display.Billboard.FIXED) return null
        val content = when (display) {
            is BlockDisplay -> blockContent(display.block)
            is ItemDisplay -> {
                if (display.itemDisplayTransform != ItemDisplay.ItemDisplayTransform.NONE) return null
                itemContent(display.itemStack.clone())
            }
            is TextDisplay -> {
                val plain = PlainTextComponentSerializer.plainText().serialize(display.text())
                if (validatedText(plain) != plain || display.lineWidth != TEXT_LINE_WIDTH) return null
                if (display.text() != Component.text(plain)) return null
                textContent(plain)
            }
            else -> return null
        }
        val at = display.location
        val native = display.transformation
        val matrix = Matrix4f().rotateY(Math.toRadians(-at.yaw.toDouble()).toFloat()).rotateX(Math.toRadians(at.pitch.toDouble()).toFloat())
            .translate(native.translation).rotate(native.leftRotation).scale(native.scale).rotate(native.rightRotation).translate(content.centre())
        val centre = matrix.getTranslation(Vector3f())
        matrix.setTranslation(0f, 0f, 0f)
        val pose = DisplayEditorPose(at.x + centre.x, at.y + centre.y, at.z + centre.z).withMatrix(matrix)
        if (!DisplayEditorGeometry.valid(pose, content.mesh())) return null
        return Snapshot(display.world.uid, owner, pose, content)
    }

    private fun spawn(snapshot: Snapshot, preview: Boolean): Display? = runCatching {
        val world = Bukkit.getWorld(snapshot.world) ?: return null
        val pose = snapshot.pose
        if (!world.isChunkLoaded(floor(pose.x).toInt() shr 4, floor(pose.z).toInt() shr 4)) return null
        val type = when (snapshot.content.kind) { Kind.BLOCK -> BlockDisplay::class.java; Kind.ITEM -> ItemDisplay::class.java; Kind.TEXT -> TextDisplay::class.java }
        spawnDisplay(world, Location(world, pose.x, pose.y, pose.z), type) { display ->
            display.isPersistent = !preview
            display.isVisibleByDefault = !preview
            display.isInvulnerable = true
            display.setGravity(false)
            display.billboard = Display.Billboard.FIXED
            display.interpolationDuration = if (preview) 1 else 0
            display.teleportDuration = if (preview) 1 else 0
            display.viewRange = 1f
            if (preview) display.persistentDataContainer.set(previewKey, PersistentDataType.BYTE, 1.toByte())
            else {
                display.persistentDataContainer.set(marker, PersistentDataType.BYTE, 1.toByte())
                display.persistentDataContainer.set(ownerKey, PersistentDataType.STRING, snapshot.owner.toString())
            }
            applyContent(display, snapshot.content)
            display.setTransformationMatrix(snapshot.pose.matrix().translate(snapshot.content.centre().negate()))
        }
    }.onFailure { plugin.logger.log(Level.WARNING, "Display editor spawn failed", it) }.getOrNull()

    private fun applyPose(display: Display, state: Snapshot): Boolean {
        val world = Bukkit.getWorld(state.world) ?: return false
        val pose = state.pose
        if (!display.teleport(Location(world, pose.x, pose.y, pose.z))) return false
        display.interpolationDelay = 0
        display.setTransformationMatrix(pose.matrix().translate(state.content.centre().negate()))
        return true
    }

    private fun applyContent(display: Display, content: Content) {
        when (display) {
            is BlockDisplay -> display.block = Bukkit.createBlockData(requireNotNull(content.block))
            is ItemDisplay -> { display.setItemStack(requireNotNull(content.item).clone()); display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE }
            is TextDisplay -> {
                display.text(Component.text(content.text.orEmpty()))
                display.lineWidth = TEXT_LINE_WIDTH
                display.isShadowed = true
                display.isSeeThrough = false
                display.isDefaultBackground = false
                display.backgroundColor = Color.fromARGB(120, 18, 23, 30)
                display.alignment = TextDisplay.TextAlignment.CENTER
            }
        }
    }

    private fun blockContent(data: BlockData) = Content(Kind.BLOCK, shapes.block(data), block = data.asString)
    private fun itemContent(stack: ItemStack) = Content(Kind.ITEM, shapes.item(stack), item = stack)
    private fun textContent(value: String): Content {
        val lines = value.split('\n')
        val pixels = lines.maxOfOrNull { line -> line.sumOf { glyphWidth(it) } } ?: 1
        val mesh = DisplayEditorMesh((pixels + 2) * .025, lines.size * .25, .04)
        return Content(Kind.TEXT, DisplayEditorShape(mesh, .0125f, (mesh.height / 2).toFloat(), 0f), text = value)
    }

    private fun cursorAxes(player: Player, axis: DisplayEditorAxis): DisplayEditorAxesFrame? {
        if (!rayReady(player)) return null
        val eye = player.eyeLocation
        val distance = trace(player)?.hitPosition?.distance(eye.toVector())?.minus(.8)?.coerceIn(.5, 3.0) ?: 3.0
        val point = eye.toVector().toVector3d().fma(distance, eye.direction.toVector3d())
        if (point.y < player.world.minHeight || point.y >= player.world.maxHeight ||
            !player.world.worldBorder.isInside(Location(player.world, point.x, point.y, point.z))) return null
        return DisplayEditorAxesGeometry.frame(DisplayEditorPose(point.x, point.y, point.z), null, axis)
    }

    private fun hud(player: Player, session: Session) {
        val drag = session.drag
        val key = when {
            drag == null -> if (session.hoveredAxis != null) "hud-axis-hover" else "hud-idle"
            drag.axisPaused -> "hud-axis-paused"
            drag.axisHandle && session.axis != DisplayEditorAxis.FREE && session.mode != DisplayEditorMode.ROTATE -> "hud-${session.mode.name.lowercase()}-axis"
            else -> "hud-${session.mode.name.lowercase()}"
        }
        player.sendActionBar(tr(player, key, mapOf(
            "mode" to plain(tr(player, "mode-name-${session.mode.name.lowercase()}")),
            "axis" to plain(tr(player, "axis-name-${(session.hoveredAxis ?: session.axis).name.lowercase()}")),
        )))
    }

    private fun cancel(player: Player, session: Session) {
        session.pendingAxis = null
        session.axis = DisplayEditorAxis.FREE
        if (session.drag != null) { cancelDraft(player, session); tell(player, "cancelled") }
        else { deselect(player, session); tell(player, "deselected") }
        session.lastAction = ticks
    }

    private fun cancelDraft(player: Player, session: Session) {
        val drag = session.drag ?: return
        session.drag = null
        session.axis = DisplayEditorAxis.FREE
        drag.preview.remove()
        drag.sourceId?.let(::entity)?.let { player.showEntity(plugin, it) }
    }

    private fun deselect(player: Player, session: Session) {
        cancelDraft(player, session)
        locks.release(player.uniqueId)
        session.selected = null
        session.hoveredAxis = null
    }

    private fun forget(player: Player) {
        player.removeMetadata(INPUT_METADATA, plugin)
        axes.clear(player.uniqueId)
        val session = sessions.remove(player.uniqueId) ?: return
        deselect(player, session)
        session.revision++
        dialogs.close(player)
        player.sendActionBar(Component.empty())
    }

    private fun forget(id: UUID) {
        Bukkit.getPlayer(id)?.let { forget(it); return }
        axes.clear(id)
        sessions.remove(id)?.drag?.preview?.remove()
        locks.release(id)
    }

    private fun remember(session: Session, change: Undo) {
        if (session.undo.size == MAX_UNDO) session.undo.removeFirst()
        session.undo.addLast(change)
        session.lastAction = ticks
    }

    private fun tr(player: Player, key: String, values: Map<String, String> = emptyMap()) =
        text(player, "display-editor.$key", values).decoration(TextDecoration.ITALIC, false)
    private fun selectionLabel(player: Player, state: Snapshot) = tr(player, "selected-object", mapOf(
        "kind" to plain(tr(player, "kind-${state.content.kind.name.lowercase()}")),
        "x" to String.format(Locale.ROOT, "%.1f", state.pose.x),
        "y" to String.format(Locale.ROOT, "%.1f", state.pose.y),
        "z" to String.format(Locale.ROOT, "%.1f", state.pose.z),
    ))
    private fun tell(player: Player, key: String) = player.sendMessage(tr(player, key))

    override fun close() { closeAsync() }

    fun closeAsync(): CompletableFuture<Void> {
        if (closed) return axes.closeAsync()
        sessions.keys.toList().forEach(::forget)
        closed = true
        locks.clear()
        dialogs.close()
        HandlerList.unregisterAll(this)
        return axes.closeAsync()
    }

    internal companion object {
        const val INPUT_METADATA = "arcbuilder-display-editor"
        const val PERMISSION = "arcbuild.display"
        const val ADMIN = "arcbuild.display.admin"
        const val TEXT_LINE_WIDTH = 1024
        private const val MAX_SESSIONS = 16
        private const val MAX_CANDIDATES = 256
        private const val MAX_UNDO = 20
        private const val SESSION_TTL = 20L * 60 * 10
        private const val MAX_REACH_SQUARED = 20.0 * 20.0
        private val MATERIALS = Material.entries.filter { !it.isLegacy }
        private fun plain(component: Component) = PlainTextComponentSerializer.plainText().serialize(component)
        private fun glyphWidth(char: Char): Int = runCatching { DialogTextLayout.glyphWidth(char) + 1 }.getOrDefault(9)

        internal fun validatedText(raw: String): String? {
            val value = raw.replace("\\n", "\n").trim()
            if (value.isBlank() || value.length > 120 || value.split('\n').size > 4 || value.any { it != '\n' && (it.isISOControl() || it.isSurrogate() || Character.getType(it) == Character.PRIVATE_USE.toInt()) }) return null
            if (value.split('\n').any { line -> line.sumOf { glyphWidth(it) } > 240 }) return null
            return value
        }

        internal fun samePose(first: DisplayEditorPose, second: DisplayEditorPose): Boolean =
            first.position().distanceSquared(second.position()) < 1e-10 &&
                first.linear.zip(second.linear).all { (a, b) -> abs(a - b) < 1e-5 }
        private fun same(first: Snapshot?, second: Snapshot?): Boolean = first != null && second != null &&
            first.world == second.world && first.owner == second.owner && first.content == second.content && samePose(first.pose, second.pose)
    }
}
