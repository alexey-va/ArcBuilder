package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BuilderToolsReloadServiceTest : FunSpec({
    test("durable construction reload waits only for an in-flight write or completion") {
        builderConstructionReloadBlocked(writesInFlight = 0, completionsInFlight = 0) shouldBe false
        builderConstructionReloadBlocked(writesInFlight = 1, completionsInFlight = 0) shouldBe true
        builderConstructionReloadBlocked(writesInFlight = 0, completionsInFlight = 1) shouldBe true
    }

    test("candidate validation failure leaves the active runtime completely untouched") {
        val harness = ReloadHarness()
        val active = FakeReloadRuntime()
        var factoryCalls = 0
        val service = harness.service(active, load = { error("invalid limits.blocks-per-tick") }, create = {
            factoryCalls += 1
            FakeReloadRuntime()
        })
        val pending = service.reload()
        harness.drain()

        val result = pending.join().shouldBeInstanceOf<BuilderToolsReloadResult.Rejected>()
        result.failure.message shouldBe "invalid limits.blocks-per-tick"
        service.runtime shouldBe active
        service.config shouldBe "previous"
        active.closeCalls shouldBe 0
        factoryCalls shouldBe 0
    }

    test("unsafe active generation rejects reload before loading or closing anything") {
        val harness = ReloadHarness()
        val active = FakeReloadRuntime(BuilderToolsReloadBlocker.ACTIVE_OPERATION)
        val service = harness.service(active, load = { error("must not load") })

        service.reload().join() shouldBe BuilderToolsReloadResult.Busy(BuilderToolsReloadBlocker.ACTIVE_OPERATION)
        service.runtime shouldBe active
        active.closeCalls shouldBe 0
        harness.io.size shouldBe 0
    }

    test("blocker acquired during preparation preserves the active generation") {
        val harness = ReloadHarness()
        val active = FakeReloadRuntime()
        val service = harness.service(active)
        val pending = service.reload()
        active.blocker = BuilderToolsReloadBlocker.PENDING_PREVIEW
        harness.drain()

        pending.join() shouldBe BuilderToolsReloadResult.Busy(BuilderToolsReloadBlocker.PENDING_PREVIEW)
        service.runtime shouldBe active
        active.closeCalls shouldBe 0
    }

    test("successful reload waits for the old storage drain and publishes before native activation") {
        val harness = ReloadHarness()
        val drained = CompletableFuture<Unit>()
        val events = mutableListOf<String>()
        val active = FakeReloadRuntime(onClose = { events += "close" })
        val replacement = FakeReloadRuntime()
        val service = harness.service(
            active,
            create = { events += "activate"; replacement },
            publish = { events += "publish"; {} },
            awaitClosed = { drained },
        )
        val pending = service.reload()
        harness.drain()
        pending.isDone shouldBe false
        service.runtime shouldBe null
        events shouldBe listOf("close")

        drained.complete(Unit)
        harness.drain()
        pending.join() shouldBe BuilderToolsReloadResult.Applied("candidate")
        events shouldBe listOf("close", "publish", "activate")
        service.runtime shouldBe replacement
        service.config shouldBe "candidate"
        replacement.closeCalls shouldBe 0
    }

    test("candidate activation failure restores publication before reconstructing the previous generation") {
        val harness = ReloadHarness()
        val events = mutableListOf<String>()
        val rollback = FakeReloadRuntime()
        val service = harness.service(
            create = { configured ->
                events += "activate-$configured"
                if (configured == "candidate") error("candidate startup failed")
                rollback
            },
            publish = { events += "publish"; { events += "restore-files"; Unit } },
        )
        val pending = service.reload()
        harness.drain()

        val result = pending.join().shouldBeInstanceOf<BuilderToolsReloadResult.Rejected>()
        result.failure.message shouldBe "candidate startup failed"
        result.rollbackFailure shouldBe null
        events shouldBe listOf("publish", "activate-candidate", "restore-files", "activate-previous")
        service.runtime shouldBe rollback
        service.config shouldBe "previous"
    }

    test("publication failure never constructs the candidate and restores the previous generation") {
        val harness = ReloadHarness()
        val activated = mutableListOf<String>()
        val rollback = FakeReloadRuntime()
        val service = harness.service(
            create = { activated += it; rollback },
            publish = { error("publish failed") },
        )
        val pending = service.reload()
        harness.drain()

        pending.join().shouldBeInstanceOf<BuilderToolsReloadResult.Rejected>().failure.message shouldBe "publish failed"
        activated shouldBe listOf("previous")
        service.runtime shouldBe rollback
    }

    test("candidate and rollback activation failures leave no pretend runtime") {
        val harness = ReloadHarness()
        val service = harness.service(create = { error("$it failed") })
        val pending = service.reload()
        harness.drain()

        val result = pending.join().shouldBeInstanceOf<BuilderToolsReloadResult.Rejected>()
        result.failure.message shouldBe "candidate failed"
        result.rollbackFailure?.message shouldBe "previous failed"
        service.runtime shouldBe null
        service.config shouldBe "previous"
    }

    test("failed publication rollback prevents native reconstruction and reports failed closed") {
        val harness = ReloadHarness()
        val diskFailure = IllegalStateException("disk restore failed")
        val service = harness.service(
            create = { error("must not activate") },
            publish = { throw BuilderToolsConfigPublicationException(IllegalStateException("merge failed"), diskFailure) },
        )
        val pending = service.reload()
        harness.drain()

        pending.join().shouldBeInstanceOf<BuilderToolsReloadResult.Rejected>().rollbackFailure shouldBe diskFailure
        service.runtime shouldBe null
    }

    test("activation failure storage drain fences file rollback and previous runtime construction") {
        val harness = ReloadHarness()
        val drained = CompletableFuture<Unit>()
        var restored = false
        val service = harness.service(
            create = { if (it == "candidate") error("activation failed") else FakeReloadRuntime() },
            publish = { { restored = true } },
            activationFailureDrain = { drained },
        )
        val pending = service.reload()
        harness.drain()
        pending.isDone shouldBe false
        restored shouldBe false
        drained.complete(Unit)
        harness.drain()
        restored shouldBe true
        pending.join().shouldBeInstanceOf<BuilderToolsReloadResult.Rejected>().rollbackFailure shouldBe null
    }

    test("disable cancels a pending prepare and a duplicate reload does not enqueue another load") {
        val harness = ReloadHarness()
        val active = FakeReloadRuntime()
        val service = harness.service(active, load = { error("stale preparation must not run") })
        val pending = service.reload()
        service.reload().join() shouldBe BuilderToolsReloadResult.Busy(BuilderToolsReloadBlocker.STARTING_OR_RECOVERING)
        harness.io.size shouldBe 1
        service.close()
        harness.drain()
        pending.join() shouldBe BuilderToolsReloadResult.Cancelled
        service.runtime shouldBe null
        active.closeCalls shouldBe 1
    }

    test("disable drops an already queued publication completion without resurrecting a runtime") {
        val harness = ReloadHarness()
        var factoryCalls = 0
        val service = harness.service(create = { factoryCalls += 1; FakeReloadRuntime() })
        val pending = service.reload()
        harness.runIo()
        harness.main.executeImmediate()
        harness.main.executeImmediate()
        harness.runIo()
        service.close()
        harness.drain()
        pending.join() shouldBe BuilderToolsReloadResult.Cancelled
        factoryCalls shouldBe 0
        service.runtime shouldBe null
    }

    test("blocked real I/O returns immediately and publication rollback stays off the lifecycle thread") {
        val caller = Thread.currentThread()
        val executor = Executors.newSingleThreadExecutor()
        val main = TestTaskScheduler()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val active = FakeReloadRuntime(onClose = { Thread.currentThread() shouldBe caller })
        val service = BuilderToolsReloadService(
            initialConfig = "previous",
            initialRuntime = active,
            loadCandidate = {
                (Thread.currentThread() === caller) shouldBe false
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                "candidate"
            },
            createRuntime = {
                Thread.currentThread() shouldBe caller
                if (it == "candidate") error("activation failed")
                FakeReloadRuntime()
            },
            reloadBlocker = FakeReloadRuntime::blocker,
            publishConfig = {
                (Thread.currentThread() === caller) shouldBe false
                val rollback: () -> Unit = { (Thread.currentThread() === caller) shouldBe false }
                rollback
            },
            validateCandidate = { Thread.currentThread() shouldBe caller },
            executor = executor,
            tasks = LifecycleTaskScope(main),
        )
        try {
            val pending = service.reload()
            entered.await(5, TimeUnit.SECONDS) shouldBe true
            pending.isDone shouldBe false
            active.closeCalls shouldBe 0
            release.countDown()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!pending.isDone && System.nanoTime() < deadline) {
                main.executeImmediate()
                Thread.yield()
            }
            pending.isDone shouldBe true
            pending.join().shouldBeInstanceOf<BuilderToolsReloadResult.Rejected>().rollbackFailure shouldBe null
        } finally {
            release.countDown()
            service.close()
            executor.shutdownNow()
        }
    }
})

private class ReloadHarness {
    val main = TestTaskScheduler()
    val io = ArrayDeque<Runnable>()
    fun runIo() = io.removeFirst().run()
    fun drain() {
        repeat(20) {
            if (io.isEmpty() && main.pendingCount() == 0) return
            if (io.isNotEmpty()) runIo()
            main.executeImmediate()
        }
        error("Reload transaction did not settle")
    }

    fun service(
        active: FakeReloadRuntime = FakeReloadRuntime(),
        load: () -> String = { "candidate" },
        create: (String) -> FakeReloadRuntime = { FakeReloadRuntime() },
        publish: (String) -> (() -> Unit) = { {} },
        awaitClosed: (FakeReloadRuntime) -> CompletableFuture<Unit> = { CompletableFuture.completedFuture(Unit) },
        activationFailureDrain: (Throwable) -> CompletableFuture<Unit> = { CompletableFuture.completedFuture(Unit) },
    ) = BuilderToolsReloadService(
        initialConfig = "previous",
        initialRuntime = active,
        loadCandidate = load,
        createRuntime = create,
        reloadBlocker = FakeReloadRuntime::blocker,
        publishConfig = publish,
        executor = Executor { io.addLast(it) },
        tasks = LifecycleTaskScope(main),
        awaitClosed = awaitClosed,
        activationFailureDrain = activationFailureDrain,
    )
}

private class FakeReloadRuntime(
    var blocker: BuilderToolsReloadBlocker? = null,
    val onClose: () -> Unit = {},
) : AutoCloseable {
    var closeCalls: Int = 0
        private set

    override fun close() {
        closeCalls += 1
        onClose()
    }
}
