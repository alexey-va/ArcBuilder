package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.bukkit.block.data.BlockData
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Player
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation

class BuilderPacketRendererTest : FunSpec({
    test("selection opens updates and closes without registering any world entities") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.createSimplePlugin("PacketPreviewTest")
                val world = paper.addSimpleWorld("packet-preview")
                val player = paper.addPlayer("PreviewOwner")
                player.teleport(world.getBlockAt(4, 64, 4).location)
                val sent = mutableListOf<List<BuilderPacketDisplay>>()
                val connection = object : BuilderPreviewConnection {
                    override val identity = Any()
                    override fun submit(desired: List<BuilderPacketDisplay>, forceResetIds: Set<Int>) {
                        sent += desired.toList()
                    }
                }
                val packets = object : BuilderPreviewPacketTransport {
                    var nextId = 100
                    override fun blockStateId(blockData: BlockData) = 1
                    override fun nextEntityId() = nextId++
                    override fun connection(player: Player) = connection
                }
                LifecycleTaskScope(scheduler = TestTaskScheduler()).use { tasks ->
                    BuilderBlockDisplayRenderer(
                        plugin, 32, 1f, false, 64.0, 20L, mockk(relaxed = true), tasks,
                        packets = packets, sentChunks = { setOf(0L) },
                    ).use { renderer ->
                        val first = BuilderBlockPos(world.uid, 4, 64, 4)
                        val second = BuilderBlockPos(world.uid, 8, 68, 8)
                        val points = BuilderSelectionPoints(first, second)
                        val selection = BuilderSelection(first, second)
                        renderer.suppressSelectionGlow(player.uniqueId, true)
                        renderer.selection(player, points, selection)
                        sent.single().size shouldBe 14
                        sent.single().all { !it.glowing } shouldBe true
                        val entityIds = sent.single().map(BuilderPacketDisplay::entityId)
                        renderer.selection(player, points, selection)
                        sent.size shouldBe 1
                        renderer.suppressSelectionGlow(player.uniqueId, false)
                        sent.size shouldBe 2
                        sent.last().map(BuilderPacketDisplay::entityId) shouldBe entityIds
                        sent.last().all(BuilderPacketDisplay::glowing) shouldBe true
                        sent.last().map { it.copy(glowing = false) } shouldBe sent.first()
                        renderer.suppressSelectionGlow(player.uniqueId, true)
                        sent.last().map(BuilderPacketDisplay::entityId) shouldBe entityIds
                        sent.last().all { !it.glowing } shouldBe true
                        sent.last() shouldBe sent.first()
                        world.entities.filterIsInstance<BlockDisplay>().size shouldBe 0
                        renderer.clearSelection(player.uniqueId)
                        sent.last() shouldBe emptyList()
                        renderer.selection(player, points, selection)
                        sent.last().all { !it.glowing } shouldBe true
                        renderer.clearPlayer(player.uniqueId)
                        sent.last() shouldBe emptyList()
                        renderer.selection(player, points, selection)
                        sent.last().all(BuilderPacketDisplay::glowing) shouldBe true
                        renderer.clearPlayer(player.uniqueId)
                        sent.last() shouldBe emptyList()
                    }
                }
                sent.size shouldBe 8
            }
        }
    }
})
