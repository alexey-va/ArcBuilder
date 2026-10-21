package ru.arc.buildertools

import com.google.gson.GsonBuilder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.autobuild.ConstructionSite
import ru.arc.config.Config
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.io.File
import java.nio.file.Files
import java.util.UUID

class BuilderPastePreviewTest : FunSpec({
    test("launch and offhand cannot lock and the fresh click captures the published pose without mutations") {
        withPasteFixture { f ->
            val inventory = f.player.inventory.contents.map { it?.clone() }
            val first = f.target!!
            f.owner.begin(f.player)
            f.renderer.publish()
            f.owner.click(f.player, mainHand = true) shouldBe true
            f.locked shouldBe emptyList()
            f.tick++
            f.owner.click(f.player, mainHand = false) shouldBe true
            f.locked shouldBe emptyList()

            // The latest requested pose has not reached the renderer's complete publication boundary.
            f.target = first.copy(x = 7, y = 65)
            f.owner.tick()
            f.owner.click(f.player, mainHand = true) shouldBe true
            f.locked.single().anchor shouldBe first
            f.renderer.frozen shouldBe true
            f.player.inventory.contents.toList() shouldBe inventory
            f.player.world.getBlockAt(5, 64, 4).type shouldBe Material.AIR
            f.owner.click(f.player, mainHand = true) shouldBe true
            f.locked.size shouldBe 1

            f.owner.prepared(f.player.uniqueId, f.locked.single(), UUID.randomUUID())
            f.target = first.copy(x = 11)
            f.owner.tick()
            f.renderer.fixed shouldBe first
            f.owner.click(f.player, mainHand = true) shouldBe false
            f.owner.rotate(f.player, 90) shouldBe true
            f.locked.last().anchor shouldBe first
            f.locked.last().rotation shouldBe 90
        }
    }

    test("missing cursor and cancellation fence delayed geometry without discarding the reusable clipboard") {
        val tasks = QueuedBuilderPlanningTasks()
        withPasteFixture(tasks) { f ->
            f.target = null
            f.owner.begin(f.player)
            tasks.captureSteps shouldBe 0
            f.renderer.request shouldBe null
            f.owner.clear(f.player.uniqueId) shouldBe true
            tasks.runComputations()
            tasks.tick()
            f.renderer.request shouldBe null
            f.owner.isActive(f.player.uniqueId) shouldBe false

            f.target = BuilderBlockPos(f.player.world.uid, 8, 66, 3)
            f.owner.begin(f.player)
            tasks.runComputations()
            tasks.tick()
            f.renderer.publish()
            f.tick++
            f.owner.click(f.player, true)
            f.isCurrent!!.invoke() shouldBe true
            f.owner.clear(f.player.uniqueId)
            f.isCurrent!!.invoke() shouldBe false
            f.clipboard.current(f.player.uniqueId) shouldBe f.source
            f.renderer.request shouldBe null
        }
    }

    test("slot or forbidden-context exits clear only the paste and a camera turn does not rotate it") {
        withPasteFixture { f ->
            f.owner.begin(f.player)
            f.renderer.publish()
            f.player.setRotation(150f, 25f)
            f.target = f.target!!.copy(z = 7)
            f.owner.tick()
            f.renderer.request!!.model.rotation shouldBe 0
            f.player.inventory.heldItemSlot = 1
            f.owner.tick()
            f.owner.isActive(f.player.uniqueId) shouldBe false
            f.renderer.cleared shouldBe listOf(f.player.uniqueId)
            f.clipboard.current(f.player.uniqueId) shouldBe f.source

            f.owner.begin(f.player)
            f.allowed = false
            f.owner.tick()
            f.owner.isActive(f.player.uniqueId) shouldBe false
        }
    }

    test("large geometry and repeated rotation requests stay off-thread and coalesce to the latest orientation") {
        val tasks = QueuedBuilderPlanningTasks()
        val main = Thread.currentThread()
        var reads = 0
        val blocks = object : AbstractList<BuilderClipboardBlock>() {
            override val size = 100_000
            override fun get(index: Int): BuilderClipboardBlock {
                (Thread.currentThread() === main) shouldBe false
                reads++
                return BuilderClipboardBlock(index % 100, index / 100 % 100, index / 10_000, "minecraft:stone")
            }
        }
        withPasteFixture(tasks, blocks) { f ->
            f.owner.begin(f.player)
            f.owner.rotate(f.player, 90)
            f.owner.rotate(f.player, 90)
            reads shouldBe 0
            tasks.runComputations()
            tasks.tick()
            f.renderer.request shouldBe null
            tasks.runComputations()
            tasks.tick()
            tasks.workerRuns shouldBe 2
            f.renderer.request!!.model.rotation shouldBe 180
            f.target = f.target!!.copy(x = 6)
            f.owner.tick()
            tasks.workerRuns shouldBe 2
            tasks.captureSteps shouldBe 0
        }
    }

    test("cursor cell is the copied origin under every rotation including negative offsets") {
        val source = pasteSource(listOf(BuilderClipboardBlock(0, 0, 0, "minecraft:oak_planks"),
            BuilderClipboardBlock(2, 1, 1, "minecraft:stone"))).copy(originDx = 1, originDy = -2, originDz = 3)
        val anchor = BuilderBlockPos(UUID(0, 1), 15, 70, -6)
        val expected = mapOf(0 to listOf(Triple(14, 72, -9), Triple(16, 73, -8)),
            90 to listOf(Triple(18, 72, -7), Triple(17, 73, -5)),
            180 to listOf(Triple(16, 72, -3), Triple(14, 73, -4)),
            270 to listOf(Triple(12, 72, -5), Triple(13, 73, -7)))
        expected.forEach { (rotation, targets) ->
            val placement = BuilderPastePlacement(source, anchor, rotation)
            placement.targets().map { (position, _) -> Triple(position.x, position.y, position.z) } shouldBe targets
            val model = BuilderPastePreviewModel.create(source, rotation)
            model.blocks.map { Triple(anchor.x + it.dx, anchor.y + it.dy, anchor.z + it.dz) } shouldBe targets
        }
    }

    test("existing locale overrides survive merge while new cursor instructions become available") {
        val root = Files.createTempDirectory("builder-paste-locales")
        val path = root.resolve("modules/builder-tools.yml")
        Files.createDirectories(path.parent)
        Files.writeString(path, """
            locales:
              ru:
                clipboard:
                  saved: 'Моя сохранённая подпись'
                kinds:
                  paste: 'Мой заголовок'
        """.trimIndent())
        BuilderToolsConfig.mergeBundledDefaults(root) shouldBe true
        val config = Config(root, "modules/builder-tools.yml")
        config.string("locales.ru.clipboard.saved") shouldBe "Моя сохранённая подпись"
        config.string("locales.ru.kinds.paste") shouldBe "Мой заголовок"
        config.string("locales.ru.clipboard.moving") shouldContain "Shift"
        config.string("locales.ru.clipboard.saved-cursor") shouldContain "Разместить чертёж"
        config.string("locales.en.clipboard.fixed-actions") shouldContain "/builder paste left"
        BuilderToolsConfig(config).validated()
    }

    test("export moving locked and rotated paste from the production relative geometry") {
        val source = pasteSource(listOf(
            BuilderClipboardBlock(0, 0, 0, "minecraft:oak_planks"),
            BuilderClipboardBlock(1, 0, 0, "minecraft:oak_planks"),
            BuilderClipboardBlock(0, 1, 0, "minecraft:oak_log[axis=y]"),
            BuilderClipboardBlock(1, 1, 0, "minecraft:oak_slab[type=bottom,waterlogged=false]"),
            BuilderClipboardBlock(0, 2, 0, "minecraft:oak_leaves[persistent=true,distance=1,waterlogged=false]"),
        )).copy(originDx = 0, originDy = 0, originDz = 2)
        val palette = linkedMapOf<String, String>()
        val states = listOf(Triple("moving", BuilderBlockPos(UUID(0, 1), 2, 0, 3), 0),
            Triple("locked", BuilderBlockPos(UUID(0, 1), 4, 0, 3), 0),
            Triple("rotated", BuilderBlockPos(UUID(0, 1), 4, 0, 3), 90)).map { (state, anchor, rotation) ->
            val model = BuilderPastePreviewModel.create(source, rotation)
            fun piece(key: String, material: String, x: Double, y: Double, z: Double,
                width: Number, height: Number, depth: Number) = mapOf("key" to key, "material" to material,
                "x" to x, "y" to y, "z" to z, "width" to width, "height" to height, "depth" to depth)
                .also { palette.putIfAbsent(material, "minecraft:$material") }
            val blocks = model.blocks.mapIndexed { index, block ->
                piece("clipboard-$index", block.blockData.removePrefix("minecraft:"),
                    anchor.x + block.dx + .5, anchor.y + block.dy + .5, anchor.z + block.dz + .5, 1, 1, 1)
            }
            val bounds = model.bounds.mapIndexed { index, edge ->
                piece("bounds-$index", if (state == "moving") "orange_stained_glass" else "magenta_stained_glass",
                    anchor.x + edge.x + edge.scaleX / 2, anchor.y + edge.y + edge.scaleY / 2,
                    anchor.z + edge.z + edge.scaleZ / 2, edge.scaleX, edge.scaleY, edge.scaleZ)
            }
            val origin = BuilderDisplayGeometry.originMarker(anchor).mapIndexed { index, edge ->
                piece("origin-$index", "lime_concrete", edge.x + edge.scaleX / 2, edge.y + edge.scaleY / 2,
                    edge.z + edge.scaleZ / 2, edge.scaleX, edge.scaleY, edge.scaleZ)
            }
            mapOf("id" to state, "title" to state, "role" to "Source-derived clipboard geometry; no native glow or packet timing",
                "pieces" to blocks + bounds + origin,
                "camera" to mapOf("position" to listOf(1.5, 1.62, 6.5),
                    "target" to listOf(anchor.x + .5, anchor.y + .5, anchor.z + .5), "fovDegrees" to 70))
        }
        File("build/reports/paste-preview/scene.json").apply {
            parentFile.mkdirs()
            writeText(GsonBuilder().setPrettyPrinting().create().toJson(mapOf("states" to states, "palette" to palette)))
        }
    }
})

private fun pasteSource(blocks: List<BuilderClipboardBlock>) = BuilderClipboard(blocks, 3, 3, 3, 1, Long.MAX_VALUE)

private class PastePreviewFixture(
    val player: Player,
    val source: BuilderClipboard,
    tasks: BuilderPlanningTasks,
) : AutoCloseable {
    val clipboard = mockk<BuilderClipboardController>(relaxed = true)
    val renderer = PastePreviewRenderer()
    var target: BuilderBlockPos? = BuilderBlockPos(player.world.uid, 5, 64, 4)
    var allowed = true
    var tick = 0
    val locked = mutableListOf<BuilderPastePlacement>()
    var isCurrent: (() -> Boolean)? = null
    init {
        every { clipboard.current(player.uniqueId) } returns source
        every { clipboard.placement(player, any()) } answers { BuilderPastePlacement(source, secondArg(), 0) }
    }
    val owner = BuilderPastePreview(clipboard, renderer, tasks, { target }, { allowed },
        onLock = { _, placement, current -> locked += placement; isCurrent = current },
        onClear = { _, _ -> }, onFailure = { _, failure -> throw failure }, hint = { _, _ -> }, currentTick = { tick })
    override fun close() = owner.close()
}

private class PastePreviewRenderer : BuilderDisplayRenderer {
    data class Request(val model: BuilderPastePreviewModel, val anchor: BuilderBlockPos, val displayed: (BuilderBlockPos) -> Unit)
    var request: Request? = null
    var frozen = false
    var fixed: BuilderBlockPos? = null
    val cleared = mutableListOf<UUID>()
    fun publish() { request!!.let { it.displayed(it.anchor) } }
    override fun paste(player: Player, model: BuilderPastePreviewModel, anchor: BuilderBlockPos, displayed: (BuilderBlockPos) -> Unit) {
        request = Request(model, anchor, displayed)
        frozen = false
    }
    override fun freezePaste(playerId: UUID) { frozen = true }
    override fun fixedPaste(player: Player, anchor: BuilderBlockPos) { fixed = anchor }
    override fun clearPaste(playerId: UUID) { cleared += playerId; request = null }
    override fun selection(player: Player, points: BuilderSelectionPoints, selection: BuilderSelection?) = Unit
    override fun clearSelection(playerId: UUID) = Unit
    override fun plan(player: Player, plan: BuilderPlan) = Unit
    override fun clearPlan(playerId: UUID) = Unit
    override fun clearPlayer(playerId: UUID) = Unit
    override fun open(site: ConstructionSite) = Unit
    override fun refresh(site: ConstructionSite) = Unit
    override fun close(playerId: UUID) = Unit
    override fun close() = Unit
}

private fun withPasteFixture(
    tasks: BuilderPlanningTasks = ImmediateBuilderPlanningTasks,
    blocks: List<BuilderClipboardBlock> = listOf(BuilderClipboardBlock(0, 0, 0, "minecraft:stone")),
    action: (PastePreviewFixture) -> Unit,
) {
    MockBukkitTestRuntime.open().use { paper ->
        val world = paper.addSimpleWorld("paste-cursor")
        val player = paper.addPlayer("PasteCursor")
        player.gameMode = GameMode.CREATIVE
        player.teleport(Location(world, .5, 64.0, 3.5))
        player.inventory.setItemInMainHand(ItemStack(Material.STONE, 4))
        PastePreviewFixture(player, pasteSource(blocks), tasks).use(action)
    }
}
