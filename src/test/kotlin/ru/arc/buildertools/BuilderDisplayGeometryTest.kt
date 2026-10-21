package ru.arc.buildertools

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

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
        edges.filter { it.scaleX > .035f }.map { it.scaleX }.distinct() shouldBe listOf(6.035f)
        edges.filter { it.scaleY > .035f }.map { it.scaleY }.distinct() shouldBe listOf(3.965f)
        edges.filter { it.scaleZ > .035f }.map { it.scaleZ }.distinct() shouldBe listOf(2.965f)
    }

    "empty scenes create no border displays" {
        BuilderDisplayGeometry.bounds(emptyList()) shouldBe emptyList()
    }

    "full-size preview blocks occupy the exact Minecraft block volume" {
        BuilderDisplayGeometry.blockTransform(1f) shouldBe BuilderDisplayBlockTransform(
            offset = 0f,
            scale = 1f,
        )
    }

    "smaller configurable preview blocks remain centred in their block cell" {
        val transform = BuilderDisplayGeometry.blockTransform(.92f)
        transform.offset shouldBe (.04f plusOrMinus .00001f)
        transform.scale shouldBe .92f
    }

    "preview block scale rejects invisible and oversized displays" {
        shouldThrow<IllegalArgumentException> { BuilderDisplayGeometry.blockTransform(.49f) }
        shouldThrow<IllegalArgumentException> { BuilderDisplayGeometry.blockTransform(1.01f) }
    }

    "preview origin frame protrudes outside a solid anchor without covering its faces" {
        val world = UUID.randomUUID()
        val edges = BuilderDisplayGeometry.originMarker(BuilderBlockPos(world, 12, 64, -7))
        edges shouldHaveSize 12
        edges.forEach { edge ->
            val axes = listOf(
                Triple(edge.x, edge.scaleX, 12.0),
                Triple(edge.y, edge.scaleY, 64.0),
                Triple(edge.z, edge.scaleZ, -7.0),
            )
            axes.count { (_, scale, _) -> scale > .9f } shouldBe 1
            axes.count { (start, scale, min) ->
                scale == .08f && (start < min || start + scale > min + 1.0)
            } shouldBe 2
        }
        edges.count { it.scaleX == 1.08f } shouldBe 4
        edges.count { it.scaleY == .92f || it.scaleZ == .92f } shouldBe 8
    }

    "bounds and thick origin frames meet at corners without overlapping bar volumes" {
        val world = UUID.randomUUID()
        val first = BuilderBlockPos(world, -2, 64, -7)
        for (positions in listOf(listOf(first), listOf(first, first.copy(x = 3, y = 67, z = -5)))) {
            for (thickness in listOf(.035f, .08f, .25f)) {
                val edges = BuilderDisplayGeometry.bounds(positions, thickness)
                edges shouldHaveSize 12
                for (i in edges.indices) for (j in i + 1 until edges.size) {
                    val a = edges[i]
                    val b = edges[j]
                    val overlaps = listOf(
                        min(a.x + a.scaleX, b.x + b.scaleX) - max(a.x, b.x),
                        min(a.y + a.scaleY, b.y + b.scaleY) - max(a.y, b.y),
                        min(a.z + a.scaleZ, b.z + b.scaleZ) - max(a.z, b.z),
                    )
                    overlaps.all { it > 1e-6 } shouldBe false
                }
            }
        }
    }

    "moving preview windows retain overlap and change only entering and leaving blocks" {
        val delta = BuilderDisplaySceneDiff.between(
            previous = setOf("west", "center", "east"),
            next = listOf("center", "east", "far-east"),
        )

        delta.retained shouldBe setOf("center", "east")
        delta.added shouldBe listOf("far-east")
        delta.removed shouldBe setOf("west")
    }

    "duplicate preview specs are reconciled as one display" {
        val delta = BuilderDisplaySceneDiff.between(
            previous = emptySet(),
            next = listOf("same-block", "same-block"),
        )

        delta.added shouldBe listOf("same-block")
    }
})
