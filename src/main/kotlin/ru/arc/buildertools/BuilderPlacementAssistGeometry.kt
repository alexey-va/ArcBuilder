package ru.arc.buildertools

import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.util.Vector
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

internal enum class BuilderPlacementAssistMode { AUTO, LINE, PLANE }

/** Successful placements establish intent; looking around and crouching never change it. */
internal data class BuilderPlacementAssistLock(
    val anchor: BuilderBlockPos,
    val last: BuilderBlockPos = anchor,
    val direction: BlockFace? = null,
    val planeNormal: BlockFace? = null,
) {
    val active get() = direction != null || planeNormal != null
    val next: BuilderBlockPos?
        get() = direction?.let { last.relative(it) }

    fun placed(position: BuilderBlockPos, supportFace: BlockFace? = planeNormal): BuilderPlacementAssistLock {
        if (position == last) return this
        val samePlane = inPlane(position)
        return BuilderPlacementAssistLock(
            anchor = if (samePlane) anchor else position,
            last = position,
            direction = BuilderPlacementAssistGeometry.faceBetween(last, position),
            planeNormal = if (samePlane) planeNormal else supportFace,
        )
    }

    fun inPlane(position: BuilderBlockPos): Boolean {
        val normal = planeNormal ?: return false
        return position.worldId == anchor.worldId && when {
            normal.modX != 0 -> position.x == anchor.x
            normal.modY != 0 -> position.y == anchor.y
            else -> position.z == anchor.z
        }
    }
}

internal fun BuilderBlockPos.relative(face: BlockFace) = copy(x = x + face.modX, y = y + face.modY, z = z + face.modZ)

internal data class BuilderPlacementAssistAppearance(val material: Material, val glowRgb: Int) {
    companion object {
        private val READY = BuilderPlacementAssistAppearance(Material.LIME_STAINED_GLASS, 0x72EBC5)
        private val BLOCKED = BuilderPlacementAssistAppearance(Material.ORANGE_STAINED_GLASS, 0xFFAE64)

        fun forState(blocked: Boolean) = if (blocked) BLOCKED else READY
        fun edges(position: BuilderBlockPos): List<BuilderDisplayEdge> = BuilderDisplayGeometry.bounds(listOf(position), .025f)
    }
}

internal object BuilderPlacementAssistGeometry {
    val cardinalFaces = listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST, BlockFace.UP, BlockFace.DOWN)

    fun faceBetween(from: BuilderBlockPos, to: BuilderBlockPos): BlockFace? =
        if (from.worldId == to.worldId) cardinalFaces.firstOrNull { from.relative(it) == to } else null

    /** First entry into the real one-block layer, from either side; never project through its centre. */
    fun projectPlane(eye: Vector, direction: Vector, lock: BuilderPlacementAssistLock, range: Double): Vector? {
        val normal = lock.planeNormal ?: return null
        if (!eye.isFinite() || !direction.isFinite() || !range.isFinite() || range <= 0 || direction.lengthSquared() < 1e-9) return null
        val unit = direction.clone().normalize()
        val start = when { normal.modX != 0 -> eye.x; normal.modY != 0 -> eye.y; else -> eye.z }
        val minimum = when { normal.modX != 0 -> lock.anchor.x; normal.modY != 0 -> lock.anchor.y; else -> lock.anchor.z }.toDouble()
        val boundary = when { start < minimum -> minimum; start > minimum + 1.0 -> minimum + 1.0; else -> return null }
        val delta = when { normal.modX != 0 -> unit.x; normal.modY != 0 -> unit.y; else -> unit.z }
        if (abs(delta) < 1e-9) return null
        val distance = (boundary - start) / delta
        return if (distance > 1e-6 && distance <= range) eye.clone().add(unit.multiply(distance)) else null
    }

    fun projectedCell(point: Vector, lock: BuilderPlacementAssistLock): BuilderBlockPos {
        val anchor = lock.anchor
        val normal = checkNotNull(lock.planeNormal)
        return BuilderBlockPos(anchor.worldId,
            if (normal.modX != 0) anchor.x else floor(point.x).toInt(),
            if (normal.modY != 0) anchor.y else floor(point.y).toInt(),
            if (normal.modZ != 0) anchor.z else floor(point.z).toInt())
    }

    fun center(position: BuilderBlockPos) = Vector(position.x + .5, position.y + .5, position.z + .5)

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
