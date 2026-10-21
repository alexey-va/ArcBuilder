package ru.arc.buildertools.display

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogInputId
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.menu.PaperDialogTextInput

/** Pure, inspectable native-dialog composition; callbacks always return to the editor's authority boundary. */
internal object DisplayEditorMenu {
    val TEXT = PaperDialogInputId.of("display_text")

    fun root(
        text: (String) -> Component,
        mode: DisplayEditorMode,
        selected: Boolean,
        selectedText: Boolean,
        canUndo: Boolean,
        selection: Component?,
        action: (String) -> Unit,
    ): PaperDialogScreen = PaperDialogScreen(
        id = "builder.display-editor",
        title = text("title"),
        body = listOfNotNull(PaperDialogBody(text("intro"), 400), selection?.let { PaperDialogBody(it, 400) }, PaperDialogBody(text("controls"), 400)),
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

    fun axes(text: (String) -> Component, selected: DisplayEditorAxis, action: (DisplayEditorAxis) -> Unit) = PaperDialogScreen(
        id = "builder.display-editor.axes",
        title = text("axis-title"),
        body = listOf(PaperDialogBody(text("axis-body"), 380)),
        buttons = DisplayEditorAxis.entries.map { axis ->
            PaperDialogButton(
                id = PaperDialogActionId.of("axis_${axis.name.lowercase()}"),
                label = text("axis-${if (axis == selected) "selected" else "option"}-${axis.name.lowercase()}"),
                width = 180,
                onClick = { action(axis) },
            )
        },
        columns = 2,
    )

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
