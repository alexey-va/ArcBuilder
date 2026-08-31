package ru.arc.buildertools

import org.opentest4j.TestAbortedException

/**
 * Makes platform-test API gaps fail the scenario instead of being reported as an aborted/skipped test.
 *
 * arc-core 2.1.2 provides the runtime fixture, but the corresponding assertion helper is only
 * available in the next arc-core source revision. Keep the compatibility shim test-local until
 * ArcBuilder advances its pinned arc-core release.
 */
internal fun <T> failOnUnsupportedMockBukkitOperation(block: () -> T): T = try {
    block()
} catch (failure: Throwable) {
    val unsupported = generateSequence(failure as Throwable?) { it.cause }
        .firstOrNull { it is TestAbortedException }
    if (unsupported != null) {
        throw AssertionError("MockBukkit scenario reached an unsupported Paper API operation").apply {
            initCause(unsupported)
        }
    }
    throw failure
}
