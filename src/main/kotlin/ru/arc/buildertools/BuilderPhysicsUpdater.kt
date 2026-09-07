package ru.arc.buildertools

import org.bukkit.block.Block
import org.bukkit.block.data.BlockData

/** Separate from block writes: neighbour effects are only safe after durable commit. */
internal interface BuilderPhysicsUpdater {
    fun validate()
    fun update(block: Block, before: BlockData)
}

/** Paper 1.21.11 has no Bukkit API that notifies physics for an unchanged block state. */
internal object PaperBuilderPhysicsUpdater : BuilderPhysicsUpdater {
    private val binding by lazy { Binding() }

    override fun validate() { binding }

    override fun update(block: Block, before: BlockData) = binding.update(block, before)

    private class Binding {
        private val craftBlock = Class.forName("org.bukkit.craftbukkit.block.CraftBlock")
        private val craftData = Class.forName("org.bukkit.craftbukkit.block.data.CraftBlockData")
        private val level = Class.forName("net.minecraft.world.level.Level")
        private val position = Class.forName("net.minecraft.core.BlockPos")
        private val state = Class.forName("net.minecraft.world.level.block.state.BlockState")
        private val chunk = Class.forName("net.minecraft.world.level.chunk.LevelChunk")
        private val getLevel = craftBlock.getMethod("getHandle")
        private val getPosition = craftBlock.getMethod("getPosition")
        private val getState = craftData.getMethod("getState")
        private val getChunk = level.getMethod("getChunkAt", position)
        private val notify = level.getMethod(
            "notifyAndUpdatePhysics", position, chunk, state, state, state,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        )

        fun update(block: Block, before: BlockData) {
            val world = getLevel.invoke(block)
            val pos = getPosition.invoke(block)
            val current = getState.invoke(block.blockData)
            // UPDATE_NEIGHBORS only: the initial write already sent the client update.
            notify.invoke(world, pos, getChunk.invoke(world, pos), getState.invoke(before), current, current, 1, 512)
        }
    }
}
