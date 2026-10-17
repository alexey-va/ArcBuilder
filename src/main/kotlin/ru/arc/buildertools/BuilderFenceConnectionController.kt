package ru.arc.buildertools

import org.bukkit.block.BlockFace
import org.bukkit.block.data.MultipleFacing
import org.bukkit.block.data.type.Wall
import org.bukkit.entity.Player
import java.util.concurrent.CompletableFuture

/** Builds a one-shot plan that clears every connected side of vanilla fences and walls in the selection. */
internal class BuilderFenceConnectionController(
    private val maximumChanges: Int,
    private val host: BuilderPlanningHost,
) {
    init {
        require(maximumChanges in 1..BuilderPlan.ABSOLUTE_MAX_CHANGES) {
            "Builder fence connection maximum changes must stay inside the absolute plan bound"
        }
    }

    fun planDisconnect(player: Player, isCurrent: () -> Boolean = { true }): CompletableFuture<BuilderPlan> {
        host.ensurePermission(player, BuilderFeature.FENCE_DISCONNECT)
        val selection = host.requiredSelection(player)
        val world = host.world(selection.worldId)
        val gameMode = player.gameMode
        val current = { isCurrent() && player.isOnline && player.world.uid == selection.worldId &&
            player.gameMode == gameMode && host.requiredSelection(player) == selection }
        val changes = mutableListOf<BuilderBlockChange>()
        val positions = selection.positionsBottomUp().iterator()

        return host.capture(current) {
            if (!positions.hasNext()) return@capture true
            val position = positions.next()
            if (!world.isChunkLoaded(position.x shr 4, position.z shr 4)) host.fail("errors.chunk-unloaded")
            val block = world.getBlockAt(position.x, position.y, position.z)
            val before = block.blockData
            val after = when {
                before is Wall -> {
                    val faces = listOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)
                    if (faces.all { before.getHeight(it) == Wall.Height.NONE } && before.isUp) return@capture false
                    (before.clone() as Wall).also { wall ->
                        faces.forEach { wall.setHeight(it, Wall.Height.NONE) }
                        wall.isUp = true
                    }
                }
                block.type.name.endsWith("_FENCE") && before is MultipleFacing -> {
                    if (before.faces.isEmpty()) return@capture false
                    (before.clone() as MultipleFacing).also { fence ->
                        before.faces.forEach { face -> fence.setFace(face, false) }
                    }
                }
                else -> return@capture false
            }
            host.ensureMutable(player, block)
            changes += BuilderBlockChange(position, before.asString, after.asString)
            if (changes.size > maximumChanges) host.fail("errors.selection-too-large")
            false
        }.thenCompose {
            requireCurrentBuilderPlanning(current)
            if (changes.isEmpty()) host.fail("errors.nothing-to-change")
            host.createPlanAsync(player, BuilderPlanKind.FENCE_DISCONNECT, changes)
        }
    }
}
