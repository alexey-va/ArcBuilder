package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.PacketEventsAPI
import io.papermc.paper.event.packet.PlayerChunkLoadEvent
import io.papermc.paper.event.packet.PlayerChunkUnloadEvent
import org.bukkit.Color
import org.bukkit.block.data.BlockData
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.arc.paper.api.VisualPacketAdmission
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger

class BuilderPacketRendererTest : FunSpec({
    test("selection opens updates and closes without registering any world entities") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.createSimplePlugin("PacketPreviewTest")
                installArcVisualPacketBudgetFixture(plugin)
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
                    override fun targetConnection(player: Player): BuilderPreviewConnection? = null
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

    test("independent cursor and preview attachments coexist on one channel without resending static geometry") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.createSimplePlugin("TargetPreviewTest")
                installArcVisualPacketBudgetFixture(plugin)
                val world = paper.addSimpleWorld("target-preview")
                val player = paper.addPlayer("TargetOwner")
                val observer = paper.addPlayer("NearbyObserver")
                player.teleport(world.getBlockAt(4, 64, 4).location)
                observer.teleport(player.location)
                val gateway = SelectionTargetGateway()
                val channel = Any()
                val attachment = BuilderPacketAttachment(channel, gateway, Logger.getAnonymousLogger())
                val targetAttachment = BuilderPacketAttachment(channel, gateway, Logger.getAnonymousLogger(), compactFrames = true)
                var staticSubmissions = 0
                val targetBatchSizes = mutableListOf<Int>()
                val capturedViewers = mutableSetOf<UUID>()
                val packets = object : BuilderPreviewPacketTransport {
                    var nextId = 100
                    override fun blockStateId(blockData: BlockData) = 1
                    override fun nextEntityId() = nextId++
                    override fun connection(player: Player): BuilderPreviewConnection {
                        capturedViewers += player.uniqueId
                        return object : BuilderPreviewConnection {
                            override val identity = attachment.identity
                            override fun submit(desired: List<BuilderPacketDisplay>, forceResetIds: Set<Int>) {
                                staticSubmissions++
                                attachment.submit(desired, forceResetIds)
                            }
                        }
                    }
                    override fun targetConnection(player: Player): BuilderPreviewConnection {
                        capturedViewers += player.uniqueId
                        return object : BuilderPreviewConnection {
                            override val identity = targetAttachment.identity
                            override fun isAdmitted(desired: List<BuilderPacketDisplay>) = targetAttachment.isAdmitted(desired)
                            override fun submit(desired: List<BuilderPacketDisplay>, forceResetIds: Set<Int>) {
                                targetBatchSizes += desired.size
                                targetAttachment.submit(desired, forceResetIds)
                            }
                        }
                    }
                    override fun closeAsync(): CompletableFuture<Void> =
                        CompletableFuture.allOf(attachment.closeAsync(), targetAttachment.closeAsync())
                }
                val scheduler = TestTaskScheduler()
                LifecycleTaskScope(scheduler = scheduler).use { tasks ->
                    BuilderBlockDisplayRenderer(
                        plugin, 32, 1f, false, 64.0, 20L, mockk(relaxed = true), tasks,
                        packets = packets, sentChunks = { setOf(0L) },
                    ).use { renderer ->
                        val first = BuilderBlockPos(world.uid, 4, 64, 4)
                        val second = BuilderBlockPos(world.uid, 8, 68, 8)
                        renderer.selection(player, BuilderSelectionPoints(first, second), BuilderSelection(first, second))
                        gateway.drain()
                        val savedIds = gateway.visible.keys.toSet()
                        savedIds.size shouldBe 14
                        staticSubmissions shouldBe 1

                        renderer.selectionTarget(player, first)
                        gateway.drain()
                        gateway.visible.size shouldBe 26
                        gateway.visible.keys.containsAll(savedIds) shouldBe true
                        staticSubmissions shouldBe 1
                        val cursor = gateway.visible.values.filter { it.entityId !in savedIds }
                        cursor.size shouldBe 12
                        cursor.all { it.glowRgb == Color.WHITE.asRGB() && it.glowing } shouldBe true
                        cursor.count { it.scaleX > .9f || it.scaleY > .9f || it.scaleZ > .9f } shouldBe 12
                        val unchangedIds = gateway.visible.keys.toSet()
                        val calls = gateway.displayCalls
                        renderer.selectionTarget(player, first)
                        gateway.drain()
                        gateway.visible.keys shouldBe unchangedIds
                        gateway.displayCalls shouldBe calls

                        renderer.placementAssist(player, first.copy(x = 7), false)
                        renderer.placementAssistReady(player, first.copy(x = 7), false) shouldBe false
                        gateway.drain()
                        renderer.placementAssistReady(player, first.copy(x = 7), false) shouldBe true
                        renderer.placementAssistReady(player, first.copy(x = 7), true) shouldBe false
                        renderer.placementAssistReady(player, first.copy(x = 8), false) shouldBe false
                        gateway.visible.size shouldBe 38
                        gateway.visible.keys.containsAll(unchangedIds) shouldBe true
                        val assistIds = gateway.visible.keys - unchangedIds
                        assistIds.size shouldBe 12
                        assistIds.all { gateway.visible.getValue(it).glowRgb == BuilderPlacementAssistAppearance.forState(false).glowRgb } shouldBe true
                        val withAssistCalls = gateway.displayCalls
                        renderer.placementAssist(player, first.copy(x = 7), false)
                        gateway.drain()
                        gateway.displayCalls shouldBe withAssistCalls
                        renderer.placementAssist(player, first.copy(x = 7), true)
                        gateway.drain()
                        gateway.visible.keys.containsAll(unchangedIds) shouldBe true
                        val blockedAssistIds = gateway.visible.keys - unchangedIds
                        blockedAssistIds shouldBe assistIds
                        blockedAssistIds.all { gateway.visible.getValue(it).glowRgb == BuilderPlacementAssistAppearance.forState(true).glowRgb } shouldBe true
                        gateway.displayCalls shouldBe withAssistCalls
                        val blockedPose = assistIds.associateWith { gateway.visible.getValue(it) }
                        gateway.frameAdmissions += VisualPacketAdmission.SERVER_RATE
                        renderer.placementAssist(player, first.copy(x = 8), true)
                        renderer.placementAssistReady(player, first.copy(x = 8), true) shouldBe false
                        gateway.step()
                        renderer.placementAssistReady(player, first.copy(x = 8), true) shouldBe false
                        assistIds.forEach { id -> gateway.visible.getValue(id) shouldBe blockedPose.getValue(id) }
                        gateway.drain()
                        renderer.placementAssistReady(player, first.copy(x = 8), true) shouldBe true
                        assistIds.forEach { id ->
                            gateway.visible.getValue(id) shouldBe blockedPose.getValue(id).copy(x = blockedPose.getValue(id).x + 1.0)
                        }
                        gateway.displayCalls shouldBe withAssistCalls
                        renderer.clearPlacementAssist(player.uniqueId)
                        renderer.placementAssistReady(player, first.copy(x = 8), true) shouldBe false
                        gateway.drain()
                        gateway.visible.keys shouldBe unchangedIds
                        staticSubmissions shouldBe 1

                        renderer.selectionTarget(player, first.copy(x = 5))
                        gateway.drain()
                        gateway.visible.keys shouldBe unchangedIds
                        cursor.forEach { old -> gateway.visible.getValue(old.entityId) shouldBe old.copy(x = old.x + 1.0) }
                        gateway.displayCalls shouldBe withAssistCalls
                        gateway.visible.keys.containsAll(savedIds) shouldBe true
                        staticSubmissions shouldBe 1
                        renderer.suppressSelectionGlow(player.uniqueId, true)
                        gateway.drain()
                        savedIds.all { !gateway.visible.getValue(it).glowing } shouldBe true
                        gateway.visible.values.filter { it.entityId !in savedIds }.all { it.glowing } shouldBe true

                        // Coalesced move/clear still retires only cursor IDs.
                        renderer.selectionTarget(player, first.copy(x = 6))
                        renderer.clearSelectionTarget(player.uniqueId)
                        gateway.drain()
                        gateway.visible.keys shouldBe savedIds
                        staticSubmissions shouldBe 2 // Selection glow change only.
                        targetBatchSizes.all { it == 0 || it == 12 || it == 24 } shouldBe true
                        capturedViewers shouldBe setOf(player.uniqueId)
                        world.entities.filterIsInstance<BlockDisplay>().size shouldBe 0
                        renderer.selectionTarget(player, first)
                        gateway.drain()
                        val beforeReplay = gateway.visible.keys.toSet()
                        renderer.onChunkUnload(PlayerChunkUnloadEvent(world.getChunkAt(0, 0), player))
                        // Unload/reload can be coalesced before the channel drains.
                        renderer.onChunkLoad(PlayerChunkLoadEvent(world.getChunkAt(0, 0), player))
                        scheduler.tick()
                        gateway.drain()
                        gateway.visible.keys shouldBe beforeReplay

                        val otherWorld = paper.addSimpleWorld("target-other")
                        player.teleport(otherWorld.getBlockAt(4, 64, 4).location)
                        gateway.drain()
                        gateway.visible shouldBe emptyMap()
                        renderer.clearSelectionTarget(player.uniqueId)
                        player.teleport(world.getBlockAt(4, 64, 4).location)
                        scheduler.tick()
                        gateway.drain()
                        gateway.visible.keys shouldBe savedIds
                        renderer.selectionTarget(player, first)
                        renderer.clearPlayer(player.uniqueId)
                        gateway.drain()
                        gateway.visible shouldBe emptyMap()
                        renderer.selection(player, BuilderSelectionPoints(first, second), BuilderSelection(first, second))
                        renderer.selectionTarget(player, first)
                        gateway.drain()
                        gateway.visible.size shouldBe 26
                    }
                }
                gateway.drain()
                attachment.closeAsync()
                targetAttachment.closeAsync()
                gateway.drain()
                attachment.retainedEntityCount() shouldBe 0
                targetAttachment.retainedEntityCount() shouldBe 0
                gateway.visible shouldBe emptyMap()
            }
        }
    }

    test("transport caches independent cursor ownership and forget and close drain both streams") {
        mockkStatic(PacketEvents::class)
        try {
            val api = mockk<PacketEventsAPI<Any>>(relaxed = true)
            every { PacketEvents.getAPI() } returns api
            val player = mockk<Player>()
            val channel = Any()
            every { api.playerManager.getChannel(player) } returns channel
            val gateway = SelectionTargetGateway()
            val transport = PacketEventsBuilderPreviewTransport(mockk<Plugin>(), Logger.getAnonymousLogger(), gateway)
            fun record(id: Int) = BuilderPacketDisplay(id, UUID.randomUUID(), 1.0, 64.0, 1.0, 1,
                1f, 1f, 1f, 0f, 0f, 0f, Color.WHITE.asRGB(), 1f)
            val preview = checkNotNull(transport.connection(player))
            val cursor = checkNotNull(transport.targetConnection(player))
            (preview.identity === cursor.identity) shouldBe false
            (transport.connection(player)?.identity === preview.identity) shouldBe true
            (transport.targetConnection(player)?.identity === cursor.identity) shouldBe true
            preview.submit(listOf(record(1)))
            cursor.submit(listOf(record(2)))
            gateway.drain()
            gateway.visible.keys shouldBe setOf(1, 2)
            transport.forget(player)
            gateway.drain()
            gateway.visible shouldBe emptyMap()

            val freshPreview = checkNotNull(transport.connection(player))
            val freshCursor = checkNotNull(transport.targetConnection(player))
            (freshPreview.identity === preview.identity) shouldBe false
            (freshCursor.identity === cursor.identity) shouldBe false
            freshPreview.submit(listOf(record(3)))
            freshCursor.submit(listOf(record(4)))
            gateway.drain()
            gateway.visible.keys shouldBe setOf(3, 4)
            val drained = transport.closeAsync()
            drained.isDone shouldBe false
            gateway.drain()
            drained.join()
            gateway.visible shouldBe emptyMap()
            transport.connection(player) shouldBe null
            transport.targetConnection(player) shouldBe null
        } finally {
            unmockkStatic(PacketEvents::class)
        }
    }
})

private class SelectionTargetGateway : BuilderPreviewPacketGateway {
    val visible = linkedMapOf<Int, BuilderPacketDisplay>()
    val frameAdmissions = ArrayDeque<VisualPacketAdmission>()
    var displayCalls = 0
        private set
    private val tasks = ArrayDeque<() -> Unit>()
    override fun isOpen(channel: Any) = true
    override fun execute(channel: Any, task: () -> Unit) { tasks += task }
    override fun retry(channel: Any, task: () -> Unit) { tasks += task }
    override fun display(channel: Any, display: BuilderPacketDisplay): VisualPacketAdmission {
        displayCalls++
        visible[display.entityId] = display
        return VisualPacketAdmission.ALLOWED
    }
    override fun glow(channel: Any, display: BuilderPacketDisplay): VisualPacketAdmission {
        visible[display.entityId] = display
        return VisualPacketAdmission.ALLOWED
    }
    override fun update(channel: Any, previous: BuilderPacketDisplay, display: BuilderPacketDisplay): VisualPacketAdmission {
        visible[display.entityId] = display
        return VisualPacketAdmission.ALLOWED
    }
    override fun frame(channel: Any, previous: List<BuilderPacketDisplay>, desired: List<BuilderPacketDisplay>, forceResetIds: Set<Int>): VisualPacketAdmission {
        val admission = frameAdmissions.removeFirstOrNull() ?: VisualPacketAdmission.ALLOWED
        if (admission != VisualPacketAdmission.ALLOWED) return admission
        val oldIds = previous.mapTo(mutableSetOf(), BuilderPacketDisplay::entityId)
        val desiredIds = desired.mapTo(mutableSetOf(), BuilderPacketDisplay::entityId)
        (oldIds - desiredIds).forEach(visible::remove)
        desired.forEach { display ->
            if (display.entityId !in oldIds || display.entityId in forceResetIds) displayCalls++
            visible[display.entityId] = display
        }
        return VisualPacketAdmission.ALLOWED
    }
    override fun destroy(channel: Any, entityIds: List<Int>): VisualPacketAdmission {
        entityIds.forEach(visible::remove)
        return VisualPacketAdmission.ALLOWED
    }
    override fun flush(channel: Any) = Unit
    fun step() { tasks.removeFirst().invoke() }
    fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().invoke() }
}
