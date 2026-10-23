package ru.arc.buildertools

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import ru.arc.paper.menu.DialogDecoration
import ru.arc.paper.menu.DialogTables
import ru.arc.paper.menu.DialogTextLayout
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.text.TextAlignment

/** Explicit server-pack opt-in; text and ordinary controls remain native. */
internal class BuilderDialogPresentation(font: String = "") {
    private val decoration = font.takeIf(String::isNotBlank)?.let { DialogDecoration(Key.key(it), 0xF9F00, 0xF9F10) }
    private val corners = DialogDecoration.Corners(piece(0xF9100), piece(0xF9101), piece(0xF9102), piece(0xF9103))
    private val rule = DialogDecoration.Divider(piece(0xF9104), piece(0xF9106), piece(0xF9105))

    fun paragraph(text: Component, width: Int): PaperDialogBody = if (decoration == null) PaperDialogBody(text, width)
        else DialogTextLayout.modelBody(text, TextAlignment.LEFT, width)

    fun callout(text: Component, width: Int): PaperDialogBody =
        decoration?.openCorners(text, width, corners) ?: PaperDialogBody(text, width)

    fun summary(text: Component, width: Int): PaperDialogBody = if (decoration == null) PaperDialogBody(text, width)
        else DialogTables.filledBody(rows = listOf(listOf(text)), headers = null, width = width, columnWeights = listOf(1))

    fun divider(width: Int): PaperDialogBody? = decoration?.let { PaperDialogBody(it.divider(width, rule), width) }

    private fun piece(point: Int) = DialogDecoration.Glyph(point, 13)
}
