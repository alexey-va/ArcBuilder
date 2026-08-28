package ru.arc.util

import org.bukkit.block.data.BlockData
import org.bukkit.block.structure.StructureRotation

object BlockUtils {
    @JvmStatic
    fun rotateBlockData(data: BlockData, rotation: Int): BlockData = data.also {
        when (((rotation % 360) + 360) % 360) {
            90 -> it.rotate(StructureRotation.CLOCKWISE_90)
            180 -> it.rotate(StructureRotation.CLOCKWISE_180)
            270 -> it.rotate(StructureRotation.COUNTERCLOCKWISE_90)
        }
    }
}
