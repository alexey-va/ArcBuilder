package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.type.Wall
import org.bukkit.entity.Player
import java.util.UUID

class BuilderFenceConnectionControllerTest : FunSpec({
    test("wall sides disconnect into a central post without touching source or water state") {
        val player = mockk<Player>()
        val world = mockk<World>()
        val block = mockk<Block>()
        val host = mockk<BuilderPlanningHost>(relaxed = true)
        val position = BuilderBlockPos(UUID.randomUUID(), 0, 64, 0)
        val before = mockk<Wall>(relaxed = true)
        val after = mockk<Wall>(relaxed = true)
        val changes = slot<List<BuilderBlockChange>>()
        every { host.requiredSelection(player) } returns BuilderSelection(position, position)
        every { host.world(position.worldId) } returns world
        every { world.getBlockAt(0, 64, 0) } returns block
        every { block.blockData } returns before
        every { before.getHeight(BlockFace.NORTH) } returns Wall.Height.LOW
        every { before.getHeight(BlockFace.SOUTH) } returns Wall.Height.TALL
        every { before.clone() } returns after
        every { before.asString } returns "minecraft:cobblestone_wall[north=low,south=tall,up=false,waterlogged=true]"
        every { after.asString } returns "minecraft:cobblestone_wall[north=none,south=none,up=true,waterlogged=true]"
        every { host.createPlan(player, BuilderPlanKind.FENCE_DISCONNECT, capture(changes), any(), any(), any()) } returns mockk()

        BuilderFenceConnectionController(100, host).planDisconnect(player)

        changes.captured.single().beforeBlockData shouldBe before.asString
        changes.captured.single().afterBlockData shouldBe after.asString
        listOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST).forEach {
            verify(exactly = 1) { after.setHeight(it, Wall.Height.NONE) }
        }
        verify(exactly = 1) { after.isUp = true }
        verify(exactly = 1) { host.ensureMutable(player, block, null) }
        verify(exactly = 0) { before.setHeight(any(), any()); before.isUp = any(); after.isWaterlogged = any() }
    }
})
