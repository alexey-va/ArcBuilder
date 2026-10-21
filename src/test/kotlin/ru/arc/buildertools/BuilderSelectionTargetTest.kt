package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.WorldBorder
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.util.RayTraceResult
import org.bukkit.util.Vector
import java.util.UUID

class BuilderSelectionTargetTest : FunSpec({
    test("ordinary aim uses the exact native hit and keeps passable block collision enabled") {
        val f = SelectionTargetFixture()
        f.hit(2, 65, 2, BlockFace.SOUTH)
        f.resolve() shouldBe f.position(2, 65, 2)
        verify(exactly = 1) { f.world.rayTraceBlocks(any<Location>(), any(), 5.0, FluidCollisionMode.NEVER, false) }
        verify(exactly = 0) { f.world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) }
    }

    test("Shift selects air on each of the six hit faces") {
        listOf(BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST).forEach { face ->
            val f = SelectionTargetFixture(sneaking = true)
            f.hit(2, 65, 2, face)
            f.resolve() shouldBe f.position(2 + face.modX, 65 + face.modY, 2 + face.modZ)
        }
    }

    test("Shift never treats an occupied or fluid neighbour as air") {
        listOf(Material.STONE, Material.SHORT_GRASS, Material.WATER).forEach { material ->
            val f = SelectionTargetFixture(sneaking = true)
            f.hit(2, 65, 2, BlockFace.SOUTH)
            every { f.airBlock.type } returns material
            f.resolve() shouldBe null
        }
    }

    test("empty space is selectable only with Shift and negative coordinates use floor") {
        val f = SelectionTargetFixture(eyeX = -.25, eyeZ = -.25)
        f.resolve() shouldBe null
        every { f.player.isSneaking } returns true
        f.resolve() shouldBe f.position(-1, 65, -6)
        f.resolve(2.0) shouldBe f.position(-1, 65, -3)
    }

    test("an unloaded ray corridor is rejected before native tracing") {
        val f = SelectionTargetFixture(eyeX = 15.5, eyeZ = .5, direction = Vector(1, 0, 0), sneaking = true)
        every { f.world.isChunkLoaded(1, 0) } returns false
        f.resolve() shouldBe null
        verify(exactly = 0) { f.world.rayTraceBlocks(any<Location>(), any(), any(), any(), any()) }
        verify(exactly = 0) { f.world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) }
    }

    test("an exact ray boundary also checks the neighbouring chunk used by native epsilon") {
        val f = SelectionTargetFixture(eyeX = 0.0, eyeZ = .5, direction = Vector(1, 0, 0), sneaking = true)
        every { f.world.isChunkLoaded(-1, 0) } returns false
        f.resolve() shouldBe null
        verify(exactly = 0) { f.world.rayTraceBlocks(any<Location>(), any(), any(), any(), any()) }
    }

    test("a face-adjacent cell is checked before reading its unloaded chunk") {
        val f = SelectionTargetFixture(eyeX = 15.5, eyeZ = 5.5, sneaking = true)
        f.hit(15, 65, 2, BlockFace.EAST)
        every { f.world.isChunkLoaded(1, 0) } returns false
        f.resolve() shouldBe null
        verify(exactly = 0) { f.world.getBlockAt(16, 65, 2) }
    }

    test("world height and border reject invalid targets before an air block read") {
        val top = SelectionTargetFixture(eyeY = 319.0, sneaking = true)
        top.hit(2, 319, 2, BlockFace.UP)
        top.resolve() shouldBe null
        verify(exactly = 0) { top.world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) }
        val bottom = SelectionTargetFixture(eyeY = -63.0, sneaking = true)
        bottom.hit(2, -64, 2, BlockFace.DOWN)
        bottom.resolve() shouldBe null
        val border = SelectionTargetFixture(sneaking = true)
        every { border.border.isInside(any<Location>()) } returns false
        border.resolve() shouldBe null
        verify(exactly = 0) { border.world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) }
    }

    test("a camera outside build height can still aim at a valid cell inside it") {
        val above = SelectionTargetFixture(eyeY = 321.6, direction = Vector(0, -1, 0))
        above.hit(2, 319, 5, BlockFace.UP)
        above.resolve() shouldBe above.position(2, 319, 5)
        val below = SelectionTargetFixture(eyeY = -65.6, direction = Vector(0, 1, 0))
        below.hit(2, -64, 5, BlockFace.DOWN)
        below.resolve() shouldBe below.position(2, -64, 5)
        val air = SelectionTargetFixture(eyeY = 321.6, direction = Vector(0, -1, 0), sneaking = true)
        air.resolve() shouldBe air.position(2, 316, 5)
    }
})

private class SelectionTargetFixture(
    eyeX: Double = 2.5,
    eyeY: Double = 65.5,
    eyeZ: Double = 5.5,
    direction: Vector = Vector(0, 0, -1),
    sneaking: Boolean = false,
) {
    val world = mockk<World>()
    val border = mockk<WorldBorder>()
    val player = mockk<Player>()
    val airBlock = mockk<Block>()
    private val worldId = UUID.fromString("11111111-2222-3333-4444-555555555555")

    init {
        every { player.world } returns world
        every { player.eyeLocation } returns Location(world, eyeX, eyeY, eyeZ).setDirection(direction)
        every { player.isSneaking } returns sneaking
        every { world.uid } returns worldId
        every { world.minHeight } returns -64
        every { world.maxHeight } returns 320
        every { world.worldBorder } returns border
        every { border.isInside(any<Location>()) } returns true
        every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns true
        every { world.rayTraceBlocks(any<Location>(), any(), any(), any(), any()) } returns null
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } returns airBlock
        every { airBlock.type } returns Material.AIR
    }

    fun position(x: Int, y: Int, z: Int) = BuilderBlockPos(worldId, x, y, z)
    fun resolve(range: Double = 64.0) = BuilderSelectionTarget.resolve(player, range)

    fun hit(x: Int, y: Int, z: Int, face: BlockFace) {
        val block = mockk<Block>()
        every { block.x } returns x
        every { block.y } returns y
        every { block.z } returns z
        every { world.rayTraceBlocks(any<Location>(), any(), any(), any(), any()) } returns
            RayTraceResult(Vector(x + .5, y + .5, z + .5), block, face)
    }
}
