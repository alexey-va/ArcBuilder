package ru.arc.buildertools

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

class BuilderActionDiagnosticsTest : StringSpec({
    val world = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val player = UUID.fromString("00000000-0000-0000-0000-000000000002")
    val operation = UUID.fromString("00000000-0000-0000-0000-000000000003")
    val target = BuilderActionTarget(world, "classic_survival", 10, 64, -20)
    fun context() = BuilderActionContext(
        player, "GrocerMC", "book_prepare", target,
        operationId = operation, previewId = "$player:1000", buildingId = "viking.schem",
    )
    fun readContext(position: BuilderBlockPos) = context().copy(
        action = "construction_world_read",
        target = BuilderActionTarget(position.worldId, "classic_survival", position.x, position.y, position.z),
    )

    "attempt and async stages retain player target preview and operation correlation" {
        val records = mutableListOf<String>()
        val diagnostics = BuilderActionDiagnostics(records::add) { _, _ -> error("Unexpected failure") }
        val attempt = diagnostics.begin(context())

        attempt.outcome("submitted", "initial_commit_pending")
        attempt.outcome("durable_prepared", "initial_commit_confirmed")

        records shouldHaveSize 3
        records.forEach {
            it shouldContain "attempt=${attempt.id}"
            it shouldContain "player=$player"
            it shouldContain "player_name=GrocerMC"
            it shouldContain "world=classic_survival"
            it shouldContain "world_id=$world"
            it shouldContain "x=10 y=64 z=-20"
            it shouldContain "operation=$operation"
            it shouldContain "preview=$player:1000"
            it shouldNotContain "outcome=committed"
        }
        records[1] shouldContain "outcome=submitted"
        records[2] shouldContain "outcome=durable_prepared"
    }

    "wrapped expected denial reports the checked block instead of the preview anchor" {
        val records = mutableListOf<String>()
        val unexpected = mutableListOf<Throwable>()
        val diagnostics = BuilderActionDiagnostics(records::add) { _, failure -> unexpected += failure }
        val attempt = diagnostics.begin(context())
        val denied = BuilderActionTarget(world, "classic_survival", 27, 70, -35)

        attempt.failed(CompletionException(ExecutionException(BuilderUserFailure(
            "errors.protection", diagnostic = BuilderActionRejection("lands_build_permission", denied),
        ))))

        records shouldHaveSize 2
        records.last() shouldContain "attempt=${attempt.id}"
        records.last() shouldContain "outcome=rejected reason=lands_build_permission"
        records.last() shouldContain "x=27 y=70 z=-35"
        records.last() shouldContain "message=errors.protection"
        unexpected shouldHaveSize 0
    }

    "unexpected async failure retains the original throwable and operation context" {
        val records = mutableListOf<String>()
        val failures = mutableListOf<Pair<String, Throwable>>()
        val diagnostics = BuilderActionDiagnostics(records::add) { line, failure -> failures += line to failure }
        val attempt = diagnostics.begin(context().copy(operationId = null))
        attempt.operation(operation)
        val failure = IllegalStateException("native write failed")

        attempt.failed(CompletionException(failure))

        records shouldHaveSize 1
        failures shouldHaveSize 1
        failures.single().second shouldBe failure
        failures.single().first shouldContain "attempt=${attempt.id}"
        failures.single().first shouldContain "operation=$operation"
        failures.single().first shouldContain "outcome=failed reason=unexpected_exception"
        failures.single().first shouldContain "failure_type=IllegalStateException"
        failures.single().first shouldNotContain "native write failed"
    }

    "context invalidation is observable without claiming a plugin failure" {
        val records = mutableListOf<String>()
        val diagnostics = BuilderActionDiagnostics(records::add) { _, _ -> error("Unexpected failure") }

        diagnostics.begin(context()).failed(null)

        records.last() shouldContain "outcome=cancelled reason=context_changed"
    }

    "repeated construction denial logs once and recovery reports the suppressed count" {
        val records = mutableListOf<String>()
        val diagnostics = BuilderActionDiagnostics(records::add) { _, _ -> error("Unexpected failure") }

        repeat(3) { diagnostics.constructionBlocked(context().copy(action = "construction_step"), "lands_build_permission") }
        records shouldHaveSize 1
        records.single() shouldContain "outcome=blocked reason=lands_build_permission"

        diagnostics.constructionAllowed(operation)
        diagnostics.constructionAllowed(operation)

        records shouldHaveSize 2
        records.last() shouldContain "outcome=recovered"
        records.last() shouldContain "suppressed=2"
    }

    "terminal cleanup and runtime cleanup release construction diagnostic state" {
        val records = mutableListOf<String>()
        val diagnostics = BuilderActionDiagnostics(records::add) { _, _ -> error("Unexpected failure") }
        diagnostics.constructionBlocked(context(), "world_border")
        diagnostics.forgetConstruction(operation)
        diagnostics.constructionBlocked(context(), "world_border")
        diagnostics.clear()
        diagnostics.constructionBlocked(context(), "world_border")

        records shouldHaveSize 4
        records[1] shouldContain "outcome=closed reason=project_terminal"
        records.last() shouldContain "outcome=blocked reason=world_border"
    }

    "a successful permission check cannot mark a failed resource phase recovered" {
        val records = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        val diagnostics = BuilderActionDiagnostics(records::add) { _, failure -> failures += failure }
        val context = context().copy(action = "construction_input")
        val failure = IllegalStateException("resource preparation failed")

        repeat(3) { diagnostics.constructionBlocked(context, "unexpected_IllegalStateException", failure) }
        diagnostics.constructionAllowed(operation)

        failures shouldBe listOf(failure)
        records shouldHaveSize 0
        diagnostics.constructionAllowed(operation, "construction_input")
        records.single() shouldContain "action=construction_input outcome=recovered"
        records.single() shouldContain "suppressed=2"
    }

    "native cancellation is visible while duplicate and off hand events stay silent" {
        val records = mutableListOf<String>()
        val diagnostics = BuilderActionDiagnostics(records::add) { _, _ -> error("Unexpected failure") }
        val owner = mockk<Player>()
        val nativeWorld = mockk<World>()
        every { owner.uniqueId } returns player
        every { owner.name } returns "GrocerMC"
        every { nativeWorld.uid } returns world
        every { nativeWorld.name } returns "classic_survival"
        val location = Location(nativeWorld, 10.5, 64.0, -19.5)

        diagnostics.spatialInput(owner, "book_plaque", "scene-1", location, false, "event_cancelled")
        diagnostics.spatialInput(owner, "book_plaque", "scene-1", location, false, "duplicate")
        diagnostics.spatialInput(owner, "book_plaque", "scene-1", location, false, "off_hand")

        records shouldHaveSize 1
        records.single() shouldContain "outcome=input_rejected reason=event_cancelled"
        records.single() shouldContain "scene=scene-1"
        records.single() shouldContain "player=$player"
    }

    "project world read wrapper preserves delegate results and other port methods" {
        val records = mutableListOf<String>()
        val diagnostics = BuilderActionDiagnostics(records::add) { _, _ -> error("Unexpected failure") }
        val delegate = mockk<BuilderConstructionProjectPort>()
        val project = mockk<BuilderConstructionProjectRecord>()
        val position = BuilderBlockPos(world, 31, 72, -41)
        val step = BuilderConstructionStep(BuilderBlockChange(position, "minecraft:dirt", "minecraft:stone"), null, null)
        every { delegate.currentBlockData(position) } returns "minecraft:dirt"
        every { delegate.isStepApplied(step) } returns false
        every { delegate.canModify(project, step) } returns true
        val port = builderConstructionWorldReadPort(operation, delegate, diagnostics, ::readContext)

        port.currentBlockData(position) shouldBe "minecraft:dirt"
        port.isStepApplied(step) shouldBe false
        port.canModify(project, step) shouldBe true

        records shouldHaveSize 0
        verify(exactly = 1) { delegate.currentBlockData(position) }
        verify(exactly = 1) { delegate.isStepApplied(step) }
        verify(exactly = 1) { delegate.canModify(project, step) }
    }

    "metadata world read failure preserves throwable and does not recover on a plain block read" {
        val records = mutableListOf<String>()
        val failures = mutableListOf<Pair<String, Throwable>>()
        val diagnostics = BuilderActionDiagnostics(records::add) { line, failure -> failures += line to failure }
        val delegate = mockk<BuilderConstructionProjectPort>()
        val position = BuilderBlockPos(world, 31, 72, -41)
        val step = BuilderConstructionStep(BuilderBlockChange(position, "minecraft:dirt", "minecraft:stone"), null, null)
        val failure = IllegalStateException("metadata read failed")
        every { delegate.currentBlockData(position) } returns "minecraft:stone"
        every { delegate.isStepApplied(step) } throws failure
        val port = builderConstructionWorldReadPort(operation, delegate, diagnostics, ::readContext)

        repeat(3) {
            port.currentBlockData(position) shouldBe "minecraft:stone"
            (shouldThrow<IllegalStateException> { port.isStepApplied(step) } === failure) shouldBe true
        }

        records shouldHaveSize 0
        failures shouldHaveSize 1
        (failures.single().second === failure) shouldBe true
        failures.single().first shouldContain "action=construction_world_read"
        failures.single().first shouldContain "operation=$operation"
        failures.single().first shouldContain "player=$player"
        failures.single().first shouldContain "x=31 y=72 z=-41"

        every { delegate.isStepApplied(step) } returns false
        port.isStepApplied(step) shouldBe false
        records.single() shouldContain "action=construction_world_read outcome=recovered"
        records.single() shouldContain "suppressed=2"
    }

    "world read reports the exact protection or unloaded chunk reason" {
        for (reason in listOf("block_protection", "chunk_unloaded")) {
            val records = mutableListOf<String>()
            val diagnostics = BuilderActionDiagnostics(records::add) { _, _ -> error("Unexpected failure") }
            val delegate = mockk<BuilderConstructionProjectPort>()
            val position = BuilderBlockPos(world, 31, 72, -41)
            val failure = BuilderConstructionTemporarilyUnavailableException(reason)
            every { delegate.currentBlockData(position) } throws failure
            val port = builderConstructionWorldReadPort(operation, delegate, diagnostics, ::readContext)

            (shouldThrow<BuilderConstructionTemporarilyUnavailableException> { port.currentBlockData(position) } === failure) shouldBe true

            records.single() shouldContain "outcome=blocked reason=$reason"
            records.single() shouldContain "x=31 y=72 z=-41"
        }
    }

    "temporary world read failure preserves type and recovers only the same position and phase" {
        val records = mutableListOf<String>()
        val diagnostics = BuilderActionDiagnostics(records::add) { _, _ -> error("Unexpected failure") }
        val delegate = mockk<BuilderConstructionProjectPort>()
        val position = BuilderBlockPos(world, 31, 72, -41)
        val other = position.copy(x = 32)
        val failure = BuilderConstructionTemporarilyUnavailableException()
        every { delegate.currentBlockData(position) } throws failure
        every { delegate.currentBlockData(other) } returns "minecraft:dirt"
        val port = builderConstructionWorldReadPort(operation, delegate, diagnostics, ::readContext)
        diagnostics.constructionBlocked(context().copy(action = "construction_step"), "lands_build_permission")

        repeat(2) {
            (shouldThrow<BuilderConstructionTemporarilyUnavailableException> { port.currentBlockData(position) } === failure) shouldBe true
        }
        records shouldHaveSize 2
        records.last() shouldContain "outcome=blocked reason=block_temporarily_unavailable"
        records.last() shouldContain "x=31 y=72 z=-41"
        port.currentBlockData(other) shouldBe "minecraft:dirt"
        records shouldHaveSize 2

        every { delegate.currentBlockData(position) } returns "minecraft:dirt"
        port.currentBlockData(position) shouldBe "minecraft:dirt"
        records shouldHaveSize 3
        records.last() shouldContain "action=construction_world_read outcome=recovered"
        records.last() shouldContain "suppressed=1"
        diagnostics.constructionAllowed(operation)
        records shouldHaveSize 4
        records.last() shouldContain "action=construction_step outcome=recovered"
    }
})
