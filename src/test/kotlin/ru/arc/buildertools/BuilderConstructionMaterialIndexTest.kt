package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class BuilderConstructionMaterialIndexTest : FunSpec({
    test("every suffix retains exact metadata quantities and the existing stable material ordering") {
        val firstStone = BuilderItemAmount("stone-first", "minecraft:stone", 2)
        val secondStone = BuilderItemAmount("stone-second", "minecraft:stone", 3)
        val dirt = BuilderItemAmount("dirt-exact", "minecraft:dirt", 7)
        val amounts = listOf(firstStone, dirt, secondStone, null, firstStone.copy(amount = 5), secondStone.copy(amount = 4), dirt)
        val steps = amounts.mapIndexed { index, amount -> materialStep(index, amount) }
        val indexed = BuilderConstructionMaterialIndex.build(steps)

        for (cursor in 0..steps.size) {
            val expected = steps.drop(cursor).mapNotNull(BuilderConstructionStep::requiredMaterial)
                .groupBy { it.itemBase64 to it.materialKey }.values
                .map { grouped -> grouped.first().copy(amount = grouped.sumOf(BuilderItemAmount::amount)).validated() }
                .sortedBy(BuilderItemAmount::materialKey)
            indexed.remaining(cursor) shouldBe expected
        }
        indexed.remaining(2).map(BuilderItemAmount::itemBase64) shouldBe listOf("dirt-exact", "stone-second", "stone-first")
    }

    test("a 100000-step source is scanned on a worker only once and suffix queries never revisit it") {
        val mainThread = Thread.currentThread()
        var reads = 0
        val steps = object : AbstractList<BuilderConstructionStep>() {
            override val size = 100_000
            override fun get(index: Int): BuilderConstructionStep {
                check(Thread.currentThread() !== mainThread) { "Full step scan ran on the server thread" }
                reads++
                return materialStep(index, BuilderItemAmount("stone-exact", "minecraft:stone", 1))
            }
        }
        val tasks = QueuedBuilderPlanningTasks()
        val pending = tasks.compute { BuilderConstructionMaterialIndex.build(steps) }
        reads shouldBe 0
        tasks.runComputations()
        val indexed = pending.awaitBuilderPlanning()
        reads shouldBe 100_000
        tasks.workerRuns shouldBe 1
        indexed.remaining(0).single().amount shouldBe 100_000
        indexed.remaining(128).single().amount shouldBe 99_872
        indexed.remaining(99_999).single().amount shouldBe 1
        indexed.remaining(100_000) shouldBe emptyList()
        reads shouldBe 100_000
    }
})

private val materialWorldId = UUID.fromString("11111111-2222-3333-4444-555555555555")

private fun materialStep(index: Int, material: BuilderItemAmount?): BuilderConstructionStep = BuilderConstructionStep(
    BuilderBlockChange(BuilderBlockPos(materialWorldId, index, 64, 0), "minecraft:air", "minecraft:stone"),
    requiredMaterial = material,
    output = null,
)
