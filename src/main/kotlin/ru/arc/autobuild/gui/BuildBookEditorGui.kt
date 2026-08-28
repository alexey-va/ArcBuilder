package ru.arc.autobuild.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.TextHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.OutlinePane
import com.github.stefvanschie.inventoryframework.pane.Pane
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import com.github.stefvanschie.inventoryframework.pane.util.Slot
import dev.lone.itemsadder.api.CustomStack
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack
import ru.arc.ARC
import ru.arc.autobuild.BuildBookCodec
import ru.arc.autobuild.BuildBookData
import ru.arc.autobuild.BuildBookItems
import ru.arc.autobuild.BuildBookSettings
import ru.arc.autobuild.BuildBookTransform
import ru.arc.autobuild.BuildingManager
import ru.arc.autobuild.PreviewTransformUpdateResult
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.util.TextUtil

object BuildBookEditorGui {
    private val config: Config get() = ConfigManager.ofModule(ARC.instance.dataPath, "auto-build.yml")

    fun open(player: Player) {
        if (!player.hasPermission("arcbuild.book.edit")) {
            player.sendMessage(text("build-book.editor.no-permission"))
            return
        }
        val data = BuildBookCodec.read(player.inventory.itemInMainHand)
        if (data == null || BuildingManager.getBuilding(data.buildingId) == null) {
            player.sendMessage(text("build-book.editor.invalid"))
            return
        }
        create(player, data).show(player)
    }

    private fun create(player: Player, data: BuildBookData): ChestGui {
        val gui = ChestGui(
            3,
            TextHolder.deserialize(TextUtil.toLegacy(config.string("build-book.editor.title"))),
            ARC.instance,
        )
        val feedback = BuildBookEditorFeedbackController(refresh = gui::update)
        gui.addPane(
            Slot.fromXY(0, 0),
            OutlinePane(9, 3, Pane.Priority.LOWEST).apply {
                addItem(background())
                setRepeat(true)
            },
        )
        gui.addPane(
            Slot.fromXY(0, 0),
            StaticPane(9, 3).apply {
                addItem(overview(data), 4, 0)
                addItem(axisItem("axis-x", Material.REDSTONE_TORCH, data.transform.offsetX, feedback) { click ->
                    data.transform.offset(dx = click.delta())
                }, 2, 1)
                addItem(axisItem("axis-y", Material.SCAFFOLDING, data.transform.offsetY, feedback) { click ->
                    data.transform.offset(dy = click.delta())
                }, 4, 1)
                addItem(axisItem("axis-z", Material.RECOVERY_COMPASS, data.transform.offsetZ, feedback) { click ->
                    data.transform.offset(dz = click.delta())
                }, 6, 1)
                addItem(rotationItem(data, feedback), 3, 2)
                addItem(actionItem("reset", Material.REPEATER, feedback) { BuildBookTransform() }, 5, 2)
            },
        )
        gui.setOnTopClick { it.isCancelled = true }
        gui.setOnBottomClick { it.isCancelled = true }
        gui.setOnClose { feedback.close() }
        return gui
    }

    private fun overview(data: BuildBookData): GuiItem = item(
        Material.BOOK,
        config.component("build-book.editor.overview.name", "<#92bed8><bold><name>") {
            tag("name", Component.text(BuildBookItems.compactTitle(data.title)))
        },
        config.componentList("build-book.editor.overview.lore") {
            tag("name", Component.text(data.title))
            tag("rotation", Component.text(data.transform.rotation))
            tag("offset_x", Component.text(data.transform.offsetX))
            tag("offset_y", Component.text(data.transform.offsetY))
            tag("offset_z", Component.text(data.transform.offsetZ))
        },
    )

    private fun axisItem(
        path: String,
        material: Material,
        value: Int,
        feedback: BuildBookEditorFeedbackController,
        change: (InventoryClickEvent) -> BuildBookTransform,
    ): GuiItem = item(
        material,
        text("build-book.editor.$path.name"),
        config.componentList("build-book.editor.$path.lore") { tag("value", Component.text(value)) },
    ) { event, source -> applyChange(event, source, feedback, change(event)) }

    private fun rotationItem(
        data: BuildBookData,
        feedback: BuildBookEditorFeedbackController,
    ): GuiItem = item(
        Material.CLOCK,
        text("build-book.editor.rotation.name"),
        config.componentList("build-book.editor.rotation.lore") {
            tag("value", Component.text(data.transform.rotation))
        },
    ) { event, source ->
        val delta = if (event.isRightClick) 90 else -90
        applyChange(event, source, feedback, data.transform.rotate(delta))
    }

    private fun actionItem(
        path: String,
        material: Material,
        feedback: BuildBookEditorFeedbackController,
        change: () -> BuildBookTransform?,
    ): GuiItem = item(
        material,
        text("build-book.editor.$path.name"),
        config.componentList("build-book.editor.$path.lore"),
    ) { event, source -> change()?.let { applyChange(event, source, feedback, it) } }

    private fun applyChange(
        event: InventoryClickEvent,
        source: GuiItem,
        feedback: BuildBookEditorFeedbackController,
        nextTransform: BuildBookTransform,
    ) {
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
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
            feedback.show(source.item, feedbackState(feedbackPath))
            return
        }
        player.inventory.setItemInMainHand(BuildBookCodec.update(held, next))
        player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.7f, 1.2f)
        create(player, next).show(player)
    }

    private fun InventoryClickEvent.delta(): Int {
        val magnitude = if (isShiftClick) 5 else 1
        return if (isRightClick) magnitude else -magnitude
    }

    private fun text(path: String): Component =
        requireNotNull(config.componentOrNull(path)) { "Missing build-book editor text '$path'" }

    private fun feedbackState(path: String): BuildBookEditorItemState = BuildBookEditorPresentation.state(
        text("build-book.editor.$path.name"),
        config.componentList("build-book.editor.$path.lore"),
    )

    private fun item(
        material: Material,
        name: Component,
        lore: List<Component>,
        click: ((InventoryClickEvent, GuiItem) -> Unit)? = null,
    ): GuiItem {
        lateinit var guiItem: GuiItem
        guiItem = GuiItem(BuildBookEditorPresentation.item(material, name, lore)) { event ->
            event.isCancelled = true
            click?.invoke(event, guiItem)
        }
        return guiItem
    }

    private fun background(): GuiItem = GuiItem(
        serverBackground(),
    ) { it.isCancelled = true }

    private fun serverBackground(): ItemStack {
        val item = if (Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) {
            runCatching { CustomStack.getInstance("arc:background")?.itemStack?.clone() }.getOrNull()
        } else {
            null
        } ?: ItemStack(Material.GRAY_STAINED_GLASS_PANE)
        BuildBookEditorPresentation.state(Component.empty(), emptyList()).applyTo(item)
        return item
    }
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
        material: Material,
        name: Component,
        lore: List<Component>,
    ): ItemStack =
        ItemStack(material).apply {
            state(name, lore).applyTo(this)
        }
}
