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
                val sent = mutableListOf<Pair<List<Int>, List<BuilderPacketDisplay>>>()
                val connection = object : BuilderPreviewConnection {
                    override val identity = Any()
                    override fun send(removed: List<Int>, added: List<BuilderPacketDisplay>) {
                        sent += removed to added
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
                        renderer.selection(player, points, selection)
                        sent.single().second.size shouldBe 14
                        val ids = sent.single().second.map(BuilderPacketDisplay::entityId)
                        renderer.selection(player, points, selection)
                        sent.size shouldBe 1
                        world.entities.filterIsInstance<BlockDisplay>().size shouldBe 0
                        renderer.clearPlayer(player.uniqueId)
                        sent.last().first.toSet() shouldBe ids.toSet()
                        sent.last().second shouldBe emptyList()
                    }
                }
                sent.size shouldBe 2
            }
        }
    }
})
