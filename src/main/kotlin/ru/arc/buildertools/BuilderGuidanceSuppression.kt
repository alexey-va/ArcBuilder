package ru.arc.buildertools

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import ru.arc.staffspells.api.QuestGuidanceHudService
import ru.arc.util.Logging.warn
import java.util.UUID

/** Refreshes short-lived HUD claims while this Builder surface exists; never owns quest progress. */
internal class BuilderGuidanceSuppression(
    private val plugin: Plugin,
    private val surfaceKey: String,
    private val lookup: () -> QuestGuidanceHudService? = {
        Bukkit.getServicesManager().load(QuestGuidanceHudService::class.java)
    },
) : AutoCloseable {
    private var provider: QuestGuidanceHudService? = null
    private var viewers = emptySet<UUID>()
    private var failed = false
    private var closed = false

    fun sync(activeViewers: Collection<UUID>) {
        if (closed) return
        val next = activeViewers.toSet()
        runCatching {
            val current = lookup()
            if (provider !== current) {
                viewers.forEach { provider?.setSuppressed(plugin, it, surfaceKey, false) }
                provider = current
            }
            (viewers - next).forEach { current?.setSuppressed(plugin, it, surfaceKey, false) }
            // The provider expires abandoned claims, so live surfaces refresh even without a state change.
            next.forEach { current?.setSuppressed(plugin, it, surfaceKey, true) }
        }.onFailure {
            if (!failed) warn("Builder quest HUD suppression failed for {}: {}", surfaceKey, it.message)
        }.also { failed = it.isFailure }
        viewers = next
    }

    fun clear(playerId: UUID) = sync(viewers - playerId)

    override fun close() {
        if (closed) return
        sync(emptySet())
        closed = true
        provider = null
    }
}
