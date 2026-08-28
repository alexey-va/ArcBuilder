package ru.arc.buildertools

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal sealed interface BuilderBookMintResult {
    data class Issued(val mint: BuilderBookMint) : BuilderBookMintResult
    data object Busy : BuilderBookMintResult
    data object EconomyUnavailable : BuilderBookMintResult
    data object InsufficientFunds : BuilderBookMintResult
    data object PaymentRejected : BuilderBookMintResult
    data object RegistryUnavailable : BuilderBookMintResult
    data object Refunded : BuilderBookMintResult
    data object ManualReview : BuilderBookMintResult
}

/**
 * Crash-safe paid mint state machine. Public entry points are called on the
 * Paper thread; registry completions return through [runSync] before touching
 * the wallet or invoking the callback. Provider mutations are attempted at
 * most once and an unprovable outcome is quarantined for restart recovery.
 */
internal class BuilderBookMintCoordinator(
    private val registry: BuilderBookRegistry,
    private val wallet: BuilderBookWallet,
    private val runSync: (() -> Unit) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onManualReview: (BuilderBookMint) -> Unit = {},
) {
    private val activePlayers = ConcurrentHashMap.newKeySet<UUID>()

    fun mint(intent: BuilderBookMint, callback: (BuilderBookMintResult) -> Unit) {
        val checked = intent.validated()
        require(checked.status == BuilderBookMintStatus.PREPARED) { "Builder-book mint intent must be PREPARED" }
        if (!wallet.available) return callback(BuilderBookMintResult.EconomyUnavailable)
        if (!activePlayers.add(checked.playerId)) return callback(BuilderBookMintResult.Busy)
        registry.hasOpenMint(checked.playerId).whenComplete { open, openFailure ->
            runSync {
                if (openFailure != null || open == null) {
                    return@runSync finish(checked.playerId, callback, BuilderBookMintResult.RegistryUnavailable)
                }
                if (open) return@runSync finish(checked.playerId, callback, BuilderBookMintResult.Busy)
                registry.prepareMint(checked).whenComplete { prepared, prepareFailure ->
                    runSync prepare@{
                        if (prepareFailure != null || prepared != true) {
                            return@prepare finish(checked.playerId, callback, BuilderBookMintResult.RegistryUnavailable)
                        }
                        beginWithdrawal(checked, callback)
                    }
                }
            }
        }
    }

    /** Reconciles one durable non-terminal mint after restart without blindly retrying money mutations. */
    fun recover(record: BuilderBookMint, callback: (BuilderBookMintResult) -> Unit) {
        val checked = record.validated()
        if (!activePlayers.add(checked.playerId)) return callback(BuilderBookMintResult.Busy)
        when (checked.status) {
            BuilderBookMintStatus.PREPARED -> transitionTerminal(
                checked,
                checked.advance(BuilderBookMintStatus.CANCELLED, clock(), "restart_before_withdrawal"),
                callback,
                BuilderBookMintResult.PaymentRejected,
            )
            BuilderBookMintStatus.WITHDRAWAL_STARTED -> reconcileWithdrawal(checked, callback)
            BuilderBookMintStatus.FUNDS_WITHDRAWN -> issue(checked, callback)
            BuilderBookMintStatus.ISSUED,
            BuilderBookMintStatus.COMPLETED,
            -> finish(checked.playerId, callback, BuilderBookMintResult.Issued(checked))
            BuilderBookMintStatus.REFUND_STARTED -> reconcileRefund(checked, callback)
            BuilderBookMintStatus.REFUNDED -> finish(checked.playerId, callback, BuilderBookMintResult.Refunded)
            BuilderBookMintStatus.CANCELLED -> finish(checked.playerId, callback, BuilderBookMintResult.PaymentRejected)
            BuilderBookMintStatus.MANUAL_REVIEW -> {
                onManualReview(checked)
                finish(checked.playerId, callback, BuilderBookMintResult.ManualReview)
            }
        }
    }

    fun clear() {
        activePlayers.clear()
    }

    private fun beginWithdrawal(intent: BuilderBookMint, callback: (BuilderBookMintResult) -> Unit) {
        val balanceBefore = wallet.balanceMinor(intent.playerId)
        if (balanceBefore == null) {
            return transitionTerminal(
                intent,
                intent.advance(BuilderBookMintStatus.CANCELLED, clock(), "balance_unavailable"),
                callback,
                BuilderBookMintResult.EconomyUnavailable,
            )
        }
        if (balanceBefore < intent.blueprint.issuePriceMinor) {
            return transitionTerminal(
                intent,
                intent.advance(BuilderBookMintStatus.CANCELLED, clock(), "insufficient_funds"),
                callback,
                BuilderBookMintResult.InsufficientFunds,
            )
        }
        val started = intent.copy(
            status = BuilderBookMintStatus.WITHDRAWAL_STARTED,
            updatedAtMillis = nextTime(intent.updatedAtMillis),
            balanceBeforeMinor = balanceBefore,
        ).validated()
        registry.transitionMint(intent, started).whenComplete { durable, transitionFailure ->
            runSync {
                if (transitionFailure != null || durable == null) {
                    finish(intent.playerId, callback, BuilderBookMintResult.RegistryUnavailable)
                    return@runSync
                }
                withdraw(durable, callback)
            }
        }
    }

    private fun withdraw(started: BuilderBookMint, callback: (BuilderBookMintResult) -> Unit) {
        val before = requireNotNull(started.balanceBeforeMinor)
        val amount = started.blueprint.issuePriceMinor
        val reason = reason(started.transactionId)
        val evidence = wallet.withdraw(started.playerId, amount, reason, before)
        val expectedAfter = before - amount
        if (evidence.balanceAfterMinor != expectedAfter) {
            val unchanged = evidence.balanceAfterMinor == before && evidence.providerAccepted == false
            val next = if (unchanged) {
                started.advance(BuilderBookMintStatus.CANCELLED, clock(), evidence.failureCode ?: "provider_rejected")
            } else {
                started.advance(BuilderBookMintStatus.MANUAL_REVIEW, clock(), evidence.failureCode ?: "ambiguous_withdrawal")
            }
            registry.transitionMint(started, next).whenComplete { durable, _ ->
                runSync {
                    if (!unchanged) durable?.let(onManualReview)
                    finish(
                        started.playerId,
                        callback,
                        when {
                            durable == null -> BuilderBookMintResult.ManualReview
                            unchanged -> BuilderBookMintResult.PaymentRejected
                            else -> BuilderBookMintResult.ManualReview
                        },
                    )
                }
            }
            return
        }
        val withdrawn = started.copy(
            status = BuilderBookMintStatus.FUNDS_WITHDRAWN,
            updatedAtMillis = nextTime(started.updatedAtMillis),
            balanceAfterMinor = expectedAfter,
            evidence = "exact_balance_delta",
        ).validated()
        registry.transitionMint(started, withdrawn).whenComplete { durable, transitionFailure ->
            runSync {
                if (transitionFailure != null || durable == null) {
                    quarantineUnknown(started, callback)
                } else {
                    issue(durable, callback)
                }
            }
        }
    }

    private fun issue(withdrawn: BuilderBookMint, callback: (BuilderBookMintResult) -> Unit) {
        registry.issuePaidMint(withdrawn.transactionId, clock()).whenComplete { issued, issueFailure ->
            runSync {
                if (issueFailure == null && issued != null) {
                    finish(withdrawn.playerId, callback, BuilderBookMintResult.Issued(issued))
                    return@runSync
                }
                registry.loadMint(withdrawn.transactionId).whenComplete { current, readFailure ->
                    runSync {
                        when {
                            readFailure == null && current?.status in setOf(
                                BuilderBookMintStatus.ISSUED,
                                BuilderBookMintStatus.COMPLETED,
                            ) -> finish(withdrawn.playerId, callback, BuilderBookMintResult.Issued(checkNotNull(current)))
                            readFailure == null && current?.status == BuilderBookMintStatus.FUNDS_WITHDRAWN -> refund(current, callback)
                            else -> {
                                current?.let(onManualReview)
                                finish(withdrawn.playerId, callback, BuilderBookMintResult.ManualReview)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun refund(record: BuilderBookMint, callback: (BuilderBookMintResult) -> Unit) {
        val before = wallet.balanceMinor(record.playerId)
        if (before == null) return quarantine(record, "refund_balance_unavailable", callback)
        val started = record.copy(
            status = BuilderBookMintStatus.REFUND_STARTED,
            updatedAtMillis = nextTime(record.updatedAtMillis),
            refundBalanceBeforeMinor = before,
            evidence = "instance_issue_failed",
        ).validated()
        registry.transitionMint(record, started).whenComplete { durable, transitionFailure ->
            runSync {
                if (transitionFailure != null || durable == null) {
                    quarantine(record, "refund_journal_unavailable", callback)
                    return@runSync
                }
                val evidence = wallet.deposit(
                    durable.playerId,
                    durable.blueprint.issuePriceMinor,
                    refundReason(durable.transactionId),
                    before,
                )
                val expectedAfter = before + durable.blueprint.issuePriceMinor
                if (evidence.balanceAfterMinor == expectedAfter) {
                    val refunded = durable.copy(
                        status = BuilderBookMintStatus.REFUNDED,
                        updatedAtMillis = nextTime(durable.updatedAtMillis),
                        refundBalanceAfterMinor = expectedAfter,
                        evidence = "exact_refund_balance_delta",
                    ).validated()
                    registry.transitionMint(durable, refunded).whenComplete { saved, _ ->
                        runSync {
                            finish(
                                durable.playerId,
                                callback,
                                if (saved != null) BuilderBookMintResult.Refunded else BuilderBookMintResult.ManualReview,
                            )
                        }
                    }
                } else {
                    quarantine(durable, evidence.failureCode ?: "ambiguous_refund", callback)
                }
            }
        }
    }

    private fun reconcileWithdrawal(record: BuilderBookMint, callback: (BuilderBookMintResult) -> Unit) {
        val before = record.balanceBeforeMinor ?: return quarantine(record, "withdrawal_missing_before_balance", callback)
        val amount = record.blueprint.issuePriceMinor
        wallet.findTransaction(record.playerId, -amount, reason(record.transactionId), record.createdAtMillis)
            .whenComplete { provider, failure ->
                runSync {
                    if (failure != null || provider == null || !provider.historyAvailable) {
                        quarantine(record, "withdrawal_history_unavailable", callback)
                        return@runSync
                    }
                    if (!provider.found) {
                        if (!provider.exhaustiveSinceRequest) {
                            quarantine(record, "withdrawal_history_window_incomplete", callback)
                            return@runSync
                        }
                        if (wallet.balanceMinor(record.playerId) == before) {
                            transitionTerminal(
                                record,
                                record.advance(BuilderBookMintStatus.CANCELLED, clock(), "withdrawal_absent_after_restart"),
                                callback,
                                BuilderBookMintResult.PaymentRejected,
                            )
                        } else {
                            quarantine(record, "withdrawal_absent_balance_changed", callback)
                        }
                        return@runSync
                    }
                    val withdrawn = record.copy(
                        status = BuilderBookMintStatus.FUNDS_WITHDRAWN,
                        updatedAtMillis = nextTime(record.updatedAtMillis),
                        balanceAfterMinor = before - amount,
                        providerTransactionId = provider.transactionId,
                        evidence = "provider_history_withdrawal",
                    ).validated()
                    registry.transitionMint(record, withdrawn).whenComplete { durable, transitionFailure ->
                        runSync {
                            if (transitionFailure != null || durable == null) {
                                quarantine(record, "withdrawal_recovery_journal_failed", callback)
                            } else {
                                issue(durable, callback)
                            }
                        }
                    }
                }
            }
    }

    private fun reconcileRefund(record: BuilderBookMint, callback: (BuilderBookMintResult) -> Unit) {
        val before = record.refundBalanceBeforeMinor ?: return quarantine(record, "refund_missing_before_balance", callback)
        val amount = record.blueprint.issuePriceMinor
        wallet.findTransaction(record.playerId, amount, refundReason(record.transactionId), record.createdAtMillis)
            .whenComplete { provider, failure ->
                runSync {
                    if (failure != null || provider == null || !provider.historyAvailable) {
                        quarantine(record, "refund_history_unavailable", callback)
                        return@runSync
                    }
                    if (provider.found) {
                        val refunded = record.copy(
                            status = BuilderBookMintStatus.REFUNDED,
                            updatedAtMillis = nextTime(record.updatedAtMillis),
                            refundBalanceAfterMinor = before + amount,
                            providerTransactionId = provider.transactionId,
                            evidence = "provider_history_refund",
                        ).validated()
                        registry.transitionMint(record, refunded).whenComplete { durable, _ ->
                            runSync {
                                finish(
                                    record.playerId,
                                    callback,
                                    if (durable != null) BuilderBookMintResult.Refunded else BuilderBookMintResult.ManualReview,
                                )
                            }
                        }
                        return@runSync
                    }
                    if (!provider.exhaustiveSinceRequest) {
                        quarantine(record, "refund_history_window_incomplete", callback)
                        return@runSync
                    }
                    if (wallet.balanceMinor(record.playerId) != before) {
                        quarantine(record, "refund_absent_balance_changed", callback)
                        return@runSync
                    }
                    val evidence = wallet.deposit(record.playerId, amount, refundReason(record.transactionId), before)
                    val expectedAfter = before + amount
                    if (evidence.balanceAfterMinor != expectedAfter) {
                        quarantine(record, evidence.failureCode ?: "ambiguous_refund_recovery", callback)
                        return@runSync
                    }
                    val refunded = record.copy(
                        status = BuilderBookMintStatus.REFUNDED,
                        updatedAtMillis = nextTime(record.updatedAtMillis),
                        refundBalanceAfterMinor = expectedAfter,
                        evidence = "exact_restart_refund_delta",
                    ).validated()
                    registry.transitionMint(record, refunded).whenComplete { durable, _ ->
                        runSync {
                            finish(
                                record.playerId,
                                callback,
                                if (durable != null) BuilderBookMintResult.Refunded else BuilderBookMintResult.ManualReview,
                            )
                        }
                    }
                }
            }
    }

    private fun quarantineUnknown(started: BuilderBookMint, callback: (BuilderBookMintResult) -> Unit) {
        registry.loadMint(started.transactionId).whenComplete { current, _ ->
            runSync {
                val record = current ?: started
                quarantine(record, "withdrawal_journal_unknown", callback)
            }
        }
    }

    private fun quarantine(record: BuilderBookMint, evidence: String, callback: (BuilderBookMintResult) -> Unit) {
        val manual = runCatching { record.advance(BuilderBookMintStatus.MANUAL_REVIEW, clock(), evidence) }.getOrElse { record }
        if (manual == record) {
            onManualReview(record)
            finish(record.playerId, callback, BuilderBookMintResult.ManualReview)
            return
        }
        registry.transitionMint(record, manual).whenComplete { durable, _ ->
            runSync {
                (durable ?: manual).let(onManualReview)
                finish(record.playerId, callback, BuilderBookMintResult.ManualReview)
            }
        }
    }

    private fun transitionTerminal(
        expected: BuilderBookMint,
        terminal: BuilderBookMint,
        callback: (BuilderBookMintResult) -> Unit,
        result: BuilderBookMintResult,
    ) {
        registry.transitionMint(expected, terminal).whenComplete { durable, _ ->
            runSync {
                finish(
                    expected.playerId,
                    callback,
                    if (durable == null) BuilderBookMintResult.RegistryUnavailable else result,
                )
            }
        }
    }

    private fun finish(playerId: UUID, callback: (BuilderBookMintResult) -> Unit, result: BuilderBookMintResult) {
        activePlayers.remove(playerId)
        callback(result)
    }

    private fun nextTime(previous: Long): Long = maxOf(clock(), previous + 1)

    companion object {
        fun reason(transactionId: UUID): String = "arc-builder-book:$transactionId"
        fun refundReason(transactionId: UUID): String = "arc-builder-book-refund:$transactionId"
    }
}
