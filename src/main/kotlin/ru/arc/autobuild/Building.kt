package ru.arc.autobuild

import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats
import com.sk89q.worldedit.extent.transform.BlockTransformExtent
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.math.transform.AffineTransform
import com.sk89q.worldedit.world.block.BaseBlock
import ru.arc.ARC
import java.nio.file.Files

class Building(val fileName: String) {
    @Volatile private var loaded: Clipboard? = null
    val clipboard: Clipboard get() = loaded ?: loadClipboard().also { loaded = it }
    val volume: Long get() = clipboard.region.volume

    private fun loadClipboard(): Clipboard {
        val file = BuilderStoragePaths.schematicsRoot().resolve(fileName).toFile()
        require(file.isFile) { "Schematic file not found: $fileName" }
        val format = ClipboardFormats.findByFile(file)
            ?: throw IllegalArgumentException("Unknown schematic format: $fileName")
        return Files.newInputStream(file.toPath()).use { input -> format.getReader(input).use { it.read() } }
    }

    fun getBlock(relative: BlockVector3, rotation: Int, mirrored: Boolean = false): BaseBlock {
        val source = BuildBookStructureTransform.sourceRelative(relative, rotation, mirrored).add(clipboard.origin)
        return BuildBookStructureTransform.mirrorBlock(clipboard.getFullBlock(source), mirrored)
    }

    fun getCorner1(rotation: Int, mirrored: Boolean = false): BlockVector3 = BuildBookStructureTransform.targetRelative(
        clipboard.minimumPoint.subtract(clipboard.origin),
        rotation,
        mirrored,
    )

    fun getCorner2(rotation: Int, mirrored: Boolean = false): BlockVector3 = BuildBookStructureTransform.targetRelative(
        clipboard.maximumPoint.subtract(clipboard.origin),
        rotation,
        mirrored,
    )
}

internal object BuildBookStructureTransform {
    private val horizontalMirror = AffineTransform().scale(-1.0, 1.0, 1.0)

    fun sourceRelative(worldRelative: BlockVector3, rotation: Int, mirrored: Boolean): BlockVector3 {
        val local = rotate(worldRelative, -rotation)
        return if (mirrored) BlockVector3.at(-local.x(), local.y(), local.z()) else local
    }

    fun targetRelative(sourceRelative: BlockVector3, rotation: Int, mirrored: Boolean): BlockVector3 {
        val local = if (mirrored) {
            BlockVector3.at(-sourceRelative.x(), sourceRelative.y(), sourceRelative.z())
        } else {
            sourceRelative
        }
        return rotate(local, rotation)
    }

    fun mirrorBlock(block: BaseBlock, mirrored: Boolean): BaseBlock =
        if (mirrored) BlockTransformExtent.transform(block, horizontalMirror) else block

    fun rotate(vector: BlockVector3, degrees: Int): BlockVector3 = when (((degrees % 360) + 360) % 360) {
        90 -> BlockVector3.at(-vector.z(), vector.y(), vector.x())
        180 -> BlockVector3.at(-vector.x(), vector.y(), -vector.z())
        270 -> BlockVector3.at(vector.z(), vector.y(), -vector.x())
        else -> vector
    }
}
