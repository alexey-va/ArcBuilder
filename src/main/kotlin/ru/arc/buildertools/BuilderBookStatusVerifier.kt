package ru.arc.buildertools

import ru.arc.autobuild.BuildBookData
import java.util.UUID
import java.util.concurrent.CompletableFuture

internal data class BuilderBookPresentedIdentity(
    val blueprintId: UUID,
    val instanceId: UUID,
    val generation: Int,
) {
    init {
        require(generation > 0) { "Builder-book presented generation must be positive" }
    }

    companion object {
        fun from(data: BuildBookData?): BuilderBookPresentedIdentity? {
            if (data?.available != true) return null
            return BuilderBookPresentedIdentity(
                blueprintId = data.blueprintId ?: return null,
                instanceId = data.instanceId ?: return null,
                generation = data.instanceGeneration ?: return null,
            )
        }
    }
}

internal sealed interface BuilderBookStatusVerification {
    data class Active(val blueprint: BuilderBookBlueprint) : BuilderBookStatusVerification
    data object Stale : BuilderBookStatusVerification
    data object SourceChanged : BuilderBookStatusVerification
    data object RegistryUnavailable : BuilderBookStatusVerification
}

internal enum class BuilderBookStatusLookupStart {
    STARTED,
    ALREADY_PENDING,
    CLOSED,
}

internal object BuilderBookStatusLookupPolicy {
    fun shouldVerify(
        hasQuote: Boolean,
        identity: BuilderBookPresentedIdentity?,
        hasAuctionToken: Boolean,
    ): Boolean = !hasQuote && identity != null && !hasAuctionToken
}

/**
 * Main-thread owner of read-only authoritative book-status lookups.
 *
 * The SQL future completes off the Paper thread. Every held-item read and
 * player-facing callback is marshalled through [runSync]. At most one lookup
 * per player is in flight, and late completions are ignored after [close].
 */
internal class BuilderBookStatusVerifier(
    private val loadInstance: (UUID) -> CompletableFuture<BuilderBookInstance?>,
    private val loadBlueprint: (UUID) -> CompletableFuture<BuilderBookBlueprint?>,
    private val runSync: (() -> Unit) -> Unit,
) : AutoCloseable {
    private data class Authority(
        val instance: BuilderBookInstance?,
        val blueprint: BuilderBookBlueprint?,
    )

    private val pendingPlayers = mutableSetOf<UUID>()
    private var closed = false

    fun verify(
        playerId: UUID,
        expected: BuilderBookPresentedIdentity,
        currentIdentity: () -> BuilderBookPresentedIdentity?,
        complete: (BuilderBookStatusVerification) -> Unit,
    ): BuilderBookStatusLookupStart {
        if (closed) return BuilderBookStatusLookupStart.CLOSED
        if (!pendingPlayers.add(playerId)) return BuilderBookStatusLookupStart.ALREADY_PENDING

        val lookup = runCatching {
            loadInstance(expected.instanceId).thenCompose { instance ->
                if (!matchesInstance(playerId, expected, instance)) {
                    CompletableFuture.completedFuture(Authority(instance, null))
                } else {
                    loadBlueprint(expected.blueprintId).thenApply { blueprint -> Authority(instance, blueprint) }
                }
            }
        }
            .getOrElse { CompletableFuture.failedFuture(it) }
        lookup.whenComplete { authority, failure ->
            runSync {
                pendingPlayers.remove(playerId)
                if (closed) return@runSync
                val result = when {
                    currentIdentity() != expected -> BuilderBookStatusVerification.SourceChanged
                    failure != null -> BuilderBookStatusVerification.RegistryUnavailable
                    !matchesInstance(playerId, expected, authority?.instance) -> BuilderBookStatusVerification.Stale
                    authority.blueprint?.blueprintId != expected.blueprintId -> BuilderBookStatusVerification.Stale
                    else -> BuilderBookStatusVerification.Active(checkNotNull(authority?.blueprint))
                }
                complete(result)
            }
        }
        return BuilderBookStatusLookupStart.STARTED
    }

    override fun close() {
        closed = true
        pendingPlayers.clear()
    }

    private fun matchesInstance(
        playerId: UUID,
        expected: BuilderBookPresentedIdentity,
        authoritative: BuilderBookInstance?,
    ): Boolean = authoritative != null &&
        authoritative.instanceId == expected.instanceId &&
        authoritative.blueprintId == expected.blueprintId &&
        authoritative.ownerId == playerId &&
        authoritative.generation == expected.generation &&
        authoritative.status == BuilderBookInstanceStatus.AVAILABLE
}
