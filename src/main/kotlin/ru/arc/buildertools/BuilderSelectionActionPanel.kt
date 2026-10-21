package ru.arc.buildertools

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.arc.paper.actionpanel.ActionPanelAction
import ru.arc.paper.actionpanel.ActionPanelController
import ru.arc.paper.actionpanel.ActionPanelFollowSettings
import ru.arc.paper.actionpanel.ActionPanelFrame
import ru.arc.paper.actionpanel.ActionPanelGridSettings
import ru.arc.paper.actionpanel.ActionPanelInputProfile
import ru.arc.paper.actionpanel.ActionPanelLayouts
import ru.arc.paper.actionpanel.ActionPanelOptions
import ru.arc.paper.actionpanel.ActionPanelPlacement
import ru.arc.paper.actionpanel.ActionPanelPoint
import ru.arc.paper.actionpanel.PaperActionPanels
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.text.LocalizedMiniMessage
import java.util.UUID

internal enum class BuilderPanelAction {
    FILL, REPLACE, COPY, PASTE, PREVIOUS_PAGE, NEXT_PAGE, DECONSTRUCT, DISCONNECT, CROWN,
    DRAFT, CLEAR, UNDO, CONFIRM, CANCEL, ROTATE_LEFT, ROTATE_RIGHT;

    val localeKey: String get() = name.lowercase(java.util.Locale.ROOT).replace('_', '-')
}

internal data class BuilderPanelView(
    val context: Any,
    val selection: BuilderSelection,
    val status: Component,
    val actions: List<BuilderPanelAction>,
    val pagination: Component = Component.empty(),
)

internal data class BuilderPanelSettings(
    val distance: Double = 2.4,
    val sideOffset: Double = 0.0,
    val heightOffset: Double = 0.0,
    val rowSpacing: Double = 0.26,
    val columnSpacing: Double = 1.55,
    val buttonWidth: Float = 1.5f,
    val buttonHeight: Float = 0.23f,
    val labelScale: Float = 0.8f,
    val reach: Double = 4.0,
    val suppressSelectionGlow: Boolean = true,
    val obstacleMaxShift: Double = 1.25,
) {
    init {
        require(distance.isFinite() && distance > 0.0)
        require(sideOffset.isFinite() && heightOffset.isFinite())
        require(rowSpacing.isFinite() && rowSpacing > 0.0)
        require(columnSpacing.isFinite() && columnSpacing > 0.0)
        require(buttonWidth.isFinite() && buttonWidth in 0.2f..3.0f)
        require(buttonHeight.isFinite() && buttonHeight in 0.1f..1.0f)
        require(labelScale.isFinite() && labelScale in 0.1f..2.0f)
        require(reach.isFinite() && reach in 0.5..8.0)
        require(obstacleMaxShift.isFinite() && obstacleMaxShift in 0.0..1.5) {
            "selection-panel.obstacle-max-shift must be between 0 and 1.5 blocks"
        }
    }
}

/** Both values fence input: a caller token alone must not keep a changed selection clickable. */
internal data class BuilderPanelContext(val value: Any, val selection: BuilderSelection)

/** Builder owns eligibility and actions; Core owns placement, display handles, aiming and input. */
internal class BuilderSelectionActionPanel(
    plugin: Plugin,
    private val settings: BuilderPanelSettings,
    private val messages: LocalizedMiniMessage,
    private val view: (Player) -> BuilderPanelView?,
    private val onAction: (Player, BuilderPanelAction) -> Unit,
    private val displays: PaperPacketDisplays = PaperPacketDisplays(plugin, "selection-action-panel"),
    private val onSelectionGlowSuppression: (UUID, Boolean) -> Unit = { _, _ -> },
) : ActionPanelController<BuilderPanelContext, BuilderPanelAction>(), AutoCloseable {
    private val follow = ActionPanelFollowSettings(
        settings.distance, settings.sideOffset, settings.heightOffset, settings.obstacleMaxShift, 0.0,
    )
    private val grid = ActionPanelGridSettings(
        settings.rowSpacing, settings.columnSpacing, settings.buttonWidth, settings.buttonHeight, settings.labelScale,
    )
    private val options = ActionPanelOptions(
        ActionPanelInputProfile.ENTITY_BOTH, settings.reach, settings.suppressSelectionGlow,
    )
    private var closed = false
    private val panels = PaperActionPanels(plugin, displays, this)

    fun tick() = panels.tick()

    fun clear(playerId: UUID) = panels.clear(playerId)

    /** The selector calls this before changing a corner; a miss remains an ordinary tool gesture. */
    fun intercept(player: Player): Boolean = panels.intercept(player)

    fun isTargeted(player: Player): Boolean = panels.target(player) != null

    override fun frame(player: Player): ActionPanelFrame<BuilderPanelContext, BuilderPanelAction>? {
        if (closed) return null
        val current = view(player) ?: return null
        val selection = current.selection
        if (selection.worldId != player.world.uid) return null
        require(current.actions.size <= BuilderPanelAction.entries.size)
        val actions = current.actions.distinct()
        val locale = BuilderLocalePolicy.localeTag(player)
        return ActionPanelFrame(
            BuilderPanelContext(current.context, selection),
            ActionPanelPlacement.FollowTarget(
                selection.worldId,
                ActionPanelPoint(
                    (selection.minX.toDouble() + selection.maxX + 1.0) / 2.0,
                    (selection.minY.toDouble() + selection.maxY + 1.0) / 2.0,
                    (selection.minZ.toDouble() + selection.maxZ + 1.0) / 2.0,
                ),
                follow,
            ),
            ActionPanelLayouts.twoColumn(
                actions.map { action ->
                    ActionPanelAction(action, messages.render("selection-panel.actions.${action.localeKey}", locale))
                },
                current.status,
                current.pagination,
                navigationCount(actions),
                grid,
            ),
            true,
            options,
            null,
        )
    }

    override fun activate(player: Player, context: BuilderPanelContext, action: BuilderPanelAction) {
        if (closed) return
        val current = view(player) ?: return
        if (current.context != context.value || current.selection != context.selection ||
            current.selection.worldId != player.world.uid || action !in current.actions
        ) return
        onAction(player, action)
    }

    override fun guideSuppressionChanged(playerId: UUID, context: BuilderPanelContext, suppressed: Boolean) =
        onSelectionGlowSuppression(playerId, suppressed)

    private fun navigationCount(actions: List<BuilderPanelAction>): Int {
        if (actions.none { it == BuilderPanelAction.PREVIOUS_PAGE || it == BuilderPanelAction.NEXT_PAGE }) return 0
        require(actions.takeLast(2) == listOf(BuilderPanelAction.PREVIOUS_PAGE, BuilderPanelAction.NEXT_PAGE)) {
            "Builder selection page controls must occupy the final row in previous/next order"
        }
        return 2
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            panels.close()
        } finally {
            displays.close()
        }
    }
}
