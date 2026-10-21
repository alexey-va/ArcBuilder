package ru.arc.buildertools

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import com.google.gson.GsonBuilder
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.PacketEventsAPI
import com.github.retrooper.packetevents.injector.ChannelInjector
import com.github.retrooper.packetevents.manager.player.PlayerManager
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager
import com.github.retrooper.packetevents.manager.server.ServerManager
import com.github.retrooper.packetevents.manager.server.ServerVersion
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag
import com.github.retrooper.packetevents.util.Vector3f
import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.github.retrooper.packetevents.impl.netty.NettyManagerImpl
import ru.arc.paper.api.VisualPacketAdmission
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

    test("one compact admission keeps all twelve old parts or publishes all twelve latest parts") {
        val original = compactFrame(4)
        val intermediate = compactFrame(5)
        val latest = compactFrame(6).map { it.copy(glowRgb = 0xFFAA00, blockStateId = 2) }

        // The unchanged progressive path reproduces the prior cursor's per-part budget defect.
        val progressiveGateway = EncodedFrameBudgetGateway()
        val progressive = BuilderPacketAttachment(progressiveGateway.channel, progressiveGateway, Logger.getAnonymousLogger())
        progressive.submit(original)
        progressiveGateway.runImmediate()
        progressive.isAdmitted(original) shouldBe false // The bulk path exposes no whole-frame receipt.
        progressiveGateway.remainingPackets = 6
        progressiveGateway.phase = "progressive-move-partial-budget"
        progressive.submit(intermediate)
        progressiveGateway.runImmediate()
        val originalParts = original.map(::RecordedNativePart).associateBy(RecordedNativePart::id)
        val intermediateParts = intermediate.map(::RecordedNativePart).associateBy(RecordedNativePart::id)
        progressiveGateway.visible.size shouldBe 12
        progressiveGateway.visible.values.count { it == originalParts[it.id] } shouldBe 6
        progressiveGateway.visible.values.count { it == intermediateParts[it.id] } shouldBe 6
        (progressiveGateway.visible.values.toList() == original.map(::RecordedNativePart)) shouldBe false
        (progressiveGateway.visible.values.toList() == intermediate.map(::RecordedNativePart)) shouldBe false

        val gateway = EncodedFrameBudgetGateway()
        val compact = BuilderPacketAttachment(gateway.channel, gateway, Logger.getAnonymousLogger(), compactFrames = true)
        compact.submit(original)
        compact.isAdmitted(original) shouldBe false
        gateway.runImmediate()
        gateway.visible.values.toList() shouldBe original.map(::RecordedNativePart)
        compact.isAdmitted(original) shouldBe true
        gateway.remainingPackets = 6
        gateway.phase = "compact-move-denied"
        compact.submit(intermediate)
        compact.isAdmitted(original) shouldBe false // Changed desired input immediately fences the old receipt.
        gateway.runImmediate()
        gateway.visible.values.toList() shouldBe original.map(::RecordedNativePart)
        val deniedCandidateAdmitted = compact.isAdmitted(intermediate)
        deniedCandidateAdmitted shouldBe false
        gateway.trace.last().getValue("admission") shouldBe "SERVER_RATE"

        gateway.phase = "compact-latest-retry"
        compact.submit(latest)
        gateway.remainingPackets = Int.MAX_VALUE
        gateway.runRetry()
        gateway.visible.values.toList() shouldBe latest.map(::RecordedNativePart)
        compact.isAdmitted(latest) shouldBe true
        compact.isAdmitted(intermediate) shouldBe false
        compact.isAdmitted(latest.mapIndexed { index, part -> if (index == 0) part.copy(glowRgb = 0) else part }) shouldBe false
        compact.isAdmitted(emptyList()) shouldBe false
        gateway.trace.last().getValue("admission") shouldBe "ALLOWED"
        gateway.trace.last().getValue("packetCount") shouldBe 24 // Twelve metadata + exact position updates.
        gateway.retryTasks shouldBe emptyList()
        gateway.visible.keys shouldBe original.mapTo(linkedSetOf(), BuilderPacketDisplay::entityId)

        File("build/reports/placement-assist-v3/compact-frame-budget-trace.json").apply {
            parentFile.mkdirs()
            writeText(GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
                "evidence" to "simulated-budget; actual production PE 2.12.1 packet wrappers decoded by test",
                "nativeClientObserved" to false,
                "atomicity" to "whole-list budget admission, not pixel or client-frame atomicity",
                "clock" to "synthetic admission step, timestampMs = step * 50; not measured runtime",
                "source" to "BuilderPreviewPacketTransport.kt: builderDisplayFramePacketTransaction / BuilderPacketAttachment",
                "wholeFrameAdmissionReceipt" to mapOf("deniedCandidateB" to deniedCandidateAdmitted,
                    "latestCandidateC" to compact.isAdmitted(latest), "staleCandidateB" to compact.isAdmitted(intermediate)),
                "progressivePerPartBaseline" to progressiveGateway.trace,
                "compactFrame" to gateway.trace,
            )))
        }
    }

    test("coexisting target and assist reset all twenty-four parts under one admission") {
        val original = compactFrame(4) + compactFrame(7, firstId = 200)
        val gateway = EncodedFrameBudgetGateway()
        val compact = BuilderPacketAttachment(gateway.channel, gateway, Logger.getAnonymousLogger(), compactFrames = true)
        compact.submit(original)
        gateway.runImmediate()
        gateway.visible.values.toList() shouldBe original.map(::RecordedNativePart)
        compact.isAdmitted(original) shouldBe true

        // Full reset is one destroy plus 48 spawn/metadata packets. A 48-packet allowance admits none.
        gateway.remainingPackets = 48
        compact.submit(original, original.mapTo(linkedSetOf(), BuilderPacketDisplay::entityId))
        compact.isAdmitted(original) shouldBe false
        gateway.runImmediate()
        gateway.trace.last().getValue("packetCount") shouldBe 49
        gateway.trace.last().getValue("admission") shouldBe "SERVER_RATE"
        gateway.visible.values.toList() shouldBe original.map(::RecordedNativePart)
        compact.isAdmitted(original) shouldBe false
        gateway.remainingPackets = Int.MAX_VALUE
        gateway.runRetry()
        gateway.visible.values.toList() shouldBe original.map(::RecordedNativePart)
        gateway.trace.last().getValue("admission") shouldBe "ALLOWED"
        gateway.visible.size shouldBe 24
        compact.isAdmitted(original) shouldBe true
    }

    test("compact close cleans up a refused frame and oversized groups cannot enter the compact stream") {
        val original = compactFrame(4)
        val gateway = EncodedFrameBudgetGateway()
        val compact = BuilderPacketAttachment(gateway.channel, gateway, Logger.getAnonymousLogger(), compactFrames = true)
        compact.submit(original)
        gateway.runImmediate()
        shouldThrow<IllegalArgumentException> { compact.submit((0..24).map { display(1000 + it) }) }
        gateway.remainingPackets = 0
        compact.submit(compactFrame(5))
        gateway.runImmediate()
        gateway.visible.values.toList() shouldBe original.map(::RecordedNativePart)
        val closed = compact.closeAsync()
        compact.isAdmitted(original) shouldBe false
        gateway.runRetry()
        gateway.visible shouldBe emptyMap()
        gateway.trace.last().getValue("cleanup") shouldBe true
        closed.isDone shouldBe true
        closed.isCompletedExceptionally shouldBe false
        compact.retainedEntityCount() shouldBe 0
    }

    test("compact receipt waits for flush and a newer desired frame fences an older flush completion") {
        val original = compactFrame(4)
        val newest = compactFrame(5)
        val gateway = EncodedFrameBudgetGateway()
        val compact = BuilderPacketAttachment(gateway.channel, gateway, Logger.getAnonymousLogger(), compactFrames = true)
        gateway.onFlush = {
            compact.isAdmitted(original) shouldBe false
            compact.submit(newest)
            gateway.onFlush = null
        }
        compact.submit(original)
        gateway.runImmediate()
        gateway.visible.values.toList() shouldBe original.map(::RecordedNativePart)
        compact.isAdmitted(original) shouldBe false
        compact.isAdmitted(newest) shouldBe false
        gateway.runImmediate()
        compact.isAdmitted(newest) shouldBe true
        compact.isAdmitted(original) shouldBe false
        // An idle repeat does not invalidate an already flushed exact pose.
        compact.submit(newest.toList())
        compact.isAdmitted(newest) shouldBe true
    }

    test("a flush failure revokes the compact receipt until uncertain cleanup and complete replay finish") {
        val original = compactFrame(4)
        val newest = compactFrame(5)
        val gateway = EncodedFrameBudgetGateway()
        val compact = BuilderPacketAttachment(gateway.channel, gateway, Logger.getAnonymousLogger(), compactFrames = true)
        compact.submit(original)
        gateway.runImmediate()
        compact.isAdmitted(original) shouldBe true
        gateway.failNextFlush = true
        compact.submit(newest)
        gateway.runImmediate()
        compact.isAdmitted(original) shouldBe false
        compact.isAdmitted(newest) shouldBe false
        gateway.runRetry()
        gateway.visible shouldBe emptyMap()
        compact.isAdmitted(newest) shouldBe false
        gateway.runRetry()
        gateway.visible.values.toList() shouldBe newest.map(::RecordedNativePart)
        compact.isAdmitted(newest) shouldBe true
    }

    test("channel loss and close revoke a compact receipt before queued cleanup runs") {
        val original = compactFrame(4)
        val gateway = EncodedFrameBudgetGateway()
        val compact = BuilderPacketAttachment(gateway.channel, gateway, Logger.getAnonymousLogger(), compactFrames = true)
        compact.submit(original)
        gateway.runImmediate()
        compact.isAdmitted(original) shouldBe true
        gateway.channel.open = false
        compact.isAdmitted(original) shouldBe false
        compact.closeAsync().isCompletedExceptionally shouldBe true
        compact.isAdmitted(original) shouldBe false

        val liveGateway = EncodedFrameBudgetGateway()
        val live = BuilderPacketAttachment(liveGateway.channel, liveGateway, Logger.getAnonymousLogger(), compactFrames = true)
        live.submit(original)
        liveGateway.runImmediate()
        live.isAdmitted(original) shouldBe true
        val cleanup = live.closeAsync()
        live.isAdmitted(original) shouldBe false
        cleanup.isDone shouldBe false
        liveGateway.runImmediate()
        cleanup.isDone shouldBe true
        cleanup.isCompletedExceptionally shouldBe false
    }

    test("removing target while retaining assist uses cleanup admission without spending the ordinary budget") {
        val target = compactFrame(4)
        val assist = compactFrame(7, firstId = 200)
        val gateway = EncodedFrameBudgetGateway()
        val compact = BuilderPacketAttachment(gateway.channel, gateway, Logger.getAnonymousLogger(), compactFrames = true)
        compact.submit(target + assist)
        gateway.runImmediate()
        gateway.remainingPackets = 0
        compact.submit(assist)
        gateway.runImmediate()

        gateway.visible.values.toList() shouldBe assist.map(::RecordedNativePart)
        gateway.trace.last().getValue("cleanup") shouldBe true
        gateway.trace.last().getValue("admission") shouldBe "ALLOWED"
        gateway.trace.last().getValue("packetCount") shouldBe 1
        gateway.remainingPackets shouldBe 0
        gateway.retryTasks shouldBe emptyList()
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
        val observer = attachment.closeAsync()
        observer.cancel(false)
        val actual = attachment.closeAsync()
        actual.isDone shouldBe false
        gateway.runImmediate()
        actual.isDone shouldBe false
        gateway.visibleIds shouldBe setOf(20)
        gateway.runRetry()

        gateway.destroyAttempts shouldBe listOf(listOf(20), listOf(20))
        gateway.visibleIds shouldBe emptySet()
        actual.isDone shouldBe true
        actual.isCompletedExceptionally shouldBe false
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

    test("moving and recoloring an existing display retains its native identity") {
        val channel = FakePreviewChannel()
        val gateway = RecordingPreviewGateway()
        val attachment = BuilderPacketAttachment(channel, gateway, Logger.getAnonymousLogger())
        val original = display(29)
        val moved = original.copy(x = 4.0, y = 65.0, z = 3.0, blockStateId = 2, glowRgb = 0xFFAA00,
            scaleX = .2f, translateY = .1f)

        attachment.submit(listOf(original))
        gateway.runImmediate()
        attachment.submit(listOf(moved))
        gateway.runImmediate()

        gateway.displayAttempts shouldBe listOf(29)
        gateway.destroyAttempts shouldBe emptyList()
        gateway.visible[29] shouldBe moved
    }

    test("a deferred move keeps the previous visible pose and retries only the latest pose") {
        val channel = FakePreviewChannel()
        val gateway = RecordingPreviewGateway()
        val attachment = BuilderPacketAttachment(channel, gateway, Logger.getAnonymousLogger())
        val original = display(30)
        val intermediate = original.copy(x = 4.0)
        val latest = original.copy(x = 5.0, glowRgb = 0xFFAA00)
        gateway.updateAdmissions += listOf(VisualPacketAdmission.SERVER_RATE, VisualPacketAdmission.ALLOWED)

        attachment.submit(listOf(original))
        gateway.runImmediate()
        attachment.submit(listOf(intermediate))
        gateway.runImmediate()
        gateway.visible[30] shouldBe original
        gateway.destroyAttempts shouldBe emptyList()

        attachment.submit(listOf(latest))
        gateway.runRetry()

        gateway.updateAttempts shouldBe listOf(original to intermediate, original to latest)
        gateway.displayAttempts shouldBe listOf(30)
        gateway.destroyAttempts shouldBe emptyList()
        gateway.visible[30] shouldBe latest
        gateway.retryTasks shouldBe emptyList()
    }

    test("closing a deferred move cleans up the retained entity without replaying the move") {
        val channel = FakePreviewChannel()
        val gateway = RecordingPreviewGateway()
        val attachment = BuilderPacketAttachment(channel, gateway, Logger.getAnonymousLogger())
        val original = display(31)
        val moved = original.copy(x = 4.0)
        gateway.updateAdmissions += VisualPacketAdmission.CHANNEL_BACKPRESSURE

        attachment.submit(listOf(original))
        gateway.runImmediate()
        attachment.submit(listOf(moved))
        gateway.runImmediate()
        val closed = attachment.closeAsync()
        gateway.runRetry()

        gateway.updateAttempts shouldBe listOf(original to moved)
        gateway.destroyAttempts shouldBe listOf(listOf(31))
        gateway.visible shouldBe emptyMap()
        closed.isDone shouldBe true
        closed.isCompletedExceptionally shouldBe false
        attachment.retainedEntityCount() shouldBe 0
    }

    test("a changed UUID still retires and respawns the old entity generation") {
        val channel = FakePreviewChannel()
        val gateway = RecordingPreviewGateway()
        val attachment = BuilderPacketAttachment(channel, gateway, Logger.getAnonymousLogger())
        val original = display(32)
        val replacement = original.copy(uuid = UUID.nameUUIDFromBytes("replacement-32".toByteArray()))

        attachment.submit(listOf(original))
        gateway.runImmediate()
        attachment.submit(listOf(replacement))
        gateway.runImmediate()

        gateway.displayAttempts shouldBe listOf(32, 32)
        gateway.destroyAttempts shouldBe listOf(listOf(32))
        gateway.updateAttempts shouldBe emptyList()
        gateway.visible[32] shouldBe replacement
    }

    test("native display updates encode changed metadata and an exact absolute snapped pose") {
        val original = display(33)
        val moved = original.copy(x = 4.0, y = 65.0, z = -3.0, blockStateId = 2, glowRgb = 0xFFAA00,
            glowing = false, scaleX = .2f, translateY = .1f, viewRange = 2f)
        val update = builderDisplayUpdatePacketTransaction(original, moved)

        update.size shouldBe 2
        val metadata = update.first() as WrapperPlayServerEntityMetadata
        metadata.entityId shouldBe original.entityId
        metadata.entityMetadata.map { it.index } shouldBe listOf(0, 8, 11, 12, 17, 22, 23)
        metadata.metadataValue(0) shouldBe 0.toByte()
        metadata.metadataValue(8) shouldBe 0
        (metadata.metadataValue(11) as Vector3f).y shouldBe .1f
        (metadata.metadataValue(12) as Vector3f).x shouldBe .2f
        metadata.metadataValue(17) shouldBe 2f
        metadata.metadataValue(22) shouldBe moved.glowRgb
        metadata.metadataValue(23) shouldBe moved.blockStateId
        val teleport = update.last() as WrapperPlayServerEntityTeleport
        teleport.entityId shouldBe original.entityId
        teleport.position.x shouldBe 4.0
        teleport.position.y shouldBe 65.0
        teleport.position.z shouldBe -3.0
        teleport.deltaMovement.lengthSquared() shouldBe 0.0
        teleport.relativeFlags shouldBe RelativeFlag.NONE
        teleport.yaw shouldBe 0f
        teleport.pitch shouldBe 0f
        teleport.isOnGround shouldBe false
    }

    test("position-only and color-only native updates omit unrelated packets and unchanged poses send nothing") {
        val original = display(34)
        val move = builderDisplayUpdatePacketTransaction(original, original.copy(x = 4.0))
        move.size shouldBe 1
        (move.single() as WrapperPlayServerEntityTeleport).position.x shouldBe 4.0
        val color = builderDisplayUpdatePacketTransaction(original, original.copy(glowRgb = 0xFFAA00))
        color.size shouldBe 1
        (color.single() as WrapperPlayServerEntityMetadata).entityMetadata.map { it.index } shouldBe listOf(22)
        builderDisplayUpdatePacketTransaction(original, original) shouldBe emptyList()
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
        initialMetadata.metadataValue(8) shouldBe 0
        initialMetadata.metadataValue(9) shouldBe 0
        initialMetadata.metadataValue(10) shouldBe 0
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

    test("native admission and close do not wait for blocked Netty snapshot aggregation") {
        val channel = FakePreviewChannel()
        val gateway = RecordingPreviewGateway()
        val attachment = BuilderPacketAttachment(channel, gateway, Logger.getAnonymousLogger())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var reads = 0
        val snapshot = object : AbstractList<BuilderPacketDisplay>() {
            override val size = 1000
            override fun get(index: Int): BuilderPacketDisplay {
                reads++
                if (index == 0) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                return display(1000 + index)
            }
        }
        attachment.submit(snapshot)
        reads shouldBe 0
        val netty = CompletableFuture.runAsync { gateway.runImmediate() }
        try {
            entered.await(2, TimeUnit.SECONDS) shouldBe true
            val admission = CompletableFuture.supplyAsync {
                attachment.submit(listOf(display(9)))
                attachment.closeAsync()
            }.get(2, TimeUnit.SECONDS)
            admission.isDone shouldBe false
        } finally { release.countDown() }
        netty.get(5, TimeUnit.SECONDS)
        gateway.runImmediate()
        attachment.closeAsync().isCompletedExceptionally shouldBe false
        attachment.closeAsync().isDone shouldBe true
        gateway.visibleIds shouldBe emptySet()
    }

    test("a disconnected channel drops its captured per-viewer state") {
        val channel = FakePreviewChannel()
        val gateway = RecordingPreviewGateway()
        val attachment = BuilderPacketAttachment(channel, gateway, Logger.getAnonymousLogger())
        attachment.submit(listOf(display(30)))
        gateway.runImmediate()
        attachment.retainedEntityCount() shouldBe 1

        channel.open = false
        attachment.closeAsync().isCompletedExceptionally shouldBe true

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

private fun compactFrame(x: Int, firstId: Int = 100): List<BuilderPacketDisplay> =
    BuilderDisplayGeometry.bounds(listOf(BuilderBlockPos(UUID(0L, 1L), x, 64, 4))).mapIndexed { index, edge ->
        display(firstId + index).copy(x = edge.x, y = edge.y, z = edge.z,
            scaleX = edge.scaleX, scaleY = edge.scaleY, scaleZ = edge.scaleZ)
    }

/** Decodes the actual production packet fields; it does not copy the requested desired frame. */
private data class RecordedNativePart(
    val id: Int, val uuid: UUID, val x: Double, val y: Double, val z: Double,
    val blockStateId: Int = 0, val glowRgb: Int = 0, val glowing: Boolean = false,
    val scaleX: Float = 1f, val scaleY: Float = 1f, val scaleZ: Float = 1f,
    val translateX: Float = 0f, val translateY: Float = 0f, val translateZ: Float = 0f,
    val viewRange: Float = 1f,
) {
    constructor(display: BuilderPacketDisplay) : this(display.entityId, display.uuid, display.x, display.y, display.z,
        display.blockStateId, display.glowRgb, display.glowing, display.scaleX, display.scaleY, display.scaleZ,
        display.translateX, display.translateY, display.translateZ, display.viewRange)
}

/** Simulated packet allowance at the published gateway; never a native-client renderer. */
private class EncodedFrameBudgetGateway : BuilderPreviewPacketGateway {
    val channel = FakePreviewChannel()
    private val immediateTasks = ArrayDeque<() -> Unit>()
    val retryTasks = ArrayDeque<() -> Unit>()
    val visible = linkedMapOf<Int, RecordedNativePart>()
    val trace = mutableListOf<Map<String, Any>>()
    var remainingPackets = Int.MAX_VALUE
    var phase = "initial"
    var onFlush: (() -> Unit)? = null
    var failNextFlush = false

    override fun isOpen(channel: Any) = (channel as FakePreviewChannel).open
    override fun execute(channel: Any, task: () -> Unit) { immediateTasks += task }
    override fun retry(channel: Any, task: () -> Unit) { retryTasks += task }
    override fun display(channel: Any, display: BuilderPacketDisplay) = admit("display", builderDisplayPacketTransaction(display))
    override fun update(channel: Any, previous: BuilderPacketDisplay, display: BuilderPacketDisplay) =
        admit("update", builderDisplayUpdatePacketTransaction(previous, display))
    override fun glow(channel: Any, display: BuilderPacketDisplay) = admit("glow", builderDisplayGlowTransaction(display))
    override fun destroy(channel: Any, entityIds: List<Int>) =
        admit("destroy", listOf(WrapperPlayServerDestroyEntities(*entityIds.toIntArray())), cleanup = true)
    override fun frame(channel: Any, previous: List<BuilderPacketDisplay>, desired: List<BuilderPacketDisplay>,
        forceResetIds: Set<Int>): VisualPacketAdmission {
        val transaction = builderDisplayFramePacketTransaction(previous, desired, forceResetIds)
        return admit("frame", transaction, cleanup = builderDisplayFrameIsCleanup(transaction))
    }
    override fun flush(channel: Any) {
        onFlush?.invoke()
        if (failNextFlush) {
            failNextFlush = false
            error("Simulated preview flush failure")
        }
    }
    fun runImmediate() = immediateTasks.removeFirst().invoke()
    fun runRetry() = retryTasks.removeFirst().invoke()

    private fun admit(operation: String, packets: List<PacketWrapper<*>>, cleanup: Boolean = false): VisualPacketAdmission {
        val admitted = cleanup || packets.size <= remainingPackets
        if (admitted) {
            if (!cleanup) remainingPackets -= packets.size
            packets.forEach(::applyPacket)
        }
        val step = trace.size
        trace += mapOf("step" to step, "timestampMs" to step * 50, "phase" to phase,
            "operation" to operation, "cleanup" to cleanup, "packetCount" to packets.size,
            "admission" to if (admitted) "ALLOWED" else "SERVER_RATE",
            "attemptedEvents" to packets.map(::packetEvent), "nativeIdsAndPoses" to visible.values.toList())
        return if (admitted) VisualPacketAdmission.ALLOWED else VisualPacketAdmission.SERVER_RATE
    }

    private fun applyPacket(packet: PacketWrapper<*>) {
        when (packet) {
            is WrapperPlayServerSpawnEntity -> visible[packet.entityId] = RecordedNativePart(packet.entityId,
                packet.uuid.get(), packet.position.x, packet.position.y, packet.position.z)
            is WrapperPlayServerDestroyEntities -> packet.entityIds.forEach(visible::remove)
            is WrapperPlayServerEntityTeleport -> {
                val old = visible.getValue(packet.entityId)
                visible[packet.entityId] = old.copy(x = packet.position.x, y = packet.position.y, z = packet.position.z)
            }
            is WrapperPlayServerEntityMetadata -> {
                var part = visible.getValue(packet.entityId)
                packet.entityMetadata.forEach { value ->
                    part = when (value.index) {
                        0 -> part.copy(glowing = (value.value as Byte).toInt() and 0x40 != 0)
                        11 -> (value.value as Vector3f).let { part.copy(translateX = it.x, translateY = it.y, translateZ = it.z) }
                        12 -> (value.value as Vector3f).let { part.copy(scaleX = it.x, scaleY = it.y, scaleZ = it.z) }
                        17 -> part.copy(viewRange = value.value as Float)
                        22 -> part.copy(glowRgb = value.value as Int)
                        23 -> part.copy(blockStateId = value.value as Int)
                        else -> part
                    }
                }
                visible[packet.entityId] = part
            }
            else -> error("Unexpected preview packet ${packet.javaClass.simpleName}")
        }
    }

    private fun packetEvent(packet: PacketWrapper<*>): Map<String, Any> = when (packet) {
        is WrapperPlayServerSpawnEntity -> mapOf("type" to "spawn", "id" to packet.entityId,
            "position" to listOf(packet.position.x, packet.position.y, packet.position.z))
        is WrapperPlayServerDestroyEntities -> mapOf("type" to "destroy", "ids" to packet.entityIds.toList())
        is WrapperPlayServerEntityTeleport -> mapOf("type" to "teleport", "id" to packet.entityId,
            "position" to listOf(packet.position.x, packet.position.y, packet.position.z))
        is WrapperPlayServerEntityMetadata -> mapOf("type" to "metadata", "id" to packet.entityId,
            "indices" to packet.entityMetadata.map { it.index })
        else -> error("Unexpected preview packet ${packet.javaClass.simpleName}")
    }
}

private class RecordingPreviewGateway : BuilderPreviewPacketGateway {
    private val immediateTasks = mutableListOf<() -> Unit>()
    val retryTasks = mutableListOf<() -> Unit>()
    val displayAttempts = mutableListOf<Int>()
    val destroyAttempts = mutableListOf<List<Int>>()
    val glowAttempts = mutableListOf<BuilderPacketDisplay>()
    val updateAttempts = mutableListOf<Pair<BuilderPacketDisplay, BuilderPacketDisplay>>()
    val visibleIds = linkedSetOf<Int>()
    val displayAdmissions = mutableListOf<VisualPacketAdmission>()
    val destroyAdmissions = mutableListOf<VisualPacketAdmission>()
    val glowAdmissions = mutableListOf<VisualPacketAdmission>()
    val updateAdmissions = mutableListOf<VisualPacketAdmission>()
    val glowStates = mutableMapOf<Int, Boolean>()
    val visible = linkedMapOf<Int, BuilderPacketDisplay>()

    override fun isOpen(channel: Any) = (channel as FakePreviewChannel).open
    override fun execute(channel: Any, task: () -> Unit) { immediateTasks += task }
    override fun retry(channel: Any, task: () -> Unit) { retryTasks += task }

    override fun display(channel: Any, display: BuilderPacketDisplay): VisualPacketAdmission {
        displayAttempts += display.entityId
        val admission = displayAdmissions.removeFirstOrAllowed()
        if (admission == VisualPacketAdmission.ALLOWED) {
            visibleIds += display.entityId
            visible[display.entityId] = display
        }
        return admission
    }

    override fun glow(channel: Any, display: BuilderPacketDisplay): VisualPacketAdmission {
        glowAttempts += display
        val admission = glowAdmissions.removeFirstOrAllowed()
        if (admission == VisualPacketAdmission.ALLOWED) {
            glowStates[display.entityId] = display.glowing
            visible[display.entityId] = display
        }
        return admission
    }

    override fun update(channel: Any, previous: BuilderPacketDisplay, display: BuilderPacketDisplay): VisualPacketAdmission {
        updateAttempts += previous to display
        val admission = updateAdmissions.removeFirstOrAllowed()
        if (admission == VisualPacketAdmission.ALLOWED) visible[display.entityId] = display
        return admission
    }

    override fun frame(channel: Any, previous: List<BuilderPacketDisplay>, desired: List<BuilderPacketDisplay>,
        forceResetIds: Set<Int>): VisualPacketAdmission {
        error("Progressive attachment tests must not use the compact frame gateway")
    }

    override fun destroy(channel: Any, entityIds: List<Int>): VisualPacketAdmission {
        destroyAttempts += entityIds.toList()
        val admission = destroyAdmissions.removeFirstOrAllowed()
        if (admission == VisualPacketAdmission.ALLOWED) {
            visibleIds.removeAll(entityIds.toSet())
            entityIds.forEach(visible::remove)
        }
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
