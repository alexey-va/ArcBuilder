package ru.arc.buildertools

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.util.Base64
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

    fun bookDebitReceipt() = BuilderResourceMutation(
        amount = book,
        insert = false,
        sources = listOf(
            BuilderResourceInventoryMutation(
                kind = BuilderResourceSourceKind.PLAYER,
                playerId = playerId,
                requireNearProject = false,
                before = listOf(Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))),
                after = listOf(null),
            ),
        ),
    ).validated(playerId)

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

    test("store restart preserves the complete pending resource receipt") {
        val root = Files.createTempDirectory("arc-builder-construction-project-receipt-")
        val store = BuilderConstructionProjectStore(root, maxChanges = 10_000)
        val receipt = bookDebitReceipt()
        val prepared = prepared().activationPrepared(receipt)

        val committed = store.commit(prepared)
        val reloaded = BuilderConstructionProjectStore(root, maxChanges = 10_000).loadOrNull(projectId)

        reloaded shouldBe committed
        reloaded?.pendingResourceMutation shouldBe receipt
        reloaded?.pendingResourceMutation?.sources?.single()?.before shouldBe receipt.sources.single().before
        reloaded?.pendingResourceMutation?.sources?.single()?.after shouldBe receipt.sources.single().after
    }

    test("store restart preserves a reviewed chest loot-table step") {
        val root = Files.createTempDirectory("arc-builder-construction-project-loot-")
        val chestChange = BuilderBlockChange(
            BuilderBlockPos(worldId, 12, 64, 12),
            "minecraft:air",
            "minecraft:chest[facing=north,type=single,waterlogged=false]",
        )
        val chestStep = BuilderConstructionStep(
            change = chestChange,
            requiredMaterial = null,
            output = null,
            lootTableKey = "minecraft:chests/spawn_bonus_chest",
        )
        val chestPlan = plan.copy(changes = listOf(chestChange), costs = listOf(book))
        val project = prepared().copy(
            projectId = chestPlan.id,
            plan = chestPlan,
            steps = listOf(chestStep),
        ).validated()

        BuilderConstructionProjectStore(root, maxChanges = 10_000).commit(project)

        BuilderConstructionProjectStore(root, maxChanges = 10_000)
            .loadOrNull(projectId)
            ?.steps
            ?.single()
            ?.lootTableKey shouldBe "minecraft:chests/spawn_bonus_chest"
    }
})
