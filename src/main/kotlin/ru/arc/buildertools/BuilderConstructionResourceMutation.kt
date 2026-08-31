package ru.arc.buildertools

import java.util.UUID

internal enum class BuilderResourceSourceKind {
    PLAYER,
    CONTAINER,
}

/** Durable identity plus exact before/after contents for one inventory. */
internal data class BuilderResourceInventoryMutation(
    val kind: BuilderResourceSourceKind,
    val playerId: UUID? = null,
    val containerBlocks: List<BuilderBlockPos> = emptyList(),
    val requireNearProject: Boolean = true,
    val before: List<String?>,
    val after: List<String?>,
) {
    fun validated(projectPlayerId: UUID): BuilderResourceInventoryMutation = apply {
        require(before.size == after.size && before.size in 1..MAX_INVENTORY_SLOTS) {
            "Builder construction resource snapshot size is invalid"
        }
        require(before != after) { "Builder construction resource snapshot does not mutate inventory" }
        when (kind) {
            BuilderResourceSourceKind.PLAYER -> {
                require(playerId == projectPlayerId && containerBlocks.isEmpty()) {
                    "Builder construction player resource identity is invalid"
                }
            }
            BuilderResourceSourceKind.CONTAINER -> {
                require(playerId == null && !requireNearProject && containerBlocks.size in 1..2) {
                    "Builder construction container resource identity is invalid"
                }
                require(containerBlocks.map(BuilderBlockPos::worldId).toSet().size == 1) {
                    "Builder construction container resource crosses worlds"
                }
                containerBlocks.forEach(BuilderBlockPos::validated)
            }
        }
        val payloads = before.asSequence().plus(after.asSequence()).filterNotNull().toList()
        require(payloads.all { it.length in 4..MAX_ITEM_PAYLOAD_CHARS && ITEM_PAYLOAD.matches(it) }) {
            "Builder construction resource item payload is invalid"
        }
        require(payloads.sumOf(String::length) <= MAX_INVENTORY_PAYLOAD_CHARS) {
            "Builder construction resource inventory payload is too large"
        }
    }

    companion object {
        private const val MAX_INVENTORY_SLOTS = 54
        private const val MAX_ITEM_PAYLOAD_CHARS = 1_400_000
        private const val MAX_INVENTORY_PAYLOAD_CHARS = 16_000_000
        private val ITEM_PAYLOAD = Regex("[A-Za-z0-9+/]+={0,2}")
    }
}

/**
 * Write-ahead resource receipt. Replaying it compares exact inventory state
 * with [BuilderResourceInventoryMutation.before]/after before writing anything.
 */
internal data class BuilderResourceMutation(
    val amount: BuilderItemAmount,
    val insert: Boolean,
    val sources: List<BuilderResourceInventoryMutation>,
) {
    fun validated(projectPlayerId: UUID): BuilderResourceMutation = apply {
        amount.validated()
        require(sources.size in 1..MAX_MUTATION_SOURCES) {
            "Builder construction resource mutation source count is invalid"
        }
        sources.forEach { it.validated(projectPlayerId) }
        require(
            sources.asSequence()
                .flatMap { it.before.asSequence().plus(it.after.asSequence()) }
                .filterNotNull()
                .sumOf { it.length.toLong() } <= MAX_MUTATION_PAYLOAD_CHARS,
        ) { "Builder construction resource mutation payload is too large" }
        require(sources.map(::sourceIdentity).distinct().size == sources.size) {
            "Builder construction resource mutation repeats an inventory"
        }
    }

    private fun sourceIdentity(source: BuilderResourceInventoryMutation): String = when (source.kind) {
        BuilderResourceSourceKind.PLAYER -> "player:${source.playerId}"
        BuilderResourceSourceKind.CONTAINER -> source.containerBlocks.joinToString("|") {
            "${it.worldId}:${it.x}:${it.y}:${it.z}"
        }
    }

    companion object {
        const val MAX_MUTATION_SOURCES = 32
        private const val MAX_MUTATION_PAYLOAD_CHARS = 48_000_000L
    }
}

internal enum class BuilderResourceMutationResult {
    /** Every source exactly matches the requested target snapshot. */
    APPLIED,

    /** A player/world/chunk is temporarily unavailable; retry without guessing. */
    RETRY,

    /** No source shows evidence of this mutation and the plan may be rebuilt. */
    STALE,

    /** At least one source drifted after a partial/complete external effect. */
    CONFLICT,
}
