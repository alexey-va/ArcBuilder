package ru.arc.buildertools

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.util.Vector
import ru.arc.paper.actionpanel.ActionPanelInputProfile
import ru.arc.paper.actionpanel.ActionPanelPlacement
import ru.arc.paper.actionpanel.ActionPanelPoint
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.text.LocalizedMiniMessage
import java.util.Locale
import java.util.UUID
import java.util.function.Predicate
import kotlin.math.abs

class BuilderSelectionActionPanelTest : StringSpec({
    beforeTest { BuilderLocalePolicy.configure("ru", false) }
    afterSpec { BuilderLocalePolicy.configure("ru", false) }

    "all selection states retain their localized actions and page layout through the Core adapter" {
        PanelHarness().use { h ->
            val navigation = listOf(BuilderPanelAction.PREVIOUS_PAGE, BuilderPanelAction.NEXT_PAGE)
            val states = listOf(
                listOf(BuilderPanelAction.FILL, BuilderPanelAction.REPLACE, BuilderPanelAction.COPY,
                    BuilderPanelAction.PASTE, BuilderPanelAction.DECONSTRUCT, BuilderPanelAction.DRAFT) + navigation,
                listOf(BuilderPanelAction.UNDO, BuilderPanelAction.CLEAR, BuilderPanelAction.DISCONNECT,
                    BuilderPanelAction.CROWN) + navigation,
                listOf(BuilderPanelAction.CONFIRM, BuilderPanelAction.CANCEL),
                listOf(BuilderPanelAction.CONFIRM, BuilderPanelAction.CANCEL, BuilderPanelAction.ROTATE_LEFT,
                    BuilderPanelAction.ROTATE_RIGHT),
                listOf(BuilderPanelAction.CONFIRM, BuilderPanelAction.CANCEL, BuilderPanelAction.CROWN),
                listOf(BuilderPanelAction.CANCEL),
                emptyList(),
            )
            val seen = mutableSetOf<BuilderPanelAction>()
            states.forEachIndexed { index, actions ->
                h.current = h.current!!.copy(context = index, actions = actions,
                    pagination = if (BuilderPanelAction.NEXT_PAGE in actions) Component.text("Стр. 1 из 2") else Component.empty())
                val frame = checkNotNull(h.panel.frame(h.player))
                frame.layout.buttons.map { it.action } shouldBe actions
                frame.layout.buttons.forEach { button ->
                    button.text shouldBe Component.text("ru:selection-panel.actions.${button.action.localeKey}")
                    button.visible shouldBe true
                    button.enabled shouldBe true
                    seen += button.action
                }
                frame.layout.labels.single { it.key == "status" }.text shouldBe h.current!!.status
                frame.layout.labels.any { it.key == "pagination" } shouldBe (BuilderPanelAction.NEXT_PAGE in actions)
                frame.inputEnabled shouldBe true
                frame.audienceGroup shouldBe null
            }
            seen shouldBe BuilderPanelAction.entries.toSet()

            h.current = h.current!!.copy(actions = states.first(), pagination = Component.text("Стр. 1 из 2"))
            val full = checkNotNull(h.panel.frame(h.player)).layout
            h.current = h.current!!.copy(actions = listOf(BuilderPanelAction.CROWN) + navigation,
                pagination = Component.text("Стр. 2 из 2"))
            val short = checkNotNull(h.panel.frame(h.player)).layout
            full.labels.single { it.key == "status" }.rect shouldBe short.labels.single { it.key == "status" }.rect
            full.buttons.takeLast(2).map { it.rect } shouldBe short.buttons.takeLast(2).map { it.rect }
            short.buttons.first().rect.center.x shouldBe 0.0
            short.buttons.takeLast(2).forEach { it.rect.width shouldBe 0.45f.toDouble() }
            short.labels.single { it.key == "pagination" }.text shouldBe Component.text("Стр. 2 из 2")
        }
    }

    "the adapter preserves configured geometry, selection centre, input profile and guide switch" {
        val settings = BuilderPanelSettings(distance = 2.8, sideOffset = 0.2, heightOffset = -0.15,
            rowSpacing = 0.3, columnSpacing = 1.7, buttonWidth = 1.6f, buttonHeight = 0.25f,
            labelScale = 0.7f, reach = 4.5, suppressSelectionGlow = false, obstacleMaxShift = 0.5)
        PanelHarness(settings).use { h ->
            h.current = h.current!!.copy(actions = listOf(BuilderPanelAction.FILL, BuilderPanelAction.REPLACE))
            val frame = checkNotNull(h.panel.frame(h.player))
            val placement = frame.placement as ActionPanelPlacement.FollowTarget
            placement.worldId shouldBe h.world.uid
            placement.target shouldBe ActionPanelPoint(0.5, 64.5, 5.5)
            placement.settings.distance shouldBe settings.distance
            placement.settings.sideOffset shouldBe settings.sideOffset
            placement.settings.heightOffset shouldBe settings.heightOffset
            placement.settings.obstacleMaxShift shouldBe settings.obstacleMaxShift
            placement.settings.movementTolerance shouldBe 0.0
            frame.options.input shouldBe ActionPanelInputProfile.ENTITY_BOTH
            frame.options.reach shouldBe settings.reach
            frame.options.suppressGuide shouldBe false
            frame.layout.buttons.map { it.rect.center.x } shouldBe listOf(-0.85, 0.85)
            frame.layout.buttons.forEach {
                it.rect.width shouldBe settings.buttonWidth.toDouble()
                it.rect.height shouldBe settings.buttonHeight.toDouble()
                it.style.scale shouldBe settings.labelScale
            }
        }
    }

    "activation rechecks the exact plan, selection, action and current world before dispatch" {
        PanelHarness().use { h ->
            h.current = h.current!!.copy(context = "plan-1", actions = listOf(BuilderPanelAction.CONFIRM))
            val first = checkNotNull(h.panel.frame(h.player)).context
            h.current = h.current!!.copy(context = "plan-2")
            h.panel.activate(h.player, first, BuilderPanelAction.CONFIRM)
            h.actions shouldBe emptyList()

            val second = checkNotNull(h.panel.frame(h.player)).context
            h.current = h.current!!.copy(selection = selection(h.world.uid, 1, 5))
            h.panel.activate(h.player, second, BuilderPanelAction.CONFIRM)
            h.actions shouldBe emptyList()
            val changed = checkNotNull(h.panel.frame(h.player)).context
            h.panel.activate(h.player, changed, BuilderPanelAction.CANCEL)
            h.actions shouldBe emptyList()
            h.panel.activate(h.player, changed, BuilderPanelAction.CONFIRM)
            h.actions shouldBe listOf(BuilderPanelAction.CONFIRM)

            h.current = h.current!!.copy(selection = selection(UUID.randomUUID(), 1, 5))
            h.panel.frame(h.player) shouldBe null
            h.panel.activate(h.player, changed, BuilderPanelAction.CONFIRM)
            h.current = null
            h.panel.frame(h.player) shouldBe null
            h.panel.activate(h.player, changed, BuilderPanelAction.CONFIRM)
            h.actions shouldBe listOf(BuilderPanelAction.CONFIRM)
        }
    }

    "malformed pagination cannot silently become a different clickable action layout" {
        PanelHarness().use { h ->
            listOf(listOf(BuilderPanelAction.NEXT_PAGE),
                listOf(BuilderPanelAction.NEXT_PAGE, BuilderPanelAction.PREVIOUS_PAGE)).forEach { actions ->
                h.current = h.current!!.copy(actions = actions)
                shouldThrowAny { h.panel.frame(h.player) }
            }
        }
    }

    "Core runtime keeps the Builder status panel still for head and crouch, follows small motion and clears its guide" {
        // Shared spatial queries require the real Bukkit primary-thread boundary.
        MockBukkitTestRuntime.open().use { PanelHarness().use { h ->
            org.bukkit.Bukkit.isPrimaryThread() shouldBe true
            h.panel.tick()
            val label = h.labels.single()
            val initial = label.location.clone()
            h.glow shouldBe emptyList()

            h.headYaw = 0f
            h.panel.tick()
            h.glow shouldBe listOf(true)
            label.location shouldBe initial
            h.headYaw = 90f
            h.headPitch = 35f
            h.eyeHeight = 1.27
            h.panel.tick()
            label.location shouldBe initial
            h.glow shouldBe listOf(true, false)

            h.feetX += 0.005
            h.panel.tick()
            (label.location.distance(initial) > 0.001) shouldBe true
            (abs(label.location.y - initial.y) < 1e-8) shouldBe true
            label.duration shouldBe 2
            h.labels.size shouldBe 1
            val afterMove = label.location.clone()
            h.panel.tick()
            label.location shouldBe afterMove

            h.headYaw = 0f
            h.headPitch = 0f
            h.eyeHeight = 1.62
            h.panel.tick()
            h.glow.last() shouldBe true
            h.panel.intercept(h.player) shouldBe false
            h.panel.clear(h.player.uniqueId)
            h.glow.last() shouldBe false
            label.removed shouldBe true
            h.current = null
            h.panel.tick()
            h.labels.size shouldBe 1
            h.panel.close()
            h.panel.close()
            verify(exactly = 1) { h.displays.close() }
        } }
    }
})

private class RecordedLabel(start: Location, initialText: Component) {
    var location = start.clone()
    var content = initialText
    var removed = false
    var duration = 0
    val display = mockk<PacketTextDisplay>(relaxed = true).also { handle ->
        every { handle.location } answers { location.clone() }
        every { handle.isValid } answers { !removed }
        every { handle.text() } answers { content }
        every { handle.text(any()) } answers { content = firstArg(); Unit }
        every { handle.teleportDuration = any() } answers { duration = firstArg(); Unit }
        every { handle.teleport(any()) } answers { location = firstArg<Location>().clone(); Unit }
        every { handle.remove() } answers { removed = true; Unit }
    }
}

private class PanelHarness(settings: BuilderPanelSettings = BuilderPanelSettings()) : AutoCloseable {
    val world = mockk<World>(relaxed = true)
    val player = mockk<Player>(relaxed = true)
    val displays = mockk<PaperPacketDisplays>(relaxed = true)
    val labels = mutableListOf<RecordedLabel>()
    val actions = mutableListOf<BuilderPanelAction>()
    val glow = mutableListOf<Boolean>()
    var feetX = 0.5
    var headYaw = 90f
    var headPitch = 0f
    var eyeHeight = 1.62
    var current: BuilderPanelView?
    val panel: BuilderSelectionActionPanel

    init {
        val worldId = UUID.randomUUID()
        val playerId = UUID.randomUUID()
        every { world.uid } returns worldId
        every { world.minHeight } returns -64
        every { world.maxHeight } returns 320
        every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns true
        val air = mockk<Block>(relaxed = true)
        every { air.collisionShape.boundingBoxes } returns emptyList()
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } returns air
        every { world.rayTraceBlocks(any<Location>(), any<Vector>(), any<Double>(),
            any<FluidCollisionMode>(), any<Boolean>(), any<Predicate<Block>>()) } returns null
        every { player.uniqueId } returns playerId
        every { player.world } returns world
        every { player.isOnline } returns true
        every { player.isDead } returns false
        every { player.isChunkSent(any<Long>()) } returns true
        every { player.location } answers { Location(world, feetX, 64.0, 0.5) }
        every { player.eyeLocation } answers { Location(world, feetX, 64.0 + eyeHeight, 0.5, headYaw, headPitch) }
        every { player.locale() } returns Locale.GERMAN
        val server = mockk<Server>(relaxed = true)
        every { server.onlinePlayers } returns listOf(player)
        every { server.getPlayer(playerId) } returns player
        val plugin = mockk<Plugin>(relaxed = true)
        every { plugin.server } returns server
        every { displays.spawnText(any<Location>(), any<Component>()) } answers {
            RecordedLabel(firstArg(), secondArg()).also(labels::add).display
        }
        val messages = mockk<LocalizedMiniMessage>()
        every { messages.render(any(), any(), any()) } answers {
            Component.text("${secondArg<String>()}:${firstArg<String>()}")
        }
        current = BuilderPanelView("selection", selection(worldId, 0, 5), Component.text("selection"), emptyList())
        panel = BuilderSelectionActionPanel(plugin, settings, messages, { current }, { _, action -> actions += action },
            displays, { id, suppressed -> check(id == playerId); glow += suppressed })
    }

    override fun close() = panel.close()
}

private fun selection(worldId: UUID, x: Int, z: Int) = BuilderSelection(
    BuilderBlockPos(worldId, x, 64, z), BuilderBlockPos(worldId, x, 64, z),
)
