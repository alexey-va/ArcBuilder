package ru.arc.buildertools

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class BuilderBookContractsTest : StringSpec({
    "construction fee rounds upward to the nearest currency minor unit" {
        BuilderBookCostRules.calculate(listOf(100L, 1L), 1_500) shouldBe
            BuilderBookCost(materialCostMinor = 101L, constructionFeeMinor = 16L, issuePriceMinor = 117L)
    }

    "shop totals are fixed to currency precision without binary floating drift" {
        BuilderMoney.priceFromProvider(12.345) shouldBe 1_235L
        BuilderMoney.priceFromProvider(0.004) shouldBe 1L
    }

    "material line addition fails closed on overflow" {
        shouldThrow<ArithmeticException> {
            BuilderBookCostRules.calculate(listOf(Long.MAX_VALUE, 1L), 0)
        }
    }

    "mint state machine accepts issue delivery and completion in order" {
        val prepared = mint()
        val started = prepared.copy(
            status = BuilderBookMintStatus.WITHDRAWAL_STARTED,
            updatedAtMillis = 2L,
            balanceBeforeMinor = 1_000L,
        ).validated()
        val withdrawn = started.copy(
            status = BuilderBookMintStatus.FUNDS_WITHDRAWN,
            updatedAtMillis = 3L,
            balanceAfterMinor = 885L,
            evidence = "exact_balance_delta",
        ).validated()

        withdrawn.advance(BuilderBookMintStatus.ISSUED, 4L)
            .advance(BuilderBookMintStatus.COMPLETED, 5L)
            .status shouldBe BuilderBookMintStatus.COMPLETED
    }

    "mint cannot claim withdrawal without exact balance evidence" {
        val prepared = mint()
        shouldThrow<IllegalArgumentException> {
            prepared.copy(
                status = BuilderBookMintStatus.FUNDS_WITHDRAWN,
                updatedAtMillis = 2L,
                balanceBeforeMinor = 1_000L,
                balanceAfterMinor = 884L,
            ).validated()
        }
    }

    "book instance reservation fields are all-or-nothing" {
        shouldThrow<IllegalArgumentException> {
            BuilderBookInstance(
                instanceId = UUID.randomUUID(),
                blueprintId = UUID.randomUUID(),
                transactionId = UUID.randomUUID(),
                mintedBy = UUID.randomUUID(),
                deliveryPlayerId = UUID.randomUUID(),
                status = BuilderBookInstanceStatus.RESERVED,
                createdAtMillis = 1L,
                reservationOperationId = UUID.randomUUID(),
            ).validated()
        }
    }

    "auction listing is a distinct reservation state" {
        val leaseId = UUID.randomUUID()
        BuilderBookInstance(
            instanceId = UUID.randomUUID(),
            blueprintId = UUID.randomUUID(),
            transactionId = UUID.randomUUID(),
            mintedBy = UUID.randomUUID(),
            deliveryPlayerId = UUID.randomUUID(),
            status = BuilderBookInstanceStatus.LISTED,
            createdAtMillis = 1L,
            reservationOperationId = leaseId,
            reservationPlayerId = UUID.randomUUID(),
            reservationServer = "survival",
            reservedAtMillis = 2L,
        ).validated().reservationOperationId shouldBe leaseId
    }

    "auction transfer requires matching durable lease evidence and a positive generation" {
        val leaseId = UUID.randomUUID()
        val base = BuilderBookInstance(
            instanceId = UUID.randomUUID(),
            blueprintId = UUID.randomUUID(),
            transactionId = UUID.randomUUID(),
            mintedBy = UUID.randomUUID(),
            deliveryPlayerId = UUID.randomUUID(),
            generation = 2,
            status = BuilderBookInstanceStatus.TRANSFER_PENDING,
            createdAtMillis = 1L,
            reservationOperationId = leaseId,
            reservationPlayerId = UUID.randomUUID(),
            reservationServer = "survival",
            reservedAtMillis = 2L,
            lastAuctionLeaseId = leaseId,
        )

        base.validated().generation shouldBe 2
        shouldThrow<IllegalArgumentException> { base.copy(lastAuctionLeaseId = UUID.randomUUID()).validated() }
        shouldThrow<IllegalArgumentException> { base.copy(generation = 0).validated() }
    }

    "auction prices accept Russian decimals but reject ambiguous input" {
        BuilderBookAuctionPrice.parse("12500,50") shouldBe java.math.BigDecimal("12500.50")
        BuilderBookAuctionPrice.parse("1e9") shouldBe null
        BuilderBookAuctionPrice.parse("0") shouldBe null
        BuilderBookAuctionPrice.parse("12.345") shouldBe null
    }
})

private fun mint(): BuilderBookMint {
    val player = UUID.randomUUID()
    val blueprint = BuilderBookBlueprint(
        blueprintId = UUID.randomUUID(),
        creatorId = player,
        creatorName = "Builder",
        title = "Дом",
        buildingId = "player-${player.toString().replace("-", "")}-fixture.schem",
        contentSha256 = "a".repeat(64),
        schematicSha256 = "b".repeat(64),
        blockCount = 10,
        materialTypes = 2,
        materialItems = 10,
        materialCostMinor = 100L,
        constructionFeeMinor = 15L,
        issuePriceMinor = 115L,
        createdAtMillis = 1L,
    ).validated()
    return BuilderBookMint(
        transactionId = UUID.randomUUID(),
        kind = BuilderBookMintKind.CREATE,
        playerId = player,
        blueprint = blueprint,
        instanceId = UUID.randomUUID(),
        placement = BuilderBookPlacement(0, 0, 0, 0),
        createdAtMillis = 1L,
    ).validated()
}
