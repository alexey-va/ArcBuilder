package ru.arc

import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.builder.paper.ArcBuilderPlugin

/** Drive main-thread continuations while the real configuration worker prepares the plugin. */
fun awaitArcBuilderBootstrap(paper: MockBukkitTestRuntime, plugin: ArcBuilderPlugin) {
    val deadline = System.nanoTime() + 10_000_000_000L
    while (!plugin.bootstrapComplete && plugin.isEnabled && System.nanoTime() < deadline) {
        paper.performTicks(1)
        Thread.yield()
    }
    check(plugin.bootstrapComplete && plugin.isEnabled) { "ArcBuilder asynchronous bootstrap did not complete" }
}
