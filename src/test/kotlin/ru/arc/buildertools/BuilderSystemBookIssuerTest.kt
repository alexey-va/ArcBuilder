package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player
import ru.arc.autobuild.SystemBuildBookDefinition

class BuilderSystemBookIssuerTest : FunSpec({
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
        complete(admin, "systembook", "") shouldBe listOf("GrocerMC", "birch.schem", "forest.schem")
        complete(console, "systembook", "") shouldBe listOf("GrocerMC")
        complete(console, "systembook", "GrocerMC", "FOR") shouldBe listOf("forest.schem")
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
        verify { admin.sendMessage("Usage: builder systembook <online-player> <default.schem> [alternative.schem,...]") }
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
})
