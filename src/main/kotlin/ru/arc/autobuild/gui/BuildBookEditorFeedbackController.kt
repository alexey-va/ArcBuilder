package ru.arc.autobuild.gui

import org.bukkit.inventory.ItemStack
import ru.arc.core.LifecycleTaskScope

/** Owns one local editor rejection and prevents delayed restores from crossing GUI lifecycles. */
internal class BuildBookEditorFeedbackController(
    private val refresh: () -> Unit,
    private val taskScope: LifecycleTaskScope = LifecycleTaskScope(),
    private val durationTicks: Long = 40L,
) : AutoCloseable {
    private data class ActiveFeedback(
        val item: ItemStack,
        val normal: BuildBookEditorItemState,
    )

    private var active: ActiveFeedback? = null
    private var closed = false

    init {
        require(durationTicks > 0L) { "Build-book editor feedback duration must be positive" }
    }

    fun show(
        item: ItemStack,
        feedback: BuildBookEditorItemState,
    ) {
        if (closed) return
        restoreActive()
        val token = taskScope.restart()
        active = ActiveFeedback(item, BuildBookEditorPresentation.capture(item))
        feedback.applyTo(item)
        refresh()
        taskScope.runLater(token, durationTicks) {
            val current = active
            if (current?.item !== item) return@runLater
            active = null
            current.normal.applyTo(item)
            refresh()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            taskScope.close()
        } finally {
            restoreActive()
        }
    }

    private fun restoreActive() {
        val current = active ?: return
        active = null
        current.normal.applyTo(current.item)
    }
}
