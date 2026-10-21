package ru.arc.buildertools

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.player.InteractionHand
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUseItem
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerAcknowledgeBlockChanges
import java.util.UUID

internal data class BuilderPlacementAssistInput(
    val playerId: UUID,
    val connection: Any,
    val sequence: Int,
    val yaw: Float,
    val pitch: Float,
    val offer: BuilderPlacementAssistOffer,
)

/** Only copied packet fields and immutable offer references cross the Netty/server boundary. */
internal class BuilderPlacementAssistPackets(
    private val admit: (UUID) -> BuilderPlacementAssistOffer?,
    private val receive: (BuilderPlacementAssistInput) -> Unit,
) : PacketListenerAbstract(PacketListenerPriority.HIGHEST), AutoCloseable {
    private val api = checkNotNull(PacketEvents.getAPI()) { "PacketEvents is unavailable" }

    init { api.eventManager.registerListener(this) }

    override fun onPacketReceive(event: PacketReceiveEvent) {
        if (event.isCancelled || event.packetType != PacketType.Play.Client.USE_ITEM) return
        val packet = WrapperPlayClientUseItem(event)
        if (packet.hand != InteractionHand.MAIN_HAND || packet.sequence < 0 ||
            !packet.yaw.isFinite() || !packet.pitch.isFinite()
        ) return
        val playerId = event.user.uuid
        val offer = admit(playerId) ?: return
        val input = BuilderPlacementAssistInput(playerId, event.channel, packet.sequence, packet.yaw, packet.pitch, offer)
        event.isCancelled = true
        event.postTasks.add {
            // Air-use predicts no placed block. Acknowledge the original sequence even if
            // shutdown, a rejected scheduler, or changed player context discards the action.
            // Native use-item-on will independently acknowledge the same sequence.
            api.protocolManager.sendPacket(input.connection, WrapperPlayServerAcknowledgeBlockChanges(input.sequence))
            receive(input)
        }
    }

    override fun close() { api.eventManager.unregisterListener(this) }
}
