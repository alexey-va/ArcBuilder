package ru.arc.autobuild

import org.enginehub.linbus.stream.LinBinaryIO
import org.enginehub.linbus.tree.LinCompoundTag
import org.enginehub.linbus.tree.LinIntArrayTag
import org.enginehub.linbus.tree.LinRootEntry
import org.enginehub.linbus.tree.LinTagType
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Maintenance entrypoint kept in the test runtime so WorldEdit is never bundled into ArcBuilder. */
object SchematicOriginTool {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 3) { "Usage: <input.schem> <output.schem> <world-shift-y>" }
        val input = Path.of(args[0]).toAbsolutePath().normalize()
        val output = Path.of(args[1]).toAbsolutePath().normalize()
        val shiftY = args[2].toInt()
        require(shiftY in -64..64 && shiftY != 0) { "World Y shift must be between -64 and 64 and non-zero" }
        require(Files.isRegularFile(input)) { "Input schematic is missing" }
        require(input != output) { "Input and output must be different files" }

        val root = DataInputStream(GZIPInputStream(Files.newInputStream(input))).use { data ->
            LinRootEntry.readFrom(LinBinaryIO.read(data))
        }
        val rootValues = root.value().value().toMutableMap()
        val schematic = root.value().getTag("Schematic", LinTagType.compoundTag())
        val schematicValues = schematic.value().toMutableMap()
        val offset = schematic.getTag("Offset", LinTagType.intArrayTag()).value().clone()
        require(offset.size == 3) { "Schematic Offset must contain exactly three coordinates" }
        val before = offset.clone()

        // Sponge stores the clipboard minimum relative to its origin in Offset.
        // Raising Offset raises placement without rewriting block/entity coordinates.
        offset[1] = Math.addExact(offset[1], shiftY)
        schematicValues["Offset"] = LinIntArrayTag.of(*offset)
        rootValues["Schematic"] = LinCompoundTag.of(schematicValues)
        val updated = LinRootEntry(root.name(), LinCompoundTag.of(rootValues))

        DataOutputStream(
            GZIPOutputStream(
                Files.newOutputStream(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
            ),
        ).use { data -> LinBinaryIO.write(data, updated) }
        println("offset=${before.joinToString(",")} -> ${offset.joinToString(",")}")
    }
}
