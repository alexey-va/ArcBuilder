package ru.arc.buildertools

import org.bukkit.block.BlockFace
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.type.Bed
import org.bukkit.block.data.type.Door

internal data class BuilderBookPlannedCell(
    val position: BuilderBlockPos,
    val after: BlockData,
    val placement: BuilderBookPlacementResult,
)

/** Prevents one half of a bed, door, or other bisected block from entering a book plan alone. */
internal object BuilderBookMultiBlockPolicy {
    fun rejectedPositions(cells: List<BuilderBookPlannedCell>): Set<BuilderBlockPos> {
        val byPosition = cells.associateBy(BuilderBookPlannedCell::position)
        return buildSet {
            cells.forEach { cell ->
                val partnerPosition = partnerPosition(cell.position, cell.after) ?: return@forEach
                val partner = byPosition[partnerPosition]
                if (
                    partner == null ||
                    !matchingPair(cell.after, partner.after) ||
                    cell.placement == BuilderBookPlacementResult.SkippedUnsafe ||
                    partner.placement == BuilderBookPlacementResult.SkippedUnsafe
                ) {
                    add(cell.position)
                    add(partnerPosition)
                }
            }
        }
    }

    private fun partnerPosition(position: BuilderBlockPos, data: BlockData): BuilderBlockPos? = when (data) {
        is Bed -> {
            val direction = if (data.part == Bed.Part.FOOT) data.facing else data.facing.oppositeFace
            position.offset(direction)
        }
        is Bisected -> if (isVerticalPair(data)) {
            position.copy(y = position.y + if (data.half == Bisected.Half.BOTTOM) 1 else -1)
        } else {
            null
        }
        else -> null
    }

    private fun matchingPair(first: BlockData, second: BlockData): Boolean = when {
        first is Bed && second is Bed ->
            first.material == second.material && first.part != second.part && first.facing == second.facing
        first is Bisected && second is Bisected && isVerticalPair(first) && isVerticalPair(second) ->
            first.material == second.material && first.half != second.half
        else -> false
    }

    private fun BuilderBlockPos.offset(face: BlockFace): BuilderBlockPos = copy(
        x = x + face.modX,
        y = y + face.modY,
        z = z + face.modZ,
    )

    private fun isVerticalPair(data: Bisected): Boolean = data is Door || data.material.name in VERTICAL_PAIR_MATERIALS

    private val VERTICAL_PAIR_MATERIALS = setOf(
        "SUNFLOWER",
        "LILAC",
        "ROSE_BUSH",
        "PEONY",
        "TALL_GRASS",
        "LARGE_FERN",
        "PITCHER_PLANT",
        "SMALL_DRIPLEAF",
    )
}
