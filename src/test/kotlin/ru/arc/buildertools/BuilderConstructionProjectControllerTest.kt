package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class BuilderConstructionProjectControllerTest : FunSpec({
    val projectId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    val playerId = UUID.fromString("11111111-2222-3333-4444-555555555555")
    val worldId = UUID.fromString("99999999-8888-7777-6666-555555555555")
    val createdAt = 1_800_000_000_000L
    val book = BuilderItemAmount("BBBB", "minecraft:book", 1)
    val stone = BuilderItemAmount("AAAA", "minecraft:stone", 1)
    val dirt = BuilderItemAmount("DDDD", "minecraft:dirt", 1)
    val change = BuilderBlockChange(
        BuilderBlockPos(worldId, 10, 64, 10),
        "minecraft:dirt",
        "minecraft:stone",
    )
    val step = BuilderConstructionStep(change, requiredMaterial = stone, output = dirt)
    val plan = BuilderPlan(
        id = projectId,
        playerId = playerId,
        kind = BuilderPlanKind.BUILD_BOOK,
        changes = listOf(change),
        costs = listOf(book, stone),
        rewards = listOf(dirt),
        createdAtMillis = createdAt,
        expiresAtMillis = createdAt + 60_000,
    )

    fun active() = BuilderConstructionProjectRecord(
        projectId = projectId,
        playerId = playerId,
        playerName = "Builder",
        plan = plan,
        steps = listOf(step),
        bookCost = book,
        state = BuilderConstructionProjectState.PREPARED,
        cursor = 0,
        createdAtMillis = createdAt,
        updatedAtMillis = createdAt,
    ).validated().activated(createdAt + 1)

    class FakePort(
        var blockData: String = "minecraft:dirt",
        var mutable: Boolean = true,
        var inputAvailable: Boolean = true,
        var outputSpace: Boolean = true,
    ) : BuilderConstructionProjectPort {
        var removed = 0
        var returned = 0
        var stored = 0
        var applied = 0

        override fun currentBlockData(position: BuilderBlockPos): String = blockData

        override fun canModify(playerId: UUID, change: BuilderBlockChange): Boolean = mutable

        override fun removeInput(
            playerId: UUID,
            project: BuilderConstructionProjectRecord,
            input: BuilderItemAmount,
        ): Boolean = inputAvailable.also { if (it) removed += input.amount }

        override fun returnInput(
            playerId: UUID,
            project: BuilderConstructionProjectRecord,
            input: BuilderItemAmount,
        ): Boolean = true.also { returned += input.amount }

        override fun storeOutput(
            playerId: UUID,
            project: BuilderConstructionProjectRecord,
            output: BuilderItemAmount,
        ): Boolean = outputSpace.also { if (it) stored += output.amount }

        override fun apply(change: BuilderBlockChange) {
            applied += 1
            blockData = change.afterBlockData
        }
    }

    test("missing material pauses before mutating the block") {
        val port = FakePort(inputAvailable = false)

        val result = BuilderConstructionProjectController.tick(active(), createdAt + 2, port)

        result?.state shouldBe BuilderConstructionProjectState.WAITING_MATERIALS
        result?.cursor shouldBe 0
        port.applied shouldBe 0
        port.removed shouldBe 0
    }

    test("successful step consumes input stores replacement and completes") {
        val port = FakePort()

        val result = BuilderConstructionProjectController.tick(active(), createdAt + 2, port)

        result?.state shouldBe BuilderConstructionProjectState.COMPLETED
        result?.cursor shouldBe 1
        port.removed shouldBe 1
        port.stored shouldBe 1
        port.applied shouldBe 1
    }

    test("full output storage persists the refund before advancing the cursor") {
        val port = FakePort(outputSpace = false)
        val waiting = BuilderConstructionProjectController.tick(active(), createdAt + 2, port)!!

        waiting.state shouldBe BuilderConstructionProjectState.WAITING_OUTPUT_SPACE
        waiting.cursor shouldBe 0
        waiting.pendingOutput shouldBe dirt
        port.blockData shouldBe "minecraft:stone"

        port.outputSpace = true
        val completed = BuilderConstructionProjectController.tick(waiting, createdAt + 3, port)
        completed?.state shouldBe BuilderConstructionProjectState.COMPLETED
        completed?.cursor shouldBe 1
        port.removed shouldBe 1
        port.stored shouldBe 1
        port.applied shouldBe 1
    }

    test("protection denial and unexpected world state enter recovery without touching resources") {
        val denied = FakePort(mutable = false)
        val deniedResult = BuilderConstructionProjectController.tick(active(), createdAt + 2, denied)
        deniedResult?.state shouldBe BuilderConstructionProjectState.RECOVERY_REQUIRED
        denied.removed shouldBe 0

        val drifted = FakePort(blockData = "minecraft:oak_planks")
        val driftedResult = BuilderConstructionProjectController.tick(active(), createdAt + 2, drifted)
        driftedResult?.state shouldBe BuilderConstructionProjectState.RECOVERY_REQUIRED
        drifted.applied shouldBe 0
    }
})
