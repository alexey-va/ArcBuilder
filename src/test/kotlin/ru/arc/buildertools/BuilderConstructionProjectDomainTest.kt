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

    test("missing input and full output storage pause without losing the current step") {
        val active = prepared().activated(createdAt + 1)
        val waitingMaterial = active.waitingForMaterials(createdAt + 2)
        val waitingOutput = active.waitingForOutput(refund, createdAt + 4)
        val delivering = waitingOutput.deliveringOutput(createdAt + 5)
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
            active.waitingForOutput(refund, createdAt)
        }
    }

    test("world drift enters a recovery hold and cannot resume automatically") {
        val active = prepared().activated(createdAt + 1)
        val recovery = active.recoveryRequired(createdAt + 2)

        recovery.state shouldBe BuilderConstructionProjectState.RECOVERY_REQUIRED
        recovery.terminal shouldBe false
        shouldThrow<IllegalArgumentException> {
            recovery.activated(createdAt + 3)
        }
        recovery.cancelled(createdAt + 3).state shouldBe BuilderConstructionProjectState.CANCELLED
    }
})
