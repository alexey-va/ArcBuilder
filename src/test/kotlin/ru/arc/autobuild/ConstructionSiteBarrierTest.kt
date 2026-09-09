package ru.arc.autobuild

import com.sk89q.worldedit.math.BlockVector3
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.World

class ConstructionSiteBarrierTest : StringSpec({
    "preview and plan iteration omit barriers but retain air in every orientation" {
        val world = mockk<World>()
        for (rotation in listOf(0, 90, 180, 270)) {
            for (mirrored in listOf(false, true)) {
                val building = mockk<Building>()
                val barrier = BlockVector3.ZERO
                val air = BuildBookStructureTransform.targetRelative(BlockVector3.at(1, 0, 0), rotation, mirrored)
                every { building.getCorner1(rotation, mirrored) } returns barrier
                every { building.getCorner2(rotation, mirrored) } returns air
                every { building.getBlock(barrier, rotation, mirrored).blockType.id } returns "minecraft:barrier"
                every { building.getBlock(air, rotation, mirrored).blockType.id } returns "minecraft:air"
                val site = ConstructionSite(
                    building, Location(world, 10.0, 64.0, 20.0), mockk(), rotation, world,
                    BuildBookData("house.schem", "House"), Long.MAX_VALUE, 16,
                )
                if (mirrored) site.toggleMirror()
                site.relativePositionsBottomUp().toList() shouldBe listOf(air)
            }
        }
    }
})
