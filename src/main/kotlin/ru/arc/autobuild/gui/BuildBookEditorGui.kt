package ru.arc.autobuild.gui

import dev.lone.itemsadder.api.CustomStack
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import ru.arc.ARC
import ru.arc.autobuild.BuildBookCodec
import ru.arc.autobuild.BuildBookData
import ru.arc.autobuild.BuildBookItems
import ru.arc.autobuild.BuildBookTransform
import ru.arc.autobuild.BuildingManager
import ru.arc.autobuild.PreviewTransformUpdateResult
import ru.arc.buildertools.BuilderBookCopyPolicy
import ru.arc.buildertools.BuilderCurrencyPresentation
import ru.arc.buildertools.BuilderMoney
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.core.BukkitTaskScheduler
import ru.arc.menu.MenuContract
import ru.arc.menu.MenuElementId
import ru.arc.menu.MenuId
import ru.arc.paper.menu.PaperMenuClickContext
import ru.arc.paper.menu.PaperMenuClickTarget
import ru.arc.paper.menu.PaperMenuConfiguration
import ru.arc.paper.menu.PaperMenuConfigurationParser
import ru.arc.paper.menu.PaperMenuContent
import ru.arc.paper.menu.PaperMenuEntry
import ru.arc.paper.menu.PaperMenuExternalItemResolver
import ru.arc.paper.menu.PaperMenuExternalItemResult
import ru.arc.paper.menu.PaperMenuItemFactory
import ru.arc.paper.menu.PaperMenuRuntime
import ru.arc.util.TextUtil

object BuildBookEditorGui {
    private val config: Config get() = ConfigManager.ofModule(ARC.instance.dataPath, "auto-build.yml")
    private var runtime: PaperMenuRuntime? = null
    private val itemFactory = PaperMenuItemFactory(
        externalItems = PaperMenuExternalItemResolver { id ->
            if (id.namespace != "itemsadder" || !Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) {
                PaperMenuExternalItemResult.Missing
            } else {
                val customId = id.key.replaceFirst('/', ':')
                CustomStack.getInstance(customId)?.itemStack?.let(PaperMenuExternalItemResult::Resolved)
                    ?: PaperMenuExternalItemResult.Missing
            }
        },
    )

    fun open(player: Player, onCopy: ((Player) -> Unit)? = null) {
        if (!player.hasPermission("arcbuild.book.edit")) {
            player.sendMessage(text("build-book.editor.no-permission"))
            return
        }
        val data = BuildBookCodec.read(player.inventory.itemInMainHand)
        if (data == null || BuildingManager.getBuilding(data.buildingId) == null) {
            player.sendMessage(text("build-book.editor.invalid"))
            return
        }
        val menus = menus()
        menus.open(player, MENU_ID) { content(player, data, onCopy, menus.current()) }
    }

    /** Releases the listener and active viewers when the owning module shuts down. */
    fun close() {
        runtime?.close()
        runtime = null
    }

    private fun menus(): PaperMenuRuntime {
        val candidate = configuration()
        val active = runtime
        if (active == null) {
            return PaperMenuRuntime(ARC.instance, BukkitTaskScheduler(ARC.instance), candidate).also { runtime = it }
        }
        val current = active.current()
        if (current.catalog.layouts != candidate.catalog.layouts || current.templates != candidate.templates) {
            active.replace(candidate)
        }
        return active
    }

    private fun configuration(): PaperMenuConfiguration = PaperMenuConfigurationParser.require(
        config,
        "build-book.editor.menu.layouts",
        "build-book.editor.menu.templates",
        mapOf(MENU_ID to CONTRACT),
    )

    private fun content(
        player: Player,
        data: BuildBookData,
        onCopy: ((Player) -> Unit)?,
        configuration: PaperMenuConfiguration,
    ): PaperMenuContent = PaperMenuContent(
        title = text("build-book.editor.title"),
        background = itemFactory.create(
            configuration.template(
                requireNotNull(configuration.catalog.require(MENU_ID).backgroundTemplate) {
                    "Build-book editor background template is required"
                },
            ),
            Component.empty(),
            emptyList(),
        ),
        elements = buildMap {
            put(OVERVIEW, entry(configuration, OVERVIEW, overviewName(data), overviewLore(data), enabled = false))
            put(AXIS_X, axisEntry(configuration, AXIS_X, "axis-x", data.transform.offsetX, onCopy) { click ->
                data.transform.offset(dx = click.delta())
            })
            put(AXIS_Y, axisEntry(configuration, AXIS_Y, "axis-y", data.transform.offsetY, onCopy) { click ->
                data.transform.offset(dy = click.delta())
            })
            put(AXIS_Z, axisEntry(configuration, AXIS_Z, "axis-z", data.transform.offsetZ, onCopy) { click ->
                data.transform.offset(dz = click.delta())
            })
            put(
                ROTATION,
                entry(
                    configuration,
                    ROTATION,
                    text("build-book.editor.rotation.name"),
                    config.componentList("build-book.editor.rotation.lore") {
                        tag("value", Component.text(data.transform.rotation))
                    },
                    acceptedClicks = EDITOR_CLICKS,
                ) { click ->
                    val delta = if (click.event.isRightClick) 90 else -90
                    applyChange(click, onCopy, data.transform.rotate(delta))
                },
            )
            put(
                RESET,
                entry(
                    configuration,
                    RESET,
                    text("build-book.editor.reset.name"),
                    config.componentList("build-book.editor.reset.lore"),
                ) { click -> applyChange(click, onCopy, BuildBookTransform()) },
            )
            if (onCopy != null && BuilderBookCopyPolicy.canRequest(player.uniqueId, data) &&
                COPY in configuration.catalog.require(MENU_ID).elements
            ) {
                put(
                    COPY,
                    entry(
                        configuration,
                        COPY,
                        text("build-book.editor.copy.name"),
                        config.componentList("build-book.editor.copy.lore") {
                            tag(
                                "price",
                                BuilderCurrencyPresentation.amountWithCoin(
                                    Component.text(BuilderMoney.decimal(checkNotNull(data.issuePriceMinor)).toPlainString()),
                                ),
                            )
                        },
                    ) { click ->
                        click.player.closeInventory()
                        onCopy(click.player)
                    },
                )
            }
        },
    )

    private fun axisEntry(
        configuration: PaperMenuConfiguration,
        element: MenuElementId,
        path: String,
        value: Int,
        onCopy: ((Player) -> Unit)?,
        change: (PaperMenuClickContext) -> BuildBookTransform,
    ): PaperMenuEntry = entry(
        configuration,
        element,
        text("build-book.editor.$path.name"),
        config.componentList("build-book.editor.$path.lore") { tag("value", Component.text(value)) },
        acceptedClicks = EDITOR_CLICKS,
    ) { click -> applyChange(click, onCopy, change(click)) }

    private fun entry(
        configuration: PaperMenuConfiguration,
        element: MenuElementId,
        name: Component,
        lore: List<Component>,
        enabled: Boolean = true,
        acceptedClicks: Set<ClickType> = setOf(ClickType.LEFT, ClickType.RIGHT),
        click: (PaperMenuClickContext) -> Unit = {},
    ): PaperMenuEntry = PaperMenuEntry(
        item = itemFactory.create(configuration.template(MENU_ID, element), name, lore),
        enabled = enabled,
        acceptedClicks = acceptedClicks,
        onClick = click,
    )

    private fun applyChange(
        click: PaperMenuClickContext,
        onCopy: ((Player) -> Unit)?,
        nextTransform: BuildBookTransform,
    ) {
        val player = click.player
        val held = player.inventory.itemInMainHand
        val current = BuildBookCodec.read(held)
        if (current == null || !player.hasPermission("arcbuild.book.edit")) {
            player.closeInventory()
            player.sendMessage(text("build-book.editor.invalid"))
            return
        }
        val next = current.copy(transform = nextTransform.validated()).validated()
        val previewResult = BuildingManager.updatePendingTransform(player, next)
        if (!previewResult.allowsBookUpdate) {
            val feedbackPath = when (previewResult) {
                PreviewTransformUpdateResult.PREVIEW_INACTIVE -> "preview-inactive"
                PreviewTransformUpdateResult.BOOK_MISMATCH -> "preview-book-mismatch"
                PreviewTransformUpdateResult.PROTECTION_DENIED -> "preview-protection-denied"
                PreviewTransformUpdateResult.NO_PREVIEW,
                PreviewTransformUpdateResult.UPDATED,
                -> error("Accepted preview result cannot render rejection feedback: $previewResult")
            }
            val item = click.event.currentItem?.clone() ?: return
            feedbackState(feedbackPath).applyTo(item)
            val element = (click.target as PaperMenuClickTarget.Element).id
            click.session.showFeedback(element, FEEDBACK_TICKS, item)
            return
        }
        player.inventory.setItemInMainHand(BuildBookCodec.update(held, next))
        player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.7f, 1.2f)
        open(player, onCopy)
    }

    private fun PaperMenuClickContext.delta(): Int {
        val magnitude = if (event.isShiftClick) 5 else 1
        return if (event.isRightClick) magnitude else -magnitude
    }

    private fun overviewName(data: BuildBookData): Component = config.component(
        "build-book.editor.overview.name",
        "<#d48763><bold><name>",
    ) { tag("name", Component.text(BuildBookItems.compactTitle(data.title))) }

    private fun overviewLore(data: BuildBookData): List<Component> =
        config.componentList("build-book.editor.overview.lore") {
            tag("name", Component.text(data.title))
            tag("rotation", Component.text(data.transform.rotation))
            tag("offset_x", Component.text(data.transform.offsetX))
            tag("offset_y", Component.text(data.transform.offsetY))
            tag("offset_z", Component.text(data.transform.offsetZ))
        }

    private fun text(path: String): Component =
        requireNotNull(config.componentOrNull(path)) { "Missing build-book editor text '$path'" }

    private fun feedbackState(path: String): BuildBookEditorItemState = BuildBookEditorPresentation.state(
        text("build-book.editor.$path.name"),
        config.componentList("build-book.editor.$path.lore"),
    )

    private val MENU_ID = MenuId.of("build-book-editor")
    private val OVERVIEW = MenuElementId.of("overview")
    private val AXIS_X = MenuElementId.of("axis-x")
    private val AXIS_Y = MenuElementId.of("axis-y")
    private val AXIS_Z = MenuElementId.of("axis-z")
    private val ROTATION = MenuElementId.of("rotation")
    private val RESET = MenuElementId.of("reset")
    private val COPY = MenuElementId.of("copy")
    private val CONTRACT = MenuContract(
        requiredElements = setOf(OVERVIEW, AXIS_X, AXIS_Y, AXIS_Z, ROTATION, RESET),
        optionalElements = setOf(COPY),
    )
    private val EDITOR_CLICKS = setOf(ClickType.LEFT, ClickType.RIGHT, ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT)
    private const val FEEDBACK_TICKS = 40L
}

internal data class BuildBookEditorItemState(
    val name: Component,
    val lore: List<Component>,
) {
    fun applyTo(item: ItemStack) {
        item.editMeta { meta ->
            meta.displayName(name)
            meta.lore(lore)
        }
    }
}

/** Final item presentation shared by every visible editor control. */
internal object BuildBookEditorPresentation {
    fun state(
        name: Component,
        lore: List<Component>,
    ): BuildBookEditorItemState = BuildBookEditorItemState(
        name = requireNotNull(TextUtil.strip(name)),
        lore = lore.mapNotNull(TextUtil::strip),
    )

    fun capture(item: ItemStack): BuildBookEditorItemState {
        val meta = item.itemMeta
        return state(
            requireNotNull(meta.displayName()) { "Build-book editor item is missing a display name" },
            meta.lore().orEmpty(),
        )
    }

    fun item(
        material: org.bukkit.Material,
        name: Component,
        lore: List<Component>,
    ): ItemStack = ItemStack(material).apply {
        state(name, lore).applyTo(this)
    }
}
