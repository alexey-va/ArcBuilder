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
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.autobuild.BuildBookData
import ru.arc.autobuild.SystemBuildBookDefinition
import ru.arc.autobuild.BuildBookItems
import ru.arc.autobuild.BuildBookPreviewAdjustment
import ru.arc.autobuild.BuildBookPreviewBridge
import ru.arc.autobuild.BuildBookPreviewMove
import ru.arc.autobuild.ConstructionSite
import ru.arc.autobuild.ConstructionSiteSnapshot
import ru.arc.config.ConfigManager
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
import ru.arc.text.LocalizedMiniMessage
import ru.arc.util.Logging.warn
import java.time.Duration
import java.util.Locale
import java.util.UUID

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
    fun confirm(player: Player): Boolean
    fun complete(player: Player, kind: BuilderBookPreviewConfirmationKind)
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
    private val materialLineLimit: Int = 6,
) : BuildBookPreviewBridge, Listener, AutoCloseable {
    private data class PanelScene(
        val site: ConstructionSite,
        val entities: List<Entity>,
        val interactionId: UUID,
    )

    private val panels = mutableMapOf<UUID, PanelScene>()
    private val interactionOwners = mutableMapOf<UUID, UUID>()
    private val snapshots = mutableMapOf<UUID, ConstructionSiteSnapshot>()
    private val placementSites = mutableMapOf<UUID, ConstructionSite>()
    private val preparing = mutableSetOf<UUID>()
    private val confirmationInventories = mutableMapOf<UUID, org.bukkit.inventory.Inventory>()
    private val menuConfiguration = loadMenuConfiguration()
    private val menus = PaperMenuRuntime(plugin, BukkitTaskScheduler(plugin), menuConfiguration)
    private val menuItems = PaperMenuItemFactory(
        externalItems = PaperMenuItemsAdderResolver(
            isAvailable = { Bukkit.getPluginManager().isPluginEnabled("ItemsAdder") },
            lookup = { id -> CustomStack.getInstance(id)?.itemStack },
        ),
    )
    private var closed = false

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
            onClick = action,
        )
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
                        onClick = { onSelect(definition.buildingId) },
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

    fun clearPlayer(playerId: UUID) {
        close(playerId)
        snapshots.remove(playerId)
        confirmationInventories.remove(playerId)
        placementSites.remove(playerId)
        val player = Bukkit.getPlayer(playerId)
        player?.let(menus::session)?.close()
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPanelClick(event: PlayerInteractEntityEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val ownerId = interactionOwners[event.rightClicked.uniqueId] ?: return
        event.isCancelled = true
        val site = panels[ownerId]?.site ?: return
        when (BuilderBookPreviewAccess.level(
            ownerId,
            event.player.uniqueId,
            event.player.hasPermission(BUILDER_PREVIEW_ADMIN_PERMISSION),
        )) {
            BuilderBookPreviewAccessLevel.OWNER -> openPlacement(event.player, site)
            BuilderBookPreviewAccessLevel.INSPECT -> openInspection(event.player, site)
            BuilderBookPreviewAccessLevel.NONE -> Unit
        }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        if (!event.player.hasPermission(BUILDER_PREVIEW_ADMIN_PERMISSION)) return
        renderer.syncBookViewer(event.player)
        panels.values
            .filter { it.site.world.uid == event.player.world.uid }
            .flatMap(PanelScene::entities)
            .forEach { event.player.showEntity(plugin, it) }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        clearPlayer(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    fun onMenuClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        val playerId = player.uniqueId
        val inventory = confirmationInventories[playerId] ?: return
        if (event.view.topInventory !== inventory) return
        confirmationInventories.remove(playerId)
        val snapshot = snapshots.remove(playerId) ?: return
        if (closed || !player.isOnline) return
        host.restore(player, snapshot)?.let { placementSites[playerId] = it }
    }

    private fun adjust(player: Player, adjustment: BuildBookPreviewAdjustment) {
        if (host.adjust(player, adjustment) == null) player.closeInventory()
        else player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.65f, 1.15f)
    }

    private fun cancel(player: Player) {
        snapshots.remove(player.uniqueId)
        confirmationInventories.remove(player.uniqueId)
        host.cancel(player)
        player.closeInventory()
    }

    private fun continueToConfirmation(player: Player) {
        val site = placementSites[player.uniqueId] ?: panels[player.uniqueId]?.site ?: return player.closeInventory()
        if (!preparing.add(player.uniqueId)) return
        snapshots[player.uniqueId] = site.snapshot()
        host.prepare(player, site) { confirmation ->
            preparing.remove(player.uniqueId)
            if (closed || !player.isOnline) return@prepare
            if (player.uniqueId !in snapshots) return@prepare
            if (confirmation == null) {
                snapshots.remove(player.uniqueId)
                player.closeInventory()
            } else {
                openConfirmation(player)
            }
        }
    }

    private fun backToPlacement(player: Player) {
        val snapshot = snapshots.remove(player.uniqueId) ?: return player.closeInventory()
        val site = host.restore(player, snapshot) ?: return player.closeInventory()
        confirmationInventories.remove(player.uniqueId)
        placementSites[player.uniqueId] = site
        menus.session(player)?.requestRefresh()
    }

    private fun start(player: Player) {
        val confirmation = host.currentConfirmation(player) ?: return player.closeInventory()
        if (!confirmation.cooldownRemaining.isZero) {
            menus.session(player)?.requestRefresh()
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.7f)
            return
        }
        if (host.confirm(player)) {
            snapshots.remove(player.uniqueId)
            confirmationInventories.remove(player.uniqueId)
            host.complete(player, confirmation.kind)
            player.closeInventory()
        } else {
            menus.session(player)?.requestRefresh()
        }
    }

    private fun openPlacement(player: Player, site: ConstructionSite) {
        confirmationInventories.remove(player.uniqueId)
        placementSites[player.uniqueId] = site
        if (player.uniqueId in snapshots && host.currentConfirmation(player) == null) {
            snapshots.remove(player.uniqueId)
        }
        menus.open(player, PLACEMENT_MENU) { menuContent(player) }
        if (player.uniqueId in snapshots && host.currentConfirmation(player) != null) {
            menus.session(player)?.inventory?.let { confirmationInventories[player.uniqueId] = it }
        }
    }

    internal fun openPlacementForTest(player: Player, site: ConstructionSite) = openPlacement(player, site)

    internal fun openInspectionForTest(player: Player, site: ConstructionSite) = openInspection(player, site)

    private fun openInspection(player: Player, site: ConstructionSite) {
        menus.open(player, INSPECTION_MENU) { inspectionContent(player, site) }
    }

    private fun openConfirmation(player: Player) {
        val session = menus.session(player) ?: return player.closeInventory()
        confirmationInventories[player.uniqueId] = session.inventory
        session.requestRefresh()
    }

    private fun menuContent(player: Player): PaperMenuContent = snapshots[player.uniqueId]
        ?.let { host.currentConfirmation(player) }
        ?.let { confirmationContent(player, it) }
        ?: placementContent(player)

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
                CONTINUE to action(PLACEMENT_MENU, CONTINUE, "continue", values, locale) {
                    continueToConfirmation(it.player)
                },
            ),
        )
    }

    private fun confirmationContent(
        player: Player,
        confirmation: BuilderBookPreviewConfirmation,
    ): PaperMenuContent {
        val remaining = confirmation.cooldownRemaining
        val totalMinutes = (remaining.seconds + 59L) / 60L
        val values = mapOf(
            "count" to messages.literal(confirmation.blockCount),
            "hours" to messages.literal(totalMinutes / 60L),
            "minutes" to messages.literal(totalMinutes % 60L),
            "materials" to moneyLabel(confirmation.materialCostMinor),
            "labor" to moneyLabel(confirmation.constructionFeeMinor),
            "price" to moneyLabel(confirmation.issuePriceMinor),
        )
        val locale = locale(player)
        val activation = confirmation.kind == BuilderBookPreviewConfirmationKind.DRAFT_ACTIVATION
        val overviewKey = if (activation) "activation-overview" else "overview"
        val elements = buildMap {
            put(
                LEFT,
                confirmationItem(
                    OVERVIEW,
                    overviewKey,
                    values,
                    locale,
                    enabled = false,
                ),
            )
            if (activation) {
                put(
                    RIGHT,
                    PaperMenuEntry(
                        menuItems.create(
                            menuConfiguration.templates.getValue("price"),
                            messages.render("book.preview-menu.confirmation.activation-price.name", locale, values),
                            messages.renderLines("book.preview-menu.confirmation.activation-price.lore", locale, values),
                        ),
                        enabled = false,
                    ),
                )
            } else if (confirmation.requiredMaterials.isNotEmpty()) {
                val materialLore = buildList {
                    addAll(messages.renderLines("book.preview-menu.confirmation.materials.lore", locale, values))
                    confirmation.requiredMaterials.take(materialLineLimit).forEach { amount ->
                        add(
                            messages.render(
                                "book.preview-menu.confirmation.material-line",
                                locale,
                                mapOf(
                                    "amount" to messages.literal(amount.amount),
                                    "material" to requiredMaterialLabel(player, amount),
                                ),
                            ),
                        )
                    }
                    val hidden = confirmation.requiredMaterials.size - materialLineLimit
                    if (hidden > 0) {
                        add(
                            messages.render(
                                "book.preview-menu.confirmation.material-more",
                                locale,
                                mapOf("count" to messages.literal(hidden)),
                            ),
                        )
                    }
                }
                put(
                    RIGHT,
                    PaperMenuEntry(
                        menuItems.create(
                            menuConfiguration.template(CONFIRMATION_MENU, MATERIALS),
                            messages.render("book.preview-menu.confirmation.materials.name", locale, values),
                            materialLore,
                        ),
                        enabled = false,
                    ),
                )
            }
            put(DOWN, confirmationItem(BACK, "back", values, locale) { backToPlacement(it.player) })
            put(
                CONTINUE,
                PaperMenuEntry(
                    menuItems.create(
                        menuConfiguration.templates.getValue(if (remaining.isZero) "start" else "blocked"),
                        messages.render(
                            "book.preview-menu.confirmation.${
                                when {
                                    !remaining.isZero -> "blocked"
                                    activation -> "activation-start"
                                    else -> "start"
                                }
                            }.name",
                            locale,
                            values,
                        ),
                        messages.renderLines(
                            "book.preview-menu.confirmation.${
                                when {
                                    !remaining.isZero -> "blocked"
                                    activation -> "activation-start"
                                    else -> "start"
                                }
                            }.lore",
                            locale,
                            values,
                        ),
                    ),
                    onClick = { start(it.player) },
                ),
            )
            put(CANCEL, confirmationItem(CANCEL, "cancel", values, locale) { cancel(it.player) })
        }
        return PaperMenuContent(
            title = messages.render(
                "book.preview-menu.placement.title",
                locale,
                mapOf("name" to messages.literal(BuildBookItems.compactTitle(confirmation.title, 20))),
            ),
            background = background(PLACEMENT_MENU),
            elements = elements,
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
        onClick = click,
    )

    private fun confirmationItem(
        element: MenuElementId,
        key: String,
        values: Map<String, Component>,
        locale: String,
        extraLore: List<Component> = emptyList(),
        enabled: Boolean = true,
        click: (PaperMenuClickContext) -> Unit = {},
    ): PaperMenuEntry = PaperMenuEntry(
        menuItems.create(
            menuConfiguration.template(CONFIRMATION_MENU, element),
            messages.render("book.preview-menu.confirmation.$key.name", locale, values),
            messages.renderLines("book.preview-menu.confirmation.$key.lore", locale, values) + extraLore,
        ),
        enabled = enabled,
        onClick = click,
    )

    private fun background(menu: MenuId) = menuItems.create(
        menuConfiguration.template(requireNotNull(menuConfiguration.catalog.require(menu).backgroundTemplate)),
        Component.empty(),
        emptyList(),
    )

    private fun requiredMaterialLabel(player: Player, amount: BuilderItemAmount): Component {
        val prototype = BuilderItemCodec.decodePrototype(amount.itemBase64)
        val meta = prototype.itemMeta
        return if (meta.hasDisplayName()) {
            checkNotNull(meta.displayName())
        } else {
            BuilderMaterialPresentation.label(player, prototype.type)
        }
    }

    private fun moneyLabel(amountMinor: Long): Component = BuilderCurrencyPresentation.amountWithCoin(
        messages.literal(String.format(Locale.US, "%,.2f", BuilderMoney.decimal(amountMinor))),
    )

    private fun replacePanel(site: ConstructionSite) {
        removePanel(site.player.uniqueId)
        if (!site.player.isOnline || site.player.world.uid != site.world.uid) return
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
        val location = Location(site.world, panel.x, panel.y, panel.z)
        val entities = mutableListOf<Entity>()
        try {
            for (yaw in listOf(panel.yaw, panel.yaw + 180f)) {
                val display = site.world.spawn(location, TextDisplay::class.java) { entity ->
                    configure(entity, site.player)
                    entity.text(
                        messages.render(
                            "book.preview-panel",
                            locale(site.player),
                            mapOf("name" to messages.literal(BuildBookItems.compactTitle(site.bookData.title, 24))),
                        ),
                    )
                    BuilderConstructionSitePanelOrientation.apply(entity, yaw)
                    entity.lineWidth = panelLineWidth
                    entity.backgroundColor = panelBackgroundColor
                    entity.isShadowed = true
                    entity.isSeeThrough = false
                    entity.alignment = TextDisplay.TextAlignment.CENTER
                    entity.displayWidth = panelInteractionWidth * BuilderConstructionSitePanelOrientation.SCALE
                    entity.displayHeight = panelInteractionHeight * BuilderConstructionSitePanelOrientation.SCALE
                    entity.isGlowing = true
                    entity.glowColorOverride = panelGlowColor
                    entity.brightness = Display.Brightness(15, 15)
                    entity.viewRange = (viewRange / 64.0).toFloat()
                }
                entities += display
            }
            val interaction = site.world.spawn(
                location.clone().subtract(0.0, panelInteractionHeight * BuilderConstructionSitePanelOrientation.SCALE / 2.0, 0.0),
                Interaction::class.java,
            ) { entity ->
                configure(entity, site.player)
                entity.interactionWidth = panelInteractionWidth * BuilderConstructionSitePanelOrientation.SCALE
                entity.interactionHeight = panelInteractionHeight * BuilderConstructionSitePanelOrientation.SCALE
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
        Bukkit.getOnlinePlayers()
            .filter { it.world.uid == player.world.uid && it.hasPermission(BUILDER_PREVIEW_ADMIN_PERMISSION) }
            .forEach { it.showEntity(plugin, entity) }
    }

    private fun removePanel(playerId: UUID) {
        val scene = panels.remove(playerId) ?: return
        interactionOwners.remove(scene.interactionId)
        scene.entities.forEach(Entity::remove)
    }

    private fun locale(player: Player): String = player.locale().toLanguageTag()

    private fun loadMenuConfiguration(): PaperMenuConfiguration = PaperMenuConfigurationParser.require(
        ConfigManager.ofModule(plugin.dataPath, "builder-tools.yml"),
        "paper-menus.preview.layouts",
        "paper-menus.preview.templates",
        mapOf(
            PLACEMENT_MENU to PLACEMENT_CONTRACT,
            CONFIRMATION_MENU to CONFIRMATION_CONTRACT,
            INSPECTION_MENU to INSPECTION_CONTRACT,
            SELECTOR_MENU to SELECTOR_CONTRACT,
        ),
        requiredTemplates = setOf("blocked"),
    )

    override fun close() {
        if (closed) return
        closed = true
        HandlerList.unregisterAll(this)
        panels.keys.toList().forEach(::removePanel)
        snapshots.clear()
        confirmationInventories.clear()
        placementSites.clear()
        preparing.clear()
        menus.close()
    }

    private companion object {
        val SELECTOR_MENU = MenuId.of("book-selector")
        val CHOICES = MenuRegionId.of("choices")
        val PREVIOUS = MenuElementId.of("previous")
        val NEXT = MenuElementId.of("next")
        val EDIT = MenuElementId.of("edit")
        val PLACEMENT_MENU = MenuId.of("book-preview-placement")
        val CONFIRMATION_MENU = MenuId.of("book-preview-confirmation")
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
        val OVERVIEW = MenuElementId.of("overview")
        val MATERIALS = MenuElementId.of("materials")
        val BACK = MenuElementId.of("back")
        val START = MenuElementId.of("start")
        val INSPECTION_OVERVIEW = MenuElementId.of("inspection-overview")
        val PLACEMENT_CONTRACT = MenuContract(
            requiredElements = setOf(UP, LEFT, ROTATE, RIGHT, AWAY, TOWARD, CONTINUE, DOWN, CANCEL, MIRROR, RESET),
        )
        val CONFIRMATION_CONTRACT = MenuContract(
            requiredElements = setOf(OVERVIEW, START, BACK, CANCEL),
            optionalElements = setOf(MATERIALS),
        )
        val SELECTOR_CONTRACT = MenuContract(
            requiredElements = setOf(PREVIOUS, NEXT, CANCEL, EDIT),
            requiredRegions = setOf(CHOICES),
        )
        val INSPECTION_CONTRACT = MenuContract(requiredElements = setOf(INSPECTION_OVERVIEW))
    }
}
