package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.autobuild.BuildBookCodec
import ru.arc.autobuild.BuildBookData
import ru.arc.autobuild.BuildBookItems
import ru.arc.config.ConfigManager
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.loadPlugin
import ru.ruscrafting.builder.paper.ArcBuilderPlugin

class BuilderBookInventoryLocatorMockBukkitTest : FunSpec({
    test("preview book is found outside the selected hotbar slot") {
        ConfigManager.clear()
        MockBukkitTestRuntime.open().use { paper ->
            paper.loadPlugin<ArcBuilderPlugin>()
            BuilderToolsModule.shutdown()
            val player = paper.addPlayer("InventoryBookOwner")
            val expected = BuildBookData("house.schem", "Дом")
            player.inventory.heldItemSlot = 0
            player.inventory.setItem(0, ItemStack(Material.STONE))
            player.inventory.setItem(8, BuildBookItems.create(expected))

            val located = BuilderBookInventoryLocator.find(player, expected) { _, item, data -> item to data }

            located?.slot shouldBe 8
            located?.data shouldBe expected
            BuildBookCodec.read(player.inventory.itemInMainHand) shouldBe null
        }
        ConfigManager.clear()
    }
})
