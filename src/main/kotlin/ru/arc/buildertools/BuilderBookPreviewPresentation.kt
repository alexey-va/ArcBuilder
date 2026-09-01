package ru.arc.buildertools

import dev.lone.itemsadder.api.CustomStack
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.autobuild.BuildBookItems
import ru.arc.autobuild.BuildBookPreviewAdjustment
import ru.arc.autobuild.BuildBookPreviewBridge
import ru.arc.autobuild.BuildBookPreviewMove
import ru.arc.autobuild.ConstructionSite
import ru.arc.autobuild.ConstructionSiteSnapshot
import ru.arc.autobuild.gui.BuildBookEditorPresentation
import ru.arc.text.LocalizedMiniMessage
import ru.arc.util.Logging.warn
import java.time.Duration
import java.util.UUID

internal data class BuilderBookPreviewConfirmation(
    val plan: BuilderPlan,
    val title: String,
    val cooldownRemaining: Duration,
)

internal interface BuilderBookPreviewPresentationHost {
    fun adjust(player: Player, adjustment: BuildBookPreviewAdjustment): ConstructionSite?
    fun prepare(player: Player, site: ConstructionSite): BuilderBookPreviewConfirmation?
    fun currentConfirmation(player: Player): BuilderBookPreviewConfirmation?
    fun confirm(player: Player): Boolean
    fun restore(player: Player, snapshot: ConstructionSiteSnapshot): ConstructionSite?
    fun cancel(player: Player)
}

/** Owns the owner-only preview plaque and the two-stage placement/confirmation inventory. */
internal class BuilderBookPreviewPresentation(
    private val plugin: JavaPlugin,
    private val renderer: BuilderDisplayRenderer,
    private val messages: LocalizedMiniMessage,
    private val host: BuilderBookPreviewPresentationHost,
    private val panelHeightOffset: Double,
    private val panelFrontOffset: Double,
    private val panelInteractionWidth: Float,
    private val panelInteractionHeight: Float,
    private val panelLineWidth: Int,
    private val panelBackgroundColor: Color,
    private val panelGlowColor: Color,
    private val viewRange: Double,
    private val backgroundItem: String,
    private val backgroundFallback: Material,
) : BuildBookPreviewBridge, Listener, AutoCloseable {
    private enum class Stage { PLACEMENT, CONFIRMATION }

    private class MenuHolder(
        val owner: BuilderBookPreviewPresentation,
        val playerId: UUID,
        val stage: Stage,
    ) : InventoryHolder {
        lateinit var backing: Inventory
        override fun getInventory(): Inventory = backing
    }

    private data class PanelScene(
        val site: ConstructionSite,
        val entities: List<Entity>,
        val interactionId: UUID,
    )

    private val panels = mutableMapOf<UUID, PanelScene>()
    private val interactionOwners = mutableMapOf<UUID, UUID>()
    private val snapshots = mutableMapOf<UUID, ConstructionSiteSnapshot>()
    private val placementSites = mutableMapOf<UUID, ConstructionSite>()
    private var closed = false

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    override fun open(site: ConstructionSite) {
        if (closed) return
        renderer.open(site)
        replacePanelSafely(site)
    }

    override fun refresh(site: ConstructionSite) {
        if (closed) return
        renderer.refresh(site)
        replacePanelSafely(site)
        val holder = currentMenu(site.player)
        if (holder?.owner === this && holder.stage == Stage.PLACEMENT) renderPlacement(site.player, holder.backing, site)
    }

    override fun close(playerId: UUID) {
        renderer.close(playerId)
        removePanel(playerId)
        placementSites.remove(playerId)
        val player = Bukkit.getPlayer(playerId)
        val holder = player?.let(::currentMenu)
        if (holder?.owner === this && holder.stage == Stage.PLACEMENT) player.closeInventory()
    }

    fun clearPlayer(playerId: UUID) {
        close(playerId)
        snapshots.remove(playerId)
        placementSites.remove(playerId)
        val player = Bukkit.getPlayer(playerId)
        val holder = player?.let(::currentMenu)
        if (holder?.owner === this) player.closeInventory()
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPanelClick(event: PlayerInteractEntityEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val ownerId = interactionOwners[event.rightClicked.uniqueId] ?: return
        event.isCancelled = true
        if (event.player.uniqueId != ownerId) return
        val site = panels[ownerId]?.site ?: return
        openPlacement(event.player, site)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onMenuClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? MenuHolder ?: return
        if (holder.owner !== this) return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        if (player.uniqueId != holder.playerId || event.rawSlot !in 0 until event.view.topInventory.size) return
        when (holder.stage) {
            Stage.PLACEMENT -> handlePlacementClick(player, event.rawSlot)
            Stage.CONFIRMATION -> handleConfirmationClick(player, event.rawSlot)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onMenuDrag(event: InventoryDragEvent) {
        val holder = event.view.topInventory.holder as? MenuHolder ?: return
        if (holder.owner === this) event.isCancelled = true
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        clearPlayer(event.player.uniqueId)
    }

    private fun handlePlacementClick(player: Player, slot: Int) {
        val adjustment = when (slot) {
            SLOT_ROTATE_LEFT -> BuildBookPreviewAdjustment.Rotate(-90)
            SLOT_LEFT -> BuildBookPreviewAdjustment.Move(BuildBookPreviewMove.LEFT)
            SLOT_UP -> BuildBookPreviewAdjustment.Move(BuildBookPreviewMove.UP)
            SLOT_RESET -> BuildBookPreviewAdjustment.Reset
            SLOT_DOWN -> BuildBookPreviewAdjustment.Move(BuildBookPreviewMove.DOWN)
            SLOT_RIGHT -> BuildBookPreviewAdjustment.Move(BuildBookPreviewMove.RIGHT)
            SLOT_ROTATE_RIGHT -> BuildBookPreviewAdjustment.Rotate(90)
            else -> null
        }
        if (adjustment != null) {
            if (host.adjust(player, adjustment) == null) player.closeInventory()
            else player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.65f, 1.15f)
            return
        }
        when (slot) {
            SLOT_CANCEL -> {
                snapshots.remove(player.uniqueId)
                host.cancel(player)
                player.closeInventory()
            }
            SLOT_CONTINUE -> {
                val site = placementSites[player.uniqueId] ?: panels[player.uniqueId]?.site ?: return player.closeInventory()
                snapshots[player.uniqueId] = site.snapshot()
                val confirmation = host.prepare(player, site)
                if (confirmation == null) {
                    snapshots.remove(player.uniqueId)
                    player.closeInventory()
                } else {
                    openConfirmation(player, confirmation)
                }
            }
        }
    }

    private fun handleConfirmationClick(player: Player, slot: Int) {
        when (slot) {
            SLOT_CANCEL_CONFIRMATION -> {
                snapshots.remove(player.uniqueId)
                host.cancel(player)
                player.closeInventory()
            }
            SLOT_BACK -> {
                val snapshot = snapshots.remove(player.uniqueId) ?: return player.closeInventory()
                val site = host.restore(player, snapshot) ?: return player.closeInventory()
                openPlacement(player, site)
            }
            SLOT_START -> {
                val confirmation = host.currentConfirmation(player) ?: return player.closeInventory()
                if (!confirmation.cooldownRemaining.isZero) {
                    renderConfirmation(player, player.openInventory.topInventory, confirmation)
                    player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.7f)
                    return
                }
                if (host.confirm(player)) {
                    snapshots.remove(player.uniqueId)
                    player.closeInventory()
                } else {
                    host.currentConfirmation(player)?.let {
                        renderConfirmation(player, player.openInventory.topInventory, it)
                    }
                }
            }
        }
    }

    private fun openPlacement(player: Player, site: ConstructionSite) {
        placementSites[player.uniqueId] = site
        val holder = MenuHolder(this, player.uniqueId, Stage.PLACEMENT)
        val inventory = Bukkit.createInventory(
            holder,
            MENU_SIZE,
            messages.render(
                "book.preview-menu.placement.title",
                locale(player),
                mapOf("name" to messages.literal(BuildBookItems.compactTitle(site.bookData.title, 20))),
            ),
        )
        holder.backing = inventory
        renderPlacement(player, inventory, site)
        player.openInventory(inventory)
    }

    internal fun openPlacementForTest(player: Player, site: ConstructionSite) = openPlacement(player, site)

    private fun openConfirmation(player: Player, confirmation: BuilderBookPreviewConfirmation) {
        val holder = MenuHolder(this, player.uniqueId, Stage.CONFIRMATION)
        val inventory = Bukkit.createInventory(
            holder,
            MENU_SIZE,
            messages.render(
                "book.preview-menu.confirmation.title",
                locale(player),
                mapOf("name" to messages.literal(BuildBookItems.compactTitle(confirmation.title, 20))),
            ),
        )
        holder.backing = inventory
        renderConfirmation(player, inventory, confirmation)
        player.openInventory(inventory)
    }

    private fun renderPlacement(player: Player, inventory: Inventory, site: ConstructionSite) {
        fillBackground(inventory)
        val anchor = site.centerBlock.block
        val values = mapOf(
            "x" to messages.literal(anchor.x),
            "y" to messages.literal(anchor.y),
            "z" to messages.literal(anchor.z),
            "rotation" to messages.literal(site.rotation),
        )
        val locale = locale(player)
        setAction(inventory, SLOT_ROTATE_LEFT, Material.COMPASS, "rotate-left", values, locale)
        setAction(inventory, SLOT_LEFT, Material.ARROW, "left", values, locale)
        setAction(inventory, SLOT_UP, Material.LIME_DYE, "up", values, locale)
        setAction(inventory, SLOT_RESET, Material.RECOVERY_COMPASS, "reset", values, locale)
        setAction(inventory, SLOT_DOWN, Material.GRAY_DYE, "down", values, locale)
        setAction(inventory, SLOT_RIGHT, Material.ARROW, "right", values, locale)
        setAction(inventory, SLOT_ROTATE_RIGHT, Material.COMPASS, "rotate-right", values, locale)
        setAction(inventory, SLOT_CANCEL, Material.BARRIER, "cancel", values, locale)
        setAction(inventory, SLOT_CONTINUE, Material.LIME_CONCRETE, "continue", values, locale)
    }

    private fun renderConfirmation(
        player: Player,
        inventory: Inventory,
        confirmation: BuilderBookPreviewConfirmation,
    ) {
        fillBackground(inventory)
        val remaining = confirmation.cooldownRemaining
        val totalMinutes = (remaining.seconds + 59L) / 60L
        val values = mapOf(
            "count" to messages.literal(confirmation.plan.changes.size),
            "items" to messages.literal(confirmation.plan.costs.sumOf { it.amount.toLong() }),
            "types" to messages.literal(confirmation.plan.costs.size),
            "hours" to messages.literal(totalMinutes / 60L),
            "minutes" to messages.literal(totalMinutes % 60L),
        )
        val locale = locale(player)
        setConfirmationItem(inventory, SLOT_CONFIRM_OVERVIEW, Material.BOOK, "overview", values, locale)
        setConfirmationItem(inventory, SLOT_CONFIRM_MATERIALS, Material.CHEST, "materials", values, locale)
        setConfirmationItem(
            inventory,
            SLOT_CONFIRM_COOLDOWN,
            if (remaining.isZero) Material.CLOCK else Material.REDSTONE_TORCH,
            if (remaining.isZero) "ready" else "cooldown",
            values,
            locale,
        )
        setConfirmationItem(inventory, SLOT_BACK, Material.ARROW, "back", values, locale)
        setConfirmationItem(
            inventory,
            SLOT_START,
            if (remaining.isZero) Material.LIME_CONCRETE else Material.BARRIER,
            if (remaining.isZero) "start" else "blocked",
            values,
            locale,
        )
        setConfirmationItem(inventory, SLOT_CANCEL_CONFIRMATION, Material.BARRIER, "cancel", values, locale)
    }

    private fun setAction(
        inventory: Inventory,
        slot: Int,
        material: Material,
        key: String,
        values: Map<String, Component>,
        locale: String,
    ) {
        inventory.setItem(
            slot,
            item(
                material,
                messages.render("book.preview-menu.placement.$key.name", locale, values),
                messages.renderLines("book.preview-menu.placement.$key.lore", locale, values),
            ),
        )
    }

    private fun setConfirmationItem(
        inventory: Inventory,
        slot: Int,
        material: Material,
        key: String,
        values: Map<String, Component>,
        locale: String,
    ) {
        inventory.setItem(
            slot,
            item(
                material,
                messages.render("book.preview-menu.confirmation.$key.name", locale, values),
                messages.renderLines("book.preview-menu.confirmation.$key.lore", locale, values),
            ),
        )
    }

    private fun replacePanel(site: ConstructionSite) {
        removePanel(site.player.uniqueId)
        if (!site.player.isOnline || site.player.world.uid != site.world.uid) return
        val positions = site.relativePositionsBottomUp().map { relative ->
            val block = site.worldLocation(relative).block
            BuilderBlockPos(site.world.uid, block.x, block.y, block.z)
        }.toList()
        if (positions.isEmpty()) return
        val face = BuilderConstructionSiteDisplayLayout.nearestFace(
            positions,
            site.player.location.x,
            site.player.location.z,
        )
        val panel = BuilderConstructionSiteDisplayLayout.panelPlacement(
            positions,
            face,
            panelHeightOffset,
            panelFrontOffset,
        )
        val location = Location(site.world, panel.x, panel.y, panel.z)
        val entities = mutableListOf<Entity>()
        try {
            val display = site.world.spawn(location, TextDisplay::class.java) { entity ->
                configure(entity, site.player)
                entity.text(
                    messages.render(
                        "book.preview-panel",
                        locale(site.player),
                        mapOf("name" to messages.literal(BuildBookItems.compactTitle(site.bookData.title, 24))),
                    ),
                )
                BuilderConstructionSitePanelOrientation.apply(entity, panel.yaw)
                entity.lineWidth = panelLineWidth
                entity.backgroundColor = panelBackgroundColor
                entity.isShadowed = true
                entity.isSeeThrough = false
                entity.alignment = TextDisplay.TextAlignment.CENTER
                entity.displayWidth = panelInteractionWidth
                entity.displayHeight = panelInteractionHeight
                entity.isGlowing = true
                entity.glowColorOverride = panelGlowColor
                entity.brightness = Display.Brightness(15, 15)
                entity.viewRange = (viewRange / 64.0).toFloat()
            }
            entities += display
            val interaction = site.world.spawn(
                location.clone().subtract(0.0, panelInteractionHeight / 2.0, 0.0),
                Interaction::class.java,
            ) { entity ->
                configure(entity, site.player)
                entity.interactionWidth = panelInteractionWidth
                entity.interactionHeight = panelInteractionHeight
                entity.isResponsive = true
            }
            entities += interaction
            interactionOwners[interaction.uniqueId] = site.player.uniqueId
            panels[site.player.uniqueId] = PanelScene(site, entities.toList(), interaction.uniqueId)
        } catch (failure: Throwable) {
            entities.forEach(Entity::remove)
            throw failure
        }
    }

    private fun replacePanelSafely(site: ConstructionSite) {
        runCatching { replacePanel(site) }.onFailure { failure ->
            warn("Builder build-book preview panel failed for {}: {}", site.player.name, failure.message)
        }
    }

    private fun configure(entity: Entity, player: Player) {
        entity.setVisibleByDefault(false)
        entity.isPersistent = false
        entity.isInvulnerable = true
        entity.setGravity(false)
        player.showEntity(plugin, entity)
    }

    private fun removePanel(playerId: UUID) {
        val scene = panels.remove(playerId) ?: return
        interactionOwners.remove(scene.interactionId)
        scene.entities.forEach(Entity::remove)
    }

    private fun fillBackground(inventory: Inventory) {
        val background = if (Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) {
            runCatching { CustomStack.getInstance(backgroundItem)?.itemStack?.clone() }.getOrNull()
        } else {
            null
        } ?: ItemStack(backgroundFallback)
        BuildBookEditorPresentation.state(Component.empty(), emptyList()).applyTo(background)
        repeat(inventory.size) { slot -> inventory.setItem(slot, background.clone()) }
    }

    private fun item(material: Material, name: Component, lore: List<Component>): ItemStack =
        BuildBookEditorPresentation.item(material, name, lore)

    private fun locale(player: Player): String = player.locale().toLanguageTag()

    private fun currentMenu(player: Player): MenuHolder? = runCatching {
        player.openInventory.topInventory
    }.getOrNull()?.holder as? MenuHolder

    override fun close() {
        if (closed) return
        closed = true
        HandlerList.unregisterAll(this)
        panels.keys.toList().forEach(::removePanel)
        snapshots.clear()
        placementSites.clear()
        Bukkit.getOnlinePlayers().forEach { player ->
            val holder = currentMenu(player)
            if (holder?.owner === this) player.closeInventory()
        }
    }

    private companion object {
        const val MENU_SIZE = 27
        const val SLOT_ROTATE_LEFT = 9
        const val SLOT_LEFT = 10
        const val SLOT_UP = 11
        const val SLOT_RESET = 13
        const val SLOT_DOWN = 15
        const val SLOT_RIGHT = 16
        const val SLOT_ROTATE_RIGHT = 17
        const val SLOT_CANCEL = 20
        const val SLOT_CONTINUE = 24
        const val SLOT_CONFIRM_OVERVIEW = 10
        const val SLOT_CONFIRM_MATERIALS = 13
        const val SLOT_CONFIRM_COOLDOWN = 16
        const val SLOT_BACK = 20
        const val SLOT_START = 22
        const val SLOT_CANCEL_CONFIRMATION = 24
    }
}
