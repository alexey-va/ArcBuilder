package ru.arc.autobuild

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.regions.CuboidRegion
import com.sk89q.worldedit.world.block.BlockState
import org.bukkit.Bukkit
import ru.arc.buildertools.BuilderClipboard
import ru.arc.buildertools.BuilderClipboardBlock
import ru.arc.buildertools.BuilderPlan
import ru.arc.buildertools.BuilderPlanningTasks
import ru.arc.buildertools.requireCurrentBuilderPlanning
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture

class PlayerBuildBookLimitException : IllegalStateException("Player build-book limit reached")

data class PlayerBuildBookTemplate(
    val buildingId: String,
    val contentSha256: String,
    val schematicSha256: String,
    val blockCount: Int,
)

internal sealed interface PlayerBuildBookDigestInspection {
    data object Missing : PlayerBuildBookDigestInspection
    data class Ready(val sha256: String) : PlayerBuildBookDigestInspection
    data class Failed(val failure: Throwable) : PlayerBuildBookDigestInspection
}

internal data class PreparedPlayerBuildBookTemplate(
    val creatorId: UUID,
    val fileName: String,
    val contentSha256: String,
    val blockCount: Int,
    val writeSchematic: (OutputStream) -> Unit,
)

/** Captured by the runtime before any storage work is accepted. */
internal data class PlayerBuildBookStorageSettings(
    val dataRoot: Path,
    val configuredRoot: String?,
    val maxBooksPerPlayer: Int,
)

internal object PlayerBuildBookAddress {
    private val SHA256 = Regex("[0-9a-f]{64}")
    private val ANCHOR = Regex("o(-?\\d{1,4})_(-?\\d{1,4})_(-?\\d{1,4})")

    fun matches(creatorId: UUID, buildingId: String, contentSha256: String): Boolean {
        if (!contentSha256.matches(SHA256)) return false
        val owner = creatorId.toString().replace("-", "")
        if (buildingId == "player-$owner-$contentSha256.schem") return true
        val prefix = "player-$owner-"
        val suffix = "-$contentSha256.schem"
        if (!buildingId.startsWith(prefix) || !buildingId.endsWith(suffix)) return false
        val anchor = ANCHOR.matchEntire(buildingId.removePrefix(prefix).removeSuffix(suffix)) ?: return false
        return anchor.groupValues.drop(1).all { value -> value.toIntOrNull() in -1_024..1_024 }
    }
}

object PlayerBuildBookStore {
    /**
     * Native conversion shares the runtime's per-tick capture budget. Validation,
     * hashing and WorldEdit clipboard assembly use detached values on a worker.
     */
    internal fun prepareAsync(
        creatorId: UUID,
        source: BuilderClipboard,
        tasks: BuilderPlanningTasks,
        isCurrent: () -> Boolean,
    ): CompletableFuture<PreparedPlayerBuildBookTemplate> = tasks.compute { source.validated(10_000) }
        .thenCompose { checked ->
            tasks.onMain {
                requireCurrentBuilderPlanning(isCurrent)
                val states = ArrayList<BlockState>(checked.blocks.size)
                tasks.capture(isCurrent) {
                    val block = checked.blocks[states.size]
                    states += BukkitAdapter.adapt(Bukkit.createBlockData(block.blockData))
                    states.size == checked.blocks.size
                }.thenCompose {
                    tasks.compute { prepareCaptured(creatorId, checked, states) }
                }
            }.thenCompose { it }
        }

    private fun prepareCaptured(
        creatorId: UUID,
        checked: BuilderClipboard,
        states: List<BlockState>,
    ): PreparedPlayerBuildBookTemplate {
        val region = CuboidRegion(
            BlockVector3.ZERO,
            BlockVector3.at(checked.sizeX - 1, checked.sizeY - 1, checked.sizeZ - 1),
        )
        val clipboard = BlockArrayClipboard(region).also {
            it.origin = BlockVector3.at(checked.originDx, checked.originDy, checked.originDz)
        }
        checked.blocks.forEachIndexed { index, block ->
            clipboard.setBlock(BlockVector3.at(block.dx, block.dy, block.dz), states[index])
        }
        val contentSha256 = contentSha256(checked)
        return PreparedPlayerBuildBookTemplate(
            creatorId = creatorId,
            fileName = fileName(creatorId, checked, contentSha256),
            contentSha256 = contentSha256,
            blockCount = checked.blocks.size,
            writeSchematic = { output ->
                BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getWriter(output).use { writer -> writer.write(clipboard) }
            },
        )
    }

    /** Filesystem-only persistence; safe for the owned storage executor. */
    internal fun persist(
        prepared: PreparedPlayerBuildBookTemplate,
        settings: PlayerBuildBookStorageSettings,
    ): PlayerBuildBookTemplate {
        require(prepared.contentSha256.matches(Regex("[0-9a-f]{64}"))) {
            "Player build-book content digest is invalid"
        }
        require(PlayerBuildBookAddress.matches(prepared.creatorId, prepared.fileName, prepared.contentSha256)) {
            "Player build-book filename does not match its content address"
        }
        val root = BuilderStoragePaths.schematicsRoot(settings.dataRoot, settings.configuredRoot)
        val target = root.resolve(prepared.fileName)
        require(target.parent == root) { "Player build-book target escaped the schematic root" }
        if (!isRegularFile(target)) enforceLimit(root, prepared.creatorId, settings.maxBooksPerPlayer)
        val schematicSha256 = writeAtomic(target, prepared.writeSchematic)
        return PlayerBuildBookTemplate(
            buildingId = prepared.fileName,
            contentSha256 = prepared.contentSha256,
            schematicSha256 = schematicSha256,
            blockCount = prepared.blockCount,
        )
    }

    internal fun fileName(creatorId: UUID, clipboard: BuilderClipboard): String {
        val checked = clipboard.validated(BuilderPlan.ABSOLUTE_MAX_CHANGES)
        return fileName(creatorId, checked, contentSha256(checked))
    }

    private fun fileName(creatorId: UUID, checked: BuilderClipboard, contentSha256: String): String {
        val origin = "o${checked.originDx}_${checked.originDy}_${checked.originDz}"
        return "player-${creatorId.toString().replace("-", "")}-$origin-$contentSha256.schem"
    }

    internal fun contentSha256(clipboard: BuilderClipboard): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("${clipboard.sizeX}:${clipboard.sizeY}:${clipboard.sizeZ}\n".toByteArray(StandardCharsets.UTF_8))
        clipboard.blocks.sortedWith(compareBy<BuilderClipboardBlock> { it.dy }.thenBy { it.dx }.thenBy { it.dz })
            .forEach { block ->
                digest.update("${block.dx}:${block.dy}:${block.dz}:${block.blockData}\n".toByteArray(StandardCharsets.UTF_8))
            }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    internal fun inspectSchematic(
        buildingId: String,
        settings: PlayerBuildBookStorageSettings,
    ): PlayerBuildBookDigestInspection = try {
        require(buildingId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,159}")))
        val root = BuilderStoragePaths.schematicsRoot(settings.dataRoot, settings.configuredRoot)
        val target = root.resolve(buildingId)
        require(target.parent == root) { "Player build-book inspection escaped the schematic root" }
        if (inspectRegularFile(target)) {
            PlayerBuildBookDigestInspection.Ready(sha256(target))
        } else {
            PlayerBuildBookDigestInspection.Missing
        }
    } catch (failure: Throwable) {
        PlayerBuildBookDigestInspection.Failed(failure)
    }

    private fun enforceLimit(root: Path, creatorId: UUID, maxBooksPerPlayer: Int) {
        val prefix = "player-${creatorId.toString().replace("-", "")}-"
        val count = Files.list(root).use { files ->
            files.filter { path ->
                isRegularFile(path) && path.fileName.toString().startsWith(prefix) && path.fileName.toString().endsWith(".schem")
            }.count()
        }
        if (count >= maxBooksPerPlayer) throw PlayerBuildBookLimitException()
    }

    private fun writeAtomic(target: Path, writeSchematic: (OutputStream) -> Unit): String {
        if (isRegularFile(target)) return sha256(target)
        val temporary = Files.createTempFile(target.parent, ".${target.fileName}.", ".tmp")
        try {
            Files.newOutputStream(temporary).use(writeSchematic)
            val preparedSha256 = sha256(temporary)
            val published = try {
                // The temporary file lives in the same directory. Publishing
                // it as a hard link is atomic and, unlike ATOMIC_MOVE, has
                // strict create-only semantics when the target already exists.
                Files.createLink(target, temporary)
                true
            } catch (_: UnsupportedOperationException) {
                moveWithoutReplacement(temporary, target)
            } catch (_: FileAlreadyExistsException) {
                require(isRegularFile(target)) { "Concurrent player build-book target is not a regular file" }
                false
            }
            require(isRegularFile(target)) { "Player build-book durable target is not a regular file" }
            val durableSha256 = sha256(target)
            if (published) require(durableSha256 == preparedSha256) {
                "Player build-book durable readback did not match the published schematic"
            }
            return durableSha256
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun moveWithoutReplacement(temporary: Path, target: Path): Boolean =
        try {
            Files.move(temporary, target)
            true
        } catch (_: FileAlreadyExistsException) {
            require(isRegularFile(target)) { "Concurrent player build-book target is not a regular file" }
            false
        }

    private fun isRegularFile(path: Path): Boolean = Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)

    /** Returns false only for a proven absent path; inaccessible or unsafe paths fail closed. */
    private fun inspectRegularFile(path: Path): Boolean = try {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attributes.isRegularFile) { "Player build-book schematic is not a regular file" }
        true
    } catch (_: NoSuchFileException) {
        false
    }

    private fun sha256(path: Path): String = Files.newInputStream(path).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
