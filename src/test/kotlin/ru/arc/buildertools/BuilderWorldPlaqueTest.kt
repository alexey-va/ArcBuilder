package ru.arc.buildertools

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Color
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.entity.Interaction
import org.bukkit.attribute.Attribute
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.Transformation
import org.bukkit.util.BoundingBox
import org.bukkit.util.RayTraceResult
import org.bukkit.util.Vector
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PacketDisplayAudienceGroup
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.scene.AnchoredSurface
import ru.arc.paper.scene.PaperSpatialSpace
import ru.arc.paper.scene.PaperSpatialControls
import ru.arc.paper.scene.SceneAudience
import ru.arc.paper.scene.SpatialInputProfile
import ru.arc.paper.scene.SpatialReach
import ru.arc.paper.scene.SpatialSceneScope
import ru.arc.paper.scene.SpatialSceneKey
import ru.arc.paper.scene.SpatialControlController
import ru.arc.paper.scene.SpatialControlFrame
import java.util.UUID
import java.util.function.Consumer

class BuilderWorldPlaqueTest : StringSpec({
    "a two-sided plaque covers its full scaled label with narrow native targets and stable visual identities" {
        val h = BuilderPlaqueDisplays()
        val world = mockk<World>(relaxed = true)
        val anchor = Location(world, 10.5, 66.25, 20.4, 90f, 0f)
        val space = unblockedPlaqueSpace()
        BuilderWorldPlaque(h.owner, space, plaqueStyle(), SceneAudience.Public()).use { plaque ->
            plaque.update(anchor, Component.text("Постройка\nПКМ"))
            h.texts.size shouldBe 2
            h.texts.map { it.location.yaw } shouldBe listOf(90f, 270f)
            h.texts.forEach { record ->
                record.transform.scale shouldBe org.joml.Vector3f(2f)
                record.transform.translation.z shouldBe .02f
                verify { record.display.billboard = Display.Billboard.FIXED }
            }
            val control = plaque.controls("first")
            control.scope shouldBe SpatialSceneScope.World
            control.input shouldBe SpatialInputProfile.ENTITY_RIGHT_EARLY
            control.reach shouldBe SpatialReach.NativeInteraction
            control.controls.size shouldBe 4
            control.controls.forEach { strip ->
                strip.nativeTarget!!.also {
                    it.width shouldBe 2.35f
                    it.height shouldBe 1.5f
                    it.location().y shouldBe (anchor.y + .5 - .75 plusOrMinus 1e-8)
                }
                strip.planes.single().frontFaceOnly shouldBe false
            }
            control.controls.first().nativeTarget!!.location().z shouldBe (anchor.z - 3.525 plusOrMinus 1e-8)
            control.controls.last().nativeTarget!!.location().z shouldBe (anchor.z + 3.525 plusOrMinus 1e-8)
            plaque.update(anchor.clone().add(0.0, 0.0, .25), Component.text("Обновлено"))
            h.texts.size shouldBe 2
            h.texts.map { it.location.z }.distinct() shouldBe listOf(20.65)
            plaque.controls("replacement").context shouldBe "replacement"
        }
        h.texts.forEach { verify(exactly = 1) { it.display.remove() } }
        verify(exactly = 0) { h.owner.close() }
    }

    "a blocked plaque hides its target and restores the same text handles without moving its authored anchor" {
        val h = BuilderPlaqueDisplays()
        val anchor = Location(mockk<World>(relaxed = true), 5.5, 66.25, 7.4)
        var blocked = false
        val space = mockk<PaperSpatialSpace>()
        every { space.place(any(), any(), any()) } answers {
            if (blocked) null else firstArg<AnchoredSurface>().location().add(.5, .25, 0.0)
        }
        BuilderWorldPlaque(h.owner, space, plaqueStyle(), SceneAudience.Public()).use { plaque ->
            plaque.update(anchor, Component.text("Стройплощадка"))
            h.texts.size shouldBe 2
            h.texts.first().location.x shouldBe 6.0
            plaque.controls(Unit).clearanceFootprints.single().bounds.centerX shouldBe (6.0 plusOrMinus 1e-8)
            plaque.controls(Unit).clearanceFootprints.single().bounds.centerY shouldBe (66.75 plusOrMinus 1e-8)
            blocked = true
            plaque.refresh()
            plaque.controls(Unit).controls.all { it.nativeTarget == null && !it.visible } shouldBe true
            h.texts.size shouldBe 2
            blocked = false
            plaque.refresh()
            plaque.controls(Unit).controls.all { it.visible } shouldBe true
            h.texts.size shouldBe 2
            anchor.x shouldBe 5.5
            anchor.y shouldBe 66.25
            anchor.z shouldBe 7.4
        }
    }

    "a colliding plaque escapes forward before moving up and retains its two handles" {
        val h = BuilderPlaqueDisplays()
        val world = clearPlaqueWorld()
        var wallDepth = 1
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers {
            val boxes = if (secondArg<Int>() == 64 && thirdArg<Int>() in 0 until wallDepth)
                listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)) else emptyList()
            mockk<org.bukkit.block.Block> { every { collisionShape.boundingBoxes } returns boxes }
        }
        val anchor = Location(world, .5, 64.8, .8)
        mockkStatic(Bukkit::class)
        try {
            every { Bukkit.isPrimaryThread() } returns true
            BuilderWorldPlaque(h.owner, PaperSpatialSpace(), plaqueStyle(), SceneAudience.Public()).use { plaque ->
                plaque.update(anchor, Component.text("Дом"))
                h.texts.first().location.y shouldBe anchor.y
                h.texts.first().location.z shouldBe (1.05 plusOrMinus 1e-8)
                wallDepth = 2
                plaque.refresh()
                h.texts.first().location.y shouldBe anchor.y
                h.texts.first().location.z shouldBe (2.05 plusOrMinus 1e-8)
                val moved = plaque.controls(Unit).clearanceFootprints.single()
                PaperSpatialSpace().clear(world, moved) shouldBe true
                h.texts.size shouldBe 2
                anchor.z shouldBe .8
                wallDepth = 4
                plaque.refresh()
                h.texts.first().location.z shouldBe anchor.z
                h.texts.first().location.y shouldBe (65.05 plusOrMinus 1e-8)
                wallDepth = 0
                plaque.refresh()
                h.texts.first().location shouldBe anchor
            }
        } finally { unmockkStatic(Bukkit::class) }
    }

    "ghost columns are excluded before world collision and expanded bounds reject the old pose" {
        val world = clearPlaqueWorld()
        var realWall = false
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers {
            val boxes = if (realWall && thirdArg<Int>() == 4 && secondArg<Int>() in 66..69)
                listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)) else emptyList()
            mockk<org.bukkit.block.Block> { every { collisionShape.boundingBoxes } returns boxes }
        }
        val origin = BuilderBlockPos(world.uid, 0, 64, 0)
        val occupied = listOf(-2, 2).flatMap { x -> (65..69).map { y -> BuilderBlockPos(world.uid, x, y, 1) } }
        val columns = listOf(BoundingBox(-2.0, 65.0, 1.0, -1.0, 70.0, 2.0), BoundingBox(2.0, 65.0, 1.0, 3.0, 70.0, 2.0))
        val content = Component.text("Настроить постройку\nБерёзовый коттедж\nПКМ — открыть меню")
        fun location(panel: BuilderSitePanelPlacement) = Location(world, panel.x, panel.y, panel.z, panel.yaw, 0f)
        fun panel(blocks: List<BuilderBlockPos>) = BuilderConstructionSiteDisplayLayout.panelPlacement(
            listOf(origin), BuilderConstructionSitePanelFace.MAX_Z, 2.6, .4,
            occupiedPositions = blocks, panelWidth = BuilderWorldPlaque.visualWidth(180),
            panelHeight = BuilderWorldPlaque.visualHeight(content, 180),
        )
        val old = location(panel(listOf(origin)))
        val before = BuilderWorldPlaque.visualFootprint(old, content, 180)
        columns.all(before::overlaps) shouldBe true
        val next = location(panel(occupied))
        val after = BuilderWorldPlaque.visualFootprint(next, content, 180)
        columns.none(after::overlaps) shouldBe true

        val h = BuilderPlaqueDisplays()
        val space = PaperSpatialSpace()
        mockkStatic(Bukkit::class)
        try {
            every { Bukkit.isPrimaryThread() } returns true
            // A packet-only column cannot trigger Core's Bukkit block-collision search.
            space.place(AnchoredSurface(old, listOf(before))) shouldBe old
            BuilderWorldPlaque(h.owner, space, plaqueStyle(), SceneAudience.Public()).use { plaque ->
                plaque.update(next, content)
                h.texts.first().location.z shouldBe (2.4 plusOrMinus 1e-8)
                plaque.controls(Unit).controls.size shouldBe 4
                plaque.controls(Unit).controls.forEach { it.nativeTarget!!.width shouldBe 2.35f }
                realWall = true
                val expanded = occupied.map { it.copy(z = 3) }
                plaque.update(location(panel(expanded)), content)
                // The old safe real-world pose at Z2.4 is now inside the enlarged ghost house.
                // Even when the new anchor is blocked, Core must not retain that previous pose.
                h.texts.first().location.z shouldBe (5.15 plusOrMinus 1e-8)
                h.texts.first().location.y shouldBe next.y
                val moved = plaque.controls(Unit).clearanceFootprints.single()
                moved.overlaps(BoundingBox(-3.0, 64.0, -2.0, 4.0, 71.0, 4.0)) shouldBe false
                space.clear(world, moved) shouldBe true
                h.texts.size shouldBe 2
            }
        } finally { unmockkStatic(Bukkit::class) }

        fun box(bounds: BoundingBox) = mapOf(
            "minX" to bounds.minX, "minY" to bounds.minY, "minZ" to bounds.minZ,
            "maxX" to bounds.maxX, "maxY" to bounds.maxY, "maxZ" to bounds.maxZ,
        )
        java.io.File("build/reports/builder-plaque/ghost-placement.json").apply {
            parentFile.mkdirs()
            writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
                "evidence" to "Source geometry from BuilderWorldPlaqueTest; not a native client render",
                "ghostBounds" to box(BoundingBox(-3.0, 64.0, -2.0, 4.0, 71.0, 2.0)),
                "columns" to columns.map(::box), "before" to box(before.bounds), "after" to box(after.bounds),
            )))
        }
    }

    "a changed authored pose recalculates the nearest real clearance instead of retaining a far old escape" {
        val h = BuilderPlaqueDisplays()
        val world = clearPlaqueWorld()
        var wallDepth = 2
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers {
            val boxes = if (secondArg<Int>() == 64 && thirdArg<Int>() in 0 until wallDepth)
                listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)) else emptyList()
            mockk<org.bukkit.block.Block> { every { collisionShape.boundingBoxes } returns boxes }
        }
        mockkStatic(Bukkit::class)
        try {
            every { Bukkit.isPrimaryThread() } returns true
            BuilderWorldPlaque(h.owner, PaperSpatialSpace(), plaqueStyle(), SceneAudience.Public()).use { plaque ->
                plaque.update(Location(world, .5, 64.8, .8), Component.text("Дом"))
                h.texts.first().location.z shouldBe (2.05 plusOrMinus 1e-8)
                wallDepth = 1
                plaque.update(Location(world, .5, 64.8, 1.0), Component.text("Дом"))
                h.texts.first().location.z shouldBe (1.25 plusOrMinus 1e-8)
                PaperSpatialSpace().clear(world, plaque.controls(Unit).clearanceFootprints.single()) shouldBe true
                h.texts.size shouldBe 2
            }
        } finally { unmockkStatic(Bukkit::class) }
    }

    "native text is bottom anchored and a supplementary glyph counts as one conservative wrapped line" {
        val at = Location(mockk<World>(relaxed = true), 0.0, 64.0, 0.0)
        val single = BuilderWorldPlaque.visualFootprint(at, Component.text("🛠"), 180).bounds
        single.minY shouldBe (63.98 plusOrMinus 1e-8)
        single.maxY shouldBe (64.52 plusOrMinus 1e-8)
        single.widthX shouldBe (9.2 plusOrMinus 1e-8)
        val multiline = BuilderWorldPlaque.visualFootprint(at, Component.text("🛠\nДом\nПКМ"), 180).bounds
        multiline.minY shouldBe (63.98 plusOrMinus 1e-8)
        multiline.maxY shouldBe (65.52 plusOrMinus 1e-8)
        val privateGlyph = BuilderWorldPlaque.visualFootprint(at, Component.text("\uE000\uE001"), 180).bounds
        privateGlyph.maxY shouldBe (65.02 plusOrMinus 1e-8)
    }

    "unknown-glyph fallback preserves native word boundaries in its conservative height" {
        val at = Location(mockk<World>(relaxed = true), 0.0, 64.0, 0.0)
        val advance = ru.arc.paper.menu.DialogTextLayout.glyphWidth('M') + 1
        val word = "M".repeat(180 / advance / 2 + 1)
        val wrapped = BuilderWorldPlaque.visualFootprint(at, Component.text("\uE000 $word $word $word"), 180).bounds
        wrapped.minY shouldBe (63.98 plusOrMinus 1e-8)
        wrapped.maxY shouldBe (66.02 plusOrMinus 1e-8)
        val boldLine = Component.text("M".repeat(180 / advance))
            .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD)
        val bold = BuilderWorldPlaque.visualFootprint(at, Component.text("\uE000\n").append(boldLine), 180).bounds
        bold.maxY shouldBe (65.52 plusOrMinus 1e-8)
    }

    "both faces accept the full label edges and reject misses walls and remote clicks" {
        val h = BuilderPlaqueDisplays()
        val world = clearPlaqueWorld()
        every { world.rayTraceBlocks(any(), any(), any(), any(), any(), any()) } returns null
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()).collisionShape.boundingBoxes } returns emptyList()
        every { world.spawn(any<Location>(), Interaction::class.java, any<Consumer<Interaction>>()) } answers {
            val at = firstArg<Location>()
            mockk<Interaction>(relaxed = true).also { target ->
                every { target.uniqueId } returns UUID.randomUUID()
                every { target.isValid } returns true
                every { target.location } returns at
                every { target.interactionWidth } returns 3f
                every { target.interactionHeight } returns 1.5f
                thirdArg<Consumer<Interaction>>().accept(target)
            }
        }
        val player = mockk<Player>(relaxed = true)
        val anchor = Location(world, 4.5, 66.25, 6.4)
        var eye = anchor.clone().add(0.0, .75, 2.0).apply { yaw = 180f }
        every { player.uniqueId } returns UUID.randomUUID()
        every { player.world } returns world
        every { player.isOnline } returns true
        every { player.isDead } returns false
        every { player.isChunkSent(any<Long>()) } returns true
        every { player.eyeLocation } answers { eye.clone() }
        every { player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE) } returns null
        val plugin = mockk<JavaPlugin>(relaxed = true)
        every { plugin.server.onlinePlayers } returns listOf(player)
        every { plugin.server.getPlayer(player.uniqueId) } returns player
        mockkStatic(Bukkit::class)
        try {
            every { Bukkit.isPrimaryThread() } returns true
            BuilderWorldPlaque(h.owner, unblockedPlaqueSpace(), plaqueStyle(), SceneAudience.Public()).use { plaque ->
                plaque.update(anchor, Component.text("Постройка\nДом\nМатериалы\nПКМ"))
                val key = SpatialSceneKey("plaque")
                val controller = object : SpatialControlController<String, Unit>() {
                    override fun current(player: Player, scene: SpatialSceneKey): SpatialControlFrame<String, Unit> = plaque.controls("current")
                    override fun activate(player: Player, scene: SpatialSceneKey, context: String, action: Unit) = Unit
                }
                PaperSpatialControls(plugin, h.owner, controller).use { controls ->
                    controls.apply(key, plaque.controls("current"))
                    controls.target(key, player)?.context shouldBe "current"
                    eye = anchor.clone().add(0.0, .75, -2.0)
                    controls.target(key, player)?.context shouldBe "current"
                    // The old 3 x 1.5 target missed these visible outer corners.
                    eye = anchor.clone().add(4.55, 1.95, 2.0).apply { yaw = 180f }
                    controls.target(key, player)?.context shouldBe "current"
                    eye = anchor.clone().add(-4.55, .05, -2.0)
                    controls.target(key, player)?.context shouldBe "current"
                    eye = anchor.clone().add(4.8, .75, 2.0).apply { yaw = 180f }
                    controls.target(key, player) shouldBe null
                    eye = anchor.clone().add(0.0, -.25, 2.0).apply { yaw = 180f }
                    controls.target(key, player) shouldBe null
                    eye = anchor.clone().add(0.0, .75, 3.1).apply { yaw = 180f }
                    controls.target(key, player) shouldBe null
                    eye = anchor.clone().add(0.0, .75, 2.0).apply { yaw = 180f }
                    every { world.rayTraceBlocks(any(), any(), any(), any(), any(), any()) } returns
                        RayTraceResult(Vector(anchor.x, anchor.y + .75, anchor.z + 1.0))
                    controls.target(key, player) shouldBe null
                    every { world.rayTraceBlocks(any(), any(), any(), any(), any(), any()) } returns null
                    every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()).collisionShape.boundingBoxes } returns
                        listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0))
                    controls.target(key, player) shouldBe null
                }
            }
        } finally { unmockkStatic(Bukkit::class) }
    }
})

private fun clearPlaqueWorld(): World = mockk<World>(relaxed = true).also { world ->
    every { world.uid } returns UUID.randomUUID()
    every { world.minHeight } returns -64
    every { world.maxHeight } returns 320
    every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns true
    every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()).collisionShape.boundingBoxes } returns emptyList()
}

internal fun unblockedPlaqueSpace(): PaperSpatialSpace = mockk<PaperSpatialSpace>().also { space ->
    every { space.place(any(), any(), any()) } answers { firstArg<AnchoredSurface>().location() }
}

internal fun plaqueStyle() = BuilderWorldPlaqueStyle(
    3f, 1.5f, 180, Color.fromARGB(0xB21C2328.toInt()), Color.fromRGB(0xFFB142), 64.0,
)

/** Observable packet handles shared by the two adapter tests; no native display fallback. */
internal class BuilderPlaqueDisplays {
    class Text(var location: Location, var content: Component) {
        val display = mockk<PacketTextDisplay>(relaxed = true)
        var transform = Transformation(org.joml.Vector3f(), org.joml.Quaternionf(), org.joml.Vector3f(1f), org.joml.Quaternionf())
        init {
            every { display.isValid } returns true
            every { display.location } answers { location.clone() }
            every { display.text() } answers { content }
            every { display.text(any<Component>()) } answers { content = firstArg(); Unit }
            every { display.teleport(any()) } answers { location = firstArg<Location>().clone(); Unit }
            every { display.transformation = any() } answers { transform = firstArg(); Unit }
        }
    }
    val owner = mockk<PaperPacketDisplays>(relaxed = true)
    val texts = mutableListOf<Text>()
    val blocks = mutableListOf<PacketBlockDisplay>()
    val audiencePredicates = mutableListOf<(Player) -> Boolean>()
    val audienceGroups = mutableListOf<PacketDisplayAudienceGroup>()
    init {
        every { owner.spawnText(any(), any()) } answers {
            Text(firstArg<Location>().clone(), secondArg()).also(texts::add).display
        }
        every { owner.spawnBlock(any(), any()) } answers {
            mockk<PacketBlockDisplay>(relaxed = true) { every { isValid } returns true }.also(blocks::add)
        }
        every { owner.createAudienceGroup(any()) } answers {
            audiencePredicates += firstArg<(Player) -> Boolean>()
            mockk<PacketDisplayAudienceGroup>(relaxed = true).also(audienceGroups::add)
        }
    }
}
