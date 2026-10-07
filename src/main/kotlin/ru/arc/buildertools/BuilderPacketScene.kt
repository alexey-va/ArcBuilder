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
        val desired: MutableMap<Int, BuilderPacketDisplay>,
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
            val previousDesired = previous?.desired.orEmpty()
            if (previousDesired != desired) {
                val removedIds = previousDesired.keys - desired.keys
                snapshot.connection.submit(desired.values.toList(), removedIds)
            }
            viewers[playerId] = Viewer(snapshot.connection, desired.toMutableMap())
        }
    }

    fun forgetChunk(playerId: UUID, chunkKey: Long) {
        val viewer = viewers[playerId] ?: return
        val forgotten = viewer.desired.values.filter { it.chunkKey() == chunkKey }
        if (forgotten.isEmpty()) return
        val retained = viewer.desired.values.filterNot { it.chunkKey() == chunkKey }
        viewer.desired.clear()
        retained.forEach { viewer.desired[it.entityId] = it }
        viewer.connection.submit(retained, forgotten.mapTo(linkedSetOf(), BuilderPacketDisplay::entityId))
    }

    fun removeViewer(playerId: UUID) {
        val viewer = viewers.remove(playerId) ?: return
        if (viewer.desired.isNotEmpty()) viewer.connection.submit(emptyList(), viewer.desired.keys)
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
