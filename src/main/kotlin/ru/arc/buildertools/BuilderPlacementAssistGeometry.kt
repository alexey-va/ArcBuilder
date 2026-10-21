package ru.arc.buildertools

import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.util.Vector
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A direction is earned by two adjacent, successful native placements. */
internal data class BuilderPlacementAssistLine(val last: BuilderBlockPos, val direction: BlockFace? = null) {
    val next: BuilderBlockPos?
        get() = direction?.let { last.copy(x = last.x + it.modX, y = last.y + it.modY, z = last.z + it.modZ) }

    fun placed(position: BuilderBlockPos): BuilderPlacementAssistLine {
        val direction = if (last.worldId == position.worldId) CARDINALS.firstOrNull {
            position.x - last.x == it.modX && position.y - last.y == it.modY && position.z - last.z == it.modZ
        } else null
        return BuilderPlacementAssistLine(position, direction)
    }

    companion object {
        private val CARDINALS = listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST, BlockFace.UP, BlockFace.DOWN)
    }
}

internal data class BuilderPlacementAssistAppearance(val material: Material, val glowRgb: Int) {
    companion object {
        private val READY = BuilderPlacementAssistAppearance(Material.LIME_STAINED_GLASS, 0x72EBC5)
        private val BLOCKED = BuilderPlacementAssistAppearance(Material.ORANGE_STAINED_GLASS, 0xFFAE64)

        fun forState(blocked: Boolean) = if (blocked) BLOCKED else READY
        fun edges(position: BuilderBlockPos): List<BuilderDisplayEdge> {
            val thickness = .025f
            val half = thickness / 2.0
            val x = position.x.toDouble()
            val y = position.y.toDouble()
            val z = position.z.toDouble()
            // Butt joins avoid overlapping coplanar faces at the eight frame corners.
            return buildList(12) {
                for (dy in listOf(0.0, 1.0)) for (dz in listOf(0.0, 1.0)) {
                    add(BuilderDisplayEdge(x - half, y + dy - half, z + dz - half, 1f + thickness, thickness, thickness))
                }
                for (dx in listOf(0.0, 1.0)) for (dz in listOf(0.0, 1.0)) {
                    add(BuilderDisplayEdge(x + dx - half, y + half, z + dz - half, thickness, 1f - thickness, thickness))
                }
                for (dx in listOf(0.0, 1.0)) for (dy in listOf(0.0, 1.0)) {
                    add(BuilderDisplayEdge(x + dx - half, y + dy - half, z + half, thickness, thickness, 1f - thickness))
                }
            }
        }
    }
}

internal object BuilderPlacementAssistGeometry {
    /** First entry into the actual one-block target; no aim-cone or expanded invisible target. */
    fun aimDistance(eye: Vector, direction: Vector, target: BuilderBlockPos, range: Double): Double? {
        if (!range.isFinite() || range <= 0.0 || !eye.isFinite() || !direction.isFinite()) return null
        val length = direction.length()
        if (length < 1e-9) return null
        val unit = direction.clone().multiply(1.0 / length)
        var near = 0.0
        var far = range
        val starts = doubleArrayOf(eye.x, eye.y, eye.z)
        val deltas = doubleArrayOf(unit.x, unit.y, unit.z)
        val minimums = doubleArrayOf(target.x.toDouble(), target.y.toDouble(), target.z.toDouble())
        for (axis in 0..2) {
            val start = starts[axis]
            val delta = deltas[axis]
            val minimum = minimums[axis]
            if (abs(delta) < 1e-9) {
                if (start < minimum || start > minimum + 1.0) return null
            } else {
                val first = (minimum - start) / delta
                val second = (minimum + 1.0 - start) / delta
                near = max(near, min(first, second))
                far = min(far, max(first, second))
                if (near > far) return null
            }
        }
        // An eye inside the target would create a block around the player.
        return near.takeIf { it > 1e-6 && far >= it }
    }

    fun faceCenter(support: BuilderBlockPos, face: BlockFace): Vector = Vector(
        support.x + .5 + face.modX * .5,
        support.y + .5 + face.modY * .5,
        support.z + .5 + face.modZ * .5,
    )

    private fun Vector.isFinite() = x.isFinite() && y.isFinite() && z.isFinite()
}
