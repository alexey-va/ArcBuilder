package ru.arc.buildertools

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.player.InteractionHand
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUseItem
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerAcknowledgeBlockChanges
import org.bukkit.block.BlockFace
import org.bukkit.util.Vector
import java.util.UUID

internal data class BuilderPlacementAssistHit(val x: Int, val y: Int, val z: Int, val face: BlockFace)

internal data class BuilderPlacementAssistClick(
    val hit: BuilderPlacementAssistHit,
    val x: Float,
    val y: Float,
    val z: Float,
) {
    fun location() = Vector(hit.x + x.toDouble(), hit.y + y.toDouble(), hit.z + z.toDouble())
}

internal data class BuilderPlacementAssistInput(
    val playerId: UUID,
    val connection: Any,
    val sequence: Int,
    val yaw: Float,
    val pitch: Float,
    val offer: BuilderPlacementAssistOffer,
    val click: BuilderPlacementAssistClick? = null,
)

/** Only copied packet fields and immutable offer references cross the Netty/server boundary. */
internal class BuilderPlacementAssistPackets(
    private val admit: (UUID, BuilderPlacementAssistHit?) -> BuilderPlacementAssistOffer?,
    private val receive: (BuilderPlacementAssistInput) -> Unit,
) : PacketListenerAbstract(PacketListenerPriority.HIGHEST), AutoCloseable {
    private val api = checkNotNull(PacketEvents.getAPI()) { "PacketEvents is unavailable" }
    @Volatile private var closed = false

    init { api.eventManager.registerListener(this) }

    @Synchronized
    override fun onPacketReceive(event: PacketReceiveEvent) {
        if (closed || event.isCancelled) return
        val playerId = event.user.uuid
        val input = when (event.packetType) {
            PacketType.Play.Client.USE_ITEM -> {
                val packet = WrapperPlayClientUseItem(event)
                if (packet.hand != InteractionHand.MAIN_HAND || packet.sequence < 0 ||
                    !packet.yaw.isFinite() || !packet.pitch.isFinite()) return
                val offer = admit(playerId, null) ?: return
                BuilderPlacementAssistInput(playerId, event.channel, packet.sequence, packet.yaw, packet.pitch, offer)
            }
            PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT -> {
                val packet = WrapperPlayClientPlayerBlockPlacement(event)
                val cursor = packet.cursorPosition
                if (packet.hand != InteractionHand.MAIN_HAND || packet.sequence < 0 || packet.faceId !in 0..5 ||
                    packet.worldBorderHit.orElse(false) || !cursor.x.isFinite() || !cursor.y.isFinite() || !cursor.z.isFinite() ||
                    cursor.x !in 0f..1f || cursor.y !in 0f..1f || cursor.z !in 0f..1f) return
                val position = packet.blockPosition
                val hit = BuilderPlacementAssistHit(position.x, position.y, position.z, BlockFace.valueOf(packet.face.name))
                val offer = admit(playerId, hit) ?: return
                BuilderPlacementAssistInput(playerId, event.channel, packet.sequence, 0f, 0f, offer,
                    BuilderPlacementAssistClick(hit, cursor.x, cursor.y, cursor.z))
            }
            else -> return
        }
        event.isCancelled = true
        event.postTasks.add {
            // Every cancelled sequence is acknowledged even when shutdown or changed context
            // discards its action. Native use-item-on independently acknowledges the same sequence.
            api.protocolManager.sendPacket(input.connection, WrapperPlayServerAcknowledgeBlockChanges(input.sequence))
        }
        // Register the cancelled input before releasing the ingress lock. close() waits
        // for this registration, so the owning thread can drain every prediction.
        receive(input)
    }

    @Synchronized
    override fun close() {
        closed = true
        api.eventManager.unregisterListener(this)
    }
}
