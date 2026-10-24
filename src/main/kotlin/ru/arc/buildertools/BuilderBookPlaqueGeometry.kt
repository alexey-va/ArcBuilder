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
) {
    data class Nearest(val face: BuilderConstructionSitePanelFace, val placement: BuilderSitePanelPlacement)

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
            // An outer rectangle is deliberately conservative for concave houses: no spatial index or per-frame scan.
            return BuilderBookPlaqueGeometry(fixed, boundary.minOf { it.x }.toDouble(), boundary.minOf { it.z }.toDouble(),
                boundary.maxOf { it.x } + 1.0, boundary.maxOf { it.z } + 1.0, frontOffset)
        }
    }
}
