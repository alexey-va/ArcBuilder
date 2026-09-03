package ru.arc.buildertools

import java.util.UUID

/** Shows one hold hint per uninterrupted draft-without-preview state. */
internal class BuilderBookHoldHintTracker {
    private val shown = mutableMapOf<UUID, String>()

    fun shouldShow(playerId: UUID, draftIdentity: String?, previewOpen: Boolean): Boolean {
        if (draftIdentity == null || previewOpen) {
            shown.remove(playerId)
            return false
        }
        return shown.put(playerId, draftIdentity) != draftIdentity
    }

    fun clear(playerId: UUID) {
        shown.remove(playerId)
    }

    fun clear() = shown.clear()
}
