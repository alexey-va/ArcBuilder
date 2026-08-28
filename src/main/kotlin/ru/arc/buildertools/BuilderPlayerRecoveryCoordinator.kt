package ru.arc.buildertools

import org.bukkit.entity.Player
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.ScheduledTask
import java.util.UUID

/**
 * Main-thread owner of inventory recovery that must be durably acknowledged.
 *
 * The player stays fully isolated from value-changing actions from the moment
 * the record enters this owner until the exact journal record is acknowledged.
 * A failed acknowledgement retains the same record and retries it without
 * restoring the inventory a second time in the current process. Live paths
 * that prove the inventory never changed may enter with restoration complete.
 */
internal class BuilderPlayerRecoveryCoordinator(
    taskScope: LifecycleTaskScope,
    private val operationLocks: BuilderOperationLocks,
    private val playerLookup: (UUID) -> Player?,
    private val restoreInventory: (Player, BuilderJournalRecord) -> Unit,
    private val acknowledgeAsync: (BuilderJournalRecord, (Throwable?) -> Unit) -> Unit,
    private val releaseReservation: (BuilderPlan) -> Unit,
    private val onTerminalFailure: (BuilderJournalRecord, Throwable) -> Unit,
    private val onAcknowledgementPending: (BuilderJournalRecord, Throwable) -> Unit,
    private val onAcknowledgementRecovered: (BuilderJournalRecord) -> Unit,
    private val onResolved: (UUID) -> Unit,
) : AutoCloseable {
    private data class PendingRecovery(
        val record: BuilderJournalRecord,
        var inventoryRestored: Boolean = false,
        var acknowledgementInFlight: Boolean = false,
        var acknowledgementFailureReported: Boolean = false,
        var terminalFailure: Boolean = false,
    )

    private val pending = linkedMapOf<UUID, PendingRecovery>()
    private val retryTask: ScheduledTask = checkNotNull(
        taskScope.runTimer(RETRY_PERIOD_TICKS, RETRY_PERIOD_TICKS, ::retryPending),
    ) { "Builder player-recovery retry task was not scheduled" }
    private var closed = false

    val pendingCount: Int get() = pending.size

    fun contains(playerId: UUID): Boolean = playerId in pending

    fun record(operationId: UUID): BuilderJournalRecord? =
        pending.values.firstOrNull { it.record.operationId == operationId }?.record

    /** Keeps an ambiguous APPLYING record and its player locked for restart or operator recovery. */
    fun hold(record: BuilderJournalRecord) {
        check(!closed) { "Builder player-recovery coordinator is closed" }
        require(record.phase == BuilderJournalPhase.APPLYING) {
            "Only APPLYING builder records can require a manual recovery hold"
        }
        val existing = pending[record.playerId]
        if (existing != null) {
            require(existing.record == record) {
                "Builder player already has a different pending recovery"
            }
            return
        }
        operationLocks.lockRecovery(record.playerId)
        pending[record.playerId] = PendingRecovery(record, terminalFailure = true)
    }

    fun add(record: BuilderJournalRecord, inventoryRestored: Boolean = false): Boolean {
        check(!closed) { "Builder player-recovery coordinator is closed" }
        require(record.phase == BuilderJournalPhase.APPLYING || inventoryRestored) {
            "PREPARED builder records may only be acknowledged without inventory restoration"
        }
        val existing = pending[record.playerId]
        if (existing != null) {
            require(existing.record == record) {
                "Builder player already has a different pending recovery"
            }
            require(existing.inventoryRestored == inventoryRestored || existing.inventoryRestored) {
                "Builder player recovery cannot forget a required inventory restoration"
            }
            return !existing.terminalFailure
        }
        operationLocks.lockRecovery(record.playerId)
        val entry = PendingRecovery(record, inventoryRestored = inventoryRestored)
        pending[record.playerId] = entry

        // Queue the exact idempotent release before acknowledgement. The book
        // registry also retains the reservation identity, so startup
        // reconciliation can reconstruct the same release after a crash.
        try {
            releaseReservation(record.plan)
        } catch (failure: Throwable) {
            entry.terminalFailure = true
            onTerminalFailure(record, failure)
            return false
        }
        playerLookup(record.playerId)
            ?.takeIf { it.isOnline && !it.isDead }
            ?.let { attempt(entry, it) }
        return !entry.terminalFailure
    }

    /** Returns true when this player remains owned by the recovery lifecycle. */
    fun onPlayerAvailable(player: Player): Boolean {
        val entry = pending[player.uniqueId] ?: return false
        attempt(entry, player)
        return true
    }

    private fun retryPending() {
        if (closed) return
        pending.values.toList().forEach { entry ->
            val player = if (entry.inventoryRestored) null else playerLookup(entry.record.playerId)
            attempt(entry, player)
        }
    }

    private fun attempt(entry: PendingRecovery, player: Player?) {
        if (
            closed || pending[entry.record.playerId] !== entry || entry.acknowledgementInFlight ||
            entry.terminalFailure
        ) {
            return
        }
        if (!entry.inventoryRestored) {
            if (player?.isOnline != true || player.isDead) return
            try {
                restoreInventory(player, entry.record)
                entry.inventoryRestored = true
            } catch (failure: Throwable) {
                entry.terminalFailure = true
                onTerminalFailure(entry.record, failure)
                return
            }
        }
        entry.acknowledgementInFlight = true
        try {
            acknowledgeAsync(entry.record) { failure -> completeAcknowledgement(entry, failure) }
        } catch (failure: Throwable) {
            completeAcknowledgement(entry, failure)
        }
    }

    private fun completeAcknowledgement(entry: PendingRecovery, failure: Throwable?) {
        if (closed || pending[entry.record.playerId] !== entry) return
        entry.acknowledgementInFlight = false
        if (failure != null) {
            if (!entry.acknowledgementFailureReported) {
                entry.acknowledgementFailureReported = true
                onAcknowledgementPending(entry.record, failure)
            }
            return
        }
        pending.remove(entry.record.playerId, entry)
        operationLocks.unlockRecovery(entry.record.playerId)
        if (entry.acknowledgementFailureReported) onAcknowledgementRecovered(entry.record)
        onResolved(entry.record.playerId)
    }

    override fun close() {
        if (closed) return
        closed = true
        retryTask.cancel()
        pending.keys.toList().forEach(operationLocks::unlockRecovery)
        pending.clear()
    }

    private companion object {
        const val RETRY_PERIOD_TICKS = 100L
    }
}
