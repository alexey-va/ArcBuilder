package ru.arc.buildertools

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import ru.arc.config.Config
import ru.arc.paper.actionpanel.ActionPanelInputProfile
import ru.arc.paper.actionpanel.ActionPanelFollowSettings
import ru.arc.paper.actionpanel.ActionPanelPlacement
import ru.arc.paper.actionpanel.ActionPanelPoint
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.text.LocalizedMiniMessage
import java.nio.file.Files
import java.util.Locale
import java.util.UUID
import java.util.function.Consumer
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
                    BuilderPanelAction.MOVE, BuilderPanelAction.PASTE, BuilderPanelAction.DECONSTRUCT) + navigation,
                listOf(BuilderPanelAction.DRAFT, BuilderPanelAction.UNDO, BuilderPanelAction.CLEAR, BuilderPanelAction.DISCONNECT,
                    BuilderPanelAction.CROWN, BuilderPanelAction.ASSIST_ON) + navigation,
                listOf(BuilderPanelAction.DRAFT, BuilderPanelAction.UNDO, BuilderPanelAction.CLEAR, BuilderPanelAction.DISCONNECT,
                    BuilderPanelAction.CROWN, BuilderPanelAction.ASSIST_OFF) + navigation,
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
            placement.settings.movementTolerance shouldBe ActionPanelFollowSettings().movementTolerance
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

    "both blueprint labels and their backgrounds fit the existing draft button without changing other actions" {
        val messages = BuilderToolsConfig(Config(Files.createTempDirectory("arc-builder-draft-fit-"),
            "modules/builder-tools.yml")).messages()
        val plain = PlainTextComponentSerializer.plainText()
        // Measured with the pinned vanilla 1.21.11 font: full label and longest existing word.
        val labels = listOf(Triple("ru", "Создать схему", 77 to 43), Triple("en", "Create blueprint", 83 to 45))
        for (settings in listOf(BuilderPanelSettings(), BuilderPanelSettings(labelScale = 0.4f),
            BuilderPanelSettings(buttonWidth = 0.75f), BuilderPanelSettings(buttonWidth = 0.2f))) {
            for ((locale, label, widths) in labels) {
                BuilderLocalePolicy.configure(locale, false)
                PanelHarness(settings, messages).use { h ->
                    h.current = h.current!!.copy(actions = listOf(BuilderPanelAction.DRAFT, BuilderPanelAction.COPY))
                    val buttons = checkNotNull(h.panel.frame(h.player)).layout.buttons
                    val draft = buttons.single { it.action == BuilderPanelAction.DRAFT }
                    plain.serialize(draft.text) shouldBe label
                    draft.rect.width shouldBe settings.buttonWidth.toDouble()
                    draft.rect.height shouldBe settings.buttonHeight.toDouble()
                    (draft.style.scale <= settings.labelScale) shouldBe true
                    val lineWidth = if (widths.first <= draft.style.lineWidth) widths.first else widths.second
                    (lineWidth <= draft.style.lineWidth) shouldBe true
                    ((lineWidth + 2) * 0.025 * draft.style.scale <= draft.rect.width) shouldBe true
                    buttons.single { it.action == BuilderPanelAction.COPY }.style.scale shouldBe settings.labelScale
                    h.panel.activate(h.player, checkNotNull(h.panel.frame(h.player)).context, BuilderPanelAction.DRAFT)
                    h.actions shouldBe listOf(BuilderPanelAction.DRAFT)
                }
            }
        }
    }

    "walking away changes only placement and keeps the exact selection actions and context" {
        PanelHarness().use { h ->
            h.current = h.current!!.copy(actions = listOf(BuilderPanelAction.COPY, BuilderPanelAction.PASTE,
                BuilderPanelAction.MOVE, BuilderPanelAction.DRAFT))
            val nearby = checkNotNull(h.panel.frame(h.player))
            h.feetX = 40.5
            h.current = h.current!!.copy(followView = true)
            val away = checkNotNull(h.panel.frame(h.player))
            (away.placement is ActionPanelPlacement.FollowView) shouldBe true
            away.context shouldBe nearby.context
            away.layout.buttons.map { it.action } shouldBe nearby.layout.buttons.map { it.action }
            h.panel.activate(h.player, away.context, BuilderPanelAction.PASTE)
            h.actions shouldBe listOf(BuilderPanelAction.PASTE)

            h.current = h.current!!.copy(followView = false)
            (checkNotNull(h.panel.frame(h.player)).placement is ActionPanelPlacement.FollowTarget) shouldBe true
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

    "Core runtime holds the Builder panel for head crouch and body jitter then smoothly follows deliberate motion" {
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
            label.location shouldBe initial
            h.feetX += 0.04
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

    "the distant menu keeps its whole safe pose beside blocks and recovers smoothly when the path clears" {
        MockBukkitTestRuntime.open().use { PanelHarness().use { h ->
            h.headYaw = 0f
            h.current = h.current!!.copy(followView = true, actions = listOf(
                BuilderPanelAction.FILL, BuilderPanelAction.REPLACE, BuilderPanelAction.COPY,
                BuilderPanelAction.MOVE, BuilderPanelAction.PASTE, BuilderPanelAction.DECONSTRUCT,
                BuilderPanelAction.PREVIOUS_PAGE, BuilderPanelAction.NEXT_PAGE,
            ), pagination = Component.text("Стр. 1 из 2"))
            val walls = mutableListOf(
                BoundingBox(-5.0, 64.0, 2.1, -1.15, 69.0, 3.5),
                BoundingBox(2.15, 64.0, 2.1, 6.0, 69.0, 3.5),
            )
            h.useCollisionWorld(walls)
            fun pose() = h.labels.map { it.location.clone() }
            h.panel.tick()
            h.labels.size shouldBe 10
            val initial = pose()
            repeat(3) {
                listOf(0.62, 0.38, 0.56, 0.44, 0.5).forEach { x ->
                    h.feetX = x
                    h.panel.tick()
                    pose() shouldBe initial
                }
            }

            walls += BoundingBox(-5.0, 64.0, 2.1, 6.0, 69.0, 3.1)
            h.panel.tick()
            h.labels.all { it.location.z < 2.1 && it.duration == 0 } shouldBe true
            walls.clear()
            var previous = pose()
            repeat(20) {
                h.panel.tick()
                val current = pose()
                current.zip(previous).all { (after, before) -> after.distance(before) <= 0.09 } shouldBe true
                previous = current
            }
            h.labels.all { abs(it.location.z - 2.9) < 1e-6 && !it.removed } shouldBe true

            h.headYaw = 85f
            h.headPitch = 30f
            h.eyeHeight = 1.27
            h.panel.tick()
            pose() shouldBe previous
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

private class PanelHarness(
    settings: BuilderPanelSettings = BuilderPanelSettings(),
    localizedMessages: LocalizedMiniMessage? = null,
) : AutoCloseable {
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
        every { world.spawn(any<Location>(), eq(Interaction::class.java), any<Consumer<Interaction>>()) } answers {
            var location = firstArg<Location>().clone()
            var valid = true
            val interaction = mockk<Interaction>(relaxed = true)
            every { interaction.uniqueId } returns UUID.randomUUID()
            every { interaction.world } returns world
            every { interaction.location } answers { location.clone() }
            every { interaction.isValid } answers { valid }
            every { interaction.teleport(any<Location>()) } answers { location = firstArg<Location>().clone(); true }
            every { interaction.remove() } answers { valid = false; Unit }
            thirdArg<Consumer<Interaction>>().accept(interaction)
            interaction
        }
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
        val messages = localizedMessages ?: mockk<LocalizedMiniMessage>().also { mocked ->
            every { mocked.render(any(), any(), any()) } answers {
                Component.text("${secondArg<String>()}:${firstArg<String>()}")
            }
        }
        current = BuilderPanelView("selection", selection(worldId, 0, 5), Component.text("selection"), emptyList())
        panel = BuilderSelectionActionPanel(plugin, settings, messages, { current }, { _, action -> actions += action },
            displays, { id, suppressed -> check(id == playerId); glow += suppressed })
    }

    fun useCollisionWorld(obstacles: List<BoundingBox>) {
        val blocks = mutableMapOf<Triple<Int, Int, Int>, Block>()
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers {
            val x = firstArg<Int>(); val y = secondArg<Int>(); val z = thirdArg<Int>()
            blocks.getOrPut(Triple(x, y, z)) {
                mockk<Block>().also { block ->
                    every { block.collisionShape.boundingBoxes } answers {
                        obstacles.mapNotNull { box ->
                            val minX = maxOf(box.minX, x.toDouble()); val maxX = minOf(box.maxX, x + 1.0)
                            val minY = maxOf(box.minY, y.toDouble()); val maxY = minOf(box.maxY, y + 1.0)
                            val minZ = maxOf(box.minZ, z.toDouble()); val maxZ = minOf(box.maxZ, z + 1.0)
                            if (minX >= maxX || minY >= maxY || minZ >= maxZ) null
                            else BoundingBox(minX - x, minY - y, minZ - z, maxX - x, maxY - y, maxZ - z)
                        }
                    }
                }
            }
        }
        every { world.rayTraceBlocks(any<Location>(), any<Vector>(), any<Double>(),
            any<FluidCollisionMode>(), any<Boolean>(), any<Predicate<Block>>()) } answers {
            val origin = firstArg<Location>().toVector()
            val direction = secondArg<Vector>()
            val distance = thirdArg<Double>()
            obstacles.mapNotNull { it.rayTrace(origin, direction, distance) }
                .minByOrNull { it.hitPosition.distanceSquared(origin) }
        }
    }

    override fun close() = panel.close()
}

private fun selection(worldId: UUID, x: Int, z: Int) = BuilderSelection(
    BuilderBlockPos(worldId, x, 64, z), BuilderBlockPos(worldId, x, 64, z),
)
