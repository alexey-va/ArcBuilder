package ru.arc.buildertools

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.nio.file.Files
import java.util.UUID

class BuilderInstantConstructionTest : FunSpec({
    val owner = UUID.randomUUID()
    val admin = UUID.randomUUID()
    val world = UUID.randomUUID()
    val book = BuilderItemAmount("BBBB", "minecraft:book", 1)
    val stone = BuilderItemAmount("AAAA", "minecraft:stone", 1)
    fun project(count: Int = 12): BuilderConstructionProjectRecord {
        val id = UUID.randomUUID()
        val changes = (0 until count).map { BuilderBlockChange(BuilderBlockPos(world, it, 64, 0), "minecraft:air", "minecraft:stone") }
        val steps = changes.map { BuilderConstructionStep(it, stone, null) }
        val plan = BuilderPlan(id, owner, BuilderPlanKind.BUILD_BOOK, changes,
            listOf(book, stone.copy(amount = count)), emptyList(), createdAtMillis = 1000, expiresAtMillis = 60000)
        return BuilderConstructionProjectRecord(projectId = id, playerId = owner, playerName = "Builder",
            plan = plan, steps = steps, bookCost = book, state = BuilderConstructionProjectState.PREPARED,
            cursor = 0, createdAtMillis = 1000, updatedAtMillis = 1000).validated()
    }
    fun worldPort(blocks: MutableMap<BuilderBlockPos, String> = mutableMapOf()): BuilderConstructionProjectPort {
        val port = mockk<BuilderConstructionProjectPort>()
        every { port.currentBlockData(any()) } answers { blocks[firstArg()] ?: "minecraft:air" }
        every { port.isStepApplied(any()) } answers { val step = firstArg<BuilderConstructionStep>(); blocks[step.change.position] == step.change.afterBlockData }
        every { port.canModify(any(), any()) } returns true
        every { port.apply(any(), any()) } answers { val step = secondArg<BuilderConstructionStep>(); blocks[step.change.position] = step.change.afterBlockData }
        return port
    }
    test("waiting project completes all remaining blocks in one batch with no resource calls") {
        val active = project().activated(1001).waitingForMaterials(1002)
        val intent = checkNotNull(BuilderInstantConstruction.prepare(active, admin, 1003))
        val port = worldPort()
        val completed = checkNotNull(BuilderConstructionProjectController.tick(intent, 1004, port))
        completed.state shouldBe BuilderConstructionProjectState.COMPLETED
        completed.cursor shouldBe 12
        completed.instantBuildRequestedBy shouldBe admin
        verify(exactly = 12) { port.apply(any(), any()) }
        verify(exactly = 0) { port.prepareInput(any(), any(), any()); port.prepareOutput(any(), any(), any()) }
    }
    test("durable intent survives restart and replays partial world apply without duplication") {
        val root = Files.createTempDirectory("builder-instant-")
        val store = BuilderConstructionProjectStore(root, 10000)
        val prepared = store.commit(project())
        val active = store.transition(prepared, prepared.activated(1001))
        val intent = store.transition(active, checkNotNull(BuilderInstantConstruction.prepare(active, admin, 1002)))
        val restored = checkNotNull(BuilderConstructionProjectStore(root, 10000).loadOrNull(intent.projectId))
        BuilderConstructionRecoveryPolicy.normalizeLoaded(restored, 1003) shouldBe intent
        val blocks = intent.steps.take(5).associate { it.change.position to it.change.afterBlockData }.toMutableMap()
        val port = worldPort(blocks)
        val done = checkNotNull(BuilderConstructionProjectController.tick(restored, 1004, port))
        store.transition(restored, done).state shouldBe BuilderConstructionProjectState.COMPLETED
        verify(exactly = 7) { port.apply(any(), any()) }
        BuilderConstructionTransitionFailurePolicy.requiresRecovery(restored, done) shouldBe false
        val replay = checkNotNull(BuilderConstructionProjectController.tick(restored, 1005, port))
        replay.state shouldBe BuilderConstructionProjectState.COMPLETED
        verify(exactly = 7) { port.apply(any(), any()) }
    }
    test("protection failure is detected before any world mutation") {
        val intent = checkNotNull(BuilderInstantConstruction.prepare(project().activated(1001), admin, 1002))
        val port = worldPort()
        every { port.canModify(any(), intent.steps.last()) } returns false
        BuilderConstructionProjectController.tick(intent, 1003, port)?.state shouldBe BuilderConstructionProjectState.RECOVERY_REQUIRED
        verify(exactly = 0) { port.apply(any(), any()) }
    }
    test("large projects finish in bounded batches and cannot forge cursor jumps") {
        val intent = checkNotNull(BuilderInstantConstruction.prepare(project(4100).activated(1001), admin, 1002))
        val port = worldPort()
        val next = checkNotNull(BuilderConstructionProjectController.tick(intent, 1003, port))
        next.cursor shouldBe 4096
        next.state shouldBe BuilderConstructionProjectState.WORLD_PREPARED
        BuilderConstructionProjectController.tick(next, 1004, port)?.state shouldBe BuilderConstructionProjectState.COMPLETED
        shouldThrow<IllegalArgumentException> { BuilderConstructionProjectTransitionRules.validate(intent, next.copy(cursor = 4095)) }
    }
    test("logging failure after applying a block still completes without replaying the mutation") {
        val intent = checkNotNull(BuilderInstantConstruction.prepare(project(1).activated(1001), admin, 1002))
        val blocks = mutableMapOf<BuilderBlockPos, String>()
        val port = worldPort(blocks)
        every { port.apply(any(), any()) } answers {
            val step = secondArg<BuilderConstructionStep>()
            blocks[step.change.position] = step.change.afterBlockData
            error("logging failed after placement")
        }
        BuilderConstructionProjectController.tick(intent, 1003, port)?.state shouldBe BuilderConstructionProjectState.COMPLETED
        verify(exactly = 1) { port.apply(any(), any()) }
    }
    test("temporary apply failure keeps durable intent replayable while unknown world drift stops") {
        val intent = checkNotNull(BuilderInstantConstruction.prepare(project(2).activated(1001), admin, 1002))
        val port = worldPort()
        every { port.apply(any(), intent.steps.last()) } throws IllegalStateException("temporary apply failure")
        BuilderConstructionProjectController.tick(intent, 1003, port) shouldBe null
        verify(exactly = 1) { port.apply(any(), intent.steps.first()) }
        every { port.currentBlockData(intent.steps.last().change.position) } returns "minecraft:bedrock"
        BuilderConstructionProjectController.tick(intent, 1004, port)?.state shouldBe BuilderConstructionProjectState.RECOVERY_REQUIRED
    }
    test("already placed chest metadata is repaired without treating it as a new placement") {
        val active = project(1).activated(1001)
        val change = active.steps.single().change.copy(afterBlockData = "minecraft:chest")
        val record = active.copy(plan = active.plan.copy(changes = listOf(change)),
            steps = listOf(active.steps.single().copy(change = change, lootTableKey = "minecraft:chests/simple_dungeon"))).validated()
        val intent = checkNotNull(BuilderInstantConstruction.prepare(record, admin, 1002))
        val blocks = mutableMapOf(change.position to change.afterBlockData)
        val port = worldPort(blocks)
        var metadataApplied = false
        every { port.isStepApplied(any()) } answers { metadataApplied }
        every { port.canModify(any(), any()) } returns false
        every { port.apply(any(), any()) } answers { metadataApplied = true }
        BuilderConstructionProjectController.tick(intent, 1003, port)?.state shouldBe BuilderConstructionProjectState.COMPLETED
        metadataApplied shouldBe true
        verify(exactly = 0) { port.canModify(any(), any()) }
    }
    test("unissued replacement output is waived but an in-flight receipt cannot be discarded") {
        val active = project(1).activated(1001)
        val output = stone.copy(materialKey = "minecraft:dirt")
        val record = active.copy(plan = active.plan.copy(rewards = listOf(output)),
            steps = listOf(active.steps.single().copy(output = output))).validated()
        val waiting = record.outputPending(output, 1002).waitingForOutput(1003)
        val intent = checkNotNull(BuilderInstantConstruction.prepare(waiting, admin, 1004))
        intent.pendingOutput shouldBe null
        intent.cursor shouldBe waiting.cursor
        BuilderInstantConstruction.prepare(record.worldPrepared(1002), admin, 1003) shouldBe null
    }
    test("legacy JSON has no instant intent and normal project cannot jump to completion") {
        val record = project().activated(1001)
        Gson().fromJson(Gson().toJson(record), BuilderConstructionProjectRecord::class.java).instantBuildRequestedBy shouldBe null
        BuilderInstantConstruction.prepare(project(), admin, 1002) shouldBe null
        shouldThrow<IllegalArgumentException> { BuilderConstructionProjectTransitionRules.validate(record,
            record.copy(state = BuilderConstructionProjectState.COMPLETED, cursor = record.steps.size, updatedAtMillis = 1003, completedAtMillis = 1003)) }
    }
})
