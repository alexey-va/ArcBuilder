package ru.arc.buildertools

import ru.arc.core.LifecycleTaskScope
import ru.arc.core.BudgetedTaskQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/** Runtime-owned scheduling boundary; workers receive detached calculation inputs only. */
internal class BuilderPlanningWork(private val scope: LifecycleTaskScope) : BuilderPlanningTasks, AutoCloseable {
    private val pump = BudgetedTaskQueue(scope)
    private val pending = ConcurrentHashMap.newKeySet<CompletableFuture<*>>()
    @Volatile private var closed = false

    override fun capture(isCurrent: () -> Boolean, step: () -> Boolean): CompletableFuture<Unit> =
        pump.submit(isCurrent, step)

    override fun <T> compute(action: () -> T): CompletableFuture<T> = schedule(false, action)

    override fun <T> onMain(action: () -> T): CompletableFuture<T> = schedule(true, action)

    private fun <T> schedule(main: Boolean, action: () -> T): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        pending += result
        result.whenComplete { _, _ -> pending -= result }
        val token = try { if (closed) null else scope.token() } catch (_: IllegalStateException) { null }
        if (token == null || !scope.isCurrent(token)) {
            result.completeExceptionally(CancellationException("Builder planning is closed"))
            return result
        }
        val run = {
            if (!result.isDone && !closed && scope.isCurrent(token)) {
                try { result.complete(action()) } catch (failure: Throwable) { result.completeExceptionally(failure) }
            } else {
                result.completeExceptionally(CancellationException("Builder planning is closed"))
            }
            Unit
        }
        try {
            val task = if (main) scope.runSync(token, run) else scope.runAsync(token, run)
            checkNotNull(task) { "Builder planning task was not scheduled" }
        } catch (failure: Throwable) {
            result.completeExceptionally(failure)
        }
        return result
    }

    override fun close() {
        closed = true
        pump.close()
        pending.toList().forEach { it.completeExceptionally(CancellationException("Builder planning is closed")) }
    }
}
