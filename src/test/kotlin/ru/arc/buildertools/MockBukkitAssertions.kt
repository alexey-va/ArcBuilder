package ru.arc.buildertools

import org.opentest4j.TestAbortedException

/**
 * Makes platform-test API gaps fail the scenario instead of being reported as an aborted/skipped test.
 *
 * Keep this compatibility shim test-local so an unsupported MockBukkit operation cannot silently
 * turn a required ArcBuilder regression scenario into a skipped test.
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
