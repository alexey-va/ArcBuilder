package ru.arc.buildertools

import java.time.Duration
import java.util.UUID

internal object BuilderBookApplicationCooldown {
    fun remaining(
        playerId: UUID,
        records: Iterable<BuilderConstructionProjectRecord>,
        nowMillis: Long,
        cooldown: Duration,
        bypass: Boolean,
    ): Duration {
        require(!cooldown.isNegative) { "Build-book application cooldown cannot be negative" }
        if (bypass || cooldown.isZero) return Duration.ZERO
        val lastStartedAt = records.asSequence()
            .filter { it.playerId == playerId }
            .map { it.applicationStartedAtMillis ?: it.createdAtMillis }
            .maxOrNull()
            ?: return Duration.ZERO
        return Duration.ofMillis((lastStartedAt + cooldown.toMillis() - nowMillis).coerceAtLeast(0L))
    }
}
