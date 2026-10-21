package ru.arc.buildertools

import com.google.gson.GsonBuilder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.util.UUID

/** Focused source export consumed by the existing location-atelier renderer. */
class BuilderPlacementAssistPreviewTest : FunSpec({
    test("export both next-cell frame states from production geometry") {
        val target = BuilderBlockPos(UUID(0L, 1L), 2, 0, 0)
        val states = listOf(false, true).map { blocked ->
            val appearance = BuilderPlacementAssistAppearance.forState(blocked)
            val pieces = BuilderPlacementAssistAppearance.edges(target).mapIndexed { index, edge ->
                mapOf("key" to "target-edge-$index", "material" to appearance.material.name.lowercase(),
                    "x" to edge.x + edge.scaleX / 2.0, "y" to edge.y + edge.scaleY / 2.0, "z" to edge.z + edge.scaleZ / 2.0,
                    "width" to edge.scaleX, "height" to edge.scaleY, "depth" to edge.scaleZ)
            } + (0..1).map { x ->
                mapOf("key" to "placed-$x", "material" to "oak_planks", "x" to x + .5, "y" to .5, "z" to .5,
                    "width" to 1, "height" to 1, "depth" to 1)
            }
            pieces.size shouldBe 14
            mapOf("id" to if (blocked) "blocked" else "ready", "title" to if (blocked) "Клетка недоступна" else "Продолжить ряд",
                "role" to "Офлайн: геометрия из кода, ванильные текстуры; без нативного glow и клиентского ввода",
                "pieces" to pieces, "glowRgb" to appearance.glowRgb,
                "camera" to mapOf("position" to listOf(.5, 2.62, .5), "target" to listOf(2.5, .5, .5), "fovDegrees" to 70))
        }
        val scene = mapOf("states" to states, "palette" to mapOf("ready" to "minecraft:lime_stained_glass",
            "blocked" to "minecraft:orange_stained_glass", "support" to "minecraft:oak_planks"))
        File("build/reports/placement-assist/scene.json").apply {
            parentFile.mkdirs()
            writeText(GsonBuilder().setPrettyPrinting().create().toJson(scene))
        }
    }
})
