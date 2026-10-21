package ru.arc.buildertools.display

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.joml.Matrix4f
import org.joml.Vector3d
import org.joml.Vector3f
import java.util.UUID

class DisplayEditorGeometryTest : FunSpec({
    test("gizmo scale stretches only the selected world coordinate of a rotated non-uniform model") {
        val pose = DisplayEditorPose(4.0, 65.0, 7.0).withMatrix(Matrix4f().rotateXYZ(.4f, -.7f, .25f).scale(2f, .7f, 1.5f))
        val mesh = DisplayEditorMesh(1.0, 1.0, 1.0)
        val before = DisplayEditorGeometry.corners(pose, mesh)
        for (axis in listOf(DisplayEditorAxis.X, DisplayEditorAxis.Y, DisplayEditorAxis.Z)) {
            for (factor in listOf(.7f, 1.4f)) {
                val stretched = DisplayEditorGeometry.worldScale(pose, axis, factor)
                stretched.position() shouldBe pose.position()
                DisplayEditorGeometry.corners(stretched, mesh).zip(before).forEach { (after, original) ->
                    for (coordinate in 0..2) {
                        val expected = pose.position()[coordinate] + (original[coordinate] - pose.position()[coordinate]) * if (coordinate == axis.ordinal - 1) factor else 1f
                        after[coordinate] shouldBe (expected plusOrMinus .000001)
                    }
                }
            }
        }
    }

    test("axis gesture follows outward and inward rays while near-parallel views and rays behind the plane pause") {
        val pose = DisplayEditorPose(0.0, 0.0, 0.0)
        val eye = Vector3d(0.0, 0.0, -3.0)
        fun ray(x: Double) = Vector3d(x, 0.0, 3.0).normalize()
        val gesture = DisplayEditorGeometry.axisGesture(pose, DisplayEditorAxis.X, eye, ray(1.35))!!
        DisplayEditorGeometry.axisDisplacement(gesture, eye, ray(2.0))!! shouldBe (.65 plusOrMinus .000001)
        DisplayEditorGeometry.axisDisplacement(gesture, eye, ray(.8))!! shouldBe (-.55 plusOrMinus .000001)
        DisplayEditorGeometry.axisDisplacement(gesture, eye, Vector3d(1.0, 0.0, 0.0)) shouldBe null
        DisplayEditorGeometry.axisDisplacement(gesture, eye, Vector3d(0.0, 0.0, -1.0)) shouldBe null
        DisplayEditorGeometry.axisGesture(pose, DisplayEditorAxis.X, eye, Vector3d(1.0, 0.0, 0.0)) shouldBe null
        DisplayEditorGeometry.axisGesture(pose, DisplayEditorAxis.FREE, eye, ray(1.35)) shouldBe null
    }

    test("absolute local scale and both resets preserve a rotated non-uniform object's centre and the other property") {
        val pose = DisplayEditorPose(12.5, 66.25, -3.75)
            .withMatrix(Matrix4f().rotateXYZ(.4f, -.8f, .25f).scale(2f, .75f, 1.5f))
        val desired = DisplayEditorScale(1.23f, 3.01f, .05f)
        val resized = DisplayEditorGeometry.withScale(pose, desired)!!
        val unit = DisplayEditorGeometry.withScale(pose, DisplayEditorScale(1f, 1f, 1f))!!
        val unrotated = DisplayEditorGeometry.withoutRotation(pose)
        listOf(resized, unit, unrotated).forEach { it.position() shouldBe pose.position() }
        for (axis in 0..2) {
            val originalDirection = pose.matrix().getColumn(axis, Vector3f()).normalize()
            val resizedColumn = resized.matrix().getColumn(axis, Vector3f())
            resizedColumn.length().toDouble() shouldBe (desired.values()[axis].toDouble() plusOrMinus .000001)
            Vector3f(resizedColumn).normalize().distance(originalDirection).toDouble() shouldBe (0.0 plusOrMinus .000001)
            val unitColumn = unit.matrix().getColumn(axis, Vector3f())
            unitColumn.length().toDouble() shouldBe (1.0 plusOrMinus .000001)
            unitColumn.distance(originalDirection).toDouble() shouldBe (0.0 plusOrMinus .000001)
            val aligned = unrotated.matrix().getColumn(axis, Vector3f())
            aligned.length().toDouble() shouldBe (pose.matrix().getColumn(axis, Vector3f()).length().toDouble() plusOrMinus .000001)
            aligned.normalize().distance(Vector3f().setComponent(axis, 1f)).toDouble() shouldBe (0.0 plusOrMinus .000001)
        }
    }

    test("absolute scale refuses invisible non-finite and excessive components") {
        val pose = DisplayEditorPose(.5, 65.5, 3.5)
        for (invalid in listOf(0f, -.5f, .049f, 4.01f, Float.NaN, Float.POSITIVE_INFINITY)) {
            DisplayEditorGeometry.withScale(pose, DisplayEditorScale(1f, invalid, 1f)) shouldBe null
        }
    }

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
