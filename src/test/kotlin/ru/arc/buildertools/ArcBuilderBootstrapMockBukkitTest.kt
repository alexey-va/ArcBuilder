package ru.arc.buildertools

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPhysicsEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.inventory.ItemStack
import ru.arc.ARC
import ru.arc.awaitArcBuilderBootstrap
import ru.arc.config.ConfigManager
import ru.arc.observability.RuntimeHealthState
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.loadPlugin
import ru.ruscrafting.builder.paper.ArcBuilderPlugin
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ArcBuilderBootstrapMockBukkitTest : FunSpec({
    test("native protection starts before configuration completion and hands off without retaining a global lock") {
        failOnUnsupportedMockBukkitOperation {
            ConfigManager.clear()
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.loadPlugin<ArcBuilderPlugin>()
                plugin.bootstrapComplete shouldBe false
                val player = paper.addPlayer("BootstrapGuard")
                val block = paper.addSimpleWorld("bootstrap-guard").getBlockAt(0, 64, 0)
                val inventory = paper.server.createInventory(null, 9)
                paper.callEvent(BlockBreakEvent(block, player)).isCancelled shouldBe true
                paper.callEvent(BlockPhysicsEvent(block, Material.STONE.createBlockData())).isCancelled shouldBe true
                paper.callEvent(InventoryMoveItemEvent(inventory, ItemStack(Material.STONE), inventory, true)).isCancelled shouldBe true
                paper.callEvent(PlayerCommandPreprocessEvent(player, "/spawn")).isCancelled shouldBe true
                paper.callEvent(EntityDamageEvent(player, EntityDamageEvent.DamageCause.FALL, 1.0)).isCancelled shouldBe true

                awaitArcBuilderBootstrap(paper, plugin)
                awaitBootstrapRecovery(paper)
                paper.callEvent(BlockBreakEvent(block, player)).isCancelled shouldBe false
                paper.callEvent(BlockPhysicsEvent(block, Material.STONE.createBlockData())).isCancelled shouldBe false
                paper.callEvent(InventoryMoveItemEvent(inventory, ItemStack(Material.STONE), inventory, true)).isCancelled shouldBe false
                paper.callEvent(PlayerCommandPreprocessEvent(player, "/spawn")).isCancelled shouldBe false
                paper.callEvent(EntityDamageEvent(player, EntityDamageEvent.DamageCause.FALL, 1.0)).isCancelled shouldBe false
            }
        }
    }

    test("bootstrap returns before preparation and an early command receives the localized loading message") {
        failOnUnsupportedMockBukkitOperation {
            ConfigManager.clear()
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.loadPlugin<ArcBuilderPlugin>()
                plugin.bootstrapComplete shouldBe false
                BuilderToolsModule.runtimeHealthContribution().state shouldBe RuntimeHealthState.STARTING
                val console = paper.server.consoleSender
                val command = checkNotNull(plugin.getCommand("builder"))

                BuilderToolsModule.onCommand(console, command, "builder", arrayOf("status")) shouldBe true
                awaitArcBuilderBootstrap(paper, plugin)
                awaitBootstrapRecovery(paper)

                val message = checkNotNull(console.nextComponentMessage())
                PlainTextComponentSerializer.plainText().serialize(message) shouldContain "восстановление"
                BuilderToolsModule.runtimeHealthContribution().state shouldBe RuntimeHealthState.UP
            }
        }
    }

    test("disable before preparation completion cannot reinstall ARC or the configuration generation") {
        failOnUnsupportedMockBukkitOperation {
            ConfigManager.clear()
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.loadPlugin<ArcBuilderPlugin>()
                paper.server.pluginManager.disablePlugin(plugin)
                plugin.drained().get(10, TimeUnit.SECONDS) shouldBe Unit
                paper.performTicks(3)

                plugin.isEnabled shouldBe false
                plugin.bootstrapComplete shouldBe false
                ARC.plugin shouldBe null
                shouldThrow<IllegalStateException> { BuilderToolsConfiguration.current() }
                val block = paper.addSimpleWorld("disabled-bootstrap").getBlockAt(0, 64, 0)
                paper.callEvent(BlockPhysicsEvent(block, Material.STONE.createBlockData())).isCancelled shouldBe false
            }
        }
    }
    test("replacement and superseded waiting generation cannot pass accepted predecessor work") {
        failOnUnsupportedMockBukkitOperation {
            ConfigManager.clear()
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.loadPlugin<ArcBuilderPlugin>()
                awaitArcBuilderBootstrap(paper, plugin)
                awaitBootstrapRecovery(paper)
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                plugin.configurationExecutor.execute {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
                entered.await(5, TimeUnit.SECONDS) shouldBe true
                try {
                    paper.server.pluginManager.disablePlugin(plugin)
                    val firstDrain = plugin.drained()
                    firstDrain.isDone shouldBe false
                    paper.server.pluginManager.enablePlugin(plugin)
                    paper.performTicks(3)
                    plugin.bootstrapComplete shouldBe false
                    ARC.plugin shouldBe null
                    shouldThrow<IllegalStateException> { BuilderToolsConfiguration.current() }
                    val block = paper.addSimpleWorld("waiting-predecessor").getBlockAt(0, 64, 0)
                    paper.callEvent(BlockPhysicsEvent(block, Material.STONE.createBlockData())).isCancelled shouldBe true
                    // Disabling twice and replacing a still-waiting generation must retain the original fence.
                    paper.server.pluginManager.disablePlugin(plugin)
                    plugin.onDisable()
                    val waitingDrain = plugin.drained()
                    waitingDrain.isDone shouldBe false
                    paper.server.pluginManager.enablePlugin(plugin)
                    paper.performTicks(3)
                    plugin.bootstrapComplete shouldBe false
                    firstDrain.isDone shouldBe false
                    release.countDown()
                    awaitArcBuilderBootstrap(paper, plugin)
                    awaitBootstrapRecovery(paper)
                    firstDrain.get(5, TimeUnit.SECONDS) shouldBe Unit
                    waitingDrain.get(5, TimeUnit.SECONDS) shouldBe Unit
                    paper.callEvent(BlockPhysicsEvent(block, Material.STONE.createBlockData())).isCancelled shouldBe false
                } finally { release.countDown() }
            }
        }
    }

})

private fun awaitBootstrapRecovery(paper: MockBukkitTestRuntime) {
    val deadline = System.nanoTime() + 10_000_000_000L
    while (BuilderToolsModule.runtimeHealthContribution().state == RuntimeHealthState.STARTING && System.nanoTime() < deadline) {
        paper.performTicks(1)
        Thread.yield()
    }
    BuilderToolsModule.runtimeHealthContribution().state shouldBe RuntimeHealthState.UP
}
