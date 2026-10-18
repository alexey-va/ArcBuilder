package ru.arc.buildertools

import org.bukkit.Bukkit
import org.bukkit.Location
import ru.arc.util.Logging.warn
import ru.ruscrafting.onboarding.api.OnboardingBuildProgressService
import ru.ruscrafting.onboarding.api.OnboardingBuildProgressService.Stage
import java.util.UUID

/** Main-thread optional quest integration; its persistence failure never undoes a build. */
internal object BuilderOnboardingProgressBridge {
    fun opened(playerId: UUID, location: Location) {
        val world = location.world ?: return
        record(playerId, Stage.BOOK_OPENED, world.name, location.blockX, location.blockZ)
    }

    fun started(plan: BuilderPlan) = recordPlan(plan, Stage.BUILD_STARTED)

    fun completed(plan: BuilderPlan) = recordPlan(plan, Stage.BUILD_COMPLETED)

    fun started(project: BuilderConstructionProjectRecord) = recordProject(project, Stage.BUILD_STARTED)

    fun completed(project: BuilderConstructionProjectRecord) = recordProject(project, Stage.BUILD_COMPLETED)

    fun record(
        playerId: UUID,
        stage: Stage,
        worldName: String,
        blockX: Int,
        blockZ: Int,
        operationId: String? = null,
    ) {
        runCatching {
            check(Bukkit.isPrimaryThread()) { "Builder onboarding observations require the Paper thread" }
            Bukkit.getServicesManager().load(OnboardingBuildProgressService::class.java)
                ?.record(playerId, stage, worldName, blockX, blockZ, operationId)
                ?.whenComplete { _, failure ->
                    if (failure != null) failed(playerId, stage, operationId, failure)
                }
        }.onFailure { failure -> failed(playerId, stage, operationId, failure) }
    }

    private fun recordPlan(plan: BuilderPlan, stage: Stage) {
        if (plan.kind != BuilderPlanKind.BUILD_BOOK) return
        val anchor = plan.changes.firstOrNull()?.position ?: return
        recordAt(plan.playerId, stage, anchor, plan.id)
    }

    private fun recordProject(project: BuilderConstructionProjectRecord, stage: Stage) {
        if (project.instantBuildRequestedBy != null) return
        val anchor = project.siteAnchor ?: project.plan.changes.firstOrNull()?.position ?: return
        recordAt(project.playerId, stage, anchor, project.projectId)
    }

    private fun recordAt(playerId: UUID, stage: Stage, anchor: BuilderBlockPos, operationId: UUID) {
        runCatching {
            check(Bukkit.isPrimaryThread()) { "Builder onboarding anchors require the Paper thread" }
            val world = requireNotNull(Bukkit.getWorld(anchor.worldId)) { "Builder onboarding world is unavailable: ${anchor.worldId}" }
            record(playerId, stage, world.name, anchor.x, anchor.z, operationId.toString())
        }.onFailure { failure -> failed(playerId, stage, operationId.toString(), failure) }
    }

    private fun failed(playerId: UUID, stage: Stage, operationId: String?, failure: Throwable) {
        warn("Builder onboarding observation failed: player={}, stage={}, operation={}", playerId, stage, operationId, failure)
    }
}
