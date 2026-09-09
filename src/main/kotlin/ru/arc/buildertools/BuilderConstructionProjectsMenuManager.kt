package ru.arc.buildertools

import dev.lone.itemsadder.api.CustomStack
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.autobuild.BuildBookItems
import ru.arc.config.ConfigManager
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.menu.MenuContract
import ru.arc.menu.MenuElementId
import ru.arc.menu.MenuId
import ru.arc.menu.MenuRegionId
import ru.arc.paper.menu.PaperMenuConfiguration
import ru.arc.paper.menu.PaperMenuConfigurationParser
import ru.arc.paper.menu.PaperMenuContent
import ru.arc.paper.menu.PaperMenuEntry
import ru.arc.paper.menu.PaperMenuItemsAdderResolver
import ru.arc.paper.menu.PaperMenuItemFactory
import ru.arc.paper.menu.PaperMenuRuntime
import ru.arc.text.LocalizedMiniMessage
import java.util.Locale
import java.util.UUID

/** Player-owned, live view of durable unfinished construction projects. */
internal class BuilderConstructionProjectsMenuManager(
    private val plugin: JavaPlugin,
    private val messages: LocalizedMiniMessage,
    private val taskScope: LifecycleTaskScope,
    private val projects: (UUID) -> List<BuilderConstructionProjectRecord>,
    private val onTeleport: (Player, BuilderConstructionProjectRecord) -> Unit,
    private val onInspect: (Player, BuilderConstructionProjectRecord) -> Unit,
) : Listener, AutoCloseable {
    private val configuration = loadConfiguration()
    private val menus = PaperMenuRuntime(plugin, BukkitTaskScheduler(plugin), configuration)
    private val items = PaperMenuItemFactory(
        externalItems = PaperMenuItemsAdderResolver(
            isAvailable = { Bukkit.getPluginManager().isPluginEnabled("ItemsAdder") },
            lookup = { id -> CustomStack.getInstance(id)?.itemStack },
        ),
    )
    private val viewers = mutableSetOf<UUID>()
    private var closed = false

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
        checkNotNull(taskScope.runTimer(10L, 10L, ::refreshViewers)) {
            "Builder projects menu refresh task scope is inactive"
        }
    }

    fun open(player: Player) {
        if (closed) return
        menus.open(player, MENU_ID) { content(player) }
        viewers += player.uniqueId
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        viewers -= event.player.uniqueId
    }

    private fun content(player: Player): PaperMenuContent {
        val locale = locale(player)
        val records = projects(player.uniqueId)
            .asSequence()
            .filter { it.playerId == player.uniqueId && !it.terminal }
            .sortedByDescending(BuilderConstructionProjectRecord::updatedAtMillis)
            .toList()
        val entries = if (records.isEmpty()) {
            listOf(
                entry(
                    "empty",
                    messages.render("construction.projects.menu.empty.name", locale),
                    messages.renderLines("construction.projects.menu.empty.lore", locale),
                    enabled = false,
                ),
            )
        } else {
            records.map { record -> projectEntry(player, record) }
        }
        return PaperMenuContent(
            title = messages.render("construction.projects.menu.title", locale),
            background = items.create(
                configuration.template(requireNotNull(configuration.catalog.require(MENU_ID).backgroundTemplate)),
                Component.empty(),
                emptyList(),
            ),
            elements = mapOf(
                PREVIOUS to entry(
                    "previous",
                    messages.render("construction.projects.menu.previous.name", locale),
                    messages.renderLines("construction.projects.menu.previous.lore", locale),
                ) { it.session.previousPage() },
                CLOSE to entry(
                    "close",
                    messages.render("construction.projects.menu.close.name", locale),
                    messages.renderLines("construction.projects.menu.close.lore", locale),
                ) { it.player.closeInventory() },
                NEXT to entry(
                    "next",
                    messages.render("construction.projects.menu.next.name", locale),
                    messages.renderLines("construction.projects.menu.next.lore", locale),
                ) { it.session.nextPage() },
            ),
            regions = mapOf(PROJECTS to entries),
        )
    }

    private fun projectEntry(player: Player, project: BuilderConstructionProjectRecord): PaperMenuEntry {
        val locale = locale(player)
        val position = project.steps.first().change.position
        val world = Bukkit.getWorld(position.worldId)
        val percent = project.cursor.toLong() * 100L / project.steps.size
        val values = mapOf(
            "name" to messages.literal(projectName(project)),
            "state" to messages.render(
                "construction.states.${BuilderConstructionPausePolicy.playerFacingState(project.state).name.lowercase(Locale.ROOT)}",
                locale,
            ),
            "count" to messages.literal(project.cursor),
            "total" to messages.literal(project.steps.size),
            "percent" to messages.literal(percent),
            "world" to (world?.let { messages.literal(it.name) }
                ?: messages.render("construction.projects.menu.world-unavailable", locale)),
            "x" to messages.literal(position.x),
            "y" to messages.literal(position.y),
            "z" to messages.literal(position.z),
            "reason" to stopReason(player, project),
            "teleport" to messages.render(
                if (world == null) {
                    "construction.projects.menu.card.teleport-unavailable"
                } else {
                    "construction.projects.menu.card.teleport"
                },
                locale,
            ),
        )
        return entry(
            "project",
            messages.render("construction.projects.menu.card.name", locale, values),
            messages.renderLines("construction.projects.menu.card.lore", locale, values),
            acceptedClicks = setOf(ClickType.LEFT, ClickType.RIGHT),
        ) { click ->
            val current = projects(player.uniqueId).firstOrNull {
                it.projectId == project.projectId && it.playerId == player.uniqueId && !it.terminal
            } ?: run {
                click.session.requestRefresh()
                return@entry
            }
            if (click.event.isRightClick) {
                onInspect(player, current)
            } else {
                player.closeInventory()
                onTeleport(player, current)
            }
        }
    }

    private fun stopReason(player: Player, project: BuilderConstructionProjectRecord): Component {
        val locale = locale(player)
        val path = when (project.state) {
            BuilderConstructionProjectState.WAITING_MATERIALS -> "waiting-materials"
            BuilderConstructionProjectState.WAITING_OUTPUT_SPACE -> "waiting-output"
            BuilderConstructionProjectState.PAUSED -> "paused"
            BuilderConstructionProjectState.RECOVERY_REQUIRED -> "recovery"
            else -> "working"
        }
        return messages.render("construction.projects.menu.reasons.$path", locale)
    }

    private fun entry(
        template: String,
        name: Component,
        lore: List<Component>,
        enabled: Boolean = true,
        acceptedClicks: Set<ClickType> = setOf(ClickType.LEFT, ClickType.RIGHT),
        click: (ru.arc.paper.menu.PaperMenuClickContext) -> Unit = {},
    ): PaperMenuEntry = PaperMenuEntry(
        item = items.create(configuration.templates.getValue(template), name, lore),
        enabled = enabled,
        acceptedClicks = acceptedClicks,
        onClick = click,
    )

    private fun projectName(project: BuilderConstructionProjectRecord): String =
        BuildBookItems.compactTitle(project.projectTitle ?: project.plan.bookBuildingId ?: "Строительный проект", 30)

    private fun refreshViewers() {
        if (closed) return
        viewers.toList().forEach { viewerId ->
            val player = Bukkit.getPlayer(viewerId)?.takeIf(Player::isOnline)
            val session = player?.let(menus::session)
            if (session == null || session.menuId != MENU_ID) {
                viewers -= viewerId
            } else {
                session.requestRefresh()
            }
        }
    }

    private fun locale(player: Player): String = player.locale().toLanguageTag()

    private fun loadConfiguration(): PaperMenuConfiguration = PaperMenuConfigurationParser.require(
        ConfigManager.ofModule(plugin.dataPath, "builder-tools.yml"),
        "construction.projects-menu.layouts",
        "construction.projects-menu.templates",
        mapOf(MENU_ID to CONTRACT),
    )

    override fun close() {
        if (closed) return
        closed = true
        HandlerList.unregisterAll(this)
        menus.close()
        viewers.clear()
    }

    private companion object {
        val MENU_ID = MenuId.of("construction-projects")
        val PREVIOUS = MenuElementId.of("previous")
        val CLOSE = MenuElementId.of("close")
        val NEXT = MenuElementId.of("next")
        val PROJECTS = MenuRegionId.of("projects")
        val CONTRACT = MenuContract(
            requiredElements = setOf(PREVIOUS, CLOSE, NEXT),
            requiredRegions = setOf(PROJECTS),
        )
    }
}
