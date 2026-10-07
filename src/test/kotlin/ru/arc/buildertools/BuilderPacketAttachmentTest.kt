package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.paper.api.VisualPacketAdmission
import java.util.UUID
import java.util.logging.Logger

class BuilderPacketAttachmentTest : FunSpec({
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
    val visibleIds = linkedSetOf<Int>()
    val displayAdmissions = mutableListOf<VisualPacketAdmission>()
    val destroyAdmissions = mutableListOf<VisualPacketAdmission>()

    override fun isOpen(channel: Any) = (channel as FakePreviewChannel).open
    override fun execute(channel: Any, task: () -> Unit) { immediateTasks += task }
    override fun retry(channel: Any, task: () -> Unit) { retryTasks += task }

    override fun display(channel: Any, display: BuilderPacketDisplay): VisualPacketAdmission {
        displayAttempts += display.entityId
        val admission = displayAdmissions.removeFirstOrAllowed()
        if (admission == VisualPacketAdmission.ALLOWED) visibleIds += display.entityId
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
