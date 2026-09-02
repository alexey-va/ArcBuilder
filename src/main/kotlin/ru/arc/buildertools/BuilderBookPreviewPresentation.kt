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
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.java.JavaPlugin
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
import ru.arc.menu.MenuId
import ru.arc.paper.menu.PaperMenuConfiguration
import ru.arc.paper.menu.PaperMenuConfigurationParser
import ru.arc.paper.menu.PaperMenuClickContext
import ru.arc.paper.menu.PaperMenuContent
import ru.arc.paper.menu.PaperMenuEntry
import ru.arc.paper.menu.PaperMenuExternalItemResolver
import ru.arc.paper.menu.PaperMenuExternalItemResult
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
    private val menuConfiguration = loadMenuConfiguration()
    private val menus = PaperMenuRuntime(plugin, BukkitTaskScheduler(plugin), menuConfiguration)
    private val menuItems = PaperMenuItemFactory(
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
        menus.session(site.player)?.takeIf { it.menuId == PLACEMENT_MENU }?.refresh()
    }

    override fun close(playerId: UUID) {
        renderer.close(playerId)
        removePanel(playerId)
        placementSites.remove(playerId)
        val player = Bukkit.getPlayer(playerId)
        player?.let(menus::session)?.takeIf { it.menuId == PLACEMENT_MENU }?.close()
    }

    fun clearPlayer(playerId: UUID) {
        close(playerId)
        snapshots.remove(playerId)
        placementSites.remove(playerId)
        val player = Bukkit.getPlayer(playerId)
        player?.let(menus::session)?.close()
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

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        clearPlayer(event.player.uniqueId)
    }

    private fun adjust(player: Player, adjustment: BuildBookPreviewAdjustment) {
        if (host.adjust(player, adjustment) == null) player.closeInventory()
        else player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.65f, 1.15f)
    }

    private fun cancel(player: Player) {
        snapshots.remove(player.uniqueId)
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
                openConfirmation(player, confirmation)
            }
        }
    }

    private fun backToPlacement(player: Player) {
        val snapshot = snapshots.remove(player.uniqueId) ?: return player.closeInventory()
        val site = host.restore(player, snapshot) ?: return player.closeInventory()
        openPlacement(player, site)
    }

    private fun start(player: Player) {
        val confirmation = host.currentConfirmation(player) ?: return player.closeInventory()
        if (!confirmation.cooldownRemaining.isZero) {
            menus.session(player)?.refresh()
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.7f)
            return
        }
        if (host.confirm(player)) {
            snapshots.remove(player.uniqueId)
            player.closeInventory()
        } else {
            menus.session(player)?.refresh()
        }
    }

    private fun openPlacement(player: Player, site: ConstructionSite) {
        placementSites[player.uniqueId] = site
        menus.open(player, PLACEMENT_MENU) { placementContent(player) }
    }

    internal fun openPlacementForTest(player: Player, site: ConstructionSite) = openPlacement(player, site)

    private fun openConfirmation(player: Player, confirmation: BuilderBookPreviewConfirmation) {
        menus.open(player, CONFIRMATION_MENU) {
            confirmationContent(player, host.currentConfirmation(player) ?: confirmation)
        }
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
        val noMaterialsLore = if (confirmation.requiredMaterials.isEmpty()) {
            listOf(messages.render("book.preview-menu.confirmation.materials-none", locale))
        } else {
            emptyList()
        }
        val elements = buildMap {
            put(
                OVERVIEW,
                confirmationItem(
                    OVERVIEW,
                    overviewKey,
                    values,
                    locale,
                    if (activation) emptyList() else noMaterialsLore,
                    enabled = false,
                ),
            )
            if (activation) {
                put(
                    MATERIALS,
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
                                    "material" to requiredMaterialLabel(amount),
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
                    MATERIALS,
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
            put(BACK, confirmationItem(BACK, "back", values, locale) { backToPlacement(it.player) })
            put(
                START,
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
                "book.preview-menu.confirmation.${if (activation) "activation-title" else "title"}",
                locale,
                mapOf("name" to messages.literal(BuildBookItems.compactTitle(confirmation.title, 20))),
            ),
            background = background(CONFIRMATION_MENU),
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

    private fun requiredMaterialLabel(amount: BuilderItemAmount): Component {
        val prototype = BuilderItemCodec.decodePrototype(amount.itemBase64)
        val meta = prototype.itemMeta
        return if (meta.hasDisplayName()) {
            checkNotNull(meta.displayName())
        } else {
            Component.translatable(prototype.type.translationKey())
        }
    }

    private fun moneyLabel(amountMinor: Long): Component = BuilderCurrencyPresentation.amountWithCoin(
        messages.literal(String.format(Locale.US, "%,.2f", BuilderMoney.decimal(amountMinor))),
    )

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

    private fun locale(player: Player): String = player.locale().toLanguageTag()

    private fun loadMenuConfiguration(): PaperMenuConfiguration = PaperMenuConfigurationParser.require(
        ConfigManager.ofModule(plugin.dataPath, "builder-tools.yml"),
        "paper-menus.preview.layouts",
        "paper-menus.preview.templates",
        mapOf(
            PLACEMENT_MENU to PLACEMENT_CONTRACT,
            CONFIRMATION_MENU to CONFIRMATION_CONTRACT,
        ),
        requiredTemplates = setOf("blocked"),
    )

    override fun close() {
        if (closed) return
        closed = true
        HandlerList.unregisterAll(this)
        panels.keys.toList().forEach(::removePanel)
        snapshots.clear()
        placementSites.clear()
        preparing.clear()
        menus.close()
    }

    private companion object {
        val PLACEMENT_MENU = MenuId.of("book-preview-placement")
        val CONFIRMATION_MENU = MenuId.of("book-preview-confirmation")
        val UP = MenuElementId.of("up")
        val LEFT = MenuElementId.of("left")
        val ROTATE = MenuElementId.of("rotate")
        val RIGHT = MenuElementId.of("right")
        val CONTINUE = MenuElementId.of("continue")
        val DOWN = MenuElementId.of("down")
        val CANCEL = MenuElementId.of("cancel")
        val MIRROR = MenuElementId.of("mirror")
        val RESET = MenuElementId.of("reset")
        val OVERVIEW = MenuElementId.of("overview")
        val MATERIALS = MenuElementId.of("materials")
        val BACK = MenuElementId.of("back")
        val START = MenuElementId.of("start")
        val PLACEMENT_CONTRACT = MenuContract(
            requiredElements = setOf(UP, LEFT, ROTATE, RIGHT, CONTINUE, DOWN, CANCEL, MIRROR, RESET),
        )
        val CONFIRMATION_CONTRACT = MenuContract(
            requiredElements = setOf(OVERVIEW, START, BACK, CANCEL),
            optionalElements = setOf(MATERIALS),
        )
    }
}
