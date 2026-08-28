package ru.arc.buildertools

import dev.unnm3d.rediseconomy.api.RedisEconomyAPI
import dev.unnm3d.rediseconomy.transaction.AccountID
import net.milkbowl.vault.economy.EconomyResponse
import java.util.UUID
import java.util.concurrent.CompletableFuture

internal data class BuilderBookMoneyEvidence(
    val providerAccepted: Boolean?,
    val providerCallAttempted: Boolean,
    val balanceAfterMinor: Long?,
    val failureCode: String? = null,
)

internal data class BuilderBookProviderTransaction(
    val transactionId: String?,
    val historyAvailable: Boolean,
    val exhaustiveSinceRequest: Boolean = false,
) {
    val found: Boolean get() = transactionId != null
}

/** Main-thread economy boundary with exact minor-unit evidence. */
internal interface BuilderBookWallet {
    val available: Boolean

    fun balanceMinor(playerId: UUID): Long?

    fun withdraw(playerId: UUID, amountMinor: Long, reason: String, expectedBalanceBeforeMinor: Long): BuilderBookMoneyEvidence

    fun deposit(playerId: UUID, amountMinor: Long, reason: String, expectedBalanceBeforeMinor: Long): BuilderBookMoneyEvidence

    fun findTransaction(
        playerId: UUID,
        amountMinor: Long,
        reason: String,
        notBeforeMillis: Long,
    ): CompletableFuture<BuilderBookProviderTransaction> =
        CompletableFuture.completedFuture(BuilderBookProviderTransaction(null, false))
}

/**
 * RedisEconomy 4.5.12 compile-baseline adapter, verified API-compatible with
 * the active 4.5.13 runtime. Mutation calls are never retried.
 */
internal class RedisEconomyBuilderBookWallet(
    private val apiProvider: () -> RedisEconomyAPI? = RedisEconomyAPI::getAPI,
) : BuilderBookWallet {
    override val available: Boolean get() = apiProvider()?.defaultCurrency != null

    override fun balanceMinor(playerId: UUID): Long? =
        runCatching { apiProvider()?.defaultCurrency?.getBalance(playerId)?.let(BuilderMoney::balanceFromProvider) }.getOrNull()

    override fun withdraw(
        playerId: UUID,
        amountMinor: Long,
        reason: String,
        expectedBalanceBeforeMinor: Long,
    ): BuilderBookMoneyEvidence = mutate(playerId, amountMinor, reason, expectedBalanceBeforeMinor, deposit = false)

    override fun deposit(
        playerId: UUID,
        amountMinor: Long,
        reason: String,
        expectedBalanceBeforeMinor: Long,
    ): BuilderBookMoneyEvidence = mutate(playerId, amountMinor, reason, expectedBalanceBeforeMinor, deposit = true)

    override fun findTransaction(
        playerId: UUID,
        amountMinor: Long,
        reason: String,
        notBeforeMillis: Long,
    ): CompletableFuture<BuilderBookProviderTransaction> {
        require(amountMinor != 0L) { "Builder-book provider history amount cannot be zero" }
        require(REASON.matches(reason)) { "Invalid builder-book provider history reason" }
        val api = apiProvider() ?: return CompletableFuture.completedFuture(BuilderBookProviderTransaction(null, false))
        val currency = api.defaultCurrency
        if (!currency.shouldSaveTransactions()) {
            return CompletableFuture.completedFuture(BuilderBookProviderTransaction(null, false))
        }
        return api.exchange.getTransactions(AccountID(playerId), HISTORY_LIMIT).toCompletableFuture().handle { transactions, failure ->
            // RedisEconomy 4.5.12 converts its own read failures to an empty
            // TreeMap. Empty therefore cannot prove absence and must fail closed.
            if (failure != null || transactions.isNullOrEmpty()) {
                return@handle BuilderBookProviderTransaction(null, false)
            }
            val match = transactions.entries.firstOrNull { (_, transaction) ->
                transaction.timestamp >= notBeforeMillis &&
                    transaction.currencyName == currency.currencyName &&
                    transaction.reason.orEmpty().lineSequence().firstOrNull() == reason &&
                    runCatching { BuilderMoney.signedFromProvider(transaction.amount) }.getOrNull() == amountMinor
            }
            val oldestTimestamp = transactions.values.minOf { it.timestamp }
            BuilderBookProviderTransaction(
                transactionId = match?.key?.toString(),
                historyAvailable = true,
                exhaustiveSinceRequest = match != null || transactions.size < HISTORY_LIMIT || oldestTimestamp <= notBeforeMillis,
            )
        }
    }

    private fun mutate(
        playerId: UUID,
        amountMinor: Long,
        reason: String,
        expectedBalanceBeforeMinor: Long,
        deposit: Boolean,
    ): BuilderBookMoneyEvidence {
        require(amountMinor > 0L) { "Builder-book money mutation must be positive" }
        require(REASON.matches(reason)) { "Invalid builder-book money mutation reason" }
        val currency = apiProvider()?.defaultCurrency
            ?: return BuilderBookMoneyEvidence(false, false, null, "provider_unavailable")
        val before = runCatching { BuilderMoney.balanceFromProvider(currency.getBalance(playerId)) }.getOrNull()
            ?: return BuilderBookMoneyEvidence(false, false, null, "provider_balance_unavailable")
        if (before != expectedBalanceBeforeMinor) {
            return BuilderBookMoneyEvidence(false, false, before, "provider_balance_changed_before_call")
        }
        if (currency.transactionTax != 0.0) {
            return BuilderBookMoneyEvidence(false, false, before, "provider_transaction_tax_nonzero")
        }
        val response: EconomyResponse = try {
            if (deposit) {
                currency.depositPlayer(playerId, currency.currencyName, BuilderMoney.toProviderDouble(amountMinor), reason)
            } else {
                currency.withdrawPlayer(playerId, currency.currencyName, BuilderMoney.toProviderDouble(amountMinor), reason)
            }
        } catch (_: Throwable) {
            return BuilderBookMoneyEvidence(
                providerAccepted = null,
                providerCallAttempted = true,
                balanceAfterMinor = runCatching { BuilderMoney.balanceFromProvider(currency.getBalance(playerId)) }.getOrNull(),
                failureCode = "provider_threw",
            )
        }
        val after = runCatching { BuilderMoney.balanceFromProvider(currency.getBalance(playerId)) }.getOrNull()
        val expectedAfter = if (deposit) Math.addExact(before, amountMinor) else Math.subtractExact(before, amountMinor)
        return BuilderBookMoneyEvidence(
            providerAccepted = response.transactionSuccess() && after == expectedAfter,
            providerCallAttempted = true,
            balanceAfterMinor = after,
            failureCode = when {
                !response.transactionSuccess() -> "provider_rejected"
                after != expectedAfter -> "provider_balance_mismatch"
                else -> null
            },
        )
    }

    private companion object {
        const val HISTORY_LIMIT = 512
        val REASON = Regex("arc-builder-book(?:-refund)?:[0-9a-f-]{36}")
    }
}
