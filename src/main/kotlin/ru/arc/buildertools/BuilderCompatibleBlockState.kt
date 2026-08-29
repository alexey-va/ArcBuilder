package ru.arc.buildertools

import org.bukkit.block.BlockFace
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Directional
import org.bukkit.block.data.Hangable
import org.bukkit.block.data.MultipleFacing
import org.bukkit.block.data.Openable
import org.bukkit.block.data.Orientable
import org.bukkit.block.data.Rail
import org.bukkit.block.data.Rotatable
import org.bukkit.block.data.Snowable
import org.bukkit.block.data.Waterlogged
import org.bukkit.block.data.type.Candle
import org.bukkit.block.data.type.Slab
import org.bukkit.block.data.type.Stairs
import org.bukkit.block.data.type.Wall

/** Copies only properties that both vanilla block-data types explicitly support. */
internal object BuilderCompatibleBlockState {
    fun copy(source: BlockData, target: BlockData) {
        if (source is Directional && target is Directional && source.facing in target.faces) {
            target.facing = source.facing
        }
        if (source is Orientable && target is Orientable && source.axis in target.axes) {
            target.axis = source.axis
        }
        if (source is MultipleFacing && target is MultipleFacing) {
            target.allowedFaces.forEach { face ->
                target.setFace(face, face in source.allowedFaces && source.hasFace(face))
            }
        }
        if (source is Rotatable && target is Rotatable) target.rotation = source.rotation
        if (source is Hangable && target is Hangable) target.isHanging = source.isHanging
        if (source is Snowable && target is Snowable) target.isSnowy = source.isSnowy
        if (source is Bisected && target is Bisected) target.half = source.half
        if (source is Waterlogged && target is Waterlogged) target.isWaterlogged = source.isWaterlogged
        if (source is Openable && target is Openable) target.isOpen = source.isOpen
        if (source is Stairs && target is Stairs) target.shape = source.shape
        if (source is Slab && target is Slab) target.type = source.type
        if (source is Candle && target is Candle) target.candles = source.candles
        if (source is Rail && target is Rail && source.shape in target.shapes) target.shape = source.shape
        if (source is Wall && target is Wall) {
            target.isUp = source.isUp
            HORIZONTAL_FACES.forEach { face -> target.setHeight(face, source.getHeight(face)) }
        }
    }

    private val HORIZONTAL_FACES = listOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)
}
