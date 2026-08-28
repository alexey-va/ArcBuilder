package ru.arc.buildertools

import java.util.UUID

/** Main-thread owner of one player's non-durable crown preferences and reroll sequence. */
internal class BuilderCrownSessions {
    private data class Session(
        var settings: BuilderCrownSettings = BuilderCrownSettings(),
        var roll: Long = 0L,
    )

    private val sessions = mutableMapOf<UUID, Session>()

    fun settings(playerId: UUID): BuilderCrownSettings =
        sessions.getOrPut(playerId, ::Session).settings

    fun update(playerId: UUID, settings: BuilderCrownSettings): BuilderCrownSettings {
        val checked = settings.validated()
        sessions[playerId] = Session(settings = checked)
        return checked
    }

    fun seed(
        playerId: UUID,
        center: BuilderBlockPos,
        settings: BuilderCrownSettings,
        reroll: Boolean,
    ): Long {
        val checked = settings.validated()
        val session = sessions.getOrPut(playerId, ::Session)
        if (reroll) session.roll = Math.addExact(session.roll, 1L)
        return playerId.mostSignificantBits xor
            playerId.leastSignificantBits xor
            center.x.toLong().shl(32) xor
            center.y.toLong().shl(16) xor
            center.z.toLong() xor
            checked.hashCode().toLong().shl(1) xor
            (session.roll * -7046029254386353131L)
    }

    fun clear(playerId: UUID) {
        sessions.remove(playerId)
    }

    fun clear() {
        sessions.clear()
    }
}
