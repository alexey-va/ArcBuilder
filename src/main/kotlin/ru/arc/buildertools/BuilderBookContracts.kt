package ru.arc.buildertools

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

internal enum class BuilderBookMintKind {
    CREATE,
    COPY,
}

internal enum class BuilderBookMintStatus(val terminal: Boolean) {
    PREPARED(false),
    WITHDRAWAL_STARTED(false),
    FUNDS_WITHDRAWN(false),
    ISSUED(false),
    REFUND_STARTED(false),
    COMPLETED(true),
    CANCELLED(true),
    REFUNDED(true),
    MANUAL_REVIEW(false),
}

internal enum class BuilderBookInstanceStatus {
    PENDING_DELIVERY,
    AVAILABLE,
    RESERVED,
    LISTED,
    TRANSFER_PENDING,
    CONSUMED,
    REVOKED,
}

internal data class BuilderBookPlacement(
    val rotation: Int,
    val offsetX: Int,
    val offsetY: Int,
    val offsetZ: Int,
) {
    fun validated(): BuilderBookPlacement = apply {
        require(rotation in setOf(0, 90, 180, 270)) { "Builder-book delivery rotation is invalid" }
        require(offsetX in -64..64 && offsetY in -64..64 && offsetZ in -64..64) {
            "Builder-book delivery offset is invalid"
        }
    }
}

internal data class BuilderBookBlueprint(
    val blueprintId: UUID,
    val creatorId: UUID,
    val creatorName: String,
    val title: String,
    val buildingId: String,
    val contentSha256: String,
    val schematicSha256: String,
    val blockCount: Int,
    val materialTypes: Int,
    val materialItems: Int,
    val materialCostMinor: Long,
    val constructionFeeMinor: Long,
    val issuePriceMinor: Long,
    val createdAtMillis: Long,
    val sourceRotation: Int = 0,
) {
    fun validated(): BuilderBookBlueprint = apply {
        require(PLAYER_NAME.matches(creatorName)) { "Builder-book creator name is invalid" }
        require(title.isNotBlank() && title.length <= 48 && title.none(Char::isISOControl)) {
            "Builder-book title is invalid"
        }
        require(BUILDING_ID.matches(buildingId)) { "Builder-book building id is invalid" }
        require(SHA256.matches(contentSha256) && SHA256.matches(schematicSha256)) {
            "Builder-book content digest is invalid"
        }
        require(blockCount in 1..BuilderPlan.ABSOLUTE_MAX_CHANGES) { "Builder-book block count is invalid" }
        require(materialTypes in 1..MAX_MATERIAL_TYPES) { "Builder-book material type count is invalid" }
        require(materialItems in 1..BuilderPlan.ABSOLUTE_MAX_ITEMS.toInt()) { "Builder-book material item count is invalid" }
        require(materialCostMinor > 0L && constructionFeeMinor >= 0L) { "Builder-book costs are invalid" }
        require(issuePriceMinor == Math.addExact(materialCostMinor, constructionFeeMinor)) {
            "Builder-book issue price does not equal its cost components"
        }
        require(issuePriceMinor <= MAX_PRICE_MINOR) { "Builder-book issue price exceeds its hard bound" }
        require(sourceRotation in setOf(0, 90, 180, 270)) { "Builder-book source rotation is invalid" }
        require(createdAtMillis > 0L) { "Builder-book creation time is invalid" }
    }

    companion object {
        const val MAX_MATERIAL_TYPES = 256
        const val MAX_PRICE_MINOR = 100_000_000_000L
        private val PLAYER_NAME = Regex("[A-Za-z0-9_]{1,16}")
        private val BUILDING_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,159}")
        private val SHA256 = Regex("[a-f0-9]{64}")
    }
}

internal data class BuilderBookInstance(
    val instanceId: UUID,
    val blueprintId: UUID,
    val transactionId: UUID,
    val mintedBy: UUID,
    val deliveryPlayerId: UUID,
    val ownerId: UUID = deliveryPlayerId,
    val generation: Int = INITIAL_GENERATION,
    val status: BuilderBookInstanceStatus,
    val createdAtMillis: Long,
    val reservationOperationId: UUID? = null,
    val reservationPlayerId: UUID? = null,
    val reservationServer: String? = null,
    val reservedAtMillis: Long? = null,
    val consumedOperationId: UUID? = null,
    val consumedAtMillis: Long? = null,
    val lastAuctionLeaseId: UUID? = null,
) {
    fun validated(): BuilderBookInstance = apply {
        require(createdAtMillis > 0L) { "Builder-book instance creation time is invalid" }
        require(generation > 0) { "Builder-book instance generation is invalid" }
        val reservationValues = listOf(reservationOperationId, reservationPlayerId, reservationServer, reservedAtMillis)
        require(reservationValues.all { it == null } || reservationValues.all { it != null }) {
            "Builder-book reservation fields must be present together"
        }
        require((status in RESERVATION_STATUSES) == (reservationOperationId != null)) {
            "Builder-book reservation fields do not match status"
        }
        reservationServer?.let {
            require(SERVER_NAME.matches(it)) { "Builder-book reservation server is invalid" }
        }
        require((status == BuilderBookInstanceStatus.CONSUMED) == (consumedAtMillis != null && consumedOperationId != null)) {
            "Builder-book consumed evidence does not match status"
        }
        if (status == BuilderBookInstanceStatus.TRANSFER_PENDING) {
            require(lastAuctionLeaseId == reservationOperationId) {
                "Builder-book pending transfer lacks auction lease evidence"
            }
        }
    }

    companion object {
        const val INITIAL_GENERATION = 1
        val SERVER_NAME = Regex("[A-Za-z0-9_.-]{1,64}")
        val RESERVATION_STATUSES = setOf(
            BuilderBookInstanceStatus.RESERVED,
            BuilderBookInstanceStatus.LISTED,
            BuilderBookInstanceStatus.TRANSFER_PENDING,
        )
    }
}

internal data class BuilderBookMint(
    val transactionId: UUID,
    val kind: BuilderBookMintKind,
    val playerId: UUID,
    val blueprint: BuilderBookBlueprint,
    val instanceId: UUID,
    val sourceInstanceId: UUID? = null,
    val sourceInstanceGeneration: Int? = null,
    val placement: BuilderBookPlacement,
    val status: BuilderBookMintStatus = BuilderBookMintStatus.PREPARED,
    val createdAtMillis: Long,
    val updatedAtMillis: Long = createdAtMillis,
    val balanceBeforeMinor: Long? = null,
    val balanceAfterMinor: Long? = null,
    val refundBalanceBeforeMinor: Long? = null,
    val refundBalanceAfterMinor: Long? = null,
    val providerTransactionId: String? = null,
    val evidence: String? = null,
) {
    fun validated(): BuilderBookMint = apply {
        blueprint.validated()
        placement.validated()
        if (kind == BuilderBookMintKind.CREATE) {
            require(playerId == blueprint.creatorId) { "Builder-book create mint owner is invalid" }
        }
        require((kind == BuilderBookMintKind.COPY) == (sourceInstanceId != null && sourceInstanceGeneration != null)) {
            "Builder-book copy mint source is invalid"
        }
        sourceInstanceGeneration?.let { require(it > 0) { "Builder-book copy source generation is invalid" } }
        require(sourceInstanceId == null || sourceInstanceId != instanceId) { "Builder-book copy instance collides with its source" }
        require(createdAtMillis > 0L && updatedAtMillis >= createdAtMillis) { "Builder-book mint time is invalid" }
        require(evidence == null || EVIDENCE.matches(evidence)) { "Builder-book mint evidence is invalid" }
        require(providerTransactionId == null || PROVIDER_ID.matches(providerTransactionId)) {
            "Builder-book provider transaction id is invalid"
        }
        if (status in WITHDRAWN_STATUSES) {
            require(balanceBeforeMinor != null && balanceAfterMinor == balanceBeforeMinor - blueprint.issuePriceMinor) {
                "Builder-book mint lacks exact withdrawal evidence"
            }
        }
        if (status == BuilderBookMintStatus.REFUNDED) {
            require(
                refundBalanceBeforeMinor != null &&
                    refundBalanceAfterMinor == refundBalanceBeforeMinor + blueprint.issuePriceMinor,
            ) { "Builder-book mint lacks exact refund evidence" }
        }
    }

    fun advance(next: BuilderBookMintStatus, now: Long, evidence: String? = this.evidence): BuilderBookMint {
        require(next == status || next in transitions(status)) { "Illegal builder-book mint transition $status -> $next" }
        return copy(status = next, updatedAtMillis = maxOf(now, updatedAtMillis + 1), evidence = evidence).validated()
    }

    companion object {
        private val EVIDENCE = Regex("[a-z0-9_:-]{1,160}")
        private val PROVIDER_ID = Regex("[A-Za-z0-9._:-]{1,160}")
        private val WITHDRAWN_STATUSES = setOf(
            BuilderBookMintStatus.FUNDS_WITHDRAWN,
            BuilderBookMintStatus.ISSUED,
            BuilderBookMintStatus.REFUND_STARTED,
            BuilderBookMintStatus.COMPLETED,
            BuilderBookMintStatus.REFUNDED,
        )

        fun transitions(status: BuilderBookMintStatus): Set<BuilderBookMintStatus> = when (status) {
            BuilderBookMintStatus.PREPARED -> setOf(BuilderBookMintStatus.WITHDRAWAL_STARTED, BuilderBookMintStatus.CANCELLED)
            BuilderBookMintStatus.WITHDRAWAL_STARTED -> setOf(
                BuilderBookMintStatus.FUNDS_WITHDRAWN,
                BuilderBookMintStatus.CANCELLED,
                BuilderBookMintStatus.MANUAL_REVIEW,
            )
            BuilderBookMintStatus.FUNDS_WITHDRAWN -> setOf(
                BuilderBookMintStatus.ISSUED,
                BuilderBookMintStatus.REFUND_STARTED,
                BuilderBookMintStatus.MANUAL_REVIEW,
            )
            BuilderBookMintStatus.ISSUED -> setOf(BuilderBookMintStatus.COMPLETED, BuilderBookMintStatus.MANUAL_REVIEW)
            BuilderBookMintStatus.REFUND_STARTED -> setOf(BuilderBookMintStatus.REFUNDED, BuilderBookMintStatus.MANUAL_REVIEW)
            BuilderBookMintStatus.MANUAL_REVIEW -> setOf(
                BuilderBookMintStatus.FUNDS_WITHDRAWN,
                BuilderBookMintStatus.ISSUED,
                BuilderBookMintStatus.COMPLETED,
                BuilderBookMintStatus.REFUNDED,
            )
            BuilderBookMintStatus.COMPLETED,
            BuilderBookMintStatus.CANCELLED,
            BuilderBookMintStatus.REFUNDED,
            -> emptySet()
        }
    }
}

internal data class BuilderBookDelivery(
    val blueprint: BuilderBookBlueprint,
    val instance: BuilderBookInstance,
    val placement: BuilderBookPlacement,
    val sourceInstanceId: UUID?,
    val sourceInstanceGeneration: Int?,
) {
    fun validated(): BuilderBookDelivery = apply {
        blueprint.validated()
        instance.validated()
        placement.validated()
        require(instance.blueprintId == blueprint.blueprintId) { "Builder-book delivery identity mismatch" }
        require(sourceInstanceId == null || sourceInstanceId != instance.instanceId) { "Builder-book delivery source is invalid" }
        require((sourceInstanceId == null) == (sourceInstanceGeneration == null)) {
            "Builder-book delivery source generation is invalid"
        }
        sourceInstanceGeneration?.let { require(it > 0) { "Builder-book delivery source generation is invalid" } }
    }
}

internal data class BuilderBookCost(
    val materialCostMinor: Long,
    val constructionFeeMinor: Long,
    val issuePriceMinor: Long,
) {
    fun validated(): BuilderBookCost = apply {
        require(materialCostMinor > 0L && constructionFeeMinor >= 0L) { "Builder-book cost is invalid" }
        require(issuePriceMinor == Math.addExact(materialCostMinor, constructionFeeMinor)) {
            "Builder-book price does not equal cost plus construction fee"
        }
        require(issuePriceMinor <= BuilderBookBlueprint.MAX_PRICE_MINOR) { "Builder-book cost exceeds its hard bound" }
    }
}

internal object BuilderBookCostRules {
    fun calculate(materialLinesMinor: List<Long>, constructionMarkupBasisPoints: Int): BuilderBookCost {
        require(materialLinesMinor.isNotEmpty() && materialLinesMinor.all { it > 0L }) {
            "Builder-book material quote is empty or invalid"
        }
        require(constructionMarkupBasisPoints in 0..10_000) { "Builder-book construction markup is invalid" }
        val material = materialLinesMinor.fold(0L, Math::addExact)
        val fee = BigDecimal.valueOf(material)
            .multiply(BigDecimal.valueOf(constructionMarkupBasisPoints.toLong()))
            .divide(BigDecimal.valueOf(10_000L), 0, RoundingMode.CEILING)
            .longValueExact()
        return BuilderBookCost(material, fee, Math.addExact(material, fee)).validated()
    }
}

internal sealed interface BuilderBookAuctionReservationResult {
    data class Reserved(val blueprint: BuilderBookBlueprint) : BuilderBookAuctionReservationResult
    data object Missing : BuilderBookAuctionReservationResult
    data object Unavailable : BuilderBookAuctionReservationResult
    data object Stale : BuilderBookAuctionReservationResult
    data object Mismatch : BuilderBookAuctionReservationResult
}

internal sealed interface BuilderBookAuctionTransferResult {
    data class Pending(val generation: Int) : BuilderBookAuctionTransferResult
    data class Completed(val generation: Int) : BuilderBookAuctionTransferResult
    data object Rejected : BuilderBookAuctionTransferResult
}
