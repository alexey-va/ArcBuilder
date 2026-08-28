package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import ru.arc.config.Config
import java.nio.file.Files

class BuilderBookJourneyTest : FunSpec({
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
        russianGuide shouldContain "бесплатный черновик"
        russianGuide shouldContain "смету без оплаты"
        config.string("locales.ru.book.status.active") shouldContain "Себестоимость копии"
        config.string("locales.en.book.status.active") shouldContain "Copy at stored cost"
        config.string("locales.ru.book.status.checking") shouldContain "UUID, владельца и поколение"
        config.string("locales.en.book.status.checking") shouldContain "UUID, owner, and generation"
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

        listOf("ru", "en").forEach { locale ->
            val plan = config.string("locales.$locale.plan.ready").lines()
            plan.size shouldBe 5
            plan.first() shouldBe ""
            plan.single { it.contains("<cost>") }.contains("<reward>") shouldBe false
            plan.single { it.contains("<reward>") }.contains("<cost>") shouldBe false

            val quote = config.string("locales.$locale.book.quote").lines()
            quote.size shouldBe 6
            quote.first() shouldBe ""
            quote.single { it.contains("<materials>") }.contains("<labor>") shouldBe false
            quote.single { it.contains("<required>") }.contains("<materials>") shouldBe false
            quote.single { it.contains("<labor>") }.contains("<materials>") shouldBe false

            plan.drop(2).all { line -> line.contains(">   ") } shouldBe true
            quote.drop(2).all { line -> line.contains(">   ") } shouldBe true

            config.string("locales.$locale.items.none").isNotBlank() shouldBe true
            config.string("locales.$locale.items.summary") shouldContain "<items>"
            config.string("locales.$locale.items.summary") shouldContain "<types>"
        }
    }

    test("player guidance hides internal state names and capitalizes operation labels") {
        val config = Config(Files.createTempDirectory("arc-builder-wording-"), "modules/builder-tools.yml")

        config.string("locales.ru.book.quote-expired").lowercase().contains("activate") shouldBe false
        config.string("locales.ru.book.quote-expired").lowercase().contains("copy") shouldBe false
        config.string("locales.en.book.quote-expired").lowercase().contains("activate") shouldBe false
        config.string("locales.en.book.quote-expired").lowercase().contains("copy") shouldBe false
        config.string("locales.ru.kinds.paste") shouldBe "Вставка чертежа"
        config.string("locales.en.kinds.paste") shouldBe "Blueprint paste"
    }
})
