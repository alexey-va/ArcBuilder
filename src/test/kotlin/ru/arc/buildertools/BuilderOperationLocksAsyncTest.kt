package ru.arc.buildertools

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.event.block.BlockBreakEvent
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.concurrent.CancellationException

class BuilderOperationLocksAsyncTest : FunSpec({
    test("100000 block acquisition and release share bounded ticks") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("large-locks")
            val plan = lockPlan(world.uid, 0 until 100_000)
            val first = lockPlan(world.uid, 0..0)
            val last = lockPlan(world.uid, 99_999..99_999)
            val tasks = QueuedBuilderPlanningTasks()
            BuilderOperationLocks(paper.createSimplePlugin("LargeLocks")).use { locks ->
                val pending = locks.acquireAsync(plan, tasks) { true }
                pending.isDone shouldBe false
                tasks.tick()
                pending.isDone shouldBe false
                tasks.captureSteps shouldBe 128
                locks.tryLock(first) shouldBe false
                locks.tryLock(last) shouldBe true
                locks.unlock(last)
                repeat(800) { tasks.tick() }
                val lease = checkNotNull(pending.awaitBuilderPlanning())
                locks.tryLock(last) shouldBe false

                val released = locks.releaseAsync(lease, tasks)
                released.isDone shouldBe false
                tasks.tick()
                released.isDone shouldBe false
                locks.tryLock(first) shouldBe true
                locks.unlock(first)
                locks.tryLock(last) shouldBe false
                repeat(800) { tasks.tick() }
                released.awaitBuilderPlanning()
                locks.tryLock(plan) shouldBe true
                locks.unlock(plan)
            }
        }
    }

    test("collision retains provisional event protection until budgeted cleanup finishes") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("colliding-locks")
            val visitor = paper.addPlayer("LockVisitor")
            val occupied = lockPlan(world.uid, 10..10)
            val tasks = QueuedBuilderPlanningTasks()
            BuilderOperationLocks(paper.createSimplePlugin("CollidingLocks")).use { locks ->
                locks.tryLock(occupied) shouldBe true
                val pending = locks.acquireAsync(lockPlan(world.uid, 0..20), tasks) { true }
                tasks.tick(10)
                val protected = BlockBreakEvent(world.getBlockAt(0, 64, 0), visitor)
                paper.callEvent(protected)
                protected.isCancelled shouldBe true

                tasks.tick(1)
                pending.isDone shouldBe false
                tasks.tick(5)
                pending.isDone shouldBe false
                locks.tryLock(lockPlan(world.uid, 9..9)) shouldBe false
                tasks.finish(pending) shouldBe null
                val released = BlockBreakEvent(world.getBlockAt(0, 64, 0), visitor)
                paper.callEvent(released)
                released.isCancelled shouldBe false
                locks.tryLock(lockPlan(world.uid, 10..10)) shouldBe false
                val recovered = lockPlan(world.uid, 0..9)
                locks.tryLock(recovered) shouldBe true
                locks.unlock(recovered)
            }
        }
    }

    test("context cancellation completes only after all provisional locks are released") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("cancelled-locks")
            val plan = lockPlan(world.uid, 0..999)
            val tasks = QueuedBuilderPlanningTasks()
            var current = true
            BuilderOperationLocks(paper.createSimplePlugin("CancelledLocks")).use { locks ->
                val pending = locks.acquireAsync(plan, tasks) { current }
                tasks.tick()
                current = false
                tasks.tick(1)
                pending.isDone shouldBe false
                tasks.tick(128)
                pending.isDone shouldBe false
                tasks.tick(1)
                shouldThrow<CancellationException> { pending.awaitBuilderPlanning() }
                locks.tryLock(plan) shouldBe true
                locks.unlock(plan)
            }
        }
    }

    test("late release of the same plan ID cannot release a resumed lease") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("resumed-locks")
            val plan = lockPlan(world.uid, 0..3)
            val tasks = QueuedBuilderPlanningTasks()
            BuilderOperationLocks(paper.createSimplePlugin("ResumedLocks")).use { locks ->
                val first = checkNotNull(tasks.finish(locks.acquireAsync(plan, tasks) { true }))
                val firstRelease = locks.releaseAsync(first, tasks)
                tasks.finish(firstRelease)
                val resumed = checkNotNull(tasks.finish(locks.acquireAsync(plan, tasks) { true }))

                locks.releaseAsync(first, tasks) shouldBe firstRelease
                tasks.tick()
                locks.tryLock(lockPlan(world.uid, 0..3)) shouldBe false
                tasks.finish(locks.releaseAsync(resumed, tasks))
                locks.tryLock(plan) shouldBe true
                locks.unlock(plan)
            }
        }
    }

    test("closing discards the private lock generation and fences unfinished acquisitions") {
        MockBukkitTestRuntime.open().use { paper ->
            val tasks = QueuedBuilderPlanningTasks()
            val plan = lockPlan(UUID.randomUUID(), 0..999)
            val locks = BuilderOperationLocks(paper.createSimplePlugin("ClosingLocks"))
            val pending = locks.acquireAsync(plan, tasks) { true }
            tasks.tick()
            locks.close()
            tasks.tick()
            shouldThrow<CancellationException> { pending.awaitBuilderPlanning() }
            locks.tryLock(plan) shouldBe false
        }
    }
})

private fun lockPlan(worldId: UUID, positions: IntRange): BuilderPlan = BuilderPlan(
    id = UUID.randomUUID(),
    playerId = UUID.randomUUID(),
    kind = BuilderPlanKind.BUILD_BOOK,
    changes = positions.map { x ->
        BuilderBlockChange(BuilderBlockPos(worldId, x, 64, 0), "minecraft:air", "minecraft:stone")
    },
    costs = emptyList(),
    rewards = emptyList(),
    createdAtMillis = 1_800_000_000_000L,
    expiresAtMillis = 1_800_000_030_000L,
).validated()
