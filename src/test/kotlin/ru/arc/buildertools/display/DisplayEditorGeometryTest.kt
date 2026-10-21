package ru.arc.buildertools.display

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.joml.Matrix4f
import org.joml.Vector3d
import java.util.UUID

class DisplayEditorGeometryTest : FunSpec({
    test("picking follows rotated scaled visual geometry and rejects empty AABB corners") {
        val pose = DisplayEditorPose(0.0, 65.0, 4.0).withMatrix(Matrix4f().rotateZ((Math.PI / 4).toFloat()).scale(4f, .5f, 1f))
        val mesh = DisplayEditorMesh(1.0, 1.0, 1.0)
        DisplayEditorGeometry.rayDistance(Vector3d(1.0, 66.0, 0.0), Vector3d(0.0, 0.0, 1.0), pose, mesh) shouldBe (3.5 plusOrMinus .0001)
        DisplayEditorGeometry.rayDistance(Vector3d(1.0, 65.0, 0.0), Vector3d(0.0, 0.0, 1.0), pose, mesh) shouldBe null
    }

    test("negative coordinates and the complete scaled footprint are protected") {
        val pose = DisplayEditorPose(-.5, 64.5, -.5).withMatrix(Matrix4f().scaling(3f, 1f, 1f))
        DisplayEditorGeometry.cells(pose, DisplayEditorMesh(1.0, 1.0, 1.0))!!.toSet() shouldBe setOf(
            Triple(-2, 64, -1), Triple(-1, 64, -1), Triple(0, 64, -1),
        )
        DisplayEditorGeometry.valid(pose.withMatrix(Matrix4f().scaling(0f)), DisplayEditorMesh(1.0, 1.0, 1.0)) shouldBe false
        DisplayEditorGeometry.cells(pose.copy(x = Double.NaN), DisplayEditorMesh(1.0, 1.0, 1.0)) shouldBe null
        DisplayEditorGeometry.valid(pose, DisplayEditorMesh(4.0, 1.0, 1.0)) shouldBe false
    }

    test("surface magnet uses transformed support for floors walls and ceilings") {
        val pose = DisplayEditorPose(3.0, 65.0, 3.0).withMatrix(Matrix4f().rotateX(.6f).rotateZ(.4f).scale(2f, 1f, .5f))
        val mesh = DisplayEditorMesh(1.0, 1.0, 1.0)
        for (normal in listOf(Vector3d(0.0, 1.0, 0.0), Vector3d(0.0, -1.0, 0.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 0.0, -1.0))) {
            val surface = Vector3d(5.0, 66.0, 7.0)
            val magnet = DisplayEditorGeometry.onSurface(pose, mesh, surface, normal)
            DisplayEditorGeometry.corners(magnet, mesh).minOf { Vector3d(it).sub(surface).dot(normal) } shouldBe (.006 plusOrMinus .00001)
        }
    }

    test("ray preflight catches a briefly grazed unloaded diagonal chunk and epsilon boundary") {
        val visited = mutableSetOf<Pair<Int, Int>>()
        DisplayEditorGeometry.rayChunksLoaded(Vector3d(15.9, 65.0, 15.8), Vector3d(1.0, 0.0, 1.0).normalize()) { x, z ->
            visited += x to z
            x != 1 || z != 0
        } shouldBe false
        visited.contains(1 to 0) shouldBe true
        DisplayEditorGeometry.rayChunksLoaded(Vector3d(0.0, 65.0, .5), Vector3d(1.0, 0.0, 0.0)) { x, _ -> x != -1 } shouldBe false
    }

    test("world axis constraints preserve the other coordinates and yaw wraps without jumping") {
        DisplayEditorGeometry.constrain(Vector3d(9.0, 8.0, 7.0), Vector3d(1.0, 2.0, 3.0), DisplayEditorAxis.Y) shouldBe Vector3d(1.0, 8.0, 3.0)
        DisplayEditorGeometry.yawDelta(179f, -179f) shouldBe 2.0
        DisplayEditorGeometry.edges.size shouldBe 12
    }

    test("permission and ownership never admit unmarked displays even for admins") {
        val owner = UUID.randomUUID()
        val other = UUID.randomUUID()
        DisplayEditorAuthority.mayEdit(false, owner, owner, true, true) shouldBe false
        DisplayEditorAuthority.mayEdit(true, null, owner, true, true) shouldBe false
        DisplayEditorAuthority.mayEdit(true, owner, other, true, false) shouldBe false
        DisplayEditorAuthority.mayEdit(true, owner, other, false, true) shouldBe false
        DisplayEditorAuthority.mayEdit(true, owner, other, true, true) shouldBe true
        DisplayEditorAuthority.mayEdit(true, owner, owner, true, false) shouldBe true
        val locks = DisplayEditorLocks()
        val objectId = UUID.randomUUID()
        locks.acquire(objectId, owner) shouldBe true
        locks.acquire(objectId, other) shouldBe false
        locks.release(owner)
        locks.acquire(objectId, other) shouldBe true
    }

    test("plain text input bounds lines glyphs and rejects invisible or private-font content") {
        BuilderDisplayEditor.validatedText("Дом\\nМастерская") shouldBe "Дом\nМастерская"
        BuilderDisplayEditor.validatedText("   ") shouldBe null
        BuilderDisplayEditor.validatedText("А".repeat(121)) shouldBe null
        BuilderDisplayEditor.validatedText("a\nb\nc\nd\ne") shouldBe null
        BuilderDisplayEditor.validatedText("private\uE001") shouldBe null
        BuilderDisplayEditor.validatedText("<click:run_command:'/op'>literal</click>") shouldBe "<click:run_command:'/op'>literal</click>"
    }
})
