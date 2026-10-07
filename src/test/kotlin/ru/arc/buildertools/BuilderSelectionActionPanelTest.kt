package ru.arc.buildertools

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import net.kyori.adventure.text.Component
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.util.Vector
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.text.LocalizedMiniMessage
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

class BuilderSelectionActionPanelTest : StringSpec({
    "all two-column controls stay inside reach on an oblique eye ray with obstruction pullback" {
        val settings = BuilderPanelSettings()
        val eye = BuilderPanelPoint3(0.5, 65.62, 0.5)
        val direction = BuilderPanelPoint3(0.25, -0.15, 0.95)
        val directionLength = sqrt(direction.x * direction.x + direction.y * direction.y + direction.z * direction.z)
        val yaw = Math.toDegrees(atan2(direction.x, -direction.z)).toFloat()
        val layout = BuilderPanelGeometry.layout(BuilderPanelAction.entries.size, settings)
        val anchor = BuilderPanelGeometry.anchorOnRay(
            eye, direction, yaw, settings, layout, visibleBlockDistance = 1.4,
        )

        BuilderPanelGeometry.MAX_COLUMNS shouldBe 2
        layout.rows shouldBe 8
        (anchor.yaw % 90f == 0f) shouldBe false
        BuilderPanelGeometry.controlsReachable(eye, anchor, layout, settings) shouldBe true
        val center = BuilderPanelPoint3(
            anchor.point.x,
            anchor.point.y + BuilderPanelGeometry.centerY(layout, settings),
            anchor.point.z,
        )
        val requestedCenter = BuilderPanelPoint3(
            eye.x + direction.x / directionLength * settings.distance,
            eye.y + direction.y / directionLength * settings.distance,
            eye.z + direction.z / directionLength * settings.distance,
        )
        (center.distance(eye) < settings.distance) shouldBe true
        (center.distance(requestedCenter) > 0.1) shouldBe true
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

        BuilderPanelGeometry.controlWidth(BuilderPanelAction.CONFIRM, settings) shouldBe settings.buttonWidth
        BuilderPanelGeometry.controlWidth(BuilderPanelAction.PREVIOUS_PAGE, settings) shouldBe
            BuilderPanelGeometry.NAVIGATION_HITBOX_WIDTH
        BuilderPanelGeometry.controlWidth(fullPage, fullPage.buttons.last().index, settings) shouldBe
            BuilderPanelGeometry.NAVIGATION_HITBOX_WIDTH
        val navCenterDistance = fullPage.buttons.last().x - fullPage.buttons[fullPage.buttons.size - 2].x
        val textHalfWidth = 0.5
        val navHalfWidth = BuilderPanelGeometry.NAVIGATION_HITBOX_WIDTH / 2.0
        (navCenterDistance / 2.0 - navHalfWidth - textHalfWidth >= 0.04) shouldBe true
    }

    "selection direction follows its horizontal center and preserves fallback directly overhead" {
        val worldId = selection().worldId
        val eye = BuilderPanelPoint3(0.5, 65.62, 0.5)
        val straightAhead = BuilderSelection(
            BuilderBlockPos(worldId, 0, 64, 5), BuilderBlockPos(worldId, 0, 64, 5),
        )
        val diagonal = BuilderSelection(
            BuilderBlockPos(worldId, 4, 64, 4), BuilderBlockPos(worldId, 4, 64, 4),
        )
        val fallback = BuilderPanelPoint3(-1.0, 0.0, 0.0)

        BuilderPanelGeometry.selectionDirection(eye, straightAhead, fallback) shouldBe
            BuilderPanelPoint3(0.0, 0.0, 1.0)
        val diagonalDirection = BuilderPanelGeometry.selectionDirection(eye, diagonal, fallback)
        (diagonalDirection.x > 0.0 && diagonalDirection.z > 0.0) shouldBe true
        (abs(sqrt(diagonalDirection.x * diagonalDirection.x + diagonalDirection.z * diagonalDirection.z) - 1.0) < 1e-9) shouldBe true
        BuilderPanelGeometry.selectionDirection(
            eye,
            BuilderSelection(
                BuilderBlockPos(worldId, 0, 64, 0), BuilderBlockPos(worldId, 0, 64, 0),
            ),
            fallback,
        ) shouldBe fallback
    }

    "panel tick tracks selection and body translation, not head pose or crouch" {
        val worldId = UUID.randomUUID()
        val world = mockk<World>(relaxed = true)
        every { world.uid } returns worldId
        every { world.minHeight } returns -64
        every { world.maxHeight } returns 320
        every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns true
        every {
            world.rayTraceBlocks(
                any<Location>(), any<Vector>(), any<Double>(), any<FluidCollisionMode>(), any<Boolean>(),
            )
        } returns null
        val passableBlock = mockk<Block>(relaxed = true)
        every { passableBlock.isPassable } returns true
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } returns passableBlock

        val playerId = UUID.randomUUID()
        var feetX = 0.5
        var feetY = 64.0
        var feetZ = 0.5
        var headYaw = 90f
        var headPitch = -40f
        var eyeHeight = 1.62
        val player = mockk<Player>(relaxed = true)
        every { player.uniqueId } returns playerId
        every { player.world } returns world
        every { player.isOnline } returns true
        every { player.location } answers { Location(world, feetX, feetY, feetZ) }
        every { player.eyeLocation } answers {
            Location(world, feetX, feetY + eyeHeight, feetZ, headYaw, headPitch)
        }
        every { player.sentChunkKeys } returns (-2..2).flatMap { x ->
            (-2..2).map { z -> chunkKey(x, z) }
        }.toSet()

        val server = mockk<Server>(relaxed = true)
        every { server.onlinePlayers } returns listOf(player)
        every { server.getPlayer(playerId) } returns player
        val plugin = mockk<Plugin>(relaxed = true)
        every { plugin.server } returns server
        val displays = mockk<PaperPacketDisplays>(relaxed = true)
        every { displays.spawnText(any<Location>(), any<Component>()) } answers {
            val location = firstArg<Location>().clone()
            val display = mockk<PacketTextDisplay>(relaxed = true)
            every { display.location } returns location
            display
        }

        val initialSelection = singleBlockSelection(worldId, x = 0, z = 5)
        var currentView = BuilderPanelView("first-selection", initialSelection, Component.text("selection"), emptyList())
        val panel = BuilderSelectionActionPanel(
            plugin,
            BuilderPanelSettings(),
            mockk<LocalizedMiniMessage>(relaxed = true),
            view = { currentView },
            onAction = { _, _ -> },
            displays = displays,
        )
        try {
            panel.tick()
            val state = panelState(panel, playerId)
            val initialFrame = panelField(state, "viewFrame") as BuilderPanelViewFrame
            val initialAnchor = (panelField(state, "anchor") as Location).clone()

            (abs(initialFrame.direction.x) < 1e-9) shouldBe true
            (abs(initialFrame.direction.z - 1.0) < 1e-9) shouldBe true
            initialFrame.yaw shouldBe 180f

            headYaw = 270f
            headPitch = 35f
            eyeHeight = 1.27
            panel.tick()
            (panelField(state, "viewFrame") as BuilderPanelViewFrame) shouldBe initialFrame
            panelField(state, "anchor") shouldBe initialAnchor

            feetX = 2.5
            panel.tick()
            val movedFrame = panelField(state, "viewFrame") as BuilderPanelViewFrame
            val movedAnchor = panelField(state, "anchor") as Location
            val movedLength = sqrt(29.0)
            (abs(movedFrame.direction.x - (-2.0 / movedLength)) < 1e-9) shouldBe true
            (abs(movedFrame.direction.z - (5.0 / movedLength)) < 1e-9) shouldBe true
            movedFrame.eyeOffset.y shouldBe initialFrame.eyeOffset.y
            (movedAnchor.distance(initialAnchor) > 0.5) shouldBe true

            currentView = currentView.copy(
                context = "second-selection",
                selection = singleBlockSelection(worldId, x = 6, z = 4),
            )
            panel.tick()
            val selectedFrame = panelField(state, "viewFrame") as BuilderPanelViewFrame
            val selectedLength = sqrt(32.0)
            (abs(selectedFrame.direction.x - (4.0 / selectedLength)) < 1e-9) shouldBe true
            (abs(selectedFrame.direction.z - (4.0 / selectedLength)) < 1e-9) shouldBe true
        } finally {
            panel.close()
        }
    }

    "whole panel center stays on the placement frame across pages and body movement" {
        val settings = BuilderPanelSettings()
        val eye = BuilderPanelPoint3(2.0, 70.0, -3.0)
        val look = BuilderPanelPoint3(0.25, -0.15, 0.95)
        val fullPage = BuilderPanelGeometry.layout(8, settings, navigationCount = 2)
        val shortPage = BuilderPanelGeometry.layout(3, settings, navigationCount = 2)

        fun panelCenter(origin: BuilderPanelPoint3, layout: BuilderPanelLayout): BuilderPanelPoint3 {
            val anchor = BuilderPanelGeometry.anchorOnRay(origin, look, 165.26f, settings, layout)
            return BuilderPanelPoint3(anchor.point.x, anchor.point.y + BuilderPanelGeometry.centerY(layout, settings), anchor.point.z)
        }

        val normalizedLength = kotlin.math.sqrt(look.x * look.x + look.y * look.y + look.z * look.z)
        val normalized = BuilderPanelPoint3(look.x / normalizedLength, look.y / normalizedLength, look.z / normalizedLength)
        val center = panelCenter(eye, fullPage)
        val expected = BuilderPanelPoint3(
            eye.x + normalized.x * settings.distance,
            eye.y + normalized.y * settings.distance,
            eye.z + normalized.z * settings.distance,
        )
        (center.distance(expected) < 1e-9) shouldBe true
        panelCenter(eye, shortPage) shouldBe center

        val movement = BuilderPanelPoint3(0.2, 0.1, -0.3)
        val movedCenter = panelCenter(
            BuilderPanelPoint3(eye.x + movement.x, eye.y + movement.y, eye.z + movement.z),
            shortPage,
        )
        (movedCenter.distance(BuilderPanelPoint3(
            center.x + movement.x,
            center.y + movement.y,
            center.z + movement.z,
        )) < 1e-9) shouldBe true
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

    "same-yaw swept bounds follow the thin panel depth during vertical motion" {
        val settings = BuilderPanelSettings()
        val layout = BuilderPanelGeometry.layout(8, settings, navigationCount = 2)
        val from = BuilderPanelAnchor(BuilderPanelPoint3(0.0, 70.0, 0.0), 0f)
        val to = BuilderPanelAnchor(BuilderPanelPoint3(0.0, 70.2, 0.1), 0f)
        val swept = BuilderPanelGeometry.sweptBounds(from, to, layout, settings)

        swept.overlaps(BuilderPanelBounds(-0.2, 68.0, 0.45, 0.2, 72.0, 0.6)) shouldBe false
        swept.overlaps(BuilderPanelBounds(-0.2, 68.0, 0.08, 0.2, 72.0, 0.12)) shouldBe true
    }

    "a small yaw sweep stays thin in depth while covering the rotated edge" {
        val settings = BuilderPanelSettings()
        val layout = BuilderPanelGeometry.layout(8, settings, navigationCount = 2)
        val from = BuilderPanelAnchor(BuilderPanelPoint3(0.0, 70.0, 0.0), 0f)
        val to = BuilderPanelAnchor(from.point, 2f)
        val swept = BuilderPanelGeometry.sweptBounds(from, to, layout, settings)

        swept.overlaps(BuilderPanelBounds(-0.2, 68.0, 0.4, 0.2, 72.0, 0.6)) shouldBe false
        swept.overlaps(BuilderPanelBounds(-0.2, 68.0, 0.06, 0.2, 72.0, 0.08)) shouldBe true
    }

    "stable placement distance suppresses near-wall jitter and follows clearances gradually" {
        val chosen = checkNotNull(BuilderPanelGeometry.stableDistance(2.4, null) { it <= 1.60 })
        (chosen in 1.45..1.60) shouldBe true

        val jittered = listOf(1.59, 1.61, 1.60, 1.61).map { limit ->
            checkNotNull(BuilderPanelGeometry.stableDistance(2.4, chosen) { it <= limit })
        }
        jittered.all { abs(it - chosen) < 1e-9 } shouldBe true

        val inward = checkNotNull(BuilderPanelGeometry.stableDistance(2.4, chosen) { it <= 1.25 })
        (inward <= 1.25) shouldBe true
        (1.25 - inward <= 0.11) shouldBe true

        val opening = checkNotNull(BuilderPanelGeometry.stableDistance(2.4, chosen) { it <= 2.4 })
        (opening - chosen in 0.0..0.061) shouldBe true
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
        BuilderPanelView("plain", selection, Component.empty(), listOf(BuilderPanelAction.CONFIRM)).pagination shouldBe Component.empty()
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

    "native left attack cancels owned hitboxes, accepts engine no-attack events, and honors external cancellation" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("BuilderSelectionAttackTest")
            val world = mockk<World>(relaxed = true)
            val playerId = UUID.randomUUID()
            val player = mockk<Player>(relaxed = true)
            val feet = Location(world, 0.5, 64.0, 0.5, 0f, 0f)
            val eye = Location(world, 0.5, 65.62, 0.5, 0f, 0f)
            every { player.uniqueId } returns playerId
            every { player.world } returns world
            every { player.location } returns feet
            every { player.eyeLocation } returns eye
            every { player.isOnline } returns true
            every {
                world.rayTraceBlocks(
                    any<Location>(), any<Vector>(), any<Double>(), any<FluidCollisionMode>(), any<Boolean>(),
                )
            } returns null
            val unowned = mockk<Interaction>(relaxed = true) {
                every { uniqueId } returns UUID.randomUUID()
            }
            val current = BuilderPanelView("attack-test", selection(), Component.empty(), listOf(BuilderPanelAction.CONFIRM))
            val hitbox = mockk<Interaction>(relaxed = true)
            every { hitbox.uniqueId } returns UUID.randomUUID()
            every { hitbox.isValid } returns true
            every { hitbox.world } returns world
            every { hitbox.location } returns Location(world, eye.x, eye.y - BuilderPanelSettings().buttonHeight / 2.0, eye.z + 2.0)
            val label = mockk<PacketTextDisplay>(relaxed = true) { every { isValid } returns true }
            var actions = 0
            val panel = BuilderSelectionActionPanel(
                plugin,
                BuilderPanelSettings(),
                mockk<LocalizedMiniMessage>(relaxed = true),
                view = { current },
                onAction = { _, action -> if (action == BuilderPanelAction.CONFIRM) actions++ },
                displays = mockk<PaperPacketDisplays>(relaxed = true),
            )
            try {
                installButtonState(panel, player, hitbox, label, current)

                val unrelatedAttack = PrePlayerAttackEntityEvent(player, unowned, true)
                panel.onPrePlayerAttack(unrelatedAttack)
                unrelatedAttack.isCancelled shouldBe false
                actions shouldBe 0

                val nonOwner = mockk<Player>(relaxed = true) {
                    every { uniqueId } returns UUID.randomUUID()
                }
                val nonOwnerAttack = PrePlayerAttackEntityEvent(nonOwner, hitbox, false)
                nonOwnerAttack.isCancelled shouldBe true
                panel.onPrePlayerAttack(nonOwnerAttack)
                actions shouldBe 0

                val noAttack = PrePlayerAttackEntityEvent(player, hitbox, false)
                noAttack.isCancelled shouldBe true
                panel.onPrePlayerAttack(noAttack)
                noAttack.isCancelled shouldBe true
                actions shouldBe 1

                val externallyCancelled = PrePlayerAttackEntityEvent(player, hitbox, true).also { it.isCancelled = true }
                panel.onPrePlayerAttack(externallyCancelled)
                externallyCancelled.isCancelled shouldBe true
                actions shouldBe 1
            } finally {
                panel.close()
            }
        }
    }
})

private fun installButtonState(
    panel: BuilderSelectionActionPanel,
    player: org.bukkit.entity.Player,
    hitbox: Interaction,
    label: PacketTextDisplay,
    view: BuilderPanelView,
) {
    val panelClass = BuilderSelectionActionPanel::class.java.declaredClasses.single { it.simpleName == "Panel" }
    val buttonClass = BuilderSelectionActionPanel::class.java.declaredClasses.single { it.simpleName == "Button" }
    val eye = player.eyeLocation
    val anchor = Location(player.world, eye.x, eye.y, eye.z + 2.0, 180f, 0f)
    val feet = player.location
    val panelConstructor = panelClass.declaredConstructors.single { it.parameterCount == 18 }.apply { isAccessible = true }
    val state = panelConstructor.newInstance(
        player.uniqueId,
        player.world,
        view.context,
        view.selection,
        0L,
        anchor,
        180f,
        feet,
        BuilderPanelViewFrame(
            BuilderPanelPoint3(eye.x - feet.x, eye.y - feet.y, eye.z - feet.z),
            BuilderPanelPoint3(0.0, 0.0, 1.0),
            180f,
        ),
        2.4,
        BuilderPanelClickGate(),
        linkedMapOf<BuilderPanelAction, Any>(),
        view.actions,
        null,
        null,
        null,
        null,
        2,
    )
    val buttonConstructor = buttonClass.declaredConstructors.single { it.parameterCount == 6 }.apply { isAccessible = true }
    val button = buttonConstructor.newInstance(BuilderPanelAction.CONFIRM, label, hitbox, 0L, null, false)
    @Suppress("UNCHECKED_CAST")
    val buttons = panelClass.getDeclaredField("buttons").apply { isAccessible = true }
        .get(state) as MutableMap<BuilderPanelAction, Any>
    buttons[BuilderPanelAction.CONFIRM] = button

    @Suppress("UNCHECKED_CAST")
    val panels = BuilderSelectionActionPanel::class.java.getDeclaredField("panels").apply { isAccessible = true }
        .get(panel) as MutableMap<UUID, Any>
    panels[player.uniqueId] = state
    @Suppress("UNCHECKED_CAST")
    val owners = BuilderSelectionActionPanel::class.java.getDeclaredField("hitboxOwners").apply { isAccessible = true }
        .get(panel) as MutableMap<UUID, UUID>
    owners[hitbox.uniqueId] = player.uniqueId
}

private fun panelState(panel: BuilderSelectionActionPanel, owner: UUID): Any {
    val panels = BuilderSelectionActionPanel::class.java.getDeclaredField("panels").apply { isAccessible = true }
        .get(panel) as Map<UUID, Any>
    return checkNotNull(panels[owner])
}

private fun panelField(panel: Any, name: String): Any? =
    panel.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(panel)

private fun singleBlockSelection(worldId: UUID, x: Int, z: Int) = BuilderSelection(
    BuilderBlockPos(worldId, x, 64, z),
    BuilderBlockPos(worldId, x, 64, z),
)

private fun chunkKey(x: Int, z: Int): Long =
    (x.toLong() and 0xffffffffL) or ((z.toLong() and 0xffffffffL) shl 32)

private fun selection(): BuilderSelection {
    val world = UUID.fromString("11111111-2222-3333-4444-555555555555")
    return BuilderSelection(BuilderBlockPos(world, 1, 64, 1), BuilderBlockPos(world, 2, 65, 2))
}
