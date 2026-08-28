package ru.arc.buildertools

import ru.arc.onetime.OneTimeUseClaim
import ru.arc.onetime.OneTimeUseClaimRequest
import ru.arc.onetime.OneTimeUseFingerprint
import ru.arc.onetime.OneTimeUseIdentity
import ru.arc.onetime.OneTimeUseScope
import java.util.UUID

/** Domain mapping from an authoritative builder-book instance to the shared ledger. */
internal object BuilderBookOneTimeUse {
    fun fingerprint(
        blueprintId: UUID,
        expectedGeneration: Int,
        buildingId: String,
        schematicSha256: String,
    ): OneTimeUseFingerprint {
        require(expectedGeneration > 0) { "Builder-book generation is invalid" }
        return OneTimeUseFingerprint.sha256Fields(
            blueprintId.toString(),
            expectedGeneration.toString(),
            buildingId,
            schematicSha256,
        )
    }

    fun request(
        instanceId: UUID,
        expectedGeneration: Int,
        blueprintId: UUID,
        buildingId: String,
        schematicSha256: String,
        operationId: UUID,
        playerId: UUID,
        serverName: String,
    ): OneTimeUseClaimRequest = OneTimeUseClaimRequest(
        identity = OneTimeUseIdentity(
            instanceId,
            fingerprint(blueprintId, expectedGeneration, buildingId, schematicSha256),
        ),
        claimId = operationId,
        claimantId = playerId,
        scope = OneTimeUseScope.parse(serverName),
    )

    fun request(plan: BuilderPlan, serverName: String): OneTimeUseClaimRequest = request(
        instanceId = checkNotNull(plan.bookInstanceId),
        expectedGeneration = checkNotNull(plan.bookInstanceGeneration),
        blueprintId = checkNotNull(plan.bookBlueprintId),
        buildingId = checkNotNull(plan.bookBuildingId),
        schematicSha256 = checkNotNull(plan.bookSchematicSha256),
        operationId = plan.id,
        playerId = plan.playerId,
        serverName = serverName,
    )

    fun claim(plan: BuilderPlan, serverName: String): OneTimeUseClaim =
        OneTimeUseClaim.acquired(request(plan, serverName), newlyCreated = false)
}
