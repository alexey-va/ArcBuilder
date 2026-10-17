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
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.Tasks
import ru.arc.core.whenCompleteSync
import ru.arc.hooks.HookRegistry
import ru.arc.observability.RuntimeHealthState
import ru.arc.paper.sql.PaperSqlServices
import ru.arc.paper.runtime.PaperPluginRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

open class ArcBuilderPlugin : JavaPlugin() {
    private class Generation(val runtime: PaperPluginRuntime, val previous: CompletableFuture<Unit>) {
        val drain = CompletableFuture<Unit>()
        var configuration: BoundedAsyncExecutor? = null
        var installedGlobals = false
    }
    private var generation: Generation? = null
    private var predecessor = CompletableFuture.completedFuture(Unit)
    internal val configurationExecutor: BoundedAsyncExecutor
        get() = checkNotNull(generation?.configuration) { "Builder configuration is not accepting work" }
    internal fun drained(): CompletableFuture<Unit> = (generation?.drain ?: predecessor).copy()
    @Volatile
    internal var bootstrapComplete: Boolean = false
        private set

    override fun onEnable() {
        bootstrapComplete = false
        // This local scope can wait without replacing another generation's global scheduler.
        val runtime = PaperPluginRuntime(this, "arc-builder", BukkitTaskScheduler(this))
        val owner = Generation(runtime, predecessor)
        generation = owner
        runtime.start("version" to pluginMeta.version)
        try {
            // Unknown journals remain protected even while an earlier generation is draining.
            BuilderToolsModule.beginStartup(this)
            owner.previous.whenCompleteSync(runtime.tasks) { _, failure ->
                if (generation !== owner) return@whenCompleteSync
                if (failure != null) failStartup(owner, failure) else beginGeneration(owner)
            }
        } catch (failure: Throwable) { failStartup(owner, failure) }
    }

    private fun beginGeneration(owner: Generation) {
        val runtime = owner.runtime
        try {
            PaperArcRuntime.installScheduling(this)
            owner.installedGlobals = true
            val resourceLoader = javaClass.classLoader
            val worker = BoundedAsyncExecutor("arc-builder-configuration", 1, 4).also { owner.configuration = it }
            val dataRoot = dataPath
            val fallbackServerId = server.name.ifBlank { "survival" }
            runtime.own(PaperSqlServices.installConsumer(this, owner.drain))
            runtime.registerHealth("builder") { BuilderToolsModule.runtimeHealthContribution() }
            runtime.reportHealthEvery(1_200L)
            val token = runtime.tasks.token()
            worker.submit {
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
            }.whenCompleteSync(runtime.tasks, token) { prepared, failure ->
                if (generation !== owner) return@whenCompleteSync
                if (failure != null) {
                    failStartup(owner, failure)
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
                    markReadyAfterRecovery(owner, serverId)
                } catch (activationFailure: Throwable) { failStartup(owner, activationFailure) }
            }
        } catch (failure: Throwable) { failStartup(owner, failure) }
    }

    private fun markReadyAfterRecovery(owner: Generation, serverId: String) {
        if (generation !== owner) return
        val runtime = owner.runtime
        when (BuilderToolsModule.runtimeHealthContribution().state) {
            RuntimeHealthState.STARTING -> runtime.tasks.runLater(1L) { markReadyAfterRecovery(owner, serverId) }
            RuntimeHealthState.DOWN -> failStartup(owner, IllegalStateException(
                "Builder-tools recovery or a required dependency is unavailable",
            ))
            RuntimeHealthState.UP, RuntimeHealthState.DEGRADED -> {
                runtime.ready("server" to serverId, "worldguard" to false)
                logger.info("ArcBuilder enabled on $serverId")
            }
        }
    }

    private fun failStartup(owner: Generation, failure: Throwable) {
        if (generation !== owner) return
        val runtime = owner.runtime
        runCatching { runtime.health.markDown(); runtime.emitHealth() }
        logger.log(Level.SEVERE, "ArcBuilder failed closed during startup", failure)
        server.pluginManager.disablePlugin(this)
    }

    override fun onDisable() {
        val owner = generation ?: return
        generation = null
        predecessor = owner.drain
        var nativeFailure: Throwable? = null
        fun attempt(action: () -> Unit) {
            try { action() } catch (failure: Throwable) {
                if (nativeFailure == null) nativeFailure = failure else if (nativeFailure !== failure) nativeFailure!!.addSuppressed(failure)
            }
        }
        // Seal native continuations first; accepted worker work still reaches its actual terminal state.
        attempt { owner.runtime.tasks.close() }
        attempt { BuilderToolsModule.shutdown() }
        val moduleDrain = BuilderToolsModule.drained()
        if (owner.installedGlobals) attempt { HookRegistry.close() }
        attempt { owner.runtime.close() }
        val configurationDrain = runCatching { owner.configuration?.closeAsync() ?: CompletableFuture.completedFuture(Unit) }
            .getOrElse { CompletableFuture.failedFuture(it) }
        bootstrapComplete = false
        if (owner.installedGlobals) {
            attempt { BuilderToolsConfiguration.clear() }
            attempt { ARC.clear(this) }
            attempt { ConfigManager.clear() }
            attempt { Tasks.reset() }
        }
        val closeFailure = nativeFailure
        val drains = listOf(owner.previous, configurationDrain, moduleDrain)
        CompletableFuture.allOf(*drains.toTypedArray()).whenComplete { _, _ ->
            var failure = closeFailure
            drains.forEach { drain ->
                runCatching { drain.join() }.exceptionOrNull()?.let { error ->
                    val cause = error.cause ?: error
                    if (failure == null) failure = cause else if (failure !== cause) failure!!.addSuppressed(cause)
                }
            }
            if (failure == null) owner.drain.complete(Unit) else {
                owner.drain.completeExceptionally(failure)
                logger.log(Level.SEVERE, "Could not drain ArcBuilder generation", failure)
            }
        }
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
