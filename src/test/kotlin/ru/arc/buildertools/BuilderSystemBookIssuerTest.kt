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
        verify { admin.sendMessage("Usage: builder systembook <online-player> <catalogue-file.schem>") }
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
