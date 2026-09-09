package ru.arc.buildertools

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe

class BuilderPreviewSurfaceTest : StringSpec({
    val fullSize = BuilderDisplayGeometry.blockTransform(1f)

    "matching world blocks need no duplicate display" {
        BuilderPreviewSurface.transform(fullSize, matchesWorld = true, occupied = true) shouldBe null
    }

    "air placement separates adjoining preview faces and preserves the centre" {
        val result = BuilderPreviewSurface.transform(fullSize, matchesWorld = false, occupied = false)!!
        result.scale shouldBe .998f
        result.offset shouldBe (.001f plusOrMinus .000001f)
        (result.offset + result.scale / 2f) shouldBe .5f
    }

    "replacement remains outside a full world cube instead of disappearing inside it" {
        val result = BuilderPreviewSurface.transform(fullSize, matchesWorld = false, occupied = true)!!
        result.scale shouldBe 1.002f
        result.offset shouldBe (-.001f plusOrMinus .000001f)
        (result.offset + result.scale / 2f) shouldBe .5f
    }

    "intentional smaller configured scale is preserved" {
        val configured = BuilderDisplayGeometry.blockTransform(.9f)
        BuilderPreviewSurface.transform(configured, matchesWorld = false, occupied = false) shouldBe configured
        BuilderPreviewSurface.transform(configured, matchesWorld = false, occupied = true) shouldBe configured
    }
})
