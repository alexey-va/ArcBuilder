package ru.arc.autobuild

import com.sk89q.worldedit.math.BlockVector3
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.World
import ru.arc.paper.api.ArcSchematicBlock
import ru.arc.paper.api.ArcSchematicPoint

class ConstructionSiteBarrierTest : StringSpec({
    "preview and plan consume transformed cells and omit barriers while retaining air" {
        val world = mockk<World>()
        for (air in listOf(ArcSchematicPoint(1, 0, 0), ArcSchematicPoint(-1, 0, 0), ArcSchematicPoint(0, 0, 1), ArcSchematicPoint(0, 0, -1))) {
            val view = schematicTestSnapshot(listOf(
                ArcSchematicBlock(ArcSchematicPoint(0, 0, 0), "minecraft:barrier", false, true),
                ArcSchematicBlock(air, "minecraft:air", true, false),
            ))
            val site = ConstructionSite(
                mockk(), Location(world, 10.0, 64.0, 20.0), mockk(), 0, world,
                BuildBookData("civs/house", "House"), Long.MAX_VALUE, 16, initialView = view,
            )
            site.relativePositionsBottomUp().toList() shouldBe listOf(BlockVector3.at(air.x, air.y, air.z))
        }
    }
})
