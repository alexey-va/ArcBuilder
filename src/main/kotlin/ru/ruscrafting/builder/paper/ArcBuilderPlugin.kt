package ru.ruscrafting.builder.paper

import org.bukkit.plugin.java.JavaPlugin
import ru.arc.ARC
import ru.arc.config.ConfigManager
import ru.arc.core.PaperArcRuntime
import ru.arc.core.Tasks
import ru.arc.buildertools.BuilderToolsModule
import ru.arc.hooks.HookRegistry
import ru.arc.paper.runtime.PaperPluginRuntime
import java.nio.file.Files
import java.util.logging.Level

open class ArcBuilderPlugin : JavaPlugin() {
    private var lifecycle: PaperPluginRuntime? = null

    override fun onEnable() {
        saveResourceIfMissing("modules/builder-tools.yml")
        saveResourceIfMissing("modules/auto-build.yml")
        saveResourceIfMissing("modules/system-build-books.yml")
        PaperArcRuntime.installScheduling(this)
        val runtime = PaperPluginRuntime(this, "arc-builder").also {
            lifecycle = it
            it.start("version" to pluginMeta.version)
        }
        try {
            val runtimeConfigPath = ConfigManager.moduleYamlPath(dataPath, "builder-tools-runtime.yml")
            val serverId = if (Files.isRegularFile(runtimeConfigPath)) {
                ConfigManager.ofModule(dataPath, "builder-tools-runtime.yml")
                    .string("server-id", server.name.ifBlank { "survival" })
            } else {
                server.name.ifBlank { "survival" }
            }
            ARC.install(this, serverId)
            HookRegistry.start(this)
            BuilderToolsModule.init()
            runtime.registerHealth("builder") { BuilderToolsModule.runtimeHealthContribution() }
            runtime.ready("server" to serverId, "worldguard" to false)
            runtime.reportHealthEvery(1_200L)
            logger.info("ArcBuilder enabled on $serverId")
        } catch (failure: Throwable) {
            runCatching { runtime.health.markDown(); runtime.emitHealth() }
            logger.log(Level.SEVERE, "ArcBuilder failed closed during startup", failure)
            server.pluginManager.disablePlugin(this)
        }
    }

    override fun onDisable() {
        runCatching { BuilderToolsModule.shutdown() }
            .onFailure { logger.log(Level.SEVERE, "Could not close Builder Tools", it) }
        runCatching { HookRegistry.close() }
        runCatching { lifecycle?.close() }
        lifecycle = null
        ARC.clear(this)
        ConfigManager.clear()
        Tasks.reset()
    }

    private fun saveResourceIfMissing(path: String) {
        if (!Files.isRegularFile(dataPath.resolve(path))) saveResource(path, false)
    }
}
