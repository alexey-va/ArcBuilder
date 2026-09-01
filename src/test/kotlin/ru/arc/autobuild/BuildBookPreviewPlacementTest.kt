package ru.arc.autobuild

import com.sk89q.worldedit.math.BlockVector3
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class BuildBookPreviewPlacementTest : StringSpec({
    "preview movement changes only the session anchor and preserves authored book data" {
        val book = BuildBookData(
            buildingId = "house.schem",
            title = "Дом",
            transform = BuildBookTransform(rotation = 90, offsetX = 2, offsetY = 1, offsetZ = -3),
            sourceRotation = 180,
        )
        val original = BuildBookPreviewPlacement(
            originalAnchor = BlockVector3.at(10, 64, 20),
            baseRotation = 180,
            maxOffset = 16,
        )

        val moved = original
            .move(BuildBookPreviewMove.LEFT, playerRotation = 180)
            .move(BuildBookPreviewMove.UP, playerRotation = 180)
            .rotate(90)

        moved.anchor shouldBe BlockVector3.at(11, 65, 20)
        moved.rotation shouldBe 270
        moved.bookAdjustedAnchor(book) shouldBe BlockVector3.at(9, 66, 23)
        book.transform shouldBe BuildBookTransform(rotation = 90, offsetX = 2, offsetY = 1, offsetZ = -3)
        original.anchor shouldBe BlockVector3.at(10, 64, 20)
        original.rotation shouldBe 180
    }

    "left and right follow the player's view while vertical movement follows world Y" {
        val origin = BuildBookPreviewPlacement(BlockVector3.ZERO, baseRotation = 0, maxOffset = 16)

        origin.move(BuildBookPreviewMove.LEFT, 0).anchor shouldBe BlockVector3.at(-1, 0, 0)
        origin.move(BuildBookPreviewMove.RIGHT, 0).anchor shouldBe BlockVector3.at(1, 0, 0)
        origin.move(BuildBookPreviewMove.LEFT, 90).anchor shouldBe BlockVector3.at(0, 0, -1)
        origin.move(BuildBookPreviewMove.RIGHT, 90).anchor shouldBe BlockVector3.at(0, 0, 1)
        origin.move(BuildBookPreviewMove.LEFT, 180).anchor shouldBe BlockVector3.at(1, 0, 0)
        origin.move(BuildBookPreviewMove.RIGHT, 270).anchor shouldBe BlockVector3.at(0, 0, -1)
        origin.move(BuildBookPreviewMove.UP, 270).anchor shouldBe BlockVector3.at(0, 1, 0)
        origin.move(BuildBookPreviewMove.DOWN, 90).anchor shouldBe BlockVector3.at(0, -1, 0)
    }

    "preview placement is bounded and reset restores the original click" {
        var placement = BuildBookPreviewPlacement(BlockVector3.at(4, 70, -8), baseRotation = 90, maxOffset = 2)
        repeat(5) {
            placement = placement.move(BuildBookPreviewMove.RIGHT, playerRotation = 180)
            placement = placement.move(BuildBookPreviewMove.UP, playerRotation = 180)
        }
        placement = placement.rotate(-90)

        placement.anchor shouldBe BlockVector3.at(2, 72, -8)
        placement.rotation shouldBe 0
        placement.reset() shouldBe BuildBookPreviewPlacement(
            originalAnchor = BlockVector3.at(4, 70, -8),
            baseRotation = 90,
            maxOffset = 2,
        )
    }
})
