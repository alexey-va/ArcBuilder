package ru.arc.buildertools

import com.mojang.brigadier.Message
import com.mojang.brigadier.suggestion.Suggestion
import com.mojang.brigadier.suggestion.Suggestions
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player
import ru.arc.autobuild.BuildBookData
import ru.arc.autobuild.BuildBookItems
import ru.arc.autobuild.SystemBuildBookDefinition
import ru.arc.text.LocalizedMiniMessage
import ru.arc.util.Logging
import kotlin.random.Random

/** Administrative issuance of reviewed catalogue books through the native item codec. */
internal object BuilderSystemBookIssuer {
    fun issue(
        sender: CommandSender,
        args: List<String>,
        resolve: (BuildBookData) -> SystemBuildBookDefinition?,
        messages: LocalizedMiniMessage,
        randomBuildingIds: () -> List<String> = { emptyList() },
        permissionDenied: () -> Unit,
    ): Boolean {
        if (!canIssue(sender)) {
            permissionDenied()
            return true
        }
        val explicitRecipient = args.firstOrNull()?.equals(GIVE_SUBCOMMAND, ignoreCase = true) == true
        val issueArgs = when {
            explicitRecipient -> args.drop(1)
            sender is Player && (args.size == 1 || args.size == 2 && args[0].equals(RANDOM_SELECTOR, ignoreCase = true)) ->
                listOf(sender.name) + args
            else -> args // Retain the recipient-first form used by console integrations.
        }
        if (issueArgs.size !in 2..3) {
            if (sender is Player) sender.sendMessage(messages.render("book.system-issue.usage", BuilderLocalePolicy.localeTag(sender)))
            else sender.sendMessage("Usage: builder systembook give <online-player> <building-id|random> [alternative-id,...|@starter]")
            return true
        }
        var schematicId = issueArgs[1]
        try {
            val player = Bukkit.getPlayerExact(issueArgs[0])
                ?: throw Refused("recipient-unavailable", "Player must be online on this server")
            val requestedId = issueArgs[1]
            if (requestedId.equals(RANDOM_SELECTOR, ignoreCase = true) && issueArgs.size != 3)
                throw Refused("pool-required", "Random system-book selection requires an explicit ID list or @starter")
            val options: List<String>
            val definition = if (requestedId.equals(RANDOM_SELECTOR, ignoreCase = true)) {
                val selected = selectRandomDefinition(
                    if (issueArgs[2].equals(STARTER_SELECTOR, ignoreCase = true)) {
                        randomBuildingIds()
                    } else {
                        issueArgs[2].split(',').map(String::trim).filter(String::isNotEmpty)
                    },
                    resolve,
                )
                options = selected.second
                selected.first
            } else {
                val resolved = resolve(BuildBookData(requestedId, "System book").validated())
                    ?: throw Refused("catalogue-unavailable", "Catalogue entry is disabled or missing")
                options = if (issueArgs.size == 3) {
                    (listOf(resolved.buildingId) + issueArgs[2].split(',')).distinct()
                } else emptyList()
                resolved
            }
            schematicId = definition.buildingId
            options.forEach { id ->
                if (resolve(BuildBookData(id, "System book").validated()) == null)
                    throw Refused("catalogue-unavailable", "Selector entry is unavailable: $id")
            }
            val slot = player.inventory.firstEmpty()
            if (slot < 0)
                throw Refused("inventory-full", "Player inventory has no empty slot")
            val bookData = data(definition).copy(
                selectableBuildingIds = options,
                cooldownSeconds = if (requestedId.equals(RANDOM_SELECTOR, ignoreCase = true)) 0L else null,
            ).validated()
            player.inventory.setItem(slot, BuildBookItems.create(bookData))
            if (sender is Player) sender.sendMessage(messages.render(
                "book.system-issue.success", BuilderLocalePolicy.localeTag(sender),
                mapOf("player" to messages.literal(player.name), "name" to messages.literal(definition.title)),
            )) else sender.sendMessage(
                "SYSTEM_BOOK_ISSUED player=${player.name} schematic=${definition.buildingId} slot=$slot",
            )
        } catch (failure: Throwable) {
            if (failure !is Exception && failure !is LinkageError) throw failure
            Logging.warn("SYSTEM_BOOK_REFUSED issuer={} recipient={} schematic={} reason={}",
                logField(sender.name), logField(issueArgs[0]), logField(schematicId),
                logField(failure.message ?: failure.javaClass.simpleName), failure)
            if (sender is Player) {
                val key = (failure as? Refused)?.key ?: "failed"
                sender.sendMessage(messages.render("book.system-issue.$key", BuilderLocalePolicy.localeTag(sender)))
            } else sender.sendMessage("SYSTEM_BOOK_REFUSED ${failure.message}")
        }
        return true
    }

    internal fun selectRandomDefinition(
        buildingIds: List<String>,
        resolve: (BuildBookData) -> SystemBuildBookDefinition?,
        random: Random = Random.Default,
    ): Pair<SystemBuildBookDefinition, List<String>> {
        require(buildingIds.isNotEmpty()) { "No enabled catalogue entries are available" }
        val validDefinitions = buildingIds.distinct().mapNotNull { id ->
            resolve(BuildBookData(id, "System book").validated())
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
        fun schematicSuggestions(): List<String> {
            val prefix = args.last()
            val segmentStart = prefix.lastIndexOf('/') + 1
            return buildingIds().asSequence()
                .filter { it.startsWith(prefix, ignoreCase = true) }
                .map { id ->
                    val nextSlash = id.indexOf('/', segmentStart)
                    if (nextSlash < 0) id else id.substring(0, nextSlash + 1)
                }
                .toList()
        }
        val suggestions = when {
            args.size == 1 -> listOf("systembook")
            !args[0].equals("systembook", ignoreCase = true) -> emptyList()
            args.size == 2 -> listOf(GIVE_SUBCOMMAND) +
                if (sender is Player) listOf(RANDOM_SELECTOR) + schematicSuggestions() else onlinePlayers()
            args[1].equals(GIVE_SUBCOMMAND, ignoreCase = true) -> when {
                args.size == 3 -> onlinePlayers()
                args.size == 4 && onlinePlayers().any { it.equals(args[2], ignoreCase = true) } ->
                    listOf(RANDOM_SELECTOR) + schematicSuggestions()
                args.size == 5 && onlinePlayers().any { it.equals(args[2], ignoreCase = true) } &&
                    args[3].equals(RANDOM_SELECTOR, ignoreCase = true) -> listOf(STARTER_SELECTOR) + schematicSuggestions()
                else -> emptyList()
            }
            args.size == 3 && sender is Player && args[1].equals(RANDOM_SELECTOR, ignoreCase = true) ->
                listOf(STARTER_SELECTOR) + schematicSuggestions()
            args.size == 3 && onlinePlayers().any { it.equals(args[1], ignoreCase = true) } -> listOf(RANDOM_SELECTOR) + schematicSuggestions()
            args.size == 4 && onlinePlayers().any { it.equals(args[1], ignoreCase = true) } && args[2].equals(RANDOM_SELECTOR, ignoreCase = true) ->
                listOf(STARTER_SELECTOR) + schematicSuggestions()
            else -> emptyList()
        }
        return suggestions.filter { it.startsWith(args.last(), ignoreCase = true) }.distinct().sorted()
    }

    fun clientSuggestions(suggestions: Suggestions, directoryTooltip: Message): Suggestions {
        val directories = suggestions.list.filter { it.text.endsWith('/') }.map { it.text.dropLast(1) }.toSet()
        return Suggestions(suggestions.range, suggestions.list.map { suggestion ->
            val text = suggestion.text.removeSuffix("/")
            // Vanilla retains the list after Tab; typing the separator requests the next directory level.
            if (text in directories) Suggestion(suggestion.range, text, directoryTooltip) else suggestion
        }.distinct())
    }

    internal const val PERMISSION = "arcbuild.admin.systembook"
    internal const val GIVE_SUBCOMMAND = "give"
    internal const val RANDOM_SELECTOR = "random"
    internal const val STARTER_SELECTOR = "@starter"

    private class Refused(val key: String, message: String) : IllegalArgumentException(message)
    private fun logField(value: String) = value.filterNot(Char::isISOControl).take(180)

    internal fun canIssue(sender: CommandSender): Boolean =
        sender is ConsoleCommandSender || sender is Player && sender.hasPermission(PERMISSION)

    internal fun data(definition: SystemBuildBookDefinition) = BuildBookData(
        buildingId = definition.buildingId,
        title = definition.title,
        systemMaterialsIncluded = definition.materialsIncluded,
    )
}
