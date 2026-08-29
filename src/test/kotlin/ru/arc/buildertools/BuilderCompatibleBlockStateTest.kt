package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Axis
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.Hangable
import org.bukkit.block.data.MultipleFacing
import org.bukkit.block.data.Orientable
import org.bukkit.block.data.Snowable
import org.bukkit.block.data.type.Candle
import org.bukkit.block.data.type.Slab
import org.bukkit.block.data.type.Stairs
import ru.arc.paper.testing.MockBukkitTestRuntime

class BuilderCompatibleBlockStateTest : FunSpec({
    test("compatible replacement preserves stairs orientation and shape") {
        MockBukkitTestRuntime.open().use {
            val source = Material.OAK_STAIRS.createBlockData() as Stairs
            source.facing = BlockFace.WEST
            source.half = Bisected.Half.TOP
            source.shape = Stairs.Shape.INNER_RIGHT
            val target = Material.SPRUCE_STAIRS.createBlockData() as Stairs

            BuilderCompatibleBlockState.copy(source, target)

            target.facing shouldBe BlockFace.WEST
            target.half shouldBe Bisected.Half.TOP
            target.shape shouldBe Stairs.Shape.INNER_RIGHT
        }
    }

    test("compatible replacement preserves log axis slab type and fence faces") {
        MockBukkitTestRuntime.open().use {
            val sourceLog = Material.OAK_LOG.createBlockData() as Orientable
            sourceLog.axis = Axis.Z
            val targetLog = Material.SPRUCE_LOG.createBlockData() as Orientable
            val sourceSlab = Material.STONE_SLAB.createBlockData() as Slab
            sourceSlab.type = Slab.Type.TOP
            val targetSlab = Material.BRICK_SLAB.createBlockData() as Slab
            val sourceFence = Material.OAK_FENCE.createBlockData() as MultipleFacing
            sourceFence.setFace(BlockFace.NORTH, true)
            sourceFence.setFace(BlockFace.EAST, true)
            val targetFence = Material.SPRUCE_FENCE.createBlockData() as MultipleFacing

            BuilderCompatibleBlockState.copy(sourceLog, targetLog)
            BuilderCompatibleBlockState.copy(sourceSlab, targetSlab)
            BuilderCompatibleBlockState.copy(sourceFence, targetFence)

            targetLog.axis shouldBe Axis.Z
            targetSlab.type shouldBe Slab.Type.TOP
            targetFence.faces shouldBe setOf(BlockFace.NORTH, BlockFace.EAST)
        }
    }

    test("incompatible replacement keeps the target default state") {
        MockBukkitTestRuntime.open().use {
            val source = Material.OAK_STAIRS.createBlockData() as Stairs
            source.facing = BlockFace.WEST
            val target = Material.STONE.createBlockData()
            val default = target.asString

            BuilderCompatibleBlockState.copy(source, target)

            target.asString shouldBe default
        }
    }

    test("compatible replacement preserves the count of stacked construction blocks") {
        MockBukkitTestRuntime.open().use {
            val source = Material.WHITE_CANDLE.createBlockData() as Candle
            source.candles = 4
            val target = Material.BLACK_CANDLE.createBlockData() as Candle

            BuilderCompatibleBlockState.copy(source, target)

            target.candles shouldBe 4
        }
    }

    test("compatible replacement preserves hanging and snowy presentation") {
        val sourceLantern = mockk<Hangable>()
        val targetLantern = mockk<Hangable>()
        val sourceGround = mockk<Snowable>()
        val targetGround = mockk<Snowable>()
        every { sourceLantern.isHanging } returns true
        every { targetLantern.isHanging = any() } just Runs
        every { sourceGround.isSnowy } returns true
        every { targetGround.isSnowy = any() } just Runs

        BuilderCompatibleBlockState.copy(sourceLantern, targetLantern)
        BuilderCompatibleBlockState.copy(sourceGround, targetGround)

        verify(exactly = 1) { targetLantern.isHanging = true }
        verify(exactly = 1) { targetGround.isSnowy = true }
    }
})
