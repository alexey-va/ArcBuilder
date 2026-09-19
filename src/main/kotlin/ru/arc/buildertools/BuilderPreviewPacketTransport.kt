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
import io.github.retrooper.packetevents.util.SpigotReflectionUtil
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import java.util.Optional
import java.util.WeakHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
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
    override fun close() = Unit
}

internal interface BuilderPreviewConnection {
    /** Reference-stable for one channel attachment; changes after a failed batch or reconnect. */
    val identity: Any

    /** Removes are queued before additions, and the whole batch is flushed once. */
    fun send(removed: List<Int>, added: List<BuilderPacketDisplay>)
}

internal class PacketEventsBuilderPreviewTransport(
    private val logger: Logger,
) : BuilderPreviewPacketTransport {
    /* Weak keys keep one attachment per live Bukkit player without retaining disconnected players. */
    private val attachments = WeakHashMap<Player, Attachment>()

    override fun blockStateId(blockData: BlockData): Int =
        SpigotConversionUtil.fromBukkitBlockData(blockData).globalId

    override fun nextEntityId(): Int = SpigotReflectionUtil.generateEntityId()

    override fun connection(player: Player): BuilderPreviewConnection? {
        val channel = PacketEvents.getAPI().playerManager.getChannel(player) ?: return null
        if (!ChannelHelper.isOpen(channel)) return null

        synchronized(attachments) {
            val current = attachments[player]
            if (current != null && current.channel === channel && current.valid.get()) {
                return Connection(current)
            }
            val replacement = Attachment(channel, logger)
            attachments[player] = replacement
            return Connection(replacement)
        }
    }

    override fun forget(player: Player) {
        synchronized(attachments) { attachments.remove(player) }
    }

    override fun close() {
        synchronized(attachments) { attachments.clear() }
    }

    private inner class Connection(
        private val attachment: Attachment,
    ) : BuilderPreviewConnection {
        override val identity: Any = attachment.identity

        override fun send(removed: List<Int>, added: List<BuilderPacketDisplay>) {
            val removedIds = removed.toIntArray()
            val addedDisplays = added.toList()
            try {
                ChannelHelper.runInEventLoop(attachment.channel) {
                    try {
                        if (!ChannelHelper.isOpen(attachment.channel)) {
                            attachment.invalidate(IllegalStateException("preview channel closed"))
                        } else {
                            val allowAdditions = attachment.valid.get()
                            if (removedIds.isNotEmpty()) {
                                writePacket(
                                    WrapperPlayServerDestroyEntities(*removedIds),
                                )
                            }
                            if (allowAdditions) {
                                addedDisplays.forEach { display ->
                                    writePacket(spawnPacket(display))
                                    writePacket(metadataPacket(display))
                                }
                            }
                            if (removedIds.isNotEmpty() || (allowAdditions && addedDisplays.isNotEmpty())) {
                                ChannelHelper.flush(attachment.channel)
                            }
                        }
                    } catch (failure: Exception) {
                        attachment.invalidate(failure)
                    }
                }
            } catch (failure: Exception) {
                attachment.invalidate(failure)
            }
        }

        private fun writePacket(packet: PacketWrapper<*>) {
            /*
             * Keep PE's wrapper path intact: the overload performs
             * transformWrappers/prepareForSend and ProtocolManagerImpl.writePacket
             * preserves its ProtocolSupport ByteBuf ownership rules before the
             * channel write. The enclosing batch flushes once after all writes.
             */
            PacketEvents.getAPI().protocolManager.writePacket(attachment.channel, packet)
        }

        private fun spawnPacket(display: BuilderPacketDisplay) = WrapperPlayServerSpawnEntity(
            display.entityId,
            Optional.of(display.uuid),
            EntityTypes.BLOCK_DISPLAY,
            Vector3d(display.x, display.y, display.z),
            0f,
            0f,
            0f,
            0,
            Optional.empty(),
        )

        private fun metadataPacket(display: BuilderPacketDisplay) = WrapperPlayServerEntityMetadata(
            display.entityId,
            listOf(
                // Entity metadata index 0: GLOWING flag (0x40).
                EntityData(0, EntityDataTypes.BYTE, 0x40.toByte()),
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
        )
    }

    private class Attachment(
        val channel: Any,
        private val logger: Logger,
    ) {
        val identity: Any = Any()
        val valid = AtomicBoolean(true)
        private val failureLogged = AtomicBoolean(false)

        fun invalidate(failure: Exception) {
            valid.set(false)
            if (failureLogged.compareAndSet(false, true)) {
                logger.log(Level.WARNING, "ArcBuilder preview packet batch failed; reconnect snapshot will retry", failure)
            }
        }
    }

    private companion object {
        const val FULL_BRIGHTNESS = 0xF000F0
    }
}
