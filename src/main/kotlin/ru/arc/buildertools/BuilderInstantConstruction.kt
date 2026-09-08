package ru.arc.buildertools

import java.util.UUID

/** Admin-only world completion; the existing journal stores intent before any batch is applied. */
internal object BuilderInstantConstruction {
    const val MAX_STEPS_PER_BATCH = 4096
    private val safeStates = setOf(
        BuilderConstructionProjectState.ACTIVE,
        BuilderConstructionProjectState.WAITING_MATERIALS,
        BuilderConstructionProjectState.PAUSED,
        BuilderConstructionProjectState.OUTPUT_PENDING,
        BuilderConstructionProjectState.WAITING_OUTPUT_SPACE,
    )

    fun prepare(record: BuilderConstructionProjectRecord, requester: UUID, nowMillis: Long): BuilderConstructionProjectRecord? {
        if (record.state !in safeStates || record.pendingResourceMutation != null) return null
        return record.copy(
            state = BuilderConstructionProjectState.WORLD_PREPARED,
            instantBuildRequestedBy = requester,
            pendingOutput = null,
            updatedAtMillis = nowMillis,
        ).also { BuilderConstructionProjectTransitionRules.validate(record, it) }
    }

    fun validateTransition(before: BuilderConstructionProjectRecord, after: BuilderConstructionProjectRecord) {
        if (before.instantBuildRequestedBy == null) {
            require(before.state in safeStates && before.pendingResourceMutation == null)
            require(after.instantBuildRequestedBy != null)
            require(after == before.copy(state = BuilderConstructionProjectState.WORLD_PREPARED,
                instantBuildRequestedBy = after.instantBuildRequestedBy, pendingOutput = null, updatedAtMillis = after.updatedAtMillis))
            return
        }
        require(after.instantBuildRequestedBy == before.instantBuildRequestedBy)
        require(before.state == BuilderConstructionProjectState.WORLD_PREPARED)
        if (after.state == BuilderConstructionProjectState.RECOVERY_REQUIRED || after.state == BuilderConstructionProjectState.CANCELLED) {
            require(after.cursor == before.cursor)
            return
        }
        val end = minOf(before.steps.size, before.cursor + MAX_STEPS_PER_BATCH)
        require(after.cursor == end)
        val completed = end == before.steps.size
        require(after == before.copy(
            state = if (completed) BuilderConstructionProjectState.COMPLETED else BuilderConstructionProjectState.WORLD_PREPARED,
            cursor = end,
            updatedAtMillis = after.updatedAtMillis,
            completedAtMillis = after.updatedAtMillis.takeIf { completed },
        )) { "Instant construction changed unrelated state" }
    }

    fun applyBatch(record: BuilderConstructionProjectRecord, nowMillis: Long, port: BuilderConstructionProjectPort): BuilderConstructionProjectRecord? {
        require(record.instantBuildRequestedBy != null && record.state == BuilderConstructionProjectState.WORLD_PREPARED)
        val end = minOf(record.steps.size, record.cursor + MAX_STEPS_PER_BATCH)
        val steps = record.steps.subList(record.cursor, end)
        try {
            // Check the whole batch before writing; a retry may already contain a partially applied batch.
            for (step in steps) {
                val current = port.currentBlockData(step.change.position)
                if (current != step.change.beforeBlockData && current != step.change.afterBlockData) {
                    return record.recoveryRequired(nowMillis)
                }
                if (current == step.change.beforeBlockData && !port.canModify(record, step)) return record.recoveryRequired(nowMillis)
            }
        } catch (_: BuilderConstructionTemporarilyUnavailableException) {
            return null
        } catch (_: Throwable) {
            return record.recoveryRequired(nowMillis)
        }
        for (step in steps) {
            try {
                if (!port.isStepApplied(step)) port.apply(record, step)
                check(port.isStepApplied(step)) { "Instant construction block was not applied" }
            } catch (_: Throwable) {
                val applied = runCatching { port.isStepApplied(step) }.getOrNull()
                if (applied == true) continue
                val current = runCatching { port.currentBlockData(step.change.position) }.getOrNull()
                if (current == null || current == step.change.beforeBlockData || current == step.change.afterBlockData) return null
                return record.recoveryRequired(nowMillis)
            }
        }
        val completed = end == record.steps.size
        return record.copy(
            state = if (completed) BuilderConstructionProjectState.COMPLETED else BuilderConstructionProjectState.WORLD_PREPARED,
            cursor = end,
            updatedAtMillis = nowMillis,
            completedAtMillis = nowMillis.takeIf { completed },
        ).also { BuilderConstructionProjectTransitionRules.validate(record, it) }
    }
}
