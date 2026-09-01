package ru.arc.autobuild

import com.sk89q.worldedit.math.BlockVector3
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player

internal enum class PreviewTransformUpdateResult(val allowsBookUpdate: Boolean = false) {
    NO_PREVIEW(true),
    UPDATED(true),
    PREVIEW_INACTIVE,
    BOOK_MISMATCH,
    PROTECTION_DENIED,
}

internal object BuildBookRelativeRotation {
    fun resolve(playerRotation: Int, sourceRotation: Int, manualRotation: Int): Int =
        BuildBookTransform.normalizeRotation(playerRotation - sourceRotation + manualRotation)
}

/** Lightweight placement model; construction itself is owned by BuilderPlan. */
class ConstructionSite internal constructor(
    val building: Building,
    centerBlock: Location,
    val player: Player,
    rotation: Int,
    val world: World,
    bookData: BuildBookData,
    val expiresAtMillis: Long,
    maxPlacementOffset: Int,
    initialPlacement: BuildBookPreviewPlacement? = null,
) {
    data class Corners(val corner1: BlockVector3, val corner2: BlockVector3)

    var bookData: BuildBookData = bookData
        private set

    private var placement: BuildBookPreviewPlacement = initialPlacement ?: BuildBookPreviewPlacement(
        originalAnchor = BlockVector3.at(centerBlock.blockX, centerBlock.blockY, centerBlock.blockZ),
        baseRotation = rotation,
        maxOffset = maxPlacementOffset,
    )

    val centerBlock: Location get() = placement.anchor.let { anchor ->
        Location(world, anchor.x().toDouble(), anchor.y().toDouble(), anchor.z().toDouble())
    }
    val rotation: Int get() = placement.rotation

    val fullRotation: Int get() = BuildBookRelativeRotation.resolve(
        rotation,
        bookData.sourceRotation,
        bookData.transform.rotation,
    )
    val adjustedCenter: Location get() {
        val (x, y, z) = bookData.transform.rotatedOffset(fullRotation)
        return centerBlock.clone().add(x.toDouble(), y.toDouble(), z.toDouble())
    }
    val corners: Corners get() {
        val first = building.getCorner1(fullRotation)
        val second = building.getCorner2(fullRotation)
        return Corners(
            BlockVector3.at(minOf(first.x(), second.x()), minOf(first.y(), second.y()), minOf(first.z(), second.z())),
            BlockVector3.at(maxOf(first.x(), second.x()), maxOf(first.y(), second.y()), maxOf(first.z(), second.z())),
        )
    }

    fun isExactOpenPreview(player: Player, expected: BuildBookData): Boolean =
        this.player.uniqueId == player.uniqueId && bookData == expected

    internal fun update(next: BuildBookData): PreviewTransformUpdateResult {
        if (next.buildingId != building.fileName || next.blueprintId != bookData.blueprintId) {
            return PreviewTransformUpdateResult.BOOK_MISMATCH
        }
        bookData = next.validated()
        return PreviewTransformUpdateResult.UPDATED
    }

    internal fun move(direction: BuildBookPreviewMove, playerRotation: Int) {
        placement = placement.move(direction, playerRotation)
    }

    internal fun rotate(delta: Int) {
        placement = placement.rotate(delta)
    }

    internal fun resetPlacement() {
        placement = placement.reset()
    }

    internal fun snapshot(): ConstructionSiteSnapshot = ConstructionSiteSnapshot(
        building = building,
        player = player,
        world = world,
        bookData = bookData,
        expiresAtMillis = expiresAtMillis,
        placement = placement,
    )

    fun relativePositionsBottomUp(): Sequence<BlockVector3> = sequence {
        val bounds = corners
        for (y in bounds.corner1.y()..bounds.corner2.y()) {
            for (x in bounds.corner1.x()..bounds.corner2.x()) {
                for (z in bounds.corner1.z()..bounds.corner2.z()) yield(BlockVector3.at(x, y, z))
            }
        }
    }

    fun worldLocation(relative: BlockVector3): Location = adjustedCenter.clone().add(
        relative.x().toDouble(), relative.y().toDouble(), relative.z().toDouble(),
    )

    fun cancelSilently(): Boolean {
        BuildingManager.closePreview(player.uniqueId)
        return true
    }
}

internal data class ConstructionSiteSnapshot(
    val building: Building,
    val player: Player,
    val world: World,
    val bookData: BuildBookData,
    val expiresAtMillis: Long,
    val placement: BuildBookPreviewPlacement,
)
