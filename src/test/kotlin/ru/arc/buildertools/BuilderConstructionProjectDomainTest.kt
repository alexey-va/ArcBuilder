package ru.arc.buildertools

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class BuilderConstructionProjectDomainTest : FunSpec({
    val projectId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    val playerId = UUID.fromString("11111111-2222-3333-4444-555555555555")
    val worldId = UUID.fromString("99999999-8888-7777-6666-555555555555")
    val createdAt = 1_800_000_000_000L

    fun item(material: String, amount: Int = 1, payload: String = "AAAA") = BuilderItemAmount(
        itemBase64 = payload,
        materialKey = "minecraft:$material",
        amount = amount,
    )

    val book = item("book", payload = "BBBB")
    val stone = item("stone")
    val slab = item("oak_slab", amount = 2, payload = "CCCC")
    val refund = item("dirt", payload = "DDDD")
    val firstChange = BuilderBlockChange(
        position = BuilderBlockPos(worldId, 10, 64, 10),
        beforeBlockData = "minecraft:air",
        afterBlockData = "minecraft:stone",
    )
    val secondChange = BuilderBlockChange(
        position = BuilderBlockPos(worldId, 10, 65, 10),
        beforeBlockData = "minecraft:dirt",
        afterBlockData = "minecraft:oak_slab[type=double,waterlogged=false]",
    )
    val steps = listOf(
        BuilderConstructionStep(firstChange, requiredMaterial = stone, output = null),
        BuilderConstructionStep(secondChange, requiredMaterial = slab, output = refund),
    )
    val plan = BuilderPlan(
        id = projectId,
        playerId = playerId,
        kind = BuilderPlanKind.BUILD_BOOK,
        changes = steps.map(BuilderConstructionStep::change),
        costs = listOf(book, stone, slab),
        rewards = listOf(refund),
        createdAtMillis = createdAt,
        expiresAtMillis = createdAt + 60_000,
    )

    fun prepared() = BuilderConstructionProjectRecord(
        projectId = projectId,
        playerId = playerId,
        playerName = "Builder",
        plan = plan,
        steps = steps,
        bookCost = book,
        state = BuilderConstructionProjectState.PREPARED,
        cursor = 0,
        createdAtMillis = createdAt,
        updatedAtMillis = createdAt,
    ).validated()

    fun mutation(amount: BuilderItemAmount, insert: Boolean) = BuilderResourceMutation(
        amount = amount,
        insert = insert,
        sources = listOf(
            BuilderResourceInventoryMutation(
                kind = BuilderResourceSourceKind.PLAYER,
                playerId = playerId,
                requireNearProject = true,
                before = listOf(if (insert) null else "AAAA"),
                after = listOf(if (insert) "DDDD" else null),
            ),
        ),
    ).validated(playerId)

    test("project advances one durable step at a time and completes on the final step") {
        val active = prepared().activated(createdAt + 1)
        val afterFirst = active.advanced(createdAt + 2)
        val completed = afterFirst.advanced(createdAt + 3)

        active.state shouldBe BuilderConstructionProjectState.ACTIVE
        afterFirst.cursor shouldBe 1
        afterFirst.state shouldBe BuilderConstructionProjectState.ACTIVE
        completed.cursor shouldBe 2
        completed.state shouldBe BuilderConstructionProjectState.COMPLETED
        completed.completedAtMillis shouldBe createdAt + 3
        completed.terminal shouldBe true
    }

    test("completed project records finalization exactly once without changing completion time") {
        val completed = prepared()
            .activated(createdAt + 1)
            .advanced(createdAt + 2)
            .advanced(createdAt + 3)
        val finalized = completed.completionFinalized(createdAt + 4)

        completed.completionFinalizedAtMillis shouldBe null
        finalized.completionFinalizedAtMillis shouldBe createdAt + 4
        finalized.completedAtMillis shouldBe createdAt + 3
        finalized.updatedAtMillis shouldBe createdAt + 3
        finalized.state shouldBe BuilderConstructionProjectState.COMPLETED

        shouldThrow<IllegalArgumentException> { finalized.completionFinalized(createdAt + 5) }
        shouldThrow<IllegalArgumentException> {
            completed.copy(completionFinalizedAtMillis = createdAt + 2).validated()
        }
        shouldThrow<IllegalArgumentException> {
            prepared().activated(createdAt + 1).copy(completionFinalizedAtMillis = createdAt + 2).validated()
        }
    }

    test("missing input and full output storage pause without losing the current step") {
        val active = prepared().activated(createdAt + 1)
        val waitingMaterial = active.waitingForMaterials(createdAt + 2)
        val outputPending = active.outputPending(refund, createdAt + 3)
        val waitingOutput = outputPending.waitingForOutput(createdAt + 4)
        val delivering = waitingOutput.deliveringOutput(mutation(refund, insert = true), createdAt + 5)
        val delivered = delivering.outputDelivered(createdAt + 6)

        waitingMaterial.cursor shouldBe 0
        waitingMaterial.state shouldBe BuilderConstructionProjectState.WAITING_MATERIALS
        shouldThrow<IllegalArgumentException> { waitingMaterial.activated(createdAt + 3) }
        waitingOutput.cursor shouldBe 0
        waitingOutput.pendingOutput shouldBe refund
        delivering.state shouldBe BuilderConstructionProjectState.DELIVERING_OUTPUT
        delivering.pendingOutput shouldBe refund
        delivered.cursor shouldBe 1
        delivered.pendingOutput shouldBe null
        delivered.state shouldBe BuilderConstructionProjectState.ACTIVE
    }

    test("project validates exact step exchange against the preview plan") {
        prepared().validated() shouldBe prepared()

        shouldThrow<IllegalArgumentException> {
            prepared().copy(steps = steps.dropLast(1)).validated()
        }
        shouldThrow<IllegalArgumentException> {
            prepared().copy(bookCost = book.copy(amount = 2)).validated()
        }
        shouldThrow<IllegalArgumentException> {
            prepared().copy(plan = plan.copy(costs = listOf(book, stone))).validated()
        }
        shouldThrow<IllegalArgumentException> {
            prepared().copy(plan = plan.copy(kind = BuilderPlanKind.FILL)).validated()
        }
    }

    test("transition rules reject cursor regression identity changes and invalid pending output") {
        val active = prepared().activated(createdAt + 1)
        val afterFirst = active.advanced(createdAt + 2)

        shouldThrow<IllegalArgumentException> {
            BuilderConstructionProjectTransitionRules.validate(afterFirst, active.copy(updatedAtMillis = createdAt + 3))
        }
        shouldThrow<IllegalArgumentException> {
            BuilderConstructionProjectTransitionRules.validate(
                active,
                active.copy(playerName = "Other", updatedAtMillis = createdAt + 2),
            )
        }
        shouldThrow<IllegalArgumentException> {
            active.copy(
                state = BuilderConstructionProjectState.WAITING_OUTPUT_SPACE,
                updatedAtMillis = createdAt + 2,
            ).validated()
        }
        shouldThrow<IllegalArgumentException> {
            active.outputPending(refund, createdAt)
        }
    }

    test("active and material-waiting projects pause durably and resume from the same cursor") {
        val active = prepared().activated(createdAt + 1).advanced(createdAt + 2)
        val pausedActive = active.paused(createdAt + 3)
        val resumed = pausedActive.resumed(createdAt + 4)
        val waiting = resumed.waitingForMaterials(createdAt + 5)
        val pausedWaiting = waiting.paused(createdAt + 6)

        pausedActive.state shouldBe BuilderConstructionProjectState.PAUSED
        pausedActive.cursor shouldBe 1
        resumed.state shouldBe BuilderConstructionProjectState.ACTIVE
        resumed.cursor shouldBe 1
        pausedWaiting.state shouldBe BuilderConstructionProjectState.PAUSED
        pausedWaiting.cursor shouldBe 1
        BuilderConstructionProjectController.tick(pausedWaiting, createdAt + 7, object : BuilderConstructionProjectPort {
            override fun currentBlockData(position: BuilderBlockPos) = error("paused project must not inspect the world")
            override fun canModify(project: BuilderConstructionProjectRecord, step: BuilderConstructionStep) =
                error("paused project must not inspect protection")
            override fun prepareInput(playerId: UUID, project: BuilderConstructionProjectRecord, input: BuilderItemAmount) =
                error("paused project must not touch resources")
            override fun prepareOutput(playerId: UUID, project: BuilderConstructionProjectRecord, output: BuilderItemAmount) =
                error("paused project must not touch resources")
            override fun reconcileResource(project: BuilderConstructionProjectRecord, mutation: BuilderResourceMutation) =
                error("paused project must not touch resources")
            override fun rollbackResource(project: BuilderConstructionProjectRecord, mutation: BuilderResourceMutation) =
                error("paused project must not touch resources")
            override fun apply(project: BuilderConstructionProjectRecord, step: BuilderConstructionStep) =
                error("paused project must not mutate the world")
        }) shouldBe null
    }

    test("world drift enters a recovery hold and cannot resume automatically") {
        val active = prepared().activated(createdAt + 1)
        val recovery = active.recoveryRequired(createdAt + 2)

        recovery.state shouldBe BuilderConstructionProjectState.RECOVERY_REQUIRED
        recovery.terminal shouldBe false
        shouldThrow<IllegalArgumentException> {
            recovery.activated(createdAt + 3)
        }
        shouldThrow<IllegalArgumentException> {
            recovery.advanced(createdAt + 3)
        }
        recovery.cancelled(createdAt + 3).state shouldBe BuilderConstructionProjectState.CANCELLED
    }

    test("startup recovery advances an already-applied companion only when it has no item exchange") {
        val companionChange = BuilderBlockChange(
            position = BuilderBlockPos(worldId, 12, 64, 10),
            beforeBlockData = "minecraft:seagrass",
            afterBlockData = "minecraft:brown_bed[facing=north,occupied=false,part=head]",
        )
        val companionPlan = BuilderPlan(
            id = projectId,
            playerId = playerId,
            kind = BuilderPlanKind.BUILD_BOOK,
            changes = listOf(companionChange),
            costs = listOf(book),
            rewards = emptyList(),
            createdAtMillis = createdAt,
            expiresAtMillis = createdAt + 60_000,
        )
        val held = BuilderConstructionProjectRecord(
            projectId = projectId,
            playerId = playerId,
            playerName = "Builder",
            projectTitle = "Underwater starter house",
            plan = companionPlan,
            steps = listOf(BuilderConstructionStep(companionChange, requiredMaterial = null, output = null)),
            bookCost = book,
            state = BuilderConstructionProjectState.RECOVERY_REQUIRED,
            cursor = 0,
            createdAtMillis = createdAt,
            updatedAtMillis = createdAt + 1,
        ).validated()
        val appliedPort = object : BuilderConstructionProjectPort {
            override fun currentBlockData(position: BuilderBlockPos): String = companionChange.afterBlockData
            override fun canModify(project: BuilderConstructionProjectRecord, step: BuilderConstructionStep) = true
            override fun prepareInput(playerId: UUID, project: BuilderConstructionProjectRecord, input: BuilderItemAmount) = null
            override fun prepareOutput(playerId: UUID, project: BuilderConstructionProjectRecord, output: BuilderItemAmount) = null
            override fun reconcileResource(project: BuilderConstructionProjectRecord, mutation: BuilderResourceMutation) =
                BuilderResourceMutationResult.RETRY
            override fun rollbackResource(project: BuilderConstructionProjectRecord, mutation: BuilderResourceMutation) =
                BuilderResourceMutationResult.RETRY
            override fun apply(project: BuilderConstructionProjectRecord, step: BuilderConstructionStep) = Unit
        }

        val resumed = BuilderConstructionRecoveryPolicy.resumeAppliedNoExchangeStep(
            held,
            createdAt + 2,
            appliedPort,
        )

        resumed.state shouldBe BuilderConstructionProjectState.COMPLETED
        resumed.cursor shouldBe 1
        resumed.completedAtMillis shouldBe createdAt + 2

        val driftedPort = object : BuilderConstructionProjectPort by appliedPort {
            override fun currentBlockData(position: BuilderBlockPos): String = companionChange.beforeBlockData
            override fun isStepApplied(step: BuilderConstructionStep): Boolean = false
        }
        BuilderConstructionRecoveryPolicy.resumeAppliedNoExchangeStep(
            held,
            createdAt + 3,
            driftedPort,
        ) shouldBe held

        val unavailablePort = object : BuilderConstructionProjectPort by appliedPort {
            override fun isStepApplied(step: BuilderConstructionStep): Boolean =
                throw BuilderConstructionTemporarilyUnavailableException()
        }
        BuilderConstructionRecoveryPolicy.canResumeAppliedNoExchangeStep(held) shouldBe true
        BuilderConstructionRecoveryPolicy.resumeAppliedNoExchangeStep(
            held,
            createdAt + 3,
            unavailablePort,
        ) shouldBe held

        val exchangeHeld = prepared()
            .activated(createdAt + 1)
            .recoveryRequired(createdAt + 2)
        val exchangeAppliedPort = object : BuilderConstructionProjectPort by appliedPort {
            override fun isStepApplied(step: BuilderConstructionStep): Boolean = true
        }
        BuilderConstructionRecoveryPolicy.resumeAppliedNoExchangeStep(
            exchangeHeld,
            createdAt + 3,
            exchangeAppliedPort,
        ) shouldBe exchangeHeld
        BuilderConstructionRecoveryPolicy.canResumeAppliedNoExchangeStep(exchangeHeld) shouldBe false
    }
})
