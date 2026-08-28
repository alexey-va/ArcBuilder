package ru.arc.buildertools

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID
import java.util.concurrent.CompletableFuture

class BuilderBookMintCoordinatorTest : StringSpec({
    "successful mint withdraws once and creates one pending-delivery instance" {
        val fixture = MintFixture()
        var result: BuilderBookMintResult? = null

        fixture.coordinator.mint(fixture.intent()) { result = it }

        result shouldBe BuilderBookMintResult.Issued(fixture.registry.records.single())
        fixture.wallet.withdrawals shouldBe 1
        fixture.wallet.balanceMinor shouldBe 885L
        fixture.registry.records.single().status shouldBe BuilderBookMintStatus.ISSUED
    }

    "insufficient funds cancel before any provider mutation" {
        val fixture = MintFixture().also { it.wallet.balanceMinor = 100L }
        var result: BuilderBookMintResult? = null

        fixture.coordinator.mint(fixture.intent()) { result = it }

        result shouldBe BuilderBookMintResult.InsufficientFunds
        fixture.wallet.withdrawals shouldBe 0
        fixture.registry.records.single().status shouldBe BuilderBookMintStatus.CANCELLED
    }

    "ambiguous withdrawal is quarantined and blocks another mint for the player" {
        val fixture = MintFixture().also { it.wallet.ambiguousWithdrawal = true }
        var first: BuilderBookMintResult? = null
        var second: BuilderBookMintResult? = null

        fixture.coordinator.mint(fixture.intent()) { first = it }
        fixture.coordinator.mint(fixture.intent()) { second = it }

        first shouldBe BuilderBookMintResult.ManualReview
        second shouldBe BuilderBookMintResult.Busy
        fixture.wallet.withdrawals shouldBe 1
        fixture.registry.records.single().status shouldBe BuilderBookMintStatus.MANUAL_REVIEW
    }

    "failed instance issue refunds the exact charge once" {
        val fixture = MintFixture().also { it.registry.issueFails = true }
        var result: BuilderBookMintResult? = null

        fixture.coordinator.mint(fixture.intent()) { result = it }

        result shouldBe BuilderBookMintResult.Refunded
        fixture.wallet.withdrawals shouldBe 1
        fixture.wallet.deposits shouldBe 1
        fixture.wallet.balanceMinor shouldBe 1_000L
        fixture.registry.records.single().status shouldBe BuilderBookMintStatus.REFUNDED
    }

    "restart recovery uses provider history and never charges a second time" {
        val fixture = MintFixture()
        val prepared = fixture.intent()
        val started = prepared.copy(
            status = BuilderBookMintStatus.WITHDRAWAL_STARTED,
            updatedAtMillis = prepared.updatedAtMillis + 1,
            balanceBeforeMinor = 1_000L,
        ).validated()
        fixture.registry.records += started
        fixture.wallet.historyFound = true
        fixture.wallet.balanceMinor = 885L
        var result: BuilderBookMintResult? = null

        fixture.coordinator.recover(started) { result = it }

        result shouldBe BuilderBookMintResult.Issued(fixture.registry.records.single())
        fixture.wallet.withdrawals shouldBe 0
        fixture.registry.records.single().status shouldBe BuilderBookMintStatus.ISSUED
    }

    "restart recovery cancels when exhaustive history has no withdrawal and balance is unchanged" {
        val fixture = MintFixture()
        val prepared = fixture.intent()
        val started = prepared.copy(
            status = BuilderBookMintStatus.WITHDRAWAL_STARTED,
            updatedAtMillis = prepared.updatedAtMillis + 1,
            balanceBeforeMinor = 1_000L,
        ).validated()
        fixture.registry.records += started
        var result: BuilderBookMintResult? = null

        fixture.coordinator.recover(started) { result = it }

        result shouldBe BuilderBookMintResult.PaymentRejected
        fixture.wallet.withdrawals shouldBe 0
        fixture.registry.records.single().status shouldBe BuilderBookMintStatus.CANCELLED
    }

    "restart recovery quarantines an incomplete history window without mutating money" {
        val fixture = MintFixture().also { it.wallet.historyExhaustive = false }
        val prepared = fixture.intent()
        val started = prepared.copy(
            status = BuilderBookMintStatus.WITHDRAWAL_STARTED,
            updatedAtMillis = prepared.updatedAtMillis + 1,
            balanceBeforeMinor = 1_000L,
        ).validated()
        fixture.registry.records += started
        var result: BuilderBookMintResult? = null

        fixture.coordinator.recover(started) { result = it }

        result shouldBe BuilderBookMintResult.ManualReview
        fixture.wallet.withdrawals shouldBe 0
        fixture.wallet.deposits shouldBe 0
        fixture.registry.records.single().status shouldBe BuilderBookMintStatus.MANUAL_REVIEW
    }
})

private class MintFixture {
    val playerId: UUID = UUID.randomUUID()
    val registry = FakeBookRegistry()
    val wallet = FakeBookWallet()
    val coordinator = BuilderBookMintCoordinator(registry, wallet, { it() }, clock = { registry.clock++ })

    fun intent(): BuilderBookMint {
        val now = registry.clock++
        val blueprint = BuilderBookBlueprint(
            blueprintId = UUID.randomUUID(),
            creatorId = playerId,
            creatorName = "Builder",
            title = "Дом",
            buildingId = "player-${playerId.toString().replace("-", "")}-${UUID.randomUUID().toString().take(8)}.schem",
            contentSha256 = "a".repeat(64),
            schematicSha256 = "b".repeat(64),
            blockCount = 10,
            materialTypes = 2,
            materialItems = 10,
            materialCostMinor = 100L,
            constructionFeeMinor = 15L,
            issuePriceMinor = 115L,
            createdAtMillis = now,
        ).validated()
        return BuilderBookMint(
            transactionId = UUID.randomUUID(),
            kind = BuilderBookMintKind.CREATE,
            playerId = playerId,
            blueprint = blueprint,
            instanceId = UUID.randomUUID(),
            placement = BuilderBookPlacement(0, 0, 0, 0),
            createdAtMillis = now,
        ).validated()
    }
}

private class FakeBookWallet : BuilderBookWallet {
    override val available = true
    var balanceMinor = 1_000L
    var withdrawals = 0
    var deposits = 0
    var ambiguousWithdrawal = false
    var historyFound = false
    var historyAvailable = true
    var historyExhaustive = true

    override fun balanceMinor(playerId: UUID): Long = balanceMinor

    override fun withdraw(
        playerId: UUID,
        amountMinor: Long,
        reason: String,
        expectedBalanceBeforeMinor: Long,
    ): BuilderBookMoneyEvidence {
        withdrawals++
        if (ambiguousWithdrawal) {
            balanceMinor -= amountMinor / 2
            return BuilderBookMoneyEvidence(null, true, balanceMinor, "provider_threw")
        }
        balanceMinor -= amountMinor
        return BuilderBookMoneyEvidence(true, true, balanceMinor)
    }

    override fun deposit(
        playerId: UUID,
        amountMinor: Long,
        reason: String,
        expectedBalanceBeforeMinor: Long,
    ): BuilderBookMoneyEvidence {
        deposits++
        balanceMinor += amountMinor
        return BuilderBookMoneyEvidence(true, true, balanceMinor)
    }

    override fun findTransaction(
        playerId: UUID,
        amountMinor: Long,
        reason: String,
        notBeforeMillis: Long,
    ): CompletableFuture<BuilderBookProviderTransaction> = CompletableFuture.completedFuture(
        BuilderBookProviderTransaction(
            if (historyFound) "provider-fixture" else null,
            historyAvailable,
            historyExhaustive,
        ),
    )
}

private class FakeBookRegistry : BuilderBookRegistry {
    val records = mutableListOf<BuilderBookMint>()
    var issueFails = false
    var clock = 1L

    override fun initialize() = CompletableFuture.completedFuture(Unit)

    override fun prepareMint(mint: BuilderBookMint): CompletableFuture<Boolean> {
        records += mint
        return CompletableFuture.completedFuture(true)
    }

    override fun hasOpenMint(playerId: UUID): CompletableFuture<Boolean> =
        CompletableFuture.completedFuture(records.any { it.playerId == playerId && !it.status.terminal })

    override fun transitionMint(expected: BuilderBookMint, next: BuilderBookMint): CompletableFuture<BuilderBookMint?> {
        val index = records.indexOf(expected)
        if (index < 0) return CompletableFuture.completedFuture(records.firstOrNull { it == next })
        records[index] = next
        return CompletableFuture.completedFuture(next)
    }

    override fun issuePaidMint(transactionId: UUID, now: Long): CompletableFuture<BuilderBookMint> {
        if (issueFails) return CompletableFuture.failedFuture(IllegalStateException("fixture issue failure"))
        val index = records.indexOfFirst { it.transactionId == transactionId }
        val issued = records[index].advance(BuilderBookMintStatus.ISSUED, now, "instance_pending_delivery")
        records[index] = issued
        return CompletableFuture.completedFuture(issued)
    }

    override fun loadMint(transactionId: UUID): CompletableFuture<BuilderBookMint?> =
        CompletableFuture.completedFuture(records.firstOrNull { it.transactionId == transactionId })

    override fun markDelivered(instanceId: UUID, transactionId: UUID, now: Long) = CompletableFuture.completedFuture(true)
    override fun loadBlueprint(blueprintId: UUID) = CompletableFuture.completedFuture<BuilderBookBlueprint?>(null)
    override fun loadInstance(instanceId: UUID) = CompletableFuture.completedFuture<BuilderBookInstance?>(null)
    override fun pendingDeliveries(playerId: UUID) =
        CompletableFuture.completedFuture<List<BuilderBookDelivery>>(emptyList())
    override fun openMints() = CompletableFuture.completedFuture(records.filter { !it.status.terminal })
    override fun reservedForServer(serverName: String) = CompletableFuture.completedFuture<List<BuilderBookInstance>>(emptyList())
    override fun reserveForAuction(
        instanceId: UUID,
        expectedGeneration: Int,
        expectedBlueprintId: UUID,
        expectedBuildingId: String,
        expectedSchematicSha256: String,
        leaseId: UUID,
        sellerId: UUID,
        serverName: String,
        now: Long,
    ) = CompletableFuture.completedFuture<BuilderBookAuctionReservationResult>(BuilderBookAuctionReservationResult.Missing)
    override fun releaseFromAuction(instanceId: UUID, leaseId: UUID) = CompletableFuture.completedFuture(false)
    override fun beginAuctionTransfer(
        instanceId: UUID,
        leaseId: UUID,
        recipientId: UUID,
        serverName: String,
        now: Long,
    ) = CompletableFuture.completedFuture<BuilderBookAuctionTransferResult>(BuilderBookAuctionTransferResult.Rejected)
    override fun completeAuctionTransfer(
        instanceId: UUID,
        leaseId: UUID,
        recipientId: UUID,
        generation: Int,
    ) = CompletableFuture.completedFuture(false)
    override fun listedForServer(serverName: String) = CompletableFuture.completedFuture<List<BuilderBookInstance>>(emptyList())
    override fun close() = Unit
}
