package ru.arc.buildertools

import net.kyori.adventure.text.Component
import ru.arc.paper.actionpanel.ActionPanelButton
import ru.arc.paper.actionpanel.ActionPanelLabel
import ru.arc.paper.actionpanel.ActionPanelLayout
import ru.arc.paper.actionpanel.ActionPanelPoint
import ru.arc.paper.actionpanel.ActionPanelRect
import ru.arc.paper.actionpanel.ActionPanelTextStyle

internal enum class BuilderBookWorldAction {
    LEFT, RIGHT, AWAY, TOWARD, UP, DOWN, ROTATE, MIRROR, RESET, PRIMARY, CANCEL;
    val localeKey: String get() = name.lowercase(java.util.Locale.ROOT)
}

/** Horizontal movement is relative to the player's view; height remains a separate world axis. */
internal object BuilderBookWorldControls {
    fun layout(
        text: (String) -> Component,
        primary: String,
        status: String = "hint",
        busy: Boolean = false,
        primaryEnabled: Boolean = true,
        distanceScale: Double = 1.0,
    ): ActionPanelLayout<BuilderBookWorldAction> {
        fun rect(x: Double, y: Double, width: Double, height: Double) =
            ActionPanelRect(ActionPanelPoint(x * distanceScale, y * distanceScale, 0.0), width * distanceScale, height * distanceScale)
        fun button(action: BuilderBookWorldAction, x: Double, y: Double, width: Double = .76,
            height: Double = .25, scale: Float = .8f): ActionPanelButton<BuilderBookWorldAction> =
            ActionPanelButton(action, rect(x, y, width, height), text(if (action == BuilderBookWorldAction.PRIMARY) primary else action.localeKey),
                ActionPanelTextStyle((scale * distanceScale).toFloat(), (width * 40 / scale).toInt() - 5,
                    (width * distanceScale).toFloat(), (height * distanceScale).toFloat(), .025 * distanceScale),
                true, if (action == BuilderBookWorldAction.CANCEL) true else !busy &&
                    (action != BuilderBookWorldAction.PRIMARY || primaryEnabled))
        fun label(key: String, x: Double, y: Double, width: Double, scale: Float = .48f, textKey: String = key) =
            ActionPanelLabel(key, rect(x, y, width, .18), text(textKey),
                ActionPanelTextStyle((scale * distanceScale).toFloat(), (width * 40 / scale).toInt() - 5,
                    (width * distanceScale).toFloat(), (.18 * distanceScale).toFloat(), .025 * distanceScale))
        return ActionPanelLayout(listOf(
            button(BuilderBookWorldAction.AWAY, -.55, .47),
            button(BuilderBookWorldAction.LEFT, -1.37, .18),
            button(BuilderBookWorldAction.RIGHT, .27, .18),
            button(BuilderBookWorldAction.TOWARD, -.55, -.11),
            button(BuilderBookWorldAction.UP, 1.12, .47, .9),
            button(BuilderBookWorldAction.DOWN, 1.12, -.11, .9),
            button(BuilderBookWorldAction.ROTATE, -1.08, -.46, 1.02, scale = .55f),
            button(BuilderBookWorldAction.MIRROR, 0.0, -.46, 1.02, scale = .55f),
            button(BuilderBookWorldAction.RESET, 1.08, -.46, 1.02, scale = .55f),
            button(BuilderBookWorldAction.PRIMARY, -.53, -.83, 2.12, .42, .54f),
            button(BuilderBookWorldAction.CANCEL, 1.12, -.83, .9, .42, .5f),
        ), listOf(
            label("title", 0.0, 1.0, 3.5, .56f),
            label("status", 0.0, .74, 3.5, .46f, status),
            label("height", 1.12, .18, .9, .46f),
        ))
    }
}
