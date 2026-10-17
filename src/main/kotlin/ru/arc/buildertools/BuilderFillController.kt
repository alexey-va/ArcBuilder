package ru.arc.buildertools

import org.bukkit.Material
import org.bukkit.entity.Player
import java.util.concurrent.CompletableFuture

/**
 * Captures native fill targets in bounded server-thread slices.
 *
 * Planning never changes the world. It records only safe replaceable targets,
 * checks Lands/range/border access through [BuilderPlanningHost.ensureMutable], and
 * charges exact vanilla items in survival while keeping creative free.
 */
internal class BuilderFillController(
    private val safety: BuilderBlockSafety,
    private val maximumChanges: Int,
    private val host: BuilderPlanningHost,
) {
    init {
        require(maximumChanges in 1..BuilderPlan.ABSOLUTE_MAX_CHANGES) {
            "Builder fill maximum changes must stay inside the absolute plan bound"
        }
    }

    fun plan(player: Player, material: Material, isCurrent: () -> Boolean = { true }): CompletableFuture<BuilderPlan> {
        host.ensurePermission(player, BuilderFeature.FILL)
        val after = host.placementData(material)
        val selection = host.requiredSelection(player)
        val world = host.world(selection.worldId)
        val gameMode = player.gameMode
        val current = { isCurrent() && player.isOnline && player.world.uid == selection.worldId &&
            player.gameMode == gameMode && host.requiredSelection(player) == selection }
        val changes = mutableListOf<BuilderBlockChange>()
        val positions = selection.positionsBottomUp().iterator()
        val afterString = after.asString
        val cost = if (BuilderGameModePolicy.usesInventory(gameMode)) {
            BuilderItemCodec.PrototypeCache().capture(checkNotNull(BuilderPlacementCost.itemOrNull(after)))
        } else null

        return host.capture(current) {
            if (!positions.hasNext()) return@capture true
            val position = positions.next()
            if (!world.isChunkLoaded(position.x shr 4, position.z shr 4)) host.fail("errors.chunk-unloaded")
            val block = world.getBlockAt(position.x, position.y, position.z)
            val before = block.blockData.asString
            if (before == afterString || !safety.isReplaceable(block)) return@capture false
            host.ensurePlacement(player, block, after.material)
            changes += BuilderBlockChange(position, before, afterString)
            if (changes.size > maximumChanges) host.fail("errors.selection-too-large")
            false
        }.thenCompose {
            requireCurrentBuilderPlanning(current)
            if (changes.isEmpty()) host.fail("errors.nothing-to-change")
            val costs = cost?.let { listOf(it.copy(amount = Math.multiplyExact(it.amount, changes.size))) }.orEmpty()
            host.createPlanAsync(player, BuilderPlanKind.FILL, changes, costs)
        }
    }
}
