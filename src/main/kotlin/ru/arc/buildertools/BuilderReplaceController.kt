package ru.arc.buildertools

import org.bukkit.Material
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.type.Bed
import org.bukkit.block.data.type.Door
import org.bukkit.block.data.type.Stairs
import org.bukkit.block.data.type.TrapDoor
import org.bukkit.entity.Player
import java.util.concurrent.CompletableFuture

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

    fun plan(player: Player, source: Material, target: Material, isCurrent: () -> Boolean = { true }): CompletableFuture<BuilderPlan> {
        host.ensurePermission(player, BuilderFeature.REPLACE)
        if (source == target) host.fail("errors.nothing-to-change")
        val targetTemplate = host.placementData(target)
        if (isCoupledMultiBlock(targetTemplate)) host.fail("errors.material")
        val selection = host.requiredSelection(player)
        val world = host.world(selection.worldId)
        val gameMode = player.gameMode
        val usesInventory = BuilderGameModePolicy.usesInventory(gameMode)
        val current = { isCurrent() && player.isOnline && player.world.uid == selection.worldId &&
            player.gameMode == gameMode && host.requiredSelection(player) == selection }
        val changes = mutableListOf<BuilderBlockChange>()
        val costs = mutableListOf<BuilderItemAmount>()
        val rewards = mutableListOf<BuilderItemAmount>()
        val prototypes = BuilderItemCodec.PrototypeCache()
        val positions = selection.positionsBottomUp().iterator()
        var skippedUnsafe = 0

        return host.capture(current) {
            if (!positions.hasNext()) return@capture true
            val position = positions.next()
            if (!world.isChunkLoaded(position.x shr 4, position.z shr 4)) host.fail("errors.chunk-unloaded")
            val block = world.getBlockAt(position.x, position.y, position.z)
            if (block.type != source && !(source == Material.AIR && block.type.isAir)) return@capture false
            val before = block.blockData
            if (!safety.isSafeExisting(block, allowAir = true) || isCoupledMultiBlock(before)) {
                skippedUnsafe += 1
                return@capture false
            }
            val after = targetTemplate.clone()
            BuilderCompatibleBlockState.copy(before, after)
            if (!safety.isSafePlacement(after)) {
                skippedUnsafe += 1
                return@capture false
            }
            val cost = BuilderPlacementCost.itemOrNull(after)
            val reward = BuilderPlacementCost.itemOrNull(before)
            if (usesInventory && (cost == null || reward == null && !before.material.isAir)) {
                skippedUnsafe += 1
                return@capture false
            }
            if (before.asString == after.asString) return@capture false
            host.ensurePlacement(player, block, after.material)
            changes += BuilderBlockChange(position, before.asString, after.asString)
            if (usesInventory) {
                costs += prototypes.capture(checkNotNull(cost))
                // Air variants can expose an AIR ItemStack; the previous aggregate discarded empty refunds.
                reward?.takeUnless { it.type.isAir || it.amount <= 0 }?.let { rewards += prototypes.capture(it) }
            }
            if (changes.size > maximumChanges) host.fail("errors.selection-too-large")
            false
        }.thenCompose {
            if (changes.isEmpty()) host.fail("errors.nothing-to-change")
            host.compute { BuilderItemCodec.aggregateAmounts(costs) to BuilderItemCodec.aggregateAmounts(rewards) }
        }.thenCompose { (capturedCosts, capturedRewards) ->
            host.onMain {
                requireCurrentBuilderPlanning(current)
                host.createPlanAsync(player, BuilderPlanKind.REPLACE, changes, capturedCosts, capturedRewards, skippedUnsafe)
            }.thenCompose { it }
        }
    }

    companion object {
        fun isCoupledMultiBlock(data: BlockData): Boolean =
            data is Bed || data is Door || (data is Bisected && data !is Stairs && data !is TrapDoor)
    }
}
