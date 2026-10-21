package ru.arc.buildertools.display

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.configuration.file.YamlConfiguration

class DisplayEditorMenuTest : FunSpec({
    test("both localized native menus keep creation and modes in deliberate rows and fit a 426 pixel viewport") {
        val configuration = checkNotNull(javaClass.getResourceAsStream("/modules/builder-tools.yml")).reader().use(YamlConfiguration::loadConfiguration)
        val keys = listOf("ru", "en").map { locale ->
            checkNotNull(configuration.getConfigurationSection("locales.$locale.display-editor")).getKeys(false)
        }
        keys[0] shouldBe keys[1]
        for (locale in listOf("ru", "en")) {
            fun text(key: String): Component = MiniMessage.miniMessage().deserialize(
                checkNotNull(configuration.getString("locales.$locale.display-editor.$key")) { "Missing $locale/$key" },
                Placeholder.unparsed("kind", "block"), Placeholder.unparsed("x", "12.5"),
                Placeholder.unparsed("y", "65.5"), Placeholder.unparsed("z", "-3.5"),
            ).decoration(TextDecoration.ITALIC, false)
            val screen = DisplayEditorMenu.root(::text, DisplayEditorMode.ROTATE, true, true, true, text("selected-object")) {}
            screen.buttons.take(6).map { it.id.value } shouldContainExactly listOf("block", "item", "text", "move", "rotate", "scale")
            (screen.columns * screen.buttons.maxOf { it.width } + (screen.columns - 1) * 4 <= 426) shouldBe true
            screen.body.all { it.width <= 426 } shouldBe true
            PlainTextComponentSerializer.plainText().serialize(screen.buttons.single { it.id.value == "rotate" }.label).startsWith("✔ ") shouldBe true
            screen.buttons.all { it.label.decoration(TextDecoration.ITALIC) == TextDecoration.State.FALSE } shouldBe true
            val deletion = DisplayEditorMenu.deletion(::text, text("selected-object")) {}
            PlainTextComponentSerializer.plainText().serialize(deletion.body.first().text).contains("12.5") shouldBe true
            val form = DisplayEditorMenu.textForm(::text, "bad input", false, true) {}
            form.inputs.single().initial shouldBe "bad input"
            form.body.size shouldBe 2
        }
    }
})
