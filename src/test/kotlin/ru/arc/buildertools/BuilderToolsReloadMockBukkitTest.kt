package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.config.ConfigManager
import ru.arc.config.Config
import ru.arc.observability.RuntimeHealthState
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.loadPlugin
import ru.ruscrafting.builder.paper.ArcBuilderPlugin
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale

class BuilderToolsReloadMockBukkitTest : FunSpec({
    test("book placement switches refuse busy reload and reach the replacement presentation after draining") {
        withReloadPlugin { paper, plugin ->
            prepareEnabledReloadFixture(plugin)
            val console = paper.server.consoleSender
            val command = checkNotNull(plugin.getCommand("builder"))
            BuilderToolsModule.onCommand(console, command, "builder", arrayOf("reload")) shouldBe true
            awaitConsoleMessage(paper)
            awaitBuilderUp(paper)
            val active = checkNotNull(activeRuntime())
            val previousPresentation = activeBookPreviewPresentation()
            activeBookPreviewModes() shouldBe (true to true)
            val world = paper.addSimpleWorld("book-mode-reload")
            val player = paper.addPlayer("BookModeOwner")
            val selections = BuilderToolsRuntime::class.java.getDeclaredField("selections").apply { isAccessible = true }
                .get(active) as BuilderSelectionController
            val corner = BuilderBlockPos(world.uid, 1, 64, 1)
            selections.set(player.uniqueId, corner, true)
            val override = Config(plugin.dataPath, "modules/builder-tools-runtime.yml")
            override.setBoolean("preview.book-controls.enabled", false)
            override.setBoolean("preview.book-plaque.follow-player", false)
            override.saveStrict()
            BuilderToolsModule.onCommand(console, command, "builder", arrayOf("reload")) shouldBe true
            plain(awaitConsoleMessage(paper)) shouldBe plain(BuilderToolsConfiguration.current().messages.render("reload.busy", "ru"))
            (activeRuntime() === active) shouldBe true
            activeBookPreviewModes() shouldBe (true to true)
            selections.first(player.uniqueId, world.uid) shouldBe corner

            selections.clear(player.uniqueId)
            BuilderToolsModule.onCommand(console, command, "builder", arrayOf("reload")) shouldBe true
            plain(awaitConsoleMessage(paper)) shouldContain "применена без перезапуска"
            awaitBuilderUp(paper)
            (activeRuntime() === active) shouldBe false
            activeBookPreviewModes() shouldBe (false to false)
            BuilderBookPreviewPresentation::class.java.getDeclaredField("closed").apply { isAccessible = true }
                .get(previousPresentation) shouldBe true
            override.setBoolean("preview.book-controls.enabled", true)
            override.setBoolean("preview.book-plaque.follow-player", true)
            override.saveStrict()
            BuilderToolsModule.onCommand(console, command, "builder", arrayOf("reload")) shouldBe true
            plain(awaitConsoleMessage(paper)) shouldContain "применена без перезапуска"
            awaitBuilderUp(paper)
            activeBookPreviewModes() shouldBe (true to true)
        }
    }

    test("the actual reload command applies only appearance in place while a player selection remains open") {
        withReloadPlugin { paper, plugin ->
            prepareEnabledReloadFixture(plugin)
            val console = paper.server.consoleSender
            val command = checkNotNull(plugin.getCommand("builder"))
            BuilderToolsModule.onCommand(console, command, "builder", arrayOf("reload"))
            awaitConsoleMessage(paper)
            awaitBuilderUp(paper)
            val active = checkNotNull(activeRuntime())
            val world = paper.addSimpleWorld("appearance-reload")
            val player = paper.addPlayer("PlaqueReloadOwner")
            val selections = BuilderToolsRuntime::class.java.getDeclaredField("selections").apply { isAccessible = true }
                .get(active) as BuilderSelectionController
            val corner = BuilderBlockPos(world.uid, 1, 64, 1)
            selections.set(player.uniqueId, corner, true)
            active.reloadBlocker() shouldBe BuilderToolsReloadBlocker.VOLATILE_PLAYER_STATE
            val live = Config(plugin.dataPath, ConfigManager.moduleYamlRelative(plugin.dataPath, "builder-tools.yml"))
            live.setDouble("construction.site.panel.background-opacity", .4)
            live.setDouble("construction.site.panel.border-trail.speed", 3.0)
            live.saveStrict()
            BuilderToolsModule.onCommand(console, command, "builder", arrayOf("reload")) shouldBe true
            plain(awaitConsoleMessage(paper)) shouldContain "применена без перезапуска"
            (activeRuntime() === active) shouldBe true
            selections.first(player.uniqueId, world.uid) shouldBe corner
            BuilderToolsConfiguration.current().settings.plaqueAppearance().trail.speed shouldBe 3.0

            // An unchanged retry is also a no-op on the open player state.
            BuilderToolsModule.onCommand(console, command, "builder", arrayOf("reload")) shouldBe true
            plain(awaitConsoleMessage(paper)) shouldContain "применена без перезапуска"
            (activeRuntime() === active) shouldBe true
            selections.first(player.uniqueId, world.uid) shouldBe corner

            live.setDouble("construction.site.panel.border-trail.speed", 4.0)
            live.setInt("limits.blocks-per-tick", 8)
            live.saveStrict()
            BuilderToolsModule.onCommand(console, command, "builder", arrayOf("reload")) shouldBe true
            plain(awaitConsoleMessage(paper)) shouldBe plain(BuilderToolsConfiguration.current().messages.render("reload.busy", "ru"))
            (activeRuntime() === active) shouldBe true
            selections.first(player.uniqueId, world.uid) shouldBe corner
            BuilderToolsConfiguration.current().settings.plaqueAppearance().trail.speed shouldBe 3.0
            BuilderToolsConfiguration.current().settings.blocksPerTick shouldBe 16
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

private fun activeBookPreviewPresentation(): BuilderBookPreviewPresentation =
    BuilderToolsRuntime::class.java.getDeclaredField("bookPreviewPresentation").apply { isAccessible = true }
        .get(checkNotNull(activeRuntime())) as BuilderBookPreviewPresentation

private fun activeBookPreviewModes(): Pair<Boolean, Boolean> {
    val presentation = activeBookPreviewPresentation()
    fun mode(field: String) = BuilderBookPreviewPresentation::class.java.getDeclaredField(field)
        .apply { isAccessible = true }.getBoolean(presentation)
    return mode("worldControlsEnabled") to mode("plaqueFollowPlayer")
}

private fun plain(component: net.kyori.adventure.text.Component?): String =
    reloadPlain.serialize(checkNotNull(component)).lowercase()

private fun withReloadPlugin(block: (MockBukkitTestRuntime, ArcBuilderPlugin) -> Unit) {
    failOnUnsupportedMockBukkitOperation {
        ConfigManager.clear()
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<ArcBuilderPlugin>()
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

private fun awaitConsoleMessage(paper: MockBukkitTestRuntime): net.kyori.adventure.text.Component =
    checkNotNull(paper.server.consoleSender.nextComponentMessage()) { "Synchronous builder reload did not return a result" }
