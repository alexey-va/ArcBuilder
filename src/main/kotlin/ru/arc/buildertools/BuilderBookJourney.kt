package ru.arc.buildertools

/**
 * Pure view of the player-visible build-book journey. Persistent book, quote,
 * auction, and preview state remain owned by their domain services; this owner
 * only chooses the safest contextual instruction to render.
 */
internal data class BuilderBookJourneySnapshot(
    val hasQuote: Boolean = false,
    val deliveryPending: Boolean = false,
    val auctionLocked: Boolean = false,
    val draft: Boolean = false,
    val previewOpen: Boolean = false,
    val active: Boolean = false,
    val hasClipboard: Boolean = false,
    val hasSelection: Boolean = false,
    val hasFirstPoint: Boolean = false,
    val hasSecondPoint: Boolean = false,
)

internal enum class BuilderBookJourneyStage(val messagePath: String) {
    START("book.status.start"),
    FIRST_POINT("book.status.first-point"),
    SECOND_POINT("book.status.second-point"),
    SELECTION("book.status.selection"),
    CLIPBOARD("book.status.clipboard"),
    DRAFT("book.status.draft"),
    PREVIEW("book.status.preview"),
    QUOTE("book.status.quote"),
    DELIVERY("book.status.delivery"),
    ACTIVE("book.status.active"),
    AUCTION_LOCKED("book.auction-locked"),
}

internal object BuilderBookJourney {
    fun resolve(snapshot: BuilderBookJourneySnapshot): BuilderBookJourneyStage = when {
        snapshot.hasQuote -> BuilderBookJourneyStage.QUOTE
        snapshot.deliveryPending -> BuilderBookJourneyStage.DELIVERY
        snapshot.auctionLocked -> BuilderBookJourneyStage.AUCTION_LOCKED
        snapshot.draft && snapshot.previewOpen -> BuilderBookJourneyStage.PREVIEW
        snapshot.draft -> BuilderBookJourneyStage.DRAFT
        snapshot.active -> BuilderBookJourneyStage.ACTIVE
        snapshot.hasClipboard -> BuilderBookJourneyStage.CLIPBOARD
        snapshot.hasSelection -> BuilderBookJourneyStage.SELECTION
        snapshot.hasFirstPoint -> BuilderBookJourneyStage.FIRST_POINT
        snapshot.hasSecondPoint -> BuilderBookJourneyStage.SECOND_POINT
        else -> BuilderBookJourneyStage.START
    }
}
