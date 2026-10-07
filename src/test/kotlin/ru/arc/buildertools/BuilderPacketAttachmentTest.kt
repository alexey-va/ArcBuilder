package ru.arc.buildertools

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.PacketEventsAPI
import com.github.retrooper.packetevents.injector.ChannelInjector
import com.github.retrooper.packetevents.manager.player.PlayerManager
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager
import com.github.retrooper.packetevents.manager.server.ServerManager
import com.github.retrooper.packetevents.manager.server.ServerVersion
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.github.retrooper.packetevents.impl.netty.NettyManagerImpl
import ru.arc.paper.api.VisualPacketAdmission
import java.util.UUID
import java.util.logging.Logger

class BuilderPacketAttachmentTest : FunSpec({
    val previousPacketEventsApi = PacketEvents.getAPI()
    beforeSpec { PacketEvents.setAPI(TestPacketEventsApi()) }
    afterSpec { PacketEvents.setAPI(previousPacketEventsApi) }

    test("a retry uses only the latest desired scene") {
        val channel = FakePreviewChannel()
        val gateway = RecordingPreviewGateway()
        val attachment = BuilderPacketAttachment(channel, gateway, Logger.getAnonymousLogger())
        val oldVisible = display(1)
        val oldDeferred = display(2)
        val newest = display(3)
        gateway.displayAdmissions += listOf(
            VisualPacketAdmission.ALLOWED,
            VisualPacketAdmission.SERVER_RATE,
            VisualPacketAdmission.ALLOWED,
        )

        attachment.submit(listOf(oldVisible, oldDeferred))
        gateway.runImmediate()
        attachment.submit(listOf(newest))
        gateway.runRetry()

        gateway.displayAttempts shouldBe listOf(1, 2, 3)
        gateway.destroyAttempts shouldBe listOf(listOf(1))
        gateway.visibleIds shouldBe setOf(3)
    }

    test("a partial budget lets later entities progress and retries every deferred entity") {
        val channel = FakePreviewChannel()
        val gateway = RecordingPreviewGateway()
        val attachment = BuilderPacketAttachment(channel, gateway, Logger.getAnonymousLogger())
        gateway.displayAdmissions += listOf(
            VisualPacketAdmission.VIEWER_RATE,
            VisualPacketAdmission.ALLOWED,
            VisualPacketAdmission.SERVER_RATE,
            VisualPacketAdmission.ALLOWED,
            VisualPacketAdmission.ALLOWED,
        )

        attachment.submit(listOf(display(10), display(11), display(12)))
        gateway.runImmediate()
        gateway.runRetry()

        gateway.displayAttempts shouldBe listOf(10, 11, 12, 10, 12)
        gateway.visibleIds shouldBe setOf(10, 11, 12)
    }

    test("cleanup is separate and retries after the owner closes") {
        val channel = FakePreviewChannel()
        val gateway = RecordingPreviewGateway()
        val attachment = BuilderPacketAttachment(channel, gateway, Logger.getAnonymousLogger())
        attachment.submit(listOf(display(20)))
        gateway.runImmediate()
        gateway.visibleIds shouldBe setOf(20)

        gateway.destroyAdmissions += listOf(
            VisualPacketAdmission.CHANNEL_BACKPRESSURE,
            VisualPacketAdmission.ALLOWED,
        )
        attachment.close()
        gateway.runImmediate()
        gateway.visibleIds shouldBe setOf(20)
        gateway.runRetry()

        gateway.destroyAttempts shouldBe listOf(listOf(20), listOf(20))
        gateway.visibleIds shouldBe emptySet()
        attachment.retainedEntityCount() shouldBe 0
    }

    test("a chunk reload forces replay even when unload was coalesced away") {
        val channel = FakePreviewChannel()
        val gateway = RecordingPreviewGateway()
        val attachment = BuilderPacketAttachment(channel, gateway, Logger.getAnonymousLogger())
        val display = display(25)
        attachment.submit(listOf(display))
        gateway.runImmediate()

        attachment.submit(emptyList(), forceResetIds = setOf(display.entityId))
        attachment.submit(listOf(display))
        gateway.runImmediate()

        gateway.destroyAttempts shouldBe listOf(listOf(25))
        gateway.displayAttempts shouldBe listOf(25, 25)
        gateway.visibleIds shouldBe setOf(25)
    }

    test("glow-only changes update the existing entity without respawn") {
        val channel = FakePreviewChannel()
        val gateway = RecordingPreviewGateway()
        val attachment = BuilderPacketAttachment(channel, gateway, Logger.getAnonymousLogger())
        val visible = display(26)
        val suppressed = visible.copy(glowing = false)

        attachment.submit(listOf(visible))
        gateway.runImmediate()
        attachment.submit(listOf(suppressed))
        gateway.runImmediate()
        attachment.submit(listOf(visible))
        gateway.runImmediate()
        attachment.submit(listOf(visible))
        gateway.runImmediate()

        gateway.displayAttempts shouldBe listOf(26)
        gateway.glowAttempts shouldBe listOf(suppressed, visible)
        gateway.destroyAttempts shouldBe emptyList()
        gateway.glowStates[26] shouldBe true
    }

    test("a denied glow metadata update retries without replacing the entity") {
        val channel = FakePreviewChannel()
        val gateway = RecordingPreviewGateway()
        val attachment = BuilderPacketAttachment(channel, gateway, Logger.getAnonymousLogger())
        val visible = display(27)
        val suppressed = visible.copy(glowing = false)
        gateway.glowAdmissions += listOf(VisualPacketAdmission.SERVER_RATE, VisualPacketAdmission.ALLOWED)

        attachment.submit(listOf(visible))
        gateway.runImmediate()
        attachment.submit(listOf(suppressed))
        gateway.runImmediate()
        gateway.runRetry()

        gateway.displayAttempts shouldBe listOf(27)
        gateway.glowAttempts shouldBe listOf(suppressed, suppressed)
        gateway.destroyAttempts shouldBe emptyList()
        gateway.glowStates[27] shouldBe false
    }

    test("initial suppression and metadata toggles touch only the entity glow flag") {
        val suppressed = display(28).copy(glowing = false)
        val initial = builderDisplayPacketTransaction(suppressed)
        val initialMetadata = initial.last() as WrapperPlayServerEntityMetadata
        val off = builderDisplayGlowTransaction(suppressed)
        val on = builderDisplayGlowTransaction(suppressed.copy(glowing = true))

        initial.size shouldBe 2
        initialMetadata.metadataValue(0) shouldBe 0.toByte()
        initialMetadata.metadataValue(22) shouldBe suppressed.glowRgb
        off.size shouldBe 1
        val offMetadata = off.single() as WrapperPlayServerEntityMetadata
        offMetadata.entityId shouldBe suppressed.entityId
        offMetadata.entityMetadata.map { it.index } shouldBe listOf(0)
        offMetadata.metadataValue(0) shouldBe 0.toByte()
        val onMetadata = on.single() as WrapperPlayServerEntityMetadata
        onMetadata.entityId shouldBe suppressed.entityId
        onMetadata.metadataValue(0) shouldBe 0x40.toByte()
    }

    test("a disconnected channel drops its captured per-viewer state") {
        val channel = FakePreviewChannel()
        val gateway = RecordingPreviewGateway()
        val attachment = BuilderPacketAttachment(channel, gateway, Logger.getAnonymousLogger())
        attachment.submit(listOf(display(30)))
        gateway.runImmediate()
        attachment.retainedEntityCount() shouldBe 1

        channel.open = false
        attachment.close()

        attachment.retainedEntityCount() shouldBe 0
        attachment.accepts(channel) shouldBe false
        gateway.retryTasks.size shouldBe 0
    }
})

private fun display(id: Int) = BuilderPacketDisplay(
    entityId = id,
    uuid = UUID.nameUUIDFromBytes("preview-$id".toByteArray()),
    x = id.toDouble(), y = 64.0, z = 0.0,
    blockStateId = 1,
    scaleX = 1f, scaleY = 1f, scaleZ = 1f,
    translateX = 0f, translateY = 0f, translateZ = 0f,
    glowRgb = 0xFFFFFF, viewRange = 1f,
)

private class FakePreviewChannel(var open: Boolean = true)

private class RecordingPreviewGateway : BuilderPreviewPacketGateway {
    private val immediateTasks = mutableListOf<() -> Unit>()
    val retryTasks = mutableListOf<() -> Unit>()
    val displayAttempts = mutableListOf<Int>()
    val destroyAttempts = mutableListOf<List<Int>>()
    val glowAttempts = mutableListOf<BuilderPacketDisplay>()
    val visibleIds = linkedSetOf<Int>()
    val displayAdmissions = mutableListOf<VisualPacketAdmission>()
    val destroyAdmissions = mutableListOf<VisualPacketAdmission>()
    val glowAdmissions = mutableListOf<VisualPacketAdmission>()
    val glowStates = mutableMapOf<Int, Boolean>()

    override fun isOpen(channel: Any) = (channel as FakePreviewChannel).open
    override fun execute(channel: Any, task: () -> Unit) { immediateTasks += task }
    override fun retry(channel: Any, task: () -> Unit) { retryTasks += task }

    override fun display(channel: Any, display: BuilderPacketDisplay): VisualPacketAdmission {
        displayAttempts += display.entityId
        val admission = displayAdmissions.removeFirstOrAllowed()
        if (admission == VisualPacketAdmission.ALLOWED) visibleIds += display.entityId
        return admission
    }

    override fun glow(channel: Any, display: BuilderPacketDisplay): VisualPacketAdmission {
        glowAttempts += display
        val admission = glowAdmissions.removeFirstOrAllowed()
        if (admission == VisualPacketAdmission.ALLOWED) glowStates[display.entityId] = display.glowing
        return admission
    }

    override fun destroy(channel: Any, entityIds: List<Int>): VisualPacketAdmission {
        destroyAttempts += entityIds.toList()
        val admission = destroyAdmissions.removeFirstOrAllowed()
        if (admission == VisualPacketAdmission.ALLOWED) visibleIds.removeAll(entityIds.toSet())
        return admission
    }

    override fun flush(channel: Any) = Unit

    fun runImmediate() = immediateTasks.removeAt(0).invoke()
    fun runRetry() = retryTasks.removeAt(0).invoke()

    private fun MutableList<VisualPacketAdmission>.removeFirstOrAllowed() =
        if (isEmpty()) VisualPacketAdmission.ALLOWED else removeAt(0)
}

private fun WrapperPlayServerEntityMetadata.metadataValue(index: Int): Any? =
    entityMetadata.single { it.index == index }.value

/** PacketEvents entity metadata wrappers need a protocol version, but these tests use no live connection. */
private class TestPacketEventsApi : PacketEventsAPI<Any>() {
    private val server = ServerManager { ServerVersion.V_1_21_11 }
    private val netty = NettyManagerImpl()

    override fun getServerManager() = server
    override fun getNettyManager() = netty
    override fun getPlugin(): Any = this
    override fun getProtocolManager(): ProtocolManager = error("No live protocol manager in packet unit tests")
    override fun getPlayerManager(): PlayerManager = error("No Bukkit players in packet unit tests")
    override fun getInjector(): ChannelInjector = error("No channel injection in packet unit tests")
    override fun load() = Unit
    override fun init() = Unit
    override fun terminate() = Unit
    override fun isLoaded() = true
    override fun isInitialized() = true
    override fun isTerminated() = false
}
