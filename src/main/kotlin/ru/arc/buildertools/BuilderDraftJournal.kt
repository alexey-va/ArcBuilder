package ru.arc.buildertools

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import ru.arc.persistence.DurableRecordJournal
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.UUID

internal enum class BuilderDraftPhase {
    PREPARED,
    READY,
}

internal data class BuilderDraftRecord(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val operationId: UUID,
    val playerId: UUID,
    val playerName: String,
    val title: String,
    val buildingId: String,
    val blueprintId: UUID,
    val contentSha256: String,
    val schematicSha256: String? = null,
    val blockCount: Int,
    val phase: BuilderDraftPhase,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
) {
    fun validated(maxBlocks: Int = BuilderPlan.ABSOLUTE_MAX_CHANGES): BuilderDraftRecord = apply {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "Unsupported builder-draft journal schema" }
        require(playerName.matches(Regex("[A-Za-z0-9_]{1,16}"))) {
            "Builder-draft player name is invalid"
        }
        require(title.isNotBlank() && title.length <= 48 && title.none(Char::isISOControl)) {
            "Builder-draft title is invalid"
        }
        require(contentSha256.matches(SHA256)) { "Builder-draft content digest is invalid" }
        require(
            buildingId == "player-${playerId.toString().replace("-", "")}-$contentSha256.schem",
        ) { "Builder-draft building id does not match its content address" }
        require(blockCount in 1..maxBlocks) { "Builder-draft block count is invalid" }
        require(createdAtMillis > 0L && updatedAtMillis >= createdAtMillis) {
            "Builder-draft timestamps are invalid"
        }
        require((phase == BuilderDraftPhase.READY) == (schematicSha256 != null)) {
            "Builder-draft persistence phase does not match its schematic digest"
        }
        schematicSha256?.let { require(it.matches(SHA256)) { "Builder-draft schematic digest is invalid" } }
    }

    fun ready(schematicSha256: String, nowMillis: Long): BuilderDraftRecord {
        validated()
        require(phase == BuilderDraftPhase.PREPARED) { "Only a prepared builder draft may become ready" }
        require(nowMillis >= updatedAtMillis) { "Builder-draft transition time moved backwards" }
        return copy(
            schematicSha256 = schematicSha256,
            phase = BuilderDraftPhase.READY,
            updatedAtMillis = nowMillis,
        ).validated()
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        private val SHA256 = Regex("[a-f0-9]{64}")
    }
}

internal enum class BuilderDraftRecoveryAction {
    ACK_FAILED,
    ADVANCE_READY,
    AWAIT_SOURCE_BOOK,
    ACK_DELIVERED,
    MANUAL_REVIEW,
}

internal object BuilderDraftRecoveryRules {
    fun action(
        record: BuilderDraftRecord,
        actualSchematicSha256: String?,
        matchingDraftItems: Int,
        conflictingDraftItems: Int = 0,
    ): BuilderDraftRecoveryAction {
        val checked = record.validated()
        require(matchingDraftItems >= 0) { "Builder-draft matching item count is invalid" }
        require(conflictingDraftItems >= 0) { "Builder-draft conflicting item count is invalid" }
        if (matchingDraftItems > 1 || conflictingDraftItems > 0) return BuilderDraftRecoveryAction.MANUAL_REVIEW
        if (matchingDraftItems == 1) {
            return if (
                actualSchematicSha256 != null &&
                (checked.phase == BuilderDraftPhase.PREPARED || actualSchematicSha256 == checked.schematicSha256)
            ) {
                BuilderDraftRecoveryAction.ACK_DELIVERED
            } else {
                BuilderDraftRecoveryAction.MANUAL_REVIEW
            }
        }
        return when (checked.phase) {
            BuilderDraftPhase.PREPARED -> if (actualSchematicSha256 == null) {
                BuilderDraftRecoveryAction.ACK_FAILED
            } else {
                BuilderDraftRecoveryAction.ADVANCE_READY
            }
            BuilderDraftPhase.READY -> when (actualSchematicSha256) {
                null -> BuilderDraftRecoveryAction.ACK_FAILED
                checked.schematicSha256 -> BuilderDraftRecoveryAction.AWAIT_SOURCE_BOOK
                else -> BuilderDraftRecoveryAction.MANUAL_REVIEW
            }
        }
    }
}

internal class BuilderDraftJournal(
    dataRoot: Path,
    private val maxBlocks: Int,
    gson: Gson = GsonBuilder().disableHtmlEscaping().create(),
) {
    private val journal = DurableRecordJournal(
        root = dataRoot,
        relativeDirectory = Path.of("data", "builder-book-draft-journal"),
        maxRecordBytes = 64L * 1024L,
        encode = { record: BuilderDraftRecord -> gson.toJson(record).toByteArray(StandardCharsets.UTF_8) },
        decode = { bytes -> gson.fromJson(String(bytes, StandardCharsets.UTF_8), BuilderDraftRecord::class.java) },
        validate = { record -> record.validated(maxBlocks) },
    )

    @Synchronized
    fun commit(record: BuilderDraftRecord): BuilderDraftRecord {
        val checked = record.validated(maxBlocks)
        require(checked.phase == BuilderDraftPhase.PREPARED) { "A new builder-draft record must be prepared" }
        require(journal.loadOrNull(checked.operationId.toString()) == null) {
            "Builder-draft operation already exists"
        }
        return journal.commit(checked.operationId.toString(), checked)
    }

    @Synchronized
    fun transition(expected: BuilderDraftRecord, target: BuilderDraftRecord): BuilderDraftRecord {
        val checkedExpected = expected.validated(maxBlocks)
        val checkedTarget = target.validated(maxBlocks)
        require(checkedExpected.phase == BuilderDraftPhase.PREPARED && checkedTarget.phase == BuilderDraftPhase.READY) {
            "Builder-draft transition is invalid"
        }
        require(checkedTarget.copy(schematicSha256 = null, phase = BuilderDraftPhase.PREPARED, updatedAtMillis = checkedExpected.updatedAtMillis) == checkedExpected) {
            "Builder-draft transition changed immutable identity"
        }
        val current = journal.loadOrNull(checkedExpected.operationId.toString())
        if (current == checkedTarget) return checkedTarget
        require(current == checkedExpected) { "Builder-draft durable predecessor changed" }
        return journal.commit(checkedTarget.operationId.toString(), checkedTarget)
    }

    fun loadAll(): List<BuilderDraftRecord> = journal.loadAll().map { it.value.validated(maxBlocks) }

    fun loadOrNull(operationId: UUID): BuilderDraftRecord? =
        journal.loadOrNull(operationId.toString())?.validated(maxBlocks)

    fun acknowledge(operationId: UUID): Boolean = journal.acknowledge(operationId.toString())

    fun acknowledgeConfirmed(operationId: UUID): Boolean {
        journal.acknowledge(operationId.toString())
        return journal.loadOrNull(operationId.toString()) == null
    }
}
