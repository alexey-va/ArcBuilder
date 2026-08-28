package ru.arc.buildertools

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.util.UUID

class BuilderDisplayGeometryTest : StringSpec({
    "selection bounds are exactly twelve straight axis-aligned edges" {
        val world = UUID.randomUUID()
        val edges = BuilderDisplayGeometry.bounds(
            listOf(
                BuilderBlockPos(world, -2, 5, 7),
                BuilderBlockPos(world, 3, 8, 9),
            ),
        )

        edges shouldHaveSize 12
        edges.count { it.scaleX > .035f && it.scaleY == .035f && it.scaleZ == .035f } shouldBe 4
        edges.count { it.scaleX == .035f && it.scaleY > .035f && it.scaleZ == .035f } shouldBe 4
        edges.count { it.scaleX == .035f && it.scaleY == .035f && it.scaleZ > .035f } shouldBe 4
        edges.filter { it.scaleX > .035f }.map { it.scaleX }.distinct() shouldBe listOf(6f)
        edges.filter { it.scaleY > .035f }.map { it.scaleY }.distinct() shouldBe listOf(4f)
        edges.filter { it.scaleZ > .035f }.map { it.scaleZ }.distinct() shouldBe listOf(3f)
    }

    "empty scenes create no border displays" {
        BuilderDisplayGeometry.bounds(emptyList()) shouldBe emptyList()
    }
})
