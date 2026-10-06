package ru.arc.buildertools

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogInputId
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.menu.PaperDialogTextInput
import ru.arc.text.LocalizedMiniMessage
import java.util.UUID

/** Native, bounded material chooser used only with caller-provided candidates. */
internal class BuilderMaterialPicker(
    private val plugin: JavaPlugin,
    private val messages: LocalizedMiniMessage,
    private val dialogs: PaperDialogRuntime = PaperDialogRuntime(plugin),
) : AutoCloseable, Listener {
    private class Flow {
        val screens = mutableMapOf<String, Screen>()
        var currentScreen: String? = null
    }

    private class Screen(
        val titleKey: String,
        val candidates: List<Material>,
        val inventoryMaterials: Set<Material>,
        val isCurrent: () -> Boolean,
        val onSelected: (Material) -> Unit,
        val closeOnSelect: Boolean,
        var query: String = "",
        var page: Int = 0,
        var revision: Long = 0,
    )

    private val flows = mutableMapOf<UUID, Flow>()
    private var closed = false

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    fun open(
        player: Player,
        titleKey: String,
        materials: List<Material>,
        isCurrent: () -> Boolean,
        onSelected: (Material) -> Unit,
        beginFlow: Boolean = true,
        closeOnSelect: Boolean = true,
    ) {
        if (closed || !player.isOnline) return
        require(titleKey in TITLE_KEYS) { "Unsupported builder material-picker title key" }
        if (!current(isCurrent)) {
            close(player.uniqueId)
            return
        }
        val flow = if (beginFlow) {
            flows.remove(player.uniqueId)
            dialogs.beginFlow(player)
            Flow().also { flows[player.uniqueId] = it }
        } else {
            flows[player.uniqueId] ?: return
        }
        val permitted = materials.distinct()
        val screen = Screen(
            titleKey = titleKey,
            candidates = permitted,
            inventoryMaterials = player.inventory.contents.asSequence()
                .filterNotNull()
                .map { it.type }
                .filterNot { it.isAir }
                .filter { it in permitted }
                .toSet(),
            isCurrent = isCurrent,
            onSelected = onSelected,
            closeOnSelect = closeOnSelect,
        )
        flow.screens[titleKey] = screen
        flow.currentScreen = titleKey
        render(player, flow, screen, screen.revision)
    }

    fun close(playerId: UUID) {
        if (flows.remove(playerId) == null) return
        Bukkit.getPlayer(playerId)?.let(dialogs::close)
    }

    fun isOpen(playerId: UUID): Boolean = !closed && playerId in flows

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        flows.remove(event.player.uniqueId)
    }

    @EventHandler
    fun onPluginDisable(event: PluginDisableEvent) {
        if (event.plugin === plugin) close()
    }

    override fun close() {
        if (closed) return
        closed = true
        flows.clear()
        dialogs.close()
        HandlerList.unregisterAll(this)
    }

    private fun render(player: Player, flow: Flow, screen: Screen, revision: Long) {
        val playerId = player.uniqueId
        if (!active(playerId, flow, screen, revision)) return
        if (!current(screen.isCurrent)) {
            closeIfFlow(player, flow)
            return
        }
        val locale = player.locale().toLanguageTag()
        val results = BuilderMaterialArguments.search(screen.candidates, screen.query, screen.inventoryMaterials)
        val pageCount = maxOf(1, (results.size + PAGE_SIZE - 1) / PAGE_SIZE)
        val page = screen.page.coerceIn(0, pageCount - 1)
        if (page != screen.page) {
            screen.page = page
            screen.revision++
            return render(player, flow, screen, screen.revision)
        }
        val pageResults = results.drop(page * PAGE_SIZE).take(PAGE_SIZE)
        val buttons = buildList {
            add(
                PaperDialogButton(
                    id = SEARCH_ACTION,
                    label = messages.render("material-picker.find", locale),
                    width = 210,
                    onClick = { context ->
                        if (!validAction(player, flow, screen, revision)) return@PaperDialogButton
                        refresh(
                            player,
                            flow,
                            screen,
                            revision,
                            context.text(SEARCH_INPUT).orEmpty(),
                            0,
                        )
                    },
                ),
            )
            pageResults.forEachIndexed { index, material ->
                add(
                    PaperDialogButton(
                        id = PaperDialogActionId.of("material_$index"),
                        label = choiceLabel(player, material),
                        tooltip = Component.empty(),
                        width = 210,
                        onClick = { context ->
                            if (!validAction(player, flow, screen, revision)) return@PaperDialogButton
                            screen.query = context.text(SEARCH_INPUT).orEmpty().take(MAX_QUERY_LENGTH)
                            if (material !in screen.candidates) return@PaperDialogButton
                            if (screen.closeOnSelect) {
                                flows.remove(playerId, flow)
                                dialogs.close(player)
                            }
                            screen.onSelected(material)
                        },
                    ),
                )
            }
            if (page > 0) {
                add(
                    PaperDialogButton(
                        id = PREVIOUS_ACTION,
                        label = messages.render("material-picker.previous", locale),
                        width = 210,
                        onClick = { context ->
                            if (!validAction(player, flow, screen, revision)) return@PaperDialogButton
                            refresh(
                                player,
                                flow,
                                screen,
                                revision,
                                context.text(SEARCH_INPUT).orEmpty(),
                                page - 1,
                            )
                        },
                    ),
                )
            }
            if (page + 1 < pageCount) {
                add(
                    PaperDialogButton(
                        id = NEXT_ACTION,
                        label = messages.render("material-picker.next", locale),
                        width = 210,
                        onClick = { context ->
                            if (!validAction(player, flow, screen, revision)) return@PaperDialogButton
                            refresh(
                                player,
                                flow,
                                screen,
                                revision,
                                context.text(SEARCH_INPUT).orEmpty(),
                                page + 1,
                            )
                        },
                    ),
                )
            }
        }
        val body = if (results.isEmpty()) {
            listOf(PaperDialogBody(messages.render("material-picker.no-results", locale), BODY_WIDTH))
        } else {
            listOf(
                PaperDialogBody(
                    messages.render(
                        "material-picker.page",
                        locale,
                        mapOf(
                            "current" to Component.text(page + 1),
                            "total" to Component.text(pageCount),
                        ),
                    ),
                    BODY_WIDTH,
                ),
            )
        }
        val back = PaperDialogButton(
            id = BACK_ACTION,
            label = messages.render("material-picker.back", locale),
            width = 200,
            onClick = {},
        )
        dialogs.open(
            player,
            PaperDialogScreen(
                id = SCREEN_ID_PREFIX + screen.titleKey,
                title = messages.render(screen.titleKey, locale),
                body = body,
                inputs = listOf(
                    PaperDialogTextInput(
                        id = SEARCH_INPUT,
                        label = messages.render("material-picker.search-label", locale),
                        initial = screen.query,
                        width = 420,
                        maxLength = MAX_QUERY_LENGTH,
                    ),
                ),
                buttons = buttons,
                exitButton = back,
                columns = 2,
            ),
            reopen = null,
            onDismiss = { dismiss(playerId, flow, screen) },
        )
    }

    private fun refresh(
        player: Player,
        flow: Flow,
        screen: Screen,
        expectedRevision: Long,
        query: String,
        page: Int,
    ) {
        if (!active(player.uniqueId, flow, screen, expectedRevision)) return
        screen.query = query.take(MAX_QUERY_LENGTH)
        screen.page = page.coerceAtLeast(0)
        screen.revision++
        render(player, flow, screen, screen.revision)
    }

    private fun validAction(player: Player, flow: Flow, screen: Screen, revision: Long): Boolean {
        if (!active(player.uniqueId, flow, screen, revision)) return false
        if (current(screen.isCurrent)) return true
        closeIfFlow(player, flow)
        return false
    }

    private fun active(playerId: UUID, flow: Flow, screen: Screen, revision: Long): Boolean =
        !closed && flows[playerId] === flow && flow.currentScreen == screen.titleKey &&
            flow.screens[screen.titleKey] === screen && screen.revision == revision

    private fun closeIfFlow(player: Player, flow: Flow) {
        if (flows.remove(player.uniqueId, flow)) dialogs.close(player)
    }

    private fun dismiss(playerId: UUID, flow: Flow, screen: Screen) {
        if (flows[playerId] !== flow) return
        if (flow.screens[screen.titleKey] === screen) flow.screens.remove(screen.titleKey)
        if (flow.currentScreen == screen.titleKey) flow.currentScreen = flow.screens.keys.lastOrNull()
        if (flow.screens.isEmpty()) flows.remove(playerId, flow)
    }

    private fun choiceLabel(player: Player, material: Material): Component =
        Component.text("○ ", NamedTextColor.WHITE)
            .append(BuilderMaterialPresentation.label(player, material).color(NamedTextColor.WHITE))
            .decoration(TextDecoration.ITALIC, false)

    private fun current(isCurrent: () -> Boolean): Boolean = runCatching(isCurrent).getOrDefault(false)

    private companion object {
        const val PAGE_SIZE = 12
        const val MAX_QUERY_LENGTH = 64
        const val BODY_WIDTH = 420
        const val SCREEN_ID_PREFIX = "builder."
        val TITLE_KEYS = setOf(
            "material-picker.fill-title",
            "material-picker.replace-source-title",
            "material-picker.replace-target-title",
        )
        val SEARCH_INPUT = PaperDialogInputId.of("query")
        val SEARCH_ACTION = PaperDialogActionId.of("find")
        val PREVIOUS_ACTION = PaperDialogActionId.of("previous")
        val NEXT_ACTION = PaperDialogActionId.of("next")
        val BACK_ACTION = PaperDialogActionId.of("back")
    }
}
