package ru.arc.buildertools

import java.util.UUID

internal data class BuilderSelectionUpdate(
    val selection: BuilderSelection?,
    val worldReset: Boolean,
)

internal data class BuilderSelectionPoints(
    val first: BuilderBlockPos?,
    val second: BuilderBlockPos?,
)

/**
 * Owns one player's two-corner selection from the first point through visual
 * cleanup. All methods are Paper-primary-thread only.
 */
internal class BuilderSelectionController(
    private val previewRadius: Double,
    private val previewSpacing: Double,
    private val maximumOutlinePoints: Int,
) {
    private data class Draft(
        var first: BuilderBlockPos? = null,
        var second: BuilderBlockPos? = null,
    )

    private val drafts = mutableMapOf<UUID, Draft>()

    init {
        require(previewRadius > 0.0 && previewRadius.isFinite()) { "Selection preview radius must be positive" }
        require(previewSpacing > 0.0 && previewSpacing.isFinite()) { "Selection preview spacing must be positive" }
        require(maximumOutlinePoints > 0) { "Selection preview point limit must be positive" }
    }

    fun set(playerId: UUID, position: BuilderBlockPos, first: Boolean): BuilderSelectionUpdate {
        val checked = position.validated()
        val draft = drafts.getOrPut(playerId, ::Draft)
        val worldReset = sequenceOf(draft.first, draft.second)
            .filterNotNull()
            .any { it.worldId != checked.worldId }
        if (worldReset) {
            draft.first = null
            draft.second = null
        }
        if (first) draft.first = checked else draft.second = checked
        return BuilderSelectionUpdate(
            selection = selection(playerId, checked.worldId),
            worldReset = worldReset,
        )
    }

    fun selection(playerId: UUID, viewerWorldId: UUID): BuilderSelection? {
        val points = points(playerId, viewerWorldId)
        val first = points.first ?: return null
        val second = points.second ?: return null
        return BuilderSelection(first, second)
    }

    fun points(playerId: UUID, viewerWorldId: UUID): BuilderSelectionPoints {
        val draft = drafts[playerId]
        return BuilderSelectionPoints(
            first = draft?.first?.takeIf { it.worldId == viewerWorldId },
            second = draft?.second?.takeIf { it.worldId == viewerWorldId },
        )
    }

    fun first(playerId: UUID, viewerWorldId: UUID): BuilderBlockPos? =
        points(playerId, viewerWorldId).first

    fun clear(playerId: UUID): Boolean = drafts.remove(playerId) != null

    fun clear() = drafts.clear()

}
