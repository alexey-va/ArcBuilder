package ru.arc.buildertools

import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogInputId
import ru.arc.paper.menu.PaperDialogNumberRangeInput
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.text.LocalizedMiniMessage
import java.util.UUID
import kotlin.math.roundToInt

/** Native, revisable settings flow for the selector-driven crown operation. */
internal class BuilderCrownSelectionDialog(
    private val messages: LocalizedMiniMessage,
    private val dialogs: PaperDialogRuntime,
    private val materialPicker: BuilderMaterialPicker,
    private val leafMaterials: List<Material>,
    private val isCurrent: (Player, BuilderSelection, UUID?) -> Boolean,
    private val onPreview: (Player, BuilderSelection, UUID?, BuilderCrownSelectionOptions) -> Boolean,
) : AutoCloseable {
    private class Session(
        val selection: BuilderSelection,
        val expectedPlanId: UUID?,
        var options: BuilderCrownSelectionOptions,
        var revision: Long = 0L,
    )

    private val sessions = mutableMapOf<UUID, Session>()
    private val lastOptions = mutableMapOf<UUID, BuilderCrownSelectionOptions>()
    private var closed = false

    fun open(
        player: Player,
        selection: BuilderSelection,
        expectedPlanId: UUID?,
        initialOptions: BuilderCrownSelectionOptions,
    ) {
        if (closed || !player.isOnline || !current(player, selection, expectedPlanId)) return
        val session = Session(
            selection = selection,
            expectedPlanId = expectedPlanId,
            options = (lastOptions[player.uniqueId] ?: initialOptions).validated(),
        )
        materialPicker.forgetFlow(player.uniqueId)
        sessions[player.uniqueId] = session
        dialogs.beginFlow(player)
        render(player, session, session.revision)
    }

    fun isOpen(playerId: UUID): Boolean = !closed && playerId in sessions

    /** Closes any native child screen and invalidates its callbacks. */
    fun close(playerId: UUID) {
        val hadSession = sessions.remove(playerId) != null
        materialPicker.forgetFlow(playerId)
        if (hadSession) org.bukkit.Bukkit.getPlayer(playerId)?.let(dialogs::close)
    }

    fun clearPlayer(playerId: UUID) {
        sessions.remove(playerId)
        lastOptions.remove(playerId)
        materialPicker.forgetFlow(playerId)
    }

    override fun close() {
        if (closed) return
        closed = true
        sessions.clear()
        lastOptions.clear()
    }

    private fun render(player: Player, session: Session, revision: Long) {
        if (!active(player, session, revision)) return
        val locale = BuilderLocalePolicy.localeTag(player)
        fun text(path: String, values: Map<String, Component> = emptyMap()) =
            messages.render("crown-selection.$path", locale, values)

        val densityButtons = BuilderCrownDensity.entries.map { density ->
            val densityLabel = messages.render("crown.labels.density.${density.name.lowercase()}", locale)
            val selected = session.options.density == density
            PaperDialogButton(
                id = PaperDialogActionId.of("density_${density.name.lowercase()}"),
                label = text(if (selected) "density-selected" else "density-option", mapOf("density" to densityLabel)),
                tooltip = text("density-help"),
                width = 190,
                onClick = { context ->
                    if (!validAction(player, session, revision)) return@PaperDialogButton
                    session.options = captureOptions(player, session, context).copy(density = density)
                    lastOptions[player.uniqueId] = session.options
                    session.revision++
                    render(player, session, session.revision)
                },
            )
        }
        val material = Material.matchMaterial(session.options.materialName) ?: Material.OAK_LEAVES
        val buttons = buildList {
            addAll(densityButtons)
            add(
                PaperDialogButton(
                    id = MATERIAL_ACTION,
                    label = text("material-button", mapOf("material" to BuilderMaterialPresentation.label(player, material))),
                    tooltip = text("material-help"),
                    width = 260,
                    onClick = { context ->
                        if (!validAction(player, session, revision)) return@PaperDialogButton
                        captureOptions(player, session, context)
                        materialPicker.joinCurrentFlow(player)
                        materialPicker.open(
                            player = player,
                            titleKey = "material-picker.crown-leaf-title",
                            materials = leafMaterials,
                            isCurrent = { current(player, session.selection, session.expectedPlanId) },
                            onSelected = selectedHandler@{ selected ->
                                if (!active(player, session, revision)) return@selectedHandler
                                session.options = session.options.copy(
                                    materialName = selected.name.lowercase(),
                                )
                                materialPicker.forgetFlow(player.uniqueId)
                                lastOptions[player.uniqueId] = session.options
                                session.revision++
                                render(player, session, session.revision)
                            },
                            beginFlow = false,
                            closeOnSelect = false,
                        )
                    },
                ),
            )
            add(
                PaperDialogButton(
                    id = PREVIEW_ACTION,
                    label = text("preview"),
                    tooltip = text("preview-help"),
                    width = 260,
                    onClick = { context ->
                        if (!validAction(player, session, revision)) return@PaperDialogButton
                        val requested = captureOptions(player, session, context)
                        session.options = requested
                        lastOptions[player.uniqueId] = requested
                        if (onPreview(player, session.selection, session.expectedPlanId, requested)) {
                            sessions.remove(player.uniqueId, session)
                            materialPicker.forgetFlow(player.uniqueId)
                            dialogs.close(player)
                        } else {
                            session.revision++
                            render(player, session, session.revision)
                        }
                    },
                ),
            )
        }
        val screen = PaperDialogScreen(
            id = SETUP_SCREEN_ID,
            title = text("title"),
            body = listOf(
                PaperDialogBody(
                    text(
                        "body",
                        mapOf(
                            "x" to Component.text(session.selection.sizeX),
                            "y" to Component.text(session.selection.sizeY),
                            "z" to Component.text(session.selection.sizeZ),
                            "thickness" to Component.text(session.options.thickness),
                        ),
                    ),
                ),
            ),
            numberInputs = listOf(
                PaperDialogNumberRangeInput(
                    id = THICKNESS_INPUT,
                    label = text("thickness-label"),
                    start = BuilderCrownSelectionOptions.MINIMUM_THICKNESS.toFloat(),
                    end = BuilderCrownSelectionOptions.MAXIMUM_THICKNESS.toFloat(),
                    initial = session.options.thickness.toFloat(),
                    step = 1f,
                    width = 360,
                ),
            ),
            buttons = buttons,
            exitButton = PaperDialogButton(
                id = CANCEL_ACTION,
                label = text("cancel"),
                width = 200,
                onClick = {},
            ),
            columns = 2,
        )
        dialogs.open(
            player,
            screen,
            reopen = { render(player, session, session.revision) },
            onDismiss = { dismiss(player.uniqueId, session) },
        )
    }

    private fun validAction(player: Player, session: Session, revision: Long): Boolean {
        if (active(player, session, revision)) return true
        if (sessions[player.uniqueId] === session) close(player.uniqueId)
        return false
    }

    private fun active(player: Player, session: Session, revision: Long): Boolean =
        !closed && sessions[player.uniqueId] === session && session.revision == revision &&
            current(player, session.selection, session.expectedPlanId)

    private fun current(player: Player, selection: BuilderSelection, expectedPlanId: UUID?): Boolean =
        runCatching { isCurrent(player, selection, expectedPlanId) }.getOrDefault(false)

    private fun captureOptions(
        player: Player,
        session: Session,
        context: PaperDialogClickContext,
    ): BuilderCrownSelectionOptions = session.options.copy(
        thickness = (context.number(THICKNESS_INPUT)?.roundToInt() ?: session.options.thickness).coerceIn(
            BuilderCrownSelectionOptions.MINIMUM_THICKNESS,
            BuilderCrownSelectionOptions.MAXIMUM_THICKNESS,
        ),
    ).validated().also { options ->
        session.options = options
        lastOptions[player.uniqueId] = options
    }

    private fun dismiss(playerId: UUID, session: Session) {
        if (sessions.remove(playerId, session)) materialPicker.forgetFlow(playerId)
    }

    private companion object {
        const val SETUP_SCREEN_ID = "builder.crown-selection"
        val THICKNESS_INPUT = PaperDialogInputId.of("thickness")
        val MATERIAL_ACTION = PaperDialogActionId.of("material")
        val PREVIEW_ACTION = PaperDialogActionId.of("preview")
        val CANCEL_ACTION = PaperDialogActionId.of("cancel")
    }
}
