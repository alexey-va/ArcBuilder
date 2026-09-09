package ru.arc.autobuild

import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.math.BlockVector3
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

class BuildingBlockCountTest : StringSpec({
    "book volume includes air and occupied cells but not barrier markers" {
        val clipboard = mockk<Clipboard>()
        val positions = (0..3).map { BlockVector3.at(it, 0, 0) }
        every { clipboard.region.iterator() } answers { positions.toMutableList().iterator() }
        listOf("minecraft:air", "minecraft:stone", "minecraft:barrier", "minecraft:cave_air")
            .forEachIndexed { index, id -> every { clipboard.getFullBlock(positions[index]).blockType.id } returns id }
        countBuildBookCells(clipboard) shouldBe 3
        every { clipboard.getFullBlock(any()).blockType.id } returns "minecraft:barrier"
        countBuildBookCells(clipboard) shouldBe 0
    }
})
