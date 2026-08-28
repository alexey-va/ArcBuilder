package ru.arc.buildertools

import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

/** Generic permission, protection and plan boundary consumed by fill. */
internal interface BuilderFillHost {
    fun ensurePermission(player: Player)
    fun requiredSelection(player: Player): BuilderSelection
    fun world(worldId: UUID): World
    fun placementData(material: Material): BlockData
    fun ensureMutable(player: Player, block: Block)
    fun createPlan(
        player: Player,
        changes: List<BuilderBlockChange>,
        costs: List<BuilderItemAmount>,
    ): BuilderPlan
    fun fail(path: String): Nothing
}

/**
 * Main-thread owner of bounded fill planning.
 *
 * Planning never changes the world. It records only safe replaceable targets,
 * checks Lands/range/border access through [BuilderFillHost.ensureMutable], and
 * charges exact vanilla items in survival while keeping creative free.
 */
internal class BuilderFillController(
    private val safety: BuilderBlockSafety,
    private val maximumChanges: Int,
    private val host: BuilderFillHost,
) {
    init {
        require(maximumChanges in 1..BuilderPlan.ABSOLUTE_MAX_CHANGES) {
            "Builder fill maximum changes must stay inside the absolute plan bound"
        }
    }

    fun plan(player: Player, material: Material): BuilderPlan {
        host.ensurePermission(player)
        val after = host.placementData(material)
        val selection = host.requiredSelection(player)
        val world = host.world(selection.worldId)
        val changes = mutableListOf<BuilderBlockChange>()

        selection.positionsBottomUp().forEach { position ->
            val block = world.getBlockAt(position.x, position.y, position.z)
            if (block.blockData.asString == after.asString || !safety.isReplaceable(block)) return@forEach
            host.ensureMutable(player, block)
            changes += BuilderBlockChange(position, block.blockData.asString, after.asString)
            if (changes.size > maximumChanges) host.fail("errors.selection-too-large")
        }

        if (changes.isEmpty()) host.fail("errors.nothing-to-change")
        val costs = if (BuilderGameModePolicy.usesInventory(player.gameMode)) {
            BuilderItemCodec.aggregate(listOf(ItemStack(material, changes.size)))
        } else {
            emptyList()
        }
        return host.createPlan(player, changes, costs)
    }
}
