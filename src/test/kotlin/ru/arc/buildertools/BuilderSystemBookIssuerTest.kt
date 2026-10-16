package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player
import ru.arc.autobuild.SystemBuildBookDefinition
import ru.arc.autobuild.BuildBookSettings
import kotlin.random.Random
import ru.arc.paper.testing.loadPlugin

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
        BuilderSystemBookIssuer.issue(sender, listOf("house.schem"), { error("must not resolve") }, 8192) { denied = true } shouldBe true
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
        BuilderSystemBookIssuer.issue(admin, emptyList(), { error("must not resolve") }, 8192) { error("must not deny") }
        verify { admin.sendMessage("Usage: builder systembook <online-player> <building-id|random> [alternative-id,...|@starter]") }
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
