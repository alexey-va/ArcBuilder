package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import ru.arc.autobuild.Building
import ru.arc.config.Config
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import io.kotest.assertions.throwables.shouldThrow

class BuilderBookSqlBootstrapTest : FunSpec({
    test("blocked SQL factory leaves Paper free and a close before completion releases the late registry") {
        MockBukkitTestRuntime.open().use { paper ->
            val caller = Thread.currentThread()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val registry = mockk<BuilderBookRegistry>(relaxed = true)
            every { registry.close() } answers { (Thread.currentThread() === caller) shouldBe false }
            BookSqlBootstrapHarness(paper) {
                (Thread.currentThread() === caller) shouldBe false
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                registry
            }.use { harness ->
                try {
                    harness.lifecycle.start()
                    entered.await(5, TimeUnit.SECONDS) shouldBe true
                    harness.lifecycle.health().registryReady shouldBe false
                    harness.lifecycle.health().registryFailed shouldBe false
                    harness.lifecycle.close()
                    harness.lifecycle.closeCompletion.isDone shouldBe false
                    release.countDown()
                    harness.lifecycle.closeCompletion.get(5, TimeUnit.SECONDS)
                    harness.main.executeImmediate()

                    harness.lifecycle.health().registryReady shouldBe false
                    verify(exactly = 0) { registry.initialize() }
                    verify(exactly = 1) { registry.close() }
                } finally { release.countDown() }
            }
        }
    }

    test("migration completion after close cannot restore registry readiness or start recovery") {
        MockBukkitTestRuntime.open().use { paper ->
            val caller = Thread.currentThread()
            val initialized = CompletableFuture<Unit>()
            val registry = mockk<BuilderBookRegistry>(relaxed = true)
            every { registry.initialize() } answers {
                Thread.currentThread() shouldBe caller
                initialized
            }
            every { registry.close() } answers { (Thread.currentThread() === caller) shouldBe false }
            BookSqlBootstrapHarness(paper) { registry }.use { harness ->
                harness.lifecycle.start()
                harness.workerBarrier()
                harness.main.executeImmediate()
                harness.lifecycle.health().registryFailed shouldBe false
                verify(exactly = 1) { registry.initialize() }
                harness.lifecycle.health().registryReady shouldBe false

                harness.lifecycle.close()
                initialized.complete(Unit)
                harness.main.executeImmediate()
                harness.lifecycle.closeCompletion.get(5, TimeUnit.SECONDS)

                harness.lifecycle.health().registryReady shouldBe false
                verify(exactly = 0) { registry.openMints() }
                verify(exactly = 1) { registry.close() }
            }
        }
    }

    test("blocked and failed SQL close is asynchronous and remains visible to the runtime drain") {
        MockBukkitTestRuntime.open().use { paper ->
            val caller = Thread.currentThread()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val registry = mockk<BuilderBookRegistry>(relaxed = true)
            every { registry.initialize() } returns CompletableFuture<Unit>()
            every { registry.close() } answers {
                (Thread.currentThread() === caller) shouldBe false
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                error("SQL close failed")
            }
            BookSqlBootstrapHarness(paper) { registry }.use { harness ->
                try {
                    harness.lifecycle.start()
                    harness.workerBarrier()
                    harness.main.executeImmediate()
                    harness.lifecycle.close()
                    entered.await(5, TimeUnit.SECONDS) shouldBe true
                    harness.lifecycle.closeCompletion.isDone shouldBe false
                    release.countDown()

                    shouldThrow<ExecutionException> {
                        harness.lifecycle.closeCompletion.get(5, TimeUnit.SECONDS)
                    }.cause?.message shouldBe "SQL close failed"
                } finally { release.countDown() }
            }
        }
    }

    test("SQL acquisition failure reports unavailable without native readiness") {
        MockBukkitTestRuntime.open().use { paper ->
            BookSqlBootstrapHarness(paper) { error("SQL unavailable") }.use { harness ->
                harness.lifecycle.start()
                harness.workerBarrier()
                harness.main.executeImmediate()

                harness.lifecycle.health().registryFailed shouldBe true
                harness.lifecycle.health().registryReady shouldBe false
                harness.lifecycle.close()
                harness.lifecycle.closeCompletion.get(5, TimeUnit.SECONDS)
            }
        }
    }
})

private class BookSqlBootstrapHarness(
    paper: MockBukkitTestRuntime,
    factory: () -> BuilderBookRegistry,
) : AutoCloseable {
    private val root = Files.createTempDirectory("builder-book-sql-bootstrap-")
    private val config = BuilderToolsConfig(Config(root, "modules/builder-tools.yml").apply {
        setBoolean("book-contracts.enabled", true)
    })
    private val plugin = paper.createSimplePlugin("BookSqlBootstrap")
    val main = TestTaskScheduler()
    private val scope = LifecycleTaskScope(main)
    private val executor = Executors.newSingleThreadExecutor()
    private val locks = BuilderOperationLocks(plugin)
    private val host = mockk<BuilderBookLifecycleHost>(relaxed = true)
    val lifecycle = BuilderBookLifecycle(
        config = config,
        messages = config.messages(),
        taskScope = scope,
        storageExecutor = executor,
        operationLocks = locks,
        host = host,
        draftJournal = BuilderDraftJournal(root, 100),
        draftStorage = mockk<BuilderDraftStorage>(relaxed = true),
        loadBuilding = { _, _ -> CompletableFuture.failedFuture<Building>(IllegalStateException("Not used")) },
        registryFactory = factory,
        walletFactory = { mockk<BuilderBookWallet>(relaxed = true) },
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
