package ru.arc.buildertools

import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.entity.Player
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** One owning-thread ray for both the held selector's cursor and its clicks. */
internal object BuilderSelectionTarget {
    const val RANGE = 5.0

    fun resolve(player: Player, maximumRange: Double): BuilderBlockPos? {
        val range = min(RANGE, maximumRange)
        if (!range.isFinite() || range <= 0.0) return null
        val world = player.world
        val eye = player.eyeLocation
        if (eye.world != world || !eye.x.isFinite() || !eye.y.isFinite() || !eye.z.isFinite()) return null
        val direction = eye.direction
        if (!direction.x.isFinite() || !direction.y.isFinite() || !direction.z.isFinite() ||
            direction.lengthSquared() < 1e-12
        ) return null
        val end = eye.clone().add(direction.clone().normalize().multiply(range))
        // Paper's precise ray trace may load chunks. At five blocks this rectangle
        // contains at most four; fail closed before any native world read.
        // Include the native ray traversal's tiny endpoint extension at exact voxel boundaries.
        for (x in (floor(min(eye.x, end.x) - 1e-5).toInt() shr 4)..(floor(max(eye.x, end.x) + 1e-5).toInt() shr 4)) {
            for (z in (floor(min(eye.z, end.z) - 1e-5).toInt() shr 4)..(floor(max(eye.z, end.z) + 1e-5).toInt() shr 4)) {
                if (!world.isChunkLoaded(x, z)) return null
            }
        }
        val hit = world.rayTraceBlocks(eye, direction, range, FluidCollisionMode.NEVER, false)
        val block = hit?.hitBlock
        val air = player.isSneaking
        val position = if (block != null) {
            val face = if (air) hit.hitBlockFace ?: return null else null
            BuilderBlockPos(world.uid, block.x + (face?.modX ?: 0), block.y + (face?.modY ?: 0),
                block.z + (face?.modZ ?: 0))
        } else {
            if (!air) return null
            BuilderBlockPos(world.uid, end.blockX, end.blockY, end.blockZ)
        }
        if (position.y !in world.minHeight until world.maxHeight || position.y !in -2048..2048 ||
            position.x !in -30_000_000..30_000_000 || position.z !in -30_000_000..30_000_000 ||
            !world.isChunkLoaded(position.x shr 4, position.z shr 4)
        ) return null
        val center = Location(world, position.x + .5, position.y + .5, position.z + .5)
        if (!world.worldBorder.isInside(center)) return null
        if (air && !world.getBlockAt(position.x, position.y, position.z).type.isAir) return null
        return position
    }
}
