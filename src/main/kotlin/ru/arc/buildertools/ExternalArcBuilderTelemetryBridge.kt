package ru.arc.buildertools

import org.bukkit.Bukkit
import ru.arc.paper.api.ArcTelemetryProvider
import java.util.UUID

/** Optional ARC product telemetry; a missing ARC never affects a committed build. */
internal object ExternalArcBuilderTelemetryBridge {
    fun completed(playerId: UUID, operationId: String) {
        if (!Bukkit.getPluginManager().isPluginEnabled("ARC")) return
        runCatching { AvailableArcTelemetry.completed(playerId, operationId) }
    }

    private object AvailableArcTelemetry {
        fun completed(playerId: UUID, operationId: String) {
            Bukkit.getServicesManager().load(ArcTelemetryProvider::class.java)?.record(
                playerId,
                "arcbuilder",
                "autobuild",
                "autobuild_complete",
                null,
                "build:$operationId",
            )
        }
    }
}
