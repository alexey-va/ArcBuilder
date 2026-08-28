package ru.arc.buildertools

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.util.UUID

class BuilderDraftJournalTest : FunSpec({
    val playerId = UUID.fromString("11111111-2222-3333-4444-555555555555")
    val operationId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    val blueprintId = UUID.fromString("99999999-8888-7777-6666-555555555555")
    val contentSha256 = "a".repeat(64)
    val schematicSha256 = "b".repeat(64)

    fun prepared() = BuilderDraftRecord(
        operationId = operationId,
        playerId = playerId,
        playerName = "Builder",
        title = "Дом у озера",
        buildingId = "player-${playerId.toString().replace("-", "")}-$contentSha256.schem",
        blueprintId = blueprintId,
        contentSha256 = contentSha256,
        sourceRotation = 270,
        blockCount = 42,
        phase = BuilderDraftPhase.PREPARED,
        createdAtMillis = 1_800_000_000_000L,
        updatedAtMillis = 1_800_000_000_000L,
    ).validated()

    test("draft record advances once without changing its immutable identity") {
        val prepared = prepared()
        val ready = prepared.ready(schematicSha256, prepared.updatedAtMillis + 1)

        ready.phase shouldBe BuilderDraftPhase.READY
        ready.schematicSha256 shouldBe schematicSha256
        ready.operationId shouldBe prepared.operationId
        ready.playerId shouldBe prepared.playerId
        ready.blueprintId shouldBe prepared.blueprintId
        ready.sourceRotation shouldBe 270

        shouldThrow<IllegalArgumentException> {
            ready.ready("c".repeat(64), ready.updatedAtMillis + 1)
        }
        shouldThrow<IllegalArgumentException> {
            prepared.copy(buildingId = "player-other.schem").validated()
        }
    }

    test("draft record accepts bounded player anchors as part of its content address") {
        val anchored = prepared().copy(
            buildingId = "player-${playerId.toString().replace("-", "")}-o-12_4_1024-$contentSha256.schem",
        )

        anchored.validated() shouldBe anchored
        shouldThrow<IllegalArgumentException> {
            anchored.copy(buildingId = anchored.buildingId.replace(contentSha256, "c".repeat(64))).validated()
        }
        shouldThrow<IllegalArgumentException> {
            anchored.copy(buildingId = anchored.buildingId.replace("o-12_4_1024", "o-12_4_1025")).validated()
        }
    }

    test("recovery distinguishes incomplete persistence, ready delivery and duplicates") {
        val prepared = prepared()
        val ready = prepared.ready(schematicSha256, prepared.updatedAtMillis + 1)

        BuilderDraftRecoveryRules.action(prepared, null, matchingDraftItems = 0) shouldBe
            BuilderDraftRecoveryAction.ACK_FAILED
        BuilderDraftRecoveryRules.action(prepared, schematicSha256, matchingDraftItems = 0) shouldBe
            BuilderDraftRecoveryAction.ADVANCE_READY
        BuilderDraftRecoveryRules.action(ready, schematicSha256, matchingDraftItems = 0) shouldBe
            BuilderDraftRecoveryAction.AWAIT_SOURCE_BOOK
        BuilderDraftRecoveryRules.action(ready, null, matchingDraftItems = 0) shouldBe
            BuilderDraftRecoveryAction.ACK_FAILED
        BuilderDraftRecoveryRules.action(ready, schematicSha256, matchingDraftItems = 1) shouldBe
            BuilderDraftRecoveryAction.ACK_DELIVERED
        BuilderDraftRecoveryRules.action(ready, null, matchingDraftItems = 1) shouldBe
            BuilderDraftRecoveryAction.MANUAL_REVIEW
        BuilderDraftRecoveryRules.action(ready, schematicSha256, matchingDraftItems = 2) shouldBe
            BuilderDraftRecoveryAction.MANUAL_REVIEW
        BuilderDraftRecoveryRules.action(
            ready,
            schematicSha256,
            matchingDraftItems = 1,
            conflictingDraftItems = 1,
        ) shouldBe BuilderDraftRecoveryAction.MANUAL_REVIEW
        BuilderDraftRecoveryRules.action(ready, "c".repeat(64), matchingDraftItems = 0) shouldBe
            BuilderDraftRecoveryAction.MANUAL_REVIEW
    }

    test("journal durably reloads a ready draft and acknowledges it idempotently") {
        val root = Files.createTempDirectory("arc-builder-draft-journal-")
        val first = BuilderDraftJournal(root, maxBlocks = 10_000)
        val prepared = first.commit(prepared())
        val ready = first.transition(prepared, prepared.ready(schematicSha256, prepared.updatedAtMillis + 1))
        first.transition(prepared, ready) shouldBe ready

        val reloaded = BuilderDraftJournal(root, maxBlocks = 10_000)
        reloaded.loadAll().single() shouldBe ready
        reloaded.acknowledge(ready.operationId) shouldBe true
        reloaded.acknowledge(ready.operationId) shouldBe false
        reloaded.loadAll() shouldBe emptyList()
    }

    test("creative draft recovery persists that no source book is required") {
        val root = Files.createTempDirectory("arc-builder-creative-draft-journal-")
        val journal = BuilderDraftJournal(root, maxBlocks = 10_000)
        val creative = prepared().copy(sourceBookRequired = false).validated()

        journal.commit(creative)

        val reloaded = BuilderDraftJournal(root, maxBlocks = 10_000).loadAll().single()
        reloaded.requiresSourceBook shouldBe false
        prepared().requiresSourceBook shouldBe true
    }
})
