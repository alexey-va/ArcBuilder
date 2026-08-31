package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BuilderPreviewWindowTest : FunSpec({
    data class Point(val id: Int, val x: Double, val y: Double = 0.0, val z: Double = 0.0)

    fun select(points: List<Point>, limit: Int, viewerX: Double): List<Int> = BuilderPreviewWindow.nearest(
        values = points,
        limit = limit,
        viewerX = viewerX,
        viewerY = 0.0,
        viewerZ = 0.0,
    ) { point -> Triple(point.x, point.y, point.z) }.map(Point::id)

    test("preview below its limit keeps every block in schematic order") {
        select(
            listOf(Point(1, 30.0), Point(2, 1.0), Point(3, 20.0)),
            limit = 3,
            viewerX = 0.0,
        ) shouldBe listOf(1, 2, 3)
    }

    test("preview above its limit keeps the nearest blocks without stride holes") {
        select(
            (0 until 10).map { Point(it, it.toDouble()) },
            limit = 4,
            viewerX = 8.2,
        ) shouldBe listOf(6, 7, 8, 9)
    }

    test("moving the player changes the visible window across the whole project") {
        val project = (0 until 12).map { Point(it, it * 10.0) }

        select(project, limit = 3, viewerX = 1.0) shouldBe listOf(0, 1, 2)
        select(project, limit = 3, viewerX = 109.0) shouldBe listOf(9, 10, 11)
    }

    test("equal-distance ties stay deterministic and preserve scene order") {
        select(
            listOf(Point(0, -2.0), Point(1, 2.0), Point(2, -2.0), Point(3, 2.0)),
            limit = 2,
            viewerX = 0.0,
        ) shouldBe listOf(0, 1)
    }
})
