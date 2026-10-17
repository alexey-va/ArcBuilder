package ru.arc.buildertools

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.autobuild.BuildingManager
import ru.arc.autobuild.gui.BuildBookEditorGui
import ru.arc.core.PluginModule
import ru.arc.core.LifecycleTaskScope
import ru.ruscrafting.builder.paper.ArcBuilderPlugin
import java.util.concurrent.CompletableFuture
import ru.arc.observability.RuntimeHealthContribution
import ru.arc.observability.RuntimeHealthState
import ru.arc.text.LocalizedMiniMessage
import ru.arc.util.Logging.info
import ru.arc.util.Logging.error
import ru.arc.util.Logging.warn

object BuilderToolsModule : PluginModule, CommandExecutor, TabCompleter {
    override val name: String = "BuilderTools"
    override val priority: Int = 91

    @Volatile
    private var runtime: BuilderToolsRuntime? = null
    @Volatile
    private var reloadService: BuilderToolsReloadService<BuilderToolsPreparedConfiguration, BuilderToolsRuntime>? = null
    private var messages: LocalizedMiniMessage? = null
    @Volatile private var reloadFailedClosed: Boolean = false
    @Volatile private var loading: Boolean = true
    private val pendingLoadingFeedback = linkedSetOf<CommandSender>()
    private var startupLocks: BuilderOperationLocks? = null

    /** Bind immediately, while worker preparation is still in flight. */
    internal fun beginStartup(plugin: ArcBuilderPlugin) {
        // Configuration preparation is asynchronous, so protection starts before any unknown journal can be read.
        val guard = BuilderOperationLocks(plugin).also(BuilderOperationLocks::beginRecoveryBarrier)
        startupLocks?.close()
        startupLocks = guard
        loading = true
        reloadFailedClosed = false
        messages = null
        pendingLoadingFeedback.clear()
        bindCommands(plugin)
    }

    override fun init() {
        val plugin = ARC.instance
        val prepared = BuilderToolsConfiguration.current(plugin.dataPath)
        reloadFailedClosed = false
        BuildingManager.clearPreviews()
        messages = prepared.messages
        val initialRuntime = createRuntime(prepared)
        runtime = initialRuntime
        reloadService = BuilderToolsReloadService(
            initialConfig = prepared,
            initialRuntime = initialRuntime,
            loadCandidate = { BuilderToolsReloadPreflight.prepare(prepared.dataRoot) },
            createRuntime = ::createRuntime,
            reloadBlocker = BuilderToolsRuntime::reloadBlocker,
            publishConfig = BuilderToolsConfigPublication::publish,
            executor = plugin.configurationExecutor,
            tasks = LifecycleTaskScope(),
            validateCandidate = { it.settings.validateNativeMaterials() },
            awaitClosed = { it.closeCompletion },
            activationFailureDrain = {
                (it as? BuilderToolsRuntimeActivationException)?.closeCompletion
                    ?: CompletableFuture.completedFuture(Unit)
            },
            runtimeChanged = { replacement ->
                // The old generation releases its leases before worker drain/publication finishes.
                // Keep that whole transition protected until the new constructor owns recovery.
                if (replacement == null && !loading && startupLocks == null) {
                    startupLocks = BuilderOperationLocks(plugin).also(BuilderOperationLocks::beginRecoveryBarrier)
                }
                runtime = replacement
                if (replacement != null) closeStartupBarrier()
            },
        )
        pendingLoadingFeedback.toList().also { pendingLoadingFeedback.clear() }.forEach(::sendLoading)
        loading = false
        // An enabled runtime already owns its recovery barrier; a disabled feature has no runtime to activate.
        closeStartupBarrier()
        info(if (initialRuntime == null) "Builder tools disabled by configuration" else "Builder tools enabled")
    }

    private fun closeStartupBarrier() {
        startupLocks?.close()
        startupLocks = null
    }

    override fun shutdown() {
        loading = true
        pendingLoadingFeedback.clear()
        try {
            BuildBookEditorGui.close()
            reloadService?.close() ?: runtime?.close()
            BuildingManager.clearPreviews()
            reloadService = null
            runtime = null
            messages = null
        } finally {
            closeStartupBarrier()
        }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (loading || (runtime == null && reloadService?.inProgress == true)) {
            sendLoading(sender)
            return true
        }
        if (args.firstOrNull()?.equals("reload", ignoreCase = true) == true) {
            return reload(sender, args)
        }
        val active = runtime
        if (active == null) {
            val locale = BuilderLocalePolicy.localeTag(sender as? Player)
            sender.sendMessage(checkNotNull(messages).render("errors.disabled", locale))
            return true
        }
        return active.onCommand(sender, command, label, args)
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> {
        val suggestions = runtime?.onTabComplete(sender, command, alias, args).orEmpty()
        if (args.size != 1 || !sender.hasPermission(RELOAD_PERMISSION)) return suggestions
        return (suggestions + "reload")
            .distinct()
            .filter { it.startsWith(args[0], ignoreCase = true) }
    }

    fun rejectUnsafeAuctionSale(player: Player) {
        runtime?.rejectUnsafeAuctionSale(player)
    }

    fun runtimeHealthContribution(): RuntimeHealthContribution =
        if (reloadFailedClosed) {
            RuntimeHealthContribution(
                state = RuntimeHealthState.DOWN,
                dependencies = mapOf("reload_rollback" to false),
            )
        } else if (loading || (runtime == null && reloadService?.inProgress == true)) {
            RuntimeHealthContribution(state = RuntimeHealthState.STARTING)
        } else {
            runtime?.runtimeHealthContribution() ?: RuntimeHealthContribution()
        }

    private fun sendLoading(sender: CommandSender) {
        val catalog = messages
        if (catalog == null) {
            if (pendingLoadingFeedback.size < 256) pendingLoadingFeedback += sender
            return
        }
        if (sender is Player && !sender.isOnline) return
        sender.sendMessage(catalog.render("errors.recovering", BuilderLocalePolicy.localeTag(sender as? Player)))
    }

    private fun reload(sender: CommandSender, args: Array<out String>): Boolean {
        val catalog = checkNotNull(messages)
        val locale = BuilderLocalePolicy.localeTag(sender as? Player)
        if (!sender.hasPermission(RELOAD_PERMISSION)) {
            sender.sendMessage(catalog.render("errors.no-permission", locale))
            return true
        }
        if (args.size != 1) {
            sender.sendMessage(
                catalog.render(
                    "reload.failed",
                    locale,
                    mapOf("reason" to catalog.literal("usage: /builder reload")),
                ),
            )
            return true
        }
        val service = reloadService ?: run {
            sender.sendMessage(
                catalog.render(
                    "reload.failed",
                    locale,
                    mapOf("reason" to catalog.literal("reload service is unavailable")),
                ),
            )
            return true
        }
        service.reload().thenAccept { result ->
            when (result) {
                is BuilderToolsReloadResult.Applied -> {
                    messages = result.config.messages
                    if (runtime == null) closeStartupBarrier()
                    sender.sendMessage(result.config.messages.render("reload.success", locale))
                    info("Builder tools configuration reloaded")
                }
                is BuilderToolsReloadResult.Busy -> sender.sendMessage(catalog.render("reload.busy", locale))
                is BuilderToolsReloadResult.Rejected -> {
                    val reason = safeReloadReason(result.failure)
                    sender.sendMessage(
                        catalog.render("reload.failed", locale, mapOf("reason" to catalog.literal(reason))),
                    )
                    if (result.rollbackFailure != null) {
                        reloadFailedClosed = true
                        error("Builder-tools reload and rollback both failed", result.rollbackFailure)
                        ARC.instance.server.pluginManager.disablePlugin(ARC.instance)
                    } else {
                        // A successful rollback may restore a disabled configuration with no runtime.
                        if (runtime == null) closeStartupBarrier()
                        warn("Builder-tools reload rejected: {}", reason)
                    }
                }
                BuilderToolsReloadResult.Cancelled -> Unit
            }
        }
        return true
    }

    private fun createRuntime(prepared: BuilderToolsPreparedConfiguration): BuilderToolsRuntime? {
        BuilderToolsConfiguration.install(prepared)
        val config = prepared.settings
        BuilderLocalePolicy.configure(config.defaultLocaleTag, config.followClientLocale)
        if (!config.enabled) return null
        val catalog = checkNotNull(prepared.systemBooks)
        return BuilderToolsRuntime(
            ARC.instance,
            config,
            systemBuildBookResolver = catalog::resolve,
            systemBuildBookIds = catalog.enabledBuildingIds,
            systemBuildBookStarterIds = catalog.starterBuildingIds,
        )
    }

    private fun safeReloadReason(failure: Throwable): String =
        (failure.message ?: failure::class.simpleName ?: "unknown error")
            .filterNot(Char::isISOControl)
            .take(180)

    private fun bindCommands(plugin: ArcBuilderPlugin) {
        val command = plugin.getCommand("builder")
        if (command == null) {
            warn("Builder-tools command 'builder' is missing from plugin.yml")
        } else {
            command.setExecutor(this)
            command.tabCompleter = this
        }
    }

    private const val RELOAD_PERMISSION = "arcbuild.admin.reload"
}
