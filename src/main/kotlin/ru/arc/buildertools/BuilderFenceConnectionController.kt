package ru.arc.buildertools

import org.bukkit.block.data.MultipleFacing
import org.bukkit.entity.Player

/** Builds a one-shot plan that clears every connected side of vanilla fences in the selection. */
internal class BuilderFenceConnectionController(
    private val maximumChanges: Int,
    private val host: BuilderPlanningHost,
) {
    init {
        require(maximumChanges in 1..BuilderPlan.ABSOLUTE_MAX_CHANGES) {
            "Builder fence connection maximum changes must stay inside the absolute plan bound"
        }
    }

    fun planDisconnect(player: Player): BuilderPlan {
        host.ensurePermission(player, BuilderFeature.FENCE_DISCONNECT)
        val selection = host.requiredSelection(player)
        val world = host.world(selection.worldId)
        val changes = mutableListOf<BuilderBlockChange>()

        selection.positionsBottomUp().forEach { position ->
            val block = world.getBlockAt(position.x, position.y, position.z)
            if (!block.type.name.endsWith("_FENCE")) return@forEach
            val before = block.blockData as? MultipleFacing ?: return@forEach
            if (before.faces.isEmpty()) return@forEach
            val after = before.clone() as MultipleFacing
            before.faces.forEach { face -> after.setFace(face, false) }
            host.ensureMutable(player, block)
            changes += BuilderBlockChange(position, before.asString, after.asString)
            if (changes.size > maximumChanges) host.fail("errors.selection-too-large")
        }

        if (changes.isEmpty()) host.fail("errors.nothing-to-change")
        return host.createPlan(player, BuilderPlanKind.FENCE_DISCONNECT, changes)
    }
}
