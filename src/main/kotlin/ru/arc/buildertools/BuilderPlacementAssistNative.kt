package ru.arc.buildertools

import org.bukkit.Bukkit
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import java.lang.reflect.InvocationTargetException

/** Exact Paper 1.21.11 boundary: Bukkit has no API for ordinary player block placement. */
internal object PaperBuilderPlacementAssistNative {
    // Cache failures too: an unsupported platform must not rescan classes on every player input.
    private val binding by lazy { runCatching { Binding() } }

    fun place(player: Player, support: BuilderBlockPos, face: BlockFace, sequence: Int) {
        check(Bukkit.isPrimaryThread()) { "Placement assist must execute on the server thread" }
        binding.getOrThrow().place(player, support, face, sequence)
    }

    private class Binding {
        init {
            check(Bukkit.getMinecraftVersion() == "1.21.11") {
                "Placement assist native adapter requires Paper 1.21.11; found ${Bukkit.getMinecraftVersion()}"
            }
        }

        private val craftPlayer = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer")
        private val serverPlayer = Class.forName("net.minecraft.server.level.ServerPlayer")
        private val listener = Class.forName("net.minecraft.server.network.ServerGamePacketListenerImpl")
        private val hand = Class.forName("net.minecraft.world.InteractionHand")
        private val direction = Class.forName("net.minecraft.core.Direction")
        private val position = Class.forName("net.minecraft.core.BlockPos")
        private val vector = Class.forName("net.minecraft.world.phys.Vec3")
        private val hit = Class.forName("net.minecraft.world.phys.BlockHitResult")
        private val packet = Class.forName("net.minecraft.network.protocol.game.ServerboundUseItemOnPacket")
        private val getHandle = craftPlayer.getMethod("getHandle")
        private val connection = serverPlayer.getField("connection")
        private val mainHand = hand.getField("MAIN_HAND").get(null)
        private val directions = listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST, BlockFace.UP, BlockFace.DOWN)
            .associateWith { direction.getField(it.name).get(null) }
        private val newPosition = position.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        private val newVector = vector.getConstructor(Double::class.javaPrimitiveType, Double::class.javaPrimitiveType, Double::class.javaPrimitiveType)
        private val newHit = hit.getConstructor(vector, direction, position, Boolean::class.javaPrimitiveType)
        private val newPacket = packet.getConstructor(hand, hit, Int::class.javaPrimitiveType)
        private val timestamp = packet.getField("timestamp")
        private val useItemOn = listener.getMethod("handleUseItemOn", packet)

        fun place(player: Player, support: BuilderBlockPos, face: BlockFace, sequence: Int) {
            val center = BuilderPlacementAssistGeometry.faceCenter(support, face)
            val nativeHit = newHit.newInstance(
                newVector.newInstance(center.x, center.y, center.z), directions.getValue(face),
                newPosition.newInstance(support.x, support.y, support.z), false,
            )
            try {
                // This is the complete normal handler, synchronously on its owning thread:
                // reach/spawn checks, interaction and placement events, inventory and physics.
                val nativePacket = newPacket.newInstance(mainHand, nativeHit, sequence)
                // Paper initializes this in the wire constructor, not the public constructor.
                timestamp.setLong(nativePacket, System.currentTimeMillis())
                useItemOn.invoke(connection.get(getHandle.invoke(player)), nativePacket)
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
        }
    }
}
