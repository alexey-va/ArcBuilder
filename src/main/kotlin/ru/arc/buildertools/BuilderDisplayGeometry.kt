package ru.arc.buildertools

internal data class BuilderDisplayEdge(
    val x: Double,
    val y: Double,
    val z: Double,
    val scaleX: Float,
    val scaleY: Float,
    val scaleZ: Float,
)

/** Exact twelve-edge cuboid used by every selection and preview layer. */
internal object BuilderDisplayGeometry {
    private const val THICKNESS = .035f

    fun bounds(positions: List<BuilderBlockPos>): List<BuilderDisplayEdge> {
        if (positions.isEmpty()) return emptyList()
        val minX = positions.minOf { it.x }.toDouble()
        val minY = positions.minOf { it.y }.toDouble()
        val minZ = positions.minOf { it.z }.toDouble()
        val maxX = positions.maxOf { it.x } + 1.0
        val maxY = positions.maxOf { it.y } + 1.0
        val maxZ = positions.maxOf { it.z } + 1.0
        val half = THICKNESS / 2.0
        return buildList(12) {
            for (y in listOf(minY, maxY)) for (z in listOf(minZ, maxZ)) {
                add(BuilderDisplayEdge(minX, y - half, z - half, (maxX - minX).toFloat(), THICKNESS, THICKNESS))
            }
            for (x in listOf(minX, maxX)) for (z in listOf(minZ, maxZ)) {
                add(BuilderDisplayEdge(x - half, minY, z - half, THICKNESS, (maxY - minY).toFloat(), THICKNESS))
            }
            for (x in listOf(minX, maxX)) for (y in listOf(minY, maxY)) {
                add(BuilderDisplayEdge(x - half, y - half, minZ, THICKNESS, THICKNESS, (maxZ - minZ).toFloat()))
            }
        }
    }
}
