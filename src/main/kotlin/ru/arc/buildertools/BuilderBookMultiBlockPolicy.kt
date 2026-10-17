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

/** Native block state is reduced to immutable pair identity on the server thread. */
internal data class BuilderBookCapturedCell(
    val position: BuilderBlockPos,
    val companion: BuilderBlockPos?,
    val pairKey: String?,
    val primary: Boolean,
    val skippedUnsafe: Boolean,
    val placement: BuilderBookCapturedChange?,
)

/** Prevents one half of a bed, door, or other bisected block from entering a book plan alone. */
internal object BuilderBookMultiBlockPolicy {
    fun capture(cell: BuilderBookPlannedCell, placement: BuilderBookCapturedChange?): BuilderBookCapturedCell =
        BuilderBookCapturedCell(
            position = cell.position,
            companion = companionPosition(cell.position, cell.after),
            pairKey = when (val data = cell.after) {
                is Bed -> "bed:${data.material}:${data.facing}"
                is Bisected -> if (isVerticalPair(data)) "vertical:${data.material}" else null
                else -> null
            },
            primary = isPrimary(cell.after),
            skippedUnsafe = cell.placement == BuilderBookPlacementResult.SkippedUnsafe,
            placement = placement,
        )

    /** Worker-only counterpart of the native policy; equality includes both bed direction and material. */
    fun rejectedCaptured(cells: List<BuilderBookCapturedCell>): Set<BuilderBlockPos> {
        val byPosition = cells.associateBy(BuilderBookCapturedCell::position)
        return buildSet {
            cells.forEach { cell ->
                val companion = cell.companion ?: return@forEach
                val partner = byPosition[companion]
                if (partner == null || !matchingCaptured(cell, partner) || cell.skippedUnsafe || partner.skippedUnsafe ||
                    (cell.placement != null) != (partner.placement != null)) {
                    add(cell.position)
                    add(companion)
                }
            }
        }
    }

    fun primaryFirstCaptured(cells: List<BuilderBookCapturedCell>): List<BuilderBookCapturedCell> {
        val indexed = cells.withIndex().associateBy { it.value.position }
        return cells.withIndex().sortedWith(
            compareBy<IndexedValue<BuilderBookCapturedCell>> { cell ->
                val partner = cell.value.companion?.let(indexed::get)?.takeIf { matchingCaptured(cell.value, it.value) }
                minOf(cell.index, partner?.index ?: cell.index)
            }.thenBy { if (it.value.primary) 0 else 1 }.thenBy { it.index },
        ).map { it.value }
    }

    private fun matchingCaptured(first: BuilderBookCapturedCell, second: BuilderBookCapturedCell): Boolean =
        first.pairKey != null && first.pairKey == second.pairKey && first.primary != second.primary

    /** Keeps the item-owning half first and its companion immediately after it. */
    fun primaryFirst(cells: List<BuilderBookPlannedCell>): List<BuilderBookPlannedCell> {
        val indexed = cells.withIndex().associateBy { it.value.position }
        return cells.withIndex()
            .sortedWith(
                compareBy<IndexedValue<BuilderBookPlannedCell>> { indexedCell ->
                    val partner = companionPosition(indexedCell.value.position, indexedCell.value.after)
                        ?.let(indexed::get)
                        ?.takeIf { matchingPair(indexedCell.value.after, it.value.after) }
                    minOf(indexedCell.index, partner?.index ?: indexedCell.index)
                }.thenBy { indexedCell ->
                    if (isPrimary(indexedCell.value.after)) 0 else 1
                }.thenBy(IndexedValue<BuilderBookPlannedCell>::index),
            )
            .map(IndexedValue<BuilderBookPlannedCell>::value)
    }

    fun rejectedPositions(cells: List<BuilderBookPlannedCell>): Set<BuilderBlockPos> {
        val byPosition = cells.associateBy(BuilderBookPlannedCell::position)
        return buildSet {
            cells.forEach { cell ->
                val partnerPosition = companionPosition(cell.position, cell.after) ?: return@forEach
                val partner = byPosition[partnerPosition]
                if (
                    partner == null ||
                    !matchingPair(cell.after, partner.after) ||
                    cell.placement == BuilderBookPlacementResult.SkippedUnsafe ||
                    partner.placement == BuilderBookPlacementResult.SkippedUnsafe ||
                    (cell.placement is BuilderBookPlacementResult.Change) !=
                    (partner.placement is BuilderBookPlacementResult.Change)
                ) {
                    add(cell.position)
                    add(partnerPosition)
                }
            }
        }
    }

    fun companionPosition(position: BuilderBlockPos, data: BlockData): BuilderBlockPos? = when (data) {
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

    fun matchingPair(first: BlockData, second: BlockData): Boolean = when {
        first is Bed && second is Bed ->
            first.material == second.material && first.part != second.part && first.facing == second.facing
        first is Bisected && second is Bisected && isVerticalPair(first) && isVerticalPair(second) ->
            first.material == second.material && first.half != second.half
        else -> false
    }

    fun isPrimary(data: BlockData): Boolean = when (data) {
        is Bed -> data.part == Bed.Part.FOOT
        is Bisected -> isVerticalPair(data) && data.half == Bisected.Half.BOTTOM
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
