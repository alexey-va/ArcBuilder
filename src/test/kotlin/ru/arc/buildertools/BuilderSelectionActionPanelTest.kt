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
            cameraYaw = 0f,
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

    "plane hover uses the visible button rectangle and honors block occlusion" {
        val control = BuilderPanelControlPlane(
            action = BuilderPanelAction.CONFIRM,
            center = BuilderPanelPoint3(0.0, 1.6, 2.4),
            yaw = 180.0,
            width = 1.05,
            height = 0.23,
        )
        val origin = BuilderPanelPoint3(0.0, 1.6, 0.0)
        val forward = BuilderPanelPoint3(0.0, 0.0, 1.0)

        BuilderPanelGeometry.nearestTarget(origin, forward, listOf(control), 4.0) shouldBe BuilderPanelAction.CONFIRM
        BuilderPanelGeometry.nearestTarget(
            BuilderPanelPoint3(0.6, 1.6, 0.0), forward, listOf(control), 4.0,
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
