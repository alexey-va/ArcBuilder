package ru.arc.buildertools

import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * Main-thread owner of bounded fill planning.
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

    fun plan(player: Player, material: Material): BuilderPlan {
        host.ensurePermission(player, BuilderFeature.FILL)
        val after = host.placementData(material)
        val selection = host.requiredSelection(player)
        val world = host.world(selection.worldId)
        val changes = mutableListOf<BuilderBlockChange>()

        selection.positionsBottomUp().forEach { position ->
            val block = world.getBlockAt(position.x, position.y, position.z)
            if (block.blockData.asString == after.asString || !safety.isReplaceable(block)) return@forEach
            host.ensurePlacement(player, block, after.material)
            changes += BuilderBlockChange(position, block.blockData.asString, after.asString)
            if (changes.size > maximumChanges) host.fail("errors.selection-too-large")
        }

        if (changes.isEmpty()) host.fail("errors.nothing-to-change")
        val costs = if (BuilderGameModePolicy.usesInventory(player.gameMode)) {
            BuilderItemCodec.aggregate(listOf(checkNotNull(BuilderPlacementCost.itemOrNull(after)).also {
                it.amount *= changes.size
            }))
        } else {
            emptyList()
        }
        return host.createPlan(player, BuilderPlanKind.FILL, changes, costs)
    }
}
