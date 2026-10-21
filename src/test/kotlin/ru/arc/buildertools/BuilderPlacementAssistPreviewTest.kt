package ru.arc.buildertools

import com.google.gson.GsonBuilder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.block.BlockFace
import org.bukkit.util.Vector
import java.io.File
import java.util.UUID

/** Small source-derived fixtures: no alternate particle or display geometry. */
class BuilderPlacementAssistPreviewTest : FunSpec({
    test("export off-aim row floor wall and blocked states from production geometry") {
        val world = UUID(0L, 1L)
        fun position(x: Int, y: Int = 0, z: Int = 0) = BuilderBlockPos(world, x, y, z)
        fun point(vector: Vector) = listOf(vector.x, vector.y, vector.z)
        fun state(id: String, title: String, role: String, target: BuilderBlockPos,
                  placed: List<BuilderBlockPos>, eye: Vector, aim: Vector, blocked: Boolean = false): Map<String, Any> {
            val appearance = BuilderPlacementAssistAppearance.forState(blocked)
            val pieces = BuilderPlacementAssistAppearance.edges(target).mapIndexed { index, edge ->
                mapOf("key" to "target-edge-$index", "material" to appearance.material.name.lowercase(),
                    "x" to edge.x + edge.scaleX / 2.0, "y" to edge.y + edge.scaleY / 2.0, "z" to edge.z + edge.scaleZ / 2.0,
                    "width" to edge.scaleX, "height" to edge.scaleY, "depth" to edge.scaleZ)
            } + placed.mapIndexed { index, block ->
                mapOf("key" to "placed-$index", "material" to "oak_planks", "x" to block.x + .5,
                    "y" to block.y + .5, "z" to block.z + .5, "width" to 1, "height" to 1, "depth" to 1)
            }
            pieces.size shouldBe 12 + placed.size
            return mapOf("id" to id, "title" to title,
                "role" to "$role · Офлайн: геометрия и текстуры, без нативного glow и клиентского ввода",
                "pieces" to pieces, "glowRgb" to appearance.glowRgb,
                "selectedCell" to listOf(target.x, target.y, target.z),
                "camera" to mapOf("position" to point(eye), "target" to point(aim), "fovDegrees" to 70))
        }
        val line = BuilderPlacementAssistLock(position(0)).placed(position(1))
        val target = checkNotNull(line.next)
        val eye = Vector(.5, 2.62, .5)
        val offAim = Vector(3.5, -.1, 1.8)
        BuilderPlacementAssistGeometry.inView(eye, offAim.clone().subtract(eye), target) shouldBe true
        BuilderPlacementAssistGeometry.aimDistance(eye, offAim.clone().subtract(eye), target, 4.5) shouldBe null
        val lineBlocks = listOf(position(0), position(1), position(3, -1, 1))
        val states = mutableListOf(state("line-off-aim", "Ряд · восток", "Прицел на фоне: выбранная клетка остаётся в ряду",
            target, lineBlocks, eye, offAim))
        fun plane(id: String, title: String, normal: BlockFace, placed: List<BuilderBlockPos>, eye: Vector, aim: Vector) {
            val lock = BuilderPlacementAssistLock(placed.first(), planeNormal = normal)
            val projection = checkNotNull(BuilderPlacementAssistGeometry.projectPlane(eye, aim.clone().subtract(eye), lock, 4.5))
            val selected = BuilderPlacementAssistGeometry.planeCandidates(projection, lock).first { candidate ->
                candidate !in placed && BuilderPlacementAssistGeometry.cardinalFaces.any { face ->
                    face.modX * normal.modX + face.modY * normal.modY + face.modZ * normal.modZ == 0 &&
                        candidate.relative(face.oppositeFace) in placed
                }
            }
            lock.inPlane(selected) shouldBe true
            BuilderPlacementAssistGeometry.inView(eye, aim.clone().subtract(eye), selected) shouldBe true
            states += state(id, title, "Клетка на фиксированной плоскости примыкает к существующему блоку",
                selected, placed, eye, aim)
        }
        plane("plane-floor", "Плоскость · пол Y 0", BlockFace.UP,
            listOf(position(0), position(1), position(0, 0, 1)), Vector(-.7, 2.62, .3), Vector(1.65, .5, 1.55))
        plane("plane-wall", "Плоскость · стена Z 0", BlockFace.NORTH,
            listOf(position(0), position(1), position(0, 1)), Vector(.5, 2.62, -3.0), Vector(1.65, 1.6, .5))
        states += state("blocked", "Ряд · строительство недоступно", "Та же клетка: янтарная рамка, без размещения",
            target, lineBlocks, eye, offAim, blocked = true)
        val scene = mapOf("states" to states, "palette" to mapOf("ready" to "minecraft:lime_stained_glass",
            "blocked" to "minecraft:orange_stained_glass", "support" to "minecraft:oak_planks"))
        File("build/reports/placement-assist/scene.json").apply {
            parentFile.mkdirs()
            writeText(GsonBuilder().setPrettyPrinting().create().toJson(scene))
        }
    }
})
