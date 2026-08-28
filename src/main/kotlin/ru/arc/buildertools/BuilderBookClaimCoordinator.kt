package ru.arc.buildertools

import ru.arc.onetime.OneTimeUseClaim
import ru.arc.onetime.OneTimeUseClaimRequest
import ru.arc.onetime.OneTimeUseClaimResult
import ru.arc.onetime.OneTimeUseCommitResult
import java.util.concurrent.CompletableFuture

internal data class BuilderBookDomainReservation(
    val result: OneTimeUseClaimResult,
    val newlyReserved: Boolean = false,
) {
    init {
        require(!newlyReserved || result is OneTimeUseClaimResult.Acquired) {
            "Only an acquired builder-book identity can be newly reserved"
        }
        require(result !is OneTimeUseClaimResult.Acquired || result.claim.newlyCreated == newlyReserved) {
            "Builder-book domain creation evidence must match its reservation result"
        }
    }
}

/**
 * Orders the two durable builder-book anti-duplication barriers.
 *
 * The domain row is reserved first because it records the operation, player
 * and backend needed by restart recovery. The shared one-time-use ledger is
 * still acquired before any payment, inventory or world mutation and retains
 * its live cross-node lock until commit/release/abandon.
 *
 * A durable-ledger failure deliberately leaves the domain reservation intact:
 * startup reconciliation can reacquire the exact claim from those persisted
 * fields. A newly-created domain reservation is released only for a proven
 * non-exceptional ledger rejection; an existing matching reservation may
 * belong to an in-flight retry and is never released behind it.
 */
internal class BuilderBookClaimCoordinator(
    private val reserveDomain: (OneTimeUseClaimRequest) -> CompletableFuture<BuilderBookDomainReservation>,
    private val claimDurably: (OneTimeUseClaimRequest) -> CompletableFuture<OneTimeUseClaimResult>,
    private val commitDurably: (OneTimeUseClaim) -> CompletableFuture<OneTimeUseCommitResult>,
    private val consumeDomain: (OneTimeUseClaim) -> CompletableFuture<Boolean>,
    private val releaseDomain: (OneTimeUseClaim) -> CompletableFuture<Boolean>,
    private val onAcquired: (OneTimeUseClaim) -> Unit,
) {
    fun claim(request: OneTimeUseClaimRequest): CompletableFuture<OneTimeUseClaimResult> =
        future { reserveDomain(request) }.thenCompose { reservation ->
            when (val domain = reservation.result) {
                is OneTimeUseClaimResult.Acquired -> {
                    require(domain.claim.asRequest() == request) {
                        "Builder-book domain reservation changed claim identity"
                    }
                    claimReserved(request, domain.claim, reservation.newlyReserved)
                }
                OneTimeUseClaimResult.AlreadyConsumed -> reconcileConsumed(request)
                else -> CompletableFuture.completedFuture(domain)
            }
        }

    private fun claimReserved(
        request: OneTimeUseClaimRequest,
        domainClaim: OneTimeUseClaim,
        newlyReserved: Boolean,
    ): CompletableFuture<OneTimeUseClaimResult> = future { claimDurably(request) }.thenCompose { durable ->
        when (durable) {
            is OneTimeUseClaimResult.Acquired -> {
                require(durable.claim.asRequest() == request) {
                    "Durable builder-book reservation changed claim identity"
                }
                onAcquired(durable.claim)
                CompletableFuture.completedFuture(durable)
            }
            OneTimeUseClaimResult.AlreadyConsumed -> future { consumeDomain(domainClaim) }.thenApply { consumed ->
                check(consumed) { "Could not reconcile consumed builder-book domain identity" }
                OneTimeUseClaimResult.AlreadyConsumed
            }
            else -> if (newlyReserved) {
                future { releaseDomain(domainClaim) }.thenApply { released ->
                    check(released) { "Could not release rejected builder-book domain reservation" }
                    durable
                }
            } else {
                CompletableFuture.completedFuture(durable)
            }
        }
    }

    private fun reconcileConsumed(request: OneTimeUseClaimRequest): CompletableFuture<OneTimeUseClaimResult> =
        future { claimDurably(request) }.thenCompose { durable ->
            when (durable) {
                is OneTimeUseClaimResult.Acquired -> {
                    require(durable.claim.asRequest() == request) {
                        "Durable builder-book reconciliation changed claim identity"
                    }
                    future { commitDurably(durable.claim) }.thenApply { committed ->
                        check(
                            committed == OneTimeUseCommitResult.COMMITTED ||
                                committed == OneTimeUseCommitResult.ALREADY_COMMITTED,
                        ) { "Could not reconcile consumed builder-book durable identity" }
                        OneTimeUseClaimResult.AlreadyConsumed
                    }
                }
                else -> CompletableFuture.completedFuture(durable)
            }
        }

    private fun <T> future(action: () -> CompletableFuture<T>): CompletableFuture<T> =
        runCatching(action).getOrElse { failure -> CompletableFuture.failedFuture(failure) }
}
