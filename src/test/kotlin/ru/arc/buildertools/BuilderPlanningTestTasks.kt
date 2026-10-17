package ru.arc.buildertools

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/** Existing domain oracles run immediately; asynchronous boundary tests use [QueuedBuilderPlanningTasks]. */
internal object ImmediateBuilderPlanningTasks : BuilderPlanningTasks {
    override fun capture(isCurrent: () -> Boolean, step: () -> Boolean): CompletableFuture<Unit> = onMain {
        do {
            requireCurrentBuilderPlanning(isCurrent)
        } while (!step())
    }

    override fun <T> compute(action: () -> T): CompletableFuture<T> = onMain(action)

    override fun <T> onMain(action: () -> T): CompletableFuture<T> = try {
        CompletableFuture.completedFuture(action())
    } catch (failure: Throwable) {
        CompletableFuture.failedFuture(failure)
    }
}

/** Test clock: only tick() runs native work, while runComputations() uses a real worker thread. */
internal class QueuedBuilderPlanningTasks : BuilderPlanningTasks {
    private val mainThread = Thread.currentThread()
    private val captures = ConcurrentLinkedQueue<() -> Boolean>()
    private val mainActions = ConcurrentLinkedQueue<() -> Unit>()
    private val computations = ConcurrentLinkedQueue<() -> Unit>()
    var captureSteps = 0
        private set
    var workerRuns = 0
        private set

    fun assertMain() = check(Thread.currentThread() === mainThread) { "Native planning work escaped the server thread" }

    override fun capture(isCurrent: () -> Boolean, step: () -> Boolean): CompletableFuture<Unit> {
        assertMain()
        val future = CompletableFuture<Unit>()
        captures += {
            try {
                assertMain()
                requireCurrentBuilderPlanning(isCurrent)
                captureSteps++
                step().also { done -> if (done) future.complete(Unit) }
            } catch (failure: Throwable) {
                future.completeExceptionally(failure)
                true
            }
        }
        return future
    }

    override fun <T> compute(action: () -> T): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        computations += {
            check(Thread.currentThread() !== mainThread)
            workerRuns++
            try {
                future.complete(action())
            } catch (failure: Throwable) {
                future.completeExceptionally(failure)
            }
        }
        return future
    }

    override fun <T> onMain(action: () -> T): CompletableFuture<T> {
        if (Thread.currentThread() === mainThread) return ImmediateBuilderPlanningTasks.onMain(action)
        val future = CompletableFuture<T>()
        mainActions += {
            try {
                future.complete(action())
            } catch (failure: Throwable) {
                future.completeExceptionally(failure)
            }
        }
        return future
    }

    fun tick(maximumSteps: Int = 128) {
        assertMain()
        while (true) (mainActions.poll() ?: break).invoke()
        repeat(maximumSteps) {
            val step = captures.poll() ?: return
            if (!step()) captures += step
        }
    }

    fun runComputations() {
        assertMain()
        CompletableFuture.runAsync {
            while (true) (computations.poll() ?: break).invoke()
        }.get(5, TimeUnit.SECONDS)
    }

    fun <T> finish(future: CompletableFuture<T>): T {
        repeat(1_000) {
            if (future.isDone) return future.awaitBuilderPlanning()
            tick()
            runComputations()
        }
        error("Planning did not finish after 1000 test ticks")
    }
}

internal fun <T> CompletableFuture<T>.awaitBuilderPlanning(): T {
    check(isDone) { "Drive the planning test clock before observing its result" }
    return try {
        join()
    } catch (failure: CompletionException) {
        throw failure.cause ?: failure
    }
}
