package ru.arc.buildertools

import org.bukkit.block.Block
import org.bukkit.block.data.BlockData

/** MockBukkit cannot execute Paper's NMS neighbour engine; real effects are covered by Plugwright. */
internal class RecordingBuilderPhysicsUpdater : BuilderPhysicsUpdater {
    val positions = mutableListOf<BuilderBlockPos>()
    var validated = false
    override fun validate() { validated = true }
    override fun update(block: Block, before: BlockData) {
        check(validated)
        positions += BuilderBlockPos(block.world.uid, block.x, block.y, block.z)
    }
}
