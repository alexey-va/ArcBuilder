package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.config.Config
import java.nio.file.Files
import java.nio.file.Path

class BuilderBookJourneyTest : FunSpec({
    test("visual preview keeps block totals separate from currency placeholders") {
        val projectDir = Path.of(checkNotNull(System.getProperty("arcbuilder.projectDir")))
        val placeholders = Files.readString(projectDir.resolve("visual-preview.yml"))
            .substringBefore("glyphs:")
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()

        placeholders.single { it.startsWith("total:") } shouldBe "total: '1 920'"
        listOf("balance", "full_price", "labor", "materials", "missing_price", "price").forEach { key ->
            placeholders.single { it.startsWith("$key:") } shouldContain "💰"
        }
    }

    test("status resolver follows the complete guided creation journey") {
        val cases = listOf(
            BuilderBookJourneySnapshot() to BuilderBookJourneyStage.START,
            BuilderBookJourneySnapshot(hasFirstPoint = true) to BuilderBookJourneyStage.FIRST_POINT,
            BuilderBookJourneySnapshot(hasSecondPoint = true) to BuilderBookJourneyStage.SECOND_POINT,
            BuilderBookJourneySnapshot(hasSelection = true) to BuilderBookJourneyStage.SELECTION,
            BuilderBookJourneySnapshot(hasSelection = true, hasClipboard = true) to BuilderBookJourneyStage.CLIPBOARD,
            BuilderBookJourneySnapshot(hasClipboard = true, draft = true) to BuilderBookJourneyStage.DRAFT,
            BuilderBookJourneySnapshot(draft = true, previewOpen = true) to BuilderBookJourneyStage.PREVIEW,
            BuilderBookJourneySnapshot(draft = true, previewOpen = true, hasQuote = true) to BuilderBookJourneyStage.QUOTE,
            BuilderBookJourneySnapshot(deliveryPending = true) to BuilderBookJourneyStage.DELIVERY,
            BuilderBookJourneySnapshot(active = true) to BuilderBookJourneyStage.ACTIVE,
        )
        cases.forEach { (snapshot, expected) -> BuilderBookJourney.resolve(snapshot) shouldBe expected }
    }

    test("unsafe or transitional states keep the most restrictive guidance") {
        BuilderBookJourney.resolve(
            BuilderBookJourneySnapshot(
                hasQuote = true,
                deliveryPending = true,
                auctionLocked = true,
                draft = true,
                previewOpen = true,
                active = true,
                hasClipboard = true,
                hasSelection = true,
            ),
        ) shouldBe BuilderBookJourneyStage.QUOTE
        BuilderBookJourney.resolve(
            BuilderBookJourneySnapshot(
                deliveryPending = true,
                auctionLocked = true,
                active = true,
            ),
        ) shouldBe BuilderBookJourneyStage.DELIVERY
        BuilderBookJourney.resolve(
            BuilderBookJourneySnapshot(
                auctionLocked = true,
                active = true,
            ),
        ) shouldBe BuilderBookJourneyStage.AUCTION_LOCKED
    }

    test("every journey stage owns one stable configured message path") {
        BuilderBookJourneyStage.entries.map(BuilderBookJourneyStage::messagePath) shouldContainExactly listOf(
            "book.status.start",
            "book.status.first-point",
            "book.status.second-point",
            "book.status.selection",
            "book.status.clipboard",
            "book.status.draft",
            "book.status.preview",
            "book.status.quote",
            "book.status.delivery",
            "book.status.active",
            "book.auction-locked",
        )
    }

    test("bundled locales preserve the complete seven-step builder guidance") {
        val config = Config(Files.createTempDirectory("arc-builder-journey-"), "modules/builder-tools.yml")
        val requiredCommands = listOf(
            "/builder wand",
            "/builder copy",
            "/builder book draft",
            "/builder book copy",
            "/builder book sell",
            "/builder book status",
        )

        listOf("ru", "en").forEach { locale ->
            val guide = config.stringList("locales.$locale.book.guide").joinToString("\n")
            (1..7).forEach { step -> guide shouldContain "$step." }
            requiredCommands.forEach(guide::shouldContain)
        }

        val russianGuide = config.stringList("locales.ru.book.guide").joinToString("\n")
        russianGuide shouldContain "Контур виден постоянно"
        russianGuide shouldContain "бесплатный"
        russianGuide shouldContain ">черновик</hover>"
        russianGuide shouldContain ">смету</hover> без оплаты"
        config.string("locales.ru.book.status.active") shouldContain "Цена новой копии"
        config.string("locales.en.book.status.active") shouldContain "New-copy price"
        config.string("locales.ru.book.status.checking") shouldContain "подлинность"
        config.string("locales.en.book.status.checking") shouldContain "authenticity"
        config.string("locales.ru.book.status.changed") shouldContain "/builder book status"
        config.string("locales.en.book.status.changed") shouldContain "/builder book status"
        config.string("locales.ru.book.preview-required") shouldContain "ПКМ"
        config.string("locales.en.book.preview-required").lowercase() shouldContain "right-click"
        config.string("locales.ru.book.status.first-point") shouldContain "ПКМ"
        config.string("locales.en.book.status.first-point") shouldContain "right-click"
        config.string("locales.ru.book.status.second-point") shouldContain "ЛКМ"
        config.string("locales.en.book.status.second-point") shouldContain "left-click"
    }

    test("dense dynamic messages keep one fact per physical chat line") {
        val config = Config(Files.createTempDirectory("arc-builder-message-layout-"), "modules/builder-tools.yml")
        val messages = BuilderToolsConfig(config).messages()

        listOf("ru", "en").forEach { locale ->
            val plan = config.string("locales.$locale.plan.ready").lines()
            plan.size shouldBe 4
            plan.first() shouldBe ""
            plan.single { it.contains("<cost>") }.contains("<reward>") shouldBe false
            plan.single { it.contains("<reward>") }.contains("<cost>") shouldBe false
            plan.single { it.contains("<cost>") } shouldContain "<hover:show_text:"
            plan.single { it.contains("<reward>") } shouldContain "<hover:show_text:"
            plan.count { it.contains("</hover>") } shouldBe 3
            val renderedPlan = messages.render(
                "plan.ready",
                locale,
                mapOf(
                    "kind" to messages.literal("Blueprint paste"),
                    "count" to messages.literal(55),
                    "cost" to messages.literal("none"),
                    "reward" to messages.literal("none"),
                ),
            )
            MiniMessage.miniMessage().serialize(renderedPlan)
                .split("<hover:show_text").size shouldBe 4

            val quote = config.string("locales.$locale.book.quote").lines()
            quote.size shouldBe 6
            quote.first() shouldBe ""
            quote.single { it.contains("<materials>") }.contains("<labor>") shouldBe false
            quote.single { it.contains("<required>") }.contains("<materials>") shouldBe false
            quote.single { it.contains("<labor>") }.contains("<materials>") shouldBe false

            plan.drop(2).all { line -> line.contains(">   ") } shouldBe true
            plan.none { it.contains("/builder confirm") } shouldBe true
            val readyAction = config.string("locales.$locale.plan.actions.ready")
            readyAction shouldContain "/builder confirm"
            readyAction shouldContain "<color:#92bed8>[▶ "
            readyAction shouldContain "/builder cancel"
            val marketAction = config.stringList("locales.$locale.plan.market").single { it.contains("confirm buy") }
            marketAction shouldContain "<color:#92bed8>[▶ "
            marketAction shouldContain "/builder cancel"
            quote.drop(2).all { line -> line.contains(">   ") } shouldBe true

            val draftRecovery = config.string("locales.$locale.book.draft-recovering").lines()
            draftRecovery.size shouldBe 4
            draftRecovery.first() shouldBe ""
            draftRecovery.drop(2).all { line -> line.contains(">   ") } shouldBe true
            val recoveryWords = if (locale == "ru") "книг" to "автоматически" else "book" to "automatic"
            draftRecovery[2].lowercase() shouldContain recoveryWords.first
            draftRecovery[3].lowercase() shouldContain recoveryWords.second

            config.string("locales.$locale.items.none").isNotBlank() shouldBe true
            config.string("locales.$locale.items.summary") shouldContain "<items>"
            config.string("locales.$locale.items.summary") shouldContain "<types>"
            val waitingMaterials = config.string("locales.$locale.construction.waiting-materials")
            waitingMaterials shouldContain "<count>/<total>"
            waitingMaterials shouldContain if (locale == "ru") "блоков" else "blocks"
        }
    }

    test("chat action controls use one semantic button language") {
        val config = Config(Files.createTempDirectory("arc-builder-chat-actions-"), "modules/builder-tools.yml")

        val russian = config.string("locales.ru.selection.complete")
        russian shouldContain "<click:run_command:'/builder copy'>"
        russian shouldContain "<click:run_command:'/builder clear'>"
        russian shouldContain "<color:#92bed8>[▶ Скопировать]"
        russian shouldContain "<color:#969696>[✖ Сбросить]"

        val english = config.string("locales.en.selection.complete")
        english shouldContain "<click:run_command:'/builder copy'>"
        english shouldContain "<click:run_command:'/builder clear'>"
        english shouldContain "<color:#92bed8>[▶ Copy]"
        english shouldContain "<color:#969696>[✖ Reset]"

        val russianClipboard = config.string("locales.ru.clipboard.saved")
        russianClipboard shouldContain "<color:#92bed8>[▶ Вставить здесь]"
        russianClipboard shouldContain "<color:#92bed8>[▶ Создать книгу]"

        val englishClipboard = config.string("locales.en.clipboard.saved")
        englishClipboard shouldContain "<color:#92bed8>[▶ Paste here]"
        englishClipboard shouldContain "<color:#92bed8>[▶ Create book]"

        listOf("ru", "en").forEach { locale ->
            val buttonSurfaces = listOf(
                config.string("locales.$locale.selection.complete"),
                config.string("locales.$locale.clipboard.saved"),
                config.string("locales.$locale.book.status.quote"),
                config.string("locales.$locale.book.quote"),
                config.string("locales.$locale.plan.actions.ready"),
                config.stringList("locales.$locale.plan.market").single { it.contains("confirm buy") },
                config.string("locales.$locale.operation.paste-again"),
            )
            val buttonLabel = Regex("<click:[^>]+><color:[^>]+>\\[([^]]+)]")
            val labels = buttonSurfaces.flatMap { surface ->
                buttonLabel.findAll(surface).map { match -> match.groupValues[1] }.toList()
            }

            labels.size shouldBe 12
            labels.all { label -> label.startsWith("▶ ") || label.startsWith("✖ ") } shouldBe true
        }
    }

    test("compact localized summaries explain unfamiliar terms with hover text") {
        val config = Config(Files.createTempDirectory("arc-builder-hover-glossary-"), "modules/builder-tools.yml")
        val scalarMinimums = mapOf(
            "clipboard.saved" to 2,
            "book.status.active" to 1,
            "book.quote" to 4,
            "plan.ready" to 3,
            "plan.skipped" to 1,
            "construction.started" to 1,
            "construction.waiting-materials" to 1,
            "construction.status" to 1,
            "items.summary" to 2,
            "status.plan" to 1,
            "crown.palette-row" to 1,
        )
        val listMinimums = mapOf(
            "help" to 4,
            "book.guide" to 6,
            "crown.status" to 4,
            "plan.market" to 2,
        )

        listOf("ru", "en").forEach { locale ->
            scalarMinimums.forEach { (path, minimum) ->
                (config.string("locales.$locale.$path")
                    .split("<hover:show_text").size >= minimum + 1) shouldBe true
            }
            listMinimums.forEach { (path, minimum) ->
                (config.stringList("locales.$locale.$path").joinToString("\n")
                    .split("<hover:show_text").size >= minimum + 1) shouldBe true
            }
            BuilderPlanKind.entries.forEach { kind ->
                config.string("locales.$locale.kinds.${kind.name.lowercase()}") shouldContain "<hover:show_text:"
            }
            BuilderConstructionProjectState.entries.forEach { state ->
                config.string("locales.$locale.construction.states.${state.name.lowercase()}") shouldContain
                    "<hover:show_text:"
            }
        }
    }

    test("player guidance hides internal state names and capitalizes operation labels") {
        val config = Config(Files.createTempDirectory("arc-builder-wording-"), "modules/builder-tools.yml")
        val messages = BuilderToolsConfig(config).messages()
        val plain = PlainTextComponentSerializer.plainText()

        config.string("locales.ru.book.quote-expired").lowercase().contains("activate") shouldBe false
        config.string("locales.ru.book.quote-expired").lowercase().contains("copy") shouldBe false
        config.string("locales.en.book.quote-expired").lowercase().contains("activate") shouldBe false
        config.string("locales.en.book.quote-expired").lowercase().contains("copy") shouldBe false
        plain.serialize(messages.render("kinds.paste", "ru")) shouldBe "Вставка чертежа"
        plain.serialize(messages.render("kinds.paste", "en")) shouldBe "Blueprint paste"
    }
})
