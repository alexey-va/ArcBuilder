package ru.arc.buildertools

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.ARC
import ru.arc.autobuild.BuildingManager
import ru.arc.autobuild.BuildBookSettings
import ru.arc.autobuild.gui.BuildBookEditorGui
import ru.arc.config.ConfigManager
import ru.arc.core.PluginModule
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
    private var reloadService: BuilderToolsReloadService<BuilderToolsRuntime>? = null
    private var messages: LocalizedMiniMessage? = null
    @Volatile private var reloadFailedClosed: Boolean = false

    override fun init() {
        reloadFailedClosed = false
        BuildingManager.clearPreviews()
        bindCommands()
        BuildBookSettings.validate()
        val config = BuilderToolsConfig.load().validated()
        messages = config.messages()
        val initialRuntime = createRuntime(config)
        runtime = initialRuntime
        reloadService = BuilderToolsReloadService(
            initialConfig = config,
            initialRuntime = initialRuntime,
            loadCandidate = { BuilderToolsReloadPreflight.load(ARC.instance.dataPath) },
            createRuntime = ::createRuntime,
            reloadBlocker = BuilderToolsRuntime::reloadBlocker,
            publishConfig = { candidate ->
                val candidateMessages = candidate.messages()
                BuilderToolsConfigPublication.publish(ARC.instance.dataPath)
                messages = candidateMessages
            },
        )
        if (initialRuntime == null) {
            info("Builder tools disabled by configuration")
            return
        }
        info("Builder tools enabled")
    }

    override fun shutdown() {
        BuildBookEditorGui.close()
        reloadService?.close() ?: runtime?.close()
        BuildingManager.clearPreviews()
        reloadService = null
        runtime = null
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.firstOrNull()?.equals("reload", ignoreCase = true) == true) {
            return reload(sender, args)
        }
        val active = runtime
        if (active == null) {
            val locale = (sender as? Player)?.locale()?.toLanguageTag()
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
        val locale = (sender as? Player)?.locale()?.toLanguageTag()
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
                val updated = checkNotNull(messages)
                sender.sendMessage(updated.render("reload.success", locale))
                info("Builder tools configuration reloaded")
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

    private fun createRuntime(config: BuilderToolsConfig): BuilderToolsRuntime? =
        if (config.enabled) BuilderToolsRuntime(ARC.instance, config) else null

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
