package ru.arc.buildertools

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.onetime.OneTimeUseClaim
import ru.arc.onetime.OneTimeUseClaimRequest
import ru.arc.onetime.OneTimeUseFingerprint
import ru.arc.onetime.OneTimeUseIdentity
import ru.arc.onetime.OneTimeUseReleaseResult
import ru.arc.onetime.OneTimeUseScope
import java.util.UUID
import java.util.concurrent.CompletableFuture

class BuilderBookReleaseQueueTest : FunSpec({
    test("unknown release is retained and the same claim recovers without repeating its callback") {
        val claim = claim()
        val attempts = ArrayDeque<CompletableFuture<OneTimeUseReleaseResult>>()
        val first = CompletableFuture<OneTimeUseReleaseResult>()
        attempts += first
        var callbackCount = 0
        var pendingReports = 0
        var recoveredReports = 0
        val queue = BuilderBookReleaseQueue(
            release = { attempts.removeFirst() },
            runSync = { it() },
            onPending = { _, _, _ -> pendingReports++ },
            onRecovered = { recoveredReports++ },
        )

        queue.request(claim) { callbackCount++ }
        first.completeExceptionally(IllegalStateException("storage unavailable"))

        queue.pendingCount shouldBe 1
        callbackCount shouldBe 1
        pendingReports shouldBe 1

        attempts += CompletableFuture.completedFuture(OneTimeUseReleaseResult.RELEASED)
        queue.retryPending()

        queue.pendingCount shouldBe 0
        callbackCount shouldBe 1
        pendingReports shouldBe 1
        recoveredReports shouldBe 1
    }

    test("duplicate requests share one in-flight release and finish every initiating callback once") {
        val claim = claim()
        val release = CompletableFuture<OneTimeUseReleaseResult>()
        var releaseCalls = 0
        var callbackCount = 0
        var callbackFailures = 0
        val queue = BuilderBookReleaseQueue(
            release = {
                releaseCalls++
                release
            },
            runSync = { it() },
            onPending = { _, _, _ -> },
            onRecovered = {},
            onCallbackFailure = { callbackFailures++ },
        )

        queue.request(claim) { error("first callback failed") }
        queue.request(claim.copy(newlyCreated = false)) { callbackCount++ }
        releaseCalls shouldBe 1

        release.complete(OneTimeUseReleaseResult.ALREADY_RELEASED)

        queue.pendingCount shouldBe 0
        callbackCount shouldBe 1
        callbackFailures shouldBe 1
    }

    test("one operation id cannot be rebound to a different book identity") {
        val original = claim()
        val neverCompletes = CompletableFuture<OneTimeUseReleaseResult>()
        val queue = BuilderBookReleaseQueue(
            release = { neverCompletes },
            runSync = { it() },
            onPending = { _, _, _ -> },
            onRecovered = {},
        )
        queue.request(original)

        shouldThrow<IllegalArgumentException> {
            queue.request(
                original.copy(
                    identity = OneTimeUseIdentity(UUID.randomUUID(), original.identity.fingerprint),
                ),
            )
        }
    }
})

private fun claim(): OneTimeUseClaim = OneTimeUseClaim.acquired(
    OneTimeUseClaimRequest(
        identity = OneTimeUseIdentity(
            UUID.fromString("11111111-2222-3333-4444-555555555555"),
            OneTimeUseFingerprint.parse("a".repeat(64)),
        ),
        claimId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
        claimantId = UUID.fromString("99999999-8888-7777-6666-555555555555"),
        scope = OneTimeUseScope.parse("survival"),
    ),
    newlyCreated = true,
)
