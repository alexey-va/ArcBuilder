package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPhysicsEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import ru.arc.config.ConfigManager
import ru.arc.awaitArcBuilderBootstrap
import ru.arc.config.Config
import ru.arc.observability.RuntimeHealthState
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.loadPlugin
import ru.ruscrafting.builder.paper.ArcBuilderPlugin
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BuilderToolsReloadMockBukkitTest : FunSpec({
    test("reload protects native mutations while publication waits and releases the guard after enabled or disabled activation") {
        withReloadPlugin { paper, plugin ->
            prepareEnabledReloadFixture(plugin)
            val command = checkNotNull(plugin.getCommand("builder"))
            BuilderToolsModule.onCommand(paper.server.consoleSender, command, "builder", arrayOf("reload")) shouldBe true
            plain(awaitConsoleMessage(paper)) shouldContain "применена без перезапуска"
            awaitBuilderUp(paper)
            val probe = ReloadMutationProbe(paper)
            probe.checkCancelled(false)

            listOf(true, false).forEach { enabled ->
                Config(plugin.dataPath, ConfigManager.moduleYamlRelative(plugin.dataPath, "builder-tools.yml")).apply {
                    setBoolean("enabled", enabled)
                    saveStrict()
                }
                withBlockedReloadPublication(paper, plugin) { probe.checkCancelled(true) }
                plain(awaitConsoleMessage(paper)) shouldContain "применена без перезапуска"
                awaitBuilderUp(paper)
                (activeRuntime() != null) shouldBe enabled
                probe.checkCancelled(false)
            }
        }
    }

    test("successful rollback to a disabled generation releases the publication guard after a late source conflict") {
        withReloadPlugin { paper, plugin ->
            activeRuntime() shouldBe null
            prepareEnabledReloadFixture(plugin)
            val probe = ReloadMutationProbe(paper)
            probe.checkCancelled(false)
            withBlockedReloadPublication(paper, plugin) {
                probe.checkCancelled(true)
                val configPath = ConfigManager.moduleYamlPath(plugin.dataPath, "builder-tools.yml")
                Files.writeString(configPath, Files.readString(configPath) + "\n# operator edit after validation\n")
            }

            plain(awaitConsoleMessage(paper)) shouldContain "не применена"
            plugin.isEnabled shouldBe true
            activeRuntime() shouldBe null
            BuilderToolsModule.runtimeHealthContribution().state shouldBe RuntimeHealthState.UP
            probe.checkCancelled(false)
        }
    }

    test("console can validate and reload a disabled generation without a player-only rejection") {
        withReloadPlugin { paper, plugin ->
            val console = paper.server.consoleSender
            val command = checkNotNull(plugin.getCommand("builder"))

            BuilderToolsModule.onCommand(console, command, "builder", arrayOf("reload")) shouldBe true

            plain(awaitConsoleMessage(paper)) shouldContain "применена без перезапуска"
        }
    }

    test("unauthorized player cannot reload and does not receive the admin completion") {
        withReloadPlugin { paper, plugin ->
            val player = paper.addPlayer("ReloadDenied").apply {
                isOp = false
                setLocale(java.util.Locale.forLanguageTag("ru-RU"))
            }
            val command = checkNotNull(plugin.getCommand("builder"))

            BuilderToolsModule.onCommand(player, command, "builder", arrayOf("reload")) shouldBe true

            plain(checkNotNull(player.nextComponentMessage())) shouldContain "нет доступа"
            BuilderToolsModule.onTabComplete(player, command, "builder", arrayOf("")) shouldNotContain "reload"
        }
    }

    test("authorized player receives only the reload completion while tools are disabled") {
        withReloadPlugin { paper, plugin ->
            val player = paper.addPlayer("ReloadAdmin").apply {
                isOp = false
                addAttachment(plugin, "arcbuild.admin.reload", true)
                recalculatePermissions()
            }
            val command = checkNotNull(plugin.getCommand("builder"))

            val suggestions = BuilderToolsModule.onTabComplete(player, command, "builder", arrayOf(""))

            suggestions shouldContain "reload"
            suggestions shouldNotContain "fill"
            suggestions shouldNotContain "book"
        }
    }

    test("invalid YAML is reported and the previous generation remains available for a corrected retry") {
        withReloadPlugin { paper, plugin ->
            val console = paper.server.consoleSender
            val command = checkNotNull(plugin.getCommand("builder"))
            val configPath = ConfigManager.moduleYamlPath(plugin.dataPath, "builder-tools.yml")
            val valid = Files.readString(configPath)
            Files.writeString(configPath, "enabled: true\nallowed-worlds: [survival\n")

            BuilderToolsModule.onCommand(console, command, "builder", arrayOf("reload")) shouldBe true
            plain(awaitConsoleMessage(paper)) shouldContain "не применена"

            Files.writeString(configPath, valid)
            BuilderToolsModule.onCommand(console, command, "builder", arrayOf("reload")) shouldBe true
            plain(awaitConsoleMessage(paper)) shouldContain "применена без перезапуска"
        }
    }

    test("reload enables a real generation, applies changed messages, and disables it without restart") {
        withReloadPlugin { paper, plugin ->
            prepareEnabledReloadFixture(plugin)
            val console = paper.server.consoleSender
            val command = checkNotNull(plugin.getCommand("builder"))

            BuilderToolsModule.onCommand(console, command, "builder", arrayOf("reload")) shouldBe true
            plain(awaitConsoleMessage(paper)) shouldContain "применена без перезапуска"
            awaitBuilderUp(paper)
            activePanelSettings().suppressSelectionGlow shouldBe true
            activePanelSettings().obstacleMaxShift shouldBe 1.25

            val player = paper.addPlayer("ReloadJourney").apply {
                isOp = false
                setLocale(Locale.forLanguageTag("ru-RU"))
                addAttachment(plugin, "arcbuild.fill", true)
                recalculatePermissions()
            }
            val liveConfig = Config(plugin.dataPath, ConfigManager.moduleYamlRelative(plugin.dataPath, "builder-tools.yml"))
            liveConfig.setStringList("locales.ru.help", listOf("<green>Маркер горячей перезагрузки"))
            liveConfig.setBoolean("selection-panel.suppress-selection-glow", false)
            liveConfig.setDouble("selection-panel.obstacle-max-shift", 0.5)
            liveConfig.saveStrict()

            BuilderToolsModule.onCommand(console, command, "builder", arrayOf("reload")) shouldBe true
            plain(awaitConsoleMessage(paper)) shouldContain "применена без перезапуска"
            awaitBuilderUp(paper)
            activePanelSettings().suppressSelectionGlow shouldBe false
            activePanelSettings().obstacleMaxShift shouldBe 0.5
            BuilderToolsModule.onCommand(player, command, "builder", arrayOf("help")) shouldBe true
            plain(player.nextComponentMessage()) shouldContain "маркер горячей перезагрузки"

            liveConfig.setBoolean("enabled", false)
            liveConfig.saveStrict()
            BuilderToolsModule.onCommand(console, command, "builder", arrayOf("reload")) shouldBe true
            plain(awaitConsoleMessage(paper)) shouldContain "применена без перезапуска"
            BuilderToolsModule.onCommand(player, command, "builder", arrayOf("help")) shouldBe true
            plain(player.nextComponentMessage()) shouldContain "только на survival"
        }
    }
})

private val reloadPlain = PlainTextComponentSerializer.plainText()

private fun activeRuntime(): BuilderToolsRuntime? =
    BuilderToolsModule::class.java.getDeclaredField("runtime").apply { isAccessible = true }
        .get(BuilderToolsModule) as BuilderToolsRuntime?

private fun activePanelSettings(): BuilderPanelSettings {
    val panel = BuilderToolsRuntime::class.java.getDeclaredField("selectionActionPanel").apply { isAccessible = true }
        .get(checkNotNull(activeRuntime()))
    return BuilderSelectionActionPanel::class.java.getDeclaredField("settings").apply { isAccessible = true }
        .get(checkNotNull(panel)) as BuilderPanelSettings
}

private fun plain(component: net.kyori.adventure.text.Component?): String =
    reloadPlain.serialize(checkNotNull(component)).lowercase()

private fun withReloadPlugin(block: (MockBukkitTestRuntime, ArcBuilderPlugin) -> Unit) {
    failOnUnsupportedMockBukkitOperation {
        ConfigManager.clear()
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<ArcBuilderPlugin>()
            awaitArcBuilderBootstrap(paper, plugin)
            installArcVisualPacketBudgetFixture(paper.createSimplePlugin("ArcCoreVisualFixture"))
            try {
                block(paper, plugin)
            } finally {
                paper.server.pluginManager.disablePlugin(plugin)
                ConfigManager.clear()
            }
        }
    }
}

/** Stops the real configuration executor after preflight, while native close/drain queues publication. */
private fun withBlockedReloadPublication(
    paper: MockBukkitTestRuntime,
    plugin: ArcBuilderPlugin,
    checkWhileBlocked: () -> Unit,
) {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val executor = plugin.configurationExecutor
    BuilderToolsModule.onCommand(
        paper.server.consoleSender, checkNotNull(plugin.getCommand("builder")), "builder", arrayOf("reload"),
    ) shouldBe true
    executor.execute {
        entered.countDown()
        check(release.await(10, TimeUnit.SECONDS)) { "Reload publication test gate timed out" }
    }
    try {
        entered.await(5, TimeUnit.SECONDS) shouldBe true
        val probe = paper.server.worlds.first().getBlockAt(4, 64, 4)
        fun guarded() = paper.callEvent(BlockPhysicsEvent(probe, Material.STONE.createBlockData())).isCancelled
        val deadline = System.nanoTime() + 5_000_000_000L
        // A disabled old generation is already null before preflight. Wait for the actual
        // native publication guard, rather than a particular executor queue implementation.
        while (!guarded() && System.nanoTime() < deadline) {
            paper.performTicks(1)
            Thread.yield()
        }
        guarded() shouldBe true
        activeRuntime() shouldBe null
        BuilderToolsModule.runtimeHealthContribution().state shouldBe RuntimeHealthState.STARTING
        checkWhileBlocked()
    } finally {
        release.countDown()
    }
}

private class ReloadMutationProbe(private val paper: MockBukkitTestRuntime) {
    private val player = paper.addPlayer("ReloadGuard")
    private val block = player.world.getBlockAt(0, 64, 0)
    private val inventory = paper.server.createInventory(null, 9)

    fun checkCancelled(expected: Boolean) {
        paper.callEvent(BlockBreakEvent(block, player)).isCancelled shouldBe expected
        paper.callEvent(BlockPhysicsEvent(block, Material.STONE.createBlockData())).isCancelled shouldBe expected
        paper.callEvent(InventoryMoveItemEvent(inventory, ItemStack(Material.STONE), inventory, true)).isCancelled shouldBe expected
        paper.callEvent(PlayerInteractEvent(
            player, Action.RIGHT_CLICK_BLOCK, ItemStack(Material.BREAD), block, BlockFace.UP, EquipmentSlot.HAND,
        )).isCancelled shouldBe expected
        paper.callEvent(PlayerItemConsumeEvent(player, ItemStack(Material.BREAD), EquipmentSlot.HAND)).isCancelled shouldBe expected
    }
}

private fun prepareEnabledReloadFixture(plugin: ArcBuilderPlugin) {
    val schematics = Files.createDirectories(plugin.dataPath.resolve("schematics"))
    val bytes = "mock schematic contract".toByteArray()
    Files.write(schematics.resolve("reload-test.schem"), bytes)
    val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    Files.writeString(
        plugin.dataPath.resolve("modules/system-build-books.yml"),
        """
        books:
          - building-id: reload-test.schem
            title: Reload test
            sha256: $sha256
            player-enabled: true
        """.trimIndent() + "\n",
    )
    Config(plugin.dataPath, ConfigManager.moduleYamlRelative(plugin.dataPath, "builder-tools.yml")).apply {
        setBoolean("enabled", true)
        setStringList("allowed-worlds", listOf("*"))
        setBoolean("safety.require-coreprotect", false)
        setBoolean("safety.require-lands", false)
        setBoolean("shop.enabled", false)
        setBoolean("book-contracts.enabled", false)
        saveStrict()
    }
}

private fun awaitBuilderUp(paper: MockBukkitTestRuntime) {
    val deadline = System.nanoTime() + 5_000_000_000L
    while (
        BuilderToolsModule.runtimeHealthContribution().state != RuntimeHealthState.UP &&
        System.nanoTime() < deadline
    ) {
        paper.performTicks(1)
        Thread.yield()
    }
    BuilderToolsModule.runtimeHealthContribution().state shouldBe RuntimeHealthState.UP
}

private fun awaitConsoleMessage(paper: MockBukkitTestRuntime): net.kyori.adventure.text.Component {
    val deadline = System.nanoTime() + 10_000_000_000L
    while (System.nanoTime() < deadline) {
        paper.server.consoleSender.nextComponentMessage()?.let { return it }
        paper.performTicks(1)
        Thread.yield()
    }
    error("Builder reload did not return a console result")
}
