package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import ru.arc.config.Config
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class BuilderDraftRecoveryIsolationTest : FunSpec({
    test("blocked journal load installs late and offline owners before isolation is ready") {
        MockBukkitTestRuntime.open().use { paper ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val records = AtomicReference(emptyList<BuilderDraftRecord>())
            DraftRecoveryIsolationHarness(paper) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                records.get()
            }.use { harness ->
                try {
                    harness.lifecycle.start()
                    entered.await(5, TimeUnit.SECONDS) shouldBe true
                    harness.lifecycle.isolationReady.isDone shouldBe false
                    val latePlayer = paper.addPlayer("LateDraft")
                    val offlineOwner = UUID.randomUUID()
                    records.set(listOf(draftIsolationRecord(latePlayer.uniqueId), draftIsolationRecord(offlineOwner), draftIsolationRecord(offlineOwner)))
                    harness.locks.isRecoveryLocked(latePlayer.uniqueId) shouldBe true
                    var holdsObservedAtCompletion = false
                    harness.lifecycle.isolationReady.thenRun {
                        harness.locks.isBookLocked(latePlayer.uniqueId) shouldBe true
                        harness.locks.isBookLocked(offlineOwner) shouldBe true
                        holdsObservedAtCompletion = true
                    }

                    release.countDown()
                    harness.workerBarrier()
                    harness.lifecycle.isolationReady.isDone shouldBe false
                    harness.main.executeImmediate()

                    harness.lifecycle.isolationReady.get(5, TimeUnit.SECONDS)
                    holdsObservedAtCompletion shouldBe true
                    harness.lifecycle.health().ready shouldBe true
                    harness.lifecycle.health().recoveryBlocked shouldBe true
                    verify(exactly = 0) { harness.storage.inspectSchematic(any()) }
                    harness.locks.endRecoveryBarrier()
                    harness.locks.isPlayerLocked(latePlayer.uniqueId) shouldBe true
                    harness.locks.isPlayerLocked(offlineOwner) shouldBe true
                    harness.lifecycle.close()
                    harness.locks.isBookLocked(latePlayer.uniqueId) shouldBe true
                    harness.locks.isBookLocked(offlineOwner) shouldBe true
                } finally { release.countDown() }
            }
        }
    }

    test("journal failure keeps isolation incomplete and reports a failed barrier") {
        MockBukkitTestRuntime.open().use { paper ->
            DraftRecoveryIsolationHarness(paper) { error("Unreadable journal") }.use { harness ->
                harness.lifecycle.start()
                harness.workerBarrier()
                harness.main.executeImmediate()

                harness.lifecycle.isolationReady.isCompletedExceptionally shouldBe true
                harness.lifecycle.health().ready shouldBe false
                harness.lifecycle.health().failed shouldBe true
                harness.locks.isRecoveryLocked(UUID.randomUUID()) shouldBe true
            }
        }
    }

    test("close before journal load completes cancels readiness and cannot install late owners") {
        MockBukkitTestRuntime.open().use { paper ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val owner = UUID.randomUUID()
            DraftRecoveryIsolationHarness(paper) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                listOf(draftIsolationRecord(owner))
            }.use { harness ->
                try {
                    harness.lifecycle.start()
                    entered.await(5, TimeUnit.SECONDS) shouldBe true
                    harness.lifecycle.close()
                    harness.lifecycle.isolationReady.isCancelled shouldBe true
                    release.countDown()
                    harness.workerBarrier()
                    harness.main.executeImmediate()

                    harness.lifecycle.isolationReady.isCancelled shouldBe true
                    harness.lifecycle.health().ready shouldBe false
                    harness.locks.isBookLocked(owner) shouldBe false
                } finally { release.countDown() }
            }
        }
    }
})

private class DraftRecoveryIsolationHarness(
    paper: MockBukkitTestRuntime,
    load: () -> List<BuilderDraftRecord>,
) : AutoCloseable {
    private val mainThread = Thread.currentThread()
    private val root = Files.createTempDirectory("builder-draft-isolation-")
    private val config = BuilderToolsConfig(Config(root, "modules/builder-tools.yml"))
    private val plugin = paper.createSimplePlugin("DraftIsolation")
    val main = TestTaskScheduler()
    private val scope = LifecycleTaskScope(main)
    private val executor = Executors.newSingleThreadExecutor()
    val locks = BuilderOperationLocks(plugin).also { it.beginRecoveryBarrier() }
    val storage = mockk<BuilderDraftStorage>(relaxed = true)
    private val journal = mockk<BuilderDraftJournal>().also { journal ->
        every { journal.loadAll() } answers {
            (Thread.currentThread() === mainThread) shouldBe false
            load()
        }
    }
    val lifecycle = BuilderDraftLifecycle(
        config, config.messages(), scope, executor, locks,
        mockk<BuilderBookLifecycleHost>(relaxed = true), journal, storage,
    )

    fun workerBarrier() { executor.submit {}.get(5, TimeUnit.SECONDS) }

    override fun close() {
        lifecycle.close()
        scope.close()
        locks.close()
        executor.shutdown()
        check(executor.awaitTermination(5, TimeUnit.SECONDS))
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}

private fun draftIsolationRecord(playerId: UUID): BuilderDraftRecord {
    val contentSha256 = "a".repeat(64)
    return BuilderDraftRecord(
        operationId = UUID.randomUUID(),
        playerId = playerId,
        playerName = "DraftOwner",
        title = "Recovery draft",
        buildingId = "player-${playerId.toString().replace("-", "")}-$contentSha256.schem",
        blueprintId = UUID.randomUUID(),
        contentSha256 = contentSha256,
        blockCount = 1,
        phase = BuilderDraftPhase.PREPARED,
        createdAtMillis = 1_800_000_000_000L,
        updatedAtMillis = 1_800_000_000_000L,
    ).validated()
}
