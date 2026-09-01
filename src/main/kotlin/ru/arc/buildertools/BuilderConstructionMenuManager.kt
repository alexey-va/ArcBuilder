package ru.arc.buildertools

import dev.lone.itemsadder.api.CustomStack
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.autobuild.BuildBookItems
import ru.arc.autobuild.gui.BuildBookEditorPresentation
import ru.arc.core.LifecycleTaskScope
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

/** Owns live construction-menu sessions, rendering, click isolation and periodic refresh. */
internal class BuilderConstructionMenuManager(
    private val plugin: JavaPlugin,
    private val settings: BuilderConstructionMenuSettings,
    private val messages: LocalizedMiniMessage,
    private val taskScope: LifecycleTaskScope,
    private val projectLookup: (UUID) -> BuilderConstructionProjectRecord?,
    private val canControl: (Player, BuilderConstructionProjectRecord) -> Boolean,
    private val requestPaused: (Player, UUID, Boolean) -> Boolean,
) : Listener, AutoCloseable {
    private class MenuHolder(
        val manager: BuilderConstructionMenuManager,
        val viewerId: UUID,
        val projectId: UUID,
    ) : InventoryHolder {
        lateinit var backingInventory: Inventory
        override fun getInventory(): Inventory = backingInventory
    }

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
        val holder = MenuHolder(this, player.uniqueId, current.projectId)
        val inventory = Bukkit.createInventory(
            holder,
            settings.rows * 9,
            messages.render(
                "construction.site.menu.title",
                locale(player),
                mapOf("name" to projectName(current, locale(player))),
            ),
        )
        holder.backingInventory = inventory
        render(player, inventory, current)
        player.openInventory(inventory)
        // Opening a new inventory closes the previous one synchronously. Register the
        // new session afterwards so that its close event cannot remove this mapping.
        viewers[player.uniqueId] = current.projectId
    }

    fun refreshProject(projectId: UUID) {
        viewers.filterValues { it == projectId }.keys.toList().forEach(::refreshViewer)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? MenuHolder ?: return
        if (holder.manager !== this) return
        event.isCancelled = true
        if (event.whoClicked.uniqueId != holder.viewerId || event.rawSlot != settings.controlSlot) return
        val player = event.whoClicked as? Player ?: return
        val project = projectLookup(holder.projectId)?.takeUnless(BuilderConstructionProjectRecord::terminal)
        if (project == null) {
            player.closeInventory()
            return
        }
        if (!canControl(player, project)) {
            render(player, event.view.topInventory, project)
            return
        }
        val pause = when (project.state) {
            in PAUSE_CONTROL_STATES -> true
            BuilderConstructionProjectState.PAUSED -> false
            else -> return
        }
        if (requestPaused(player, project.projectId, pause)) {
            player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.7f, if (pause) 0.85f else 1.2f)
        }
        refreshViewer(holder.viewerId)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onDrag(event: InventoryDragEvent) {
        val holder = event.view.topInventory.holder as? MenuHolder ?: return
        if (holder.manager === this) event.isCancelled = true
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        val holder = event.inventory.holder as? MenuHolder ?: return
        if (holder.manager === this) viewers.remove(holder.viewerId, holder.projectId)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        viewers.remove(event.player.uniqueId)
    }

    private fun refreshViewers() {
        if (closed) return
        viewers.keys.toList().forEach(::refreshViewer)
    }

    private fun refreshViewer(viewerId: UUID) {
        val player = Bukkit.getPlayer(viewerId)?.takeIf(Player::isOnline)
        val projectId = viewers[viewerId]
        if (player == null || projectId == null) {
            viewers.remove(viewerId)
            return
        }
        val holder = player.openInventory.topInventory.holder as? MenuHolder
        if (holder == null || holder.manager !== this || holder.projectId != projectId) {
            viewers.remove(viewerId)
            return
        }
        val project = projectLookup(projectId)?.takeUnless(BuilderConstructionProjectRecord::terminal)
        if (project == null) {
            player.closeInventory()
            return
        }
        render(player, holder.backingInventory, project)
    }

    private fun render(
        player: Player,
        inventory: Inventory,
        project: BuilderConstructionProjectRecord,
    ) {
        val background = background()
        repeat(inventory.size) { slot -> inventory.setItem(slot, background.clone()) }
        val values = values(player, project)
        inventory.setItem(
            settings.overviewSlot,
            item(
                settings.overviewMaterial,
                messages.render("construction.site.menu.overview.name", locale(player), values),
                messages.renderLines("construction.site.menu.overview.lore", locale(player), values),
            ),
        )
        inventory.setItem(
            settings.progressSlot,
            item(
                settings.progressMaterial,
                messages.render("construction.site.menu.progress.name", locale(player), values),
                messages.renderLines("construction.site.menu.progress.lore", locale(player), values),
            ),
        )
        inventory.setItem(
            settings.resourcesSlot,
            item(
                settings.resourcesMaterial,
                messages.render("construction.site.menu.resources.name", locale(player), values),
                resourceLore(player, project, values),
            ),
        )
        inventory.setItem(settings.controlSlot, controlItem(player, project, values))
    }

    private fun controlItem(
        player: Player,
        project: BuilderConstructionProjectRecord,
        values: Map<String, Component>,
    ): ItemStack {
        val path: String
        val material: Material
        when {
            !canControl(player, project) -> {
                path = "readonly"
                material = settings.unavailableMaterial
            }
            project.state == BuilderConstructionProjectState.PAUSED -> {
                path = "resume"
                material = settings.resumeMaterial
            }
            project.state in PAUSE_CONTROL_STATES -> {
                path = "pause"
                material = settings.pauseMaterial
            }
            else -> {
                path = "unavailable"
                material = settings.unavailableMaterial
            }
        }
        return item(
            material,
            messages.render("construction.site.menu.control.$path.name", locale(player), values),
            messages.renderLines("construction.site.menu.control.$path.lore", locale(player), values),
        )
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
                "construction.states.${playerFacingState(project.state).name.lowercase(Locale.ROOT)}",
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

    private fun background(): ItemStack {
        val item = if (Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) {
            runCatching { CustomStack.getInstance(settings.backgroundItem)?.itemStack?.clone() }.getOrNull()
        } else {
            null
        } ?: ItemStack(settings.backgroundFallback)
        BuildBookEditorPresentation.state(Component.empty(), emptyList()).applyTo(item)
        return item
    }

    private fun item(material: Material, name: Component, lore: List<Component>): ItemStack =
        BuildBookEditorPresentation.item(material, name, lore)

    private fun aggregateConstructionAmounts(items: List<BuilderItemAmount>): List<BuilderItemAmount> = items
        .groupBy { it.itemBase64 to it.materialKey }
        .values
        .map { grouped -> grouped.first().copy(amount = grouped.sumOf(BuilderItemAmount::amount)).validated() }
        .sortedBy(BuilderItemAmount::materialKey)

    private fun locale(player: Player): String = player.locale().toLanguageTag()

    private fun playerFacingState(state: BuilderConstructionProjectState): BuilderConstructionProjectState =
        if (state in INTERNAL_STEP_STATES) BuilderConstructionProjectState.ACTIVE else state

    override fun close() {
        if (closed) return
        closed = true
        HandlerList.unregisterAll(this)
        viewers.keys.mapNotNull(Bukkit::getPlayer).forEach { player ->
            val holder = player.openInventory.topInventory.holder as? MenuHolder
            if (holder?.manager === this) player.closeInventory()
        }
        viewers.clear()
    }

    private companion object {
        val INTERNAL_STEP_STATES = setOf(
            BuilderConstructionProjectState.INPUT_PREPARED,
            BuilderConstructionProjectState.WORLD_PREPARED,
            BuilderConstructionProjectState.OUTPUT_PENDING,
            BuilderConstructionProjectState.DELIVERING_OUTPUT,
        )
        val PAUSE_CONTROL_STATES = INTERNAL_STEP_STATES + setOf(
            BuilderConstructionProjectState.ACTIVE,
            BuilderConstructionProjectState.WAITING_MATERIALS,
        )
    }
}
