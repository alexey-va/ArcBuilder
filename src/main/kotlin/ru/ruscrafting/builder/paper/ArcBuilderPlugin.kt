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
import ru.arc.core.whenCompleteSync
import ru.arc.hooks.HookRegistry
import ru.arc.observability.RuntimeHealthState
import ru.arc.paper.sql.PaperSqlServices
import ru.arc.paper.runtime.PaperPluginRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.logging.Level

open class ArcBuilderPlugin : JavaPlugin() {
    private var lifecycle: PaperPluginRuntime? = null
    internal lateinit var configurationExecutor: ExecutorService
        private set
    @Volatile
    internal var bootstrapComplete: Boolean = false
        private set

    override fun onEnable() {
        bootstrapComplete = false
        PaperArcRuntime.installScheduling(this)
        val runtime = PaperPluginRuntime(this, "arc-builder").also {
            lifecycle = it
            it.start("version" to pluginMeta.version)
        }
        val dataRoot = dataPath
        val fallbackServerId = server.name.ifBlank { "survival" }
        val resourceLoader = javaClass.classLoader
        configurationExecutor = ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(4),
            { task -> Thread(task, "arc-builder-configuration").apply {
                isDaemon = true
                contextClassLoader = resourceLoader
            } },
        )
        try {
            BuilderToolsModule.beginStartup(this)
            runtime.own(PaperSqlServices.installConsumer(this))
            runtime.registerHealth("builder") { BuilderToolsModule.runtimeHealthContribution() }
            runtime.reportHealthEvery(1_200L)
            val token = runtime.tasks.token()
            CompletableFuture.supplyAsync({
                if (!runtime.tasks.isCurrent(token)) throw java.util.concurrent.CancellationException()
                listOf("builder-tools.yml", "auto-build.yml", "system-build-books.yml").forEach { file ->
                    saveResourceIfMissing(dataRoot, "modules/$file", resourceLoader)
                }
                val prepared = BuilderToolsReloadPreflight.prepare(dataRoot)
                if (!runtime.tasks.isCurrent(token)) throw java.util.concurrent.CancellationException()
                BuilderToolsConfigPublication.publish(prepared)
                val runtimePath = ConfigManager.moduleYamlPath(dataRoot, "builder-tools-runtime.yml")
                val serverId = if (Files.isRegularFile(runtimePath)) {
                    Config(dataRoot, dataRoot.relativize(runtimePath).toString()).string("server-id", fallbackServerId)
                } else fallbackServerId
                prepared to serverId
            }, configurationExecutor).whenCompleteSync(runtime.tasks, token) { prepared, failure ->
                if (failure != null) {
                    failStartup(runtime, failure)
                    return@whenCompleteSync
                }
                try {
                    val (configuration, serverId) = checkNotNull(prepared)
                    configuration.settings.validateNativeMaterials()
                    BuilderToolsConfiguration.install(configuration)
                    ARC.install(this, serverId)
                    HookRegistry.start(this)
                    BuilderToolsModule.init()
                    bootstrapComplete = true
                    markReadyAfterRecovery(runtime, serverId)
                } catch (activationFailure: Throwable) {
                    failStartup(runtime, activationFailure)
                }
            }
        } catch (failure: Throwable) {
            failStartup(runtime, failure)
        }
    }

    private fun markReadyAfterRecovery(runtime: PaperPluginRuntime, serverId: String) {
        when (BuilderToolsModule.runtimeHealthContribution().state) {
            RuntimeHealthState.STARTING -> runtime.tasks.runLater(1L) { markReadyAfterRecovery(runtime, serverId) }
            RuntimeHealthState.DOWN -> failStartup(runtime, IllegalStateException(
                "Builder-tools recovery or a required dependency is unavailable",
            ))
            RuntimeHealthState.UP, RuntimeHealthState.DEGRADED -> {
                runtime.ready("server" to serverId, "worldguard" to false)
                logger.info("ArcBuilder enabled on $serverId")
            }
        }
    }

    private fun failStartup(runtime: PaperPluginRuntime, failure: Throwable) {
        runCatching { runtime.health.markDown(); runtime.emitHealth() }
        logger.log(Level.SEVERE, "ArcBuilder failed closed during startup", failure)
        server.pluginManager.disablePlugin(this)
    }

    override fun onDisable() {
        // Invalidate callbacks before closing any native owner; accepted I/O may finish without resurrection.
        runCatching { lifecycle?.tasks?.close() }
        runCatching { BuilderToolsModule.shutdown() }
            .onFailure { logger.log(Level.SEVERE, "Could not close Builder Tools", it) }
        runCatching { HookRegistry.close() }
        runCatching { lifecycle?.close() }
        lifecycle = null
        if (::configurationExecutor.isInitialized) configurationExecutor.shutdown()
        bootstrapComplete = false
        BuilderToolsConfiguration.clear()
        ARC.clear(this)
        ConfigManager.clear()
        Tasks.reset()
    }

    private fun saveResourceIfMissing(dataRoot: Path, path: String, resourceLoader: ClassLoader) {
        val destination = dataRoot.resolve(path)
        if (Files.isRegularFile(destination)) return
        Files.createDirectories(checkNotNull(destination.parent))
        checkNotNull(resourceLoader.getResourceAsStream(path)) { "Missing bundled resource: $path" }.use {
            Files.copy(it, destination)
        }
    }
}
