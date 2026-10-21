package ru.arc.buildertools

internal data class BuilderDisplayEdge(
    val x: Double,
    val y: Double,
    val z: Double,
    val scaleX: Float,
    val scaleY: Float,
    val scaleZ: Float,
)

internal data class BuilderDisplayBlockTransform(
    val offset: Float,
    val scale: Float,
)

internal data class BuilderDisplaySceneDelta<T>(
    val retained: Set<T>,
    val added: List<T>,
    val removed: Set<T>,
)

internal object BuilderDisplaySceneDiff {
    fun <T> between(previous: Set<T>, next: List<T>): BuilderDisplaySceneDelta<T> {
        val distinctNext = next.distinct()
        val nextSet = distinctNext.toSet()
        return BuilderDisplaySceneDelta(
            retained = previous.intersect(nextSet),
            added = distinctNext.filterNot(previous::contains),
            removed = previous - nextSet,
        )
    }
}

/** Exact twelve-edge cuboid used by every selection and preview layer. */
internal object BuilderDisplayGeometry {
    private const val DEFAULT_THICKNESS = .035f

    // Straddle the block edges so an occupied anchor cannot bury the marker.
    fun originMarker(position: BuilderBlockPos): List<BuilderDisplayEdge> = bounds(listOf(position), .08f)

    fun blockTransform(scale: Float): BuilderDisplayBlockTransform {
        require(scale.isFinite() && scale in .5f..1f)
        return BuilderDisplayBlockTransform(
            offset = (1f - scale) / 2f,
            scale = scale,
        )
    }

    fun bounds(
        positions: List<BuilderBlockPos>,
        thickness: Float = DEFAULT_THICKNESS,
    ): List<BuilderDisplayEdge> {
        if (positions.isEmpty()) return emptyList()
        require(thickness.isFinite() && thickness > 0f)
        val minX = positions.minOf { it.x }.toDouble()
        val minY = positions.minOf { it.y }.toDouble()
        val minZ = positions.minOf { it.z }.toDouble()
        val maxX = positions.maxOf { it.x } + 1.0
        val maxY = positions.maxOf { it.y } + 1.0
        val maxZ = positions.maxOf { it.z } + 1.0
        require(thickness < minOf(maxX - minX, maxY - minY, maxZ - minZ))
        val half = thickness / 2.0
        return buildList(12) {
            // X rails own all eight corners. The Y/Z rails meet their inner faces
            // with butt joints, keeping the same silhouette without self-overlap.
            for (y in listOf(minY, maxY)) for (z in listOf(minZ, maxZ)) {
                add(BuilderDisplayEdge(minX - half, y - half, z - half, (maxX - minX + thickness).toFloat(), thickness, thickness))
            }
            for (x in listOf(minX, maxX)) for (z in listOf(minZ, maxZ)) {
                add(BuilderDisplayEdge(x - half, minY + half, z - half, thickness, (maxY - minY - thickness).toFloat(), thickness))
            }
            for (x in listOf(minX, maxX)) for (y in listOf(minY, maxY)) {
                add(BuilderDisplayEdge(x - half, y - half, minZ + half, thickness, thickness, (maxZ - minZ - thickness).toFloat()))
            }
        }
    }
}
