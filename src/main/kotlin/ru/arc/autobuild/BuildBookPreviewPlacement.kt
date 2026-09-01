package ru.arc.autobuild

import com.sk89q.worldedit.math.BlockVector3

internal enum class BuildBookPreviewMove {
    UP,
    DOWN,
    LEFT,
    RIGHT,
}

/**
 * Ephemeral placement of one open preview. It deliberately owns no book data:
 * moving or rotating a preview must never rewrite the held book or future copies.
 */
internal data class BuildBookPreviewPlacement(
    private val originalAnchor: BlockVector3,
    val baseRotation: Int,
    val maxOffset: Int,
    private val offsetX: Int = 0,
    private val offsetY: Int = 0,
    private val offsetZ: Int = 0,
    private val rotationDelta: Int = 0,
) {
    init {
        require(baseRotation in BuildBookTransform.CARDINAL_ROTATIONS) { "Preview base rotation must be cardinal" }
        require(maxOffset in 0..64) { "Preview offset bound is invalid" }
        require(offsetX in -maxOffset..maxOffset) { "Preview X offset is outside its bound" }
        require(offsetY in -maxOffset..maxOffset) { "Preview Y offset is outside its bound" }
        require(offsetZ in -maxOffset..maxOffset) { "Preview Z offset is outside its bound" }
        require(rotationDelta in BuildBookTransform.CARDINAL_ROTATIONS) { "Preview rotation must be cardinal" }
    }

    val anchor: BlockVector3 get() = originalAnchor.add(offsetX, offsetY, offsetZ)
    val rotation: Int get() = BuildBookTransform.normalizeRotation(baseRotation + rotationDelta)

    fun move(direction: BuildBookPreviewMove, playerRotation: Int): BuildBookPreviewPlacement {
        val facing = BuildBookTransform.normalizeRotation(playerRotation)
        require(facing in BuildBookTransform.CARDINAL_ROTATIONS) { "Preview movement rotation must be cardinal" }
        val (dx, dy, dz) = when (direction) {
            BuildBookPreviewMove.UP -> Triple(0, 1, 0)
            BuildBookPreviewMove.DOWN -> Triple(0, -1, 0)
            BuildBookPreviewMove.LEFT -> when (facing) {
                90 -> Triple(0, 0, -1)
                180 -> Triple(1, 0, 0)
                270 -> Triple(0, 0, 1)
                else -> Triple(-1, 0, 0)
            }
            BuildBookPreviewMove.RIGHT -> when (facing) {
                90 -> Triple(0, 0, 1)
                180 -> Triple(-1, 0, 0)
                270 -> Triple(0, 0, -1)
                else -> Triple(1, 0, 0)
            }
        }
        return copy(
            offsetX = (offsetX + dx).coerceIn(-maxOffset, maxOffset),
            offsetY = (offsetY + dy).coerceIn(-maxOffset, maxOffset),
            offsetZ = (offsetZ + dz).coerceIn(-maxOffset, maxOffset),
        )
    }

    fun rotate(delta: Int): BuildBookPreviewPlacement {
        require(delta % 90 == 0) { "Preview rotation delta must be cardinal" }
        return copy(rotationDelta = BuildBookTransform.normalizeRotation(rotationDelta + delta))
    }

    fun reset(): BuildBookPreviewPlacement = copy(offsetX = 0, offsetY = 0, offsetZ = 0, rotationDelta = 0)

    fun bookAdjustedAnchor(bookData: BuildBookData): BlockVector3 {
        val fullRotation = BuildBookRelativeRotation.resolve(rotation, bookData.sourceRotation, bookData.transform.rotation)
        val (x, y, z) = bookData.transform.rotatedOffset(fullRotation)
        return anchor.add(x, y, z)
    }
}
