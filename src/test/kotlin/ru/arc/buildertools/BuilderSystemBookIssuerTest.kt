package ru.arc.buildertools

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.entity.PlayerMock
import ru.arc.autobuild.BuildBookCodec
import ru.arc.autobuild.Building
import ru.arc.autobuild.SystemBuildBookDefinition
import ru.arc.autobuild.BuildBookSettings
import ru.arc.autobuild.schematicTestBuilding
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import kotlin.random.Random
import ru.arc.paper.testing.loadPlugin
import ru.arc.text.LocalizedMiniMessage
import ru.arc.util.Logging
import ru.ruscrafting.builder.paper.ArcBuilderPlugin
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

class BuilderSystemBookIssuerTest : FunSpec({
    test("two deferred console grants to one recipient both issue after unrelated hotbar changes") {
        ru.arc.config.ConfigManager.clear()
        ru.arc.paper.testing.MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<ru.ruscrafting.builder.paper.ArcBuilderPlugin>()
            BuilderToolsModule.shutdown()
            val player = paper.addPlayer("GrantRecipient")
            val console = mockk<ConsoleCommandSender>(relaxed = true)
            val scheduler = ru.arc.core.TestTaskScheduler()
            val scope = ru.arc.core.LifecycleTaskScope(scheduler)
            val requests = BuilderBookRequests(scope)
            val definition = SystemBuildBookDefinition("civs/house", "House", "a".repeat(64), true, true)
            val pending = mutableListOf<java.util.concurrent.CompletableFuture<ru.arc.autobuild.Building>>()
            repeat(2) {
                BuilderSystemBookIssuer.issue(console, listOf(player.name, definition.buildingId), { definition }, 8192,
                    BuilderToolsConfig.load().messages(),
                    load = { _, _ -> java.util.concurrent.CompletableFuture<ru.arc.autobuild.Building>().also { pending += it } },
                    requests = requests, permissionDenied = { error("must not deny") })
            }
            requests.size shouldBe 2
            player.inventory.contents.filterNotNull().size shouldBe 0
            player.inventory.heldItemSlot = 3
            player.gameMode = org.bukkit.GameMode.CREATIVE
            val building = ru.arc.autobuild.schematicTestBuilding(definition.buildingId)
            pending.asReversed().forEach { it.complete(building) }
            scheduler.executeImmediate()
            requests.size shouldBe 0
            player.inventory.contents.filterNotNull().count { ru.arc.autobuild.BuildBookCodec.read(it)?.buildingId == definition.buildingId } shouldBe 2
            verify(exactly = 2) { console.sendMessage(match<String> { it.startsWith("SYSTEM_BOOK_ISSUED ") }) }
            verify(exactly = 0) { console.sendMessage(match<String> { it.startsWith("SYSTEM_BOOK_REFUSED ") }) }
            requests.close()
            scope.close()
        }
        ru.arc.config.ConfigManager.clear()
    }

    test("player self issuance reports loading then success only after main-thread delivery") {
        withPlayerIssuer { fixture ->
            val pending = fixture.issue()
            fixture.player.nextComponentMessage()?.let(fixture.plain::serialize) shouldContain "Загружаем схему"
            fixture.player.nextComponentMessage() shouldBe null
            fixture.player.inventory.contents.filterNotNull().size shouldBe 0

            pending.complete(schematicTestBuilding(fixture.definition.buildingId))
            fixture.player.inventory.contents.filterNotNull().size shouldBe 0
            fixture.scheduler.executeImmediate()

            val book = checkNotNull(BuildBookCodec.read(fixture.player.inventory.contents.filterNotNull().single()))
            book.buildingId shouldBe fixture.definition.buildingId
            book.title shouldBe fixture.definition.title
            book.systemMaterialsIncluded shouldBe true
            book.registered shouldBe false
            book.instanceId shouldBe null
            val success = fixture.plain.serialize(checkNotNull(fixture.player.nextComponentMessage()))
            success shouldContain "Книга выдана игроку ${fixture.player.name}"
            success shouldContain fixture.definition.title
            success shouldNotContain fixture.definition.buildingId
            fixture.player.nextComponentMessage() shouldBe null
            fixture.requests.size shouldBe 0
        }
    }

    test("async loader linkage failure is readable for the issuer and logs its original stack with context") {
        withPlayerIssuer { fixture ->
            mockkStatic(Logging::class)
            try {
                every { Logging.warn(any(), *anyVararg()) } just Runs
                val pending = fixture.issue("civs/alpine-house.schem")
                fixture.player.nextComponentMessage()
                val cause = NoSuchMethodError("ClipboardFormats.findByInputStream(java.util.function.Supplier)")

                pending.completeExceptionally(CompletionException(cause))
                fixture.scheduler.executeImmediate()

                val failure = fixture.plain.serialize(checkNotNull(fixture.player.nextComponentMessage()))
                failure shouldContain "Не удалось завершить выдачу книги"
                failure shouldContain "журнал сервера"
                failure shouldNotContain "NoSuchMethodError"
                failure shouldNotContain "ClipboardFormats"
                failure shouldNotContain "SYSTEM_BOOK_REFUSED"
                fixture.player.nextComponentMessage() shouldBe null
                fixture.player.inventory.contents.filterNotNull().size shouldBe 0
                verify(exactly = 1) {
                    Logging.warn("SYSTEM_BOOK_REFUSED issuer={} recipient={} schematic={} reason={}",
                        fixture.player.name, fixture.player.name, fixture.definition.buildingId, cause.message, cause)
                }
            } finally { unmockkStatic(Logging::class) }
        }
    }

    test("inventory filled during async issuance gives actionable refusal without replacing items") {
        withPlayerIssuer { fixture ->
            val pending = fixture.issue()
            fixture.player.nextComponentMessage()
            repeat(fixture.player.inventory.size) { slot ->
                fixture.player.inventory.setItem(slot, ItemStack(Material.STONE, 64))
            }

            pending.complete(schematicTestBuilding(fixture.definition.buildingId))
            fixture.scheduler.executeImmediate()

            val failure = fixture.plain.serialize(checkNotNull(fixture.player.nextComponentMessage()))
            failure shouldContain "нет места"
            failure shouldContain "Освободите один слот"
            fixture.player.nextComponentMessage() shouldBe null
            fixture.player.inventory.contents.all { it?.type == Material.STONE && it.amount == 64 } shouldBe true
        }
    }

    test("fatal VM and thread errors are not converted to issuance refusals") {
        withPlayerIssuer { fixture ->
            listOf(StackOverflowError("fatal VM error"), ThreadDeath()).forEach { fatal ->
                shouldThrow<Error> {
                    BuilderSystemBookIssuer.issue(fixture.player, listOf(fixture.definition.buildingId),
                        { throw fatal }, 8192, fixture.messages, requests = fixture.requests,
                        permissionDenied = { error("must not deny") })
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
            listOf("loading", "failed", "cancelled", "inventory-full", "recipient-unavailable",
                "issuer-unavailable", "catalogue-unavailable", "pool-required", "too-large", "usage").forEach { key ->
                val rendered = plain.serialize(messages.render("book.system-issue.$key", locale))
                rendered.isNotBlank() shouldBe true
                rendered shouldNotContain "Missing builder-tools message"
                rendered shouldNotContain "<prefix>"
            }
            val usage = plain.serialize(messages.render("book.system-issue.usage", locale))
            usage shouldContain "/builder systembook <id>"
            usage shouldContain "/builder systembook <player> <id>"
            val title = "Дом <red>у леса</red>"
            val success = plain.serialize(messages.render("book.system-issue.success", locale,
                mapOf("name" to messages.literal(title), "player" to messages.literal("BookIssuer"))))
            success shouldContain title
            success shouldContain "BookIssuer"
            success.lines().first() shouldBe ""
            success.lines().last() shouldBe ""
            success.lines().filter(String::isNotEmpty).all { it.startsWith("  ") } shouldBe true
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
    test("players without issuance permission cannot resolve or issue a book") {
        val sender = mockk<Player>(relaxed = true)
        var denied = false
        BuilderSystemBookIssuer.issue(sender, listOf("house.schem"), { error("must not resolve") }, 8192, mockk()) { denied = true } shouldBe true
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
        BuilderSystemBookIssuer.issue(admin, emptyList(), { error("must not resolve") }, 8192, messages) { error("must not deny") }
        verify { admin.sendMessage(messages.render("book.system-issue.usage", BuilderLocalePolicy.localeTag(admin))) }
    }
    test("issued catalogue data preserves title and material policy without inventing a player contract") {
        val definition = SystemBuildBookDefinition("house.schem", "Стартовый домик", "a".repeat(64), true, false)
        val data = BuilderSystemBookIssuer.data(definition)
        data.buildingId shouldBe "house.schem"
        data.title shouldBe "Стартовый домик"
        data.systemMaterialsIncluded shouldBe false
        data.playerCreated shouldBe false
        data.registered shouldBe false
        data.instanceId shouldBe null
        BuilderSystemBookIssuer.data(definition.copy(materialsIncluded = true)).systemMaterialsIncluded shouldBe true
    }
    test("random selection validates the starter pool and keeps every valid option") {
        mockkObject(BuildBookSettings)
        every { BuildBookSettings.maxOffset } returns 64
        try {
            val definitions = (1..30).map { id ->
                SystemBuildBookDefinition("starter-$id.schem", "Starter $id", "a".repeat(64), true, false)
            }
            val byId = definitions.associateBy(SystemBuildBookDefinition::buildingId)
            val selected = BuilderSystemBookIssuer.selectRandomDefinition(
                definitions.map(SystemBuildBookDefinition::buildingId) + "missing.schem",
                { byId[it.buildingId] },
                1,
                volume = { 1 },
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
            val definition = SystemBuildBookDefinition("only.schem", "Only", "a".repeat(64), true, false)
            val resolver: (ru.arc.autobuild.BuildBookData) -> SystemBuildBookDefinition? = { definition }
            runCatching {
                BuilderSystemBookIssuer.selectRandomDefinition(emptyList(), resolver, 1, volume = { 1 })
            }.exceptionOrNull()?.message shouldBe "No enabled catalogue entries are available"
            BuilderSystemBookIssuer.selectRandomDefinition(listOf("only.schem"), resolver, 1, volume = { 1 }).second shouldBe emptyList()
        } finally {
            unmockkObject(BuildBookSettings)
        }
    }
})

private class PlayerIssuerFixture(
    val player: PlayerMock,
    val messages: LocalizedMiniMessage,
    val scheduler: TestTaskScheduler,
    val requests: BuilderBookRequests,
) {
    val definition = SystemBuildBookDefinition("civs/alpine-house", "Северный дом", "a".repeat(64), true, true)
    val plain = PlainTextComponentSerializer.plainText()
    fun issue(requestedId: String = definition.buildingId): CompletableFuture<Building> = CompletableFuture<Building>().also { pending ->
        BuilderSystemBookIssuer.issue(player, listOf(requestedId), { definition }, 8192, messages,
            load = { id, sha ->
                id shouldBe definition.buildingId
                sha shouldBe definition.schematicSha256
                pending
            }, requests = requests, permissionDenied = { error("must not deny") })
    }
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
            val scheduler = TestTaskScheduler()
            LifecycleTaskScope(scheduler).use { scope ->
                BuilderBookRequests(scope).use { requests ->
                    test(PlayerIssuerFixture(player, BuilderToolsConfig.load().messages(), scheduler, requests))
                }
            }
        }
    } finally { ConfigManager.clear() }
}
