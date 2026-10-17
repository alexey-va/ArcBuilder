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
    "a two-sided plaque retains scale, configured native dimensions and stable visual identities" {
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
            control.controls.single().nativeTarget!!.also {
                it.width shouldBe 3f
                it.height shouldBe 1.5f
                it.location().y shouldBe (anchor.y + .5 - .75 plusOrMinus 1e-8)
            }
            control.controls.single().planes.single().frontFaceOnly shouldBe false
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
            plaque.controls(Unit).controls.single().nativeTarget shouldBe null
            plaque.controls(Unit).controls.single().visible shouldBe false
            h.texts.size shouldBe 2
            blocked = false
            plaque.refresh()
            plaque.controls(Unit).controls.single().visible shouldBe true
            h.texts.size shouldBe 2
            anchor.x shouldBe 5.5
            anchor.y shouldBe 66.25
            anchor.z shouldBe 7.4
        }
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

    "both text faces accept the current visible center and reject the legacy below-text hit" {
        val h = BuilderPlaqueDisplays()
        val world = mockk<World>(relaxed = true)
        every { world.uid } returns UUID.randomUUID()
        every { world.minHeight } returns -64
        every { world.maxHeight } returns 320
        every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns true
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
                plaque.update(anchor, Component.text("Постройка\nДом\nПКМ"))
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
                    eye = anchor.clone().add(0.0, -.25, 2.0).apply { yaw = 180f }
                    controls.target(key, player) shouldBe null
                }
            }
        } finally { unmockkStatic(Bukkit::class) }
    }
})

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
