package ru.arc.buildertools

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import ru.arc.persistence.DurableRecordJournal
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.UUID

internal enum class BuilderConstructionProjectState(val terminal: Boolean) {
    PREPARED(false),
    ACTIVE(false),
    WAITING_MATERIALS(false),
    WAITING_OUTPUT_SPACE(false),
    RECOVERY_REQUIRED(false),
    COMPLETED(true),
    CANCELLED(true),
}

internal data class BuilderConstructionStep(
    val change: BuilderBlockChange,
    val requiredMaterial: BuilderItemAmount?,
    val output: BuilderItemAmount?,
) {
    fun validated(): BuilderConstructionStep = apply {
        change.validated()
        requiredMaterial?.validated()
        output?.validated()
    }
}

internal data class BuilderConstructionProjectRecord(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val projectId: UUID,
    val playerId: UUID,
    val playerName: String,
    val plan: BuilderPlan,
    val steps: List<BuilderConstructionStep>,
    val bookCost: BuilderItemAmount,
    val state: BuilderConstructionProjectState,
    val cursor: Int,
    val pendingOutput: BuilderItemAmount? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val completedAtMillis: Long? = null,
) {
    val terminal: Boolean get() = state.terminal

    fun validated(maxChanges: Int = BuilderPlan.ABSOLUTE_MAX_CHANGES): BuilderConstructionProjectRecord = apply {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "Unsupported builder construction project schema" }
        require(projectId == plan.id && playerId == plan.playerId) {
            "Builder construction project identity mismatch"
        }
        require(playerName.matches(Regex("[A-Za-z0-9_]{1,16}"))) {
            "Builder construction project player name is invalid"
        }
        plan.validated(maxChanges)
        require(plan.kind == BuilderPlanKind.BUILD_BOOK) {
            "Only a build-book plan may become a construction project"
        }
        require(plan.toolFingerprintBase64 == null && plan.toolDamage == 0) {
            "Builder construction projects never use tools"
        }
        require(steps.size == plan.changes.size && steps.map(BuilderConstructionStep::change) == plan.changes) {
            "Builder construction project steps do not match its plan"
        }
        require(steps.map { it.change.position.worldId }.toSet().size == 1) {
            "Builder construction project cannot cross worlds"
        }
        steps.forEach(BuilderConstructionStep::validated)
        bookCost.validated()
        require(bookCost.amount == 1) { "Builder construction project consumes exactly one book" }
        require(sameExchange(plan.costs, listOf(bookCost) + steps.mapNotNull(BuilderConstructionStep::requiredMaterial))) {
            "Builder construction project input exchange does not match its plan"
        }
        require(sameExchange(plan.rewards, steps.mapNotNull(BuilderConstructionStep::output))) {
            "Builder construction project output exchange does not match its plan"
        }
        require(cursor in 0..steps.size) { "Builder construction project cursor is outside its plan" }
        require(createdAtMillis == plan.createdAtMillis && updatedAtMillis >= createdAtMillis) {
            "Builder construction project timestamps are invalid"
        }
        require((completedAtMillis != null) == state.terminal) {
            "Builder construction project completion time does not match its state"
        }
        completedAtMillis?.let {
            require(it == updatedAtMillis) { "Builder construction project completion time is invalid" }
        }
        require((state == BuilderConstructionProjectState.WAITING_OUTPUT_SPACE) == (pendingOutput != null)) {
            "Builder construction project pending output does not match its state"
        }
        pendingOutput?.validated()
        when (state) {
            BuilderConstructionProjectState.PREPARED -> require(cursor == 0) {
                "A prepared builder construction project must start at its first step"
            }
            BuilderConstructionProjectState.COMPLETED -> require(cursor == steps.size) {
                "A completed builder construction project must finish every step"
            }
            else -> require(cursor < steps.size) {
                "A non-completed builder construction project cannot be past its final step"
            }
        }
    }

    fun activated(nowMillis: Long): BuilderConstructionProjectRecord = transitionTo(
        copy(
            state = BuilderConstructionProjectState.ACTIVE,
            pendingOutput = null,
            updatedAtMillis = nowMillis,
        ),
    )

    fun waitingForMaterials(nowMillis: Long): BuilderConstructionProjectRecord = transitionTo(
        copy(
            state = BuilderConstructionProjectState.WAITING_MATERIALS,
            pendingOutput = null,
            updatedAtMillis = nowMillis,
        ),
    )

    fun waitingForOutput(output: BuilderItemAmount, nowMillis: Long): BuilderConstructionProjectRecord = transitionTo(
        copy(
            state = BuilderConstructionProjectState.WAITING_OUTPUT_SPACE,
            pendingOutput = output.validated(),
            updatedAtMillis = nowMillis,
        ),
    )

    fun advanced(nowMillis: Long): BuilderConstructionProjectRecord = advanceFromCurrent(nowMillis)

    fun outputDelivered(nowMillis: Long): BuilderConstructionProjectRecord = advanceFromCurrent(nowMillis)

    fun recoveryRequired(nowMillis: Long): BuilderConstructionProjectRecord = transitionTo(
        copy(
            state = BuilderConstructionProjectState.RECOVERY_REQUIRED,
            pendingOutput = null,
            updatedAtMillis = nowMillis,
        ),
    )

    fun cancelled(nowMillis: Long): BuilderConstructionProjectRecord = transitionTo(
        copy(
            state = BuilderConstructionProjectState.CANCELLED,
            pendingOutput = null,
            updatedAtMillis = nowMillis,
            completedAtMillis = nowMillis,
        ),
    )

    private fun advanceFromCurrent(nowMillis: Long): BuilderConstructionProjectRecord {
        val nextCursor = cursor + 1
        val completed = nextCursor == steps.size
        return transitionTo(
            copy(
                state = if (completed) {
                    BuilderConstructionProjectState.COMPLETED
                } else {
                    BuilderConstructionProjectState.ACTIVE
                },
                cursor = nextCursor,
                pendingOutput = null,
                updatedAtMillis = nowMillis,
                completedAtMillis = nowMillis.takeIf { completed },
            ),
        )
    }

    private fun transitionTo(target: BuilderConstructionProjectRecord): BuilderConstructionProjectRecord =
        target.also { BuilderConstructionProjectTransitionRules.validate(this, it) }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1

        private fun sameExchange(first: List<BuilderItemAmount>, second: List<BuilderItemAmount>): Boolean =
            normalizedExchange(first) == normalizedExchange(second)

        private fun normalizedExchange(items: List<BuilderItemAmount>): Map<Pair<String, String>, Long> =
            items.groupingBy { it.itemBase64 to it.materialKey }
                .fold(0L) { total, item -> Math.addExact(total, item.amount.toLong()) }
    }
}

internal object BuilderConstructionProjectTransitionRules {
    fun validate(
        expected: BuilderConstructionProjectRecord,
        target: BuilderConstructionProjectRecord,
    ) {
        val before = expected.validated()
        val after = target.validated()
        require(before.immutableIdentity() == after.immutableIdentity()) {
            "Builder construction project transition changed immutable data"
        }
        require(after.updatedAtMillis >= before.updatedAtMillis) {
            "Builder construction project transition moved time backwards"
        }
        require(!before.terminal) { "A terminal builder construction project cannot transition" }
        val sameCursor = after.cursor == before.cursor
        val advancedOne = after.cursor == before.cursor + 1
        val valid = when (before.state) {
            BuilderConstructionProjectState.PREPARED ->
                after.state == BuilderConstructionProjectState.ACTIVE && sameCursor ||
                    after.state == BuilderConstructionProjectState.RECOVERY_REQUIRED && sameCursor ||
                    after.state == BuilderConstructionProjectState.CANCELLED && sameCursor
            BuilderConstructionProjectState.ACTIVE ->
                after.state == BuilderConstructionProjectState.ACTIVE && advancedOne ||
                    after.state == BuilderConstructionProjectState.WAITING_MATERIALS && sameCursor ||
                    after.state == BuilderConstructionProjectState.WAITING_OUTPUT_SPACE && sameCursor ||
                    after.state == BuilderConstructionProjectState.RECOVERY_REQUIRED && sameCursor ||
                    after.state == BuilderConstructionProjectState.COMPLETED && advancedOne ||
                    after.state == BuilderConstructionProjectState.CANCELLED && sameCursor
            BuilderConstructionProjectState.WAITING_MATERIALS ->
                after.state == BuilderConstructionProjectState.ACTIVE && sameCursor ||
                    after.state == BuilderConstructionProjectState.RECOVERY_REQUIRED && sameCursor ||
                    after.state == BuilderConstructionProjectState.CANCELLED && sameCursor
            BuilderConstructionProjectState.WAITING_OUTPUT_SPACE ->
                after.state == BuilderConstructionProjectState.ACTIVE && advancedOne ||
                    after.state == BuilderConstructionProjectState.COMPLETED && advancedOne ||
                    after.state == BuilderConstructionProjectState.RECOVERY_REQUIRED && sameCursor ||
                    after.state == BuilderConstructionProjectState.CANCELLED && sameCursor
            BuilderConstructionProjectState.RECOVERY_REQUIRED ->
                after.state == BuilderConstructionProjectState.CANCELLED && sameCursor
            BuilderConstructionProjectState.COMPLETED,
            BuilderConstructionProjectState.CANCELLED,
            -> false
        }
        require(valid) { "Builder construction project transition is invalid" }
    }

    private fun BuilderConstructionProjectRecord.immutableIdentity(): List<Any> = listOf(
        schemaVersion,
        projectId,
        playerId,
        playerName,
        plan,
        steps,
        bookCost,
        createdAtMillis,
    )
}

internal class BuilderConstructionProjectStore(
    dataRoot: Path,
    private val maxChanges: Int,
    gson: Gson = GsonBuilder().disableHtmlEscaping().create(),
) {
    private val journal = DurableRecordJournal(
        root = dataRoot,
        relativeDirectory = Path.of("data", "builder-construction-projects"),
        maxRecordBytes = 64L * 1024L * 1024L,
        encode = { record: BuilderConstructionProjectRecord ->
            gson.toJson(record).toByteArray(StandardCharsets.UTF_8)
        },
        decode = { bytes ->
            gson.fromJson(String(bytes, StandardCharsets.UTF_8), BuilderConstructionProjectRecord::class.java)
        },
        validate = { record -> record.validated(maxChanges) },
    )

    @Synchronized
    fun commit(record: BuilderConstructionProjectRecord): BuilderConstructionProjectRecord {
        val checked = record.validated(maxChanges)
        require(checked.state == BuilderConstructionProjectState.PREPARED) {
            "A new builder construction project must be prepared"
        }
        require(journal.loadOrNull(checked.projectId.toString()) == null) {
            "Builder construction project already exists"
        }
        return journal.commit(checked.projectId.toString(), checked)
    }

    @Synchronized
    fun transition(
        expected: BuilderConstructionProjectRecord,
        target: BuilderConstructionProjectRecord,
    ): BuilderConstructionProjectRecord {
        val checkedExpected = expected.validated(maxChanges)
        val checkedTarget = target.validated(maxChanges)
        BuilderConstructionProjectTransitionRules.validate(checkedExpected, checkedTarget)
        val current = journal.loadOrNull(checkedExpected.projectId.toString())
        if (current == checkedTarget) return checkedTarget
        require(current == checkedExpected) { "Builder construction project's durable predecessor changed" }
        return journal.commit(checkedTarget.projectId.toString(), checkedTarget)
    }

    fun loadAll(): List<BuilderConstructionProjectRecord> =
        journal.loadAll().map { it.value.validated(maxChanges) }

    fun loadOrNull(projectId: UUID): BuilderConstructionProjectRecord? =
        journal.loadOrNull(projectId.toString())?.validated(maxChanges)
}

internal interface BuilderConstructionProjectPort {
    fun currentBlockData(position: BuilderBlockPos): String

    fun canModify(playerId: UUID, change: BuilderBlockChange): Boolean

    fun removeInput(
        playerId: UUID,
        project: BuilderConstructionProjectRecord,
        input: BuilderItemAmount,
    ): Boolean

    fun returnInput(
        playerId: UUID,
        project: BuilderConstructionProjectRecord,
        input: BuilderItemAmount,
    ): Boolean

    fun storeOutput(
        playerId: UUID,
        project: BuilderConstructionProjectRecord,
        output: BuilderItemAmount,
    ): Boolean

    fun apply(change: BuilderBlockChange)
}

internal object BuilderConstructionProjectController {
    /**
     * Processes at most one durable state transition. The caller persists the
     * returned record before invoking another tick.
     */
    fun tick(
        record: BuilderConstructionProjectRecord,
        nowMillis: Long,
        port: BuilderConstructionProjectPort,
    ): BuilderConstructionProjectRecord? {
        val current = record.validated()
        return when (current.state) {
            BuilderConstructionProjectState.PREPARED,
            BuilderConstructionProjectState.RECOVERY_REQUIRED,
            BuilderConstructionProjectState.COMPLETED,
            BuilderConstructionProjectState.CANCELLED,
            -> null
            BuilderConstructionProjectState.WAITING_MATERIALS -> resumeMaterials(current, nowMillis, port)
            BuilderConstructionProjectState.WAITING_OUTPUT_SPACE -> deliverOutput(current, nowMillis, port)
            BuilderConstructionProjectState.ACTIVE -> processStep(current, nowMillis, port)
        }
    }

    private fun resumeMaterials(
        record: BuilderConstructionProjectRecord,
        nowMillis: Long,
        port: BuilderConstructionProjectPort,
    ): BuilderConstructionProjectRecord {
        val step = record.steps[record.cursor]
        return if (worldMatchesBefore(record, step, port) && canModify(record, step, port)) {
            record.activated(nowMillis)
        } else {
            record.recoveryRequired(nowMillis)
        }
    }

    private fun deliverOutput(
        record: BuilderConstructionProjectRecord,
        nowMillis: Long,
        port: BuilderConstructionProjectPort,
    ): BuilderConstructionProjectRecord? {
        val step = record.steps[record.cursor]
        if (!worldMatchesAfter(record, step, port)) return record.recoveryRequired(nowMillis)
        val output = checkNotNull(record.pendingOutput)
        return try {
            if (port.storeOutput(record.playerId, record, output)) {
                record.outputDelivered(nowMillis)
            } else {
                null
            }
        } catch (_: Throwable) {
            record.recoveryRequired(nowMillis)
        }
    }

    private fun processStep(
        record: BuilderConstructionProjectRecord,
        nowMillis: Long,
        port: BuilderConstructionProjectPort,
    ): BuilderConstructionProjectRecord {
        val step = record.steps[record.cursor]
        if (!worldMatchesBefore(record, step, port) || !canModify(record, step, port)) {
            return record.recoveryRequired(nowMillis)
        }
        val input = step.requiredMaterial
        if (input != null) {
            val removed = try {
                port.removeInput(record.playerId, record, input)
            } catch (_: Throwable) {
                false
            }
            if (!removed) return record.waitingForMaterials(nowMillis)
        }
        try {
            port.apply(step.change)
        } catch (_: Throwable) {
            input?.let { runCatching { port.returnInput(record.playerId, record, it) } }
            return record.recoveryRequired(nowMillis)
        }
        val output = step.output ?: return record.advanced(nowMillis)
        return try {
            if (port.storeOutput(record.playerId, record, output)) {
                record.advanced(nowMillis)
            } else {
                record.waitingForOutput(output, nowMillis)
            }
        } catch (_: Throwable) {
            record.waitingForOutput(output, nowMillis)
        }
    }

    private fun worldMatchesBefore(
        record: BuilderConstructionProjectRecord,
        step: BuilderConstructionStep,
        port: BuilderConstructionProjectPort,
    ): Boolean = runCatching {
        port.currentBlockData(step.change.position) == step.change.beforeBlockData
    }.getOrDefault(false)

    private fun worldMatchesAfter(
        record: BuilderConstructionProjectRecord,
        step: BuilderConstructionStep,
        port: BuilderConstructionProjectPort,
    ): Boolean = runCatching {
        port.currentBlockData(step.change.position) == step.change.afterBlockData
    }.getOrDefault(false)

    private fun canModify(
        record: BuilderConstructionProjectRecord,
        step: BuilderConstructionStep,
        port: BuilderConstructionProjectPort,
    ): Boolean = runCatching {
        port.canModify(record.playerId, step.change)
    }.getOrDefault(false)
}
