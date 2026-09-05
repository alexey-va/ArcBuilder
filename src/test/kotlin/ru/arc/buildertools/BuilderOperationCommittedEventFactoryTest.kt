package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.util.UUID
import org.bukkit.GameMode

class BuilderOperationCommittedEventFactoryTest : FunSpec({
    val worldId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    val playerId = UUID.fromString("22222222-2222-2222-2222-222222222222")

    test("emits only eligible non-air material changes") {
        val plan = plan(
            kind = BuilderPlanKind.FILL,
            playerId = playerId,
            changes = listOf(
                change(worldId, 1, 2, 3, "minecraft:air", "minecraft:stone"),
                change(worldId, 4, 5, 6, "minecraft:stone", "minecraft:stone[axis=x]"),
                change(worldId, 7, 8, 9, "minecraft:stone", "minecraft:air"),
            ),
        )

        val event = checkNotNull(BuilderOperationCommittedEventFactory.create(plan, GameMode.SURVIVAL))
        event.operationId shouldBe plan.id.toString()
        event.playerId shouldBe playerId
        event.worldId shouldBe worldId
        event.placements shouldContainExactly listOf(ru.ruscrafting.builder.api.BuilderPlacedBlock(1, 2, 3, "minecraft:stone"))
    }

    test("rejects ineligible mode and operation kinds") {
        val plan = plan(
            kind = BuilderPlanKind.UNDO,
            playerId = playerId,
            changes = listOf(change(worldId, 1, 2, 3, "minecraft:air", "minecraft:stone")),
        )

        BuilderOperationCommittedEventFactory.create(plan, GameMode.SURVIVAL) shouldBe null
        BuilderOperationCommittedEventFactory.create(plan.copy(kind = BuilderPlanKind.FILL), GameMode.CREATIVE) shouldBe null
    }
})

private fun plan(kind: BuilderPlanKind, playerId: UUID, changes: List<BuilderBlockChange>) = BuilderPlan(
    id = UUID.fromString("33333333-3333-3333-3333-333333333333"),
    playerId = playerId,
    kind = kind,
    changes = changes,
    costs = emptyList(),
    rewards = emptyList(),
    createdAtMillis = 1L,
    expiresAtMillis = 2L,
)

private fun change(worldId: UUID, x: Int, y: Int, z: Int, before: String, after: String) = BuilderBlockChange(
    position = BuilderBlockPos(worldId, x, y, z),
    beforeBlockData = before,
    afterBlockData = after,
)
