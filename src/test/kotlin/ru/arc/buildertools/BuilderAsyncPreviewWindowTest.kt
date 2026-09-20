package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import java.util.ArrayDeque
import java.util.concurrent.Executor

class BuilderAsyncPreviewWindowTest : FunSpec({
    class ManualExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        val pendingCount: Int
            get() = tasks.size

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runNext() {
            tasks.removeFirst().run()
        }
    }

    fun points() = listOf(
        BuilderPreviewPoint(0.0, 0.0, 0.0),
        BuilderPreviewPoint(10.0, 0.0, 0.0),
        BuilderPreviewPoint(20.0, 0.0, 0.0),
    )

    fun settleOne(scheduler: TestTaskScheduler, executor: ManualExecutor) {
        scheduler.executeImmediate()
        executor.runNext()
        scheduler.executeImmediate()
        executor.runNext()
    }

    test("calculates asynchronously and does not repeat the same eye") {
        val executor = ManualExecutor()
        val scheduler = TestTaskScheduler(executor)
        val scope = LifecycleTaskScope(scheduler)
        val changes = mutableListOf<List<Int>>()
        val failures = mutableListOf<Throwable>()
        val window = BuilderAsyncPreviewWindow(
            taskScope = scope,
            points = points(),
            limit = 2,
            onChange = { changes += it },
            onFailure = { failures += it },
        )

        window.update(BuilderPreviewPoint(1.0, 0.0, 0.0))
        window.update(BuilderPreviewPoint(1.0, 0.0, 0.0))
        changes shouldBe emptyList()
        executor.pendingCount shouldBe 0

        scheduler.executeImmediate()
        executor.pendingCount shouldBe 1
        changes shouldBe emptyList()
        settleOne(scheduler, executor)
        changes shouldBe listOf(listOf(0, 1))
        failures shouldBe emptyList()

        window.update(BuilderPreviewPoint(1.0, 0.0, 0.0))
        scheduler.executeImmediate()
        executor.pendingCount shouldBe 0
        changes shouldBe listOf(listOf(0, 1))

        window.close()
        scope.close()
    }

    test("keeps only the newest pending eye while movement continues") {
        val executor = ManualExecutor()
        val scheduler = TestTaskScheduler(executor)
        val scope = LifecycleTaskScope(scheduler)
        val changes = mutableListOf<List<Int>>()
        val window = BuilderAsyncPreviewWindow(
            taskScope = scope,
            points = points(),
            limit = 1,
            onChange = { changes += it },
            onFailure = { throw it },
        )

        window.update(BuilderPreviewPoint(0.0, 0.0, 0.0))
        scheduler.executeImmediate()
        executor.pendingCount shouldBe 1
        window.update(BuilderPreviewPoint(9.0, 0.0, 0.0))
        window.update(BuilderPreviewPoint(19.0, 0.0, 0.0))
        window.update(BuilderPreviewPoint(18.0, 0.0, 0.0))
        executor.pendingCount shouldBe 1

        settleOne(scheduler, executor)
        changes shouldBe listOf(listOf(0))
        executor.pendingCount shouldBe 0

        settleOne(scheduler, executor)
        changes shouldBe listOf(listOf(0), listOf(2))
        executor.pendingCount shouldBe 0

        window.close()
        scope.close()
    }

    test("invalidate and close reject late callbacks without wedging the next update") {
        val executor = ManualExecutor()
        val scheduler = TestTaskScheduler(executor)
        val scope = LifecycleTaskScope(scheduler)
        val changes = mutableListOf<List<Int>>()
        val failures = mutableListOf<Throwable>()
        val window = BuilderAsyncPreviewWindow(
            taskScope = scope,
            points = points(),
            limit = 1,
            onChange = { changes += it },
            onFailure = { failures += it },
        )

        window.update(BuilderPreviewPoint(0.0, 0.0, 0.0))
        scheduler.executeImmediate()
        window.invalidate()
        window.update(BuilderPreviewPoint(19.0, 0.0, 0.0))
        executor.pendingCount shouldBe 1
        settleOne(scheduler, executor)
        changes shouldBe emptyList()

        settleOne(scheduler, executor)
        changes shouldBe listOf(listOf(2))
        failures shouldBe emptyList()

        window.update(BuilderPreviewPoint(1.0, 0.0, 0.0))
        scheduler.executeImmediate()
        window.close()
        executor.runNext()
        scheduler.executeImmediate()
        executor.runNext()
        changes shouldBe listOf(listOf(2))

        scope.close()
    }

    test("a render callback failure is reported and does not block the next window") {
        val executor = ManualExecutor()
        val scheduler = TestTaskScheduler(executor)
        val scope = LifecycleTaskScope(scheduler)
        val changes = mutableListOf<List<Int>>()
        val failures = mutableListOf<Throwable>()
        var failFirstRender = true
        val window = BuilderAsyncPreviewWindow(
            taskScope = scope,
            points = points(),
            limit = 1,
            onChange = { indices ->
                if (failFirstRender) {
                    failFirstRender = false
                    error("render failed")
                }
                changes += indices
            },
            onFailure = { failures += it },
        )

        window.update(BuilderPreviewPoint(0.0, 0.0, 0.0))
        settleOne(scheduler, executor)
        failures.single().message shouldBe "render failed"
        changes shouldBe emptyList()

        // A later movement poll may retry the same eye after the failed render.
        window.update(BuilderPreviewPoint(0.0, 0.0, 0.0))
        settleOne(scheduler, executor)
        changes shouldBe listOf(listOf(0))

        window.close()
        scope.close()
    }

    test("reentrant invalidation after render does not cache the stale result") {
        val executor = ManualExecutor()
        val scheduler = TestTaskScheduler(executor)
        val scope = LifecycleTaskScope(scheduler)
        val changes = mutableListOf<List<Int>>()
        var invalidateAfterFirstRender = true
        lateinit var window: BuilderAsyncPreviewWindow
        window = BuilderAsyncPreviewWindow(
            taskScope = scope,
            points = points(),
            limit = 1,
            onChange = { indices ->
                changes += indices
                if (invalidateAfterFirstRender) {
                    invalidateAfterFirstRender = false
                    window.invalidate()
                }
            },
            onFailure = { throw it },
        )

        val eye = BuilderPreviewPoint(0.0, 0.0, 0.0)
        window.update(eye)
        settleOne(scheduler, executor)
        changes shouldBe listOf(listOf(0))

        window.update(eye)
        settleOne(scheduler, executor)
        changes shouldBe listOf(listOf(0), listOf(0))

        window.close()
        scope.close()
    }
})
