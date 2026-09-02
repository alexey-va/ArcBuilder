package ru.arc.buildertools

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import java.util.UUID
import kotlin.math.floor

internal data class BuilderConstructionTeleportColumn(val x: Int, val z: Int)

internal object BuilderConstructionTeleport {
    private val unsafeFloor = setOf(
        Material.CACTUS,
        Material.CAMPFIRE,
        Material.FIRE,
        Material.LAVA,
        Material.MAGMA_BLOCK,
        Material.SOUL_CAMPFIRE,
        Material.SOUL_FIRE,
        Material.SWEET_BERRY_BUSH,
    )

    fun candidateColumns(project: BuilderConstructionProjectRecord): List<BuilderConstructionTeleportColumn> {
        val positions = project.steps.map { it.change.position }
        val minX = positions.minOf(BuilderBlockPos::x)
        val maxX = positions.maxOf(BuilderBlockPos::x)
        val minZ = positions.minOf(BuilderBlockPos::z)
        val maxZ = positions.maxOf(BuilderBlockPos::z)
        val centerX = floor((minX + maxX) / 2.0).toInt()
        val centerZ = floor((minZ + maxZ) / 2.0).toInt()
        val offsets = listOf(0, -1, 1, -2, 2, -3, 3)
        return when (project.sitePanelFace ?: BuilderConstructionSitePanelFace.MAX_Z) {
            BuilderConstructionSitePanelFace.MIN_X -> offsets.map { BuilderConstructionTeleportColumn(minX - 2, centerZ + it) }
            BuilderConstructionSitePanelFace.MAX_X -> offsets.map { BuilderConstructionTeleportColumn(maxX + 2, centerZ + it) }
            BuilderConstructionSitePanelFace.MIN_Z -> offsets.map { BuilderConstructionTeleportColumn(centerX + it, minZ - 2) }
            BuilderConstructionSitePanelFace.MAX_Z -> offsets.map { BuilderConstructionTeleportColumn(centerX + it, maxZ + 2) }
        }
    }

    fun findSafeDestination(world: World, project: BuilderConstructionProjectRecord): Location? {
        val positions = project.steps.map { it.change.position }
        if (positions.first().worldId != world.uid) return null
        val minY = positions.minOf(BuilderBlockPos::y)
        val maxY = positions.maxOf(BuilderBlockPos::y)
        val yCandidates = buildList {
            add(minY)
            for (distance in 1..16) {
                add(minY + distance)
                add(minY - distance)
            }
            add(maxY + 1)
        }.distinct().filter { it >= world.minHeight + 1 && it < world.maxHeight - 1 }
        val centerX = positions.sumOf { it.x.toLong() }.toDouble() / positions.size + 0.5
        val centerY = positions.sumOf { it.y.toLong() }.toDouble() / positions.size + 0.5
        val centerZ = positions.sumOf { it.z.toLong() }.toDouble() / positions.size + 0.5

        return candidateColumns(project).firstNotNullOfOrNull { column ->
            yCandidates.firstNotNullOfOrNull { y ->
                val feet = world.getBlockAt(column.x, y, column.z)
                val head = world.getBlockAt(column.x, y + 1, column.z)
                val floor = world.getBlockAt(column.x, y - 1, column.z)
                val candidate = Location(world, column.x + 0.5, y.toDouble(), column.z + 0.5)
                if (!world.worldBorder.isInside(candidate) || !feet.isPassable || feet.isLiquid ||
                    !head.isPassable || head.isLiquid || !floor.type.isSolid || floor.type in unsafeFloor
                ) {
                    null
                } else {
                    candidate.apply {
                        direction = org.bukkit.util.Vector(centerX - x, centerY - (y + 1.62), centerZ - z)
                        pitch = 0f
                    }
                }
            }
        }
    }

    fun worldId(project: BuilderConstructionProjectRecord): UUID = project.steps.first().change.position.worldId
}
