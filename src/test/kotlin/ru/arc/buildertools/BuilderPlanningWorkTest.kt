package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.core.BudgetedTaskQueue

class BuilderPlanningWorkTest : FunSpec({
    test("one count budget is shared fairly across one hundred simultaneous captures") {
        val scheduler = TestTaskScheduler()
        val scope = LifecycleTaskScope(scheduler)
        val pump = BudgetedTaskQueue(scope, maximumSteps = 128, nanoTime = { 0 })
        val counts = IntArray(100)
        val results = counts.indices.map { index -> pump.submit({ true }) { ++counts[index] == 3 } }
        scheduler.tick()
        counts.sum() shouldBe 128
        counts.min() shouldBe 1
        counts.max() shouldBe 2
        results.count { it.isDone } shouldBe 0
        scheduler.tick(2)
        counts.sum() shouldBe 300
        results.all { it.isDone && !it.isCompletedExceptionally } shouldBe true
        pump.close()
        scope.close()
    }

    test("time budget yields even when step count is below the maximum") {
        val scheduler = TestTaskScheduler()
        val scope = LifecycleTaskScope(scheduler)
        var nanos = 0L
        val pump = BudgetedTaskQueue(scope, maximumNanos = 2_000_000L, nanoTime = { nanos })
        var count = 0
        val result = pump.submit({ true }) { nanos += 1_000_000; ++count == 5 }
        scheduler.tick()
        count shouldBe 2
        result.isDone shouldBe false
        scheduler.tick(2)
        count shouldBe 5
        result.isCompletedExceptionally shouldBe false
        pump.close()
        scope.close()
    }

    test("stale context and close revoke queued native work without another step") {
        val scheduler = TestTaskScheduler()
        val scope = LifecycleTaskScope(scheduler)
        val pump = BudgetedTaskQueue(scope, maximumSteps = 1, nanoTime = { 0 })
        var current = true
        var nativeSteps = 0
        val stale = pump.submit({ current }) { nativeSteps++; false }
        scheduler.tick()
        current = false
        scheduler.tick()
        stale.isCompletedExceptionally shouldBe true
        nativeSteps shouldBe 1
        val closing = pump.submit({ true }) { nativeSteps++; false }
        pump.close()
        scheduler.tick()
        closing.isCompletedExceptionally shouldBe true
        nativeSteps shouldBe 1
        scope.close()
    }

    test("large capture spans ticks and close cancels worker completion publication") {
        val scheduler = TestTaskScheduler()
        val scope = LifecycleTaskScope(scheduler)
        val pump = BudgetedTaskQueue(scope, maximumSteps = 128, nanoTime = { 0 })
        var count = 0
        val capture = pump.submit({ true }) { ++count == 10_000 }
        scheduler.tick(78)
        count shouldBe 9_984
        capture.isDone shouldBe false
        scheduler.tick()
        count shouldBe 10_000
        val tasks = BuilderPlanningWork(scope)
        var computed = false
        val computation = tasks.compute { computed = true }
        tasks.close()
        scheduler.executeImmediate()
        computation.isCompletedExceptionally shouldBe true
        computed shouldBe false
        pump.close()
        scope.close()
    }
})
