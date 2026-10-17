package ru.arc.buildertools

import dev.lone.itemsadder.api.CustomStack
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.autobuild.BuildBookData
import ru.arc.autobuild.SystemBuildBookDefinition
import ru.arc.autobuild.BuildBookItems
import ru.arc.autobuild.BuildBookPreviewAdjustment
import ru.arc.autobuild.BuildBookPreviewBridge
import ru.arc.autobuild.BuildBookPreviewMove
import ru.arc.autobuild.ConstructionSite
import ru.arc.autobuild.ConstructionSiteSnapshot
import ru.arc.core.BukkitTaskScheduler
import ru.arc.menu.MenuContract
import ru.arc.menu.MenuElementId
import ru.arc.menu.MenuRegionId
import ru.arc.menu.MenuId
import ru.arc.paper.menu.PaperMenuConfiguration
import ru.arc.paper.menu.PaperMenuConfigurationParser
import ru.arc.paper.menu.PaperMenuClickContext
import ru.arc.paper.menu.PaperMenuContent
import ru.arc.paper.menu.PaperMenuEntry
import ru.arc.paper.menu.PaperMenuItemsAdderResolver
import ru.arc.paper.menu.PaperMenuItemFactory
import ru.arc.paper.menu.PaperMenuRuntime
import ru.arc.paper.display.PacketDisplayAudienceGroup
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.scene.PaperSpatialControls
import ru.arc.paper.scene.PaperSpatialSpace
import ru.arc.paper.scene.SceneAudience
import ru.arc.paper.scene.SpatialControlController
import ru.arc.paper.scene.SpatialControlFrame
import ru.arc.paper.scene.SpatialSceneKey
import ru.arc.text.LocalizedMiniMessage
import ru.arc.util.Logging.warn
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture

internal enum class BuilderBookPreviewConfirmationKind {
    CONSTRUCTION,
    DRAFT_ACTIVATION,
}

internal data class BuilderBookPreviewConfirmation(
    val kind: BuilderBookPreviewConfirmationKind,
    val blockCount: Int,
    val title: String,
    val cooldownRemaining: Duration,
    val requiredMaterials: List<BuilderItemAmount>,
    val materialCostMinor: Long = 0L,
    val constructionFeeMinor: Long = 0L,
    val issuePriceMinor: Long = 0L,
)

internal interface BuilderBookPreviewPresentationHost {
    fun adjust(player: Player, adjustment: BuildBookPreviewAdjustment): ConstructionSite?
    fun prepare(player: Player, site: ConstructionSite, complete: (BuilderBookPreviewConfirmation?) -> Unit)
    fun currentConfirmation(player: Player): BuilderBookPreviewConfirmation?
    fun confirm(player: Player, complete: (Boolean) -> Unit)
    fun complete(player: Player, kind: BuilderBookPreviewConfirmationKind)
    fun restore(player: Player, snapshot: ConstructionSiteSnapshot): ConstructionSite?
    fun cancel(player: Player)
}

/** Owns the owner-only preview hologram and its single placement/confirmation inventory. */
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
    private val displays: PaperPacketDisplays = PaperPacketDisplays(plugin, "book-preview-plaque"),
    private val space: PaperSpatialSpace = PaperSpatialSpace(),
) : BuildBookPreviewBridge, Listener, AutoCloseable {
    private data class PanelScene(
        var site: ConstructionSite,
        val key: SpatialSceneKey,
        val plaque: BuilderWorldPlaque,
        val audience: PacketDisplayAudienceGroup,
    )

    private var panels = mutableMapOf<UUID, PanelScene>()
    private var snapshots = mutableMapOf<UUID, ConstructionSiteSnapshot>()
    private var placementSites = mutableMapOf<UUID, ConstructionSite>()
    private var preparing = mutableMapOf<UUID, Any>()
    private var displayedConfirmations = mutableMapOf<UUID, BuilderBookPreviewConfirmation>()
    private var confirmationInventories = mutableMapOf<UUID, org.bukkit.inventory.Inventory>()
    private val menuConfiguration = loadMenuConfiguration()
    private val menus = PaperMenuRuntime(plugin, BukkitTaskScheduler(plugin), menuConfiguration)
    private val menuItems = PaperMenuItemFactory(
        externalItems = PaperMenuItemsAdderResolver(
            isAvailable = { Bukkit.getPluginManager().isPluginEnabled("ItemsAdder") },
            lookup = { id -> CustomStack.getInstance(id)?.itemStack },
        ),
    )
    private var closed = false
    private var menuViewers = mutableMapOf<UUID, Player>()
    private val terminal = CompletableFuture<Void>()
    private val controls = PaperSpatialControls(plugin, displays, object : SpatialControlController<ConstructionSite, Unit>() {
        override fun current(player: Player, scene: SpatialSceneKey): SpatialControlFrame<ConstructionSite, Unit>? {
            val panel = panels[UUID.fromString(scene.value)] ?: return null
            val site = panel.site
            if (closed || !site.player.isOnline || site.player.world.uid != site.world.uid ||
                BuilderBookPreviewAccess.level(site.player.uniqueId, player.uniqueId,
                    player.hasPermission(BUILDER_PREVIEW_ADMIN_PERMISSION)) == BuilderBookPreviewAccessLevel.NONE
            ) return null
            return panel.plaque.controls(site)
        }

        override fun activate(player: Player, scene: SpatialSceneKey, context: ConstructionSite, action: Unit) {
            val current = current(player, scene) ?: return
            if (current.context !== context) return
            when (BuilderBookPreviewAccess.level(context.player.uniqueId, player.uniqueId,
                player.hasPermission(BUILDER_PREVIEW_ADMIN_PERMISSION))) {
                BuilderBookPreviewAccessLevel.OWNER -> openPlacement(player, context)
                BuilderBookPreviewAccessLevel.INSPECT -> openInspection(player, context)
                BuilderBookPreviewAccessLevel.NONE -> Unit
            }
        }

        override fun inputFailed(player: Player, scene: SpatialSceneKey, context: ConstructionSite, failure: Exception) {
            val ownerId = UUID.fromString(scene.value)
            if (panels[ownerId]?.site === context) removePanel(ownerId)
        }
    })

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    fun openSelector(
        player: Player,
        data: BuildBookData,
        choices: List<SystemBuildBookDefinition>,
        onSelect: (String) -> Unit,
        onEdit: () -> Unit,
    ) {
        if (closed) return
        val locale = locale(player)
        fun control(template: String, action: (PaperMenuClickContext) -> Unit) = PaperMenuEntry(
            item = menuItems.create(
                menuConfiguration.templates.getValue(template),
                messages.render("book.selector.$template", locale),
                emptyList(),
            ),
            onClick = { if (!closed) action(it) },
        )
        menuViewers[player.uniqueId] = player
        menus.open(player, SELECTOR_MENU) {
            PaperMenuContent(
                title = messages.render("book.selector.title", locale),
                elements = mapOf(
                    PREVIOUS to control("selector-previous") { it.session.previousPage() },
                    NEXT to control("selector-next") { it.session.nextPage() },
                    CANCEL to control("selector-close") { it.player.closeInventory() },
                    EDIT to control("selector-edit") { onEdit() },
                ),
                regions = mapOf(CHOICES to choices.map { definition ->
                    val selected = data.buildingId == definition.buildingId
                    val values = mapOf(
                        "name" to messages.literal(definition.title),
                        "materials" to messages.render(
                            if (definition.materialsIncluded) "book.selector.materials-included" else "book.selector.materials-required", locale,
                        ),
                    )
                    PaperMenuEntry(
                        item = menuItems.create(
                            menuConfiguration.templates.getValue(if (selected) "selector-selected" else "selector-choice"),
                            messages.render(if (selected) "book.selector.selected-name" else "book.selector.name", locale, values),
                            messages.renderLines("book.selector.lore", locale, values),
                        ),
                        onClick = { if (!closed) onSelect(definition.buildingId) },
                    )
                }),
            )
        }
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
        menus.session(site.player)?.takeIf { it.menuId == PLACEMENT_MENU }?.requestRefresh()
    }

    override fun close(playerId: UUID) {
        renderer.close(playerId)
        removePanel(playerId)
        placementSites.remove(playerId)
        val player = Bukkit.getPlayer(playerId)
        if (playerId !in snapshots) {
            player?.let(menus::session)?.takeIf { it.menuId == PLACEMENT_MENU }?.close()
        }
    }

    /** Keeps the world entry point after placement is converted into a prepared plan. */
    fun retainConfirmation(site: ConstructionSite) {
        if (closed) return
        snapshots[site.player.uniqueId] = site.snapshot()
        placementSites[site.player.uniqueId] = site
        replacePanelSafely(site)
    }

    fun clearConfirmation(playerId: UUID) {
        preparing.remove(playerId)
        displayedConfirmations.remove(playerId)
        if (snapshots.remove(playerId) == null) return
        confirmationInventories.remove(playerId)
        placementSites.remove(playerId)
        removePanel(playerId)
        Bukkit.getPlayer(playerId)?.let(menus::session)?.close()
    }

    fun clearPlayer(playerId: UUID) {
        preparing.remove(playerId)
        displayedConfirmations.remove(playerId)
        close(playerId)
        snapshots.remove(playerId)
        confirmationInventories.remove(playerId)
        placementSites.remove(playerId)
        val player = Bukkit.getPlayer(playerId)
        player?.let(menus::session)?.close()
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        if (!event.player.hasPermission(BUILDER_PREVIEW_ADMIN_PERMISSION)) return
        renderer.syncBookViewer(event.player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        menuViewers.remove(event.player.uniqueId)
        clearPlayer(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    fun onMenuClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        val playerId = player.uniqueId
        val inventory = confirmationInventories[playerId] ?: return
        if (event.view.topInventory !== inventory) return
        preparing.remove(playerId)
        displayedConfirmations.remove(playerId)
        confirmationInventories.remove(playerId)
        val snapshot = snapshots.remove(playerId) ?: return
        if (closed || !player.isOnline) return
        host.restore(player, snapshot)?.let { placementSites[playerId] = it }
    }

    private fun adjust(player: Player, adjustment: BuildBookPreviewAdjustment) {
        val playerId = player.uniqueId
        if (playerId in preparing) return
        // Prepared construction no longer has a BuildingManager preview. Restore
        // it before editing, and detach the confirmation before discarding its plan.
        snapshots.remove(playerId)?.let { snapshot ->
            confirmationInventories.remove(playerId)
            displayedConfirmations.remove(playerId)
            val restored = host.restore(player, snapshot) ?: return player.closeInventory()
            placementSites[playerId] = restored
        }
        if (host.adjust(player, adjustment) == null) player.closeInventory()
        else player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.65f, 1.15f)
    }

    private fun cancel(player: Player) {
        preparing.remove(player.uniqueId)
        displayedConfirmations.remove(player.uniqueId)
        snapshots.remove(player.uniqueId)
        confirmationInventories.remove(player.uniqueId)
        host.cancel(player)
        player.closeInventory()
    }

    private fun confirmFromPlacement(player: Player) {
        val playerId = player.uniqueId
        val site = placementSites[playerId] ?: panels[playerId]?.site ?: return player.closeInventory()
        if (playerId in preparing) return
        if (site.transforming) return
        val preparation = Any()
        preparing[playerId] = preparation
        snapshots[playerId] = site.snapshot()
        menus.session(player)?.inventory?.let { confirmationInventories[playerId] = it }

        val current = host.currentConfirmation(player)
        if (current != null) {
            preparing.remove(playerId)
            if (current.kind == BuilderBookPreviewConfirmationKind.DRAFT_ACTIVATION &&
                displayedConfirmations[playerId] != current) {
                menus.session(player)?.requestRefresh()
                return
            }
            start(player)
            return
        }
        try {
            host.prepare(player, site) { confirmation ->
                if (preparing[playerId] !== preparation) return@prepare
                preparing.remove(playerId)
                if (closed || !player.isOnline) return@prepare
                if (playerId !in snapshots) return@prepare
                if (confirmation == null) {
                    snapshots.remove(playerId)
                    confirmationInventories.remove(playerId)
                    player.closeInventory()
                } else if (confirmation.kind == BuilderBookPreviewConfirmationKind.DRAFT_ACTIVATION) {
                    // The amount must be visible before the click authorizing payment.
                    menus.session(player)?.requestRefresh()
                } else {
                    start(player)
                }
            }
        } catch (failure: Throwable) {
            preparing.remove(playerId)
            snapshots.remove(playerId)
            confirmationInventories.remove(playerId)
            player.closeInventory()
            throw failure
        }
    }

    private fun start(player: Player) {
        val confirmation = host.currentConfirmation(player) ?: return player.closeInventory()
        if (!confirmation.cooldownRemaining.isZero) {
            menus.session(player)?.requestRefresh()
            player.sendMessage(messages.render("book.cooldown", locale(player), mapOf(
                "hours" to messages.literal(confirmation.cooldownRemaining.toHours()),
                "minutes" to messages.literal(confirmation.cooldownRemaining.toMinutes() % 60),
            )))
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.7f)
            return
        }
        val playerId = player.uniqueId
        if (playerId in preparing) return
        val request = Any()
        preparing[playerId] = request
        host.confirm(player) { accepted ->
            if (closed || preparing[playerId] !== request) return@confirm
            preparing.remove(playerId)
            if (accepted) {
                snapshots.remove(playerId)
                confirmationInventories.remove(playerId)
                host.complete(player, confirmation.kind)
                player.closeInventory()
            } else {
                menus.session(player)?.requestRefresh()
            }
        }
    }

    private fun openPlacement(player: Player, site: ConstructionSite) {
        if (closed) return
        if (menus.session(player)?.menuId == PLACEMENT_MENU) {
            menus.session(player)?.requestRefresh()
            return
        }
        confirmationInventories.remove(player.uniqueId)
        placementSites[player.uniqueId] = site
        if (player.uniqueId in snapshots && host.currentConfirmation(player) == null) {
            snapshots.remove(player.uniqueId)
        }
        menuViewers[player.uniqueId] = player
        menus.open(player, PLACEMENT_MENU) { menuContent(player) }
        if (player.uniqueId in snapshots && host.currentConfirmation(player) != null) {
            menus.session(player)?.inventory?.let { confirmationInventories[player.uniqueId] = it }
        }
    }

    internal fun openPlacementForOwner(player: Player, site: ConstructionSite) {
        require(site.player.uniqueId == player.uniqueId) {
            "Build-book placement menu can only be opened by the preview owner"
        }
        openPlacement(player, site)
    }

    fun openCurrentPlacement(player: Player, pending: ConstructionSite?): Boolean {
        val site = pending ?: placementSites[player.uniqueId]
            ?.takeIf { host.currentConfirmation(player) != null }
            ?: return false
        if (!site.transforming) openPlacementForOwner(player, site)
        return true
    }

    internal fun openPlacementForTest(player: Player, site: ConstructionSite) = openPlacementForOwner(player, site)

    internal fun openInspectionForTest(player: Player, site: ConstructionSite) = openInspection(player, site)

    private fun openInspection(player: Player, site: ConstructionSite) {
        if (closed) return
        menuViewers[player.uniqueId] = player
        menus.open(player, INSPECTION_MENU) { inspectionContent(player, site) }
    }

    private fun menuContent(player: Player): PaperMenuContent = placementContent(player)

    private fun inspectionContent(viewer: Player, site: ConstructionSite): PaperMenuContent {
        val anchor = site.centerBlock.block
        val locale = locale(viewer)
        val remainingMinutes = ((site.expiresAtMillis - System.currentTimeMillis()).coerceAtLeast(0L) + 59_999L) / 60_000L
        val values = mapOf(
            "name" to messages.literal(BuildBookItems.compactTitle(site.bookData.title, 24)),
            "owner" to messages.literal(site.player.name),
            "state" to messages.render(if (site.bookData.draft) "book.state.draft" else "book.state.active", locale),
            "x" to messages.literal(anchor.x),
            "y" to messages.literal(anchor.y),
            "z" to messages.literal(anchor.z),
            "rotation" to messages.literal(site.rotation),
            "mirror" to messages.render(
                if (site.mirrored) "book.preview-menu.inspection.mirror.yes" else "book.preview-menu.inspection.mirror.no",
                locale,
            ),
            "minutes" to messages.literal(remainingMinutes),
        )
        return PaperMenuContent(
            title = messages.render("book.preview-menu.inspection.title", locale),
            background = background(INSPECTION_MENU),
            elements = mapOf(
                INSPECTION_OVERVIEW to PaperMenuEntry(
                    menuItems.create(
                        menuConfiguration.template(INSPECTION_MENU, INSPECTION_OVERVIEW),
                        messages.render("book.preview-menu.inspection.overview.name", locale, values),
                        messages.renderLines("book.preview-menu.inspection.overview.lore", locale, values),
                    ),
                    enabled = false,
                ),
            ),
        )
    }

    private fun placementContent(player: Player): PaperMenuContent {
        val site = requireNotNull(placementSites[player.uniqueId] ?: panels[player.uniqueId]?.site) {
            "Build-book placement preview is no longer active"
        }
        val anchor = site.centerBlock.block
        val values = mapOf(
            "x" to messages.literal(anchor.x),
            "y" to messages.literal(anchor.y),
            "z" to messages.literal(anchor.z),
            "rotation" to messages.literal(site.rotation),
        )
        val locale = locale(player)
        val confirmation = host.currentConfirmation(player)
        if (confirmation == null) displayedConfirmations.remove(player.uniqueId)
        else displayedConfirmations[player.uniqueId] = confirmation
        val confirmKey = when {
            !site.bookData.draft -> "continue"
            confirmation == null -> "quote"
            else -> "activate"
        }
        val confirmValues = values + mapOf(
            "price" to messages.literal(String.format(java.util.Locale.US, "%,.2f", BuilderMoney.decimal(confirmation?.issuePriceMinor ?: 0L))),
        )
        return PaperMenuContent(
            title = messages.render(
                "book.preview-menu.placement.title",
                locale,
                mapOf("name" to messages.literal(BuildBookItems.compactTitle(site.bookData.title, 20))),
            ),
            background = background(PLACEMENT_MENU),
            elements = mapOf(
                LEFT to action(PLACEMENT_MENU, LEFT, "left", values, locale) {
                    adjust(it.player, BuildBookPreviewAdjustment.Move(BuildBookPreviewMove.LEFT))
                },
                UP to action(PLACEMENT_MENU, UP, "up", values, locale) {
                    adjust(it.player, BuildBookPreviewAdjustment.Move(BuildBookPreviewMove.UP))
                },
                ROTATE to action(PLACEMENT_MENU, ROTATE, "rotate", values, locale) {
                    adjust(it.player, BuildBookPreviewAdjustment.Rotate(90))
                },
                MIRROR to action(PLACEMENT_MENU, MIRROR, "mirror", values, locale) {
                    adjust(it.player, BuildBookPreviewAdjustment.ToggleMirror)
                },
                RESET to action(PLACEMENT_MENU, RESET, "reset", values, locale) {
                    adjust(it.player, BuildBookPreviewAdjustment.Reset)
                },
                DOWN to action(PLACEMENT_MENU, DOWN, "down", values, locale) {
                    adjust(it.player, BuildBookPreviewAdjustment.Move(BuildBookPreviewMove.DOWN))
                },
                RIGHT to action(PLACEMENT_MENU, RIGHT, "right", values, locale) {
                    adjust(it.player, BuildBookPreviewAdjustment.Move(BuildBookPreviewMove.RIGHT))
                },
                AWAY to action(PLACEMENT_MENU, AWAY, "away", values, locale) {
                    adjust(it.player, BuildBookPreviewAdjustment.Move(BuildBookPreviewMove.AWAY))
                },
                TOWARD to action(PLACEMENT_MENU, TOWARD, "toward", values, locale) {
                    adjust(it.player, BuildBookPreviewAdjustment.Move(BuildBookPreviewMove.TOWARD))
                },
                CANCEL to action(PLACEMENT_MENU, CANCEL, "cancel", values, locale) { cancel(it.player) },
                CONTINUE to action(PLACEMENT_MENU, CONTINUE, confirmKey, confirmValues, locale) {
                    confirmFromPlacement(it.player)
                },
            ),
        )
    }

    private fun action(
        menu: MenuId,
        element: MenuElementId,
        key: String,
        values: Map<String, Component>,
        locale: String,
        click: (PaperMenuClickContext) -> Unit,
    ): PaperMenuEntry = PaperMenuEntry(
        menuItems.create(
            menuConfiguration.template(menu, element),
            messages.render("book.preview-menu.placement.$key.name", locale, values),
            messages.renderLines("book.preview-menu.placement.$key.lore", locale, values),
        ),
        onClick = { if (!closed) click(it) },
    )

    private fun background(menu: MenuId) = menuItems.create(
        menuConfiguration.template(requireNotNull(menuConfiguration.catalog.require(menu).backgroundTemplate)),
        Component.empty(),
        emptyList(),
    )

    private fun replacePanel(site: ConstructionSite) {
        val ownerId = site.player.uniqueId
        if (!site.player.isOnline || site.player.world.uid != site.world.uid) {
            removePanel(ownerId)
            return
        }
        val positions = listOf(site.adjustedCenter.let { anchor ->
            BuilderBlockPos(site.world.uid, anchor.blockX, anchor.blockY, anchor.blockZ)
        })
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
        val existing = panels[ownerId] ?: run {
            val audience = displays.createAudienceGroup { viewer ->
                viewer.uniqueId == ownerId || viewer.hasPermission(BUILDER_PREVIEW_ADMIN_PERMISSION)
            }
            val plaque = BuilderWorldPlaque(displays, space, BuilderWorldPlaqueStyle(
                panelInteractionWidth, panelInteractionHeight, panelLineWidth, panelBackgroundColor, panelGlowColor, viewRange,
            ), SceneAudience.Public(audience))
            PanelScene(site, SpatialSceneKey(ownerId.toString()), plaque, audience).also { created ->
                panels[ownerId] = created
                plaque.refreshWith {
                    if (panels[ownerId] === created && !closed) {
                        runCatching {
                            val owner = created.site.player
                            plaque.refresh(owner.isOnline && owner.world.uid == created.site.world.uid)
                            controls.apply(created.key, plaque.controls(created.site))
                        }.onFailure { failure ->
                            removePanel(ownerId)
                            warn("Builder build-book plaque refresh failed for {}: {}", ownerId, failure.message)
                        }
                    }
                }
            }
        }
        existing.site = site
        existing.plaque.update(Location(site.world, panel.x, panel.y, panel.z, panel.yaw, 0f), messages.render(
            "book.preview-panel",
            locale(site.player),
            mapOf("name" to messages.literal(BuildBookItems.compactTitle(site.bookData.title, 24))),
        ))
        controls.apply(existing.key, existing.plaque.controls(site))
    }

    private fun replacePanelSafely(site: ConstructionSite) {
        runCatching { replacePanel(site) }.onFailure { failure ->
            removePanel(site.player.uniqueId)
            warn("Builder build-book preview panel failed for {}: {}", site.player.name, failure.message)
        }
    }

    private fun removePanel(playerId: UUID) {
        val scene = panels.remove(playerId) ?: return
        try {
            controls.clear(scene.key)
        } finally {
            try { scene.plaque.close() } finally { scene.audience.close() }
        }
    }

    private fun locale(player: Player): String = BuilderLocalePolicy.localeTag(player)

    private fun loadMenuConfiguration(): PaperMenuConfiguration = PaperMenuConfigurationParser.require(
        BuilderToolsConfiguration.builder(plugin.dataPath),
        "paper-menus.preview.layouts",
        "paper-menus.preview.templates",
        mapOf(
            PLACEMENT_MENU to PLACEMENT_CONTRACT,
            INSPECTION_MENU to INSPECTION_CONTRACT,
            SELECTOR_MENU to SELECTOR_CONTRACT,
        ),
        requiredTemplates = emptySet(),
    )

    override fun close() { closeAsync() }

    /** Local native bindings close inline; only Core's detached packet cleanup may finish later. */
    fun closeAsync(): CompletableFuture<Void> {
        if (closed) return terminal.copy()
        closed = true
        HandlerList.unregisterAll(this)
        var failure: Throwable? = null
        fun attempt(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        attempt { controls.close() }
        panels.values.forEach { scene ->
            attempt { scene.plaque.close() }
            attempt { scene.audience.close() }
        }
        menuViewers.values.toList().forEach { player -> attempt { menus.session(player)?.close() } }
        attempt { menus.close() }
        panels.clear()
        menuViewers.clear()
        snapshots.clear()
        confirmationInventories.clear()
        placementSites.clear()
        preparing.clear()
        displayedConfirmations.clear()
        val nativeFailure = failure
        val packetDrain = runCatching { displays.closeAsync() }.getOrElse { CompletableFuture.failedFuture(it) }
        packetDrain.whenComplete { _, packetFailure ->
            val primary = nativeFailure ?: packetFailure
            if (primary == null) terminal.complete(null) else {
                if (nativeFailure != null && packetFailure != null && nativeFailure !== packetFailure) nativeFailure.addSuppressed(packetFailure)
                terminal.completeExceptionally(primary)
            }
        }
        return terminal.copy()
    }

    private companion object {
        val SELECTOR_MENU = MenuId.of("book-selector")
        val CHOICES = MenuRegionId.of("choices")
        val PREVIOUS = MenuElementId.of("previous")
        val NEXT = MenuElementId.of("next")
        val EDIT = MenuElementId.of("edit")
        val PLACEMENT_MENU = MenuId.of("book-preview-placement")
        val INSPECTION_MENU = MenuId.of("book-preview-inspection")
        val UP = MenuElementId.of("up")
        val LEFT = MenuElementId.of("left")
        val ROTATE = MenuElementId.of("rotate")
        val RIGHT = MenuElementId.of("right")
        val AWAY = MenuElementId.of("away")
        val TOWARD = MenuElementId.of("toward")
        val CONTINUE = MenuElementId.of("continue")
        val DOWN = MenuElementId.of("down")
        val CANCEL = MenuElementId.of("cancel")
        val MIRROR = MenuElementId.of("mirror")
        val RESET = MenuElementId.of("reset")
        val INSPECTION_OVERVIEW = MenuElementId.of("inspection-overview")
        val PLACEMENT_CONTRACT = MenuContract(
            requiredElements = setOf(UP, LEFT, ROTATE, RIGHT, AWAY, TOWARD, CONTINUE, DOWN, CANCEL, MIRROR, RESET),
        )
        val SELECTOR_CONTRACT = MenuContract(
            requiredElements = setOf(PREVIOUS, NEXT, CANCEL, EDIT),
            requiredRegions = setOf(CHOICES),
        )
        val INSPECTION_CONTRACT = MenuContract(requiredElements = setOf(INSPECTION_OVERVIEW))
    }
}
