package ru.arc.buildertools

import ru.arc.onetime.OneTimeUseClaim
import ru.arc.onetime.OneTimeUseReleaseResult
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Main-thread retry owner for exact book-reservation releases.
 *
 * An exceptional release has an unknown storage outcome, so the same claim is
 * retained and retried without minting a new operation id. Callbacks belong to
 * the initiating attempt and run exactly once; later retries only drain the
 * observable backlog.
 */
internal class BuilderBookReleaseQueue(
    private val release: (OneTimeUseClaim) -> CompletableFuture<OneTimeUseReleaseResult>,
    private val runSync: (() -> Unit) -> Unit,
    private val onPending: (OneTimeUseClaim, OneTimeUseReleaseResult?, Throwable?) -> Unit,
    private val onRecovered: (OneTimeUseClaim) -> Unit,
    private val onCallbackFailure: (Throwable) -> Unit = {},
) : AutoCloseable {
    private data class PendingRelease(
        val claim: OneTimeUseClaim,
        val callbacks: MutableList<() -> Unit> = mutableListOf(),
        var inFlight: Boolean = false,
        var failureReported: Boolean = false,
    )

    private val pending = linkedMapOf<UUID, PendingRelease>()
    private var closed = false

    val pendingCount: Int get() = pending.size

    fun request(claim: OneTimeUseClaim, done: () -> Unit = {}) {
        check(!closed) { "Builder-book release queue is closed" }
        val entry = pending.getOrPut(claim.claimId) { PendingRelease(claim) }
        require(entry.claim.asRequest() == claim.asRequest()) {
            "Builder-book release claim identity changed for ${claim.claimId}"
        }
        entry.callbacks += done
        attempt(entry)
    }

    fun retryPending() {
        if (closed) return
        pending.values.toList().forEach(::attempt)
    }

    private fun attempt(entry: PendingRelease) {
        if (closed || entry.inFlight) return
        entry.inFlight = true
        val future = try {
            release(entry.claim)
        } catch (failure: Throwable) {
            complete(entry, null, failure)
            return
        }
        future.whenComplete { result, failure ->
            runSync { complete(entry, result, failure) }
        }
    }

    private fun complete(
        entry: PendingRelease,
        result: OneTimeUseReleaseResult?,
        failure: Throwable?,
    ) {
        if (closed || pending[entry.claim.claimId] !== entry) return
        entry.inFlight = false
        val released = failure == null &&
            (result == OneTimeUseReleaseResult.RELEASED || result == OneTimeUseReleaseResult.ALREADY_RELEASED)
        val callbacks = entry.callbacks.toList()
        entry.callbacks.clear()
        if (released) {
            pending.remove(entry.claim.claimId, entry)
            if (entry.failureReported) onRecovered(entry.claim)
        } else if (!entry.failureReported) {
            entry.failureReported = true
            onPending(entry.claim, result, failure)
        }
        callbacks.forEach { callback -> runCatching(callback).onFailure(onCallbackFailure) }
    }

    override fun close() {
        if (closed) return
        closed = true
        pending.clear()
    }
}
