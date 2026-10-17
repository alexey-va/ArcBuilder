package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class BuilderBookPlanCalculationTest : FunSpec({
    test("large detached book calculation preserves exact costs and refunds without native server access") {
        val world = UUID.randomUUID()
        val player = UUID.randomUUID()
        val book = BuilderItemAmount("book", "minecraft:written_book", 1)
        val stone = BuilderItemAmount("stone", "minecraft:stone", 1)
        val dirt = BuilderItemAmount("dirt", "minecraft:dirt", 1)
        val cells = (0 until 10_000).map { x ->
            val position = BuilderBlockPos(world, x, 64, 0)
            BuilderBookCapturedCell(position, null, null, false, false,
                BuilderBookCapturedChange(BuilderBlockChange(position, "minecraft:dirt", "minecraft:stone"), stone, dirt))
        }
        val template = BuilderPlan(UUID.randomUUID(), player, BuilderPlanKind.BUILD_BOOK, emptyList(), emptyList(), emptyList(),
            createdAtMillis = 1, expiresAtMillis = 60_001)
        val planned = CompletableFuture.supplyAsync {
            BuilderBookPlanCalculation.assemble(cells, book, null, template, "Builder", "House", cells.first().position, 20_000)
        }.get(10, TimeUnit.SECONDS)
        planned.plan.changes.size shouldBe 10_000
        planned.plan.costs shouldBe listOf(book, stone.copy(amount = 10_000))
        planned.plan.rewards shouldBe listOf(dirt.copy(amount = 10_000))
        planned.project.steps.mapNotNull { it.requiredMaterial }.sumOf { it.amount } shouldBe 10_000
        planned.project.steps.mapNotNull { it.output }.sumOf { it.amount } shouldBe 10_000
        planned.plan.expiresAtMillis - planned.plan.createdAtMillis shouldBe 60_000L
        planned.project.validated(20_000) shouldBe planned.project
    }
})
