package ru.arc.buildertools.display

import org.joml.Matrix4d
import org.joml.Matrix4f
import org.joml.Vector3d
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A centred mesh and immutable affine transform; native Display hitboxes are always unsuitable. */
internal data class DisplayEditorPose(
    val x: Double,
    val y: Double,
    val z: Double,
    val linear: List<Float> = Matrix4f().get(FloatArray(16)).toList(),
) {
    fun position() = Vector3d(x, y, z)
    fun matrix() = Matrix4f().set(linear.toFloatArray())
    fun at(position: Vector3d) = copy(x = position.x, y = position.y, z = position.z)
    fun withMatrix(matrix: Matrix4f) = copy(linear = matrix.get(FloatArray(16)).toList())
}

internal data class DisplayEditorMesh(val width: Double, val height: Double, val depth: Double) {
    fun half() = Vector3d(width / 2, height / 2, depth / 2)
}

internal enum class DisplayEditorMode { MOVE, ROTATE, SCALE }
internal enum class DisplayEditorAxis { FREE, X, Y, Z }

internal data class DisplayEditorScale(val x: Float, val y: Float, val z: Float) {
    fun valid() = listOf(x, y, z).all { it.isFinite() && it in DisplayEditorGeometry.MIN_SCALE..DisplayEditorGeometry.MAX_SCALE }
    fun values() = listOf(x, y, z)
}

internal data class DisplayEditorAxisGesture(
    val axis: DisplayEditorAxis,
    val centre: Vector3d,
    val normal: Vector3d,
    val initial: Double,
    val scaleSpan: Double,
)

internal object DisplayEditorGeometry {
    const val RANGE = 12.0
    const val MAX_EXTENT = 8.0
    const val MIN_SCALE = .05f
    const val MAX_SCALE = 4f
    const val MAX_CELLS = 729

    fun scale(pose: DisplayEditorPose): DisplayEditorScale = pose.matrix().getScale(org.joml.Vector3f()).let {
        DisplayEditorScale(it.x.coerceIn(MIN_SCALE, MAX_SCALE), it.y.coerceIn(MIN_SCALE, MAX_SCALE), it.z.coerceIn(MIN_SCALE, MAX_SCALE))
    }

    /** Normalize each local basis independently, preserving its direction and the visual centre. */
    fun withScale(pose: DisplayEditorPose, desired: DisplayEditorScale): DisplayEditorPose? {
        if (!desired.valid()) return null
        val matrix = pose.matrix()
        val current = matrix.getScale(org.joml.Vector3f())
        if ((0..2).any { !current[it].isFinite() || current[it] <= 0f }) return null
        return pose.withMatrix(matrix.scale(desired.x / current.x, desired.y / current.y, desired.z / current.z))
    }

    fun withoutRotation(pose: DisplayEditorPose): DisplayEditorPose = scale(pose).let {
        pose.withMatrix(Matrix4f().scaling(it.x, it.y, it.z))
    }

    fun axisVector(axis: DisplayEditorAxis): Vector3d = Vector3d().setComponent(axis.ordinal - 1, 1.0)

    /** A fixed plane containing the world axis. Near-parallel views pause instead of amplifying noise. */
    fun axisGesture(pose: DisplayEditorPose, axis: DisplayEditorAxis, origin: Vector3d, direction: Vector3d): DisplayEditorAxisGesture? {
        if (axis == DisplayEditorAxis.FREE) return null
        val unit = axisVector(axis)
        val normal = Vector3d(direction).fma(-direction.dot(unit), unit)
        if (normal.lengthSquared() < .15 * .15) return null
        normal.normalize()
        val draft = DisplayEditorAxisGesture(axis, pose.position(), normal, 0.0, .25)
        val coordinate = axisCoordinate(draft, origin, direction) ?: return null
        return draft.copy(initial = coordinate, scaleSpan = max(abs(coordinate), .25))
    }

    fun axisDisplacement(gesture: DisplayEditorAxisGesture, origin: Vector3d, direction: Vector3d): Double? =
        axisCoordinate(gesture, origin, direction)?.minus(gesture.initial)

    private fun axisCoordinate(gesture: DisplayEditorAxisGesture, origin: Vector3d, direction: Vector3d): Double? {
        if (!origin.isFinite || !direction.isFinite || abs(direction.lengthSquared() - 1.0) > 1e-5) return null
        val unit = axisVector(gesture.axis)
        if (1 - direction.dot(unit).let { it * it } < .15 * .15) return null
        val denominator = direction.dot(gesture.normal)
        if (denominator < .15) return null
        val distance = Vector3d(gesture.centre).sub(origin).dot(gesture.normal) / denominator
        if (!distance.isFinite() || distance !in 0.0..RANGE) return null
        return Vector3d(origin).fma(distance, direction).sub(gesture.centre).dot(unit).takeIf { it.isFinite() }
    }

    fun worldScale(pose: DisplayEditorPose, axis: DisplayEditorAxis, factor: Float): DisplayEditorPose {
        val scaling = org.joml.Vector3f(1f)
        if (axis == DisplayEditorAxis.FREE) scaling.set(factor) else scaling.setComponent(axis.ordinal - 1, factor)
        return pose.withMatrix(Matrix4f().scaling(scaling).mul(pose.matrix()))
    }

    fun corners(pose: DisplayEditorPose, mesh: DisplayEditorMesh): List<Vector3d> {
        val matrix = Matrix4d(pose.matrix())
        val half = mesh.half()
        return (0..7).map { index ->
            matrix.transformPosition(Vector3d(
                if (index and 1 == 0) -half.x else half.x,
                if (index and 2 == 0) -half.y else half.y,
                if (index and 4 == 0) -half.z else half.z,
            )).add(pose.position())
        }
    }

    /** Inverse-transform the complete ray, preserving its world-distance parameter. */
    fun rayDistance(origin: Vector3d, direction: Vector3d, pose: DisplayEditorPose, mesh: DisplayEditorMesh): Double? {
        if (!valid(pose, mesh)) return null
        val inverse = Matrix4d(pose.matrix()).invert()
        val localOrigin = inverse.transformPosition(Vector3d(origin).sub(pose.position()))
        val localDirection = inverse.transformDirection(Vector3d(direction))
        val half = mesh.half()
        var near = 0.0
        var far = RANGE
        for (axis in 0..2) {
            val o = localOrigin[axis]
            val d = localDirection[axis]
            val h = half[axis]
            if (abs(d) < 1e-10) {
                if (o < -h || o > h) return null
            } else {
                val first = (-h - o) / d
                val second = (h - o) / d
                near = max(near, min(first, second))
                far = min(far, max(first, second))
                if (near > far) return null
            }
        }
        return near.takeIf { it in 0.0..RANGE }
    }

    fun valid(pose: DisplayEditorPose, mesh: DisplayEditorMesh): Boolean {
        if (!listOf(pose.x, pose.y, pose.z, mesh.width, mesh.height, mesh.depth).all(Double::isFinite) ||
            pose.linear.size != 16 || !pose.linear.all(Float::isFinite) ||
            minOf(mesh.width, mesh.height, mesh.depth) <= 0) return false
        val matrix = pose.matrix()
        if (abs(matrix.determinant()) < 1e-8 ||
            matrix.m03() != 0f || matrix.m13() != 0f || matrix.m23() != 0f || matrix.m33() != 1f ||
            matrix.m30() != 0f || matrix.m31() != 0f || matrix.m32() != 0f) return false
        val scale = matrix.getScale(org.joml.Vector3f())
        if ((0..2).any { scale[it] < MIN_SCALE - 1e-5 || scale[it] > MAX_SCALE + 1e-5 }) return false
        val corners = corners(pose, mesh)
        return (0..2).all { axis -> corners.maxOf { it[axis] } - corners.minOf { it[axis] } <= MAX_EXTENT + 1e-5 }
    }

    /** Every block touched by the rendered envelope, not just its entity anchor. */
    fun cells(pose: DisplayEditorPose, mesh: DisplayEditorMesh): List<Triple<Int, Int, Int>>? {
        if (!valid(pose, mesh)) return null
        val corners = corners(pose, mesh)
        val low = (0..2).map { axis -> floor(corners.minOf { it[axis] } + 1e-6).toInt() }
        val high = (0..2).map { axis -> max(low[axis], floor(corners.maxOf { it[axis] } - 1e-6).toInt()) }
        val count = (high[0].toLong() - low[0] + 1) * (high[1] - low[1] + 1) * (high[2] - low[2] + 1)
        if (count !in 1..MAX_CELLS.toLong()) return null
        return buildList {
            for (x in low[0]..high[0]) for (y in low[1]..high[1]) for (z in low[2]..high[2]) add(Triple(x, y, z))
        }
    }

    /** Places the transformed nearest face on the real collision surface, including rotation and scale. */
    fun onSurface(pose: DisplayEditorPose, mesh: DisplayEditorMesh, hit: Vector3d, normal: Vector3d): DisplayEditorPose {
        val minimum = corners(pose.at(Vector3d()), mesh).minOf { it.dot(normal) }
        return pose.at(Vector3d(hit).fma(.006 - minimum, normal))
    }

    fun constrain(position: Vector3d, start: Vector3d, axis: DisplayEditorAxis): Vector3d = when (axis) {
        DisplayEditorAxis.FREE -> position
        DisplayEditorAxis.X -> Vector3d(position.x, start.y, start.z)
        DisplayEditorAxis.Y -> Vector3d(start.x, position.y, start.z)
        DisplayEditorAxis.Z -> Vector3d(start.x, start.y, position.z)
    }

    fun yawDelta(previous: Float, current: Float): Double = ((current - previous + 540.0) % 360.0) - 180.0

    /** Same conservative, epsilon-expanded chunk rectangle as BuilderSelectionTarget. */
    fun rayChunksLoaded(origin: Vector3d, direction: Vector3d, loaded: (Int, Int) -> Boolean): Boolean {
        if (!origin.isFinite || !direction.isFinite || abs(direction.lengthSquared() - 1.0) > 1e-5) return false
        val end = Vector3d(origin).fma(RANGE, direction)
        for (x in (floor(min(origin.x, end.x) - 1e-5).toInt() shr 4)..(floor(max(origin.x, end.x) + 1e-5).toInt() shr 4)) {
            for (z in (floor(min(origin.z, end.z) - 1e-5).toInt() shr 4)..(floor(max(origin.z, end.z) + 1e-5).toInt() shr 4)) {
                if (!loaded(x, z)) return false
            }
        }
        return true
    }

    val edges = (0..7).flatMap { start -> listOf(1, 2, 4).map { start xor it }.filter { it > start }.map { start to it } }
}

/** Selections are exclusive even while their owner is in a dialog or holds a private draft. */
internal class DisplayEditorLocks {
    private val owners = mutableMapOf<UUID, UUID>()
    fun acquire(objectId: UUID, playerId: UUID): Boolean =
        (owners[objectId] == null || owners[objectId] == playerId).also { if (it) owners[objectId] = playerId }
    fun heldBy(objectId: UUID, playerId: UUID) = owners[objectId] == playerId
    fun release(playerId: UUID) { owners.entries.removeIf { it.value == playerId } }
    fun available(objectId: UUID, playerId: UUID) = owners[objectId].let { it == null || it == playerId }
    fun clear() = owners.clear()
}

internal object DisplayEditorAuthority {
    fun mayEdit(marked: Boolean, owner: UUID?, player: UUID, permission: Boolean, admin: Boolean): Boolean =
        marked && owner != null && permission && (owner == player || admin)
}
