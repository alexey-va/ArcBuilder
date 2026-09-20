package ru.arc.buildertools

import ru.arc.core.LifecycleTaskScope

/**
 * Calculates the nearest preview points away from the server thread.
 *
 * The caller owns this object on the server thread. The worker only sees the
 * defensive point snapshot and primitive eye coordinates; all state changes
 * and callbacks return through the captured lifecycle token.
 */
internal class BuilderAsyncPreviewWindow(
    private val taskScope: LifecycleTaskScope,
    points: List<BuilderPreviewPoint>,
    private val limit: Int,
    private val onChange: (List<Int>) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) : AutoCloseable {
    private data class Request(
        val eye: BuilderPreviewPoint,
        val generation: Long,
    )

    private val points = points.toList()
    private val pointIndices = this.points.indices.toList()
    private var generation = 0L
    private var closed = false
    private var currentEye: BuilderPreviewPoint? = null
    private var pendingEye: BuilderPreviewPoint? = null
    private var inFlight: Request? = null
    private var lastIndices: List<Int>? = null

    init {
        require(limit > 0) { "Async preview window limit must be positive" }
    }

    /** Must be called on the server thread. */
    fun update(eye: BuilderPreviewPoint) {
        if (closed || currentEye == eye) return
        currentEye = eye
        if (inFlight != null) {
            // Keep only the newest movement while the current calculation runs.
            pendingEye = eye
            return
        }
        start(eye)
    }

    /** Must be called on the server thread when the source model/viewer changes. */
    fun invalidate() {
        if (closed) return
        generation = nextGeneration(generation)
        currentEye = null
        pendingEye = null
        lastIndices = null
        // Keep an existing worker counted as in-flight. Its result is fenced by
        // generation, and this preserves the one-worker/no-queue invariant.
    }

    override fun close() {
        if (closed) return
        closed = true
        generation = nextGeneration(generation)
        currentEye = null
        pendingEye = null
        lastIndices = null
        inFlight = null
    }

    private fun start(eye: BuilderPreviewPoint) {
        check(inFlight == null) { "Async preview window already has a calculation in flight" }
        val request = Request(eye, generation)
        inFlight = request
        val token = try {
            taskScope.token()
        } catch (failure: Throwable) {
            failScheduling(request, failure)
            return
        }
        val scheduled = try {
            taskScope.runAsync(token) {
                val result = runCatching { nearestIndices(request.eye) }
                // A stale lifecycle token prevents this callback from applying.
                taskScope.runSync(token) {
                    complete(request, result)
                }
            }
        } catch (failure: Throwable) {
            failScheduling(request, failure)
            return
        }
        if (scheduled == null) {
            failScheduling(
                request,
                IllegalStateException("Async preview window task was not scheduled"),
            )
        }
    }

    private fun nearestIndices(eye: BuilderPreviewPoint): List<Int> =
        BuilderPreviewWindow.nearest(
            values = pointIndices,
            limit = limit,
        ) { index ->
            val point = points[index]
            val dx = point.x - eye.x
            val dy = point.y - eye.y
            val dz = point.z - eye.z
            dx * dx + dy * dy + dz * dz
        }

    private fun complete(request: Request, result: Result<List<Int>>) {
        if (inFlight !== request) return
        inFlight = null
        if (closed) return
        try {
            if (request.generation == generation) {
                result.fold(
                    onSuccess = { indices ->
                        if (indices != lastIndices) {
                            try {
                                onChange(indices.toList())
                                if (!closed && request.generation == generation) {
                                    lastIndices = indices
                                }
                            } catch (failure: Throwable) {
                                // Let the next external movement poll retry this
                                // eye, without recursively scheduling a retry here.
                                currentEye = null
                                onFailure(failure)
                            }
                        }
                    },
                    onFailure = { failure ->
                        currentEye = null
                        onFailure(failure)
                    },
                )
            }
        } finally {
            drainPending(request)
        }
    }

    private fun failScheduling(request: Request, failure: Throwable) {
        if (inFlight !== request) return
        inFlight = null
        try {
            if (!closed) {
                currentEye = null
                onFailure(failure)
            }
        } finally {
            drainPending(request)
        }
    }

    private fun drainPending(completed: Request) {
        val next = pendingEye ?: return
        pendingEye = null
        if (closed) return
        if (next == completed.eye && completed.generation == generation) return
        start(next)
    }

    private fun nextGeneration(value: Long): Long =
        if (value == Long.MAX_VALUE) 1L else value + 1L
}
