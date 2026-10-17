package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class BuilderPlanPreviewGeometryTest : FunSpec({
    test("large plan geometry is detached on a worker with exact preview coordinates bounds and index order") {
        val worldId = UUID.randomUUID()
        val mainThread = Thread.currentThread()
        var reads = 0
        val changes = object : AbstractList<BuilderBlockChange>() {
            override val size = 100_000
            override fun get(index: Int): BuilderBlockChange {
                check(Thread.currentThread() !== mainThread)
                reads++
                return BuilderBlockChange(BuilderBlockPos(worldId, index, 64, -10), "minecraft:air", "minecraft:stone")
            }
        }
        val tasks = QueuedBuilderPlanningTasks()
        val pending = tasks.compute { BuilderPlanPreviewGeometry.create(changes) }
        reads shouldBe 0
        tasks.runComputations()
        val model = pending.awaitBuilderPlanning()
        reads shouldBe 100_000
        model.points.points.size shouldBe 100_000
        model.points.points.first() shouldBe BuilderPreviewPoint(.5, 64.5, -9.5)
        model.points.points.last() shouldBe BuilderPreviewPoint(99_999.5, 64.5, -9.5)
        model.points.indices shouldBe (0 until 100_000).toList()
        model.bounds shouldBe BuilderDisplayGeometry.bounds(listOf(
            BuilderBlockPos(worldId, 0, 64, -10),
            BuilderBlockPos(worldId, 99_999, 64, -10),
        ))
        reads shouldBe 100_000
    }
})
