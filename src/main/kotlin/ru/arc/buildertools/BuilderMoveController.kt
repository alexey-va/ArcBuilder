package ru.arc.buildertools

import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import java.util.concurrent.CompletableFuture
import kotlin.math.abs

internal interface BuilderMoveHost : BuilderPlanningHost {
    fun createMovePlanAsync(
        player: Player,
        changes: List<BuilderBlockChange>,
        deconstruction: BuilderDeconstructionCapture?,
        displaced: List<BuilderBlockPos>,
        skippedUnsafeBlocks: Int,
    ): CompletableFuture<BuilderPlan>
}

/** Captures a move once and combines source removal with placement, including overlap. */
internal class BuilderMoveController(
    private val safety: BuilderBlockSafety,
    private val maximumChanges: Int,
    private val host: BuilderMoveHost,
    private val deconstruction: BuilderDeconstructionController,
) {
    private data class SourceBlock(val data: String, val material: Material, val pair: BuilderBookCapturedCell)
    private data class CapturedMove(
        val source: Map<BuilderBlockPos, SourceBlock>,
        val targets: List<Pair<BuilderBlockPos, SourceBlock>>,
        val before: MutableMap<BuilderBlockPos, String>,
        val skipped: Int,
        val excludedSource: Set<BuilderBlockPos>,
        val displaced: MutableList<BuilderBlockPos> = mutableListOf(),
        val deconstructBefore: MutableMap<BuilderBlockPos, String> = linkedMapOf(),
    )

    init {
        require(maximumChanges in 1..BuilderPlan.ABSOLUTE_MAX_CHANGES)
    }

    fun plan(
        player: Player,
        direction: BlockFace,
        isCurrent: () -> Boolean = { true },
    ): CompletableFuture<BuilderPlan> {
        require(direction in BuilderPlacementAssistGeometry.cardinalFaces)
        host.ensurePermission(player, BuilderFeature.COPY)
        host.ensurePermission(player, BuilderFeature.PASTE)
        val selection = host.requiredSelection(player)
        val world = host.world(selection.worldId)
        val gameMode = player.gameMode
        val current = { isCurrent() && player.isOnline && player.world.uid == selection.worldId &&
            player.gameMode == gameMode && host.requiredSelection(player) == selection }
        val source = linkedMapOf<BuilderBlockPos, SourceBlock>()
        val positions = selection.positionsBottomUp().iterator()
        var skippedUnsafe = 0

        fun checkedBlock(position: BuilderBlockPos): org.bukkit.block.Block {
            if (position.y !in world.minHeight until world.maxHeight) host.fail("errors.protection")
            if (!world.isChunkLoaded(position.x shr 4, position.z shr 4)) host.fail("errors.chunk-unloaded")
            return world.getBlockAt(position.x, position.y, position.z)
        }

        return host.capture(current) {
            if (!positions.hasNext()) return@capture true
            val position = positions.next()
            val block = checkedBlock(position)
            if (block.type.isAir) return@capture false
            if (!safety.isSafeExisting(block)) {
                if (++skippedUnsafe > BuilderPlan.ABSOLUTE_MAX_CHANGES) host.fail("errors.selection-too-large")
                return@capture false
            }
            host.ensureMutable(player, block)
            val data = block.blockData
            source[position] = SourceBlock(data.asString, data.material,
                BuilderBookMultiBlockPolicy.capture(BuilderBookPlannedCell(position, data, BuilderBookPlacementResult.Unchanged), null))
            if (source.size > maximumChanges) host.fail("errors.selection-too-large")
            false
        }.thenCompose {
            if (source.isEmpty()) host.fail("errors.nothing-to-change")
            host.compute {
                val rejected = BuilderBookMultiBlockPolicy.rejectedCaptured(source.values.map { it.pair })
                val movable = source.filterKeys { it !in rejected }
                val skipped = skippedUnsafe + source.size - movable.size
                if (skipped > BuilderPlan.ABSOLUTE_MAX_CHANGES) host.fail("errors.selection-too-large")
                if (movable.isEmpty()) host.fail("errors.nothing-to-change")
                CapturedMove(movable, movable.map { (position, block) -> position.relative(direction).validated() to block },
                    movable.mapValuesTo(linkedMapOf()) { it.value.data }, skipped, source.keys - movable.keys)
            }
        }.thenCompose { captured -> host.onMain {
            val targetIterator = captured.targets.iterator()
            host.capture(current) {
                if (!targetIterator.hasNext()) return@capture true
                val (position, moved) = targetIterator.next()
                val block = checkedBlock(position)
                // An excluded unsafe source remains an obstacle; safe occupied targets use DECONSTRUCT.
                if (position !in captured.source && !block.type.isAir) {
                    if (position in captured.excludedSource || !safety.isSafeExisting(block) && !safety.isReplaceable(block)) {
                        host.fail("errors.move-occupied")
                    }
                    if (captured.displaced.isEmpty()) host.ensurePermission(player, BuilderFeature.DECONSTRUCT)
                    captured.displaced += position
                    if (!safety.isReplaceable(block)) captured.deconstructBefore[position] = block.blockData.asString
                }
                host.ensurePlacement(player, block, moved.material)
                if (position !in captured.before) captured.before[position] = block.blockData.asString
                false
            }.thenCompose {
                val materials = if (captured.deconstructBefore.isEmpty()) CompletableFuture.completedFuture(null)
                    else deconstruction.captureBlocks(player, captured.deconstructBefore, current)
                materials.thenCompose { deconstructed -> host.compute {
                    val after = captured.source.mapValuesTo(linkedMapOf()) { AIR }
                    captured.targets.forEach { (position, moved) -> after[position] = moved.data }
                    val displaced = captured.displaced.toSet()
                    captured.before.mapNotNull { (position, data) ->
                        val moved = after.getValue(position)
                        if (data == moved && position !in displaced) null else BuilderBlockChange(position, data, moved)
                    }.also { changes ->
                        if (changes.size > maximumChanges) host.fail("errors.selection-too-large")
                    }.let { Triple(it, deconstructed, captured) }
                } }
            }
        }.thenCompose { it } }.thenCompose { (changes, deconstructed, captured) -> host.onMain {
            requireCurrentBuilderPlanning(current)
            if (changes.isEmpty()) host.fail("errors.nothing-to-change")
            host.createMovePlanAsync(player, changes, deconstructed, captured.displaced, captured.skipped)
        }.thenCompose { it } }
    }

    companion object {
        private const val AIR = "minecraft:air"

        fun lookDirection(direction: Vector): BlockFace {
            require(direction.x.isFinite() && direction.y.isFinite() && direction.z.isFinite() && direction.lengthSquared() > 0.0)
            return when {
                abs(direction.y) > abs(direction.x) && abs(direction.y) > abs(direction.z) ->
                    if (direction.y > 0.0) BlockFace.UP else BlockFace.DOWN
                abs(direction.x) > abs(direction.z) -> if (direction.x > 0.0) BlockFace.EAST else BlockFace.WEST
                else -> if (direction.z > 0.0) BlockFace.SOUTH else BlockFace.NORTH
            }
        }
    }
}

internal fun BuilderSelection.shifted(direction: BlockFace) =
    BuilderSelection(first.relative(direction).validated(), second.relative(direction).validated())
