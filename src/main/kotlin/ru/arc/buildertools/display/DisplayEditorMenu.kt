package ru.arc.buildertools.display

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import ru.arc.buildertools.BuilderDialogPresentation
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogInputId
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogNumberRangeInput
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.menu.PaperDialogTextInput
import kotlin.math.roundToInt

/** Pure, inspectable native-dialog composition; callbacks always return to the editor's authority boundary. */
internal object DisplayEditorMenu {
    val TEXT = PaperDialogInputId.of("display_text")
    val SCALE = listOf("scale_x", "scale_y", "scale_z").map(PaperDialogInputId::of)

    fun root(
        text: (String) -> Component,
        mode: DisplayEditorMode,
        selected: Boolean,
        selectedText: Boolean,
        canUndo: Boolean,
        selection: Component?,
        presentation: BuilderDialogPresentation = BuilderDialogPresentation(),
        action: (String) -> Unit,
    ): PaperDialogScreen = PaperDialogScreen(
        id = "builder.display-editor",
        title = text("title"),
        body = listOfNotNull(presentation.paragraph(text("intro"), 400),
            selection?.let { PaperDialogBody(it, 400) }, presentation.divider(400),
            presentation.callout(text("controls"), 400)),
        buttons = buildList {
            add(button("block", text("create-block"), text("create-block-help"), action))
            add(button("item", text("create-item"), text("create-item-help"), action))
            add(button("text", text("create-text"), text("create-text-help"), action))
            DisplayEditorMode.entries.forEach { option ->
                val id = option.name.lowercase()
                add(button(id, text("mode-${if (option == mode) "selected" else "option"}-$id"), text("mode-help-$id"), action))
            }
            add(button("axis", text("axis-button"), text("axis-help"), action))
            add(button("enter", text("enter"), text("enter-help"), action))
            add(button("exit", text("exit"), text("exit-help"), action))
            if (selected) {
                add(button("duplicate", text("duplicate"), text("duplicate-help"), action))
                add(button("delete", text("delete"), text("delete-help"), action))
                if (selectedText) add(button("edit_text", text("edit-text"), text("edit-text-help"), action))
            }
            if (canUndo) add(button("undo", text("undo"), text("undo-help"), action))
        },
        columns = 3,
    )

    fun axes(
        text: (String) -> Component,
        selected: DisplayEditorAxis,
        scale: DisplayEditorScale?,
        selection: Component?,
        feedback: Component?,
        chooseAxis: (DisplayEditorAxis, DisplayEditorScale?) -> Unit,
        applyScale: (DisplayEditorScale?) -> Unit,
        reset: (Boolean) -> Unit,
        presentation: BuilderDialogPresentation = BuilderDialogPresentation(),
    ) = PaperDialogScreen(
        id = "builder.display-editor.axes",
        title = text("axis-title"),
        body = listOfNotNull(
            presentation.paragraph(text("axis-body"), 380),
            selection?.let { PaperDialogBody(it, 380) },
            presentation.divider(380),
            presentation.callout(text(if (scale == null) "scale-select-first" else "scale-body"), 380),
            feedback?.let { PaperDialogBody(it, 380) },
        ),
        numberInputs = scale?.values()?.mapIndexed { index, value ->
            PaperDialogNumberRangeInput(
                SCALE[index], text("scale-label-${"xyz"[index]}"),
                5f, 400f, (value * 100).roundToInt().coerceIn(5, 400).toFloat(), 1f, 360,
            )
        }.orEmpty(),
        buttons = buildList {
            DisplayEditorAxis.entries.forEach { axis -> add(PaperDialogButton(
                id = PaperDialogActionId.of("axis_${axis.name.lowercase()}"),
                label = text("axis-${if (axis == selected) "selected" else "option"}-${axis.name.lowercase()}"),
                width = 180,
                onClick = { chooseAxis(axis, readScale(it)) },
            )) }
            if (scale != null) {
                add(PaperDialogButton(PaperDialogActionId.of("apply_scale"), text("apply-scale"), text("apply-scale-help"), 180,
                    onClick = { applyScale(readScale(it)) }))
                add(PaperDialogButton(PaperDialogActionId.of("reset_scale"), text("reset-scale"), text("reset-scale-help"), 180,
                    onClick = { reset(false) }))
                add(PaperDialogButton(PaperDialogActionId.of("reset_rotation"), text("reset-rotation"), text("reset-rotation-help"), 180,
                    onClick = { reset(true) }))
            }
        },
        columns = 2,
    )

    private fun readScale(context: PaperDialogClickContext): DisplayEditorScale? {
        val values = SCALE.map { context.number(it) ?: return null }
        if (values.any { !it.isFinite() || it !in 5f..400f || it % 1f != 0f }) return null
        return DisplayEditorScale(values[0] / 100, values[1] / 100, values[2] / 100).takeIf(DisplayEditorScale::valid)
    }

    fun textForm(text: (String) -> Component, initial: String, editing: Boolean, invalid: Boolean, submit: (String) -> Unit) = PaperDialogScreen(
        id = "builder.display-editor.text",
        title = text(if (editing) "edit-text-title" else "text-title"),
        body = listOfNotNull(PaperDialogBody(text("text-body"), 400), if (invalid) PaperDialogBody(text("invalid-text"), 400) else null),
        inputs = listOf(PaperDialogTextInput(TEXT, text("text-label"), initial.replace("\n", "\\n").take(160), 380, 160)),
        buttons = listOf(PaperDialogButton(
            id = PaperDialogActionId.of("save_text"),
            label = text(if (editing) "save-text" else "place-text"),
            width = 240,
            onClick = { submit(it.text(TEXT).orEmpty()) },
        )),
    )

    fun deletion(text: (String) -> Component, selection: Component, confirm: () -> Unit) = PaperDialogScreen(
        id = "builder.display-editor.delete",
        title = text("delete-title"),
        body = listOf(PaperDialogBody(selection, 380), PaperDialogBody(text("delete-body"), 380)),
        buttons = listOf(PaperDialogButton(
            id = PaperDialogActionId.of("confirm_delete"),
            label = text("confirm-delete"),
            width = 240,
            onClick = { confirm() },
        )),
    )

    private fun button(id: String, label: Component, tooltip: Component, action: (String) -> Unit) = PaperDialogButton(
        id = PaperDialogActionId.of(id),
        label = label.decoration(TextDecoration.ITALIC, false),
        tooltip = tooltip.decoration(TextDecoration.ITALIC, false),
        width = 130,
        onClick = { action(id) },
    )
}
