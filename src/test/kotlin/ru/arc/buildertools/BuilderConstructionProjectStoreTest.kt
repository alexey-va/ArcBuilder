package ru.arc.buildertools

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.util.UUID

class BuilderConstructionProjectStoreTest : FunSpec({
    val projectId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    val playerId = UUID.fromString("11111111-2222-3333-4444-555555555555")
    val worldId = UUID.fromString("99999999-8888-7777-6666-555555555555")
    val createdAt = 1_800_000_000_000L
    val book = BuilderItemAmount("BBBB", "minecraft:book", 1)
    val stone = BuilderItemAmount("AAAA", "minecraft:stone", 1)
    val change = BuilderBlockChange(
        BuilderBlockPos(worldId, 10, 64, 10),
        "minecraft:air",
        "minecraft:stone",
    )
    val step = BuilderConstructionStep(change, requiredMaterial = stone, output = null)
    val plan = BuilderPlan(
        id = projectId,
        playerId = playerId,
        kind = BuilderPlanKind.BUILD_BOOK,
        changes = listOf(change),
        costs = listOf(book, stone),
        rewards = emptyList(),
        createdAtMillis = createdAt,
        expiresAtMillis = createdAt + 60_000,
    )

    fun prepared() = BuilderConstructionProjectRecord(
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
    ).validated()

    test("store durably resumes a waiting project after restart") {
        val root = Files.createTempDirectory("arc-builder-construction-project-")
        val first = BuilderConstructionProjectStore(root, maxChanges = 10_000)
        val prepared = first.commit(prepared())
        val active = first.transition(prepared, prepared.activated(createdAt + 1))
        val waiting = first.transition(active, active.waitingForMaterials(createdAt + 2))

        val reloaded = BuilderConstructionProjectStore(root, maxChanges = 10_000)
        reloaded.loadAll() shouldBe listOf(waiting)
        reloaded.loadOrNull(projectId) shouldBe waiting
    }

    test("store makes an already durable transition idempotent and rejects stale predecessors") {
        val root = Files.createTempDirectory("arc-builder-construction-project-idempotent-")
        val store = BuilderConstructionProjectStore(root, maxChanges = 10_000)
        val prepared = store.commit(prepared())
        val active = prepared.activated(createdAt + 1)

        store.transition(prepared, active) shouldBe active
        store.transition(prepared, active) shouldBe active
        shouldThrow<IllegalArgumentException> {
            store.transition(prepared, prepared.cancelled(createdAt + 2))
        }
        shouldThrow<IllegalArgumentException> {
            store.commit(prepared)
        }
    }
})
