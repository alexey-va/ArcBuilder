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
            "replace",
            "disconnect",
            "copy",
            "book",
            "paste",
            "deconstruct",
            "crown",
            "confirm",
            "cancel",
            "undo",
            "status",
            "projects",
        )
        BuilderRootCommand.parse("WAND") shouldBe BuilderRootCommand.WAND
        BuilderRootCommand.parse("pos1") shouldBe null
        BuilderRootCommand.parse("pos2") shouldBe null
        BuilderRootCommand.parse("stop") shouldBe null
        BuilderRootCommand.parse(null) shouldBe null
    }

    test("status projects and canonical cancel remain available during an operation") {
        BuilderRootCommand.entries.filter(BuilderRootCommand::safeDuringOperation) shouldBe listOf(
            BuilderRootCommand.CANCEL,
            BuilderRootCommand.STATUS,
            BuilderRootCommand.PROJECTS,
        )
    }

    test("busy feedback points players to their unfinished construction projects") {
        val config = Config(Files.createTempDirectory("arc-builder-busy-projects-"), "modules/builder-tools.yml")

        listOf("ru", "en").forEach { locale ->
            config.string("locales.$locale.errors.busy") shouldContain
                "<click:run_command:'/builder projects'>"
        }
    }

    test("operation progress is shown immediately, periodically, and on completion") {
        BuilderProgressCadence.shouldRender(1, completed = false) shouldBe true
        BuilderProgressCadence.shouldRender(2, completed = false) shouldBe false
        BuilderProgressCadence.shouldRender(9, completed = false) shouldBe false
        BuilderProgressCadence.shouldRender(10, completed = false) shouldBe true
        BuilderProgressCadence.shouldRender(11, completed = true) shouldBe true
        BuilderProgressCadence.shouldRender(3, completed = false, everyBatches = 3) shouldBe true
        BuilderProgressCadence.shouldRender(4, completed = false, everyBatches = 3) shouldBe false
    }

    test("operation progress rejects impossible batch numbers") {
        shouldThrow<IllegalArgumentException> {
            BuilderProgressCadence.shouldRender(0, completed = false)
        }
    }

    test("paste completion offers another paste only while the clipboard is retained") {
        BuilderOperationCompletion.repeatPaste(BuilderPlanKind.PASTE, hasClipboard = true) shouldBe true
        BuilderOperationCompletion.repeatPaste(BuilderPlanKind.PASTE, hasClipboard = false) shouldBe false
        BuilderOperationCompletion.repeatPaste(BuilderPlanKind.FILL, hasClipboard = true) shouldBe false

        val config = Config(Files.createTempDirectory("arc-builder-repeat-paste-"), "modules/builder-tools.yml")
        listOf("ru", "en").forEach { locale ->
            config.string("locales.$locale.operation.paste-again") shouldContain
                "<click:run_command:'/builder paste'>"
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
