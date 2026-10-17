package ru.arc.buildertools

import ru.arc.core.LifecycleTaskScope
import java.util.ArrayDeque
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture

/**
 * One server-thread budget shared by every Builder capture/revalidation request.
 * A step touches at most one block or exact item prototype. Round-robin admission
 * keeps concurrent players inside the same count/time budget, rather than giving
 * each player another full budget. Domain owners retain their immutable output.
 */
internal class BuilderPlanningPump(
    private val scope: LifecycleTaskScope,
    private val maximumSteps: Int = 128,
    private val maximumNanos: Long = 2_000_000L,
    private val nanoTime: () -> Long = System::nanoTime,
) : AutoCloseable {
    private data class Capture(
        val current: () -> Boolean,
        val step: () -> Boolean,
        val result: CompletableFuture<Unit>,
    )

    private val pending = ArrayDeque<Capture>()
    private var scheduled = false
    private var closed = false

    init {
        require(maximumSteps > 0 && maximumNanos > 0)
    }

    /** Invoke on the server thread. true means this capture has finished. */
    fun submit(isCurrent: () -> Boolean, step: () -> Boolean): CompletableFuture<Unit> {
        if (closed) return CompletableFuture.failedFuture(CancellationException("Builder planning is closed"))
        val result = CompletableFuture<Unit>()
        pending.addLast(Capture(isCurrent, step, result))
        schedule()
        return result
    }

    private fun schedule() {
        if (scheduled || closed || pending.isEmpty()) return
        scheduled = true
        try {
            checkNotNull(scope.runLater(1L, ::tick)) { "Builder planning capture was not scheduled" }
        } catch (failure: Throwable) {
            scheduled = false
            failAll(failure)
        }
    }

    private fun tick() {
        scheduled = false
        val start = nanoTime()
        var steps = 0
        while (!closed && pending.isNotEmpty() && steps < maximumSteps && nanoTime() - start < maximumNanos) {
            val capture = pending.removeFirst()
            steps++
            if (capture.result.isDone) continue
            try {
                if (!capture.current()) {
                    capture.result.completeExceptionally(CancellationException("Builder planning context changed"))
                } else if (capture.step()) {
                    capture.result.complete(Unit)
                } else {
                    pending.addLast(capture)
                }
            } catch (failure: Throwable) {
                capture.result.completeExceptionally(failure)
            }
        }
        schedule()
    }

    private fun failAll(failure: Throwable) {
        while (pending.isNotEmpty()) pending.removeFirst().result.completeExceptionally(failure)
    }

    override fun close() {
        if (closed) return
        closed = true
        failAll(CancellationException("Builder planning is closed"))
    }
}
