package ru.arc.buildertools

import net.kyori.adventure.text.Component
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import org.bukkit.Color
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Display
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.text.LocalizedMiniMessage
import java.util.UUID
import java.util.logging.Level
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal enum class BuilderPanelAction {
    FILL, REPLACE, COPY, PASTE, PREVIOUS_PAGE, NEXT_PAGE, DECONSTRUCT, DISCONNECT,
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
    }
}

internal data class BuilderPanelPoint3(val x: Double, val y: Double, val z: Double) {
    fun distance(other: BuilderPanelPoint3): Double =
        sqrt((x - other.x) * (x - other.x) + (y - other.y) * (y - other.y) + (z - other.z) * (z - other.z))
}

internal data class BuilderPanelBounds(
    val minX: Double,
    val minY: Double,
    val minZ: Double,
    val maxX: Double,
    val maxY: Double,
    val maxZ: Double,
) {
    fun contains(point: BuilderPanelPoint3): Boolean =
        point.x in minX..maxX && point.y in minY..maxY && point.z in minZ..maxZ

    fun overlaps(other: BuilderPanelBounds): Boolean =
        minX < other.maxX && maxX > other.minX &&
            minY < other.maxY && maxY > other.minY &&
            minZ < other.maxZ && maxZ > other.minZ
}

internal data class BuilderPanelAnchor(val point: BuilderPanelPoint3, val yaw: Float)
internal data class BuilderPanelViewFrame(
    val eyeOffset: BuilderPanelPoint3,
    val direction: BuilderPanelPoint3,
    val yaw: Float,
)
internal data class BuilderPanelButtonOffset(val index: Int, val x: Double, val y: Double)
internal data class BuilderPanelControlPlane(
    val action: BuilderPanelAction,
    val center: BuilderPanelPoint3,
    val yaw: Double,
    val width: Double,
    val height: Double,
    val enabled: Boolean = true,
)
internal data class BuilderPanelLayout(
    val buttons: List<BuilderPanelButtonOffset>,
    val rows: Int,
    val statusY: Double,
    val navigationCount: Int,
)

internal object BuilderPanelGeometry {
    const val MAX_COLUMNS = 2
    const val STATUS_HEIGHT = 0.24
    const val STATUS_GAP = 0.04
    const val STATUS_DISPLAY_WIDTH = 3.0
    const val SURFACE_CLEARANCE = 0.035
    const val NAVIGATION_HITBOX_WIDTH = 0.45f
    private const val EPSILON = 1e-8

    fun layout(actionCount: Int, settings: BuilderPanelSettings, navigationCount: Int = 0): BuilderPanelLayout {
        require(actionCount in 0..BuilderPanelAction.entries.size)
        require(navigationCount == 0 || navigationCount == 2)
        require(navigationCount <= actionCount)
        if (actionCount == 0) return BuilderPanelLayout(emptyList(), 0, 0.0, 0)
        val bodyCount = actionCount - navigationCount
        val bodyRows = ceil(bodyCount.toDouble() / MAX_COLUMNS).toInt()
        if (navigationCount > 0) require(bodyCount <= 6) { "Navigation panels reserve six body slots" }
        val rows = if (navigationCount > 0) 4 else ceil(actionCount.toDouble() / MAX_COLUMNS).toInt()
        val buttons = List(actionCount) { index ->
            val isNavigation = navigationCount > 0 && index >= bodyCount
            val bodyIndex = index.coerceAtMost(bodyCount - 1)
            val activeBodyRow = if (bodyCount == 0) 0 else bodyIndex / MAX_COLUMNS
            val bodyStartRow = if (navigationCount > 0) (3 - bodyRows) / 2 else 0
            val row = when {
                isNavigation -> 3
                navigationCount > 0 -> bodyStartRow + activeBodyRow
                else -> index / MAX_COLUMNS
            }
            val column = when {
                isNavigation -> index - bodyCount
                else -> index % MAX_COLUMNS
            }
            val countInRow = when {
                isNavigation -> 2
                navigationCount > 0 -> min(MAX_COLUMNS, bodyCount - activeBodyRow * MAX_COLUMNS)
                else -> min(MAX_COLUMNS, actionCount - row * MAX_COLUMNS)
            }
            BuilderPanelButtonOffset(
                index = index,
                x = (column - (countInRow - 1) / 2.0) * settings.columnSpacing,
                y = ((rows - 1) / 2.0 - row) * settings.rowSpacing,
            )
        }
        val highestButtonTop = if (navigationCount == 2) {
            // Keep the header aligned when a later page has fewer body actions.
            ((rows - 1) / 2.0) * settings.rowSpacing + settings.buttonHeight / 2.0
        } else {
            buttons.maxOf(BuilderPanelButtonOffset::y) + settings.buttonHeight / 2.0
        }
        return BuilderPanelLayout(buttons, rows, highestButtonTop + STATUS_GAP + STATUS_HEIGHT / 2.0, navigationCount)
    }

    fun controlWidth(action: BuilderPanelAction, settings: BuilderPanelSettings): Float =
        if (action == BuilderPanelAction.PREVIOUS_PAGE || action == BuilderPanelAction.NEXT_PAGE) {
            NAVIGATION_HITBOX_WIDTH
        } else settings.buttonWidth

    fun controlWidth(layout: BuilderPanelLayout, index: Int, settings: BuilderPanelSettings): Float =
        if (layout.navigationCount == 2 && index >= layout.buttons.size - 2) NAVIGATION_HITBOX_WIDTH
        else settings.buttonWidth

    fun centerY(layout: BuilderPanelLayout, settings: BuilderPanelSettings): Double {
        val minimum = min(
            layout.statusY - STATUS_HEIGHT / 2.0,
            layout.buttons.minOfOrNull { it.y - settings.buttonHeight / 2.0 } ?: 0.0,
        )
        val maximum = max(
            layout.statusY + STATUS_HEIGHT / 2.0,
            layout.buttons.maxOfOrNull { it.y + settings.buttonHeight / 2.0 } ?: 0.0,
        )
        return (minimum + maximum) / 2.0
    }

    fun anchorOnRay(
        eye: BuilderPanelPoint3,
        direction: BuilderPanelPoint3,
        yaw: Float,
        settings: BuilderPanelSettings,
        layout: BuilderPanelLayout,
        visibleBlockDistance: Double? = null,
    ): BuilderPanelAnchor {
        require(listOf(eye.x, eye.y, eye.z, direction.x, direction.y, direction.z, yaw.toDouble()).all(Double::isFinite))
        val length = sqrt(direction.x * direction.x + direction.y * direction.y + direction.z * direction.z)
        require(length > EPSILON)
        val unit = BuilderPanelPoint3(direction.x / length, direction.y / length, direction.z / length)
        val normalizedYaw = normalizeYaw(yaw)
        val yawRadians = Math.toRadians(normalizedYaw.toDouble())
        val rightX = cos(yawRadians)
        val rightZ = sin(yawRadians)
        val obstructionLimit = visibleBlockDistance
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?.let { (it - 0.35).coerceAtLeast(0.25) }
            ?: Double.POSITIVE_INFINITY
        val requestedDistance = min(settings.distance, obstructionLimit)

        fun at(forward: Double): BuilderPanelAnchor {
            val centerX = eye.x + unit.x * forward
            val rayCenterY = eye.y + unit.y * forward
            val centerZ = eye.z + unit.z * forward
            return BuilderPanelAnchor(
                BuilderPanelPoint3(
                    centerX + rightX * settings.sideOffset,
                    rayCenterY - centerY(layout, settings) + settings.heightOffset,
                    centerZ + rightZ * settings.sideOffset,
                ),
                normalizedYaw,
            )
        }

        fun reachable(candidate: BuilderPanelAnchor) = controlsReachable(eye, candidate, layout, settings)
        val requested = at(requestedDistance)
        if (reachable(requested)) return requested
        var low = 0.0
        var high = requestedDistance
        repeat(28) {
            val middle = (low + high) / 2.0
            if (reachable(at(middle))) low = middle else high = middle
        }
        return at(low)
    }

    fun panelBounds(
        anchor: BuilderPanelAnchor,
        layout: BuilderPanelLayout,
        settings: BuilderPanelSettings,
        padding: Double = SURFACE_CLEARANCE,
    ): BuilderPanelBounds {
        require(padding.isFinite() && padding >= 0.0)
        val halfWidth = panelHalfWidth(layout, settings)
        val minY = min(
            layout.statusY - STATUS_HEIGHT / 2.0,
            layout.buttons.minOfOrNull { it.y - settings.buttonHeight / 2.0 } ?: 0.0,
        ) - padding
        val maxY = max(
            layout.statusY + STATUS_HEIGHT / 2.0,
            layout.buttons.maxOfOrNull { it.y + settings.buttonHeight / 2.0 } ?: 0.0,
        ) + padding
        val radians = Math.toRadians(anchor.yaw.toDouble())
        val rightX = cos(radians)
        val rightZ = sin(radians)
        val extentX = abs(rightX) * halfWidth + padding
        val extentZ = abs(rightZ) * halfWidth + padding
        return BuilderPanelBounds(
            anchor.point.x - extentX,
            anchor.point.y + minY,
            anchor.point.z - extentZ,
            anchor.point.x + extentX,
            anchor.point.y + maxY,
            anchor.point.z + extentZ,
        )
    }

    fun sweptBounds(
        from: BuilderPanelAnchor,
        to: BuilderPanelAnchor,
        layout: BuilderPanelLayout,
        settings: BuilderPanelSettings,
        padding: Double = SURFACE_CLEARANCE,
    ): BuilderPanelBounds {
        require(padding.isFinite() && padding >= 0.0)
        if (abs(((to.yaw - from.yaw + 540f) % 360f) - 180f) < 1e-5) {
            val first = panelBounds(from, layout, settings, padding)
            val last = panelBounds(to, layout, settings, padding)
            return BuilderPanelBounds(
                min(first.minX, last.minX), min(first.minY, last.minY), min(first.minZ, last.minZ),
                max(first.maxX, last.maxX), max(first.maxY, last.maxY), max(first.maxZ, last.maxZ),
            )
        }
        val radius = panelHalfWidth(layout, settings) + padding
        val minY = min(
            layout.statusY - STATUS_HEIGHT / 2.0,
            layout.buttons.minOfOrNull { it.y - settings.buttonHeight / 2.0 } ?: 0.0,
        ) - padding
        val maxY = max(
            layout.statusY + STATUS_HEIGHT / 2.0,
            layout.buttons.maxOfOrNull { it.y + settings.buttonHeight / 2.0 } ?: 0.0,
        ) + padding
        return BuilderPanelBounds(
            min(from.point.x, to.point.x) - radius,
            min(from.point.y, to.point.y) + minY,
            min(from.point.z, to.point.z) - radius,
            max(from.point.x, to.point.x) + radius,
            max(from.point.y, to.point.y) + maxY,
            max(from.point.z, to.point.z) + radius,
        )
    }

    private fun panelHalfWidth(layout: BuilderPanelLayout, settings: BuilderPanelSettings) = max(
        STATUS_DISPLAY_WIDTH / 2.0,
        layout.buttons.maxOfOrNull { abs(it.x) + controlWidth(layout, it.index, settings) / 2.0 } ?: 0.0,
    )

    /** Retain a safe offset near obstacles; restore distance only with extra clearance. */
    fun stableDistance(maximum: Double, previous: Double?, clear: (Double) -> Boolean): Double? {
        val retained = previous?.coerceAtMost(maximum)
        if (retained != null && clear(retained)) {
            val outward = min(maximum, retained + 0.06)
            val probe = min(maximum, outward + 0.12)
            return if (outward > retained && clear(probe) && clear(outward)) outward else retained
        }
        var blocked = maximum
        for (factor in listOf(1.0, 0.82, 0.64, 0.46, 0.3)) {
            val candidate = maximum * factor
            if (clear(candidate)) {
                if (factor == 1.0) return candidate
                var safe = candidate
                repeat(6) {
                    val middle = (safe + blocked) / 2.0
                    if (clear(middle)) safe = middle else blocked = middle
                }
                val inset = max(candidate, safe - 0.10)
                return if (clear(inset)) inset else candidate
            }
            blocked = candidate
        }
        return null
    }

    fun controlsReachable(
        eye: BuilderPanelPoint3,
        anchor: BuilderPanelAnchor,
        layout: BuilderPanelLayout,
        settings: BuilderPanelSettings,
    ): Boolean {
        val radians = Math.toRadians(anchor.yaw.toDouble())
        val rightX = cos(radians)
        val rightZ = sin(radians)
        return layout.buttons.all { button ->
            listOf(-1.0, 1.0).all { xSide -> listOf(-1.0, 1.0).all { ySide ->
                val corner = BuilderPanelPoint3(
                    anchor.point.x + rightX * (button.x + xSide * controlWidth(layout, button.index, settings) / 2.0),
                    anchor.point.y + button.y + ySide * settings.buttonHeight / 2.0,
                    anchor.point.z + rightZ * (button.x + xSide * controlWidth(layout, button.index, settings) / 2.0),
                )
                eye.distance(corner) <= settings.reach + 1e-6
            } }
        }
    }

    fun nearestTarget(
        origin: BuilderPanelPoint3,
        direction: BuilderPanelPoint3,
        candidates: Iterable<BuilderPanelControlPlane>,
        maxDistance: Double,
        blockDistance: Double? = null,
    ): BuilderPanelAction? {
        if (!origin.finite() || !direction.finite() || !maxDistance.isFinite() || maxDistance <= 0.0) return null
        if (blockDistance != null && (!blockDistance.isFinite() || blockDistance < 0.0)) return null
        val length = sqrt(direction.x * direction.x + direction.y * direction.y + direction.z * direction.z)
        if (!length.isFinite() || length <= EPSILON) return null
        val rayX = direction.x / length
        val rayY = direction.y / length
        val rayZ = direction.z / length
        return candidates.mapNotNull { candidate ->
            if (!candidate.enabled || !candidate.center.finite() || !candidate.yaw.isFinite() ||
                !candidate.width.isFinite() || candidate.width <= 0.0 ||
                !candidate.height.isFinite() || candidate.height <= 0.0
            ) return@mapNotNull null
            val radians = Math.toRadians(candidate.yaw)
            val rightX = cos(radians)
            val rightZ = sin(radians)
            val normalX = -rightZ
            val normalZ = rightX
            val denominator = rayX * normalX + rayZ * normalZ
            if (abs(denominator) <= EPSILON) return@mapNotNull null
            val distance = ((candidate.center.x - origin.x) * normalX +
                (candidate.center.z - origin.z) * normalZ) / denominator
            if (!distance.isFinite() || distance < 0.0 || distance > maxDistance ||
                (blockDistance != null && blockDistance <= distance + OCCLUSION_EPSILON)
            ) return@mapNotNull null
            val offsetX = ((origin.x + rayX * distance) - candidate.center.x) * rightX +
                ((origin.z + rayZ * distance) - candidate.center.z) * rightZ
            val offsetY = origin.y + rayY * distance - candidate.center.y
            if (abs(offsetX) > candidate.width / 2.0 || abs(offsetY) > candidate.height / 2.0) {
                return@mapNotNull null
            }
            candidate to distance
        }.minByOrNull { it.second }?.first?.action
    }

    private fun BuilderPanelPoint3.finite() = x.isFinite() && y.isFinite() && z.isFinite()
    private const val OCCLUSION_EPSILON = 1e-4

    private fun normalizeYaw(yaw: Float): Float = ((yaw % 360f) + 360f) % 360f
}

internal object BuilderPanelInputPolicy {
    fun accepts(
        renderedContext: Any,
        renderedSelection: BuilderSelection,
        renderedGeneration: Long,
        currentGeneration: Long,
        action: BuilderPanelAction,
        currentView: BuilderPanelView,
    ): Boolean = renderedGeneration == currentGeneration &&
        renderedContext == currentView.context && renderedSelection == currentView.selection &&
        action in currentView.actions
}

internal class BuilderPanelClickGate(private val nowNanos: () -> Long = System::nanoTime) {
    private var acceptedAt: Long? = null

    fun accept(): Boolean {
        val now = nowNanos()
        val previous = acceptedAt
        if (previous != null && now - previous < DEDUPLICATION_NANOS) return false
        acceptedAt = now
        return true
    }

    private companion object { const val DEDUPLICATION_NANOS = 250_000_000L }
}

/** Owns the viewer-private hologram and its bounded native click targets. */
internal class BuilderSelectionActionPanel(
    private val plugin: Plugin,
    private val settings: BuilderPanelSettings,
    private val messages: LocalizedMiniMessage,
    private val view: (Player) -> BuilderPanelView?,
    private val onAction: (Player, BuilderPanelAction) -> Unit,
    private val displays: PaperPacketDisplays = PaperPacketDisplays(plugin, "selection-action-panel"),
) : Listener, AutoCloseable {
    private data class Placement(val anchor: Location, val distance: Double)
    private data class Button(
        val action: BuilderPanelAction,
        val label: PacketTextDisplay,
        val hitbox: Interaction,
        var generation: Long,
        var lastLabel: Component? = null,
        var hovered: Boolean = false,
    )

    private data class Panel(
        val owner: UUID,
        val world: World,
        var context: Any,
        var selection: BuilderSelection,
        var generation: Long,
        var anchor: Location,
        var yaw: Float,
        var positionedFrom: Location,
        val viewFrame: BuilderPanelViewFrame,
        var placementDistance: Double = 2.4,
        val clickGate: BuilderPanelClickGate = BuilderPanelClickGate(),
        val buttons: MutableMap<BuilderPanelAction, Button> = linkedMapOf(),
        var actions: List<BuilderPanelAction> = emptyList(),
        var statusDisplay: PacketTextDisplay? = null,
        var lastStatus: Component? = null,
        var paginationDisplay: PacketTextDisplay? = null,
        var lastPagination: Component? = null,
        var interpolationDurationForNextUpdate: Int = INTERPOLATION_TICKS,
    )

    private val panels = mutableMapOf<UUID, Panel>()
    private val hitboxOwners = mutableMapOf<UUID, UUID>()
    private val rendererFailuresLogged = mutableSetOf<UUID>()
    private var closed = false

    init { plugin.server.pluginManager.registerEvents(this, plugin) }

    fun tick() {
        if (closed) return
        panels.keys.toList().forEach { id ->
            val player = plugin.server.getPlayer(id)
            if (player == null || !player.isOnline) {
                clear(id)
            } else {
                refresh(player)
            }
        }
        plugin.server.onlinePlayers.filter { it.uniqueId !in panels }.forEach(::refresh)
    }

    fun clear(player: UUID) {
        panels.remove(player)?.let(::removePanel)
        rendererFailuresLogged.remove(player)
    }

    /** Called by the selector's hand-input path so a panel button cannot set a new selection corner. */
    fun intercept(player: Player): Boolean {
        if (closed) return false
        val panel = panels[player.uniqueId] ?: return false
        val current = try {
            view(player)
        } catch (failure: Throwable) {
            rendererFailed(player.uniqueId, failure)
            return false
        } ?: run { clear(player.uniqueId); return false }
        val target = aimedButton(player, panel) ?: return false
        if (!matches(panel, current)) {
            try {
                applyView(player, panel, current)
            } catch (_: PanelAreaUnavailable) {
                removeCurrentPanel(player.uniqueId)
            } catch (failure: Throwable) {
                rendererFailed(player.uniqueId, failure)
            }
            return true
        }
        if (!inputCurrent(panel, target, current)) return true
        if (panel.clickGate.accept()) onAction(player, target.action)
        return true
    }

    private fun refresh(player: Player) {
        val current = try {
            view(player)
        } catch (failure: Throwable) {
            rendererFailed(player.uniqueId, failure)
            return
        }
        if (current == null || current.selection.worldId != player.world.uid) {
            clear(player.uniqueId)
            return
        }
        try {
            val panel = panels[player.uniqueId] ?: createPanel(player, current).also { panels[player.uniqueId] = it }
            applyView(player, panel, current)
            refreshHover(player, panel)
            rendererFailuresLogged.remove(player.uniqueId)
        } catch (_: PanelAreaUnavailable) {
            removeCurrentPanel(player.uniqueId)
        } catch (failure: Throwable) {
            rendererFailed(player.uniqueId, failure)
        }
    }

    private fun createPanel(player: Player, current: BuilderPanelView): Panel {
        val actions = current.actions.distinct()
        val layout = layout(actions)
        val viewFrame = captureViewFrame(player)
        val placement = placement(player, layout, viewFrame)
        val panel = Panel(
            owner = player.uniqueId,
            world = player.world,
            context = current.context,
            selection = current.selection,
            generation = 0,
            anchor = placement.anchor,
            yaw = placement.anchor.yaw,
            positionedFrom = bodyPosition(player),
            viewFrame = viewFrame,
            placementDistance = placement.distance,
            actions = actions,
        )
        try {
            panel.statusDisplay = newLabel(player, statusLocation(panel, layout), STATUS_SCALE)
            return panel
        } catch (failure: Throwable) {
            removePanel(panel)
            throw failure
        }
    }

    private fun applyView(player: Player, panel: Panel, current: BuilderPanelView) {
        require(current.selection.worldId == panel.world.uid && player.world.uid == panel.world.uid)
        require(current.actions.size <= BuilderPanelAction.entries.size)
        val actions = current.actions.distinct()
        val layout = layout(actions)
        val shouldReposition = panel.selection != current.selection || panel.actions != actions ||
            !sameBodyPosition(bodyPosition(player), panel.positionedFrom)
        if (shouldReposition) {
            setPlacement(
                player,
                panel,
                layout,
                forceSnap = panel.selection != current.selection || panel.actions != actions,
            )
        }
        if (panel.context != current.context || panel.selection != current.selection) {
            panel.context = current.context
            panel.selection = current.selection
            panel.generation++
        }
        if (!areaAvailable(player, panel.world, panel.anchor, panel.yaw, layout, panel.viewFrame, checkLineOfSight = false)) {
            setPlacement(player, panel, layout, forceSnap = true)
        }
        updateStatus(player, panel, current.status, layout)
        updatePagination(player, panel, current.pagination, layout)
        reconcileButtons(player, panel, actions, layout)
        panel.interpolationDurationForNextUpdate = INTERPOLATION_TICKS
    }

    private fun setPlacement(
        player: Player,
        panel: Panel,
        layout: BuilderPanelLayout,
        forceSnap: Boolean,
    ) {
        val oldAnchor = BuilderPanelAnchor(
            BuilderPanelPoint3(panel.anchor.x, panel.anchor.y, panel.anchor.z), panel.yaw,
        )
        val placed = placement(player, layout, panel.viewFrame, panel.placementDistance)
        val anchor = placed.anchor
        val yaw = anchor.yaw
        val newAnchor = BuilderPanelAnchor(BuilderPanelPoint3(anchor.x, anchor.y, anchor.z), yaw)
        val angleDelta = abs(((yaw - panel.yaw + 540f) % 360f) - 180f)
        val canInterpolate = !forceSnap && oldAnchor.point.distance(newAnchor.point) <= MAX_SMOOTH_STEP &&
            angleDelta <= MAX_SMOOTH_TURN &&
            hasClearBlockBounds(
                player,
                panel.world,
                BuilderPanelGeometry.sweptBounds(oldAnchor, newAnchor, layout, settings),
            )
        panel.interpolationDurationForNextUpdate = if (canInterpolate) INTERPOLATION_TICKS else 0
        panel.anchor = anchor
        panel.yaw = yaw
        panel.positionedFrom = bodyPosition(player)
        panel.placementDistance = placed.distance
    }

    private fun updatePagination(
        player: Player,
        panel: Panel,
        pagination: Component,
        layout: BuilderPanelLayout,
    ) {
        if (layout.navigationCount != 2 || pagination == Component.empty()) {
            panel.paginationDisplay?.remove()
            panel.paginationDisplay = null
            panel.lastPagination = null
            return
        }
        val navigationY = layout.buttons.last().y - settings.buttonHeight / 2.0 + LABEL_Y_OFFSET
        val location = panel.anchor.clone().add(0.0, navigationY, 0.0).apply {
            yaw = panel.yaw
            pitch = 0f
        }
        val display = panel.paginationDisplay ?: newPaginationLabel(player, location, pagination).also {
            panel.paginationDisplay = it
        }
        if (display.location != location) {
            teleportSmooth(display, location, panel.interpolationDurationForNextUpdate)
        }
        if (panel.lastPagination != pagination) display.text(pagination)
        display.showTo(player)
        panel.lastPagination = pagination
    }

    private fun layout(actions: List<BuilderPanelAction>): BuilderPanelLayout =
        BuilderPanelGeometry.layout(actions.size, settings, navigationCount(actions))

    private fun navigationCount(actions: List<BuilderPanelAction>): Int {
        val hasNavigation = actions.any { it == BuilderPanelAction.PREVIOUS_PAGE || it == BuilderPanelAction.NEXT_PAGE }
        if (!hasNavigation) return 0
        require(actions.takeLast(2) == listOf(BuilderPanelAction.PREVIOUS_PAGE, BuilderPanelAction.NEXT_PAGE)) {
            "Builder selection page controls must occupy the final row in previous/next order"
        }
        return 2
    }

    private fun bodyPosition(player: Player): Location = player.location.clone().apply {
        yaw = 0f
        pitch = 0f
    }

    private fun sameBodyPosition(first: Location, second: Location): Boolean =
        first.world?.uid == second.world?.uid && first.x == second.x && first.y == second.y && first.z == second.z

    private fun normalizeYaw(yaw: Float): Float = ((yaw % 360f) + 360f) % 360f

    private fun captureViewFrame(player: Player): BuilderPanelViewFrame {
        val feet = bodyPosition(player)
        val eye = player.eyeLocation
        val look = eye.direction.clone().normalize()
        val horizontalLength = hypot(look.x, look.z)
        val yaw = if (horizontalLength > 1e-8) {
            normalizeYaw(Math.toDegrees(kotlin.math.atan2(look.x, -look.z)).toFloat())
        } else {
            normalizeYaw(eye.yaw)
        }
        return BuilderPanelViewFrame(
            eyeOffset = BuilderPanelPoint3(eye.x - feet.x, eye.y - feet.y, eye.z - feet.z),
            direction = BuilderPanelPoint3(look.x, look.y, look.z),
            yaw = yaw,
        )
    }

    private fun eyeLocation(player: Player, frame: BuilderPanelViewFrame): Location {
        val feet = bodyPosition(player)
        return Location(
            player.world,
            feet.x + frame.eyeOffset.x,
            feet.y + frame.eyeOffset.y,
            feet.z + frame.eyeOffset.z,
        )
    }

    private fun placement(
        player: Player,
        layout: BuilderPanelLayout,
        frame: BuilderPanelViewFrame,
        previousDistance: Double? = null,
    ): Placement {
        val eye = eyeLocation(player, frame)
        val direction = org.bukkit.util.Vector(frame.direction.x, frame.direction.y, frame.direction.z)
        val ray = player.world.rayTraceBlocks(eye, direction, settings.reach,
            FluidCollisionMode.NEVER, true)
        val blockDistance = ray?.hitPosition?.distance(eye.toVector())
        val maximum = min(settings.distance, blockDistance?.let { max(0.25, it - 0.35) } ?: settings.distance)
        fun anchorAt(distance: Double): Location {
            val placed = BuilderPanelGeometry.anchorOnRay(
                BuilderPanelPoint3(eye.x, eye.y, eye.z),
                frame.direction,
                frame.yaw,
                settings.copy(distance = distance),
                layout,
            )
            return Location(player.world, placed.point.x, placed.point.y, placed.point.z, placed.yaw, 0f)
        }
        val distance = BuilderPanelGeometry.stableDistance(maximum, previousDistance) { candidate ->
            val anchor = anchorAt(candidate)
            areaAvailable(player, player.world, anchor, anchor.yaw, layout, frame, checkLineOfSight = true)
        } ?: throw PanelAreaUnavailable()
        return Placement(anchorAt(distance), distance)
    }

    private fun areaAvailable(
        player: Player,
        world: World,
        anchor: Location,
        yaw: Float,
        layout: BuilderPanelLayout,
        frame: BuilderPanelViewFrame,
        checkLineOfSight: Boolean,
    ): Boolean {
        val geometryAnchor = BuilderPanelAnchor(
            BuilderPanelPoint3(anchor.x, anchor.y, anchor.z),
            yaw,
        )
        val panelBounds = BuilderPanelGeometry.panelBounds(geometryAnchor, layout, settings)
        if (!hasClearBlockBounds(player, world, panelBounds)) return false
        return !checkLineOfSight || hasLineOfSight(player, world, geometryAnchor, layout, frame)
    }

    private fun hasClearBlockBounds(player: Player, world: World, bounds: BuilderPanelBounds): Boolean {
        val paddedBounds = org.bukkit.util.BoundingBox(bounds.minX, bounds.minY, bounds.minZ,
            bounds.maxX, bounds.maxY, bounds.maxZ)
        val minBlockX = floor(bounds.minX).toInt()
        val minBlockY = max(floor(bounds.minY).toInt(), world.minHeight)
        val minBlockZ = floor(bounds.minZ).toInt()
        val maxBlockX = floor(bounds.maxX).toInt()
        val maxBlockY = min(floor(bounds.maxY).toInt(), world.maxHeight - 1)
        val maxBlockZ = floor(bounds.maxZ).toInt()
        if (minBlockY > maxBlockY) return false
        val sentChunks = runCatching { player.sentChunkKeys }.getOrNull() ?: return false
        for (chunkX in (minBlockX shr 4)..(maxBlockX shr 4)) {
            for (chunkZ in (minBlockZ shr 4)..(maxBlockZ shr 4)) {
                if (!world.isChunkLoaded(chunkX, chunkZ) || chunkKey(chunkX, chunkZ) !in sentChunks) return false
            }
        }
        for (blockX in minBlockX..maxBlockX) {
            for (blockY in minBlockY..maxBlockY) {
                for (blockZ in minBlockZ..maxBlockZ) {
                    val block = world.getBlockAt(blockX, blockY, blockZ)
                    if (!block.isPassable && block.boundingBox.overlaps(paddedBounds)) return false
                }
            }
        }
        return true
    }

    private fun hasLineOfSight(
        player: Player,
        world: World,
        anchor: BuilderPanelAnchor,
        layout: BuilderPanelLayout,
        frame: BuilderPanelViewFrame,
    ): Boolean {
        val origin = eyeLocation(player, frame)
        val localTargets = buildList {
            add(BuilderPanelPoint3(0.0, layout.statusY, 0.0))
            layout.buttons.forEach { add(BuilderPanelPoint3(it.x, it.y, 0.0)) }
            val halfWidth = max(
                BuilderPanelGeometry.STATUS_DISPLAY_WIDTH / 2.0,
                layout.buttons.maxOfOrNull {
                    abs(it.x) + BuilderPanelGeometry.controlWidth(layout, it.index, settings) / 2.0
                } ?: 0.0,
            )
            val minY = min(
                layout.statusY - BuilderPanelGeometry.STATUS_HEIGHT / 2.0,
                layout.buttons.minOfOrNull { it.y - settings.buttonHeight / 2.0 } ?: 0.0,
            )
            val maxY = max(
                layout.statusY + BuilderPanelGeometry.STATUS_HEIGHT / 2.0,
                layout.buttons.maxOfOrNull { it.y + settings.buttonHeight / 2.0 } ?: 0.0,
            )
            listOf(-halfWidth, halfWidth).forEach { x ->
                listOf(minY, maxY).forEach { y -> add(BuilderPanelPoint3(x, y, 0.0)) }
            }
        }
        val radians = Math.toRadians(anchor.yaw.toDouble())
        val rightX = cos(radians)
        val rightZ = sin(radians)
        for (target in localTargets) {
            val targetX = anchor.point.x + rightX * target.x
            val targetY = anchor.point.y + target.y
            val targetZ = anchor.point.z + rightZ * target.x
            val vector = org.bukkit.util.Vector(targetX - origin.x, targetY - origin.y, targetZ - origin.z)
            val distance = vector.length()
            if (distance <= BuilderPanelGeometry.SURFACE_CLEARANCE) return false
            val hit = world.rayTraceBlocks(
                origin,
                vector.normalize(),
                distance - BuilderPanelGeometry.SURFACE_CLEARANCE,
                FluidCollisionMode.NEVER,
                true,
            )
            if (hit != null) return false
        }
        return true
    }

    private fun statusLocation(panel: Panel, layout: BuilderPanelLayout): Location {
        val y = layout.statusY - BuilderPanelGeometry.STATUS_HEIGHT / 2.0
        return panel.anchor.clone().add(0.0, y, 0.0).apply { yaw = panel.yaw; pitch = 0f }
    }

    private fun controlCenter(panel: Panel, offset: BuilderPanelButtonOffset): Location {
        val radians = Math.toRadians(panel.yaw.toDouble())
        return panel.anchor.clone().add(cos(radians) * offset.x, offset.y,
            sin(radians) * offset.x).apply { yaw = panel.yaw; pitch = 0f }
    }

    private fun buttonLocation(panel: Panel, offset: BuilderPanelButtonOffset): Location =
        controlCenter(panel, offset).add(0.0, -settings.buttonHeight / 2.0 + LABEL_Y_OFFSET, 0.0)

    private fun hitboxLocation(panel: Panel, offset: BuilderPanelButtonOffset): Location =
        controlCenter(panel, offset).subtract(0.0, settings.buttonHeight / 2.0, 0.0)

    private fun updateStatus(player: Player, panel: Panel, status: Component, layout: BuilderPanelLayout) {
        val display = checkNotNull(panel.statusDisplay)
        val location = statusLocation(panel, layout)
        if (display.location != location) teleportSmooth(display, location, panel.interpolationDurationForNextUpdate)
        if (panel.lastStatus == status) return
        display.text(status)
        if (status == Component.empty()) display.hideFrom(player) else display.showTo(player)
        panel.lastStatus = status
    }

    private fun reconcileButtons(
        player: Player,
        panel: Panel,
        actions: List<BuilderPanelAction>,
        layout: BuilderPanelLayout,
    ) {
        val wanted = actions.toSet()
        panel.buttons.keys.filterNot(wanted::contains).toList().forEach { action ->
            panel.buttons.remove(action)?.let { removeButton(player, it) }
        }
        val locale = BuilderLocalePolicy.localeTag(player)
        layout.buttons.forEach { offset ->
            val action = actions[offset.index]
            val label = messages.render("selection-panel.actions.${action.localeKey}", locale)
            val labelLocation = buttonLocation(panel, offset)
            val hitboxLocation = hitboxLocation(panel, offset)
            val button = panel.buttons[action] ?: createButton(player, panel, action, labelLocation, hitboxLocation).also {
                panel.buttons[action] = it
                hitboxOwners[it.hitbox.uniqueId] = player.uniqueId
            }
            if (button.label.location != labelLocation) {
                teleportSmooth(button.label, labelLocation, panel.interpolationDurationForNextUpdate)
            }
            if (button.hitbox.location != hitboxLocation) {
                check(button.hitbox.teleport(hitboxLocation)) { "Builder selection panel Interaction teleport failed" }
            }
            if (button.lastLabel != label) {
                button.label.text(label)
                button.lastLabel = label
            }
            button.generation = panel.generation
            button.label.showTo(player)
            player.showEntity(plugin, button.hitbox)
        }
        panel.actions = actions
    }

    private fun teleportSmooth(display: PacketTextDisplay, location: Location, duration: Int) {
        display.teleportDuration = duration
        display.teleport(location)
    }

    private fun createButton(
        player: Player,
        panel: Panel,
        action: BuilderPanelAction,
        labelLocation: Location,
        hitboxLocation: Location,
    ): Button {
        val width = BuilderPanelGeometry.controlWidth(action, settings)
        val label = newLabel(player, labelLocation, settings.labelScale, width)
        return try {
            val hitbox = panel.world.spawn(hitboxLocation, Interaction::class.java) { entity ->
                entity.isVisibleByDefault = false
                entity.isPersistent = false
                entity.isInvulnerable = true
                entity.setGravity(false)
                entity.interactionWidth = width
                entity.interactionHeight = settings.buttonHeight
                entity.isResponsive = true
                player.showEntity(plugin, entity)
            }
            Button(action, label, hitbox, panel.generation)
        } catch (failure: Throwable) {
            label.remove()
            throw failure
        }
    }

    private fun newLabel(
        player: Player,
        location: Location,
        scale: Float,
        width: Float = settings.buttonWidth,
    ): PacketTextDisplay =
        displays.spawnText(location, Component.empty()).apply {
            isVisibleByDefault = false
            billboard = Display.Billboard.FIXED
            brightness = Display.Brightness(15, 15)
            backgroundColor = PANEL_BACKGROUND
            isShadowed = true
            isSeeThrough = false
            alignment = org.bukkit.entity.TextDisplay.TextAlignment.CENTER
            lineWidth = if (scale == STATUS_SCALE) STATUS_LINE_WIDTH else BUTTON_LINE_WIDTH
            displayWidth = if (scale == STATUS_SCALE) STATUS_DISPLAY_WIDTH else width
            displayHeight = if (scale == STATUS_SCALE) STATUS_DISPLAY_HEIGHT else settings.buttonHeight
            viewRange = DISPLAY_VIEW_RANGE
            transformation = Transformation(Vector3f(), Quaternionf(), Vector3f(scale), Quaternionf())
            showTo(player)
        }

    private fun newPaginationLabel(
        player: Player,
        location: Location,
        pagination: Component,
    ): PacketTextDisplay = newLabel(player, location, STATUS_SCALE).apply {
        lineWidth = PAGINATION_LINE_WIDTH
        displayWidth = PAGINATION_DISPLAY_WIDTH
        displayHeight = PAGINATION_DISPLAY_HEIGHT
        text(pagination)
    }

    private fun refreshHover(player: Player, panel: Panel) {
        val selected = aimedButton(player, panel)
        panel.buttons.values.forEach { button ->
            val hovered = button === selected
            if (button.hovered == hovered || !button.label.isValid) return@forEach
            button.hovered = hovered
            button.label.isGlowing = hovered
            button.label.glowColorOverride = if (hovered) HOVER_GLOW else null
            button.label.backgroundColor = if (hovered) HOVER_BACKGROUND else PANEL_BACKGROUND
        }
    }

    /** Uses the visible button planes so hover and both click paths agree exactly. */
    private fun aimedButton(player: Player, panel: Panel): Button? {
        if (!player.isOnline || player.world != panel.world || player.uniqueId != panel.owner) return null
        val candidates = panel.buttons.values.filter { button ->
            button.action in panel.actions && button.generation == panel.generation &&
                button.hitbox.isValid && button.label.isValid && button.hitbox.world == player.world
        }
        if (candidates.isEmpty()) return null
        val eye = player.eyeLocation
        val direction = eye.direction
        val blockDistance = player.world.rayTraceBlocks(eye, direction, settings.reach,
            FluidCollisionMode.NEVER, true)?.hitPosition?.distance(eye.toVector())
        val layout = layout(panel.actions)
        val offsets = layout.buttons.associateBy(BuilderPanelButtonOffset::index)
        val byAction = candidates.associateBy(Button::action)
        val planes = panel.actions.mapIndexedNotNull { index, action ->
            val button = byAction[action] ?: return@mapIndexedNotNull null
            val offset = offsets[index] ?: return@mapIndexedNotNull null
            val center = controlCenter(panel, offset)
            BuilderPanelControlPlane(
                action = action,
                center = BuilderPanelPoint3(center.x, center.y, center.z),
                yaw = panel.yaw.toDouble(),
                width = BuilderPanelGeometry.controlWidth(action, settings).toDouble(),
                height = settings.buttonHeight.toDouble(),
                enabled = eye.distanceSquared(button.hitbox.location) <= settings.reach * settings.reach,
            )
        }
        val action = BuilderPanelGeometry.nearestTarget(
            BuilderPanelPoint3(eye.x, eye.y, eye.z),
            BuilderPanelPoint3(direction.x, direction.y, direction.z),
            planes,
            settings.reach,
            blockDistance,
        ) ?: return null
        return byAction[action]
    }

    private fun matches(panel: Panel, current: BuilderPanelView): Boolean =
        panel.context == current.context && panel.selection == current.selection

    private fun inputCurrent(panel: Panel, button: Button, current: BuilderPanelView): Boolean =
        panel.buttons[button.action] === button && button.action in panel.actions &&
            BuilderPanelInputPolicy.accepts(
                panel.context, panel.selection, button.generation, panel.generation, button.action, current,
            )

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onClick(event: PlayerInteractEntityEvent) = click(event)

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onClickAt(event: PlayerInteractAtEntityEvent) = click(event)

    private fun click(event: PlayerInteractEntityEvent) {
        val hitboxId = event.rightClicked.uniqueId
        val owner = hitboxOwners[hitboxId] ?: return
        event.isCancelled = true
        val player = event.player
        if (player.uniqueId != owner || event.hand != EquipmentSlot.HAND) return
        if (!ownsHitbox(owner, hitboxId)) return
        intercept(player)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onPrePlayerAttack(event: PrePlayerAttackEntityEvent) {
        val hitboxId = event.attacked.uniqueId
        val owner = hitboxOwners[hitboxId] ?: return
        val preserveExternalCancellation = event.willAttack() && event.isCancelled
        event.isCancelled = true
        val player = event.player
        if (preserveExternalCancellation || player.uniqueId != owner || !ownsHitbox(owner, hitboxId)) return
        intercept(player)
    }

    private fun ownsHitbox(owner: UUID, hitboxId: UUID): Boolean =
        hitboxOwners[hitboxId] == owner &&
            panels[owner]?.buttons?.values?.any { it.hitbox.uniqueId == hitboxId } == true

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onControlDamage(event: EntityDamageByEntityEvent) {
        if (event.entity.uniqueId in hitboxOwners) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) = clear(event.player.uniqueId)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    fun onTeleport(event: PlayerTeleportEvent) = clear(event.player.uniqueId)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) = clear(event.player.uniqueId)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRespawn(event: PlayerRespawnEvent) = clear(event.player.uniqueId)

    private fun removeButton(player: Player?, button: Button) {
        hitboxOwners.remove(button.hitbox.uniqueId)
        button.label.remove()
        if (player != null) runCatching { player.hideEntity(plugin, button.hitbox) }
        button.hitbox.remove()
    }

    private fun removePanel(panel: Panel) {
        val player = plugin.server.getPlayer(panel.owner)
        panel.buttons.values.toList().forEach { removeButton(player, it) }
        panel.buttons.clear()
        panel.statusDisplay?.remove()
        panel.statusDisplay = null
        panel.paginationDisplay?.remove()
        panel.paginationDisplay = null
    }

    private fun rendererFailed(id: UUID, failure: Throwable) {
        removeCurrentPanel(id)
        if (rendererFailuresLogged.add(id)) {
            plugin.logger.log(Level.WARNING, "Builder selection action panel renderer failed for $id", failure)
        }
    }

    private fun removeCurrentPanel(id: UUID) {
        panels.remove(id)?.let(::removePanel)
    }

    override fun close() {
        if (closed) return
        closed = true
        HandlerList.unregisterAll(this)
        panels.values.toList().forEach(::removePanel)
        panels.clear()
        hitboxOwners.clear()
        rendererFailuresLogged.clear()
        displays.close()
    }

    private class PanelAreaUnavailable : RuntimeException(null, null, false, false)

    private companion object {
        const val LABEL_Y_OFFSET = 0.025
        const val INTERPOLATION_TICKS = 2
        const val MAX_SMOOTH_STEP = 1.0
        const val MAX_SMOOTH_TURN = 30f
        const val STATUS_SCALE = 0.48f
        const val STATUS_LINE_WIDTH = 240
        const val BUTTON_LINE_WIDTH = 190
        const val PAGINATION_LINE_WIDTH = 80
        const val PAGINATION_DISPLAY_WIDTH = 1.0f
        const val PAGINATION_DISPLAY_HEIGHT = 0.18f
        const val STATUS_DISPLAY_WIDTH = 3.0f
        const val STATUS_DISPLAY_HEIGHT = 0.55f
        const val DISPLAY_VIEW_RANGE = 0.15f
        val PANEL_BACKGROUND = Color.fromARGB(190, 15, 23, 30)
        val HOVER_BACKGROUND = Color.fromARGB(225, 76, 57, 24)
        val HOVER_GLOW = Color.fromRGB(255, 204, 64)

        fun chunkKey(x: Int, z: Int): Long = (x.toLong() and 0xffffffffL) or ((z.toLong() and 0xffffffffL) shl 32)
    }
}
