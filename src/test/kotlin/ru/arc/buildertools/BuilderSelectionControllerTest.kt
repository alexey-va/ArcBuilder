package ru.arc.buildertools

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class BuilderSelectionControllerTest : FunSpec({
    val playerId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    val firstWorldId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    val secondWorldId = UUID.fromString("33333333-3333-3333-3333-333333333333")

    fun controller() = BuilderSelectionController(
        previewRadius = 32.0,
        previewSpacing = 0.75,
        maximumOutlinePoints = 512,
    )

    test("selection remains partial until both corners are in the viewer world") {
        val selections = controller()
        val first = BuilderBlockPos(firstWorldId, 1, 64, 2)
        val second = BuilderBlockPos(firstWorldId, 4, 70, 8)

        selections.set(playerId, first, first = true) shouldBe BuilderSelectionUpdate(null, worldReset = false)
        selections.first(playerId, firstWorldId) shouldBe first
        selections.points(playerId, firstWorldId) shouldBe BuilderSelectionPoints(first, null)
        selections.selection(playerId, firstWorldId) shouldBe null

        selections.set(playerId, second, first = false) shouldBe BuilderSelectionUpdate(
            BuilderSelection(first, second),
            worldReset = false,
        )
        selections.points(playerId, firstWorldId) shouldBe BuilderSelectionPoints(first, second)
        selections.selection(playerId, firstWorldId) shouldBe BuilderSelection(first, second)
        selections.selection(playerId, secondWorldId) shouldBe null
    }

    test("changing worlds atomically drops both stale corners") {
        val selections = controller()
        val oldFirst = BuilderBlockPos(firstWorldId, 1, 64, 2)
        val oldSecond = BuilderBlockPos(firstWorldId, 4, 70, 8)
        val newSecond = BuilderBlockPos(secondWorldId, -3, 80, 9)
        selections.set(playerId, oldFirst, first = true)
        selections.set(playerId, oldSecond, first = false)

        selections.set(playerId, newSecond, first = false) shouldBe BuilderSelectionUpdate(null, worldReset = true)
        selections.points(playerId, secondWorldId) shouldBe BuilderSelectionPoints(null, newSecond)
        selections.first(playerId, firstWorldId) shouldBe null
        selections.first(playerId, secondWorldId) shouldBe null
        selections.selection(playerId, firstWorldId) shouldBe null
        selections.selection(playerId, secondWorldId) shouldBe null
    }

    test("clear removes all state for that player") {
        val selections = controller()
        selections.set(playerId, BuilderBlockPos(firstWorldId, 1, 64, 2), first = true)

        selections.clear(playerId) shouldBe true
        selections.clear(playerId) shouldBe false
        selections.first(playerId, firstWorldId) shouldBe null
    }

    test("preview bounds reject invalid configuration") {
        shouldThrow<IllegalArgumentException> { BuilderSelectionController(0.0, 0.75, 512) }
        shouldThrow<IllegalArgumentException> { BuilderSelectionController(Double.NaN, 0.75, 512) }
        shouldThrow<IllegalArgumentException> { BuilderSelectionController(32.0, Double.POSITIVE_INFINITY, 512) }
        shouldThrow<IllegalArgumentException> { BuilderSelectionController(32.0, 0.75, 0) }
    }
})
