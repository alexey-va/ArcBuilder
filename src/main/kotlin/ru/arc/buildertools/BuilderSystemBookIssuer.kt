package ru.arc.buildertools

import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player
import ru.arc.autobuild.BuildBookData
import ru.arc.autobuild.BuildBookItems
import ru.arc.autobuild.Building
import ru.arc.autobuild.BuildingManager
import ru.arc.autobuild.SystemBuildBookDefinition
import kotlin.random.Random

/** Administrative issuance of reviewed catalogue books through the native item codec. */
internal object BuilderSystemBookIssuer {
    fun issue(
        sender: CommandSender,
        args: List<String>,
        resolve: (BuildBookData) -> SystemBuildBookDefinition?,
        maxScanVolume: Long,
        randomBuildingIds: () -> List<String> = { emptyList() },
        permissionDenied: () -> Unit,
    ): Boolean {
        if (!canIssue(sender)) {
            permissionDenied()
            return true
        }
        val issueArgs = if (sender is Player && (args.size == 1 || args.size == 2 && args[0].equals(RANDOM_SELECTOR, ignoreCase = true))) {
            listOf(sender.name) + args
        } else args
        if (issueArgs.size !in 2..3) {
            sender.sendMessage("Usage: builder systembook <online-player> <default.schem|random> [alternative.schem,...|@starter]")
            return true
        }
        try {
            val loadedBuildings = mutableMapOf<String, Building>()
            fun building(id: String): Building = loadedBuildings.getOrPut(id) {
                Building(id).also {
                    require(it.volume <= maxScanVolume) { "Schematic exceeds the configured scan volume: $id" }
                    BuildingManager.addBuilding(it)
                }
            }
            val player = requireNotNull(Bukkit.getPlayerExact(issueArgs[0])) { "Player must be online on this server" }
            val requestedId = issueArgs[1]
            require(!requestedId.equals(RANDOM_SELECTOR, ignoreCase = true) || issueArgs.size == 3) {
                "Random system-book selection requires an explicit ID list or @starter"
            }
            val options: List<String>
            val definition = if (requestedId.equals(RANDOM_SELECTOR, ignoreCase = true)) {
                val selected = selectRandomDefinition(
                    if (issueArgs[2].equals(STARTER_SELECTOR, ignoreCase = true)) {
                        randomBuildingIds()
                    } else {
                        issueArgs[2].split(',').map(String::trim).filter(String::isNotEmpty)
                    },
                    resolve,
                    maxScanVolume,
                    volume = { building(it).volume },
                )
                options = selected.second
                selected.first
            } else {
                val resolved = requireNotNull(resolve(BuildBookData(requestedId, "System book").validated())) {
                    "Catalogue entry is disabled, missing, or its schematic digest changed"
                }
                options = if (issueArgs.size == 3) {
                    (listOf(resolved.buildingId) + issueArgs[2].split(',')).distinct()
                } else emptyList()
                resolved
            }
            options.forEach { id ->
                requireNotNull(resolve(BuildBookData(id, "System book").validated())) { "Selector entry is unavailable: $id" }
                building(id)
            }
            val bookData = data(definition).copy(
                selectableBuildingIds = options,
                cooldownSeconds = if (requestedId.equals(RANDOM_SELECTOR, ignoreCase = true)) 0L else null,
                blockCount = building(definition.buildingId).blockCount,
            ).validated()
            val slot = player.inventory.firstEmpty()
            require(slot >= 0) { "Player inventory has no empty slot" }
            val item = BuildBookItems.create(bookData)
            player.inventory.setItem(slot, item)
            if (sender !is Player) {
                sender.sendMessage("SYSTEM_BOOK_ISSUED player=${player.name} schematic=${definition.buildingId} slot=$slot sha256=${definition.schematicSha256}")
            }
        } catch (failure: IllegalArgumentException) {
            sender.sendMessage("SYSTEM_BOOK_REFUSED ${failure.message}")
        }
        return true
    }

    internal fun selectRandomDefinition(
        buildingIds: List<String>,
        resolve: (BuildBookData) -> SystemBuildBookDefinition?,
        maxScanVolume: Long,
        volume: (String) -> Long,
        random: Random = Random.Default,
    ): Pair<SystemBuildBookDefinition, List<String>> {
        require(buildingIds.isNotEmpty()) { "No enabled catalogue entries are available" }
        val validDefinitions = buildingIds.distinct().mapNotNull { id ->
            resolve(BuildBookData(id, "System book").validated())?.also {
                require(volume(id) <= maxScanVolume) {
                    "Selector schematic exceeds the configured scan volume: $id"
                }
            }
        }
        require(validDefinitions.isNotEmpty()) { "No enabled catalogue entries are currently valid" }
        val selectorOptions = validDefinitions.map(SystemBuildBookDefinition::buildingId).takeIf { it.size >= 2 }.orEmpty()
        return validDefinitions.random(random) to selectorOptions
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
            args.size == 2 -> onlinePlayers() + if (sender is Player) listOf(RANDOM_SELECTOR) + buildingIds() else emptyList()
            args.size == 3 && onlinePlayers().any { it == args[1] } -> listOf(RANDOM_SELECTOR) + buildingIds()
            args.size == 3 && sender is Player && args[1].equals(RANDOM_SELECTOR, ignoreCase = true) ->
                listOf(STARTER_SELECTOR) + buildingIds()
            args.size == 4 && onlinePlayers().any { it == args[1] } && args[2].equals(RANDOM_SELECTOR, ignoreCase = true) ->
                listOf(STARTER_SELECTOR) + buildingIds()
            else -> emptyList()
        }
        return suggestions.filter { it.startsWith(args.last(), ignoreCase = true) }.distinct().sorted()
    }

    internal const val PERMISSION = "arcbuild.admin.systembook"
    internal const val RANDOM_SELECTOR = "random"
    internal const val STARTER_SELECTOR = "@starter"

    internal fun canIssue(sender: CommandSender): Boolean =
        sender is ConsoleCommandSender || sender is Player && sender.hasPermission(PERMISSION)

    internal fun data(definition: SystemBuildBookDefinition) = BuildBookData(
        buildingId = definition.buildingId,
        title = definition.title,
        systemMaterialsIncluded = definition.materialsIncluded,
    )
}
