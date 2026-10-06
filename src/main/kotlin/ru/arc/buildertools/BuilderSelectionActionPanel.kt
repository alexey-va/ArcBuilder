package ru.arc.buildertools

import net.kyori.adventure.text.Component
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
import kotlin.math.atan2
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
)

internal data class BuilderPanelSettings(
    val distance: Double = 2.4,
    val sideOffset: Double = 0.65,
    val heightOffset: Double = -0.25,
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
    const val STANDING_EYE_HEIGHT = 1.62
    const val STATUS_DISPLAY_WIDTH = 3.0
    const val SURFACE_CLEARANCE = 0.035
    const val BEARING_DEAD_ZONE = 0.75
    private const val EPSILON = 1e-8
    private const val DEGREES_PER_RADIAN = 180.0 / Math.PI
    private const val MAX_BEARING_TURN_PER_TICK = 30f

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

    fun anchor(
        eye: BuilderPanelPoint3,
        target: BuilderPanelPoint3,
        settings: BuilderPanelSettings,
        actionCount: Int,
        navigationCount: Int = 0,
        visibleBlockDistance: Double? = null,
        previousYaw: Float? = null,
    ): BuilderPanelAnchor {
        require(listOf(eye.x, eye.y, eye.z, target.x, target.y, target.z).all(Double::isFinite))
        val dx = target.x - eye.x
        val dz = target.z - eye.z
        val length = hypot(dx, dz)
        val targetDirection = if (length > EPSILON) {
            BuilderPanelPoint3(dx / length, 0.0, dz / length)
        } else {
            BuilderPanelPoint3(0.0, 0.0, 1.0)
        }
        val targetYaw = normalizeYaw((atan2(targetDirection.x, -targetDirection.z) * DEGREES_PER_RADIAN).toFloat())
        val yaw = previousYaw?.takeIf(Float::isFinite)?.let { prior ->
            if (length <= BEARING_DEAD_ZONE) normalizeYaw(prior)
            else turnToward(prior, targetYaw, MAX_BEARING_TURN_PER_TICK)
        } ?: targetYaw
        val yawRadians = Math.toRadians(yaw.toDouble())
        val direction = BuilderPanelPoint3(sin(yawRadians), 0.0, -cos(yawRadians))
        val rightX = cos(yawRadians)
        val rightZ = sin(yawRadians)
        val layout = layout(actionCount, settings, navigationCount)
        val obstructionLimit = visibleBlockDistance
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?.let { (it - 0.35).coerceAtLeast(0.25) }
            ?: Double.POSITIVE_INFINITY
        val requestedDistance = min(settings.distance, obstructionLimit)

        fun at(forward: Double) = BuilderPanelAnchor(
            BuilderPanelPoint3(
                eye.x + direction.x * forward + rightX * settings.sideOffset,
                eye.y + settings.heightOffset,
                eye.z + direction.z * forward + rightZ * settings.sideOffset,
            ),
            yaw,
        )

        fun reachable(candidate: BuilderPanelAnchor): Boolean = controlsReachable(eye, candidate, layout, settings)

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
        layout.buttons.maxOfOrNull { abs(it.x) + settings.buttonWidth / 2.0 } ?: 0.0,
    )

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
                    anchor.point.x + rightX * (button.x + xSide * settings.buttonWidth / 2.0),
                    anchor.point.y + button.y + ySide * settings.buttonHeight / 2.0,
                    anchor.point.z + rightZ * (button.x + xSide * settings.buttonWidth / 2.0),
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

    private fun turnToward(current: Float, target: Float, maximumStep: Float): Float {
        val delta = ((target - current + 540f) % 360f) - 180f
        return normalizeYaw(current + delta.coerceIn(-maximumStep, maximumStep))
    }
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
    private val displays: PaperPacketDisplays = PaperPacketDisplays(plugin),
) : Listener, AutoCloseable {
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
        val clickGate: BuilderPanelClickGate = BuilderPanelClickGate(),
        val buttons: MutableMap<BuilderPanelAction, Button> = linkedMapOf(),
        var actions: List<BuilderPanelAction> = emptyList(),
        var statusDisplay: PacketTextDisplay? = null,
        var lastStatus: Component? = null,
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

    /** Called by the selector's right-click path so a panel button cannot set a new selection corner. */
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
        val placement = placement(player, current.selection, layout)
        val panel = Panel(
            owner = player.uniqueId,
            world = player.world,
            context = current.context,
            selection = current.selection,
            generation = 0,
            anchor = placement.first,
            yaw = placement.second,
            positionedFrom = bodyPosition(player),
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
                current.selection,
                layout,
                forceSnap = panel.selection != current.selection || panel.actions != actions,
            )
        }
        if (panel.context != current.context || panel.selection != current.selection) {
            panel.context = current.context
            panel.selection = current.selection
            panel.generation++
        }
        if (!areaAvailable(player, panel.world, panel.anchor, panel.yaw, layout, checkLineOfSight = false)) {
            setPlacement(player, panel, current.selection, layout, forceSnap = true)
        }
        updateStatus(player, panel, current.status, layout)
        reconcileButtons(player, panel, actions, layout)
        panel.interpolationDurationForNextUpdate = INTERPOLATION_TICKS
    }

    private fun setPlacement(
        player: Player,
        panel: Panel,
        selection: BuilderSelection,
        layout: BuilderPanelLayout,
        forceSnap: Boolean,
    ) {
        val oldAnchor = BuilderPanelAnchor(
            BuilderPanelPoint3(panel.anchor.x, panel.anchor.y, panel.anchor.z), panel.yaw,
        )
        val (anchor, yaw) = placement(player, selection, layout, panel.yaw)
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

    private fun placement(
        player: Player,
        selection: BuilderSelection,
        layout: BuilderPanelLayout,
        previousYaw: Float? = null,
    ): Pair<Location, Float> {
        val feet = bodyPosition(player)
        val eye = Location(player.world, feet.x, feet.y + BuilderPanelGeometry.STANDING_EYE_HEIGHT, feet.z)
        val target = BuilderPanelPoint3(
            (selection.minX + selection.maxX + 1) / 2.0,
            eye.y,
            (selection.minZ + selection.maxZ + 1) / 2.0,
        )
        val ray = player.world.rayTraceBlocks(eye, direction(eye, target), settings.reach,
            FluidCollisionMode.NEVER, true)
        val blockDistance = ray?.hitPosition?.distance(eye.toVector())
        val candidateSettings = listOf(1.0, 0.82, 0.64, 0.46, 0.3).map { settings.copy(distance = settings.distance * it) }
        for (candidateSettingsForDistance in candidateSettings) {
            val placed = BuilderPanelGeometry.anchor(
                BuilderPanelPoint3(eye.x, eye.y, eye.z),
                target,
                candidateSettingsForDistance,
                layout.buttons.size,
                layout.navigationCount,
                blockDistance,
                previousYaw,
            )
            val anchor = Location(player.world, placed.point.x, placed.point.y, placed.point.z, placed.yaw, 0f)
            if (areaAvailable(player, player.world, anchor, placed.yaw, layout, checkLineOfSight = true)) {
                return anchor to placed.yaw
            }
        }
        throw PanelAreaUnavailable()
    }

    private fun direction(eye: Location, target: BuilderPanelPoint3): org.bukkit.util.Vector {
        val vector = org.bukkit.util.Vector(target.x - eye.x, target.y - eye.y, target.z - eye.z)
        return if (vector.lengthSquared() <= 1e-12) eye.direction else vector.normalize()
    }

    private fun areaAvailable(
        player: Player,
        world: World,
        anchor: Location,
        yaw: Float,
        layout: BuilderPanelLayout,
        checkLineOfSight: Boolean,
    ): Boolean {
        val geometryAnchor = BuilderPanelAnchor(
            BuilderPanelPoint3(anchor.x, anchor.y, anchor.z),
            yaw,
        )
        val panelBounds = BuilderPanelGeometry.panelBounds(geometryAnchor, layout, settings)
        if (!hasClearBlockBounds(player, world, panelBounds)) return false
        return !checkLineOfSight || hasLineOfSight(player, world, geometryAnchor, layout)
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
    ): Boolean {
        val feet = bodyPosition(player)
        val origin = Location(world, feet.x, feet.y + BuilderPanelGeometry.STANDING_EYE_HEIGHT, feet.z)
        val localTargets = buildList {
            add(BuilderPanelPoint3(0.0, layout.statusY, 0.0))
            layout.buttons.forEach { add(BuilderPanelPoint3(it.x, it.y, 0.0)) }
            val halfWidth = max(
                BuilderPanelGeometry.STATUS_DISPLAY_WIDTH / 2.0,
                layout.buttons.maxOfOrNull { abs(it.x) + settings.buttonWidth / 2.0 } ?: 0.0,
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
        val label = newLabel(player, labelLocation, settings.labelScale)
        return try {
            val hitbox = panel.world.spawn(hitboxLocation, Interaction::class.java) { entity ->
                entity.isVisibleByDefault = false
                entity.isPersistent = false
                entity.isInvulnerable = true
                entity.setGravity(false)
                entity.interactionWidth = settings.buttonWidth
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

    private fun newLabel(player: Player, location: Location, scale: Float): PacketTextDisplay =
        displays.spawnText(location, Component.empty()).apply {
            isVisibleByDefault = false
            billboard = Display.Billboard.FIXED
            brightness = Display.Brightness(15, 15)
            backgroundColor = PANEL_BACKGROUND
            isShadowed = true
            isSeeThrough = false
            alignment = org.bukkit.entity.TextDisplay.TextAlignment.CENTER
            lineWidth = if (scale == STATUS_SCALE) STATUS_LINE_WIDTH else BUTTON_LINE_WIDTH
            displayWidth = if (scale == STATUS_SCALE) STATUS_DISPLAY_WIDTH else settings.buttonWidth
            displayHeight = if (scale == STATUS_SCALE) STATUS_DISPLAY_HEIGHT else settings.buttonHeight
            viewRange = DISPLAY_VIEW_RANGE
            transformation = Transformation(Vector3f(), Quaternionf(), Vector3f(scale), Quaternionf())
            showTo(player)
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
                width = settings.buttonWidth.toDouble(),
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
        val owner = hitboxOwners[event.rightClicked.uniqueId] ?: return
        event.isCancelled = true
        val player = event.player
        if (player.uniqueId != owner || event.hand != EquipmentSlot.HAND) return
        val panel = panels[owner] ?: return
        if (panel.buttons.values.none { it.hitbox.uniqueId == event.rightClicked.uniqueId }) return
        val current = try {
            view(player)
        } catch (failure: Throwable) {
            rendererFailed(owner, failure)
            return
        } ?: run { clear(owner); return }
        val target = aimedButton(player, panel)
        if (!matches(panel, current)) {
            try {
                applyView(player, panel, current)
            } catch (_: PanelAreaUnavailable) {
                removeCurrentPanel(owner)
            } catch (failure: Throwable) {
                rendererFailed(owner, failure)
            }
            return
        }
        if (target == null || !inputCurrent(panel, target, current)) return
        if (panel.clickGate.accept()) onAction(player, target.action)
    }

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
        const val STATUS_DISPLAY_WIDTH = 3.0f
        const val STATUS_DISPLAY_HEIGHT = 0.55f
        const val DISPLAY_VIEW_RANGE = 0.15f
        val PANEL_BACKGROUND = Color.fromARGB(190, 15, 23, 30)
        val HOVER_BACKGROUND = Color.fromARGB(225, 76, 57, 24)
        val HOVER_GLOW = Color.fromRGB(255, 204, 64)

        fun chunkKey(x: Int, z: Int): Long = (x.toLong() and 0xffffffffL) or ((z.toLong() and 0xffffffffL) shl 32)
    }
}
