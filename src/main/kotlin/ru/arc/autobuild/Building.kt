package ru.arc.autobuild

import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats
import com.sk89q.worldedit.math.BlockVector3
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

    fun getBlock(relative: BlockVector3, rotation: Int): BaseBlock {
        val source = relative.rotate(-rotation).add(clipboard.origin)
        return clipboard.getFullBlock(source)
    }

    fun getCorner1(rotation: Int): BlockVector3 = clipboard.minimumPoint.subtract(clipboard.origin).rotate(rotation)
    fun getCorner2(rotation: Int): BlockVector3 = clipboard.maximumPoint.subtract(clipboard.origin).rotate(rotation)

    private fun BlockVector3.rotate(degrees: Int): BlockVector3 = when (((degrees % 360) + 360) % 360) {
        90 -> BlockVector3.at(-z(), y(), x())
        180 -> BlockVector3.at(-x(), y(), -z())
        270 -> BlockVector3.at(z(), y(), -x())
        else -> this
    }
}
