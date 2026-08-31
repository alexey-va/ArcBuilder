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
    test("console can validate and reload a disabled generation without a player-only rejection") {
        withReloadPlugin { paper, plugin ->
            val console = paper.server.consoleSender
            val command = checkNotNull(plugin.getCommand("builder"))

            BuilderToolsModule.onCommand(console, command, "builder", arrayOf("reload")) shouldBe true

            plain(console.nextComponentMessage()) shouldContain "применена без перезапуска"
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
            plain(console.nextComponentMessage()) shouldContain "не применена"

            Files.writeString(configPath, valid)
            BuilderToolsModule.onCommand(console, command, "builder", arrayOf("reload")) shouldBe true
            plain(console.nextComponentMessage()) shouldContain "применена без перезапуска"
        }
    }

    test("reload enables a real generation, applies changed messages, and disables it without restart") {
        withReloadPlugin { paper, plugin ->
            prepareEnabledReloadFixture(plugin)
            val console = paper.server.consoleSender
            val command = checkNotNull(plugin.getCommand("builder"))

            BuilderToolsModule.onCommand(console, command, "builder", arrayOf("reload")) shouldBe true
            plain(console.nextComponentMessage()) shouldContain "применена без перезапуска"
            awaitBuilderUp(paper)

            val player = paper.addPlayer("ReloadJourney").apply {
                isOp = false
                setLocale(Locale.forLanguageTag("ru-RU"))
                addAttachment(plugin, "arcbuild.fill", true)
                recalculatePermissions()
            }
            val liveConfig = Config(plugin.dataPath, ConfigManager.moduleYamlRelative(plugin.dataPath, "builder-tools.yml"))
            liveConfig.setStringList("locales.ru.help", listOf("<green>Маркер горячей перезагрузки"))
            liveConfig.saveStrict()

            BuilderToolsModule.onCommand(console, command, "builder", arrayOf("reload")) shouldBe true
            plain(console.nextComponentMessage()) shouldContain "применена без перезапуска"
            awaitBuilderUp(paper)
            BuilderToolsModule.onCommand(player, command, "builder", arrayOf("help")) shouldBe true
            plain(player.nextComponentMessage()) shouldContain "маркер горячей перезагрузки"

            liveConfig.setBoolean("enabled", false)
            liveConfig.saveStrict()
            BuilderToolsModule.onCommand(console, command, "builder", arrayOf("reload")) shouldBe true
            plain(console.nextComponentMessage()) shouldContain "применена без перезапуска"
            BuilderToolsModule.onCommand(player, command, "builder", arrayOf("help")) shouldBe true
            plain(player.nextComponentMessage()) shouldContain "только на survival"
        }
    }
})

private val reloadPlain = PlainTextComponentSerializer.plainText()

private fun plain(component: net.kyori.adventure.text.Component?): String =
    reloadPlain.serialize(checkNotNull(component)).lowercase()

private fun withReloadPlugin(block: (MockBukkitTestRuntime, ArcBuilderPlugin) -> Unit) {
    failOnUnsupportedMockBukkitOperation {
        ConfigManager.clear()
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<ArcBuilderPlugin>()
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
