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
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal enum class BuilderPanelAction {
    FILL, REPLACE, COPY, PASTE, MORE, BACK, DECONSTRUCT, DISCONNECT,
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
    val rowSpacing: Double = 0.32,
    val columnSpacing: Double = 1.25,
    val buttonWidth: Float = 1.05f,
    val buttonHeight: Float = 0.23f,
    val labelScale: Float = 0.55f,
    val reach: Double = 4.0,
    val repositionDistance: Double = 3.0,
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
        require(repositionDistance.isFinite() && repositionDistance > 0.0)
    }
}

internal data class BuilderPanelPoint3(val x: Double, val y: Double, val z: Double) {
    fun distance(other: BuilderPanelPoint3): Double =
        sqrt((x - other.x) * (x - other.x) + (y - other.y) * (y - other.y) + (z - other.z) * (z - other.z))
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
)

internal object BuilderPanelGeometry {
    const val MAX_COLUMNS = 2
    const val STATUS_HEIGHT = 0.42
    const val STATUS_GAP = 0.14
    private const val EPSILON = 1e-8
    private const val DEGREES_PER_RADIAN = 180.0 / Math.PI

    fun layout(actionCount: Int, settings: BuilderPanelSettings): BuilderPanelLayout {
        require(actionCount in 0..BuilderPanelAction.entries.size)
        if (actionCount == 0) return BuilderPanelLayout(emptyList(), 0, 0.0)
        val rows = ceil(actionCount.toDouble() / MAX_COLUMNS).toInt()
        val buttons = List(actionCount) { index ->
            val row = index / MAX_COLUMNS
            val column = index % MAX_COLUMNS
            val countInRow = min(MAX_COLUMNS, actionCount - row * MAX_COLUMNS)
            BuilderPanelButtonOffset(
                index = index,
                x = (column - (countInRow - 1) / 2.0) * settings.columnSpacing,
                y = ((rows - 1) / 2.0 - row) * settings.rowSpacing,
            )
        }
        val highestButtonTop = buttons.maxOf(BuilderPanelButtonOffset::y) + settings.buttonHeight / 2.0
        return BuilderPanelLayout(buttons, rows, highestButtonTop + STATUS_GAP + STATUS_HEIGHT / 2.0)
    }

    fun anchor(
        eye: BuilderPanelPoint3,
        target: BuilderPanelPoint3,
        cameraYaw: Float,
        settings: BuilderPanelSettings,
        actionCount: Int,
        visibleBlockDistance: Double? = null,
    ): BuilderPanelAnchor {
        require(listOf(eye.x, eye.y, eye.z, target.x, target.y, target.z, cameraYaw.toDouble()).all(Double::isFinite))
        val dx = target.x - eye.x
        val dz = target.z - eye.z
        val length = hypot(dx, dz)
        val cameraRadians = Math.toRadians(cameraYaw.toDouble())
        val direction = if (length > EPSILON) {
            BuilderPanelPoint3(dx / length, 0.0, dz / length)
        } else {
            BuilderPanelPoint3(-sin(cameraRadians), 0.0, cos(cameraRadians))
        }
        val yaw = normalizeYaw((atan2(direction.x, -direction.z) * DEGREES_PER_RADIAN).toFloat())
        val yawRadians = Math.toRadians(yaw.toDouble())
        val rightX = cos(yawRadians)
        val rightZ = sin(yawRadians)
        val cameraRightX = cos(cameraRadians)
        val cameraRightZ = sin(cameraRadians)
        val layout = layout(actionCount, settings)
        val obstructionLimit = visibleBlockDistance
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?.let { (it - 0.35).coerceAtLeast(0.25) }
            ?: Double.POSITIVE_INFINITY
        val requestedDistance = min(settings.distance, obstructionLimit)

        fun at(forward: Double) = BuilderPanelAnchor(
            BuilderPanelPoint3(
                eye.x + direction.x * forward + cameraRightX * settings.sideOffset,
                eye.y + settings.heightOffset,
                eye.z + direction.z * forward + cameraRightZ * settings.sideOffset,
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
        val placement = placement(player, current.selection, current.actions.size)
        val panel = Panel(
            owner = player.uniqueId,
            world = player.world,
            context = current.context,
            selection = current.selection,
            generation = 0,
            anchor = placement.first,
            yaw = placement.second,
            positionedFrom = player.location,
        )
        try {
            if (!areaAvailable(player, panel, current.actions.size)) {
                throw PanelAreaUnavailable()
            }
            panel.statusDisplay = newLabel(player, statusLocation(panel, current.actions.size), STATUS_SCALE)
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
        if (panel.selection != current.selection || movedBeyondAnchor(player, panel)) {
            val (anchor, yaw) = placement(player, current.selection, actions.size)
            panel.anchor = anchor
            panel.yaw = yaw
            panel.positionedFrom = player.location
        }
        if (panel.context != current.context || panel.selection != current.selection) {
            panel.context = current.context
            panel.selection = current.selection
            panel.generation++
        }
        val layout = BuilderPanelGeometry.layout(actions.size, settings)
        if (!areaAvailable(player, panel, actions.size)) throw PanelAreaUnavailable()
        updateStatus(player, panel, current.status, actions.size)
        reconcileButtons(player, panel, actions, layout)
    }

    private fun movedBeyondAnchor(player: Player, panel: Panel): Boolean =
        player.location.distanceSquared(panel.positionedFrom) > settings.repositionDistance * settings.repositionDistance

    private fun placement(player: Player, selection: BuilderSelection, actionCount: Int): Pair<Location, Float> {
        val eye = player.eyeLocation
        val target = BuilderPanelPoint3(
            (selection.minX + selection.maxX + 1) / 2.0,
            eye.y,
            (selection.minZ + selection.maxZ + 1) / 2.0,
        )
        val ray = player.world.rayTraceBlocks(eye, direction(eye, target), settings.reach,
            FluidCollisionMode.NEVER, true)
        val blockDistance = ray?.hitPosition?.distance(eye.toVector())
        val placed = BuilderPanelGeometry.anchor(
            BuilderPanelPoint3(eye.x, eye.y, eye.z), target, eye.yaw, settings, actionCount, blockDistance,
        )
        return Location(player.world, placed.point.x, placed.point.y, placed.point.z, placed.yaw, 0f) to placed.yaw
    }

    private fun direction(eye: Location, target: BuilderPanelPoint3): org.bukkit.util.Vector {
        val vector = org.bukkit.util.Vector(target.x - eye.x, target.y - eye.y, target.z - eye.z)
        return if (vector.lengthSquared() <= 1e-12) eye.direction else vector.normalize()
    }

    private fun areaAvailable(player: Player, panel: Panel, actionCount: Int): Boolean {
        val layout = BuilderPanelGeometry.layout(actionCount, settings)
        val positions = sequence {
            yield(statusLocation(panel, actionCount))
            layout.buttons.forEach { yield(buttonLocation(panel, it)) }
        }
        val sentChunks = runCatching { player.sentChunkKeys }.getOrNull() ?: return false
        return positions.all { location ->
            val chunkX = location.blockX shr 4
            val chunkZ = location.blockZ shr 4
            panel.world.isChunkLoaded(chunkX, chunkZ) && chunkKey(chunkX, chunkZ) in sentChunks
        }
    }

    private fun statusLocation(panel: Panel, actionCount: Int): Location {
        val y = BuilderPanelGeometry.layout(actionCount, settings).statusY
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

    private fun updateStatus(player: Player, panel: Panel, status: Component, actionCount: Int) {
        val display = checkNotNull(panel.statusDisplay)
        val location = statusLocation(panel, actionCount)
        if (display.location != location) display.teleport(location)
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
        val locale = player.locale().toLanguageTag()
        layout.buttons.forEach { offset ->
            val action = actions[offset.index]
            val label = messages.render("selection-panel.actions.${action.localeKey}", locale)
            val labelLocation = buttonLocation(panel, offset)
            val hitboxLocation = hitboxLocation(panel, offset)
            val button = panel.buttons[action] ?: createButton(player, panel, action, labelLocation, hitboxLocation).also {
                panel.buttons[action] = it
                hitboxOwners[it.hitbox.uniqueId] = player.uniqueId
            }
            if (button.label.location != labelLocation) button.label.teleport(labelLocation)
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
        val layout = BuilderPanelGeometry.layout(panel.actions.size, settings)
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
        const val STATUS_SCALE = 0.48f
        const val STATUS_LINE_WIDTH = 300
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
