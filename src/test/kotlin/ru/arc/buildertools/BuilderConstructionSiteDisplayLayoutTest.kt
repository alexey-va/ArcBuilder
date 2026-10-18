package ru.arc.buildertools

import io.mockk.mockk
import io.mockk.verify
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.entity.Display
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.util.BoundingBox
import net.kyori.adventure.text.Component
import ru.arc.paper.scene.SceneAudience
import java.util.UUID

class BuilderConstructionSiteDisplayLayoutTest : StringSpec({
    "construction site encloses the full plan and places its panel on every fixed vertical face" {
        val project = project()
        val worldId = project.steps.first().change.position.worldId
        val settings = BuilderConstructionSiteDisplaySettings(
            enabled = true,
            outlineEnabled = true,
            outlineMaterial = Material.ORANGE_STAINED_GLASS,
            outlineThickness = .08f,
            glowColor = Color.fromRGB(0xFFB142),
            panelEnabled = true,
            panelFace = BuilderConstructionSitePanelFace.MAX_Z,
            panelHeightOffset = 2.5,
            panelFrontOffset = .75,
            panelInteractionWidth = 3f,
            panelInteractionHeight = 1.5f,
            panelLineWidth = 180,
            panelBackgroundColor = Color.fromARGB(0xB21C2328.toInt()),
            viewRange = 64.0,
            defaultLocale = "ru",
        )

        val model = BuilderConstructionSiteDisplayLayout.create(project, settings)
        val anchored = project.copy(siteAnchor = BuilderBlockPos(worldId, 100, 64, 200))
        val restored = com.google.gson.Gson().fromJson(com.google.gson.Gson().toJson(anchored), BuilderConstructionProjectRecord::class.java).validated()
        restored.siteAnchor shouldBe anchored.siteAnchor
        val front = BuilderConstructionSiteDisplayLayout.nearestFace(restored, 100.5, 205.0)
        front shouldBe BuilderConstructionSitePanelFace.MAX_Z
        val byOrigin = BuilderConstructionSiteDisplayLayout.create(restored.copy(sitePanelFace = front), settings)
        byOrigin.edges shouldBe model.edges
        byOrigin.panelX shouldBe (100.5 plusOrMinus .00001)
        byOrigin.panelY shouldBe (66.5 plusOrMinus .00001)
        byOrigin.panelZ shouldBe (201.75 plusOrMinus .00001)
        for (face in BuilderConstructionSitePanelFace.entries) {
            val placed = BuilderConstructionSiteDisplayLayout.create(restored.copy(sitePanelFace = face), settings)
            kotlin.math.abs(placed.panelX - 100.5).let { require(it <= 1.25) }
            kotlin.math.abs(placed.panelZ - 200.5).let { require(it <= 1.25) }
            placed.panelY shouldBe (66.5 plusOrMinus .00001)
            placed.panelYaw shouldBe face.yaw
        }


        model.worldId shouldBe worldId
        model.edges shouldHaveSize 12
        model.edges.count { it.scaleX > .08f } shouldBe 4
        model.edges.count { it.scaleY > .08f } shouldBe 4
        model.edges.count { it.scaleZ > .08f } shouldBe 4
        model.edges.filter { it.scaleX > .08f }.map(BuilderDisplayEdge::scaleX).distinct() shouldBe listOf(8f)
        model.edges.filter { it.scaleY > .08f }.map(BuilderDisplayEdge::scaleY).distinct() shouldBe listOf(8f)
        model.edges.filter { it.scaleZ > .08f }.map(BuilderDisplayEdge::scaleZ).distinct() shouldBe listOf(11f)
        model.panelX shouldBe (1.0 plusOrMinus .00001)
        model.panelY shouldBe (46.5 plusOrMinus .00001)
        model.panelZ shouldBe (9.75 plusOrMinus .00001)
        model.panelYaw shouldBe 0f

        BuilderConstructionSiteDisplayLayout.create(
            project,
            settings.copy(panelFace = BuilderConstructionSitePanelFace.MIN_Z),
        ).also { minZ ->
            minZ.panelX shouldBe (1.0 plusOrMinus .00001)
            minZ.panelZ shouldBe (-2.75 plusOrMinus .00001)
            minZ.panelYaw shouldBe 180f
        }
        BuilderConstructionSiteDisplayLayout.create(
            project.copy(sitePanelFace = BuilderConstructionSitePanelFace.MIN_X),
            settings,
        ).also { minX ->
            minX.panelX shouldBe (-3.75 plusOrMinus .00001)
            minX.panelZ shouldBe (3.5 plusOrMinus .00001)
            minX.panelYaw shouldBe 90f
        }
        BuilderConstructionSiteDisplayLayout.create(
            project.copy(sitePanelFace = BuilderConstructionSitePanelFace.MAX_X),
            settings,
        ).also { maxX ->
            maxX.panelX shouldBe (5.75 plusOrMinus .00001)
            maxX.panelZ shouldBe (3.5 plusOrMinus .00001)
            maxX.panelYaw shouldBe 270f
        }
    }

    "construction panel chooses the face whose center is nearest to the player" {
        val project = project()

        BuilderConstructionSiteDisplayLayout.nearestFace(project, -20.0, 3.5) shouldBe
            BuilderConstructionSitePanelFace.MIN_X
        BuilderConstructionSiteDisplayLayout.nearestFace(project, 20.0, 3.5) shouldBe
            BuilderConstructionSitePanelFace.MAX_X
        BuilderConstructionSiteDisplayLayout.nearestFace(project, 1.0, -20.0) shouldBe
            BuilderConstructionSitePanelFace.MIN_Z
        BuilderConstructionSiteDisplayLayout.nearestFace(project, 1.0, 20.0) shouldBe
            BuilderConstructionSitePanelFace.MAX_Z
    }

    "preview panel uses the nearest face of arbitrary preview blocks" {
        val worldId = UUID.randomUUID()
        val positions = listOf(
            BuilderBlockPos(worldId, 10, 64, 20),
            BuilderBlockPos(worldId, 14, 68, 26),
        )

        val face = BuilderConstructionSiteDisplayLayout.nearestFace(positions, 12.0, 10.0)
        val panel = BuilderConstructionSiteDisplayLayout.panelPlacement(
            positions = positions,
            face = face,
            heightOffset = 2.25,
            frontOffset = 0.4,
        )

        face shouldBe BuilderConstructionSitePanelFace.MIN_Z
        panel.x shouldBe (12.5 plusOrMinus .00001)
        panel.y shouldBe (66.25 plusOrMinus .00001)
        panel.z shouldBe (19.6 plusOrMinus .00001)
        panel.yaw shouldBe 180f
    }

    "an offset origin places the full plaque outside wide ghost bounds for every rotated face" {
        val worldId = UUID.randomUUID()
        val world = mockk<World>(relaxed = true)
        val origin = BuilderBlockPos(worldId, 100, 64, 200)
        val width = BuilderWorldPlaque.visualWidth(180)
        var relative = listOf(-9 to -4, -9 to 12, 17 to -4, 17 to 12)
        repeat(4) {
            val occupied = relative.map { (x, z) -> BuilderBlockPos(worldId, origin.x + x, 60, origin.z + z) }
            val ghost = BoundingBox(
                occupied.minOf { it.x }.toDouble(), 60.0, occupied.minOf { it.z }.toDouble(),
                occupied.maxOf { it.x } + 1.0, 75.0, occupied.maxOf { it.z } + 1.0,
            )
            for (face in BuilderConstructionSitePanelFace.entries) {
                val panel = BuilderConstructionSiteDisplayLayout.panelPlacement(
                    listOf(origin), face, 2.6, .4, occupiedPositions = occupied, panelWidth = width,
                )
                val volume = BuilderWorldPlaque.visualFootprint(
                    Location(world, panel.x, panel.y, panel.z, panel.yaw, 0f),
                    Component.text("Настроить постройку\nБерёзовый коттедж\nПКМ — открыть меню"), 180,
                )
                volume.overlaps(ghost) shouldBe false
                panel.y shouldBe (66.6 plusOrMinus 1e-8)
                panel.yaw shouldBe face.yaw
                when (face) {
                    BuilderConstructionSitePanelFace.MIN_X -> panel.x shouldBe (ghost.minX - .4 plusOrMinus 1e-8)
                    BuilderConstructionSitePanelFace.MAX_X -> panel.x shouldBe (ghost.maxX + .4 plusOrMinus 1e-8)
                    BuilderConstructionSitePanelFace.MIN_Z -> panel.z shouldBe (ghost.minZ - .4 plusOrMinus 1e-8)
                    BuilderConstructionSitePanelFace.MAX_Z -> panel.z shouldBe (ghost.maxZ + .4 plusOrMinus 1e-8)
                }
            }
            relative = relative.map { (x, z) -> -z to x }
        }
    }

    "a plaque corner near the preview is cleared even when its origin lies beside the bounds" {
        val worldId = UUID.randomUUID()
        val occupied = listOf(BuilderBlockPos(worldId, 0, 64, 0), BuilderBlockPos(worldId, 6, 70, 9))
        val panel = BuilderConstructionSiteDisplayLayout.panelPlacement(
            listOf(BuilderBlockPos(worldId, 10, 64, 4)), BuilderConstructionSitePanelFace.MAX_Z, 2.6, .4,
            occupiedPositions = occupied, panelWidth = BuilderWorldPlaque.visualWidth(180),
        )
        panel.x shouldBe 10.5
        panel.z shouldBe (10.4 plusOrMinus 1e-8)
    }

    "construction panel orientation is fixed and applies its persisted face yaw" {
        val packet = BuilderPlaqueDisplays()
        BuilderWorldPlaque(packet.owner, unblockedPlaqueSpace(), plaqueStyle(), SceneAudience.Public()).use { plaque ->
            plaque.update(Location(mockk<World>(relaxed = true), 1.0, 66.25, 2.0, 90f, 0f), Component.text("Стройплощадка"))
            packet.texts.map { it.location.yaw } shouldBe listOf(90f, 270f)
            packet.texts.forEach { record ->
                verify(exactly = 1) { record.display.billboard = Display.Billboard.FIXED }
                record.transform.scale shouldBe org.joml.Vector3f(2f)
                (record.transform.translation.z > 0f) shouldBe true
            }
        }
    }
})

private fun project(
    worldId: UUID = UUID.fromString("99999999-8888-7777-6666-555555555555"),
): BuilderConstructionProjectRecord {
    val projectId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    val playerId = UUID.fromString("11111111-2222-3333-4444-555555555555")
    val now = 1_800_000_000_000L
    val positions = listOf(
        BuilderBlockPos(worldId, -3, 44, 8),
        BuilderBlockPos(worldId, 4, 51, -2),
        BuilderBlockPos(worldId, 1, 46, 3),
    )
    val changes = positions.map { position ->
        BuilderBlockChange(position, "minecraft:water", "minecraft:stone[waterlogged=false]").let { change ->
            BuilderConstructionStep(change, requiredMaterial = null, output = null)
        }
    }
    val book = BuilderItemAmount("BBBB", "minecraft:book", 1)
    val plan = BuilderPlan(
        id = projectId,
        playerId = playerId,
        kind = BuilderPlanKind.BUILD_BOOK,
        changes = changes.map(BuilderConstructionStep::change),
        costs = listOf(book),
        rewards = emptyList(),
        createdAtMillis = now,
        expiresAtMillis = now + 60_000,
    )
    return BuilderConstructionProjectRecord(
        projectId = projectId,
        playerId = playerId,
        playerName = "Builder",
        projectTitle = "Подводный дом",
        plan = plan,
        steps = changes,
        bookCost = book,
        state = BuilderConstructionProjectState.ACTIVE,
        cursor = 0,
        createdAtMillis = now,
        updatedAtMillis = now,
    ).validated()
}
