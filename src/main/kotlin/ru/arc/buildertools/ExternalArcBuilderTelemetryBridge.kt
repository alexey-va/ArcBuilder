package ru.arc.buildertools

import java.util.UUID

/** Optional ARC product telemetry; a missing ARC never affects a committed build. */
internal object ExternalArcBuilderTelemetryBridge {
    private val recordMethod = lazy {
        Class.forName("ru.arc.metrics.ExternalProductTelemetryBridge").getMethod(
            "record",
            UUID::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
        )
    }

    fun completed(playerId: UUID, operationId: String) {
        runCatching {
            recordMethod.value.invoke(
                null,
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
