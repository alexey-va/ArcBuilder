package ru.arc.buildertools.display

import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.PacketEventsAPI
import com.github.retrooper.packetevents.injector.ChannelInjector
import com.github.retrooper.packetevents.manager.player.PlayerManager
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager
import com.github.retrooper.packetevents.manager.server.ServerManager
import com.github.retrooper.packetevents.manager.server.ServerVersion
import io.github.retrooper.packetevents.impl.netty.NettyManagerImpl
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.block.data.BlockData
import org.joml.Matrix4f
import org.joml.Vector3d
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.concurrent.CompletableFuture
import java.lang.reflect.Proxy
import java.util.logging.Logger

class DisplayEditorAxesTest : FunSpec({
    val previousPacketEventsApi = PacketEvents.getAPI()
    beforeSpec { PacketEvents.setAPI(AxisPacketEventsApi()) }
    afterSpec { PacketEvents.setAPI(previousPacketEventsApi) }

    test("canonical handle picking admits aim tolerance but rejects idle inner hidden and out-of-reach guides") {
        val pose = DisplayEditorPose(0.0, 0.0, 0.0)
        val mesh = DisplayEditorMesh(1.0, 1.0, 1.0)
        val frame = DisplayEditorAxesGeometry.frame(pose, mesh, DisplayEditorAxis.FREE)
        val eye = Vector3d(0.0, 0.0, -3.0)
        fun toward(x: Double, y: Double, z: Double) = Vector3d(x, y, z).sub(eye).normalize()
        DisplayEditorAxesGeometry.pick(frame, eye, toward(1.35, 0.0, 0.0), 12.0)?.axis shouldBe DisplayEditorAxis.X
        DisplayEditorAxesGeometry.pick(frame, eye, toward(1.35, .09, 0.0), 12.0)?.axis shouldBe DisplayEditorAxis.X
        DisplayEditorAxesGeometry.pick(frame, eye, toward(1.35, .3, 0.0), 12.0) shouldBe null
        DisplayEditorAxesGeometry.pick(frame, eye, toward(.2, 0.0, 0.0), 12.0) shouldBe null
        DisplayEditorAxesGeometry.pick(frame, eye, toward(1.35, 0.0, 0.0), 1.0) shouldBe null
        DisplayEditorAxesGeometry.pick(frame, Vector3d(1.35, 0.0, -20.0), Vector3d(0.0, 0.0, 1.0), 100.0) shouldBe null
        val hiddenDirection = toward(0.0, 0.0, 1.35)
        val sourceHit = DisplayEditorGeometry.rayDistance(eye, hiddenDirection, pose, mesh)!!
        DisplayEditorAxesGeometry.pick(frame, eye, hiddenDirection, sourceHit) shouldBe null
        DisplayEditorAxesGeometry.pick(DisplayEditorAxesGeometry.frame(pose, null, DisplayEditorAxis.FREE), eye, toward(.65, 0.0, 0.0), 12.0) shouldBe null
        val hover = DisplayEditorAxesGeometry.frame(pose, mesh, DisplayEditorAxis.Y, DisplayEditorAxis.X)
        hover.parts.count { it.hovered } shouldBe 2
        hover.parts.first().height shouldBe .08f
        // Producing a hover frame never mutates the canonical boxes or widens their aim target.
        DisplayEditorAxesGeometry.pick(frame, eye, toward(1.35, .3, 0.0), 12.0) shouldBe null
    }

    test("selected world guides extend beyond a rotated large object and one guide is visibly thicker") {
        val pose = DisplayEditorPose(3.0, 65.0, 5.0).withMatrix(Matrix4f().rotateXYZ(.3f, .6f, .2f).scale(3f, 2f, .75f))
        val mesh = DisplayEditorMesh(1.0, 1.0, 1.0)
        val frame = DisplayEditorAxesGeometry.frame(pose, mesh, DisplayEditorAxis.Y)
        val corners = DisplayEditorGeometry.corners(pose, mesh)
        frame.parts shouldHaveSize 6
        for (axis in 0..2) {
            val rod = frame.parts[axis * 2]
            val tip = frame.parts[axis * 2 + 1]
            val start = listOf(rod.x, rod.y, rod.z)[axis]
            val length = listOf(rod.width, rod.height, rod.depth)[axis]
            (start + length > corners.maxOf { it[axis] } - pose.position()[axis] + .8) shouldBe true
            listOf(rod.width, rod.height, rod.depth).filterIndexed { index, _ -> index != axis }
                .all { it == if (axis == 1) .055f else .025f } shouldBe true
            tip.width shouldBe if (axis == 1) .13f else .085f
        }
        val idle = DisplayEditorAxesGeometry.frame(pose, null, DisplayEditorAxis.FREE)
        idle.parts shouldHaveSize 6
        idle.parts.filterIndexed { index, _ -> index % 2 == 0 }.all {
            maxOf(it.width, it.height, it.depth) < .7f
        } shouldBe true
    }

    test("private packet handles remain stable across ticks and worlds and close returns the actual drain") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("AxisTest")
            val world = paper.addSimpleWorld("axis-world")
            val other = paper.addSimpleWorld("axis-other")
            val player = paper.addPlayer("Decorator")
            player.teleport(Location(world, .5, 64.0, .5))
            val handles = mutableListOf<PacketBlockDisplay>()
            val drain = CompletableFuture<Void>()
            val packets = mockk<PaperPacketDisplays> {
                every { spawnBlock(any(), any()) } answers { mockk<PacketBlockDisplay>(relaxed = true).also(handles::add) }
                every { closeAsync() } returns drain
            }
            val axes = DisplayEditorAxes(plugin, packets)
            val pose = DisplayEditorPose(.5, 65.0, 3.5)
            val frame = DisplayEditorAxesGeometry.frame(pose, null, DisplayEditorAxis.X)
            axes.update(player, frame)
            axes.update(player, frame)
            handles shouldHaveSize 6
            handles.forEach { display ->
                verify(exactly = 1) { display.isVisibleByDefault = false }
                verify(exactly = 1) { display.showTo(player.uniqueId) }
                verify(exactly = 1) { display.transformation = any() }
            }
            val moved = DisplayEditorAxesGeometry.frame(pose.copy(x = 2.0), null, DisplayEditorAxis.Z)
            axes.update(player, moved)
            handles shouldHaveSize 6
            handles.forEach { display -> verify(exactly = 2) { display.transformation = any() } }
            world.entities.filterIsInstance<org.bukkit.entity.Display>() shouldHaveSize 0
            player.teleport(Location(other, .5, 64.0, .5))
            axes.update(player, moved)
            handles shouldHaveSize 12
            handles.take(6).forEach { display -> verify(exactly = 1) { display.remove() } }
            axes.clear(player.uniqueId)
            handles.drop(6).forEach { display -> verify(exactly = 1) { display.remove() } }
            axes.closeAsync() shouldBe drain
            drain.isDone shouldBe false
            drain.complete(null)
            axes.update(player, frame)
            handles shouldHaveSize 12
        }
    }

    test("compiled Core encoder restarts one-tick interpolation on every changed axis transform") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("AxisInterpolationTest")
            val world = paper.addSimpleWorld("interpolation-world")
            val player = paper.addPlayer("Decorator")
            player.teleport(Location(world, .5, 64.0, .5))
            val handles = mutableListOf<PacketBlockDisplay>()
            val packets = mockk<PaperPacketDisplays>(relaxed = true)
            // Real Core handles and immutable frames; only owner bookkeeping is mocked.
            val constructor = PacketBlockDisplay::class.java.getConstructor(
                PaperPacketDisplays::class.java, Int::class.javaPrimitiveType, Location::class.java, BlockData::class.java,
            )
            every { packets.spawnBlock(any(), any()) } answers {
                constructor.newInstance(packets, 9000 + handles.size, firstArg<Location>(), secondArg<BlockData>()).also(handles::add)
            }
            val axes = DisplayEditorAxes(plugin, packets)
            val codec = CompiledAxisMetadataEncoder()
            val pose = DisplayEditorPose(.5, 65.0, 3.5)
            val mesh = DisplayEditorMesh(1.0, 1.0, 1.0)
            axes.update(player, DisplayEditorAxesGeometry.frame(pose, mesh, DisplayEditorAxis.FREE))
            val first = codec.frame(handles.first())
            val initial = codec.delta(null, first)
            initial[8] shouldBe 0 // Interpolation starts now, not one tick later.
            initial[9] shouldBe 1 // Transformation duration.
            initial[10] shouldBe 1 // Position / rotation duration.

            axes.update(player, DisplayEditorAxesGeometry.frame(pose.withMatrix(Matrix4f().scale(2f)), mesh, DisplayEditorAxis.FREE))
            val resized = codec.frame(handles.first())
            codec.delta(first, resized)[8] shouldBe 0
            axes.update(player, DisplayEditorAxesGeometry.frame(pose.withMatrix(Matrix4f().scale(3f)), mesh, DisplayEditorAxis.FREE))
            val resizedAgain = codec.frame(handles.first())
            codec.delta(resized, resizedAgain)[8] shouldBe 0 // Constant delay still emits a new start trigger.

            val movedFrame = DisplayEditorAxesGeometry.frame(pose.copy(x = 1.5).withMatrix(Matrix4f().scale(3f)), mesh, DisplayEditorAxis.FREE)
            axes.update(player, movedFrame)
            val translated = codec.frame(handles.first())
            translated.javaClass.getMethod("getX").invoke(translated) shouldBe 1.5
            handles.first().teleportDuration shouldBe 1
            codec.delta(resizedAgain, translated).isEmpty() shouldBe true // Position travels through the teleport packet.
            axes.update(player, movedFrame)
            codec.delta(translated, codec.frame(handles.first())).isEmpty() shouldBe true
            handles shouldHaveSize 6
            handles.map { it.entityId } shouldBe (9000..9005).toList()
            axes.clear(player.uniqueId)
        }
    }
})

/** Exercise the dependency's actual metadata encoder, not a duplicate of its pulse logic. */
private class CompiledAxisMetadataEncoder {
    private val viewerType = Class.forName("ru.arc.paper.display.PacketDisplayViewer")
    private val carrierType = Class.forName("ru.arc.paper.display.PacketDisplayCarrier")
    private val frameType = Class.forName("ru.arc.paper.display.PacketDisplayFrame")
    private val attachmentType = Class.forName("ru.arc.paper.display.PacketDisplayAttachment")
    private val backendType = Class.forName("ru.arc.paper.display.PacketDisplayChannelBackend")
    private val passengerType = Class.forName("ru.arc.paper.display.PacketDisplayPassengerState")
    private val channel = Any()
    private val attachment = attachmentType.getConstructor(Any::class.java, Logger::class.java, backendType, passengerType).newInstance(
        channel, Logger.getAnonymousLogger(),
        Proxy.newProxyInstance(backendType.classLoader, arrayOf(backendType)) { _, method, _ -> error("Unexpected channel call: $method") },
        passengerType.getConstructor().newInstance(),
    )
    private val encode = attachmentType.getDeclaredMethod("metadataValues", frameType, frameType).apply { isAccessible = true }
    private val captureFrame = PacketBlockDisplay::class.java.getMethod("frame\$arc_core_paper", viewerType, carrierType)

    fun frame(display: PacketBlockDisplay): Any = captureFrame.invoke(display, null, null)

    @Suppress("UNCHECKED_CAST")
    fun delta(before: Any?, after: Any): Map<Int, Any?> =
        (encode.invoke(attachment, before, after) as List<EntityData<*>>).associate { it.index to it.value }
}

/** Real PacketEvents mapping settings for metadata construction; no live connection. */
private class AxisPacketEventsApi : PacketEventsAPI<Any>() {
    private val server = ServerManager { ServerVersion.V_1_21_11 }
    private val netty = NettyManagerImpl()
    override fun getServerManager() = server
    override fun getNettyManager() = netty
    override fun getPlugin(): Any = this
    override fun getProtocolManager(): ProtocolManager = error("No live protocol manager in axis metadata tests")
    override fun getPlayerManager(): PlayerManager = error("No live players in axis metadata tests")
    override fun getInjector(): ChannelInjector = error("No channel injection in axis metadata tests")
    override fun load() = Unit
    override fun init() = Unit
    override fun terminate() = Unit
    override fun isLoaded() = true
    override fun isInitialized() = true
    override fun isTerminated() = false
}
