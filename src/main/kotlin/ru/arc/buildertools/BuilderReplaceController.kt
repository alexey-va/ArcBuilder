package ru.arc.buildertools

import org.bukkit.Material
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.type.Bed
import org.bukkit.block.data.type.Door
import org.bukkit.block.data.type.Stairs
import org.bukkit.block.data.type.TrapDoor
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/** Plans exact-material replacement without mutating the world during the scan. */
internal class BuilderReplaceController(
    private val safety: BuilderBlockSafety,
    private val maximumChanges: Int,
    private val host: BuilderPlanningHost,
) {
    init {
        require(maximumChanges in 1..BuilderPlan.ABSOLUTE_MAX_CHANGES) {
            "Builder replace maximum changes must stay inside the absolute plan bound"
        }
    }

    fun plan(player: Player, source: Material, target: Material): BuilderPlan {
        host.ensurePermission(player, BuilderFeature.REPLACE)
        if (source == target) host.fail("errors.nothing-to-change")
        val targetTemplate = host.placementData(target)
        if (isCoupledMultiBlock(targetTemplate)) host.fail("errors.material")
        val selection = host.requiredSelection(player)
        val world = host.world(selection.worldId)
        val usesInventory = BuilderGameModePolicy.usesInventory(player.gameMode)
        val changes = mutableListOf<BuilderBlockChange>()
        val costs = mutableListOf<ItemStack>()
        val rewards = mutableListOf<ItemStack>()
        var skippedUnsafe = 0

        selection.positionsBottomUp().forEach { position ->
            val block = world.getBlockAt(position.x, position.y, position.z)
            if (block.type != source) return@forEach
            val before = block.blockData
            if (!safety.isSafeExisting(block) || isCoupledMultiBlock(before)) {
                skippedUnsafe += 1
                return@forEach
            }
            val after = targetTemplate.clone()
            BuilderCompatibleBlockState.copy(before, after)
            if (!safety.isSafePlacement(after)) {
                skippedUnsafe += 1
                return@forEach
            }
            val cost = BuilderPlacementCost.itemOrNull(after)
            val reward = BuilderPlacementCost.itemOrNull(before)
            if (usesInventory && (cost == null || reward == null)) {
                skippedUnsafe += 1
                return@forEach
            }
            if (before.asString == after.asString) return@forEach
            host.ensurePlacement(player, block, after.material)
            changes += BuilderBlockChange(position, before.asString, after.asString)
            if (usesInventory) {
                costs += checkNotNull(cost)
                rewards += checkNotNull(reward)
            }
            if (changes.size > maximumChanges) host.fail("errors.selection-too-large")
        }

        if (changes.isEmpty()) host.fail("errors.nothing-to-change")
        return host.createPlan(
            player,
            BuilderPlanKind.REPLACE,
            changes,
            BuilderItemCodec.aggregate(costs),
            BuilderItemCodec.aggregate(rewards),
            skippedUnsafe,
        )
    }

    companion object {
        fun isCoupledMultiBlock(data: BlockData): Boolean =
            data is Bed || data is Door || (data is Bisected && data !is Stairs && data !is TrapDoor)
    }
}
