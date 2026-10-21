package ru.arc.buildertools.display

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.joml.Matrix4f
import org.joml.Vector3d
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.concurrent.CompletableFuture

class DisplayEditorAxesTest : FunSpec({
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
})
