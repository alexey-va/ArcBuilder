package ru.arc.buildertools

import kotlin.math.hypot

/** Built once on a worker for an immutable transformed schematic; movement queries are O(4). */
internal data class BuilderBookPlaqueGeometry(
    val fixed: BuilderSitePanelPlacement,
    val minX: Double,
    val minZ: Double,
    val maxX: Double,
    val maxZ: Double,
    val frontOffset: Double,
    val controlMinX: Double = minX,
    val controlMinZ: Double = minZ,
    val controlMaxX: Double = maxX,
    val controlMaxZ: Double = maxZ,
) {
    data class Nearest(val face: BuilderConstructionSitePanelFace, val placement: BuilderSitePanelPlacement)
    data class Controls(val targetX: Double, val targetZ: Double, val distance: Double, val face: BuilderConstructionSitePanelFace) {
        val scale: Double get() = distance / 2.4
    }

    /** A face-parallel plane keeps every button and sight line outside opaque packet ghosts. */
    fun controls(x: Double, z: Double, previous: BuilderConstructionSitePanelFace? = null): Controls? {
        val gaps = listOf(controlMinX - x, x - controlMaxX, controlMinZ - z, z - controlMaxZ)
        val closest = gaps.indices.maxBy { gaps[it] }
        val prior = BuilderConstructionSitePanelFace.entries.indexOf(previous)
        val side = if (prior >= 0 && gaps[prior] >= .75 && gaps[closest] <= gaps[prior] + .35) prior else closest
        val gap = gaps[side]
        // At corners either separating face is safe. Prefer the one with room for the full angular layout.
        if (gap < if (previous == null) .9 else .75) return null
        val distance = minOf(2.4, gap - .15)
        val face = BuilderConstructionSitePanelFace.entries[side]
        return when (side) {
            0 -> Controls(controlMinX, z, distance, face)
            1 -> Controls(controlMaxX, z, distance, face)
            2 -> Controls(x, controlMinZ, distance, face)
            else -> Controls(x, controlMaxZ, distance, face)
        }
    }

    fun nearest(x: Double, z: Double, previous: BuilderConstructionSitePanelFace? = null): Nearest {
        fun point(face: BuilderConstructionSitePanelFace): BuilderSitePanelPlacement = when (face) {
            BuilderConstructionSitePanelFace.MIN_X -> BuilderSitePanelPlacement(minX - frontOffset, fixed.y, z.coerceIn(minZ, maxZ), face.yaw)
            BuilderConstructionSitePanelFace.MAX_X -> BuilderSitePanelPlacement(maxX + frontOffset, fixed.y, z.coerceIn(minZ, maxZ), face.yaw)
            BuilderConstructionSitePanelFace.MIN_Z -> BuilderSitePanelPlacement(x.coerceIn(minX, maxX), fixed.y, minZ - frontOffset, face.yaw)
            BuilderConstructionSitePanelFace.MAX_Z -> BuilderSitePanelPlacement(x.coerceIn(minX, maxX), fixed.y, maxZ + frontOffset, face.yaw)
        }
        fun distance(face: BuilderConstructionSitePanelFace) = point(face).let { hypot(x - it.x, z - it.z) }
        val closest = BuilderConstructionSitePanelFace.entries.minBy(::distance)
        // Keep a corner stable until the next side is meaningfully closer, without freezing tangent movement.
        val face = if (previous != null && distance(previous) <= distance(closest) + .35) previous else closest
        return Nearest(face, point(face))
    }

    companion object {
        fun create(origin: BuilderBlockPos, face: BuilderConstructionSitePanelFace, occupied: List<BuilderBlockPos>,
            heightOffset: Double, frontOffset: Double, panelWidth: Double, panelHeight: Double): BuilderBookPlaqueGeometry {
            val fixed = BuilderConstructionSiteDisplayLayout.panelPlacement(listOf(origin), face, heightOffset, frontOffset,
                occupied, panelWidth, panelHeight)
            val band = occupied.filter { it.y + 1 > fixed.y - BuilderWorldPlaque.VERTICAL_PADDING &&
                it.y < fixed.y + panelHeight + BuilderWorldPlaque.VERTICAL_PADDING + BuilderWorldPlaque.MAX_UPWARD_SHIFT }
            val boundary = band.ifEmpty { occupied.ifEmpty { listOf(origin) } }
            val full = occupied.ifEmpty { listOf(origin) }
            // An outer rectangle is deliberately conservative for concave houses: no spatial index or per-frame scan.
            return BuilderBookPlaqueGeometry(fixed, boundary.minOf { it.x }.toDouble(), boundary.minOf { it.z }.toDouble(),
                boundary.maxOf { it.x } + 1.0, boundary.maxOf { it.z } + 1.0, frontOffset,
                full.minOf { it.x }.toDouble(), full.minOf { it.z }.toDouble(),
                full.maxOf { it.x } + 1.0, full.maxOf { it.z } + 1.0)
        }
    }
}
