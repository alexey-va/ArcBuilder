package ru.arc.buildertools

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.entity.Display
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
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal data class BuilderWorldPlaqueStyle(
    val interactionWidth: Float,
    val interactionHeight: Float,
    val lineWidth: Int,
    val backgroundColor: Color,
    val glowColor: Color,
    val viewRange: Double,
)

/** Builder's authored plaque appearance; Core owns reconciliation, spatial search and input. */
internal class BuilderWorldPlaque(
    displays: PaperPacketDisplays,
    private val space: PaperSpatialSpace,
    private val style: BuilderWorldPlaqueStyle,
    private val audience: SceneAudience,
) : AutoCloseable {
    private val scene = PacketDisplayScene<Int>(displays)
    private var authored: Location? = null
    private var text: Component = Component.empty()
    private var footprint: DisplayOrientedVolume? = null
    private var previous: Location? = null
    private var placed: Location? = null
    private var enabled = true
    private var refreshController: SceneRefreshController? = null
    private val displayStyle = SceneDisplayStyle(
        brightness = Display.Brightness(15, 15),
        viewRange = (style.viewRange / 64.0).toFloat(),
        displayWidth = style.interactionWidth,
        displayHeight = style.interactionHeight,
        glowing = true,
        glowColor = style.glowColor,
    )
    private val textStyle = SceneTextStyle(
        lineWidth = style.lineWidth,
        background = style.backgroundColor,
        shadowed = true,
    )

    /** The callback reapplies cached presentation and its controls, never a construction plan. */
    fun refreshWith(refresh: () -> Unit) {
        refreshController = SceneRefreshController(refresh)
        authored?.let { scene.refreshWith(SceneRefreshRegion(it, audience, style.viewRange), checkNotNull(refreshController)) }
    }

    fun update(location: Location, content: Component) {
        if (authored != location) refreshController?.let {
            scene.refreshWith(SceneRefreshRegion(location, audience, style.viewRange), it)
        }
        if (authored != location || text != content) {
            authored = location.clone()
            text = content
            footprint = visualFootprint(location, content, style.lineWidth)
        }
        refresh()
    }

    fun refresh(visible: Boolean = true) {
        val anchor = authored ?: return
        enabled = visible
        placed = if (visible) space.place(AnchoredSurface(anchor, listOf(checkNotNull(footprint))), previous) else null
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
                    textStyle,
                    visible = placed != null,
                )
            },
            audience,
        ))
    }

    fun <C : Any> controls(context: C): SpatialControlFrame<C, Unit> {
        val anchor = checkNotNull(authored)
        val bounds = checkNotNull(footprint).bounds
        val at = (placed ?: anchor).clone().add(0.0, bounds.centerY - anchor.y, 0.0)
        val visible = enabled && placed != null
        val currentFootprint = checkNotNull(footprint).translated(Vector3d(
            (placed ?: anchor).x - anchor.x,
            (placed ?: anchor).y - anchor.y,
            (placed ?: anchor).z - anchor.z,
        ))
        return SpatialControlFrame(
            context,
            audience,
            SpatialSceneScope.World,
            listOf(SpatialControl(
                "open",
                Unit,
                listOf(SpatialHitPlane(at, style.interactionWidth.toDouble(), min(style.interactionHeight.toDouble(), bounds.height - .04))),
                if (visible) SpatialNativeTarget(
                    at.clone().subtract(0.0, style.interactionHeight / 2.0, 0.0),
                    style.interactionWidth,
                    style.interactionHeight,
                ) else null,
                visible = visible,
            )),
            SpatialInputProfile.ENTITY_RIGHT_EARLY,
            SpatialReach.NativeInteraction,
            clearanceFootprints = if (visible) listOf(currentFootprint) else emptyList(),
        )
    }

    override fun close() = scene.close()

    companion object {
        const val SCALE = 2f
        private const val FACE_SEPARATION = .02f
        private const val PIXEL_SIZE = .025

        /**
         * lineWidth bounds the authored mesh; displayWidth is only culling metadata. Minecraft
         * 1.21.11 TextDisplayRenderer uses a bottom anchor and 10 pixels per wrapped line, after its
         * -0.025 pixel transform. Unknown glyphs reserve one authored line instead of guessed width.
         */
        internal fun visualFootprint(location: Location, text: Component, lineWidth: Int): DisplayOrientedVolume {
            val hasPrivateGlyph = PlainTextComponentSerializer.plainText().serialize(text).codePoints()
                .anyMatch { Character.getType(it) == Character.PRIVATE_USE.toInt() }
            val measured = if (lineWidth <= 1016 && !hasPrivateGlyph) DialogTextLayout.layout(text, TextAlignment.LEFT, lineWidth + 8) else null
            val lines = (measured as? TextLayoutResult.Aligned)?.lineCount ?: conservativeLines(text, lineWidth)
            val pixelScale = PIXEL_SIZE * SCALE
            val halfWidth = (lineWidth + 4) * pixelScale / 2.0
            val height = max(1, lines) * 10 * pixelScale
            val angle = Math.toRadians(location.yaw.toDouble())
            return DisplayOrientedVolume(
                Vector3d(location.x, location.y + height / 2.0, location.z),
                Vector3d(cos(angle), 0.0, sin(angle)),
                Vector3d(0.0, 1.0, 0.0),
                Vector3d(-sin(angle), 0.0, cos(angle)),
                Vector3d(halfWidth, height / 2.0 + .02, .04),
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
