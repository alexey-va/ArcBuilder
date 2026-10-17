package ru.arc.buildertools

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.netty.channel.ChannelHelper
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.util.Quaternion4f
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.util.Vector3f
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import org.bukkit.Bukkit
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.arc.paper.api.VisualPacketAdmission
import ru.arc.paper.packet.PaperVisualPackets
import java.lang.ref.WeakReference
import java.util.Optional
import java.util.WeakHashMap
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import java.util.logging.Logger

/** Main-thread snapshot used to render one client-only BlockDisplay. */
internal data class BuilderPacketDisplay(
    val entityId: Int,
    val uuid: UUID,
    val x: Double,
    val y: Double,
    val z: Double,
    val blockStateId: Int,
    val scaleX: Float,
    val scaleY: Float,
    val scaleZ: Float,
    val translateX: Float,
    val translateY: Float,
    val translateZ: Float,
    val glowRgb: Int,
    val viewRange: Float,
    val glowing: Boolean = true,
)

/** Packet-only replacement for the native preview entity lifecycle. */
internal interface BuilderPreviewPacketTransport : AutoCloseable {
    /** Must be called on the Bukkit thread because PacketEvents reads server registries. */
    fun blockStateId(blockData: BlockData): Int

    /** IDs are allocated by the caller on the Bukkit thread and never become server entities. */
    fun nextEntityId(): Int

    /** Captures the current player channel; all later calls use only that channel snapshot. */
    fun connection(player: Player): BuilderPreviewConnection?

    fun forget(player: Player) = Unit
    /** Completion means terminal packet cleanup, not merely scheduling it. */
    fun closeAsync(): CompletableFuture<Void> { close(); return CompletableFuture.completedFuture(null) }
    override fun close() = Unit
}

internal interface BuilderPreviewConnection {
    /** Reference-stable for one channel attachment; changes after a reconnect. */
    val identity: Any

    /** Replaces the queued target state; intermediate snapshots are intentionally discarded. */
    fun submit(desired: List<BuilderPacketDisplay>, forceResetIds: Set<Int> = emptySet())
}

/** Narrow event-loop seam for exercising coalescing and retries without a live Netty connection. */
internal interface BuilderPreviewPacketGateway {
    fun isOpen(channel: Any): Boolean
    fun execute(channel: Any, task: () -> Unit)
    fun retry(channel: Any, task: () -> Unit)
    fun display(channel: Any, display: BuilderPacketDisplay): VisualPacketAdmission
    fun glow(channel: Any, display: BuilderPacketDisplay): VisualPacketAdmission
    fun destroy(channel: Any, entityIds: List<Int>): VisualPacketAdmission
    fun flush(channel: Any)
}

/** PacketEvents wrapper encoder routed through ARC's shared visual budget. */
internal class PaperBuilderPreviewPacketGateway(
    plugin: Plugin,
) : BuilderPreviewPacketGateway {
    private val packets = PaperVisualPackets(plugin, "preview-window")

    override fun isOpen(channel: Any) = ChannelHelper.isOpen(channel)

    override fun execute(channel: Any, task: () -> Unit) {
        ChannelHelper.runInEventLoop(channel) { task() }
    }

    override fun retry(channel: Any, task: () -> Unit) {
        PaperVisualPackets.retry(channel, task)
    }

    override fun display(channel: Any, display: BuilderPacketDisplay): VisualPacketAdmission =
        packets.write(channel, builderDisplayPacketTransaction(display))

    override fun glow(channel: Any, display: BuilderPacketDisplay): VisualPacketAdmission =
        packets.write(channel, builderDisplayGlowTransaction(display))

    override fun destroy(channel: Any, entityIds: List<Int>): VisualPacketAdmission =
        packets.write(channel, listOf(WrapperPlayServerDestroyEntities(*entityIds.toIntArray())), cleanup = true)

    override fun flush(channel: Any) {
        ChannelHelper.flush(channel)
    }

}

/** One budget admission always contains both packets needed to make the display usable. */
internal fun builderDisplayPacketTransaction(display: BuilderPacketDisplay): List<PacketWrapper<*>> = listOf(
    WrapperPlayServerSpawnEntity(
        display.entityId,
        Optional.of(display.uuid),
        EntityTypes.BLOCK_DISPLAY,
        Vector3d(display.x, display.y, display.z),
        0f,
        0f,
        0f,
        0,
        Optional.empty(),
    ),
    WrapperPlayServerEntityMetadata(
        display.entityId,
        listOf(
            // Entity metadata index 0: GLOWING flag (0x40).
            EntityData(0, EntityDataTypes.BYTE, if (display.glowing) GLOWING_FLAG else 0.toByte()),
            // Display metadata starts at 8; transformation is 11..14.
            EntityData(11, EntityDataTypes.VECTOR3F, Vector3f(display.translateX, display.translateY, display.translateZ)),
            EntityData(12, EntityDataTypes.VECTOR3F, Vector3f(display.scaleX, display.scaleY, display.scaleZ)),
            EntityData(13, EntityDataTypes.QUATERNION, Quaternion4f(0f, 0f, 0f, 1f)),
            EntityData(14, EntityDataTypes.QUATERNION, Quaternion4f(0f, 0f, 0f, 1f)),
            // Native preview uses Display.Brightness(15, 15): (15 << 4) | (15 << 20).
            EntityData(16, EntityDataTypes.INT, FULL_BRIGHTNESS),
            EntityData(17, EntityDataTypes.FLOAT, display.viewRange),
            EntityData(22, EntityDataTypes.INT, display.glowRgb),
            // BlockDisplay-specific metadata follows the Display fields.
            EntityData(23, EntityDataTypes.BLOCK_STATE, display.blockStateId),
        ),
    ),
)

private const val FULL_BRIGHTNESS = 0xF000F0
private val GLOWING_FLAG = 0x40.toByte()

/** Metadata-only update for an existing BlockDisplay; position, color and ID stay stable. */
internal fun builderDisplayGlowTransaction(display: BuilderPacketDisplay): List<PacketWrapper<*>> = listOf(
    WrapperPlayServerEntityMetadata(
        display.entityId,
        listOf(EntityData(0, EntityDataTypes.BYTE, if (display.glowing) GLOWING_FLAG else 0.toByte())),
    ),
)

/**
 * One connection's latest desired state and the entity snapshots whose packet
 * updates were admitted. All packet operations and admitted-state changes
 * happen on the captured channel's event loop.
 */
internal class BuilderPacketAttachment(
    channel: Any,
    private val gateway: BuilderPreviewPacketGateway,
    private val logger: Logger,
) : BuilderPreviewConnection {
    private val channelRef = WeakReference(channel)
    private val lock = Any()
    private var latestDesired = emptyList<BuilderPacketDisplay>()
    private var pendingResets = mutableListOf<Set<Int>>()
    private var desiredRevision = 0L
    private var scheduled = false
    @Volatile private var closed = false
    @Volatile private var terminated = false
    @Volatile private var retained = 0
    private val terminal = CompletableFuture<Void>()
    private val admitted = linkedMapOf<Int, BuilderPacketDisplay>()
    private val uncertain = linkedSetOf<Int>()
    private val forcedResetIds = linkedSetOf<Int>()
    private var nextCandidateId: Int? = null
    private var failureLogged = false

    override val identity: Any = Any()

    fun accepts(channel: Any): Boolean = !closed && !terminated && channelRef.get() === channel

    internal fun retainedEntityCount(): Int = if (terminated) 0 else retained

    override fun submit(desired: List<BuilderPacketDisplay>, forceResetIds: Set<Int>) {
        synchronized(lock) {
            if (closed || terminated) return
            // Callers hand off detached collections and never mutate them afterwards.
            // The lock owns admission only; map/set aggregation belongs to Netty.
            latestDesired = desired
            if (forceResetIds.isNotEmpty()) pendingResets.add(forceResetIds)
            desiredRevision++
            if (!scheduled) scheduled = true else return
        }
        queue(retry = false)
    }

    /** Stops accepting scenes but leaves cleanup retries alive until the channel closes. */
    fun close() { closeAsync() }

    fun closeAsync(): CompletableFuture<Void> {
        val enqueue = synchronized(lock) {
            if (closed || terminated) false
            else {
                closed = true
                latestDesired = emptyList()
                desiredRevision++
                if (scheduled) false else { scheduled = true; true }
            }
        }
        if (enqueue) queue(retry = false)
        return terminal.copy()
    }

    private fun queue(retry: Boolean) {
        val channel = channelRef.get()
        if (channel == null || !isOpen(channel)) {
            terminate()
            return
        }
        try {
            if (retry) gateway.retry(channel) { drain() } else gateway.execute(channel) { drain() }
        } catch (failure: Exception) {
            if (!isOpen(channel)) {
                terminate()
            } else {
                logFailure(failure)
                if (retry) {
                    terminate(failure)
                } else {
                    try {
                        gateway.retry(channel) { drain() }
                    } catch (retryFailure: Exception) {
                        terminate(retryFailure)
                    }
                }
            }
        }
    }

    private fun drain() {
        val channel = channelRef.get()
        if (channel == null || !isOpen(channel)) {
            terminate()
            return
        }
        val (snapshot, revision, resets) = synchronized(lock) {
            Triple(latestDesired, desiredRevision, pendingResets).also { pendingResets = mutableListOf() }
        }
        val desired = snapshot.associateBy(BuilderPacketDisplay::entityId)
        resets.forEach(forcedResetIds::addAll)
        var shouldRetry = false
        var terminalAdmission = false

        val staleIds = (admitted.keys + uncertain + forcedResetIds).filter { id ->
                val shown = admitted[id]
                id in forcedResetIds || id in uncertain ||
                    (shown != desired[id] && !shown.isGlowOnlyUpdate(desired[id]))
            }.distinct()
        if (staleIds.isNotEmpty()) {
            try {
                when (gateway.destroy(channel, staleIds)) {
                    VisualPacketAdmission.ALLOWED -> {
                        gateway.flush(channel)
                        staleIds.forEach { id ->
                            admitted.remove(id)
                            uncertain.remove(id)
                            forcedResetIds.remove(id)
                        }
                    }
                    VisualPacketAdmission.CHANNEL_BACKPRESSURE -> shouldRetry = true
                    VisualPacketAdmission.CLOSED -> terminalAdmission = true
                    VisualPacketAdmission.STARTING,
                    VisualPacketAdmission.VIEWER_RATE,
                    VisualPacketAdmission.SERVER_RATE -> shouldRetry = true
                }
            } catch (failure: Exception) {
                logFailure(failure)
                if (!isOpen(channel)) {
                    terminate()
                    return
                }
                shouldRetry = true
            }
        }

        // Do not add replacements until stale IDs have actually been cleaned up.
        val cleanupStillPending = staleIds.any { it in admitted || it in uncertain || it in forcedResetIds }
        if (!cleanupStillPending && !terminalAdmission) {
            val candidates = desired.values.filter {
                admitted[it.entityId] != it && it.entityId !in uncertain
            }
            if (candidates.isNotEmpty()) {
                val start = candidates.indexOfFirst { it.entityId == nextCandidateId }.let { if (it < 0) 0 else it }
                val rotated = candidates.drop(start) + candidates.take(start)
                var nextCursor: Int? = null
                rotated.forEachIndexed { index, display ->
                    try {
                        val isGlowUpdate = admitted[display.entityId].isGlowOnlyUpdate(display)
                        val admission = if (isGlowUpdate) gateway.glow(channel, display)
                        else gateway.display(channel, display)
                        when (admission) {
                            VisualPacketAdmission.ALLOWED -> {
                                gateway.flush(channel)
                                admitted[display.entityId] = display
                                uncertain.remove(display.entityId)
                            }
                            VisualPacketAdmission.CHANNEL_BACKPRESSURE,
                            VisualPacketAdmission.STARTING,
                    VisualPacketAdmission.VIEWER_RATE,
                            VisualPacketAdmission.SERVER_RATE -> shouldRetry = true
                            VisualPacketAdmission.CLOSED -> terminalAdmission = true
                        }
                    } catch (failure: Exception) {
                        // A failed write may have reached the client; destroy and replay it safely.
                        uncertain += display.entityId
                        logFailure(failure)
                        if (!isOpen(channel)) {
                            terminate()
                            return
                        }
                        shouldRetry = true
                    }
                    if (nextCursor == null) nextCursor = rotated[(index + 1) % rotated.size].entityId
                }
                nextCandidateId = nextCursor
            }
        } else if (cleanupStillPending) {
            shouldRetry = true
        }

        if (terminalAdmission) { terminate(); return }
        retained = (admitted.keys + uncertain + forcedResetIds + desired.keys).toSet().size
        var complete = false
        val action = synchronized(lock) {
            when {
                terminated -> QueueAction.STOP
                desiredRevision != revision -> QueueAction.NOW
                shouldRetry && !terminalAdmission -> QueueAction.LATER
                else -> {
                    scheduled = false
                    complete = closed && admitted.isEmpty() && uncertain.isEmpty() && forcedResetIds.isEmpty()
                    QueueAction.STOP
                }
            }
        }
        if (complete) terminal.complete(null)
        when (action) {
            QueueAction.NOW -> queue(retry = false)
            QueueAction.LATER -> queue(retry = true)
            QueueAction.STOP -> Unit
        }
    }

    private fun isOpen(channel: Any): Boolean = try {
        gateway.isOpen(channel)
    } catch (_: Exception) {
        false
    }

    private fun terminate(failure: Throwable = IllegalStateException("Preview channel closed before cleanup was flushed")) {
        // No worker-held monitor or bulk clear on the calling/native thread.
        terminated = true
        terminal.completeExceptionally(failure)
    }

    private fun logFailure(failure: Exception) {
        synchronized(lock) {
            if (failureLogged) return
            failureLogged = true
        }
        logger.log(Level.WARNING, "ArcBuilder preview packet transaction failed; latest state will retry", failure)
    }

    private enum class QueueAction { NOW, LATER, STOP }

    private fun BuilderPacketDisplay?.isGlowOnlyUpdate(desired: BuilderPacketDisplay?): Boolean {
        val shown = this ?: return false
        val target = desired ?: return false
        if (shown.glowing == target.glowing) return false
        return shown.copy(glowing = target.glowing) == target
    }
}

internal class PacketEventsBuilderPreviewTransport(
    plugin: Plugin,
    private val logger: Logger = plugin.logger,
    private val gateway: BuilderPreviewPacketGateway = PaperBuilderPreviewPacketGateway(plugin),
) : BuilderPreviewPacketTransport {
    /* Weak keys keep one attachment per live Bukkit player without retaining disconnected players. */
    private var attachments = WeakHashMap<Player, BuilderPacketAttachment>()
    private var closed = false
    private val terminal = CompletableFuture<Void>()
    private val retiring = AtomicInteger()
    @Volatile private var closeSubmitted = false
    private val terminalFailure = AtomicReference<Throwable?>()

    private fun retire(attachment: BuilderPacketAttachment) {
        retiring.incrementAndGet()
        val work = runCatching { attachment.closeAsync() }.getOrElse { CompletableFuture.failedFuture(it) }
        work.whenComplete { _, failure ->
            if (failure != null) terminalFailure.compareAndSet(null, failure)
            retiring.decrementAndGet()
            completeCloseIfDrained()
        }
    }

    private fun completeCloseIfDrained() {
        if (!closeSubmitted || retiring.get() != 0) return
        val failure = terminalFailure.get()
        if (failure == null) terminal.complete(null) else terminal.completeExceptionally(failure)
    }

    override fun blockStateId(blockData: BlockData): Int =
        SpigotConversionUtil.fromBukkitBlockData(blockData).globalId

    @Suppress("DEPRECATION")
    override fun nextEntityId(): Int = Bukkit.getUnsafe().nextEntityId()

    override fun connection(player: Player): BuilderPreviewConnection? {
        if (closed) return null
        val channel = PacketEvents.getAPI().playerManager.getChannel(player) ?: return null
        if (!gateway.isOpen(channel)) return null

        val current = attachments[player]
        if (current != null && current.accepts(channel)) return current
        current?.let(::retire)
        return BuilderPacketAttachment(channel, gateway, logger).also { attachments[player] = it }
    }

    override fun forget(player: Player) {
        attachments.remove(player)?.let(::retire)
    }

    override fun closeAsync(): CompletableFuture<Void> {
        if (closed) return terminal.copy()
        closed = true
        val remaining = attachments.values.toList()
        attachments.clear()
        remaining.forEach(::retire)
        closeSubmitted = true
        completeCloseIfDrained()
        return terminal.copy()
    }

    override fun close() { closeAsync() }
}
