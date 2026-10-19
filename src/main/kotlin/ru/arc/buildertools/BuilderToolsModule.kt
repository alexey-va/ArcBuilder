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
import ru.arc.observability.RuntimeHealthContribution
import ru.arc.observability.RuntimeHealthState
import ru.arc.text.LocalizedMiniMessage
import ru.arc.util.Logging.info
import ru.arc.util.Logging.error
import ru.arc.util.Logging.warn
import java.util.concurrent.CompletableFuture

object BuilderToolsModule : PluginModule, CommandExecutor, TabCompleter {
    override val name: String = "BuilderTools"
    override val priority: Int = 91

    @Volatile
    private var runtime: BuilderToolsRuntime? = null
    private var reloadService: BuilderToolsReloadService<BuilderToolsPreparedConfiguration, BuilderToolsRuntime>? = null
    private var messages: LocalizedMiniMessage? = null
    @Volatile private var reloadFailedClosed: Boolean = false
    private var nativeClose = CompletableFuture.completedFuture(Unit)

    /** Detached Core packet cleanup only; durable writes are finished by shutdown itself. */
    internal fun drained(): CompletableFuture<Unit> = nativeClose.copy()

    override fun init() {
        reloadFailedClosed = false
        BuildingManager.clearPreviews()
        bindCommands()
        val prepared = BuilderToolsConfiguration.current(ARC.instance.dataPath)
        messages = prepared.messages
        val initialRuntime = createRuntime(prepared)
        runtime = initialRuntime
        reloadService = BuilderToolsReloadService(
            initialConfig = prepared,
            initialRuntime = initialRuntime,
            loadCandidate = { BuilderToolsReloadPreflight.prepare(ARC.instance.dataPath) },
            createRuntime = ::createRuntime,
            reloadBlocker = BuilderToolsRuntime::reloadBlocker,
            publishConfig = BuilderToolsConfigPublication::publish,
            validateCandidate = { it.settings.validateNativeMaterials() },
            canRefreshInPlace = ::onlyPlaqueAppearanceChanged,
            refreshInPlace = { active, candidate ->
                active.updatePlaqueAppearance(candidate.settings)
                BuilderToolsConfiguration.install(candidate)
            },
        )
        if (initialRuntime == null) {
            info("Builder tools disabled by configuration")
            return
        }
        info("Builder tools enabled")
    }

    override fun shutdown() {
        val previous = runtime
        var failure: Throwable? = null
        fun attempt(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        attempt { BuildBookEditorGui.close() }
        attempt { reloadService?.close() ?: previous?.close() }
        attempt { BuildingManager.clearPreviews() }
        previous?.let { nativeClose = it.closeCompletion }
        reloadService = null
        runtime = null
        messages = null
        failure?.let { throw it }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
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
        } else {
            runtime?.runtimeHealthContribution() ?: RuntimeHealthContribution()
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
        val result = service.reload()
        runtime = service.runtime
        when (result) {
            is BuilderToolsReloadResult.Applied -> {
                messages = result.config.messages
                sender.sendMessage(result.config.messages.render("reload.success", locale))
                info(if (result.inPlace) "Builder tools configuration reloaded: plaque appearance updated in place" else "Builder tools configuration reloaded")
            }
            is BuilderToolsReloadResult.Busy -> sender.sendMessage(catalog.render("reload.busy", locale))
            is BuilderToolsReloadResult.Rejected -> {
                val reason = safeReloadReason(result.failure)
                sender.sendMessage(
                    catalog.render(
                        "reload.failed",
                        locale,
                        mapOf("reason" to catalog.literal(reason)),
                    ),
                )
                if (result.rollbackFailure != null) {
                    reloadFailedClosed = true
                    error("Builder-tools reload and rollback both failed", result.rollbackFailure)
                    ARC.instance.server.pluginManager.disablePlugin(ARC.instance)
                } else {
                    warn("Builder-tools reload rejected: {}", reason)
                }
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

    private fun bindCommands() {
        val command = ARC.instance.getCommand("builder")
        if (command == null) {
            warn("Builder-tools command 'builder' is missing from plugin.yml")
        } else {
            command.setExecutor(this)
            command.tabCompleter = this
        }
    }

    private const val RELOAD_PERMISSION = "arcbuild.admin.reload"
}
