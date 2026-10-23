package ru.arc.buildertools

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Color
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3f
import ru.arc.paper.display.DisplayOrientedVolume
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.menu.DialogTextLayout
import ru.arc.paper.scene.AnchoredSurface
import ru.arc.paper.scene.PacketDisplayScene
import ru.arc.paper.scene.PaperSpatialSpace
import ru.arc.paper.scene.SceneAudience
import ru.arc.paper.scene.SceneDisplayStyle
import ru.arc.paper.scene.SceneFrame
import ru.arc.paper.scene.ScenePart
import ru.arc.paper.scene.ScenePose
import ru.arc.paper.scene.SceneRefreshController
import ru.arc.paper.scene.SceneRefreshRegion
import ru.arc.paper.scene.SceneTextStyle
import ru.arc.paper.scene.SpatialControl
import ru.arc.paper.scene.SpatialControlFrame
import ru.arc.paper.scene.SpatialHitPlane
import ru.arc.paper.scene.SpatialInputProfile
import ru.arc.paper.scene.SpatialNativeTarget
import ru.arc.paper.scene.SpatialReach
import ru.arc.paper.scene.SpatialSceneScope
import ru.arc.text.TextAlignment
import ru.arc.text.TextLayoutResult
import ru.arc.text.ComponentTextLayout
import ru.arc.text.GlyphWidths
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

internal data class BuilderWorldPlaqueStyle(
    val interactionWidth: Float,
    val interactionHeight: Float,
    val lineWidth: Int,
    val backgroundColor: Color,
    val glowColor: Color,
    val viewRange: Double,
    val appearance: BuilderPlaqueAppearance = BuilderPlaqueAppearance(backgroundColor, glowColor = glowColor),
)

/** Builder's authored plaque appearance; Core owns reconciliation, spatial search and input. */
internal class BuilderWorldPlaque(
    displays: PaperPacketDisplays,
    private val space: PaperSpatialSpace,
    private val style: BuilderWorldPlaqueStyle,
    private val audience: SceneAudience,
    private val canView: (Player) -> Boolean = { true },
) : AutoCloseable {
    private val scene = PacketDisplayScene<Int>(displays)
    private var authored: Location? = null
    private var text: Component = Component.empty()
    private var footprint: DisplayOrientedVolume? = null
    private var decoration = 0.0 to 0.0
    private var previous: Location? = null
    private var placed: Location? = null
    private var enabled = true
    private var closed = false
    private var appearance = style.appearance
    private var rimBlock = if (appearance.glowEnabled) appearance.glowMaterial.createBlockData() else null
    private var lastEmissionTick: Int? = null
    private var refreshController: SceneRefreshController? = null
    private val displayStyle = SceneDisplayStyle(
        brightness = Display.Brightness(15, 15),
        viewRange = (style.viewRange / 64.0).toFloat(),
        displayWidth = style.interactionWidth,
        displayHeight = style.interactionHeight,
        // The vanilla TextDisplay renderer does not submit an outline. Optional glow uses the rim below.
        glowing = false,
    )
    private val textStyle = SceneTextStyle(
        lineWidth = style.lineWidth,
        background = appearance.background,
        shadowed = true,
    )

    /** The callback reapplies cached presentation and its controls, never a construction plan. */
    fun refreshWith(refresh: () -> Unit) {
        refreshController = SceneRefreshController { refresh(); emitTrail(Bukkit.getCurrentTick()) }
        authored?.let { scene.refreshWith(SceneRefreshRegion(it, audience, style.viewRange), checkNotNull(refreshController)) }
    }

    fun update(location: Location, content: Component, retainTranslatedClearance: Boolean = false) {
        if (authored != location) {
            val old = authored
            previous = if (retainTranslatedClearance && old != null && old.world == location.world &&
                old.yaw == location.yaw && old.pitch == location.pitch && text == content) {
                previous?.clone()?.add(location.x - old.x, location.y - old.y, location.z - old.z)
            } else null
        }
        if (authored != location) refreshController?.let {
            scene.refreshWith(SceneRefreshRegion(location, audience, style.viewRange), it)
        }
        if (authored != location || text != content) {
            authored = location.clone()
            text = content
            footprint = visualFootprint(location, content, style.lineWidth)
            decoration = decorationBounds(content, style.lineWidth)
        }
        refresh()
    }

    fun refresh(visible: Boolean = true) {
        if (closed) return
        val anchor = authored ?: return
        enabled = visible
        val volumes = listOf(checkNotNull(footprint))
        placed = if (visible) {
            // Keep a world plaque beside its authored face: escape toward the viewer side first.
            space.place(AnchoredSurface(anchor, volumes, maxOutward = 2.0, maxUp = 0.0, maxSide = 0.0), previous)
                ?: space.place(AnchoredSurface(anchor, volumes, maxOutward = 2.0,
                    maxUp = MAX_UPWARD_SHIFT, maxSide = MAX_SIDE_SHIFT), previous)
        } else null
        placed?.let { previous = it.clone() }
        val at = placed ?: anchor
        scene.apply(SceneFrame(
            listOf(0f, 180f).mapIndexed { index, turn ->
                ScenePart.Text(
                    index,
                    ScenePose(at.clone().apply { yaw += turn }, Transformation(
                        Vector3f(0f, 0f, FACE_SEPARATION), Quaternionf(), Vector3f(SCALE), Quaternionf(),
                    )),
                    displayStyle,
                    text,
                    textStyle.copy(background = appearance.background),
                    visible = placed != null,
                )
            } + rimParts(at),
            audience,
        ))
    }

    fun updateAppearance(next: BuilderPlaqueAppearance) {
        check(!closed)
        appearance = next
        rimBlock = if (next.glowEnabled) next.glowMaterial.createBlockData() else null
        lastEmissionTick = null
        refresh(enabled)
    }

    private fun rimParts(at: Location): List<ScenePart<Int>> {
        val block = rimBlock ?: return emptyList()
        val padding = appearance.trail.padding
        val width = decoration.first + padding * 2
        val height = decoration.second + padding * 2
        val thickness = appearance.glowThickness
        val rimStyle = displayStyle.copy(glowing = true, glowColor = appearance.glowColor)
        // Four bars meet at their ends; no coplanar overlapping faces or duplicate front/back rim.
        return listOf(
            floatArrayOf((-width / 2).toFloat(), -padding.toFloat(), width.toFloat(), thickness),
            floatArrayOf((-width / 2).toFloat(), (height - padding - thickness).toFloat(), width.toFloat(), thickness),
            floatArrayOf((-width / 2).toFloat(), (-padding + thickness).toFloat(), thickness, (height - thickness * 2).toFloat()),
            floatArrayOf((width / 2 - thickness).toFloat(), (-padding + thickness).toFloat(), thickness, (height - thickness * 2).toFloat()),
        ).mapIndexed { index, bar ->
            ScenePart.Block(index + 2, ScenePose(at, Transformation(
                Vector3f(bar[0], bar[1], -thickness / 2), Quaternionf(), Vector3f(bar[2], bar[3], thickness), Quaternionf(),
            )), rimStyle, block, visible = placed != null)
        }
    }

    /** Called only by the existing five-tick scene capture; no emitter/task survives its plaque. */
    internal fun emitTrail(tick: Int) {
        val at = placed ?: return
        val trail = appearance.trail
        if (closed || !enabled || !trail.enabled || lastEmissionTick?.let { tick - it < trail.intervalTicks } == true) return
        lastEmissionTick = tick
        val points = builderPlaqueTrail(decoration.first, decoration.second, tick.toLong(), trail)
        val world = at.world ?: return
        val range = minOf(style.viewRange, trail.viewRange)
        val viewers = world.getNearbyPlayers(at, range).asSequence()
            .filter { it.isOnline && !it.isDead && canView(it) &&
                (audience !is SceneAudience.Private || it.uniqueId == audience.viewerId) &&
                it.isChunkSent(org.bukkit.Chunk.getChunkKey(at.blockX shr 4, at.blockZ shr 4)) }
            .take(trail.maxViewers).toList()
        if (viewers.isEmpty()) return
        val angle = Math.toRadians(at.yaw.toDouble())
        for (viewer in viewers) {
            if (!BuilderPlaqueParticleBudget.admit(viewer.uniqueId, tick, points.size)) continue
            for (point in points) {
                val data = if (trail.particle == Particle.DUST) Particle.DustOptions(
                    Color.fromRGB((trail.color.red * point.strength).toInt(), (trail.color.green * point.strength).toInt(),
                        (trail.color.blue * point.strength).toInt()), trail.size * point.strength,
                ) else null
                viewer.spawnParticle(trail.particle, at.x + cos(angle) * point.x, at.y + point.y,
                    at.z + sin(angle) * point.x, 1, 0.0, 0.0, 0.0, 0.0, data)
            }
        }
    }

    fun <C : Any> controls(context: C): SpatialControlFrame<C, Unit> {
        val anchor = checkNotNull(authored)
        val bounds = checkNotNull(footprint).bounds
        val at = (placed ?: anchor).clone().add(0.0, bounds.centerY - anchor.y, 0.0)
        val visible = enabled && placed != null
        val width = max(style.interactionWidth.toDouble(), visualWidth(style.lineWidth) + HIT_PADDING * 2)
        val height = max(style.interactionHeight.toDouble(), bounds.height + HIT_PADDING * 2)
        // A native Interaction has a square X/Z footprint. Adjacent narrow targets cover the
        // whole label without turning a wide text panel into a deep, click-catching cube.
        val columns = ceil(width / MAX_TARGET_WIDTH).toInt()
        val columnWidth = width / columns
        val angle = Math.toRadians(at.yaw.toDouble())
        val currentFootprint = checkNotNull(footprint).translated(Vector3d(
            (placed ?: anchor).x - anchor.x,
            (placed ?: anchor).y - anchor.y,
            (placed ?: anchor).z - anchor.z,
        ))
        return SpatialControlFrame(
            context,
            audience,
            SpatialSceneScope.World,
            List(columns) { column ->
                val offset = -width / 2 + columnWidth * (column + .5)
                val center = at.clone().add(cos(angle) * offset, 0.0, sin(angle) * offset)
                SpatialControl(
                    "open-$column",
                    Unit,
                    listOf(SpatialHitPlane(center, columnWidth, height)),
                    if (visible) SpatialNativeTarget(
                        center.clone().subtract(0.0, height / 2.0, 0.0),
                        columnWidth.toFloat(),
                        height.toFloat(),
                    ) else null,
                    visible = visible,
                )
            },
            SpatialInputProfile.ENTITY_RIGHT_EARLY,
            SpatialReach.NativeInteraction,
            clearanceFootprints = if (visible) listOf(currentFootprint) else emptyList(),
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        placed = null
        scene.close()
    }

    companion object {
        const val SCALE = 2f
        private const val FACE_SEPARATION = .02f
        private const val PIXEL_SIZE = .025
        private const val HIT_PADDING = .1
        private const val MAX_TARGET_WIDTH = 3.0
        internal const val MAX_UPWARD_SHIFT = .5
        internal const val MAX_SIDE_SHIFT = .5
        internal const val VERTICAL_PADDING = .02

        private val decorationWidths = GlyphWidths { font, point, bold ->
            if (font.asString() != "minecraft:default") null
            else {
                // Native 1.21.11 unifont advance for the bundled 🛠 prefix: 7px (8px bold).
                // Core's snapshot covers ordinary glyphs but omits this supplementary code point.
                val normal = if (point == 0x1F6E0) 7 else if (point <= Char.MAX_VALUE.code)
                    runCatching { DialogTextLayout.glyphWidth(point.toChar()) }.getOrNull() else null
                normal?.plus(if (bold) 1 else 0)
            }
        }
        private val decorationLayout = ComponentTextLayout(decorationWidths, DialogTextLayout.spacing)

        /** Tight decorative bounds use known glyph advances; collision keeps its conservative envelope. */
        internal fun decorationBounds(text: Component, lineWidth: Int): Pair<Double, Double> {
            val layout = if (lineWidth in 1..1023)
                decorationLayout.layout(text, lineWidth, TextAlignment.LEFT) as? TextLayoutResult.Aligned else null
            if (layout == null) return visualWidth(lineWidth) to visualHeight(text, lineWidth)
            var line = 0
            var widest = 0
            fun measure(component: Component, inherited: Style) {
                val effective = component.style().merge(inherited, Style.Merge.Strategy.IF_ABSENT_ON_TARGET)
                val font = effective.font() ?: net.kyori.adventure.key.Key.key("minecraft:default")
                val bold = effective.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE
                if (component is TextComponent) component.content().codePoints().forEach { point ->
                    when (point) {
                        '\n'.code -> { widest = max(widest, line); line = 0 }
                        in 0xF0F01..0xF0F0A -> Unit // Core's synthetic right-padding, never rendered by this plaque.
                        else -> line += checkNotNull(decorationWidths.width(font, point, bold))
                    }
                }
                component.children().forEach { measure(it, effective) }
            }
            measure(layout.component, Style.empty())
            // Native backdrop spans [-width/2, width/2+1] px; cover both opposing text faces.
            return (max(widest, line) + 2) * PIXEL_SIZE * SCALE to layout.lineCount * 10 * PIXEL_SIZE * SCALE
        }

        internal fun visualWidth(lineWidth: Int) = (lineWidth + 4) * PIXEL_SIZE * SCALE

        internal fun visualHeight(text: Component, lineWidth: Int): Double {
            val hasPrivateGlyph = PlainTextComponentSerializer.plainText().serialize(text).codePoints()
                .anyMatch { Character.getType(it) == Character.PRIVATE_USE.toInt() }
            val measured = if (lineWidth <= 1016 && !hasPrivateGlyph) DialogTextLayout.layout(text, TextAlignment.LEFT, lineWidth + 8) else null
            val lines = (measured as? TextLayoutResult.Aligned)?.lineCount ?: conservativeLines(text, lineWidth)
            return max(1, lines) * 10 * PIXEL_SIZE * SCALE
        }

        /**
         * lineWidth bounds the authored mesh; displayWidth is only culling metadata. Minecraft
         * 1.21.11 TextDisplayRenderer uses a bottom anchor and 10 pixels per wrapped line, after its
         * -0.025 pixel transform. Unknown glyphs reserve one authored line instead of guessed width.
         */
        internal fun visualFootprint(location: Location, text: Component, lineWidth: Int): DisplayOrientedVolume {
            val halfWidth = visualWidth(lineWidth) / 2.0
            val height = visualHeight(text, lineWidth)
            val angle = Math.toRadians(location.yaw.toDouble())
            return DisplayOrientedVolume(
                Vector3d(location.x, location.y + height / 2.0, location.z),
                Vector3d(cos(angle), 0.0, sin(angle)),
                Vector3d(0.0, 1.0, 0.0),
                Vector3d(-sin(angle), 0.0, cos(angle)),
                Vector3d(halfWidth, height / 2.0 + VERTICAL_PADDING, .04),
            )
        }

        private fun conservativeLines(text: Component, lineWidth: Int): Int =
            PlainTextComponentSerializer.plainText().serialize(text).split('\n').sumOf { line ->
                var lines = 1
                var used = 0
                // Native wrapping keeps ordinary words together. Total-width division can
                // undercount three words each wider than half a line; retain those boundaries.
                Regex("\\s+|\\S+").findAll(line).forEach { token ->
                    val advances = token.value.codePoints().toArray().map { point ->
                        if (point <= Char.MAX_VALUE.code && Character.getType(point) != Character.PRIVATE_USE.toInt())
                            // The plain-text fallback loses decoration inheritance. Reserve the
                            // extra bold pixel as well as the normal inter-glyph advance.
                            runCatching { DialogTextLayout.glyphWidth(point.toChar()) + 2 }.getOrDefault(lineWidth)
                        else lineWidth
                    }
                    if (!token.value.first().isWhitespace() && used > 0 && used + advances.sum() > lineWidth) {
                        lines++
                        used = 0
                    }
                    advances.forEach { advance ->
                        if (used > 0 && used + advance > lineWidth) { lines++; used = 0 }
                        used += advance
                    }
                }
                lines
            }
    }
}

/** Decoration cannot multiply without bound when several nearby projects share an observer. */
private object BuilderPlaqueParticleBudget {
    private var currentTick: Int? = null
    private val used = mutableMapOf<java.util.UUID, Int>()
    fun admit(viewer: java.util.UUID, tick: Int, count: Int): Boolean {
        if (currentTick != tick) { currentTick = tick; used.clear() }
        val previous = used[viewer] ?: 0
        if (previous + count > 96) return false
        used[viewer] = previous + count
        return true
    }
}
