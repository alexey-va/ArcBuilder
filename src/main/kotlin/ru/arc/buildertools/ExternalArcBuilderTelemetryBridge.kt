package ru.arc.buildertools

import org.bukkit.Bukkit
import ru.arc.paper.api.ArcTelemetryProvider
import java.util.UUID

/** Optional ARC product telemetry; a missing ARC never affects a committed build. */
internal object ExternalArcBuilderTelemetryBridge {
    private val telemetry by lazy { Bukkit.getServicesManager().load(ArcTelemetryProvider::class.java) }

    fun completed(playerId: UUID, operationId: String) {
        runCatching {
            telemetry?.record(
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
