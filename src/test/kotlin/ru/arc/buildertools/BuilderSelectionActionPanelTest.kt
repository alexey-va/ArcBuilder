package ru.arc.buildertools

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import java.util.UUID
import kotlin.math.abs

class BuilderSelectionActionPanelTest : StringSpec({
    "all two-column controls stay inside reach at an oblique selection angle" {
        val settings = BuilderPanelSettings()
        val eye = BuilderPanelPoint3(0.5, 65.62, 0.5)
        val anchor = BuilderPanelGeometry.anchor(
            eye = eye,
            target = BuilderPanelPoint3(13.5, 48.0, 7.5),
            settings = settings,
            actionCount = BuilderPanelAction.entries.size,
        )
        val layout = BuilderPanelGeometry.layout(BuilderPanelAction.entries.size, settings)

        BuilderPanelGeometry.MAX_COLUMNS shouldBe 2
        layout.rows shouldBe 8
        (anchor.yaw % 90f == 0f) shouldBe false
        BuilderPanelGeometry.controlsReachable(eye, anchor, layout, settings) shouldBe true
        (anchor.point.y + layout.buttons.minOf(BuilderPanelButtonOffset::y) - settings.buttonHeight / 2.0 > 64.0) shouldBe true
    }

    "the two-line plan summary has clearance above the top button row" {
        val settings = BuilderPanelSettings()
        val layout = BuilderPanelGeometry.layout(4, settings)
        val topButtonEdge = layout.buttons.maxOf(BuilderPanelButtonOffset::y) + settings.buttonHeight / 2.0
        val statusBottom = layout.statusY - BuilderPanelGeometry.STATUS_HEIGHT / 2.0

        (abs(statusBottom - topButtonEdge - BuilderPanelGeometry.STATUS_GAP) < 1e-9) shouldBe true
    }

    "page navigation stays in its own fixed row and leaves the header aligned" {
        val settings = BuilderPanelSettings()
        val fullPage = BuilderPanelGeometry.layout(8, settings, navigationCount = 2)
        val shortPage = BuilderPanelGeometry.layout(3, settings, navigationCount = 2)

        fullPage.rows shouldBe 4
        shortPage.rows shouldBe 4
        fullPage.statusY shouldBe shortPage.statusY
        fullPage.buttons.takeLast(2).map(BuilderPanelButtonOffset::x) shouldBe
            shortPage.buttons.takeLast(2).map(BuilderPanelButtonOffset::x)
        fullPage.buttons.takeLast(2).map(BuilderPanelButtonOffset::y) shouldBe
            shortPage.buttons.takeLast(2).map(BuilderPanelButtonOffset::y)
        (fullPage.buttons.take(6).maxOf(BuilderPanelButtonOffset::y) >
            fullPage.buttons.takeLast(2).maxOf(BuilderPanelButtonOffset::y)) shouldBe true
    }

    "walking changes the anchor continuously and near-center bearing cannot flip it" {
        val settings = BuilderPanelSettings()
        val target = BuilderPanelPoint3(12.5, 65.62, 9.5)
        val stationary = BuilderPanelPoint3(0.5, 65.62, 0.5)
        val first = BuilderPanelGeometry.anchor(stationary, target, settings, actionCount = 8, navigationCount = 2)
        val nextStep = BuilderPanelGeometry.anchor(
            BuilderPanelPoint3(0.6, 65.62, 0.5), target, settings, 8, 2, previousYaw = first.yaw,
        )
        (first.point.distance(nextStep.point) < 0.2) shouldBe true
        (first.point.distance(nextStep.point) > 0.0) shouldBe true

        val nearPositive = BuilderPanelGeometry.anchor(
            stationary, BuilderPanelPoint3(0.6, 65.62, 0.5), settings, 8, 2, previousYaw = 180f,
        )
        val nearNegative = BuilderPanelGeometry.anchor(
            stationary, BuilderPanelPoint3(0.4, 65.62, 0.5), settings, 8, 2, previousYaw = 180f,
        )
        nearPositive.yaw shouldBe nearNegative.yaw
        nearPositive.point shouldBe nearNegative.point
    }

    "conservative world bounds contain the complete angled label and control footprint" {
        val settings = BuilderPanelSettings()
        val layout = BuilderPanelGeometry.layout(8, settings, navigationCount = 2)
        val anchor = BuilderPanelAnchor(BuilderPanelPoint3(10.0, 70.0, -4.0), 37f)
        val bounds = BuilderPanelGeometry.panelBounds(anchor, layout, settings)
        val yaw = Math.toRadians(anchor.yaw.toDouble())
        val rightX = kotlin.math.cos(yaw)
        val rightZ = kotlin.math.sin(yaw)
        fun worldPoint(x: Double, y: Double) = BuilderPanelPoint3(
            anchor.point.x + rightX * x,
            anchor.point.y + y,
            anchor.point.z + rightZ * x,
        )

        layout.buttons.forEach { button ->
            listOf(-1.0, 1.0).forEach { xSide ->
                listOf(-1.0, 1.0).forEach { ySide ->
                    bounds.contains(worldPoint(
                        button.x + xSide * settings.buttonWidth / 2.0,
                        button.y + ySide * settings.buttonHeight / 2.0,
                    )) shouldBe true
                }
            }
        }
        listOf(-1.5, 1.5).forEach { x ->
            listOf(-BuilderPanelGeometry.STATUS_HEIGHT / 2.0, BuilderPanelGeometry.STATUS_HEIGHT / 2.0).forEach { y ->
                bounds.contains(worldPoint(x, layout.statusY + y)) shouldBe true
            }
        }
        bounds.overlaps(BuilderPanelBounds(
            bounds.maxX - 0.2, bounds.minY, bounds.minZ,
            bounds.maxX + 0.8, bounds.maxY, bounds.maxZ,
        )) shouldBe true
        bounds.overlaps(BuilderPanelBounds(
            bounds.maxX + 0.1, bounds.minY, bounds.minZ,
            bounds.maxX + 0.8, bounds.maxY, bounds.maxZ,
        )) shouldBe false
    }

    "swept bounds contain intermediate panel positions before native interpolation" {
        val settings = BuilderPanelSettings()
        val layout = BuilderPanelGeometry.layout(8, settings, navigationCount = 2)
        val from = BuilderPanelAnchor(BuilderPanelPoint3(4.0, 70.0, 4.0), 0f)
        val to = BuilderPanelAnchor(BuilderPanelPoint3(4.2, 70.1, 4.0), 30f)
        val swept = BuilderPanelGeometry.sweptBounds(from, to, layout, settings)
        val halfWidth = maxOf(
            BuilderPanelGeometry.STATUS_DISPLAY_WIDTH / 2.0,
            layout.buttons.maxOf { kotlin.math.abs(it.x) + settings.buttonWidth / 2.0 },
        )

        for (step in 0..6) {
            val fraction = step / 6.0
            val yaw = Math.toRadians(30.0 * fraction)
            val center = BuilderPanelPoint3(4.0 + 0.2 * fraction, 70.0 + 0.1 * fraction, 4.0)
            for (side in listOf(-halfWidth, halfWidth)) {
                val corner = BuilderPanelPoint3(
                    center.x + kotlin.math.cos(yaw) * side,
                    center.y + layout.statusY,
                    center.z + kotlin.math.sin(yaw) * side,
                )
                swept.contains(corner) shouldBe true
            }
        }
        swept.overlaps(BuilderPanelBounds(
            4.05, 69.5, 4.8, 4.2, 71.0, 5.2,
        )) shouldBe true
    }

    "plane hover uses the visible button rectangle and honors block occlusion" {
        val control = BuilderPanelControlPlane(
            action = BuilderPanelAction.CONFIRM,
            center = BuilderPanelPoint3(0.0, 1.6, 2.4),
            yaw = 180.0,
            width = 1.5,
            height = 0.23,
        )
        val origin = BuilderPanelPoint3(0.0, 1.6, 0.0)
        val forward = BuilderPanelPoint3(0.0, 0.0, 1.0)

        BuilderPanelGeometry.nearestTarget(origin, forward, listOf(control), 4.0) shouldBe BuilderPanelAction.CONFIRM
        BuilderPanelGeometry.nearestTarget(
            BuilderPanelPoint3(0.9, 1.6, 0.0), forward, listOf(control), 4.0,
        ) shouldBe null
        BuilderPanelGeometry.nearestTarget(origin, forward, listOf(control), 4.0, blockDistance = 2.0) shouldBe null
    }

    "a stale plan context and duplicate click cannot dispatch confirmation" {
        val selection = selection()
        val current = BuilderPanelView("plan-2", selection, Component.text("new plan"), listOf(BuilderPanelAction.CONFIRM))
        BuilderPanelInputPolicy.accepts(
            renderedContext = "plan-1",
            renderedSelection = selection,
            renderedGeneration = 4L,
            currentGeneration = 4L,
            action = BuilderPanelAction.CONFIRM,
            currentView = current,
        ) shouldBe false
        BuilderPanelInputPolicy.accepts(
            renderedContext = "plan-2",
            renderedSelection = selection,
            renderedGeneration = 4L,
            currentGeneration = 4L,
            action = BuilderPanelAction.CONFIRM,
            currentView = current,
        ) shouldBe true

        var now = 0L
        val gate = BuilderPanelClickGate { now }
        gate.accept() shouldBe true
        now = 1L
        gate.accept() shouldBe false
        now = 250_000_000L
        gate.accept() shouldBe true
    }
})

private fun selection(): BuilderSelection {
    val world = UUID.fromString("11111111-2222-3333-4444-555555555555")
    return BuilderSelection(BuilderBlockPos(world, 1, 64, 1), BuilderBlockPos(world, 2, 65, 2))
}
