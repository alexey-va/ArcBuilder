package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.command.CommandSender
import ru.arc.autobuild.SystemBuildBookDefinition

class BuilderSystemBookIssuerTest : FunSpec({
    test("non-console callers cannot resolve or issue a system book") {
        val sender = mockk<CommandSender>(relaxed = true)
        BuilderSystemBookIssuer.issue(sender, listOf("CodexQA_728", "house.schem"), { error("must not resolve") }, 8192) shouldBe true
        verify { sender.sendMessage("This operation is available only from the server console.") }
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
