package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BuilderPreviewWindowTest : FunSpec({
    data class Point(val id: Int, val x: Double, val y: Double = 0.0, val z: Double = 0.0)

    fun select(points: List<Point>, limit: Int, viewerX: Double): List<Int> = BuilderPreviewWindow.nearest(
        values = points,
        limit = limit,
    ) { point ->
        val dx = point.x - viewerX
        dx * dx + point.y * point.y + point.z * point.z
    }.map(Point::id)

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

    test("large moving windows match exact distance ordering including ties") {
        val random = kotlin.random.Random(291)
        val points = (0 until 20_000).map {
            Point(it, random.nextInt(-128, 129).toDouble(), random.nextInt(0, 64).toDouble())
        }
        for (eye in listOf(-90.0, 0.0, 67.5)) {
            for (limit in listOf(1, 32, 4096)) {
                val expected = points.sortedWith(
                    compareBy<Point> { (it.x - eye) * (it.x - eye) + it.y * it.y }
                        .thenBy(Point::id),
                ).take(limit).sortedBy(Point::id).map(Point::id)
                select(points, limit, eye) shouldBe expected
            }
        }
    }
})
