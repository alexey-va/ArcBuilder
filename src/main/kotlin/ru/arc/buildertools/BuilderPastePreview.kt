package ru.arc.buildertools

import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

/** Exact displayed placement; camera movement never changes a locked snapshot. */
internal data class BuilderPastePlacement(
    val clipboard: BuilderClipboard,
    val anchor: BuilderBlockPos,
    val rotation: Int,
) {
    init { require(rotation in setOf(0, 90, 180, 270)) }

    fun targets(): List<Pair<BuilderBlockPos, String>> = relativeBlocks(clipboard, rotation).map { block ->
        BuilderBlockPos(anchor.worldId, Math.addExact(anchor.x, block.dx), Math.addExact(anchor.y, block.dy),
            Math.addExact(anchor.z, block.dz)).validated() to block.blockData
    }

    companion object {
        fun relativeBlocks(clipboard: BuilderClipboard, rotation: Int): List<BuilderClipboardBlock> =
            clipboard.blocks.map { block ->
                val x = block.dx - clipboard.originDx
                val z = block.dz - clipboard.originDz
                val (rx, rz) = when (rotation) {
                    90 -> -z to x
                    180 -> -x to -z
                    270 -> z to -x
                    else -> x to z
                }
                BuilderClipboardBlock(rx, block.dy - clipboard.originDy, rz, block.blockData)
            }
    }
}

/** Built off-thread once per rotation. Aim updates translate only the bounded visible window. */
internal data class BuilderPastePreviewModel(
    val rotation: Int,
    val blocks: List<BuilderClipboardBlock>,
    val points: BuilderPreviewPointSnapshot,
    val bounds: List<BuilderDisplayEdge>,
) {
    companion object {
        fun create(clipboard: BuilderClipboard, rotation: Int): BuilderPastePreviewModel {
            val blocks = BuilderPastePlacement.relativeBlocks(clipboard, rotation)
            return BuilderPastePreviewModel(rotation, blocks,
                BuilderPreviewPointSnapshot.copyOf(blocks.map { BuilderPreviewPoint(it.dx + .5, it.dy + .5, it.dz + .5) }),
                BuilderDisplayGeometry.bounds(blocks.map { BuilderBlockPos(UUID(0, 0), it.dx, it.dy, it.dz) }))
        }
    }
}

/** Owns moving clipboard input, displayed-pose admission and lifecycle, never world mutation. */
internal class BuilderPastePreview(
    private val clipboard: BuilderClipboardController,
    private val renderer: BuilderDisplayRenderer,
    private val tasks: BuilderPlanningTasks,
    private val target: (Player) -> BuilderBlockPos?,
    private val allowed: (Player) -> Boolean,
    private val onLock: (Player, BuilderPastePlacement, () -> Boolean) -> Unit,
    private val onClear: (UUID, UUID?) -> Unit,
    private val onFailure: (Player, Throwable) -> Unit,
    private val hint: (Player, String) -> Unit,
    private val currentTick: () -> Int = Bukkit::getCurrentTick,
) : AutoCloseable {
    private enum class Stage { MOVING, PLANNING, FIXED }
    private class Session(
        val player: Player,
        val source: BuilderClipboard,
        val worldId: UUID,
        val gameMode: GameMode,
        val slot: Int,
        val item: ItemStack,
        val startedTick: Int,
        var rotation: Int,
    ) {
        var stage = Stage.MOVING
        var anchor: BuilderBlockPos? = null
        var displayed: BuilderPastePlacement? = null
        var locked: BuilderPastePlacement? = null
        var planId: UUID? = null
        var model: BuilderPastePreviewModel? = null
        var calculating = false
        var lastHintTick = startedTick
    }

    private val sessions = mutableMapOf<UUID, Session>()
    private var closed = false

    fun isActive(playerId: UUID): Boolean = playerId in sessions
    fun capturesInput(playerId: UUID): Boolean = sessions[playerId]?.stage?.let { it != Stage.FIXED } == true

    fun begin(player: Player) {
        check(!closed)
        clear(player.uniqueId)
        val anchor = target(player)
        // The placeholder only captures facing when no ray target exists; it is never displayed or planned.
        val source = clipboard.placement(player, anchor ?: BuilderBlockPos(player.world.uid, 0, 0, 0))
        val session = Session(player, source.clipboard, player.world.uid, player.gameMode,
            player.inventory.heldItemSlot, player.inventory.itemInMainHand.clone(), currentTick(), source.rotation)
        session.anchor = anchor
        sessions[player.uniqueId] = session
        hint(player, "clipboard.moving")
        prepareModel(session)
    }

    fun tick() {
        sessions.values.toList().forEach { session ->
            if (!current(session)) {
                clear(session.player.uniqueId)
                return@forEach
            }
            if (session.stage != Stage.MOVING) return@forEach
            val anchor = target(session.player)
            if (anchor != session.anchor) {
                session.anchor = anchor
                if (anchor == null) {
                    session.displayed = null
                    renderer.clearPaste(session.player.uniqueId)
                } else show(session)
            }
            if (currentTick() - session.lastHintTick >= 20) {
                session.lastHintTick = currentTick()
                hint(session.player, if (anchor == null) "clipboard.aim" else "clipboard.moving-actionbar")
            }
        }
    }

    /** Returns true for every consumed hand gesture, including loading and the original panel click. */
    fun click(player: Player, mainHand: Boolean): Boolean {
        val session = sessions[player.uniqueId] ?: return false
        if (session.stage == Stage.FIXED) return false
        if (!current(session)) { clear(player.uniqueId); return true }
        if (!mainHand || session.stage != Stage.MOVING || currentTick() == session.startedTick) return true
        val displayed = session.displayed ?: return true
        lock(session, displayed)
        return true
    }

    fun rotate(player: Player, delta: Int): Boolean {
        val session = sessions[player.uniqueId] ?: return false
        require(delta % 90 == 0)
        if (!current(session)) { clear(player.uniqueId); return false }
        clipboard.rotate(player.uniqueId, delta)
        session.rotation = ((session.rotation + delta) % 360 + 360) % 360
        if (session.stage == Stage.MOVING) prepareModel(session)
        else lock(session, checkNotNull(session.locked).copy(rotation = session.rotation))
        return true
    }

    fun prepared(playerId: UUID, placement: BuilderPastePlacement, planId: UUID) {
        val session = sessions[playerId] ?: return
        if (session.locked !== placement || !current(session)) return
        session.planId = planId
        session.stage = Stage.FIXED
        renderer.fixedPaste(session.player, placement.anchor)
    }

    fun clear(playerId: UUID): Boolean {
        val session = sessions.remove(playerId) ?: return false
        renderer.clearPaste(playerId)
        onClear(playerId, session.planId)
        return true
    }

    private fun current(session: Session): Boolean {
        val player = session.player
        return !closed && sessions[player.uniqueId] === session && player.isOnline && !player.isDead &&
            player.world.uid == session.worldId && player.gameMode == session.gameMode &&
            player.inventory.heldItemSlot == session.slot && player.inventory.itemInMainHand == session.item &&
            clipboard.current(player.uniqueId) === session.source && allowed(player)
    }

    private fun prepareModel(session: Session) {
        if (session.calculating || session.stage != Stage.MOVING) return
        if (session.model?.rotation == session.rotation) { show(session); return }
        session.calculating = true
        val rotation = session.rotation
        tasks.compute { BuilderPastePreviewModel.create(session.source, rotation) }.whenComplete { model, failure ->
            tasks.onMain {
                session.calculating = false
                if (!current(session) || session.stage != Stage.MOVING) return@onMain
                if (failure != null) {
                    clear(session.player.uniqueId)
                    onFailure(session.player, failure)
                } else if (rotation == session.rotation) {
                    session.model = model
                    show(session)
                } else prepareModel(session)
            }
        }
    }

    private fun show(session: Session) {
        val anchor = session.anchor ?: return
        val model = session.model ?: return
        if (model.rotation != session.rotation) return
        renderer.paste(session.player, model, anchor) { displayedAnchor ->
            if (current(session) && session.stage == Stage.MOVING) {
                session.displayed = BuilderPastePlacement(session.source, displayedAnchor, model.rotation)
            }
        }
    }

    private fun lock(session: Session, placement: BuilderPastePlacement) {
        session.stage = Stage.PLANNING
        session.locked = placement
        session.rotation = placement.rotation
        renderer.freezePaste(session.player.uniqueId)
        hint(session.player, "clipboard.locked")
        try {
            onLock(session.player, placement) { current(session) && session.locked === placement }
        } catch (failure: Throwable) {
            clear(session.player.uniqueId)
            throw failure
        }
    }

    override fun close() {
        if (closed) return
        sessions.keys.toList().forEach(::clear)
        closed = true
    }
}
