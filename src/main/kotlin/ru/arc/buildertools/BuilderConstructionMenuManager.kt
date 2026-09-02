package ru.arc.buildertools

import dev.lone.itemsadder.api.CustomStack
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.autobuild.BuildBookItems
import ru.arc.config.ConfigManager
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.menu.MenuContract
import ru.arc.menu.MenuElementId
import ru.arc.menu.MenuId
import ru.arc.paper.menu.PaperMenuConfiguration
import ru.arc.paper.menu.PaperMenuConfigurationParser
import ru.arc.paper.menu.PaperMenuContent
import ru.arc.paper.menu.PaperMenuEntry
import ru.arc.paper.menu.PaperMenuExternalItemResolver
import ru.arc.paper.menu.PaperMenuExternalItemResult
import ru.arc.paper.menu.PaperMenuItemFactory
import ru.arc.paper.menu.PaperMenuRuntime
import ru.arc.text.LocalizedMiniMessage
import java.util.Locale
import java.util.UUID

internal data class BuilderConstructionMenuSettings(
    val rows: Int,
    val refreshPeriodTicks: Long,
    val backgroundItem: String,
    val backgroundFallback: Material,
    val overviewSlot: Int,
    val progressSlot: Int,
    val resourcesSlot: Int,
    val controlSlot: Int,
    val overviewMaterial: Material,
    val progressMaterial: Material,
    val resourcesMaterial: Material,
    val pauseMaterial: Material,
    val resumeMaterial: Material,
    val unavailableMaterial: Material,
    val maxResourceLines: Int,
)

internal fun BuilderToolsConfig.constructionMenuSettings() = BuilderConstructionMenuSettings(
    rows = constructionSiteMenuRows,
    refreshPeriodTicks = constructionSiteMenuRefreshPeriodTicks,
    backgroundItem = constructionSiteMenuBackgroundItem,
    backgroundFallback = constructionSiteMenuBackgroundFallback,
    overviewSlot = constructionSiteMenuOverviewSlot,
    progressSlot = constructionSiteMenuProgressSlot,
    resourcesSlot = constructionSiteMenuResourcesSlot,
    controlSlot = constructionSiteMenuControlSlot,
    overviewMaterial = constructionSiteMenuOverviewMaterial,
    progressMaterial = constructionSiteMenuProgressMaterial,
    resourcesMaterial = constructionSiteMenuResourcesMaterial,
    pauseMaterial = constructionSiteMenuPauseMaterial,
    resumeMaterial = constructionSiteMenuResumeMaterial,
    unavailableMaterial = constructionSiteMenuUnavailableMaterial,
    maxResourceLines = constructionSiteMaxMaterialLines,
)

/** Owns live construction-menu sessions, semantic actions and periodic refresh. */
internal class BuilderConstructionMenuManager(
    private val plugin: JavaPlugin,
    private val settings: BuilderConstructionMenuSettings,
    private val messages: LocalizedMiniMessage,
    private val taskScope: LifecycleTaskScope,
    private val projectLookup: (UUID) -> BuilderConstructionProjectRecord?,
    private val canControl: (Player, BuilderConstructionProjectRecord) -> Boolean,
    private val requestPaused: (Player, UUID, Boolean) -> Boolean,
) : Listener, AutoCloseable {
    private val configuration = loadConfiguration()
    private val menus = PaperMenuRuntime(plugin, BukkitTaskScheduler(plugin), configuration)
    private val items = PaperMenuItemFactory(
        externalItems = PaperMenuExternalItemResolver { id ->
            if (id.namespace != "itemsadder" || !Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) {
                PaperMenuExternalItemResult.Missing
            } else {
                CustomStack.getInstance(id.key.replaceFirst('/', ':'))?.itemStack
                    ?.let(PaperMenuExternalItemResult::Resolved)
                    ?: PaperMenuExternalItemResult.Missing
            }
        },
    )
    private val viewers = mutableMapOf<UUID, UUID>()
    private var closed = false

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
        checkNotNull(taskScope.runTimer(settings.refreshPeriodTicks, settings.refreshPeriodTicks, ::refreshViewers)) {
            "Builder construction menu refresh task scope is inactive"
        }
    }

    fun open(player: Player, project: BuilderConstructionProjectRecord) {
        if (closed || project.terminal) return
        val current = projectLookup(project.projectId)?.takeUnless(BuilderConstructionProjectRecord::terminal) ?: return
        menus.open(player, MENU_ID) { content(player, current.projectId) }
        viewers[player.uniqueId] = current.projectId
    }

    fun refreshProject(projectId: UUID) {
        viewers.filterValues { it == projectId }.keys.toList().forEach(::refreshViewer)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        viewers.remove(event.player.uniqueId)
    }

    private fun content(player: Player, projectId: UUID): PaperMenuContent {
        val project = requireNotNull(projectLookup(projectId)?.takeUnless(BuilderConstructionProjectRecord::terminal)) {
            "Construction project '$projectId' is no longer available"
        }
        val values = values(player, project)
        val layout = configuration.catalog.require(MENU_ID)
        return PaperMenuContent(
            title = messages.render(
                "construction.site.menu.title",
                locale(player),
                mapOf("name" to projectName(project, locale(player))),
            ),
            background = items.create(
                configuration.template(requireNotNull(layout.backgroundTemplate)),
                Component.empty(),
                emptyList(),
            ),
            elements = mapOf(
                OVERVIEW to entry(
                    OVERVIEW,
                    "overview",
                    messages.render("construction.site.menu.overview.name", locale(player), values),
                    messages.renderLines("construction.site.menu.overview.lore", locale(player), values),
                    enabled = false,
                ),
                PROGRESS to entry(
                    PROGRESS,
                    "progress",
                    messages.render("construction.site.menu.progress.name", locale(player), values),
                    messages.renderLines("construction.site.menu.progress.lore", locale(player), values),
                    enabled = false,
                ),
                RESOURCES to entry(
                    RESOURCES,
                    "resources",
                    messages.render("construction.site.menu.resources.name", locale(player), values),
                    resourceLore(player, project, values),
                    enabled = false,
                ),
                CONTROL to controlEntry(player, project, values),
            ),
        )
    }

    private fun controlEntry(
        player: Player,
        project: BuilderConstructionProjectRecord,
        values: Map<String, Component>,
    ): PaperMenuEntry {
        val (path, template) = when {
            !canControl(player, project) -> "readonly" to "unavailable"
            project.state == BuilderConstructionProjectState.PAUSED -> "resume" to "resume"
            BuilderConstructionPausePolicy.canRequestPause(project.state) -> "pause" to "pause"
            else -> "unavailable" to "unavailable"
        }
        return entry(
            CONTROL,
            template,
            messages.render("construction.site.menu.control.$path.name", locale(player), values),
            messages.renderLines("construction.site.menu.control.$path.lore", locale(player), values),
        ) { click -> toggle(click.player, project.projectId) }
    }

    private fun entry(
        element: MenuElementId,
        template: String,
        name: Component,
        lore: List<Component>,
        enabled: Boolean = true,
        click: (ru.arc.paper.menu.PaperMenuClickContext) -> Unit = {},
    ): PaperMenuEntry = PaperMenuEntry(
        item = items.create(configuration.templates.getValue(template), name, lore),
        enabled = enabled,
        onClick = click,
    )

    private fun toggle(player: Player, projectId: UUID) {
        val project = projectLookup(projectId)?.takeUnless(BuilderConstructionProjectRecord::terminal)
        if (project == null) {
            player.closeInventory()
            return
        }
        if (!canControl(player, project)) {
            refreshViewer(player.uniqueId)
            return
        }
        val pause = when {
            BuilderConstructionPausePolicy.canRequestPause(project.state) -> true
            project.state == BuilderConstructionProjectState.PAUSED -> false
            else -> return
        }
        if (requestPaused(player, project.projectId, pause)) {
            player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.7f, if (pause) 0.85f else 1.2f)
        }
        refreshViewer(player.uniqueId)
    }

    private fun refreshViewers() {
        if (!closed) viewers.keys.toList().forEach(::refreshViewer)
    }

    private fun refreshViewer(viewerId: UUID) {
        val player = Bukkit.getPlayer(viewerId)?.takeIf(Player::isOnline)
        val projectId = viewers[viewerId]
        if (player == null || projectId == null) {
            viewers.remove(viewerId)
            return
        }
        val session = menus.session(player)
        if (session == null || session.menuId != MENU_ID) {
            viewers.remove(viewerId)
            return
        }
        val project = projectLookup(projectId)?.takeUnless(BuilderConstructionProjectRecord::terminal)
        if (project == null) {
            session.close()
            viewers.remove(viewerId)
            return
        }
        session.refresh()
    }

    private fun resourceLore(
        player: Player,
        project: BuilderConstructionProjectRecord,
        values: Map<String, Component>,
    ): List<Component> {
        val lines = messages.renderLines("construction.site.menu.resources.lore", locale(player), values).toMutableList()
        val remaining = aggregateConstructionAmounts(
            project.steps.drop(project.cursor).mapNotNull(BuilderConstructionStep::requiredMaterial),
        )
        if (remaining.isEmpty()) {
            lines += messages.render("construction.site.menu.resources.none", locale(player))
        } else {
            remaining.take(settings.maxResourceLines).forEach { amount ->
                val material = Material.matchMaterial(amount.materialKey)
                    ?: BuilderItemCodec.decodePrototype(amount.itemBase64).type
                lines += messages.render(
                    "construction.site.menu.resources.line",
                    locale(player),
                    mapOf(
                        "material" to BuilderMaterialPresentation.label(player, material),
                        "amount" to messages.literal(amount.amount),
                    ),
                )
            }
            if (remaining.size > settings.maxResourceLines) {
                lines += messages.render(
                    "construction.site.menu.resources.more",
                    locale(player),
                    mapOf("count" to messages.literal(remaining.size - settings.maxResourceLines)),
                )
            }
        }
        if (project.state == BuilderConstructionProjectState.WAITING_MATERIALS) {
            val missing = checkNotNull(project.steps[project.cursor].requiredMaterial)
            val material = Material.matchMaterial(missing.materialKey)
                ?: BuilderItemCodec.decodePrototype(missing.itemBase64).type
            lines += Component.empty()
            lines += messages.render(
                "construction.site.menu.resources.missing",
                locale(player),
                mapOf(
                    "material" to BuilderMaterialPresentation.label(player, material),
                    "amount" to messages.literal(missing.amount),
                ),
            )
        }
        return lines
    }

    private fun values(player: Player, project: BuilderConstructionProjectRecord): Map<String, Component> {
        val first = project.steps.first().change.position
        val percent = project.cursor.toLong() * 100L / project.steps.size
        return mapOf(
            "name" to projectName(project, locale(player)),
            "owner" to messages.literal(project.playerName),
            "state" to messages.render(
                "construction.states.${BuilderConstructionPausePolicy.playerFacingState(project.state).name.lowercase(Locale.ROOT)}",
                locale(player),
            ),
            "count" to messages.literal(project.cursor),
            "total" to messages.literal(project.steps.size),
            "percent" to messages.literal(percent),
            "x" to messages.literal(first.x),
            "y" to messages.literal(first.y),
            "z" to messages.literal(first.z),
        )
    }

    private fun projectName(project: BuilderConstructionProjectRecord, locale: String): Component {
        val title = project.projectTitle ?: project.plan.bookBuildingId
        return title?.let { messages.literal(BuildBookItems.compactTitle(it, 28)) }
            ?: messages.render("construction.site.unknown-name", locale)
    }

    private fun aggregateConstructionAmounts(items: List<BuilderItemAmount>): List<BuilderItemAmount> = items
        .groupBy { it.itemBase64 to it.materialKey }
        .values
        .map { grouped -> grouped.first().copy(amount = grouped.sumOf(BuilderItemAmount::amount)).validated() }
        .sortedBy(BuilderItemAmount::materialKey)

    private fun locale(player: Player): String = player.locale().toLanguageTag()

    private fun loadConfiguration(): PaperMenuConfiguration = PaperMenuConfigurationParser.require(
        ConfigManager.ofModule(plugin.dataPath, "builder-tools.yml"),
        "construction.site.menu.layouts",
        "construction.site.menu.templates",
        mapOf(MENU_ID to CONTRACT),
        requiredTemplates = setOf("resume", "unavailable"),
    )

    override fun close() {
        if (closed) return
        closed = true
        HandlerList.unregisterAll(this)
        menus.close()
        viewers.clear()
    }

    private companion object {
        val MENU_ID = MenuId.of("construction-site")
        val OVERVIEW = MenuElementId.of("overview")
        val PROGRESS = MenuElementId.of("progress")
        val RESOURCES = MenuElementId.of("resources")
        val CONTROL = MenuElementId.of("control")
        val CONTRACT = MenuContract(requiredElements = setOf(OVERVIEW, PROGRESS, RESOURCES, CONTROL))
    }
}
