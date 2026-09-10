package ru.arc.buildertools

import java.util.UUID

/** Queues a player pause intent until the current durable construction step is safe to stop. */
internal class BuilderConstructionPauseRequests {
    private val requesters = mutableMapOf<UUID, UUID>()
    private val cancellations = mutableSetOf<UUID>()

    fun requestCancel(project: BuilderConstructionProjectRecord, requesterId: UUID): Boolean {
        if (!canRequestCancel(project)) return false
        requesters[project.projectId] = requesterId
        cancellations += project.projectId
        return true
    }

    fun cancellationPending(projectId: UUID): Boolean = projectId in cancellations

    fun canRequestCancel(project: BuilderConstructionProjectRecord): Boolean =
        project.instantBuildRequestedBy == null && (BuilderConstructionPausePolicy.canRequestPause(project.state) ||
            project.state == BuilderConstructionProjectState.PAUSED ||
            project.state == BuilderConstructionProjectState.WAITING_OUTPUT_SPACE)

    fun request(project: BuilderConstructionProjectRecord, requesterId: UUID): Boolean {
        if (!BuilderConstructionPausePolicy.canRequestPause(project.state)) return false
        requesters[project.projectId] = requesterId
        return true
    }

    fun pauseTarget(project: BuilderConstructionProjectRecord, nowMillis: Long): BuilderConstructionProjectRecord? {
        if (project.projectId !in requesters) return null
        if (project.projectId in cancellations) {
            return if (BuilderConstructionPausePolicy.canPauseNow(project.state) ||
                project.state == BuilderConstructionProjectState.PAUSED) project.cancelled(nowMillis) else null
        }
        return if (BuilderConstructionPausePolicy.canPauseNow(project.state)) project.paused(nowMillis) else null
    }

    fun requester(projectId: UUID): UUID? = requesters[projectId]

    fun complete(projectId: UUID) {
        requesters.remove(projectId)
        cancellations.remove(projectId)
    }

    fun clear() {
        requesters.clear()
        cancellations.clear()
    }
}

internal object BuilderConstructionPausePolicy {
    private val safeStates = setOf(
        BuilderConstructionProjectState.ACTIVE,
        BuilderConstructionProjectState.WAITING_MATERIALS,
    )
    private val inFlightStepStates = setOf(
        BuilderConstructionProjectState.INPUT_PREPARED,
        BuilderConstructionProjectState.WORLD_PREPARED,
        BuilderConstructionProjectState.OUTPUT_PENDING,
        BuilderConstructionProjectState.DELIVERING_OUTPUT,
    )

    fun canRequestPause(state: BuilderConstructionProjectState): Boolean =
        state in safeStates || state in inFlightStepStates

    fun canPauseNow(state: BuilderConstructionProjectState): Boolean = state in safeStates

    fun playerFacingState(state: BuilderConstructionProjectState): BuilderConstructionProjectState =
        if (state in inFlightStepStates) BuilderConstructionProjectState.ACTIVE else state
}
