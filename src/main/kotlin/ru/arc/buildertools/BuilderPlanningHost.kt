package ru.arc.buildertools

import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture

/**
 * One runtime owns the aggregate native capture budget and its lifecycle.
 * Capture is submitted and completed on the server thread, checking its context
 * before each bounded step. Compute accepts detached immutable values only;
 * worker continuations return through onMain before submitting native work.
 * Neither operation blocks callers.
 */
internal interface BuilderPlanningTasks {
    fun capture(isCurrent: () -> Boolean, step: () -> Boolean): CompletableFuture<Unit>
    fun <T> compute(action: () -> T): CompletableFuture<T>
    fun <T> onMain(action: () -> T): CompletableFuture<T>
}

internal fun requireCurrentBuilderPlanning(isCurrent: () -> Boolean) {
    if (!isCurrent()) throw CancellationException("Builder planning context changed")
}

/** Narrow shared boundary for selection-based plans owned by the runtime. */
internal interface BuilderPlanningHost : BuilderPlanningTasks {
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
    fun createPlanAsync(
        player: Player,
        kind: BuilderPlanKind,
        changes: List<BuilderBlockChange>,
        costs: List<BuilderItemAmount> = emptyList(),
        rewards: List<BuilderItemAmount> = emptyList(),
        skippedUnsafeBlocks: Int = 0,
    ): CompletableFuture<BuilderPlan> = onMain {
        createPlan(player, kind, changes, costs, rewards, skippedUnsafeBlocks)
    }
    fun fail(path: String): Nothing
}
