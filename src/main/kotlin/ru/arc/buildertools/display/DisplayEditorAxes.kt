package ru.arc.buildertools.display

import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.util.UUID
import java.util.concurrent.CompletableFuture

internal data class DisplayEditorAxisPart(
    val axis: Int, val x: Float, val y: Float, val z: Float,
    val width: Float, val height: Float, val depth: Float,
)

internal data class DisplayEditorAxesFrame(val x: Double, val y: Double, val z: Double, val parts: List<DisplayEditorAxisPart>)

/** Canonical geometry shared by the packet handles, tests and the source-derived preview. */
internal object DisplayEditorAxesGeometry {
    val materials = listOf(Material.RED_CONCRETE, Material.LIME_CONCRETE, Material.BLUE_CONCRETE)
    val colors = listOf(0xff6b61, 0x9bd48d, 0x6f9fff)

    fun frame(pose: DisplayEditorPose, mesh: DisplayEditorMesh?, selected: DisplayEditorAxis): DisplayEditorAxesFrame {
        val corners = mesh?.let { DisplayEditorGeometry.corners(pose, it) }
        val parts = (0..2).flatMap { axis ->
            val active = selected.ordinal == axis + 1
            val thickness = if (active) .055f else .025f
            val tip = if (active) .13f else .085f
            val reach = if (corners == null) .65f else ((corners.maxOf { it[axis] } - pose.position()[axis]) + .85).toFloat()
            val start = Vector3f(-thickness / 2).also { it.setComponent(axis, .05f) }
            val size = Vector3f(thickness).also { it.setComponent(axis, reach - .05f) }
            val end = Vector3f(-tip / 2).also { it.setComponent(axis, reach - tip / 2) }
            listOf(
                DisplayEditorAxisPart(axis, start.x, start.y, start.z, size.x, size.y, size.z),
                DisplayEditorAxisPart(axis, end.x, end.y, end.z, tip, tip, tip),
            )
        }
        return DisplayEditorAxesFrame(pose.x, pose.y, pose.z, parts)
    }
}

/**
 * Six stable private handles per active editor, at most 16 editors / 96 handles.
 * The existing editor tick owns geometry; Core owns coalesced, budgeted asynchronous
 * packets, chunk/connection replay and the terminal cleanup future. No native entities.
 */
internal class DisplayEditorAxes(
    plugin: Plugin,
    private val displays: PaperPacketDisplays = PaperPacketDisplays(plugin, "display-editor-axes"),
) : AutoCloseable {
    private data class Scene(val world: UUID, val handles: List<PacketBlockDisplay>, var frame: DisplayEditorAxesFrame? = null)
    private val scenes = mutableMapOf<UUID, Scene>()
    private val blocks = DisplayEditorAxesGeometry.materials.map(Material::createBlockData)
    private var closed = false

    fun update(player: Player, frame: DisplayEditorAxesFrame) {
        if (closed) return
        if (scenes[player.uniqueId]?.world != player.world.uid) clear(player.uniqueId)
        val scene = scenes.getOrPut(player.uniqueId) {
            check(scenes.size < 16) { "Display editor axis scene limit exceeded" }
            Scene(player.world.uid, frame.parts.map { part ->
                displays.spawnBlock(Location(player.world, frame.x, frame.y, frame.z), blocks[part.axis]).apply {
                    isVisibleByDefault = false
                    showTo(player.uniqueId)
                    brightness = Display.Brightness(15, 15)
                    isGlowing = true
                    glowColorOverride = Color.fromRGB(DisplayEditorAxesGeometry.colors[part.axis])
                    viewRange = .5f
                    shadowRadius = 0f
                    interpolationDuration = 0
                    teleportDuration = 0
                }
            })
        }
        if (scene.frame == frame) return
        scene.handles.zip(frame.parts).forEach { (display, part) ->
            display.teleport(Location(player.world, frame.x, frame.y, frame.z))
            display.transformation = Transformation(
                Vector3f(part.x, part.y, part.z), Quaternionf(),
                Vector3f(part.width, part.height, part.depth), Quaternionf(),
            )
        }
        scene.frame = frame
    }

    fun clear(playerId: UUID) { scenes.remove(playerId)?.handles?.forEach(PacketBlockDisplay::remove) }

    fun closeAsync(): CompletableFuture<Void> {
        closed = true
        scenes.clear()
        return displays.closeAsync()
    }

    override fun close() { closeAsync() }
}
