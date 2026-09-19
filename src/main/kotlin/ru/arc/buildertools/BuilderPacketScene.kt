package ru.arc.buildertools

import java.util.UUID
import kotlin.math.floor

internal data class BuilderPacketViewer(
    val connection: BuilderPreviewConnection,
    val sentChunks: Set<Long>,
)

/** Main-thread viewer state for one ephemeral preview, including client chunk eviction. */
internal class BuilderPacketScene : AutoCloseable {
    private data class Viewer(
        val connection: BuilderPreviewConnection,
        val shown: MutableMap<Int, BuilderPacketDisplay>,
    )

    private val viewers = mutableMapOf<UUID, Viewer>()

    fun update(displays: Collection<BuilderPacketDisplay>, audience: Map<UUID, BuilderPacketViewer>) {
        (viewers.keys - audience.keys).forEach(::removeViewer)
        audience.forEach { (playerId, snapshot) ->
            var previous = viewers[playerId]
            if (previous != null && previous.connection.identity !== snapshot.connection.identity) {
                removeViewer(playerId)
                previous = null
            }
            val desired = displays.asSequence()
                .filter { it.chunkKey() in snapshot.sentChunks }
                .associateBy(BuilderPacketDisplay::entityId)
            val shown = previous?.shown.orEmpty()
            val removed = (shown.keys - desired.keys).toList()
            val added = desired.filterKeys { it !in shown }.values.toList()
            if (removed.isNotEmpty() || added.isNotEmpty()) snapshot.connection.send(removed, added)
            viewers[playerId] = Viewer(snapshot.connection, desired.toMutableMap())
        }
    }

    fun forgetChunk(playerId: UUID, chunkKey: Long) {
        val viewer = viewers[playerId] ?: return
        val removed = viewer.shown.values.filter { it.chunkKey() == chunkKey }.map(BuilderPacketDisplay::entityId)
        if (removed.isEmpty()) return
        viewer.connection.send(removed, emptyList())
        removed.forEach(viewer.shown::remove)
    }

    fun removeViewer(playerId: UUID) {
        val viewer = viewers.remove(playerId) ?: return
        if (viewer.shown.isNotEmpty()) viewer.connection.send(viewer.shown.keys.toList(), emptyList())
    }

    override fun close() {
        viewers.keys.toList().forEach(::removeViewer)
    }
}

internal fun BuilderPacketDisplay.chunkKey(): Long {
    val chunkX = floor(x).toInt() shr 4
    val chunkZ = floor(z).toInt() shr 4
    return (chunkX.toLong() and 0xffffffffL) or (chunkZ.toLong() shl 32)
}
