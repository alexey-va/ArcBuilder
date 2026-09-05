package ru.arc.buildertools

import org.bukkit.GameMode
import ru.ruscrafting.builder.api.BuilderOperationCommittedEvent
import ru.ruscrafting.builder.api.BuilderPlacedBlock

internal object BuilderOperationCommittedEventFactory {
    private val eligibleKinds = setOf(
        BuilderPlanKind.FILL,
        BuilderPlanKind.REPLACE,
        BuilderPlanKind.PASTE,
        BuilderPlanKind.CROWN,
        BuilderPlanKind.BUILD_BOOK,
    )
    private val airMaterials = setOf("air", "cave_air", "void_air")

    fun create(plan: BuilderPlan, gameMode: GameMode): BuilderOperationCommittedEvent? {
        if (gameMode != GameMode.SURVIVAL && gameMode != GameMode.ADVENTURE) return null
        if (plan.kind !in eligibleKinds) return null
        val worldId = plan.changes.map { it.position.worldId }.distinct().singleOrNull() ?: return null
        val placements = plan.changes.mapNotNull { change ->
            val before = material(change.beforeBlockData)
            val after = material(change.afterBlockData)
            if (before == after || after in airMaterials) return@mapNotNull null
            BuilderPlacedBlock(change.position.x, change.position.y, change.position.z, "minecraft:$after")
        }
        if (placements.isEmpty()) return null
        return BuilderOperationCommittedEvent(plan.id.toString(), plan.playerId, worldId, placements)
    }

    private fun material(blockData: String): String =
        blockData.substringBefore('[').substringAfterLast(':')
}
