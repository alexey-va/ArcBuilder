package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import ru.arc.paper.playerstate.PaperPlayerStateEnvelope
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class BuilderOperationLocksTest : FunSpec({
    test("block locks are exact-operation leases and close fails new acquisition") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("BuilderOperationLocksTest")
            val world = paper.addSimpleWorld("builder-locks")
            val first = plan(UUID.randomUUID(), UUID.randomUUID(), world.uid)
            val second = plan(UUID.randomUUID(), UUID.randomUUID(), world.uid)

            BuilderOperationLocks(plugin).use { locks ->
                locks.tryLock(first) shouldBe true
                locks.tryLock(second) shouldBe false

                // A stale or unrelated plan must not release the current lease.
                locks.unlock(second)
                locks.tryLock(second) shouldBe false

                locks.unlock(first)
                locks.tryLock(second) shouldBe true
            }

            val closed = BuilderOperationLocks(plugin)
            closed.close()
            closed.tryLock(first) shouldBe false
        }
    }

    test("registered operation isolates commands and block changes until exact finish") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("BuilderOperationEventsTest")
            val world = paper.addSimpleWorld("builder-events")
            val player = paper.addPlayer("Builder")
            val other = paper.addPlayer("Visitor")
            val plan = plan(UUID.randomUUID(), player.uniqueId, world.uid)
            val record = BuilderJournalRecord(
                operationId = plan.id,
                playerId = player.uniqueId,
                playerName = player.name,
                phase = BuilderJournalPhase.PREPARED,
                plan = plan,
                inventoryBefore = PaperPlayerStateEnvelope(payloadBase64 = "AAAA", sha256 = "a".repeat(64)),
                createdAtMillis = plan.createdAtMillis,
                updatedAtMillis = plan.createdAtMillis,
            ).validated()
            val operation = BuilderActiveOperation(record, GameMode.SURVIVAL)
            val block = world.getBlockAt(1, 64, 1).also { it.type = Material.STONE }

            BuilderOperationLocks(plugin).use { locks ->
                locks.tryLock(plan) shouldBe true
                locks.register(operation)
                locks.activeOperationCount shouldBe 1

                val safeControl = PlayerCommandPreprocessEvent(player, "/builder status")
                paper.callEvent(safeControl)
                safeControl.isCancelled shouldBe false

                val unrelatedCommand = PlayerCommandPreprocessEvent(player, "/home")
                paper.callEvent(unrelatedCommand)
                unrelatedCommand.isCancelled shouldBe true

                val externalBreak = BlockBreakEvent(block, other)
                paper.callEvent(externalBreak)
                externalBreak.isCancelled shouldBe true

                locks.finish(operation)
                locks.activeOperationCount shouldBe 0

                val breakAfterFinish = BlockBreakEvent(block, other)
                paper.callEvent(breakAfterFinish)
                breakAfterFinish.isCancelled shouldBe false
            }
        }
    }

    test("commit boundary defers interruption until the durable outcome is known") {
        val operation = activeOperation()

        operation.interruptionDeferred shouldBe false
        operation.beginCommit()
        operation.commitBoundary shouldBe BuilderCommitBoundary.COMMIT_IN_FLIGHT
        operation.interruptionDeferred shouldBe true
        shouldThrow<IllegalStateException> { operation.beginCommit() }

        operation.markCommitFailureKnown()
        operation.commitBoundary shouldBe BuilderCommitBoundary.ROLLBACK_SAFE
        operation.interruptionDeferred shouldBe false

        operation.beginCommit()
        operation.requireCommitRecovery()
        operation.commitBoundary shouldBe BuilderCommitBoundary.RECOVERY_REQUIRED
        operation.interruptionDeferred shouldBe true
        operation.requireCommitRecovery()
    }

    test("book locks deny every command and reject a second player lease") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("BuilderBookLocksTest")
            val player = paper.addPlayer("BookOwner")

            BuilderOperationLocks(plugin).use { locks ->
                locks.tryBookLock(player.uniqueId) shouldBe true
                locks.tryBookLock(player.uniqueId) shouldBe false
                locks.bookLockedPlayerCount shouldBe 1

                val builderControl = PlayerCommandPreprocessEvent(player, "/builder cancel")
                paper.callEvent(builderControl)
                builderControl.isCancelled shouldBe true

                locks.unlockBook(player.uniqueId)
                locks.isPlayerLocked(player.uniqueId) shouldBe false
            }
        }
    }

    test("player recovery lock denies even builder controls until exact recovery finishes") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("BuilderRecoveryLocksTest")
            val player = paper.addPlayer("RecoveringBuilder")

            BuilderOperationLocks(plugin).use { locks ->
                locks.lockRecovery(player.uniqueId)
                locks.isPlayerLocked(player.uniqueId) shouldBe true
                locks.isRecoveryLocked(player.uniqueId) shouldBe true

                val status = PlayerCommandPreprocessEvent(player, "/builder status")
                paper.callEvent(status)
                status.isCancelled shouldBe true

                locks.unlockRecovery(player.uniqueId)
                locks.isPlayerLocked(player.uniqueId) shouldBe false
            }
        }
    }
})

private fun activeOperation(): BuilderActiveOperation {
    val plan = plan(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
    return BuilderActiveOperation(
        BuilderJournalRecord(
            operationId = plan.id,
            playerId = plan.playerId,
            playerName = "Builder",
            phase = BuilderJournalPhase.APPLYING,
            plan = plan,
            inventoryBefore = PaperPlayerStateEnvelope(payloadBase64 = "AAAA", sha256 = "a".repeat(64)),
            createdAtMillis = plan.createdAtMillis,
            updatedAtMillis = plan.createdAtMillis,
        ).validated(),
        GameMode.SURVIVAL,
    )
}

private fun plan(operationId: UUID, playerId: UUID, worldId: UUID): BuilderPlan {
    val now = 1_800_000_000_000L
    return BuilderPlan(
        id = operationId,
        playerId = playerId,
        kind = BuilderPlanKind.FILL,
        changes = listOf(
            BuilderBlockChange(
                BuilderBlockPos(worldId, 1, 64, 1),
                "minecraft:stone",
                "minecraft:oak_planks",
            ),
        ),
        costs = emptyList(),
        rewards = emptyList(),
        createdAtMillis = now,
        expiresAtMillis = now + 30_000L,
    ).validated()
}
