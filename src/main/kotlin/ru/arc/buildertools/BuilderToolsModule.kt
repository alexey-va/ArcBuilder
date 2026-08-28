package ru.arc.buildertools

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.ARC
import ru.arc.core.PluginModule
import ru.arc.observability.RuntimeHealthContribution
import ru.arc.text.LocalizedMiniMessage
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn

object BuilderToolsModule : PluginModule, CommandExecutor, TabCompleter {
    override val name: String = "BuilderTools"
    override val priority: Int = 91

    @Volatile
    private var runtime: BuilderToolsRuntime? = null
    private var messages: LocalizedMiniMessage? = null

    override fun init() {
        bindCommands()
        val config = BuilderToolsConfig.load().validated()
        messages = config.messages()
        if (!config.enabled) {
            info("Builder tools disabled by configuration")
            return
        }
        runtime = BuilderToolsRuntime(ARC.instance, config)
        info("Builder tools enabled")
    }

    override fun shutdown() {
        runtime?.close()
        runtime = null
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
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
    ): List<String> = runtime?.onTabComplete(sender, command, alias, args).orEmpty()

    fun rejectUnsafeAuctionSale(player: Player) {
        runtime?.rejectUnsafeAuctionSale(player)
    }

    fun runtimeHealthContribution(): RuntimeHealthContribution =
        runtime?.runtimeHealthContribution() ?: RuntimeHealthContribution()

    private fun bindCommands() {
        val command = ARC.instance.getCommand("builder")
        if (command == null) {
            warn("Builder-tools command 'builder' is missing from plugin.yml")
        } else {
            command.setExecutor(this)
            command.tabCompleter = this
        }
    }
}
