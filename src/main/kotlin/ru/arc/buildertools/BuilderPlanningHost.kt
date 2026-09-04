package ru.arc.buildertools

import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import java.util.UUID

/** Narrow shared boundary for selection-based plans owned by the runtime. */
internal interface BuilderPlanningHost {
    fun ensurePermission(player: Player, feature: BuilderFeature)
    fun requiredSelection(player: Player): BuilderSelection
    fun world(worldId: UUID): World
    fun placementData(material: Material): BlockData
    fun ensureMutable(player: Player, block: Block, placing: Material? = null)
    fun ensurePlacement(player: Player, block: Block, material: Material) =
        ensureMutable(player, block, material)
    fun createPlan(
        player: Player,
        kind: BuilderPlanKind,
        changes: List<BuilderBlockChange>,
        costs: List<BuilderItemAmount> = emptyList(),
        rewards: List<BuilderItemAmount> = emptyList(),
        skippedUnsafeBlocks: Int = 0,
    ): BuilderPlan
    fun fail(path: String): Nothing
}
