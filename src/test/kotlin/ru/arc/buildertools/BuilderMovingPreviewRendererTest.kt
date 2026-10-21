package ru.arc.buildertools

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.world.block.BaseBlock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Player
import ru.arc.autobuild.BuildBookData
import ru.arc.autobuild.ConstructionSite
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.arc.text.LocalizedMiniMessage
import java.util.UUID

class BuilderMovingPreviewRendererTest : FunSpec({
    test("paste publishes native clipboard states and origin together and freeze fences a pending cursor pose") {
        withMovingPreview { fixture ->
            for (x in -1..3) for (z in -1..0) fixture.world.loadChunk(x, z)
            val clipboard = BuilderClipboard((0 until 64).map {
                BuilderClipboardBlock(it, 0, 0, if (it % 2 == 0) "minecraft:stone" else "minecraft:oak_planks")
            }, 64, 1, 1, 1, Long.MAX_VALUE)
            val model = BuilderPastePreviewModel.create(clipboard, 0)
            val first = BuilderBlockPos(fixture.world.uid, 0, 64, 0)
            var displayed: BuilderBlockPos? = null
            fixture.renderer.paste(fixture.player, model, first) { displayed = it }
            displayed shouldBe null
            fixture.drain()
            displayed shouldBe first
            fixture.visibleBlocks() shouldBe (0..31).toList()
            fixture.blockStateConversions.any { it.material == Material.OAK_PLANKS } shouldBe true
            fixture.active.values.count { it.glowRgb == 0x00ff00 } shouldBe 12
            fixture.active.values.filter { it.glowRgb == 0x00ff00 }.map {
                BuilderDisplayEdge(it.x, it.y, it.z, it.scaleX, it.scaleY, it.scaleZ)
            }.toSet() shouldBe BuilderDisplayGeometry.originMarker(first).toSet()
            val original = fixture.active.toMap()

            fixture.renderer.paste(fixture.player, model, first.copy(x = 3)) { displayed = it }
            displayed shouldBe first
            fixture.renderer.freezePaste(fixture.player.uniqueId)
            fixture.drain()
            fixture.active shouldBe original
            displayed shouldBe first

            val next = first.copy(x = 4)
            fixture.renderer.paste(fixture.player, model, next) { displayed = it }
            fixture.drain()
            displayed shouldBe next
            fixture.world.entities.filterIsInstance<BlockDisplay>().size shouldBe 0
            fixture.renderer.clearPaste(fixture.player.uniqueId)
            fixture.drain()
            fixture.active shouldBe emptyMap()
        }
    }

    test("fixed paste keeps its moving scene until the protected plan publishes then retains the exact origin") {
        withMovingPreview { fixture ->
            for (x in -1..0) for (z in -1..0) fixture.world.loadChunk(x, z)
            val clipboard = BuilderClipboard(listOf(BuilderClipboardBlock(0, 0, 0, "minecraft:stone")),
                1, 1, 1, 1, Long.MAX_VALUE)
            val anchor = BuilderBlockPos(fixture.world.uid, 2, 64, 0)
            fixture.renderer.paste(fixture.player, BuilderPastePreviewModel.create(clipboard, 0), anchor) { }
            fixture.drain()
            val original = fixture.active.toMap()
            val plan = BuilderPlan(UUID.randomUUID(), fixture.player.uniqueId, BuilderPlanKind.PASTE,
                listOf(BuilderBlockChange(anchor, "minecraft:air", "minecraft:stone")), emptyList(), emptyList(),
                createdAtMillis = 1, expiresAtMillis = Long.MAX_VALUE)
            fixture.renderer.freezePaste(fixture.player.uniqueId)
            fixture.renderer.plan(fixture.player, plan)
            fixture.renderer.fixedPaste(fixture.player, anchor)
            fixture.active shouldBe original
            fixture.drain()
            fixture.active.values.any { it.glowRgb == 0xffb142 } shouldBe false
            fixture.active.values.count { it.glowRgb == 0x00ff00 } shouldBe 12
            fixture.active.values.filter { it.glowRgb == 0x00ff00 }.map {
                BuilderDisplayEdge(it.x, it.y, it.z, it.scaleX, it.scaleY, it.scaleZ)
            }.toSet() shouldBe BuilderDisplayGeometry.originMarker(anchor).toSet()
            fixture.visibleBlocks() shouldBe listOf(2)
            fixture.renderer.clearPlan(fixture.player.uniqueId)
            fixture.renderer.clearPaste(fixture.player.uniqueId)
            fixture.active shouldBe emptyMap()
        }
    }

    for (book in listOf(false, true)) {
        test("${if (book) "book" else "plan"} window follows sub-block movement without resending retained blocks") {
            withMovingPreview { fixture ->
                fixture.open(book)
                fixture.drain()
                fixture.visibleBlocks() shouldBe (0..31).toList()
                val first = fixture.active.toMap()
                val sends = fixture.sent.size
                val conversions = fixture.blockStateConversions.size

                fixture.player.teleport(Location(fixture.world, 16.50, 64.0, 0.0))
                fixture.scheduler.tick(2)
                fixture.drain()
                fixture.sent.size shouldBe sends // Equal-distance tie keeps the existing window.
                fixture.blockStateConversions.size shouldBe conversions

                fixture.player.teleport(Location(fixture.world, 16.51, 64.0, 0.0))
                fixture.scheduler.tick(2)
                fixture.drain()
                fixture.visibleBlocks() shouldBe (1..32).toList()
                fixture.sent.last().first.size shouldBe 1
                fixture.sent.last().second.size shouldBe 1
                fixture.blockStateConversions.size shouldBe conversions + 1
                fixture.active.values.filter { it.entityId in first }.forEach { display ->
                    display shouldBe first.getValue(display.entityId)
                }
                fixture.world.entities.filterIsInstance<BlockDisplay>().size shouldBe 0

                val changedSends = fixture.sent.size
                fixture.scheduler.tick(2)
                fixture.drain()
                fixture.sent.size shouldBe changedSends
            }
        }
    }

    test("leaving and returning to the same position rebuilds the window and close stops movement updates") {
        withMovingPreview { fixture ->
            fixture.open(book = false)
            fixture.drain()
            val position = fixture.player.location
            fixture.player.teleport(Location(fixture.otherWorld, 16.49, 64.0, 0.0))
            fixture.scheduler.tick(2)
            fixture.drain()
            fixture.active shouldBe emptyMap()
            fixture.player.teleport(position)
            fixture.scheduler.tick(2)
            fixture.drain()
            fixture.visibleBlocks() shouldBe (0..31).toList()
            fixture.renderer.clearPlayer(fixture.player.uniqueId)
            val sends = fixture.sent.size
            fixture.player.teleport(Location(fixture.world, 40.0, 64.0, 0.0))
            fixture.scheduler.tick(2)
            fixture.drain()
            fixture.sent.size shouldBe sends
            fixture.active shouldBe emptyMap()
        }
    }

    test("closing before the first asynchronous result cannot resurrect a scene") {
        withMovingPreview { fixture ->
            fixture.open(book = false)
            fixture.renderer.clearPlan(fixture.player.uniqueId)
            fixture.drain()
            fixture.sent shouldBe emptyList()
        }
    }

    test("a 100000-change plan is not scanned during the native request and a world change fences its result") {
        withMovingPreview { fixture ->
            var reads = 0
            val worldId = fixture.world.uid
            val changes = object : AbstractList<BuilderBlockChange>() {
                override val size = 100_000
                override fun get(index: Int): BuilderBlockChange {
                    reads++
                    return BuilderBlockChange(BuilderBlockPos(worldId, index, 64, 0), "minecraft:air", "minecraft:stone")
                }
            }
            fixture.renderer.plan(fixture.player, BuilderPlan(
                UUID.randomUUID(), fixture.player.uniqueId, BuilderPlanKind.BUILD_BOOK,
                changes, emptyList(), emptyList(), createdAtMillis = 0, expiresAtMillis = Long.MAX_VALUE,
            ))
            reads shouldBe 1
            fixture.player.teleport(Location(fixture.otherWorld, 16.49, 64.0, 0.0))
            fixture.drain()
            fixture.active shouldBe emptyMap()
        }
    }

    test("replacing a plan while its window is queued publishes only the replacement") {
        withMovingPreview { fixture ->
            fixture.open(book = false)
            fixture.open(book = false)
            fixture.drain()
            fixture.sent.size shouldBe 1
            fixture.visibleBlocks() shouldBe (0..31).toList()
        }
    }

    test("a failed replacement clears the previous plan instead of leaving a stale preview") {
        withMovingPreview { fixture ->
            fixture.open(book = false)
            fixture.drain()
            fixture.visibleBlocks() shouldBe (0..31).toList()
            fixture.open(book = false, afterBlockData = "minecraft:invalid_preview_block")
            fixture.drain()
            fixture.active shouldBe emptyMap()
        }
    }

    test("a large replacement shares the native step budget and publishes only its complete scene") {
        withMovingPreview(previewLimit = 1024) { fixture ->
            fixture.open(book = false, changeCount = 32)
            fixture.drain()
            val previous = fixture.active.toMap()
            val sends = fixture.sent.size
            val conversions = fixture.blockStateConversions.size
            fixture.open(book = false, changeCount = 2048)

            var sawUnpublishedAllocation = false
            fixture.advanceUntil({ fixture.sent.size > sends }) {
                val beforeTick = fixture.blockStateConversions.size
                fixture.scheduler.executeImmediate()
                fixture.scheduler.tick()
                (fixture.blockStateConversions.size - beforeTick in 0..512) shouldBe true
                if (fixture.sent.size == sends && fixture.blockStateConversions.size > conversions) {
                    sawUnpublishedAllocation = true
                    fixture.active shouldBe previous
                }
            }
            fixture.drain()

            sawUnpublishedAllocation shouldBe true
            fixture.sent.size shouldBe sends + 1
            fixture.visibleBlocks() shouldBe (0..1023).toList()
            previous.values.filter { it.scaleX == 1f && it.scaleY == 1f && it.scaleZ == 1f }.forEach {
                fixture.active[it.entityId] shouldBe it
            }
        }
    }

    test("clearing a replacement during sliced allocation cannot publish it later") {
        withMovingPreview(previewLimit = 1024) { fixture ->
            fixture.open(book = false, changeCount = 32)
            fixture.drain()
            val previous = fixture.active.toMap()
            val conversions = fixture.blockStateConversions.size
            fixture.open(book = false, changeCount = 2048)
            fixture.advanceUntil({ fixture.blockStateConversions.size > conversions }) {
                fixture.scheduler.executeImmediate()
                fixture.scheduler.tick()
            }
            fixture.active shouldBe previous

            fixture.renderer.clearPlan(fixture.player.uniqueId)
            val sends = fixture.sent.size
            fixture.drain()
            fixture.active shouldBe emptyMap()
            fixture.sent.size shouldBe sends
        }
    }
})

private class MovingPreviewFixture(
    val world: World,
    val otherWorld: World,
    val player: Player,
    val scheduler: TestTaskScheduler,
    val renderer: BuilderBlockDisplayRenderer,
    val sent: MutableList<Pair<List<Int>, List<BuilderPacketDisplay>>>,
    val active: Map<Int, BuilderPacketDisplay>,
    val blockStateConversions: List<BlockData>,
) {
    fun drain() {
        // Worker completions and the shared native queue deliberately span scheduler ticks.
        advanceUntil({ scheduler.pendingCount() == 0 }) {
            scheduler.executeImmediate()
            if (scheduler.pendingCount() > 0) scheduler.tick()
        }
    }

    fun advanceUntil(done: () -> Boolean, step: () -> Unit) {
        repeat(256) {
            if (done()) return
            step()
        }
        check(done()) { "Preview did not settle within 256 scheduler steps" }
    }

    fun visibleBlocks() = active.values.filter {
        it.scaleX == 1f && it.scaleY == 1f && it.scaleZ == 1f
    }.map { it.x.toInt() }.sorted()

    fun open(book: Boolean, afterBlockData: String = "minecraft:stone", changeCount: Int = 64) {
        if (!book) {
            renderer.plan(
                player,
                BuilderPlan(
                    UUID.randomUUID(), player.uniqueId, BuilderPlanKind.FILL,
                    (0 until changeCount).map {
                        BuilderBlockChange(BuilderBlockPos(world.uid, it, 64, 0), "minecraft:air", afterBlockData)
                    },
                    emptyList(), emptyList(), createdAtMillis = 0, expiresAtMillis = Long.MAX_VALUE,
                ),
            )
            return
        }
        val site = mockk<ConstructionSite>()
        val stone = mockk<BaseBlock>()
        every { BukkitAdapter.adapt(stone) } returns Material.STONE.createBlockData()
        every { site.player } returns player
        every { site.world } returns world
        every { site.bookData } returns BuildBookData("preview.schem", "Preview")
        every { site.centerBlock } returns Location(world, 0.0, 64.0, 0.0)
        every { site.fullRotation } returns 0
        every { site.relativePositionsBottomUp() } answers { (0..63).asSequence().map { BlockVector3.at(it, 0, 0) } }
        every { site.sourceBlock(any()) } returns Material.STONE.createBlockData().asString
        every { site.worldLocation(any()) } answers {
            Location(world, firstArg<BlockVector3>().x().toDouble(), 64.0, 0.0)
        }
        renderer.open(site)
    }
}

private fun withMovingPreview(previewLimit: Int = 32, test: (MovingPreviewFixture) -> Unit) {
    failOnUnsupportedMockBukkitOperation {
        MockBukkitTestRuntime.open().use { paper ->
            mockkStatic(BukkitAdapter::class)
            try {
                val plugin = paper.createSimplePlugin("MovingPreviewTest")
                installArcVisualPacketBudgetFixture(plugin)
                val world = paper.addSimpleWorld("moving-preview")
                val otherWorld = paper.addSimpleWorld("outside-preview")
                val player = paper.addPlayer("PreviewOwner")
                player.teleport(Location(world, 16.49, 64.0, 0.0))
                val sent = mutableListOf<Pair<List<Int>, List<BuilderPacketDisplay>>>()
                val active = linkedMapOf<Int, BuilderPacketDisplay>()
                val conversions = mutableListOf<BlockData>()
                val connection = object : BuilderPreviewConnection {
                    override val identity = Any()
                    override fun submit(desired: List<BuilderPacketDisplay>, forceResetIds: Set<Int>) {
                        val byId = desired.associateBy(BuilderPacketDisplay::entityId)
                        val removed = ((active.keys - byId.keys) + forceResetIds).distinct()
                        val added = byId.values.filter { it.entityId !in active || it.entityId in forceResetIds }
                        sent += removed to added
                        removed.forEach(active::remove)
                        added.forEach { active[it.entityId] = it }
                    }
                }
                val packets = object : BuilderPreviewPacketTransport {
                    var nextId = 100
                    override fun blockStateId(blockData: BlockData): Int {
                        conversions += blockData
                        return 1
                    }
                    override fun nextEntityId() = nextId++
                    override fun connection(player: Player) = connection
                    override fun targetConnection(player: Player): BuilderPreviewConnection? = null
                }
                val messages = mockk<LocalizedMiniMessage>(relaxed = true)
                every { messages.render(any(), any(), any()) } returns Component.empty()
                val scheduler = TestTaskScheduler()
                // Cell outlines straddle x/z = 0: their negative edges belong to the neighbouring chunks.
                val visibleChunks = buildSet {
                    for (x in -1..128) for (z in -1..0) add((x.toLong() and 0xffffffffL) or (z.toLong() shl 32))
                }
                LifecycleTaskScope(scheduler = scheduler).use { tasks ->
                    BuilderBlockDisplayRenderer(
                        plugin, previewLimit, 1f, false, 64.0, 20L, messages, tasks,
                        packets = packets, sentChunks = { visibleChunks },
                        blockDataRotation = BuilderBlockDataRotation { data, _ -> data },
                    ).use { renderer ->
                        test(MovingPreviewFixture(world, otherWorld, player, scheduler, renderer, sent, active, conversions))
                    }
                }
            } finally {
                unmockkStatic(BukkitAdapter::class)
            }
        }
    }
}
