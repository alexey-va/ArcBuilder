package ru.arc.buildertools

import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player
import ru.arc.autobuild.BuildBookData
import ru.arc.autobuild.BuildBookItems
import ru.arc.autobuild.Building
import ru.arc.autobuild.SystemBuildBookDefinition
import ru.arc.text.LocalizedMiniMessage
import ru.arc.util.Logging
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.random.Random

/** Administrative issuance of reviewed catalogue books through the native item codec. */
internal object BuilderSystemBookIssuer {
    fun issue(
        sender: CommandSender,
        args: List<String>,
        resolve: (BuildBookData) -> SystemBuildBookDefinition?,
        maxScanVolume: Long,
        messages: LocalizedMiniMessage,
        randomBuildingIds: () -> List<String> = { emptyList() },
        load: (String, String?) -> CompletableFuture<Building> = { _, _ ->
            CompletableFuture.failedFuture(IllegalStateException("Schematic service is unavailable"))
        },
        requests: BuilderBookRequests? = null,
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
            if (sender is Player) sender.sendMessage(messages.render("book.system-issue.usage", BuilderLocalePolicy.localeTag(sender)))
            else sender.sendMessage("Usage: builder systembook <online-player> <building-id|random> [alternative-id,...|@starter]")
            return true
        }
        var schematicId = issueArgs[1]
        fun refused(failure: Throwable?) {
            val cause = generateSequence(failure) {
                if (it is CompletionException || it is ExecutionException) it.cause else null
            }.lastOrNull()
            if (cause != null && cause !is Exception && cause !is LinkageError) throw cause
            val reason = cause?.message ?: cause?.javaClass?.simpleName ?: "Recipient changed context"
            Logging.warn("SYSTEM_BOOK_REFUSED issuer={} recipient={} schematic={} reason={}",
                logField(sender.name), logField(issueArgs[0]), logField(schematicId), logField(reason), cause)
            if (sender is Player) {
                val key = when (cause) {
                    is Refused -> cause.key
                    is CancellationException, null -> "cancelled"
                    else -> "failed"
                }
                sender.sendMessage(messages.render("book.system-issue.$key", BuilderLocalePolicy.localeTag(sender)))
            } else sender.sendMessage("SYSTEM_BOOK_REFUSED ${failure?.message ?: "Recipient changed context"}")
        }
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
                    maxScanVolume,
                    volume = { 0L },
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
            val requester = requireNotNull(requests) { "Schematic service is unavailable" }
            if (player.inventory.firstEmpty() < 0)
                throw Refused("inventory-full", "Player inventory has no empty slot")
            if (sender is Player) sender.sendMessage(messages.render("book.system-issue.loading", BuilderLocalePolicy.localeTag(sender)))
            requester.run(player, null,
                latestOnly = false,
                request = { load(definition.buildingId, definition.schematicSha256) },
                failed = ::refused,
                ready = ready@{ building ->
                    val slot = try {
                        if (!canIssue(sender) || sender is Player && !sender.isOnline)
                            throw Refused("issuer-unavailable", "Issuer is no longer available")
                        if (building.volume > maxScanVolume)
                            throw Refused("too-large", "Schematic exceeds the configured scan volume")
                        val bookData = data(definition).copy(
                            selectableBuildingIds = options,
                            cooldownSeconds = if (requestedId.equals(RANDOM_SELECTOR, ignoreCase = true)) 0L else null,
                            blockCount = building.blockCount,
                        ).validated()
                        val slot = player.inventory.firstEmpty()
                        if (slot < 0) throw Refused("inventory-full", "Player inventory has no empty slot")
                        player.inventory.setItem(slot, BuildBookItems.create(bookData))
                        slot
                    } catch (failure: Throwable) {
                        refused(failure)
                        return@ready
                    }
                    if (sender is Player) sender.sendMessage(messages.render(
                        "book.system-issue.success", BuilderLocalePolicy.localeTag(sender),
                        mapOf("player" to messages.literal(player.name), "name" to messages.literal(definition.title)),
                    )) else sender.sendMessage(
                        "SYSTEM_BOOK_ISSUED player=${player.name} schematic=${definition.buildingId} slot=$slot sha256=${definition.schematicSha256}",
                    )
                },
            )
        } catch (failure: Throwable) {
            refused(failure)
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
