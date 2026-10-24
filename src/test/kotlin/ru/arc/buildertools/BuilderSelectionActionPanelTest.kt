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
import ru.arc.paper.actionpanel.ActionPanelDepth
import ru.arc.paper.actionpanel.ActionPanelViewMotion
import ru.arc.paper.actionpanel.ActionPanelAnchor
import ru.arc.paper.actionpanel.ActionPanelGeometry
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

    "selection panel motion transition trace" {
        val trace = mutableListOf<Map<String, Any?>>()
        val navigation = listOf(BuilderPanelAction.PREVIOUS_PAGE, BuilderPanelAction.NEXT_PAGE)
        val firstPage = listOf(BuilderPanelAction.FILL, BuilderPanelAction.REPLACE, BuilderPanelAction.COPY,
            BuilderPanelAction.MOVE, BuilderPanelAction.PASTE, BuilderPanelAction.CLEAR) + navigation
        MockBukkitTestRuntime.open().use {
            PanelHarness().use { h ->
                h.headYaw = 180f
                h.current = h.current!!.copy(actions = firstPage, pagination = Component.text("1 / 2"))
                h.panel.tick(); trace += h.motionSnapshot("near-selection-looking-away")
                (h.anchor().point.z < .5) shouldBe true
                h.feetX = 40.5
                h.panel.tick(); trace += h.motionSnapshot("far-selection")
                h.headYaw = 0f
                h.panel.tick(); trace += h.motionSnapshot("stationary-head-turn")
                h.aimAt(BuilderPanelAction.NEXT_PAGE)
                h.panel.intercept(h.player) shouldBe true
                val beforePage = h.anchor()
                h.current = h.current!!.copy(context = "page-2", actions = listOf(BuilderPanelAction.DRAFT,
                    BuilderPanelAction.CROWN, BuilderPanelAction.CLEAR) + navigation, pagination = Component.text("2 / 2"))
                h.panel.tick(); trace += h.motionSnapshot("page-click")
                h.anchor() shouldBe beforePage
            }
            PanelHarness().use { h ->
                h.headYaw = 0f
                h.current = h.current!!.copy(actions = firstPage, pagination = Component.text("1 / 2"))
                val walls = mutableListOf<BoundingBox>()
                h.useCollisionWorld(walls)
                h.panel.tick(); trace += h.motionSnapshot("open-space")
                val beforeObstacle = h.anchor()
                walls += BoundingBox(-5.0, 64.0, 2.1, 6.0, 69.0, 3.1)
                h.panel.tick(); trace += h.motionSnapshot("obstructed")
                h.anchor() shouldBe beforeObstacle
                walls.clear()
                repeat(5) { h.panel.tick(); trace += h.motionSnapshot("obstacle-return-$it") }
            }
            PanelHarness().use { h ->
                h.headYaw = 0f
                h.current = h.current!!.copy(context = "paste-plan", actions = listOf(
                    BuilderPanelAction.CONFIRM, BuilderPanelAction.CANCEL, BuilderPanelAction.ROTATE_LEFT, BuilderPanelAction.ROTATE_RIGHT))
                h.panel.tick(); trace += h.motionSnapshot("paste-confirm-start")
                h.aimAt(BuilderPanelAction.CONFIRM)
                val confirmYaw = h.anchor().yaw
                repeat(8) { tick ->
                    h.feetX += .04
                    h.panel.tick()
                    h.anchor().yaw shouldBe confirmYaw
                    h.panel.isTargeted(h.player) shouldBe true
                    trace += h.motionSnapshot("paste-confirm-aim-$tick")
                }
            }
        }
        java.io.File("build/reports/builder-selection-motion/after.json").apply {
            parentFile.mkdirs()
            writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(trace))
        }
        trace.size shouldBe 20
    }

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

    "the adapter preserves configured geometry with an aim-stable owner overlay" {
        val settings = BuilderPanelSettings(distance = 2.8, sideOffset = 0.2, heightOffset = -0.15,
            rowSpacing = 0.3, columnSpacing = 1.7, buttonWidth = 1.6f, buttonHeight = 0.25f,
            labelScale = 0.7f, reach = 4.5, suppressSelectionGlow = false, obstacleMaxShift = 0.5)
        PanelHarness(settings).use { h ->
            h.current = h.current!!.copy(actions = listOf(BuilderPanelAction.FILL, BuilderPanelAction.REPLACE))
            val frame = checkNotNull(h.panel.frame(h.player))
            val placement = frame.placement as ActionPanelPlacement.FollowView
            placement.worldId shouldBe h.world.uid
            placement.motion shouldBe ActionPanelViewMotion.AIM_STABLE
            frame.options.depth shouldBe ActionPanelDepth.VIEW_OVERLAY
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

    "near and far selection use the same view overlay with exact actions and context" {
        PanelHarness().use { h ->
            h.current = h.current!!.copy(actions = listOf(BuilderPanelAction.COPY, BuilderPanelAction.PASTE,
                BuilderPanelAction.MOVE, BuilderPanelAction.DRAFT))
            val nearby = checkNotNull(h.panel.frame(h.player))
            h.feetX = 40.5
            val away = checkNotNull(h.panel.frame(h.player))
            (away.placement is ActionPanelPlacement.FollowView) shouldBe true
            away.context shouldBe nearby.context
            away.layout.buttons.map { it.action } shouldBe nearby.layout.buttons.map { it.action }
            h.panel.activate(h.player, away.context, BuilderPanelAction.PASTE)
            h.actions shouldBe listOf(BuilderPanelAction.PASTE)

            h.feetX = 0.5
            (checkNotNull(h.panel.frame(h.player)).placement is ActionPanelPlacement.FollowView) shouldBe true
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

    "Core retains the aim plane through crouch and small body motion then closes all owned handles" {
        MockBukkitTestRuntime.open().use { PanelHarness().use { h ->
            h.headYaw = 0f
            h.panel.tick()
            val label = h.labels.single()
            val initial = label.location.clone()
            h.glow shouldBe listOf(true)
            h.headYaw = 4f
            h.eyeHeight = 1.27
            h.panel.tick()
            label.location shouldBe initial
            h.feetX += 0.005
            h.panel.tick()
            label.location shouldBe initial
            h.feetX += 0.04
            h.panel.tick()
            (abs(label.location.x - initial.x - .045) < 1e-8) shouldBe true
            (abs(label.location.y - initial.y) < 1e-8) shouldBe true
            label.location.yaw shouldBe initial.yaw
            label.duration shouldBe 2
            h.labels.size shouldBe 1
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

    "a real obstruction leaves the selection overlay pose and handles unchanged" {
        MockBukkitTestRuntime.open().use { PanelHarness().use { h ->
            h.headYaw = 0f
            h.current = h.current!!.copy(actions = listOf(BuilderPanelAction.CONFIRM, BuilderPanelAction.CANCEL))
            h.panel.tick()
            val initial = h.labels.map { it.location.clone() }
            every { h.world.rayTraceBlocks(any<Location>(), any<Vector>(), any<Double>(),
                any<FluidCollisionMode>(), any<Boolean>(), any<Predicate<Block>>()) } answers {
                org.bukkit.util.RayTraceResult(h.player.eyeLocation.toVector().add(h.player.eyeLocation.direction.multiply(.45)))
            }
            h.panel.tick()
            h.labels.map { it.location.clone() } shouldBe initial
            h.labels.all { !it.removed } shouldBe true
            h.aimAt(BuilderPanelAction.CONFIRM)
            h.panel.intercept(h.player) shouldBe true
            h.actions shouldBe listOf(BuilderPanelAction.CONFIRM)
            verify(exactly = 0) { h.world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) }
            verify(exactly = 0) { h.world.rayTraceBlocks(any<Location>(), any<Vector>(), any<Double>(),
                any<FluidCollisionMode>(), any<Boolean>(), any<Predicate<Block>>()) }
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
    val interactions = mutableListOf<Interaction>()
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
            interactions += interaction
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

    fun anchor(): ActionPanelAnchor {
        val owner = BuilderSelectionActionPanel::class.java.getDeclaredField("panels").apply { isAccessible = true }.get(panel)
        val map = owner.javaClass.getDeclaredField("panels").apply { isAccessible = true }.get(owner) as Map<*, *>
        val current = checkNotNull(map[player.uniqueId])
        return current.javaClass.getDeclaredField("anchor").apply { isAccessible = true }.get(current) as ActionPanelAnchor
    }

    fun aimAt(action: BuilderPanelAction) {
        val rect = checkNotNull(panel.frame(player)).layout.buttons.single { it.action == action }.rect
        val point = ActionPanelGeometry.localToWorld(anchor(), rect.center)
        val aim = player.eyeLocation.apply { direction = Vector(point.x - x, point.y - y, point.z - z) }
        headYaw = aim.yaw
        headPitch = aim.pitch
    }

    fun motionSnapshot(state: String): Map<String, Any?> {
        val anchor = anchor()
        val eye = player.eyeLocation
        val direction = eye.direction
        return mapOf("state" to state, "feetX" to feetX, "headYaw" to headYaw, "headPitch" to headPitch,
            "anchor" to anchor, "frontDot" to ((anchor.point.x - eye.x) * direction.x +
                (anchor.point.y - eye.y) * direction.y + (anchor.point.z - eye.z) * direction.z),
            "texts" to labels.mapIndexedNotNull { index, label -> if (label.removed) null else mapOf(
                "id" to index, "text" to PlainTextComponentSerializer.plainText().serialize(label.content),
                "x" to label.location.x, "y" to label.location.y, "z" to label.location.z, "yaw" to label.location.yaw,
                "duration" to label.duration) },
            "nativeIds" to interactions.mapIndexedNotNull { index, interaction -> index.takeIf { interaction.isValid } })
    }

    override fun close() = panel.close()
}

private fun selection(worldId: UUID, x: Int, z: Int) = BuilderSelection(
    BuilderBlockPos(worldId, x, 64, z), BuilderBlockPos(worldId, x, 64, z),
)
