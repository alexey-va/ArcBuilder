package ru.arc.buildertools

/**
 * Separates the scheduler heartbeat from the throughput policy for pure world
 * batches. Durable resource stages must stay eligible on every heartbeat.
 */
internal object BuilderConstructionCadencePolicy {
    const val TIMER_PERIOD_TICKS: Long = 1L

    fun shouldRun(
        record: BuilderConstructionProjectRecord,
        schedulerTick: Long,
        pureBatchPeriodTicks: Long,
    ): Boolean {
        require(schedulerTick >= 0L) { "Builder construction scheduler tick must be non-negative" }
        require(pureBatchPeriodTicks >= 1L) { "Builder pure batch period must be positive" }
        if (!isPureBatchCandidate(record)) return true
        return schedulerTick % pureBatchPeriodTicks == 0L
    }

    private fun isPureBatchCandidate(record: BuilderConstructionProjectRecord): Boolean {
        if (record.state != BuilderConstructionProjectState.ACTIVE &&
            record.state != BuilderConstructionProjectState.WORLD_PREPARED
        ) {
            return false
        }
        val step = record.steps.getOrNull(record.cursor) ?: return false
        return step.requiredMaterial == null && step.output == null
    }
}
