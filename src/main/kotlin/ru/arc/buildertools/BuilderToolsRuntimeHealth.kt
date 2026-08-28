package ru.arc.buildertools

import ru.arc.observability.RuntimeHealthContribution
import ru.arc.observability.RuntimeHealthState

/** Main-thread inputs published as one immutable, bounded health contribution. */
internal data class BuilderToolsRuntimeHealthInputs(
    val closed: Boolean,
    val recovering: Boolean,
    val recoveryBlocked: Boolean,
    val recoveryPlayers: Int,
    val deliveryWaitingForSpace: Int,
    val reservationReleaseBacklog: Int,
    val activeOperations: Int,
    val bookLockedPlayers: Int,
    val landsRequired: Boolean,
    val landsAvailable: Boolean,
    val coreProtectRequired: Boolean,
    val coreProtectAvailable: Boolean,
    val shopRequired: Boolean,
    val shopAvailable: Boolean,
    val bookContractsEnabled: Boolean,
    val bookRegistryReady: Boolean,
    val bookRegistryFailed: Boolean,
    val draftJournalReady: Boolean,
    val draftJournalFailed: Boolean,
) {
    init {
        listOf(
            recoveryPlayers,
            deliveryWaitingForSpace,
            reservationReleaseBacklog,
            activeOperations,
            bookLockedPlayers,
        ).forEach { require(it >= 0) { "Builder-tools health counters must not be negative" } }
    }
}

internal object BuilderToolsRuntimeHealth {
    fun contribution(input: BuilderToolsRuntimeHealthInputs): RuntimeHealthContribution {
        val landsReady = !input.landsRequired || input.landsAvailable
        val coreProtectReady = !input.coreProtectRequired || input.coreProtectAvailable
        val shopReady = !input.shopRequired || input.shopAvailable
        val registryReady = !input.bookContractsEnabled || (input.bookRegistryReady && !input.bookRegistryFailed)
        val draftJournalReady = input.draftJournalReady && !input.draftJournalFailed
        val backlog = saturatedAdd(
            saturatedAdd(input.recoveryPlayers, input.deliveryWaitingForSpace),
            input.reservationReleaseBacklog,
        )
        val leases = saturatedAdd(input.activeOperations, input.bookLockedPlayers)
        val state = when {
            input.closed || input.recoveryBlocked || input.draftJournalFailed || !landsReady || !coreProtectReady ->
                RuntimeHealthState.DOWN
            input.recovering || !input.draftJournalReady ||
                (input.bookContractsEnabled && !input.bookRegistryReady && !input.bookRegistryFailed) ->
                RuntimeHealthState.STARTING
            input.bookRegistryFailed || !shopReady || backlog > 0 -> RuntimeHealthState.DEGRADED
            else -> RuntimeHealthState.UP
        }
        return RuntimeHealthContribution(
            state = state,
            recoveryBacklog = backlog,
            activeLeases = leases,
            schemas = buildMap {
                put("builder_drafts", BuilderDraftRecord.CURRENT_SCHEMA_VERSION)
                if (input.bookContractsEnabled) put("book_registry", BuilderBookSqlRegistry.CURRENT_SCHEMA_VERSION)
            },
            dependencies = linkedMapOf(
                "lands" to landsReady,
                "coreprotect" to coreProtectReady,
                "shop" to shopReady,
                "book_registry" to registryReady,
                "builder_drafts" to draftJournalReady,
            ),
        )
    }

    private fun saturatedAdd(first: Int, second: Int): Int =
        (first.toLong() + second.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
