package ru.arc.buildertools

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.context.StringRange
import com.mojang.brigadier.suggestion.Suggestion
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.entity.PlayerMock
import ru.arc.autobuild.BuildBookCodec
import ru.arc.autobuild.SystemBuildBookDefinition
import ru.arc.autobuild.BuildBookSettings
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.paper.testing.MockBukkitTestRuntime
import kotlin.random.Random
import ru.arc.paper.testing.loadPlugin
import ru.arc.text.LocalizedMiniMessage
import ru.ruscrafting.builder.paper.ArcBuilderPlugin
import java.nio.file.Files

class BuilderSystemBookIssuerTest : FunSpec({
    test("console grants deliver native books before returning without a schematic loader") {
        withPlayerIssuer { fixture ->
            val console = mockk<ConsoleCommandSender>(relaxed = true)
            repeat(2) { index ->
                BuilderSystemBookIssuer.issue(console, listOf(fixture.player.name, fixture.definition.buildingId),
                    { fixture.definition }, fixture.messages, permissionDenied = { error("must not deny") }) shouldBe true
                fixture.player.inventory.contents.filterNotNull().size shouldBe index + 1
                BuildBookCodec.read(checkNotNull(fixture.player.inventory.getItem(index)))?.buildingId shouldBe fixture.definition.buildingId
            }
            verify(exactly = 2) { console.sendMessage(match<String> { it.startsWith("SYSTEM_BOOK_ISSUED ") }) }
            verify(exactly = 0) { console.sendMessage(match<String> { it.contains("sha256=") || it.startsWith("SYSTEM_BOOK_REFUSED ") }) }
        }
    }

    test("self issuance immediately stores catalogue metadata without content digests or a guessed block count") {
        withPlayerIssuer { fixture ->
            fixture.issue()

            val book = checkNotNull(BuildBookCodec.read(fixture.player.inventory.contents.filterNotNull().single()))
            book.buildingId shouldBe fixture.definition.buildingId
            book.title shouldBe fixture.definition.title
            book.systemMaterialsIncluded shouldBe true
            book.registered shouldBe false
            book.instanceId shouldBe null
            book.schematicSha256 shouldBe null
            book.contentSha256 shouldBe null
            book.blockCount shouldBe null
            val success = fixture.plain.serialize(checkNotNull(fixture.player.nextComponentMessage()))
            success shouldContain "Книга выдана игроку ${fixture.player.name}"
            success shouldContain fixture.definition.title
            success shouldNotContain fixture.definition.buildingId
            success shouldNotContain "Загружаем"
            fixture.player.nextComponentMessage() shouldBe null
        }
    }

    test("legacy alias issuance stores the canonical default with its configured selector and material policy") {
        withPlayerIssuer { fixture ->
            val alternative = fixture.definition.copy(buildingId = "minecolonies/farm", title = "Ферма", materialsIncluded = false)
            val byId = mapOf("old-house.schem" to fixture.definition,
                fixture.definition.buildingId to fixture.definition, alternative.buildingId to alternative)

            BuilderSystemBookIssuer.issue(fixture.player,
                listOf(fixture.player.name, "old-house.schem", alternative.buildingId),
                { byId[it.buildingId] }, fixture.messages, permissionDenied = { error("must not deny") })

            val book = checkNotNull(BuildBookCodec.read(fixture.player.inventory.contents.filterNotNull().single()))
            book.buildingId shouldBe fixture.definition.buildingId
            book.selectableBuildingIds shouldBe listOf(fixture.definition.buildingId, alternative.buildingId)
            book.systemMaterialsIncluded shouldBe fixture.definition.materialsIncluded
            book.schematicSha256 shouldBe null
            book.contentSha256 shouldBe null
            book.blockCount shouldBe null
        }
    }

    test("random issuance supports starter and explicit pools using only catalogue metadata") {
        withPlayerIssuer { fixture ->
            val alternative = fixture.definition.copy(buildingId = "minecolonies/farm", title = "Ферма", materialsIncluded = false)
            val definitions = listOf(fixture.definition, alternative)
            val byId = definitions.associateBy(SystemBuildBookDefinition::buildingId)
            val ids = definitions.map(SystemBuildBookDefinition::buildingId)
            listOf("@starter", ids.joinToString(",")).forEachIndexed { index, pool ->
                BuilderSystemBookIssuer.issue(fixture.player, listOf("random", pool), { byId[it.buildingId] },
                    fixture.messages, randomBuildingIds = { ids }, permissionDenied = { error("must not deny") })

                val book = checkNotNull(BuildBookCodec.read(checkNotNull(fixture.player.inventory.getItem(index))))
                val selected = checkNotNull(byId[book.buildingId])
                book.title shouldBe selected.title
                book.systemMaterialsIncluded shouldBe selected.materialsIncluded
                book.selectableBuildingIds shouldBe ids
                book.cooldownSeconds shouldBe 0L
                book.blockCount shouldBe null
                book.schematicSha256 shouldBe null
                book.contentSha256 shouldBe null
            }
        }
    }

    test("full inventory refuses synchronous issuance without replacing items") {
        withPlayerIssuer { fixture ->
            repeat(fixture.player.inventory.size) { slot ->
                fixture.player.inventory.setItem(slot, ItemStack(Material.STONE, 64))
            }

            fixture.issue()

            val failure = fixture.plain.serialize(checkNotNull(fixture.player.nextComponentMessage()))
            failure shouldContain "нет места"
            failure shouldContain "Освободите один слот"
            fixture.player.nextComponentMessage() shouldBe null
            fixture.player.inventory.contents.all { it?.type == Material.STONE && it.amount == 64 } shouldBe true
        }
    }

    test("missing catalogue metadata refuses issuance before creating an item") {
        withPlayerIssuer { fixture ->
            BuilderSystemBookIssuer.issue(fixture.player, listOf("missing.schem"), { null },
                fixture.messages, permissionDenied = { error("must not deny") })

            fixture.plain.serialize(checkNotNull(fixture.player.nextComponentMessage())) shouldContain "Схема отсутствует в каталоге"
            fixture.player.nextComponentMessage() shouldBe null
            fixture.player.inventory.contents.filterNotNull().size shouldBe 0
        }
    }

    test("fatal VM and thread errors are not converted to issuance refusals") {
        withPlayerIssuer { fixture ->
            listOf(StackOverflowError("fatal VM error"), ThreadDeath()).forEach { fatal ->
                shouldThrow<Error> {
                    BuilderSystemBookIssuer.issue(fixture.player, listOf(fixture.definition.buildingId),
                        { throw fatal }, fixture.messages, permissionDenied = { error("must not deny") })
                } shouldBe fatal
            }
            fixture.player.nextComponentMessage() shouldBe null
            fixture.player.inventory.contents.filterNotNull().size shouldBe 0
        }
    }

    test("both bundled issuance locales render useful messages and literal catalogue titles") {
        val messages = BuilderToolsConfig(Config(Files.createTempDirectory("arc-builder-issuance-copy-"),
            "modules/builder-tools.yml")).messages()
        val plain = PlainTextComponentSerializer.plainText()
        listOf("ru", "en").forEach { locale ->
            listOf("directory-suggestion", "failed", "inventory-full", "recipient-unavailable",
                "catalogue-unavailable", "pool-required", "usage").forEach { key ->
                val rendered = plain.serialize(messages.render("book.system-issue.$key", locale))
                rendered.isNotBlank() shouldBe true
                rendered shouldNotContain "Missing builder-tools message"
                rendered shouldNotContain "<prefix>"
            }
            val usage = plain.serialize(messages.render("book.system-issue.usage", locale))
            usage shouldContain "/builder systembook <id>"
            usage shouldContain "/builder systembook <player> <id>"
            plain.serialize(messages.render("book.system-issue.directory-suggestion", locale)) shouldBe
                if (locale == "ru") "Папка · / — открыть" else "Folder · / — open"
            val title = "Дом <red>у леса</red>"
            val success = plain.serialize(messages.render("book.system-issue.success", locale,
                mapOf("name" to messages.literal(title), "player" to messages.literal("BookIssuer"))))
            success shouldContain title
            success shouldContain "BookIssuer"
            success.lines().first() shouldBe ""
            success.lines().last() shouldBe ""
            success.lines().filter(String::isNotEmpty).all { it.startsWith("  ") } shouldBe true
            success.lines()[2] shouldBe "     ${if (locale == "ru") "Постройка" else "Build"}: $title"
        }
    }

    test("completion denies unauthorized senders before reading catalogue or players") {
        for (sender in listOf(mockk<Player>(relaxed = true), mockk<CommandSender>(relaxed = true))) {
            BuilderSystemBookIssuer.tabComplete(sender, arrayOf("systembook", ""),
                { error("must not read catalogue") }, { error("must not read players") }) shouldBe emptyList()
        }
    }
    test("completion respects argument positions prefix and console recipient requirement") {
        val admin = mockk<Player>()
        every { admin.hasPermission(BuilderSystemBookIssuer.PERMISSION) } returns true
        val console = mockk<ConsoleCommandSender>()
        fun complete(sender: CommandSender, vararg args: String) = BuilderSystemBookIssuer.tabComplete(
            sender, args, { listOf("birch.schem", "forest.schem") }, { listOf("GrocerMC") })
        complete(admin, "SY") shouldBe listOf("systembook")
        complete(admin, "SYSTEMBOOK", "BIR") shouldBe listOf("birch.schem")
        complete(admin, "systembook", "") shouldBe listOf("GrocerMC", "birch.schem", "forest.schem", "random")
        complete(console, "systembook", "") shouldBe listOf("GrocerMC")
        complete(console, "systembook", "GrocerMC", "FOR") shouldBe listOf("forest.schem")
        complete(console, "systembook", "GrocerMC", "ran") shouldBe listOf("random")
        complete(admin, "systembook", "birch.schem", "") shouldBe emptyList()
        complete(admin, "systembook", "GrocerMC", "forest.schem", "") shouldBe emptyList()
        complete(admin, "book", "") shouldBe emptyList()
    }
    test("schematic completion reveals one directory level and preserves exact catalogue ids") {
        val admin = mockk<Player>()
        every { admin.hasPermission(BuilderSystemBookIssuer.PERMISSION) } returns true
        val ids = listOf(
            "minecolonies/medieval/house.schem", "minecolonies/asian/tower.schematic",
            "root.schematic", "minecolonies/medieval/workshop", "minecolonies/notice.schem",
            "minecolonies/medieval/house.schem", "civs/cottage.schem", "root",
        )
        fun complete(prefix: String) = BuilderSystemBookIssuer.tabComplete(
            admin, arrayOf("systembook", prefix), { ids }, { emptyList() })
        complete("") shouldBe listOf("civs/", "minecolonies/", "random", "root", "root.schematic")
        complete("mi") shouldBe listOf("minecolonies/")
        complete("minecolonies/") shouldBe listOf("minecolonies/asian/", "minecolonies/medieval/", "minecolonies/notice.schem")
        complete("MINECOLONIES/me") shouldBe listOf("minecolonies/medieval/")
        complete("minecolonies/medieval/") shouldBe listOf("minecolonies/medieval/house.schem", "minecolonies/medieval/workshop")
        complete("minecolonies/asian/t") shouldBe listOf("minecolonies/asian/tower.schematic")
        complete("missing/") shouldBe emptyList()
    }

    test("client folder completion leaves each separator for typed navigation without exposing descendants") {
        val admin = mockk<Player>()
        every { admin.hasPermission(BuilderSystemBookIssuer.PERMISSION) } returns true
        val ids = listOf("minecolonies/medieval/house.schem", "minecolonies/asian/tower.schematic",
            "minecolonies/notice.schem", "civs/cottage.schem")
        val tooltip = LiteralMessage("Папка · / — открыть")
        fun complete(prefix: String): Suggestions {
            val buffer = "/builder systembook $prefix"
            val builder = SuggestionsBuilder(buffer, buffer.lastIndexOf(' ') + 1)
            BuilderSystemBookIssuer.tabComplete(admin, arrayOf("systembook", prefix), { ids }, { emptyList() })
                .forEach { builder.suggest(it) }
            return BuilderSystemBookIssuer.clientSuggestions(builder.build(), tooltip)
        }

        val root = complete("mi")
        root.list.map { it.text } shouldBe listOf("minecolonies")
        root.list.single().tooltip shouldBeSameInstanceAs tooltip
        val firstFolder = root.list.single().apply("/builder systembook mi")
        firstFolder shouldBe "/builder systembook minecolonies"

        val nextInput = "$firstFolder/"
        val next = complete(nextInput.substringAfterLast(' '))
        next.list.map { it.text } shouldBe listOf("minecolonies/asian", "minecolonies/medieval", "minecolonies/notice.schem")
        next.list.last().tooltip shouldBe null
        val secondFolder = next.list.single { it.text == "minecolonies/medieval" }.apply(nextInput)
        secondFolder shouldBe "/builder systembook minecolonies/medieval"

        val leafInput = "$secondFolder/"
        val leaf = complete(leafInput.substringAfterLast(' ')).list.single()
        leaf.text shouldBe "minecolonies/medieval/house.schem"
        leaf.tooltip shouldBe null
        leaf.apply(leafInput) shouldBe "/builder systembook minecolonies/medieval/house.schem"
    }

    test("client completion preserves ranges and leaf metadata and merges matching file and folder labels") {
        val range = StringRange.between(20, 22)
        val leaf = Suggestion(range, "house.schem", LiteralMessage("Schematic"))
        val original = Suggestions(range, listOf(Suggestion(range, "house"), Suggestion(range, "house/"), leaf))
        val tooltip = LiteralMessage("Folder · / — open")

        val result = BuilderSystemBookIssuer.clientSuggestions(original, tooltip)

        result.range shouldBe range
        result.list.map { it.text } shouldBe listOf("house", "house.schem")
        result.list.map { it.range }.distinct() shouldBe listOf(range)
        result.list.first().tooltip shouldBeSameInstanceAs tooltip
        result.list.last() shouldBeSameInstanceAs leaf
        BuilderSystemBookIssuer.clientSuggestions(Suggestions.empty().join(), tooltip).isEmpty shouldBe true
    }

    test("players without issuance permission cannot resolve or issue a book") {
        val sender = mockk<Player>(relaxed = true)
        var denied = false
        BuilderSystemBookIssuer.issue(sender, listOf("house.schem"), { error("must not resolve") }, mockk()) { denied = true } shouldBe true
        denied shouldBe true
        verify(exactly = 0) { sender.inventory }
    }
    test("console and explicitly permitted players can issue books but other senders cannot") {
        BuilderSystemBookIssuer.canIssue(mockk<ConsoleCommandSender>()) shouldBe true
        val admin = mockk<Player>()
        every { admin.hasPermission(BuilderSystemBookIssuer.PERMISSION) } returns true
        BuilderSystemBookIssuer.canIssue(admin) shouldBe true
        val other = mockk<CommandSender>(relaxed = true)
        every { other.hasPermission(any<String>()) } returns true
        BuilderSystemBookIssuer.canIssue(other) shouldBe false
    }
    test("permitted player reaches argument validation without console-only refusal") {
        val admin = mockk<Player>(relaxed = true)
        every { admin.hasPermission(BuilderSystemBookIssuer.PERMISSION) } returns true
        val messages = BuilderToolsConfig(Config(Files.createTempDirectory("arc-builder-issuer-usage-"),
            "modules/builder-tools.yml")).messages()
        BuilderSystemBookIssuer.issue(admin, emptyList(), { error("must not resolve") }, messages) { error("must not deny") }
        verify { admin.sendMessage(messages.render("book.system-issue.usage", BuilderLocalePolicy.localeTag(admin))) }
    }
    test("issued catalogue data preserves title and material policy without inventing a player contract") {
        val definition = SystemBuildBookDefinition("house.schem", "Стартовый домик", true, false)
        val data = BuilderSystemBookIssuer.data(definition)
        data.buildingId shouldBe "house.schem"
        data.title shouldBe "Стартовый домик"
        data.systemMaterialsIncluded shouldBe false
        data.playerCreated shouldBe false
        data.registered shouldBe false
        data.instanceId shouldBe null
        data.schematicSha256 shouldBe null
        data.contentSha256 shouldBe null
        data.blockCount shouldBe null
        BuilderSystemBookIssuer.data(definition.copy(materialsIncluded = true)).systemMaterialsIncluded shouldBe true
    }
    test("random selection validates the starter pool and keeps every valid option") {
        mockkObject(BuildBookSettings)
        every { BuildBookSettings.maxOffset } returns 64
        try {
            val definitions = (1..30).map { id ->
                SystemBuildBookDefinition("starter-$id.schem", "Starter $id", true, false)
            }
            val byId = definitions.associateBy(SystemBuildBookDefinition::buildingId)
            val selected = BuilderSystemBookIssuer.selectRandomDefinition(
                definitions.map(SystemBuildBookDefinition::buildingId) + "missing.schem",
                { byId[it.buildingId] },
                random = Random(7),
            )
            definitions.map(SystemBuildBookDefinition::buildingId) shouldContain selected.first.buildingId
            selected.second shouldBe definitions.map(SystemBuildBookDefinition::buildingId)
            BuilderSystemBookIssuer.data(selected.first).copy(selectableBuildingIds = selected.second).validated().selectableBuildingIds.size shouldBe 30
        } finally {
            unmockkObject(BuildBookSettings)
        }
    }
    test("random selection supports an empty and singleton starter pool") {
        mockkObject(BuildBookSettings)
        every { BuildBookSettings.maxOffset } returns 64
        try {
            val definition = SystemBuildBookDefinition("only.schem", "Only", true, false)
            val resolver: (ru.arc.autobuild.BuildBookData) -> SystemBuildBookDefinition? = { definition }
            runCatching {
                BuilderSystemBookIssuer.selectRandomDefinition(emptyList(), resolver)
            }.exceptionOrNull()?.message shouldBe "No enabled catalogue entries are available"
            BuilderSystemBookIssuer.selectRandomDefinition(listOf("only.schem"), resolver).second shouldBe emptyList()
        } finally {
            unmockkObject(BuildBookSettings)
        }
    }
})

private class PlayerIssuerFixture(
    val player: PlayerMock,
    val messages: LocalizedMiniMessage,
) {
    val definition = SystemBuildBookDefinition("civs/alpine-house", "Северный дом", true, true)
    val plain = PlainTextComponentSerializer.plainText()
    fun issue(requestedId: String = definition.buildingId): Boolean =
        BuilderSystemBookIssuer.issue(player, listOf(requestedId), { definition }, messages,
            permissionDenied = { error("must not deny") })
}

private fun withPlayerIssuer(test: (PlayerIssuerFixture) -> Unit) {
    ConfigManager.clear()
    try {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<ArcBuilderPlugin>()
            BuilderToolsModule.shutdown()
            val player = paper.addPlayer("BookIssuer")
            player.addAttachment(plugin, BuilderSystemBookIssuer.PERMISSION, true)
            BuilderLocalePolicy.configure("ru", false)
            test(PlayerIssuerFixture(player, BuilderToolsConfig.load().messages()))
        }
    } finally { ConfigManager.clear() }
}
