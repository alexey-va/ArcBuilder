package ru.arc.buildertools

import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player
import ru.arc.autobuild.BuildBookData
import ru.arc.autobuild.BuildBookItems
import ru.arc.autobuild.Building
import ru.arc.autobuild.SystemBuildBookDefinition

/** Administrative issuance of reviewed catalogue books through the native item codec. */
internal object BuilderSystemBookIssuer {
    fun issue(
        sender: CommandSender,
        args: List<String>,
        resolve: (BuildBookData) -> SystemBuildBookDefinition?,
        maxScanVolume: Long,
        permissionDenied: () -> Unit,
    ): Boolean {
        if (!canIssue(sender)) {
            permissionDenied()
            return true
        }
        val issueArgs = if (sender is Player && args.size == 1) listOf(sender.name, args[0]) else args
        if (issueArgs.size !in 2..3) {
            sender.sendMessage("Usage: builder systembook <online-player> <default.schem> [alternative.schem,...]")
            return true
        }
        try {
            val player = requireNotNull(Bukkit.getPlayerExact(issueArgs[0])) { "Player must be online on this server" }
            val definition = requireNotNull(resolve(BuildBookData(issueArgs[1], "System book").validated())) {
                "Catalogue entry is disabled, missing, or its schematic digest changed"
            }
            val options = if (issueArgs.size == 3) (listOf(definition.buildingId) + issueArgs[2].split(',')).distinct() else emptyList()
            val bookData = data(definition).copy(selectableBuildingIds = options).validated()
            options.forEach { id ->
                requireNotNull(resolve(BuildBookData(id, "System book").validated())) { "Selector entry is unavailable: $id" }
                require(Building(id).volume <= maxScanVolume) { "Selector schematic exceeds the configured scan volume: $id" }
            }
            val building = Building(definition.buildingId)
            require(building.volume <= maxScanVolume) { "Schematic exceeds the configured scan volume" }
            val slot = player.inventory.firstEmpty()
            require(slot >= 0) { "Player inventory has no empty slot" }
            val item = BuildBookItems.create(bookData)
            player.inventory.setItem(slot, item)
            sender.sendMessage("SYSTEM_BOOK_ISSUED player=${player.name} schematic=${definition.buildingId} slot=$slot sha256=${definition.schematicSha256}")
        } catch (failure: IllegalArgumentException) {
            sender.sendMessage("SYSTEM_BOOK_REFUSED ${failure.message}")
        }
        return true
    }

    fun tabComplete(
        sender: CommandSender,
        args: Array<out String>,
        buildingIds: () -> List<String>,
        onlinePlayers: () -> List<String>,
    ): List<String> {
        if (!canIssue(sender) || args.isEmpty()) return emptyList()
        val suggestions = when {
            args.size == 1 -> listOf("systembook")
            !args[0].equals("systembook", ignoreCase = true) -> emptyList()
            args.size == 2 -> onlinePlayers() + if (sender is Player) buildingIds() else emptyList()
            args.size == 3 && onlinePlayers().any { it == args[1] } -> buildingIds()
            else -> emptyList()
        }
        return suggestions.filter { it.startsWith(args.last(), ignoreCase = true) }.distinct().sorted()
    }

    internal const val PERMISSION = "arcbuild.admin.systembook"

    internal fun canIssue(sender: CommandSender): Boolean =
        sender is ConsoleCommandSender || sender is Player && sender.hasPermission(PERMISSION)

    internal fun data(definition: SystemBuildBookDefinition) = BuildBookData(
        buildingId = definition.buildingId,
        title = definition.title,
        systemMaterialsIncluded = definition.materialsIncluded,
    )
}
