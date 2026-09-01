package ru.arc.buildertools

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.bukkit.Color
import org.bukkit.Material
import java.util.UUID

class BuilderConstructionSiteDisplayLayoutTest : StringSpec({
    "construction site encloses the full plan and places its panel on the configured opposite max-Z face" {
        val worldId = UUID.fromString("99999999-8888-7777-6666-555555555555")
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
        val project = BuilderConstructionProjectRecord(
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

        BuilderConstructionSiteDisplayLayout.create(
            project,
            settings.copy(panelFace = BuilderConstructionSitePanelFace.MIN_Z),
        ).panelZ shouldBe (-2.75 plusOrMinus .00001)
    }
})
