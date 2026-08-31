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

    fun mutation(
        amount: BuilderItemAmount,
        insert: Boolean,
        requireNearProject: Boolean = true,
    ): BuilderResourceMutation = BuilderResourceMutation(
        amount = amount,
        insert = insert,
        sources = listOf(
            BuilderResourceInventoryMutation(
                kind = BuilderResourceSourceKind.PLAYER,
                playerId = playerId,
                requireNearProject = requireNearProject,
                before = listOf(if (insert) null else "AAAA"),
                after = listOf(if (insert) "DDDD" else null),
            ),
        ),
    ).validated(playerId)

    class FakePort(
        var blockData: String = "minecraft:dirt",
        var mutable: Boolean = true,
        var inputAvailable: Boolean = true,
        var outputSpace: Boolean = true,
        var outputFailure: Boolean = false,
        var applyFailureAfterMutation: Boolean = false,
    ) : BuilderConstructionProjectPort {
        var removed = 0
        var returned = 0
        var stored = 0
        var applied = 0
        private var inputApplied = false
        private var outputApplied = false

        override fun currentBlockData(position: BuilderBlockPos): String = blockData

        override fun canModify(playerId: UUID, change: BuilderBlockChange): Boolean = mutable

        override fun prepareInput(
            playerId: UUID,
            project: BuilderConstructionProjectRecord,
            input: BuilderItemAmount,
        ): BuilderResourceMutation? = mutation(input, insert = false).takeIf { inputAvailable }

        override fun prepareOutput(
            playerId: UUID,
            project: BuilderConstructionProjectRecord,
            output: BuilderItemAmount,
        ): BuilderResourceMutation? = mutation(output, insert = true).takeIf { outputSpace }

        override fun reconcileResource(
            project: BuilderConstructionProjectRecord,
            mutation: BuilderResourceMutation,
        ): BuilderResourceMutationResult {
            if (mutation.insert) {
                if (outputFailure) return BuilderResourceMutationResult.CONFLICT
                if (!outputApplied) {
                    stored += mutation.amount.amount
                    outputApplied = true
                }
            } else if (!inputApplied) {
                removed += mutation.amount.amount
                inputApplied = true
            }
            return BuilderResourceMutationResult.APPLIED
        }

        override fun rollbackResource(
            project: BuilderConstructionProjectRecord,
            mutation: BuilderResourceMutation,
        ): BuilderResourceMutationResult {
            if (!mutation.insert && inputApplied) {
                returned += mutation.amount.amount
                inputApplied = false
            }
            return BuilderResourceMutationResult.APPLIED
        }

        override fun apply(project: BuilderConstructionProjectRecord, change: BuilderBlockChange) {
            applied += 1
            blockData = change.afterBlockData
            if (applyFailureAfterMutation) error("CoreProtect failed after the block changed")
        }
    }

    fun advanceToWaitingOutput(port: FakePort): BuilderConstructionProjectRecord {
        val inputPrepared = checkNotNull(BuilderConstructionProjectController.tick(active(), createdAt + 2, port))
        inputPrepared.state shouldBe BuilderConstructionProjectState.INPUT_PREPARED
        port.removed shouldBe 0
        port.applied shouldBe 0
        val worldPrepared = checkNotNull(
            BuilderConstructionProjectController.tick(inputPrepared, createdAt + 3, port),
        )
        worldPrepared.state shouldBe BuilderConstructionProjectState.WORLD_PREPARED
        port.removed shouldBe 1
        port.applied shouldBe 0
        return checkNotNull(BuilderConstructionProjectController.tick(worldPrepared, createdAt + 4, port)).also {
            it.state shouldBe BuilderConstructionProjectState.WAITING_OUTPUT_SPACE
        }
    }

    test("missing material pauses before mutating the block") {
        val port = FakePort(inputAvailable = false)

        val result = BuilderConstructionProjectController.tick(active(), createdAt + 2, port)!!

        result.state shouldBe BuilderConstructionProjectState.WAITING_MATERIALS
        result.cursor shouldBe 0
        port.applied shouldBe 0
        port.removed shouldBe 0

        BuilderConstructionProjectController.tick(result, createdAt + 3, port) shouldBe null
        port.inputAvailable = true
        BuilderConstructionProjectController.tick(result, createdAt + 4, port)?.state shouldBe
            BuilderConstructionProjectState.INPUT_PREPARED
    }

    test("successful step durably marks delivery before storing replacement and completing") {
        val port = FakePort()

        val waiting = advanceToWaitingOutput(port)
        port.stored shouldBe 0

        val delivering = BuilderConstructionProjectController.tick(waiting, createdAt + 5, port)!!
        delivering.state shouldBe BuilderConstructionProjectState.DELIVERING_OUTPUT
        port.stored shouldBe 0

        val result = BuilderConstructionProjectController.tick(delivering, createdAt + 6, port)

        result?.state shouldBe BuilderConstructionProjectState.COMPLETED
        result?.cursor shouldBe 1
        port.removed shouldBe 1
        port.stored shouldBe 1
        port.applied shouldBe 1
    }

    test("full output storage persists the refund before advancing the cursor") {
        val port = FakePort(outputSpace = false)
        val waiting = advanceToWaitingOutput(port)

        waiting.state shouldBe BuilderConstructionProjectState.WAITING_OUTPUT_SPACE
        waiting.cursor shouldBe 0
        waiting.pendingOutput shouldBe dirt
        port.blockData shouldBe "minecraft:stone"

        BuilderConstructionProjectController.tick(waiting, createdAt + 5, port) shouldBe null
        port.outputSpace = true
        val delivering = BuilderConstructionProjectController.tick(waiting, createdAt + 6, port)!!
        val completed = BuilderConstructionProjectController.tick(delivering, createdAt + 7, port)
        completed?.state shouldBe BuilderConstructionProjectState.COMPLETED
        completed?.cursor shouldBe 1
        port.removed shouldBe 1
        port.stored shouldBe 1
        port.applied shouldBe 1
    }

    test("failed or ambiguous output delivery never retries from the delivering state") {
        val port = FakePort()
        val waiting = advanceToWaitingOutput(port)
        val delivering = BuilderConstructionProjectController.tick(waiting, createdAt + 5, port)!!
        port.outputFailure = true

        val recovery = BuilderConstructionProjectController.tick(delivering, createdAt + 6, port)

        recovery?.state shouldBe BuilderConstructionProjectState.RECOVERY_REQUIRED
        port.stored shouldBe 0
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

    test("world-prepared replay observes an already applied block without applying or debiting twice") {
        val port = FakePort()
        val inputPrepared = checkNotNull(BuilderConstructionProjectController.tick(active(), createdAt + 2, port))
        val worldPrepared = checkNotNull(
            BuilderConstructionProjectController.tick(inputPrepared, createdAt + 3, port),
        )
        port.blockData = change.afterBlockData

        val result = BuilderConstructionProjectController.tick(worldPrepared, createdAt + 4, port)

        result?.state shouldBe BuilderConstructionProjectState.WAITING_OUTPUT_SPACE
        port.removed shouldBe 1
        port.applied shouldBe 0
        port.returned shouldBe 0
    }

    test("post-mutation logging failure never refunds input when the world proves the change landed") {
        val port = FakePort(applyFailureAfterMutation = true)
        val inputPrepared = checkNotNull(BuilderConstructionProjectController.tick(active(), createdAt + 2, port))
        val worldPrepared = checkNotNull(
            BuilderConstructionProjectController.tick(inputPrepared, createdAt + 3, port),
        )

        val result = BuilderConstructionProjectController.tick(worldPrepared, createdAt + 4, port)

        result?.state shouldBe BuilderConstructionProjectState.WAITING_OUTPUT_SPACE
        port.applied shouldBe 1
        port.returned shouldBe 0
    }

    test("permission revocation after a durable debit compensates input and stops for review") {
        val port = FakePort()
        val inputPrepared = checkNotNull(BuilderConstructionProjectController.tick(active(), createdAt + 2, port))
        val worldPrepared = checkNotNull(
            BuilderConstructionProjectController.tick(inputPrepared, createdAt + 3, port),
        )
        port.mutable = false

        val result = BuilderConstructionProjectController.tick(worldPrepared, createdAt + 4, port)

        result?.state shouldBe BuilderConstructionProjectState.RECOVERY_REQUIRED
        port.removed shouldBe 1
        port.returned shouldBe 1
        port.applied shouldBe 0
    }

    test("an input-prepared receipt loaded after a crash resumes idempotently without a second debit") {
        val port = FakePort()
        val inputPrepared = checkNotNull(BuilderConstructionProjectController.tick(active(), createdAt + 2, port))

        val normalized = BuilderConstructionRecoveryPolicy.normalizeLoaded(inputPrepared, createdAt + 3)
        val worldPrepared = BuilderConstructionProjectController.tick(normalized, createdAt + 4, port)

        normalized.state shouldBe BuilderConstructionProjectState.INPUT_PREPARED
        worldPrepared?.state shouldBe BuilderConstructionProjectState.WORLD_PREPARED
        port.removed shouldBe 1
        port.applied shouldBe 0
    }

    test("confirmed persistence rejection replays the receipt without debiting twice") {
        val port = FakePort()
        val inputPrepared = checkNotNull(BuilderConstructionProjectController.tick(active(), createdAt + 2, port))
        val worldPrepared = checkNotNull(
            BuilderConstructionProjectController.tick(inputPrepared, createdAt + 3, port),
        )

        BuilderConstructionTransitionFailurePolicy.requiresRecovery(inputPrepared, worldPrepared) shouldBe true
        BuilderConstructionProjectController.tick(inputPrepared, createdAt + 4, port)?.state shouldBe
            BuilderConstructionProjectState.WORLD_PREPARED
        port.removed shouldBe 1
    }

    test("confirmed persistence rejection requires recovery only after a value mutation") {
        val prepared = active().copy(state = BuilderConstructionProjectState.PREPARED, updatedAtMillis = createdAt).validated()
        val preparedWithReceipt = prepared.activationPrepared(
            mutation(book, insert = false, requireNearProject = false),
        )
        val activeWithReceipt = preparedWithReceipt.activated(createdAt + 1)
        val flowPort = FakePort()
        val inputPrepared = checkNotNull(
            BuilderConstructionProjectController.tick(activeWithReceipt, createdAt + 2, flowPort),
        )
        val worldPrepared = checkNotNull(
            BuilderConstructionProjectController.tick(inputPrepared, createdAt + 3, flowPort),
        )
        val waitingOutput = checkNotNull(
            BuilderConstructionProjectController.tick(worldPrepared, createdAt + 4, flowPort),
        )
        val delivering = checkNotNull(
            BuilderConstructionProjectController.tick(waitingOutput, createdAt + 5, flowPort),
        )
        val advanced = BuilderConstructionProjectController.tick(delivering, createdAt + 6, flowPort)

        BuilderConstructionTransitionFailurePolicy.requiresRecovery(
            preparedWithReceipt,
            preparedWithReceipt.activated(createdAt + 1),
        ) shouldBe true
        BuilderConstructionTransitionFailurePolicy.requiresRecovery(inputPrepared, worldPrepared) shouldBe true
        BuilderConstructionTransitionFailurePolicy.requiresRecovery(worldPrepared, waitingOutput) shouldBe true
        BuilderConstructionTransitionFailurePolicy.requiresRecovery(delivering, checkNotNull(advanced)) shouldBe true

        BuilderConstructionTransitionFailurePolicy.requiresRecovery(
            inputPrepared,
            inputPrepared.waitingForMaterials(createdAt + 7),
        ) shouldBe false
        BuilderConstructionTransitionFailurePolicy.requiresRecovery(
            delivering,
            delivering.waitingForOutput(dirt, createdAt + 8),
        ) shouldBe false
    }
})
