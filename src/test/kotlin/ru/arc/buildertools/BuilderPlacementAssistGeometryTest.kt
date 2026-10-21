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

    test("successful placements renew direction naturally while same-plane side clicks preserve its normal") {
        val line = BuilderPlacementAssistLock(origin, planeNormal = BlockFace.UP)
            .placed(origin.relative(BlockFace.EAST), BlockFace.EAST)
        line.planeNormal shouldBe BlockFace.UP
        line.placed(checkNotNull(line.next)).next shouldBe origin.copy(x = 3)
        val turn = line.placed(origin.copy(x = 1, z = 1), BlockFace.SOUTH)
        turn.direction shouldBe BlockFace.SOUTH
        turn.planeNormal shouldBe BlockFace.UP
        turn.anchor shouldBe origin
        val newStroke = turn.placed(origin.copy(x = 4, y = 66), BlockFace.NORTH)
        newStroke.direction shouldBe null
        newStroke.anchor shouldBe origin.copy(x = 4, y = 66)
        newStroke.planeNormal shouldBe BlockFace.NORTH
        BuilderPlacementAssistLock(origin).inPlane(origin) shouldBe false
    }

    test("an off-cell ray never magnetizes the row from a neighbouring crosshair cell") {
        val eye = Vector(.5, 66.62, .5)
        val target = origin.copy(x = 2)
        val direction = Vector(3.5, 63.9, 1.8).subtract(eye)
        BuilderPlacementAssistGeometry.aimDistance(eye, direction, target, 4.5) shouldBe null
    }

    test("plane chooses exactly the first visible layer cell on either side of a floor or wall") {
        for (normal in listOf(BlockFace.UP, BlockFace.DOWN, BlockFace.EAST, BlockFace.WEST, BlockFace.NORTH, BlockFace.SOUTH)) {
            val lock = BuilderPlacementAssistLock(origin, planeNormal = normal)
            val center = BuilderPlacementAssistGeometry.center(origin)
            val eye = center.clone().add(Vector(normal.modX, normal.modY, normal.modZ).multiply(3))
            val point = checkNotNull(BuilderPlacementAssistGeometry.projectPlane(eye, center.clone().subtract(eye), lock, 4.5))
            point shouldBe center.clone().add(Vector(normal.modX, normal.modY, normal.modZ).multiply(.5))
            BuilderPlacementAssistGeometry.projectedCell(point, lock) shouldBe origin
            BuilderPlacementAssistGeometry.projectPlane(eye, center.clone().subtract(eye), lock, 2.0) shouldBe null
        }
    }

    test("small diagonal aim movement keeps one plane cell and a reachable face is not projected beyond reach") {
        val lock = BuilderPlacementAssistLock(origin, planeNormal = BlockFace.UP)
        val eye = Vector(2.5, 65.62, 3.5)
        for (point in listOf(Vector(1.49, 65.0, 1.51), Vector(1.51, 65.0, 1.49))) {
            val projection = checkNotNull(BuilderPlacementAssistGeometry.projectPlane(eye, point.subtract(eye), lock, 4.5))
            BuilderPlacementAssistGeometry.projectedCell(projection, lock) shouldBe origin.copy(x = 1, z = 1)
        }
        val top = Vector(.5, 65.0, 1.5)
        val projection = checkNotNull(BuilderPlacementAssistGeometry.projectPlane(eye, top.clone().subtract(eye), lock, 4.5))
        (projection.distance(top) < 1e-9) shouldBe true
        BuilderPlacementAssistGeometry.projectedCell(projection, lock) shouldBe origin.copy(z = 1)
        BuilderPlacementAssistGeometry.projectPlane(Vector(.5, 64.5, .5), Vector(1, 0, 0), lock, 4.5) shouldBe null
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
