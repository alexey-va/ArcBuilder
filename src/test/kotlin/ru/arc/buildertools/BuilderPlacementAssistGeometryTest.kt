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
            val line = BuilderPlacementAssistLine(origin).placed(second)
            line.direction shouldBe face
            line.next shouldBe origin.copy(x = 2 * face.modX, y = 64 + 2 * face.modY, z = 2 * face.modZ)
        }
        for (second in listOf(origin, origin.copy(x = 2), origin.copy(x = 1, z = 1), origin.copy(worldId = UUID.randomUUID()))) {
            BuilderPlacementAssistLine(origin).placed(second).direction shouldBe null
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
