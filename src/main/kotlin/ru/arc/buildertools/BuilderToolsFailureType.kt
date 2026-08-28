package ru.arc.buildertools

import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

/** Secret-safe exception classification for SQL, economy, and plugin boundaries. */
internal object BuilderToolsFailureType {
    private val SAFE_NAME = Regex("[A-Za-z0-9_$]{1,80}")

    fun of(failure: Throwable?): String {
        if (failure == null) return "missing_result"
        val name = unwrapAsyncBoundary(failure).javaClass.simpleName
        return name.takeIf(SAFE_NAME::matches) ?: "unknown_failure"
    }

    private fun unwrapAsyncBoundary(failure: Throwable): Throwable {
        var current = failure
        repeat(MAX_ASYNC_BOUNDARIES) {
            val cause = current.cause
            if (cause == null || current !is CompletionException && current !is ExecutionException) return current
            current = cause
        }
        return current
    }

    private const val MAX_ASYNC_BOUNDARIES = 8
}
