package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.block.BlockFace
import org.bukkit.util.Vector
import java.util.UUID

class BuilderPlacementAssistGeometryTest : FunSpec({
    val world = UUID.randomUUID()
    val origin = BuilderBlockPos(world, 0, 64, 0)

    test("only adjacent cardinal successes establish a direction and next cell") {
        for (face in listOf(BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST)) {
            val second = origin.copy(x = face.modX, y = 64 + face.modY, z = face.modZ)
            val line = BuilderPlacementAssistLock(origin).placed(second)
            line.direction shouldBe face
            line.next shouldBe origin.copy(x = 2 * face.modX, y = 64 + 2 * face.modY, z = 2 * face.modZ)
        }
        for (second in listOf(origin, origin.copy(x = 2), origin.copy(x = 1, z = 1), origin.copy(worldId = UUID.randomUUID()))) {
            BuilderPlacementAssistLock(origin).placed(second).direction shouldBe null
        }
    }

    test("earned line ignores incidental placements and advances only its next cell") {
        val line = BuilderPlacementAssistLock(origin).placed(origin.relative(BlockFace.EAST))
        (line.placed(origin.relative(BlockFace.UP)) === line) shouldBe true
        (line.placed(origin.copy(x = 3)) === line) shouldBe true
        line.placed(checkNotNull(line.next)).next shouldBe origin.copy(x = 3)
        BuilderPlacementAssistLock(origin).inPlane(origin) shouldBe false
    }

    test("line view sector accepts an off-cell ray but not a substantially different view") {
        val eye = Vector(.5, 66.62, .5)
        val target = origin.copy(x = 2)
        val direction = Vector(3.5, 63.9, 1.8).subtract(eye)
        BuilderPlacementAssistGeometry.inView(eye, direction, target) shouldBe true
        BuilderPlacementAssistGeometry.aimDistance(eye, direction, target, 4.5) shouldBe null
        BuilderPlacementAssistGeometry.inView(eye, Vector(-1, 0, 0), target) shouldBe false
    }

    test("plane projection and local candidates keep the locked floor or wall coordinate") {
        for (normal in listOf(BlockFace.UP, BlockFace.DOWN, BlockFace.EAST, BlockFace.WEST, BlockFace.NORTH, BlockFace.SOUTH)) {
            val lock = BuilderPlacementAssistLock(origin, planeNormal = normal)
            val center = BuilderPlacementAssistGeometry.center(origin)
            val eye = center.clone().add(Vector(normal.modX, normal.modY, normal.modZ).multiply(3))
            val point = checkNotNull(BuilderPlacementAssistGeometry.projectPlane(eye, center.clone().subtract(eye), lock, 4.5))
            point shouldBe center
            val candidates = BuilderPlacementAssistGeometry.planeCandidates(point, lock)
            candidates.size shouldBe 5
            candidates.first() shouldBe origin
            candidates.all(lock::inPlane) shouldBe true
            BuilderPlacementAssistGeometry.projectPlane(eye, center.clone().subtract(eye), lock, 2.0) shouldBe null
        }
    }

    test("aim requires the real block volume within eye reach and in front of the camera") {
        val target = origin.copy(z = 3)
        BuilderPlacementAssistGeometry.aimDistance(Vector(.5, 64.5, .5), Vector(0, 0, 1), target, 4.5) shouldBe 2.5
        BuilderPlacementAssistGeometry.aimDistance(Vector(.5, 64.5, .5), Vector(0, 0, 1), target, 2.0) shouldBe null
        BuilderPlacementAssistGeometry.aimDistance(Vector(1.01, 64.5, .5), Vector(0, 0, 1), target, 4.5) shouldBe null
        BuilderPlacementAssistGeometry.aimDistance(Vector(.5, 64.5, .5), Vector(0, 0, -1), target, 4.5) shouldBe null
        BuilderPlacementAssistGeometry.aimDistance(Vector(.5, 64.5, 3.5), Vector(0, 0, 1), target, 4.5) shouldBe null
        BuilderPlacementAssistGeometry.aimDistance(Vector(Double.NaN, 64.5, .5), Vector(0, 0, 1), target, 4.5) shouldBe null
    }

    test("ready and blocked frames keep exactly twelve thin edges and an open aim area") {
        val edges = BuilderPlacementAssistAppearance.edges(origin)
        edges.size shouldBe 12
        edges.all { listOf(it.scaleX, it.scaleY, it.scaleZ).sorted().take(2) == listOf(.025f, .025f) } shouldBe true
        edges.count { it.scaleX == 1.025f } shouldBe 4
        edges.count { it.scaleY == .975f || it.scaleZ == .975f } shouldBe 8
        (BuilderPlacementAssistAppearance.forState(false) != BuilderPlacementAssistAppearance.forState(true)) shouldBe true
    }
})
