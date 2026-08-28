package ru.arc.buildertools

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import net.kyori.adventure.text.TranslatableComponent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import ru.arc.config.Config
import java.nio.file.Files

class BuilderToolsExperienceTest : FunSpec({
    test("builder root exposes only the guided public command contract") {
        BuilderRootCommand.suggestions() shouldBe listOf(
            "help",
            "wand",
            "clear",
            "fill",
            "copy",
            "book",
            "paste",
            "deconstruct",
            "crown",
            "confirm",
            "cancel",
            "undo",
            "status",
        )
        BuilderRootCommand.parse("WAND") shouldBe BuilderRootCommand.WAND
        BuilderRootCommand.parse("pos1") shouldBe null
        BuilderRootCommand.parse("pos2") shouldBe null
        BuilderRootCommand.parse("stop") shouldBe null
        BuilderRootCommand.parse(null) shouldBe null
    }

    test("only status and canonical cancel remain available during an operation") {
        BuilderRootCommand.entries.filter(BuilderRootCommand::safeDuringOperation) shouldBe listOf(
            BuilderRootCommand.CANCEL,
            BuilderRootCommand.STATUS,
        )
    }

    test("operation progress is shown immediately, periodically, and on completion") {
        BuilderProgressCadence.shouldRender(1, completed = false) shouldBe true
        BuilderProgressCadence.shouldRender(2, completed = false) shouldBe false
        BuilderProgressCadence.shouldRender(9, completed = false) shouldBe false
        BuilderProgressCadence.shouldRender(10, completed = false) shouldBe true
        BuilderProgressCadence.shouldRender(11, completed = true) shouldBe true
    }

    test("operation progress rejects impossible batch numbers") {
        shouldThrow<IllegalArgumentException> {
            BuilderProgressCadence.shouldRender(0, completed = false)
        }
    }

    test("bundled operation action bars stay compact and identity-free") {
        val config = Config(Files.createTempDirectory("arc-builder-progress-"), "modules/builder-tools.yml")

        listOf("ru", "en").forEach { locale ->
            val progress = config.string("locales.$locale.operation.progress")
            progress shouldNotContain "<prefix>"
            progress shouldContain "<kind>"
            progress shouldContain "<count>"
            progress shouldContain "<total>"
        }
    }

    test("material labels use Russian catalog names and client translation elsewhere") {
        val russian = BuilderMaterialPresentation.label(Material.OAK_PLANKS, "ru-RU") { "Дубовые доски" }
        PlainTextComponentSerializer.plainText().serialize(russian) shouldBe "Дубовые доски"

        val english = BuilderMaterialPresentation.label(Material.OAK_PLANKS, "en-US") { "не используется" }
        (english as TranslatableComponent).key() shouldBe Material.OAK_PLANKS.translationKey()
    }
})
