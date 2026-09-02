package ru.arc.autobuild

import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.extent.transform.BlockTransformExtent
import com.sk89q.worldedit.math.transform.AffineTransform
import com.sk89q.worldedit.math.transform.Transform
import com.sk89q.worldedit.world.block.BaseBlock
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic

class BuildBookStructureTransformTest : StringSpec({
    "horizontal mirror reflects local X before rotating into the world" {
        BuildBookStructureTransform.sourceRelative(
            worldRelative = BlockVector3.at(0, 0, 2),
            rotation = 90,
            mirrored = true,
        ) shouldBe BlockVector3.at(-2, 0, 0)
    }

    "horizontal mirror also reflects directional block state" {
        mockkStatic(BlockTransformExtent::class)
        try {
            val original = mockk<BaseBlock>()
            val reflected = mockk<BaseBlock>()
            val transform = slot<Transform>()
            every { BlockTransformExtent.transform(original, capture(transform)) } returns reflected

            BuildBookStructureTransform.mirrorBlock(original, mirrored = true) shouldBe reflected
            (transform.captured as AffineTransform).isHorizontalFlip shouldBe true
            BuildBookStructureTransform.mirrorBlock(original, mirrored = false) shouldBe original
        } finally {
            unmockkStatic(BlockTransformExtent::class)
        }
    }
})
