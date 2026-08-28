package ru.arc.buildertools

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.arc.onetime.OneTimeUseClaim
import ru.arc.onetime.OneTimeUseClaimRequest
import ru.arc.onetime.OneTimeUseClaimResult
import ru.arc.onetime.OneTimeUseCommitResult
import ru.arc.onetime.OneTimeUseFingerprint
import ru.arc.onetime.OneTimeUseIdentity
import java.util.UUID
import java.util.concurrent.CompletableFuture

class BuilderBookClaimCoordinatorTest : StringSpec({
    "domain reservation is durable before a ledger failure and remains for recovery" {
        val request = request()
        val events = mutableListOf<String>()
        val coordinator = coordinator(
            reserveDomain = {
                events += "domain"
                completed(reserved(it, newlyReserved = true))
            },
            claimDurably = {
                events += "ledger"
                CompletableFuture.failedFuture(IllegalStateException("ledger unavailable"))
            },
            releaseDomain = {
                events += "release"
                completed(true)
            },
        )

        shouldThrowAny { coordinator.claim(request).join() }

        events shouldContainExactly listOf("domain", "ledger")
    }

    "a new domain reservation is released when the durable identity rejects it" {
        val events = mutableListOf<String>()
        val coordinator = coordinator(
            reserveDomain = {
                events += "domain"
                completed(reserved(it, newlyReserved = true))
            },
            claimDurably = {
                events += "ledger"
                completed(OneTimeUseClaimResult.Busy)
            },
            releaseDomain = {
                events += "release"
                completed(true)
            },
        )

        coordinator.claim(request()).join() shouldBe OneTimeUseClaimResult.Busy
        events shouldContainExactly listOf("domain", "ledger", "release")
    }

    "an existing matching domain reservation is not released behind an active retry" {
        var releases = 0
        val coordinator = coordinator(
            reserveDomain = { completed(reserved(it, newlyReserved = false)) },
            claimDurably = { completed(OneTimeUseClaimResult.Busy) },
            releaseDomain = {
                releases++
                completed(true)
            },
        )

        coordinator.claim(request()).join() shouldBe OneTimeUseClaimResult.Busy
        releases shouldBe 0
    }

    "the caller receives authority only after both reservations are acquired" {
        val request = request()
        val durableClaim = OneTimeUseClaim.acquired(request, newlyCreated = true)
        val acquired = mutableListOf<OneTimeUseClaim>()
        val coordinator = coordinator(
            reserveDomain = { completed(reserved(it, newlyReserved = true)) },
            claimDurably = { completed(OneTimeUseClaimResult.Acquired(durableClaim)) },
            onAcquired = acquired::add,
        )

        coordinator.claim(request).join() shouldBe OneTimeUseClaimResult.Acquired(durableClaim)
        acquired shouldContainExactly listOf(durableClaim)
    }

    "a durable ledger cannot substitute a different claim identity" {
        val request = request()
        val forged = OneTimeUseClaim.acquired(
            request.copy(claimId = UUID.randomUUID()),
            newlyCreated = true,
        )
        val coordinator = coordinator(
            reserveDomain = { completed(reserved(it, newlyReserved = true)) },
            claimDurably = { completed(OneTimeUseClaimResult.Acquired(forged)) },
        )

        shouldThrowAny { coordinator.claim(request).join() }
    }

    "a durable consumed identity advances the matching domain reservation" {
        val consumed = mutableListOf<OneTimeUseClaim>()
        val coordinator = coordinator(
            reserveDomain = { completed(reserved(it, newlyReserved = true)) },
            claimDurably = { completed(OneTimeUseClaimResult.AlreadyConsumed) },
            consumeDomain = {
                consumed += it
                completed(true)
            },
        )
        val request = request()

        coordinator.claim(request).join() shouldBe OneTimeUseClaimResult.AlreadyConsumed
        consumed.map(OneTimeUseClaim::asRequest) shouldContainExactly listOf(request)
    }

    "an already consumed domain identity commits a missing durable mirror" {
        val request = request()
        val claim = OneTimeUseClaim.acquired(request, newlyCreated = true)
        val committed = mutableListOf<OneTimeUseClaim>()
        val coordinator = coordinator(
            reserveDomain = { completed(BuilderBookDomainReservation(OneTimeUseClaimResult.AlreadyConsumed)) },
            claimDurably = { completed(OneTimeUseClaimResult.Acquired(claim)) },
            commitDurably = {
                committed += it
                completed(OneTimeUseCommitResult.COMMITTED)
            },
        )

        coordinator.claim(request).join() shouldBe OneTimeUseClaimResult.AlreadyConsumed
        committed shouldContainExactly listOf(claim)
    }

    "consumed-domain reconciliation also rejects a substituted durable claim" {
        val request = request()
        val forged = OneTimeUseClaim.acquired(
            request.copy(claimantId = UUID.randomUUID()),
            newlyCreated = true,
        )
        val coordinator = coordinator(
            reserveDomain = { completed(BuilderBookDomainReservation(OneTimeUseClaimResult.AlreadyConsumed)) },
            claimDurably = { completed(OneTimeUseClaimResult.Acquired(forged)) },
        )

        shouldThrowAny { coordinator.claim(request).join() }
    }
})

private fun coordinator(
    reserveDomain: (OneTimeUseClaimRequest) -> CompletableFuture<BuilderBookDomainReservation>,
    claimDurably: (OneTimeUseClaimRequest) -> CompletableFuture<OneTimeUseClaimResult>,
    commitDurably: (OneTimeUseClaim) -> CompletableFuture<OneTimeUseCommitResult> = {
        completed(OneTimeUseCommitResult.COMMITTED)
    },
    consumeDomain: (OneTimeUseClaim) -> CompletableFuture<Boolean> = { completed(true) },
    releaseDomain: (OneTimeUseClaim) -> CompletableFuture<Boolean> = { completed(true) },
    onAcquired: (OneTimeUseClaim) -> Unit = {},
) = BuilderBookClaimCoordinator(
    reserveDomain = reserveDomain,
    claimDurably = claimDurably,
    commitDurably = commitDurably,
    consumeDomain = consumeDomain,
    releaseDomain = releaseDomain,
    onAcquired = onAcquired,
)

private fun reserved(
    request: OneTimeUseClaimRequest,
    newlyReserved: Boolean,
) = BuilderBookDomainReservation(
    result = OneTimeUseClaimResult.Acquired(OneTimeUseClaim.acquired(request, newlyCreated = newlyReserved)),
    newlyReserved = newlyReserved,
)

private fun request() = OneTimeUseClaimRequest(
    identity = OneTimeUseIdentity(
        useId = UUID.randomUUID(),
        fingerprint = OneTimeUseFingerprint.sha256(UUID.randomUUID().toString().toByteArray()),
    ),
    claimId = UUID.randomUUID(),
    claimantId = UUID.randomUUID(),
)

private fun <T> completed(value: T): CompletableFuture<T> = CompletableFuture.completedFuture(value)
