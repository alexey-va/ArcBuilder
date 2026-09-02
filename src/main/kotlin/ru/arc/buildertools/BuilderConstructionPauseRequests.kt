package ru.arc.buildertools

import java.util.UUID

/** Queues a player pause intent until the current durable construction step is safe to stop. */
internal class BuilderConstructionPauseRequests {
    private val requesters = mutableMapOf<UUID, UUID>()

    fun request(project: BuilderConstructionProjectRecord, requesterId: UUID): Boolean {
        if (!BuilderConstructionPausePolicy.canRequestPause(project.state)) return false
        requesters[project.projectId] = requesterId
        return true
    }

    fun pauseTarget(project: BuilderConstructionProjectRecord, nowMillis: Long): BuilderConstructionProjectRecord? {
        if (project.projectId !in requesters) return null
        return if (BuilderConstructionPausePolicy.canPauseNow(project.state)) project.paused(nowMillis) else null
    }

    fun requester(projectId: UUID): UUID? = requesters[projectId]

    fun complete(projectId: UUID) {
        requesters.remove(projectId)
    }

    fun clear() {
        requesters.clear()
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
