package ru.arc.autobuild

/** Resolves the optional per-book cooldown without letting malformed item data disable limits. */
object BuildCooldownPolicy {
    const val DEFAULT_SECONDS: Long = 3_600L
    const val MAX_SECONDS: Long = 604_800L

    fun resolveSeconds(raw: String?, fallbackSeconds: Long = DEFAULT_SECONDS): Long {
        val safeFallback = fallbackSeconds.takeIf { it in 0..MAX_SECONDS } ?: DEFAULT_SECONDS
        return raw
            ?.toLongOrNull()
            ?.takeIf { it in 0..MAX_SECONDS }
            ?: safeFallback
    }

    fun toTicks(seconds: Long): Long = Math.multiplyExact(seconds.coerceIn(0, MAX_SECONDS), 20L)
}
