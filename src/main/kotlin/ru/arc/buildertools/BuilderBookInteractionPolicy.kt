package ru.arc.buildertools

import ru.arc.autobuild.BuildBookData
import java.util.UUID

internal enum class BuilderBookClick {
    BLOCK,
    AIR,
}

internal enum class BuilderBookPreparedPlan {
    NONE,
    SAME_BOOK,
    OTHER_BOOK,
}

internal enum class BuilderBookInteractionDecision {
    OPEN_PREVIEW,
    REPLACE_PLAN_WITH_PREVIEW,
    REQUIRE_PREVIEW,
}

/** Keeps book positioning and prepared-plan previews in one explicit state machine. */
internal object BuilderBookInteractionPolicy {
    fun decide(
        action: BuilderBookClick,
        exactBookPreviewOpen: Boolean,
        preparedPlan: BuilderBookPreparedPlan,
    ): BuilderBookInteractionDecision = when (action) {
        BuilderBookClick.BLOCK -> if (preparedPlan == BuilderBookPreparedPlan.NONE) {
            BuilderBookInteractionDecision.OPEN_PREVIEW
        } else {
            BuilderBookInteractionDecision.REPLACE_PLAN_WITH_PREVIEW
        }
        // A build book is confirmed only from the green button in the hologram
        // menu. Air clicks are deliberately inert at the event boundary; this
        // result keeps direct policy callers on the same safe path.
        BuilderBookClick.AIR -> BuilderBookInteractionDecision.REQUIRE_PREVIEW
    }
}

internal object BuilderBookCopyPolicy {
    fun canRequest(playerId: UUID, data: BuildBookData): Boolean =
        data.available && data.creatorId == playerId

    fun canConfirm(playerId: UUID, itemCreatorId: UUID?, blueprintCreatorId: UUID): Boolean =
        itemCreatorId == playerId && blueprintCreatorId == playerId
}
