package ru.arc.buildertools

import com.google.gson.GsonBuilder
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogScreen
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Optional export of real controller/Core models; number inputs remain explicit in the evidence. */
internal fun exportBuilderDialog(id: String, screen: PaperDialogScreen) {
    val directory = System.getenv("BUILDER_DIALOG_PREVIEW_OUT") ?: return
    val legacy = LegacyComponentSerializer.builder().character('&').hexColors().build()
    val content = linkedMapOf("title" to legacy.serialize(screen.title))
    val body = screen.body.mapIndexed { index, value ->
        val key = "body-$index"
        content[key] = legacy.serialize(value.text)
        mapOf("id" to key, "text-key" to key, "width" to value.width)
    }
    fun button(value: PaperDialogButton): Map<String, Any> {
        val key = value.id.value
        content[key] = legacy.serialize(value.label)
        content["$key-tooltip"] = legacy.serialize(value.tooltip)
        return mapOf("id" to key, "text-key" to key, "tooltip-key" to "$key-tooltip", "width" to value.width)
    }
    val dialog = mapOf("id" to id, "source" to id, "format" to "reference-multi-action", "name-key" to "title",
        "columns" to screen.columns, "body" to body, "buttons" to screen.buttons.map(::button),
        "exit-button" to screen.exitButton?.let(::button), "viewport" to listOf(640, 360),
        "variants" to listOf(mapOf("id" to "example")))
    val numbers = screen.numberInputs.map { mapOf("id" to it.id.value,
        "label" to legacy.serialize(it.label), "start" to it.start, "end" to it.end,
        "initial" to it.initial, "step" to it.step, "width" to it.width) }
    val out = Path.of(directory)
    Files.createDirectories(out)
    val sourceHashes = listOf(
        "src/main/kotlin/ru/arc/buildertools/BuilderCrownSelectionDialog.kt",
        "src/main/kotlin/ru/arc/buildertools/BuilderDialogPresentation.kt",
        "src/main/kotlin/ru/arc/buildertools/display/DisplayEditorMenu.kt",
        "src/main/resources/modules/builder-tools.yml",
    ).associateWith { source -> MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(source)))
        .joinToString("") { "%02x".format(it) } }
    Files.writeString(out.resolve("$id.json"), GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(
        mapOf("content" to content, "dialog" to dialog, "numberInputs" to numbers,
            "sourceSha256" to sourceHashes,
            "exitComponent" to screen.exitButton?.label?.let(GsonComponentSerializer.gson()::serialize),
            "evidence" to "Actual controller and Core dialog components with fixture data. Native client acceptance is separate.")))
}
