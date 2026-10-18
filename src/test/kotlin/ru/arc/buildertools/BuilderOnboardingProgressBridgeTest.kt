package ru.arc.buildertools

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.plugin.ServicePriority
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.onboarding.api.OnboardingBuildProgressService
import ru.ruscrafting.onboarding.api.OnboardingBuildProgressService.Stage
import java.util.UUID
import java.util.concurrent.CompletableFuture

private data class OnboardingObservation(
    val playerId: UUID,
    val stage: Stage,
    val world: String,
    val x: Int,
    val z: Int,
    val operationId: String?,
)

class BuilderOnboardingProgressBridgeTest : StringSpec({
    "service absence and synchronous or asynchronous provider failures never escape the adapter" {
        MockBukkitTestRuntime.open().use { runtime ->
            val playerId = UUID.randomUUID()
            shouldNotThrowAny { BuilderOnboardingProgressBridge.record(playerId, Stage.BOOK_OPENED, "survival", -1, -17) }
            val observations = mutableListOf<OnboardingObservation>()
            val persistence = CompletableFuture<Void>()
            var failSynchronously = false
            var calledOnMain = false
            val provider = OnboardingBuildProgressService { id, stage, world, x, z, operation ->
                calledOnMain = Bukkit.isPrimaryThread()
                observations += OnboardingObservation(id, stage, world, x, z, operation)
                if (failSynchronously) error("Synthetic provider failure")
                persistence
            }
            runtime.server.servicesManager.register(OnboardingBuildProgressService::class.java, provider,
                runtime.createSimplePlugin("OnboardingProvider"), ServicePriority.Normal)

            BuilderOnboardingProgressBridge.record(playerId, Stage.BOOK_OPENED, "survival", -1, -17)
            observations shouldBe listOf(OnboardingObservation(playerId, Stage.BOOK_OPENED, "survival", -1, -17, null))
            calledOnMain shouldBe true
            persistence.isDone shouldBe false
            shouldNotThrowAny { persistence.completeExceptionally(IllegalStateException("Synthetic async persistence failure")) }
            failSynchronously = true
            shouldNotThrowAny { BuilderOnboardingProgressBridge.record(playerId, Stage.BUILD_COMPLETED, "survival", -1, -17, UUID.randomUUID().toString()) }
            observations.size shouldBe 2
        }
    }

    "book previews and both build paths retain their anchor and operation for offline owners while instant builds are excluded" {
        MockBukkitTestRuntime.open().use { runtime ->
            val world = runtime.addSimpleWorld("survival")
            val playerId = UUID.randomUUID()
            Bukkit.getPlayer(playerId) shouldBe null
            val observations = mutableListOf<OnboardingObservation>()
            val provider = OnboardingBuildProgressService { id, stage, name, x, z, operation ->
                observations += OnboardingObservation(id, stage, name, x, z, operation)
                CompletableFuture.completedFuture<Void>(null)
            }
            runtime.server.servicesManager.register(OnboardingBuildProgressService::class.java, provider,
                runtime.createSimplePlugin("OnboardingProvider"), ServicePriority.Normal)
            val first = BuilderBlockPos(world.uid, -17, 64, 31)
            val book = BuilderItemAmount("BBBB", "minecraft:book", 1)
            val change = BuilderBlockChange(first, "minecraft:air", "minecraft:stone")
            val plan = BuilderPlan(UUID.randomUUID(), playerId, BuilderPlanKind.BUILD_BOOK, listOf(change),
                listOf(book), emptyList(), createdAtMillis = 1L, expiresAtMillis = 60_001L)

            BuilderOnboardingProgressBridge.opened(playerId, Location(world, -0.5, 64.0, -16.5))
            BuilderOnboardingProgressBridge.started(plan.copy(kind = BuilderPlanKind.FILL))
            BuilderOnboardingProgressBridge.completed(plan.copy(kind = BuilderPlanKind.FILL))
            BuilderOnboardingProgressBridge.started(plan)
            BuilderOnboardingProgressBridge.completed(plan)
            val siteAnchor = BuilderBlockPos(world.uid, 32, 64, -48)
            val project = BuilderConstructionProjectRecord(
                projectId = plan.id, playerId = playerId, playerName = "Builder", siteAnchor = siteAnchor,
                plan = plan, steps = listOf(BuilderConstructionStep(change, null, null)), bookCost = book,
                state = BuilderConstructionProjectState.ACTIVE, cursor = 0, createdAtMillis = 1L,
                updatedAtMillis = 2L, applicationStartedAtMillis = 2L,
            )
            BuilderOnboardingProgressBridge.started(project)
            BuilderOnboardingProgressBridge.completed(project)
            BuilderOnboardingProgressBridge.completed(project.copy(siteAnchor = null))
            BuilderOnboardingProgressBridge.completed(project.copy(instantBuildRequestedBy = UUID.randomUUID()))

            observations shouldBe listOf(
                OnboardingObservation(playerId, Stage.BOOK_OPENED, "survival", -1, -17, null),
                OnboardingObservation(playerId, Stage.BUILD_STARTED, "survival", first.x, first.z, plan.id.toString()),
                OnboardingObservation(playerId, Stage.BUILD_COMPLETED, "survival", first.x, first.z, plan.id.toString()),
                OnboardingObservation(playerId, Stage.BUILD_STARTED, "survival", siteAnchor.x, siteAnchor.z, plan.id.toString()),
                OnboardingObservation(playerId, Stage.BUILD_COMPLETED, "survival", siteAnchor.x, siteAnchor.z, plan.id.toString()),
                OnboardingObservation(playerId, Stage.BUILD_COMPLETED, "survival", first.x, first.z, plan.id.toString()),
            )
        }
    }
})
