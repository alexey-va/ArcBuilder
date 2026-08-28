package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.observability.RuntimeHealthState

class BuilderToolsRuntimeHealthTest : FunSpec({
    test("healthy runtime publishes only bounded aggregate readiness") {
        val contribution = BuilderToolsRuntimeHealth.contribution(healthyInputs())

        contribution.state shouldBe RuntimeHealthState.UP
        contribution.recoveryBacklog shouldBe 0
        contribution.activeLeases shouldBe 0
        contribution.schemas shouldBe mapOf(
            "builder_drafts" to BuilderDraftRecord.CURRENT_SCHEMA_VERSION,
            "book_registry" to BuilderBookSqlRegistry.CURRENT_SCHEMA_VERSION,
        )
        contribution.dependencies shouldBe mapOf(
            "lands" to true,
            "coreprotect" to true,
            "shop" to true,
            "book_registry" to true,
            "builder_drafts" to true,
        )
    }

    test("startup and hard recovery failure are distinguishable") {
        BuilderToolsRuntimeHealth.contribution(
            healthyInputs().copy(recovering = true, bookRegistryReady = false),
        ).state shouldBe RuntimeHealthState.STARTING

        val failed = BuilderToolsRuntimeHealth.contribution(
            healthyInputs().copy(
                recoveryBlocked = true,
                recoveryPlayers = 2,
                deliveryWaitingForSpace = 3,
                reservationReleaseBacklog = 6,
                activeOperations = 1,
                bookLockedPlayers = 4,
            ),
        )
        failed.state shouldBe RuntimeHealthState.DOWN
        failed.recoveryBacklog shouldBe 11
        failed.activeLeases shouldBe 5
    }

    test("book registry failure degrades tools without hiding the dependency") {
        val contribution = BuilderToolsRuntimeHealth.contribution(
            healthyInputs().copy(bookRegistryReady = false, bookRegistryFailed = true),
        )

        contribution.state shouldBe RuntimeHealthState.DEGRADED
        contribution.dependencies["book_registry"] shouldBe false
    }

    test("an exact reservation release retry is visible without taking unrelated books down") {
        val contribution = BuilderToolsRuntimeHealth.contribution(
            healthyInputs().copy(reservationReleaseBacklog = 1),
        )

        contribution.state shouldBe RuntimeHealthState.DEGRADED
        contribution.recoveryBacklog shouldBe 1
        contribution.dependencies["book_registry"] shouldBe true
    }

    test("draft journal remains a fail-closed startup dependency") {
        val starting = BuilderToolsRuntimeHealth.contribution(
            healthyInputs().copy(draftJournalReady = false),
        )
        starting.state shouldBe RuntimeHealthState.STARTING
        starting.dependencies["builder_drafts"] shouldBe false

        val failed = BuilderToolsRuntimeHealth.contribution(
            healthyInputs().copy(draftJournalReady = false, draftJournalFailed = true),
        )
        failed.state shouldBe RuntimeHealthState.DOWN
        failed.dependencies["builder_drafts"] shouldBe false
    }

    test("missing enabled shop degrades tools and publishes the failed dependency") {
        val contribution = BuilderToolsRuntimeHealth.contribution(
            healthyInputs().copy(shopAvailable = false),
        )

        contribution.state shouldBe RuntimeHealthState.DEGRADED
        contribution.dependencies["shop"] shouldBe false
    }

    test("disabled shop remains an optional healthy dependency") {
        val contribution = BuilderToolsRuntimeHealth.contribution(
            healthyInputs().copy(shopRequired = false, shopAvailable = false),
        )

        contribution.state shouldBe RuntimeHealthState.UP
        contribution.dependencies["shop"] shouldBe true
    }

    test("external failure classification never includes the exception message") {
        BuilderToolsFailureType.of(IllegalStateException("jdbc:mysql://secret-host/password")) shouldBe
            "IllegalStateException"
        BuilderToolsFailureType.of(null) shouldBe "missing_result"
    }
})

private fun healthyInputs() = BuilderToolsRuntimeHealthInputs(
    closed = false,
    recovering = false,
    recoveryBlocked = false,
    recoveryPlayers = 0,
    deliveryWaitingForSpace = 0,
    reservationReleaseBacklog = 0,
    activeOperations = 0,
    bookLockedPlayers = 0,
    landsRequired = true,
    landsAvailable = true,
    coreProtectRequired = true,
    coreProtectAvailable = true,
    shopRequired = true,
    shopAvailable = true,
    bookContractsEnabled = true,
    bookRegistryReady = true,
    bookRegistryFailed = false,
    draftJournalReady = true,
    draftJournalFailed = false,
)
