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
    for (book in listOf(false, true)) {
        test("${if (book) "book" else "plan"} window follows sub-block movement within two ticks without resending retained blocks") {
            withMovingPreview { fixture ->
                fixture.open(book)
                fixture.drain()
                fixture.visibleBlocks() shouldBe (0..31).toList()
                val first = fixture.active.toMap()
                val sends = fixture.sent.size

                fixture.player.teleport(Location(fixture.world, 16.50, 64.0, 0.0))
                fixture.scheduler.tick(2)
                fixture.drain()
                fixture.sent.size shouldBe sends // Equal-distance tie keeps the existing window.

                fixture.player.teleport(Location(fixture.world, 16.51, 64.0, 0.0))
                fixture.scheduler.tick(2)
                fixture.drain()
                fixture.visibleBlocks() shouldBe (1..32).toList()
                fixture.sent.last().first.size shouldBe 1
                fixture.sent.last().second.size shouldBe 1
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
})

private class MovingPreviewFixture(
    val world: World,
    val otherWorld: World,
    val player: Player,
    val scheduler: TestTaskScheduler,
    val renderer: BuilderBlockDisplayRenderer,
    val sent: MutableList<Pair<List<Int>, List<BuilderPacketDisplay>>>,
    val active: Map<Int, BuilderPacketDisplay>,
) {
    fun drain() {
        scheduler.executeImmediate()
        scheduler.executeImmediate()
    }

    fun visibleBlocks() = active.values.filter {
        it.scaleX == 1f && it.scaleY == 1f && it.scaleZ == 1f
    }.map { it.x.toInt() }.sorted()

    fun open(book: Boolean, afterBlockData: String = "minecraft:stone") {
        if (!book) {
            renderer.plan(
                player,
                BuilderPlan(
                    UUID.randomUUID(), player.uniqueId, BuilderPlanKind.FILL,
                    (0..63).map {
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
        every { site.sourceBlock(any()) } returns stone
        every { site.worldLocation(any()) } answers {
            Location(world, firstArg<BlockVector3>().x().toDouble(), 64.0, 0.0)
        }
        renderer.open(site)
    }
}

private fun withMovingPreview(test: (MovingPreviewFixture) -> Unit) {
    failOnUnsupportedMockBukkitOperation {
        MockBukkitTestRuntime.open().use { paper ->
            mockkStatic(BukkitAdapter::class)
            try {
                val plugin = paper.createSimplePlugin("MovingPreviewTest")
                val world = paper.addSimpleWorld("moving-preview")
                val otherWorld = paper.addSimpleWorld("outside-preview")
                val player = paper.addPlayer("PreviewOwner")
                player.teleport(Location(world, 16.49, 64.0, 0.0))
                val sent = mutableListOf<Pair<List<Int>, List<BuilderPacketDisplay>>>()
                val active = linkedMapOf<Int, BuilderPacketDisplay>()
                val connection = object : BuilderPreviewConnection {
                    override val identity = Any()
                    override fun send(removed: List<Int>, added: List<BuilderPacketDisplay>) {
                        sent += removed to added
                        removed.forEach(active::remove)
                        added.forEach { active[it.entityId] = it }
                    }
                }
                val packets = object : BuilderPreviewPacketTransport {
                    var nextId = 100
                    override fun blockStateId(blockData: BlockData) = 1
                    override fun nextEntityId() = nextId++
                    override fun connection(player: Player) = connection
                }
                val messages = mockk<LocalizedMiniMessage>(relaxed = true)
                every { messages.render(any(), any(), any()) } returns Component.empty()
                val scheduler = TestTaskScheduler()
                LifecycleTaskScope(scheduler = scheduler).use { tasks ->
                    BuilderBlockDisplayRenderer(
                        plugin, 32, 1f, false, 64.0, 20L, messages, tasks,
                        packets = packets, sentChunks = { (0L..4L).toSet() },
                    ).use { renderer ->
                        test(MovingPreviewFixture(world, otherWorld, player, scheduler, renderer, sent, active))
                    }
                }
            } finally {
                unmockkStatic(BukkitAdapter::class)
            }
        }
    }
}
