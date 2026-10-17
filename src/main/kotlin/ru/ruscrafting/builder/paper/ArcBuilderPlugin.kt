package ru.ruscrafting.builder.paper

import org.bukkit.plugin.java.JavaPlugin
import ru.arc.ARC
import ru.arc.buildertools.BuilderToolsConfiguration
import ru.arc.buildertools.BuilderToolsConfigPublication
import ru.arc.buildertools.BuilderToolsModule
import ru.arc.buildertools.BuilderToolsReloadPreflight
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.core.PaperArcRuntime
import ru.arc.core.Tasks
import ru.arc.hooks.HookRegistry
import ru.arc.paper.sql.PaperSqlServices
import ru.arc.paper.runtime.PaperPluginRuntime
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

open class ArcBuilderPlugin : JavaPlugin() {
    private var lifecycle: PaperPluginRuntime? = null

    /** Observation of detached packet cleanup; start/stop never waits for future Paper ticks. */
    internal fun drained(): CompletableFuture<Unit> = BuilderToolsModule.drained()

    override fun onEnable() {
        PaperArcRuntime.installScheduling(this)
        val runtime = PaperPluginRuntime(this, "arc-builder").also {
            lifecycle = it
            it.start("version" to pluginMeta.version)
        }
        try {
            listOf("builder-tools.yml", "auto-build.yml", "system-build-books.yml").forEach {
                saveResourceIfMissing("modules/$it")
            }
            val configuration = BuilderToolsReloadPreflight.prepare(dataPath)
            configuration.settings.validateNativeMaterials()
            BuilderToolsConfigPublication.publish(configuration)
            BuilderToolsConfiguration.install(configuration)
            runtime.own(PaperSqlServices.installConsumer(this))
            val runtimePath = ConfigManager.moduleYamlPath(dataPath, "builder-tools-runtime.yml")
            val serverId = if (Files.isRegularFile(runtimePath)) {
                Config(dataPath, dataPath.relativize(runtimePath).toString())
                    .string("server-id", server.name.ifBlank { "survival" })
            } else server.name.ifBlank { "survival" }
            ARC.install(this, serverId)
            HookRegistry.start(this)
            BuilderToolsModule.init()
            runtime.registerHealth("builder") { BuilderToolsModule.runtimeHealthContribution() }
            // Recovery contributes STARTING until its native steps and durable reads finish.
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
            .onFailure { logger.log(Level.SEVERE, "Could not close ArcBuilder lifecycle", it) }
        lifecycle = null
        BuilderToolsConfiguration.clear()
        ARC.clear(this)
        ConfigManager.clear()
        Tasks.reset()
    }

    private fun saveResourceIfMissing(path: String) {
        if (!Files.isRegularFile(dataPath.resolve(path))) saveResource(path, false)
    }
}
