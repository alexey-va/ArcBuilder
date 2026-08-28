package ru.arc.buildertools

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.GameMode
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.playerstate.PaperPlayerStateEnvelope
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.util.UUID

class BuilderPlayerRecoveryCoordinatorTest : FunSpec({
    test("inventory stays locked while exact acknowledgement retries without a second restore") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("BuilderPlayerRecoveryTest")
            val player = paper.addPlayer("RecoveryUser")
            val scheduler = TestTaskScheduler()
            val scope = LifecycleTaskScope(scheduler)
            val acknowledgements = mutableListOf<Pair<BuilderJournalRecord, (Throwable?) -> Unit>>()
            var restores = 0
            var releases = 0
            var pendingReports = 0
            var recoveredReports = 0
            var resolutions = 0

            BuilderOperationLocks(plugin).use { locks ->
                BuilderPlayerRecoveryCoordinator(
                    taskScope = scope,
                    operationLocks = locks,
                    playerLookup = { id -> player.takeIf { it.uniqueId == id } },
                    restoreInventory = { restoredPlayer, _ ->
                        restoredPlayer.uniqueId shouldBe player.uniqueId
                        restores++
                    },
                    acknowledgeAsync = { record, complete -> acknowledgements += record to complete },
                    releaseReservation = { releases++ },
                    onTerminalFailure = { _, failure -> throw failure },
                    onAcknowledgementPending = { _, _ -> pendingReports++ },
                    onAcknowledgementRecovered = { recoveredReports++ },
                    onResolved = { resolutions++ },
                ).use { recoveries ->
                    val record = applyingRecord(player.uniqueId)
                    recoveries.add(record)

                    recoveries.pendingCount shouldBe 1
                    recoveries.contains(player.uniqueId) shouldBe true
                    locks.isRecoveryLocked(player.uniqueId) shouldBe true
                    restores shouldBe 1
                    releases shouldBe 1
                    acknowledgements.size shouldBe 1

                    acknowledgements.removeFirst().second(IllegalStateException("storage unavailable"))
                    recoveries.pendingCount shouldBe 1
                    locks.isRecoveryLocked(player.uniqueId) shouldBe true
                    pendingReports shouldBe 1

                    scheduler.tick(100)
                    acknowledgements.size shouldBe 1
                    restores shouldBe 1
                    releases shouldBe 1

                    val (retriedRecord, completeRetry) = acknowledgements.removeFirst()
                    retriedRecord shouldBe record
                    completeRetry(null)

                    recoveries.pendingCount shouldBe 0
                    locks.isRecoveryLocked(player.uniqueId) shouldBe false
                    restores shouldBe 1
                    recoveredReports shouldBe 1
                    resolutions shouldBe 1
                }
            }
            scope.close()
        }
    }

    test("a different interrupted operation cannot replace a locked player recovery") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("BuilderPlayerRecoveryConflictTest")
            val player = paper.addPlayer("RecoveryUser")
            val scope = LifecycleTaskScope(TestTaskScheduler())
            BuilderOperationLocks(plugin).use { locks ->
                BuilderPlayerRecoveryCoordinator(
                    taskScope = scope,
                    operationLocks = locks,
                    playerLookup = { null },
                    restoreInventory = { _, _ -> },
                    acknowledgeAsync = { _, _ -> },
                    releaseReservation = {},
                    onTerminalFailure = { _, failure -> throw failure },
                    onAcknowledgementPending = { _, _ -> },
                    onAcknowledgementRecovered = {},
                    onResolved = {},
                ).use { recoveries ->
                    recoveries.add(applyingRecord(player.uniqueId))

                    shouldThrow<IllegalArgumentException> {
                        recoveries.add(applyingRecord(player.uniqueId, UUID.randomUUID()))
                    }
                    recoveries.pendingCount shouldBe 1
                    locks.isRecoveryLocked(player.uniqueId) shouldBe true
                }
            }
            scope.close()
        }
    }

    test("a live pre-mutation failure waits for exact acknowledgement without restoring inventory") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("BuilderPlayerPreMutationTest")
            val player = paper.addPlayer("RecoveryUser")
            val scope = LifecycleTaskScope(TestTaskScheduler())
            val acknowledgements = mutableListOf<(Throwable?) -> Unit>()
            var restores = 0

            BuilderOperationLocks(plugin).use { locks ->
                BuilderPlayerRecoveryCoordinator(
                    taskScope = scope,
                    operationLocks = locks,
                    playerLookup = { id -> player.takeIf { it.uniqueId == id } },
                    restoreInventory = { _, _ -> restores++ },
                    acknowledgeAsync = { _, complete -> acknowledgements += complete },
                    releaseReservation = {},
                    onTerminalFailure = { _, failure -> throw failure },
                    onAcknowledgementPending = { _, _ -> },
                    onAcknowledgementRecovered = {},
                    onResolved = {},
                ).use { recoveries ->
                    val prepared = applyingRecord(player.uniqueId)
                        .copy(phase = BuilderJournalPhase.PREPARED)
                        .validated()

                    shouldThrow<IllegalArgumentException> { recoveries.add(prepared) }
                    recoveries.add(prepared, inventoryRestored = true) shouldBe true

                    restores shouldBe 0
                    locks.isRecoveryLocked(player.uniqueId) shouldBe true
                    acknowledgements.single()(null)
                    locks.isRecoveryLocked(player.uniqueId) shouldBe false
                }
            }
            scope.close()
        }
    }

    test("an ambiguous block rollback holds the player without touching value or acknowledgement") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("BuilderPlayerManualHoldTest")
            val player = paper.addPlayer("RecoveryUser")
            val scope = LifecycleTaskScope(TestTaskScheduler())
            var restores = 0
            var acknowledgements = 0
            var releases = 0

            BuilderOperationLocks(plugin).use { locks ->
                BuilderPlayerRecoveryCoordinator(
                    taskScope = scope,
                    operationLocks = locks,
                    playerLookup = { id -> player.takeIf { it.uniqueId == id } },
                    restoreInventory = { _, _ -> restores++ },
                    acknowledgeAsync = { _, _ -> acknowledgements++ },
                    releaseReservation = { releases++ },
                    onTerminalFailure = { _, failure -> throw failure },
                    onAcknowledgementPending = { _, _ -> },
                    onAcknowledgementRecovered = {},
                    onResolved = {},
                ).use { recoveries ->
                    val record = applyingRecord(player.uniqueId)
                    recoveries.hold(record)

                    recoveries.onPlayerAvailable(player) shouldBe true
                    recoveries.record(record.operationId) shouldBe record
                    locks.isRecoveryLocked(player.uniqueId) shouldBe true
                    restores shouldBe 0
                    acknowledgements shouldBe 0
                    releases shouldBe 0
                }
            }
            scope.close()
        }
    }

    test("a failed inventory restore remains locked and is not retried blindly") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("BuilderPlayerRestoreFailureTest")
            val player = paper.addPlayer("RecoveryUser")
            val scheduler = TestTaskScheduler()
            val scope = LifecycleTaskScope(scheduler)
            var restores = 0
            var restoreFailures = 0
            var acknowledgements = 0

            BuilderOperationLocks(plugin).use { locks ->
                BuilderPlayerRecoveryCoordinator(
                    taskScope = scope,
                    operationLocks = locks,
                    playerLookup = { id -> player.takeIf { it.uniqueId == id } },
                    restoreInventory = { _, _ ->
                        restores++
                        error("corrupt recovery envelope")
                    },
                    acknowledgeAsync = { _, _ -> acknowledgements++ },
                    releaseReservation = {},
                    onTerminalFailure = { _, _ -> restoreFailures++ },
                    onAcknowledgementPending = { _, _ -> },
                    onAcknowledgementRecovered = {},
                    onResolved = {},
                ).use { recoveries ->
                    recoveries.add(applyingRecord(player.uniqueId))

                    scheduler.tick(500)

                    recoveries.pendingCount shouldBe 1
                    locks.isRecoveryLocked(player.uniqueId) shouldBe true
                    restores shouldBe 1
                    restoreFailures shouldBe 1
                    acknowledgements shouldBe 0
                }
            }
            scope.close()
        }
    }

    test("a failed reservation release blocks acknowledgement and keeps recovery locked") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("BuilderPlayerReleaseFailureTest")
            val player = paper.addPlayer("RecoveryUser")
            val scheduler = TestTaskScheduler()
            val scope = LifecycleTaskScope(scheduler)
            var restores = 0
            var acknowledgements = 0
            var terminalFailures = 0

            BuilderOperationLocks(plugin).use { locks ->
                BuilderPlayerRecoveryCoordinator(
                    taskScope = scope,
                    operationLocks = locks,
                    playerLookup = { id -> player.takeIf { it.uniqueId == id } },
                    restoreInventory = { _, _ -> restores++ },
                    acknowledgeAsync = { _, _ -> acknowledgements++ },
                    releaseReservation = { error("release queue unavailable") },
                    onTerminalFailure = { _, _ -> terminalFailures++ },
                    onAcknowledgementPending = { _, _ -> },
                    onAcknowledgementRecovered = {},
                    onResolved = {},
                ).use { recoveries ->
                    recoveries.add(applyingRecord(player.uniqueId))

                    scheduler.tick(500)

                    recoveries.pendingCount shouldBe 1
                    locks.isRecoveryLocked(player.uniqueId) shouldBe true
                    restores shouldBe 0
                    acknowledgements shouldBe 0
                    terminalFailures shouldBe 1
                }
            }
            scope.close()
        }
    }

    test("player recovery acknowledges only the exact durable journal record idempotently") {
        val store = BuilderJournalStore(Files.createTempDirectory("arc-builder-player-recovery-"), 16)
        val record = applyingRecord(UUID.fromString("99999999-8888-7777-6666-555555555555"))
        store.commit(record) shouldBe record

        shouldThrow<IllegalStateException> {
            store.acknowledgeExactly(record.copy(updatedAtMillis = record.updatedAtMillis + 1).validated())
        }
        store.loadAll().map { it.value } shouldBe listOf(record)

        store.acknowledgeExactly(record) shouldBe true
        store.acknowledgeExactly(record) shouldBe true
        store.loadAll() shouldBe emptyList()
    }
})

private fun applyingRecord(
    playerId: UUID,
    operationId: UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
): BuilderJournalRecord {
    val now = 1_800_000_000_000L
    val plan = BuilderPlan(
        id = operationId,
        playerId = playerId,
        kind = BuilderPlanKind.FILL,
        changes = listOf(
            BuilderBlockChange(
                BuilderBlockPos(UUID.fromString("11111111-2222-3333-4444-555555555555"), 1, 64, 1),
                "minecraft:air",
                "minecraft:stone",
            ),
        ),
        costs = emptyList(),
        rewards = emptyList(),
        createdAtMillis = now,
        expiresAtMillis = now + 30_000L,
    ).validated()
    return BuilderJournalRecord(
        operationId = operationId,
        playerId = playerId,
        playerName = "RecoveryUser",
        phase = BuilderJournalPhase.APPLYING,
        plan = plan,
        inventoryBefore = PaperPlayerStateEnvelope(payloadBase64 = "AAAA", sha256 = "a".repeat(64)),
        createdAtMillis = now,
        updatedAtMillis = now + 1,
    ).validated()
}
