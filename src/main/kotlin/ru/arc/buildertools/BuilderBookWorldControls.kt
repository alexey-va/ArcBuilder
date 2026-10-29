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
    ): ActionPanelLayout<BuilderBookWorldAction> {
        val spacing = .85
        fun rect(x: Double, y: Double, width: Double, height: Double) =
            ActionPanelRect(ActionPanelPoint(x * spacing, y * spacing, 0.0), width * spacing, height * spacing)
        fun button(action: BuilderBookWorldAction, x: Double, y: Double, width: Double = .42,
            height: Double = .32, scale: Float = .9f): ActionPanelButton<BuilderBookWorldAction> {
            val area = rect(x, y, width, height)
            val direction = when (action) {
                BuilderBookWorldAction.LEFT -> 180f
                BuilderBookWorldAction.RIGHT -> 0f
                BuilderBookWorldAction.AWAY, BuilderBookWorldAction.UP -> 90f
                BuilderBookWorldAction.TOWARD, BuilderBookWorldAction.DOWN -> -90f
                else -> null
            }
            val textKey = when {
                direction != null -> "right"
                action == BuilderBookWorldAction.PRIMARY -> primary
                else -> action.localeKey
            }
            return ActionPanelButton(action, area, text(textKey),
                ActionPanelTextStyle(scale, (area.width * 40 / scale).toInt() - 5,
                    area.width.toFloat(), area.height.toFloat(), .025, 0, 0, direction ?: 0f),
                true, if (action == BuilderBookWorldAction.CANCEL) true else !busy &&
                    (action != BuilderBookWorldAction.PRIMARY || primaryEnabled))
        }
        fun label(key: String, y: Double, scale: Float, textKey: String = key): ActionPanelLabel {
            val area = rect(0.0, y, 3.1, .22)
            return ActionPanelLabel(key, area, text(textKey),
                ActionPanelTextStyle(scale, (area.width * 40 / scale).toInt() - 5,
                    area.width.toFloat(), area.height.toFloat(), .025))
        }
        return ActionPanelLayout(listOf(
            button(BuilderBookWorldAction.AWAY, -.55, .32),
            button(BuilderBookWorldAction.LEFT, -1.05, .08),
            button(BuilderBookWorldAction.RIGHT, -.05, .08),
            button(BuilderBookWorldAction.TOWARD, -.55, -.16),
            button(BuilderBookWorldAction.UP, .92, .32, .50),
            button(BuilderBookWorldAction.DOWN, .92, -.16, .50),
            button(BuilderBookWorldAction.ROTATE, -1.0, -.48, .94, .27, .55f),
            button(BuilderBookWorldAction.MIRROR, 0.0, -.48, .94, .27, .55f),
            button(BuilderBookWorldAction.RESET, 1.0, -.48, .94, .27, .55f),
            button(BuilderBookWorldAction.PRIMARY, -.50, -.84, 1.94, .42, .54f),
            button(BuilderBookWorldAction.CANCEL, 1.04, -.84, 1.0, .42, .5f),
        ), buildList {
            add(label("title", .64, .56f))
            if (status != "hint") add(label("status", .90, .46f, status))
        })
    }
}
