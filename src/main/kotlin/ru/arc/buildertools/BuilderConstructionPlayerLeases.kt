package ru.arc.buildertools

import java.util.UUID

/**
 * Main-thread owner of the player and container leases used while a durable construction
 * project crosses a value-mutation commit boundary.
 *
 * A construction project is long-lived, but its player lock must exist only
 * around one synchronous construction action. The exact project identity keeps
 * a stale completion from unlocking a newer lease for the same player.
 */
internal class BuilderConstructionPlayerLeases(
    private val operationLocks: BuilderOperationLocks,
) : AutoCloseable {
    private data class Lease(val playerId: UUID, val containerBlocks: Set<BuilderBlockPos>)

    private val leaseByProject = mutableMapOf<UUID, Lease>()
    private val projectByPlayer = mutableMapOf<UUID, UUID>()
    private var closed = false

    /**
     * Acquires the player lease for [projectId]. Re-acquiring the same
     * project/player pair is idempotent; every other project or an ordinary
     * builder operation keeps the acquisition rejected.
     */
    fun acquire(
        projectId: UUID,
        playerId: UUID,
        mutation: BuilderResourceMutation? = null,
    ): Boolean {
        if (closed) return false
        val containerBlocks = mutation?.sources.orEmpty()
            .flatMap(BuilderResourceInventoryMutation::containerBlocks)
            .toSet()
        val requested = Lease(playerId, containerBlocks)
        val existing = leaseByProject[projectId]
        if (existing != null) return existing == requested
        if (playerId in projectByPlayer) return false
        if (!operationLocks.tryBookLock(playerId)) return false
        if (containerBlocks.isNotEmpty() && !operationLocks.tryResourceLock(projectId, containerBlocks)) {
            operationLocks.unlockBook(playerId)
            return false
        }

        leaseByProject[projectId] = requested
        projectByPlayer[playerId] = projectId
        return true
    }

    /** Releases only the lease held by this exact construction project. */
    fun release(projectId: UUID) {
        val lease = leaseByProject.remove(projectId) ?: return
        check(projectByPlayer.remove(lease.playerId, projectId)) {
            "Builder construction player lease indexes diverged for $projectId"
        }
        operationLocks.unlockResources(projectId)
        operationLocks.unlockBook(lease.playerId)
    }

    /** Releases every currently held construction lease. Safe to repeat. */
    fun releaseAll() {
        leaseByProject.keys.toList().forEach(::release)
    }

    override fun close() {
        if (closed) return
        closed = true
        releaseAll()
    }
}
