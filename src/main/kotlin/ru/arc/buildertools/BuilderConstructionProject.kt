package ru.arc.buildertools

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import ru.arc.persistence.DurableRecordJournal
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.UUID

private val BUILDER_LOOT_TABLE_KEY = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")

internal enum class BuilderConstructionProjectState(val terminal: Boolean) {
    PREPARED(false),
    ACTIVE(false),
    WAITING_MATERIALS(false),
    INPUT_PREPARED(false),
    WORLD_PREPARED(false),
    OUTPUT_PENDING(false),
    WAITING_OUTPUT_SPACE(false),
    DELIVERING_OUTPUT(false),
    PAUSED(false),
    RECOVERY_REQUIRED(false),
    COMPLETED(true),
    CANCELLED(true),
}

enum class BuilderConstructionSitePanelFace(val yaw: Float) {
    MIN_X(90f),
    MAX_X(270f),
    MIN_Z(180f),
    MAX_Z(0f),
}

internal data class BuilderConstructionStep(
    val change: BuilderBlockChange,
    val requiredMaterial: BuilderItemAmount?,
    val output: BuilderItemAmount?,
    val lootTableKey: String? = null,
    val systemFurniture: Boolean = false,
) {
    fun validated(): BuilderConstructionStep = apply {
        change.validated()
        requiredMaterial?.validated()
        output?.validated()
        if (systemFurniture) {
            val material = change.afterBlockData.substringBefore('[').removePrefix("minecraft:").uppercase()
            require(change.afterBlockData.startsWith("minecraft:") && BuilderSystemFurniturePolicy.isSupported(material)) {
                "System furniture step contains an unsupported block"
            }
        }
        lootTableKey?.let { key ->
            require(key.length <= 256 && key.matches(BUILDER_LOOT_TABLE_KEY)) {
                "Builder construction loot-table key is invalid"
            }
            require(change.afterBlockData == "minecraft:chest" || change.afterBlockData.startsWith("minecraft:chest[")) {
                "Builder construction loot table requires a chest step"
            }
        }
    }
}

internal data class BuilderConstructionProjectRecord(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val projectId: UUID,
    val playerId: UUID,
    val playerName: String,
    val projectTitle: String? = null,
    val sitePanelFace: BuilderConstructionSitePanelFace? = null,
    val plan: BuilderPlan,
    val steps: List<BuilderConstructionStep>,
    val bookCost: BuilderItemAmount,
    val state: BuilderConstructionProjectState,
    val cursor: Int,
    val pendingOutput: BuilderItemAmount? = null,
    val pendingResourceMutation: BuilderResourceMutation? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val applicationStartedAtMillis: Long? = null,
    val completedAtMillis: Long? = null,
    val completionFinalizedAtMillis: Long? = null,
    val instantBuildRequestedBy: UUID? = null,
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
        projectTitle?.let { title ->
            require(title.isNotBlank() && title.length <= 48 && title.none(Char::isISOControl)) {
                "Builder construction project title is invalid"
            }
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
        if (instantBuildRequestedBy != null) {
            require(state in setOf(BuilderConstructionProjectState.WORLD_PREPARED,
                BuilderConstructionProjectState.COMPLETED, BuilderConstructionProjectState.RECOVERY_REQUIRED,
                BuilderConstructionProjectState.CANCELLED)) { "Instant construction has an invalid state" }
            require(pendingResourceMutation == null && pendingOutput == null) { "Instant construction cannot own an item receipt" }
        }
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
        applicationStartedAtMillis?.let { startedAt ->
            require(startedAt in createdAtMillis..updatedAtMillis) {
                "Builder construction application time is invalid"
            }
        }
        require((completedAtMillis != null) == state.terminal) {
            "Builder construction project completion time does not match its state"
        }
        completedAtMillis?.let {
            require(it == updatedAtMillis) { "Builder construction project completion time is invalid" }
        }
        completionFinalizedAtMillis?.let { finalizedAt ->
            require(state == BuilderConstructionProjectState.COMPLETED) {
                "Only a completed builder construction project may be finalized"
            }
            require(finalizedAt >= checkNotNull(completedAtMillis)) {
                "Builder construction project finalization predates completion"
            }
        }
        require((state in OUTPUT_STATES) == (pendingOutput != null)) {
            "Builder construction project pending output does not match its state"
        }
        pendingOutput?.validated()
        pendingResourceMutation?.validated(playerId)?.sources
            ?.flatMap(BuilderResourceInventoryMutation::containerBlocks)
            ?.forEach { position ->
                require(position.worldId == steps.first().change.position.worldId) {
                    "Builder construction resource receipt crosses the project world"
                }
            }
        when (state) {
            BuilderConstructionProjectState.PREPARED -> {
                require(cursor == 0) { "A prepared builder construction project must start at its first step" }
                pendingResourceMutation?.let { mutation ->
                    require(!mutation.insert && mutation.amount == bookCost) {
                        "A prepared builder construction project must debit its exact book"
                    }
                    require(
                        mutation.sources.size == 1 &&
                            mutation.sources.single().kind == BuilderResourceSourceKind.PLAYER &&
                            !mutation.sources.single().requireNearProject,
                    ) { "A prepared builder construction project requires one unrestricted player receipt" }
                }
            }
            BuilderConstructionProjectState.INPUT_PREPARED -> {
                pendingResourceMutation?.let { mutation ->
                    require(!mutation.insert && mutation.amount == steps[cursor].requiredMaterial) {
                        "Builder construction input receipt does not match its step"
                    }
                }
            }
            BuilderConstructionProjectState.WORLD_PREPARED -> {
                val input = steps[cursor].requiredMaterial
                val mutation = pendingResourceMutation
                if (mutation != null) {
                    require(input != null && !mutation.insert && mutation.amount == input) {
                        "Builder construction world-prepared receipt does not match its step"
                    }
                }
            }
            BuilderConstructionProjectState.DELIVERING_OUTPUT -> {
                pendingResourceMutation?.let { mutation ->
                    require(mutation.insert && mutation.amount == pendingOutput) {
                        "Builder construction output receipt does not match its step"
                    }
                }
            }
            BuilderConstructionProjectState.COMPLETED -> require(cursor == steps.size) {
                "A completed builder construction project must finish every step"
            }
            else -> {
                require(cursor < steps.size) { "A non-completed builder construction project cannot be past its final step" }
                if (state != BuilderConstructionProjectState.RECOVERY_REQUIRED) {
                    require(pendingResourceMutation == null) {
                        "Builder construction project retained an unexpected resource receipt"
                    }
                }
            }
        }
    }

    fun activationPrepared(mutation: BuilderResourceMutation): BuilderConstructionProjectRecord =
        copy(pendingResourceMutation = mutation.validated(playerId)).validated()

    fun activated(nowMillis: Long): BuilderConstructionProjectRecord = transitionTo(
        copy(
            state = BuilderConstructionProjectState.ACTIVE,
            pendingOutput = null,
            pendingResourceMutation = null,
            updatedAtMillis = nowMillis,
        ),
    )

    fun waitingForMaterials(nowMillis: Long): BuilderConstructionProjectRecord = transitionTo(
        copy(
            state = BuilderConstructionProjectState.WAITING_MATERIALS,
            pendingOutput = null,
            pendingResourceMutation = null,
            updatedAtMillis = nowMillis,
        ),
    )

    fun inputPrepared(mutation: BuilderResourceMutation, nowMillis: Long): BuilderConstructionProjectRecord = transitionTo(
        copy(
            state = BuilderConstructionProjectState.INPUT_PREPARED,
            pendingOutput = null,
            pendingResourceMutation = mutation.validated(playerId),
            updatedAtMillis = nowMillis,
        ),
    )

    fun worldPrepared(nowMillis: Long): BuilderConstructionProjectRecord = transitionTo(
        copy(
            state = BuilderConstructionProjectState.WORLD_PREPARED,
            pendingOutput = null,
            updatedAtMillis = nowMillis,
        ),
    )

    fun outputPending(output: BuilderItemAmount, nowMillis: Long): BuilderConstructionProjectRecord = transitionTo(
        copy(
            state = BuilderConstructionProjectState.OUTPUT_PENDING,
            pendingOutput = output.validated(),
            pendingResourceMutation = null,
            updatedAtMillis = nowMillis,
        ),
    )

    fun waitingForOutput(nowMillis: Long): BuilderConstructionProjectRecord = transitionTo(
        copy(
            state = BuilderConstructionProjectState.WAITING_OUTPUT_SPACE,
            pendingResourceMutation = null,
            updatedAtMillis = nowMillis,
        ),
    )

    fun deliveringOutput(mutation: BuilderResourceMutation, nowMillis: Long): BuilderConstructionProjectRecord = transitionTo(
        copy(
            state = BuilderConstructionProjectState.DELIVERING_OUTPUT,
            pendingResourceMutation = mutation.validated(playerId),
            updatedAtMillis = nowMillis,
        ),
    )

    fun advanced(nowMillis: Long): BuilderConstructionProjectRecord {
        require(state != BuilderConstructionProjectState.RECOVERY_REQUIRED) {
            "A recovery-held builder construction project needs explicit reconciliation"
        }
        return advanceFromCurrent(nowMillis)
    }

    fun outputDelivered(nowMillis: Long): BuilderConstructionProjectRecord = advanceFromCurrent(nowMillis)

    fun recoveryRequired(nowMillis: Long): BuilderConstructionProjectRecord = transitionTo(
        copy(
            state = BuilderConstructionProjectState.RECOVERY_REQUIRED,
            pendingOutput = null,
            updatedAtMillis = nowMillis,
        ),
    )

    fun paused(nowMillis: Long): BuilderConstructionProjectRecord = transitionTo(
        copy(
            state = BuilderConstructionProjectState.PAUSED,
            pendingOutput = null,
            pendingResourceMutation = null,
            updatedAtMillis = nowMillis,
        ),
    )

    fun resumed(nowMillis: Long): BuilderConstructionProjectRecord = transitionTo(
        copy(
            state = BuilderConstructionProjectState.ACTIVE,
            pendingOutput = null,
            pendingResourceMutation = null,
            updatedAtMillis = nowMillis,
        ),
    )

    /**
     * Advances a recovery hold only after startup reconciliation proved that a
     * no-exchange companion step was already applied atomically by its neighbour.
     */
    fun recoveredAppliedStep(nowMillis: Long): BuilderConstructionProjectRecord {
        require(state == BuilderConstructionProjectState.RECOVERY_REQUIRED) {
            "Only a recovery-held builder construction project can reconcile an applied step"
        }
        val step = steps[cursor]
        require(step.requiredMaterial == null && step.output == null && pendingResourceMutation == null) {
            "A builder construction exchange cannot be auto-reconciled"
        }
        return advanceFromCurrent(nowMillis)
    }

    fun cancelled(nowMillis: Long): BuilderConstructionProjectRecord = transitionTo(
        copy(
            state = BuilderConstructionProjectState.CANCELLED,
            pendingOutput = null,
            pendingResourceMutation = null,
            updatedAtMillis = nowMillis,
            completedAtMillis = nowMillis,
        ),
    )

    fun completionFinalized(nowMillis: Long): BuilderConstructionProjectRecord {
        require(state == BuilderConstructionProjectState.COMPLETED && completionFinalizedAtMillis == null) {
            "Only an unfinalized completed builder construction project may be finalized"
        }
        return transitionTo(copy(completionFinalizedAtMillis = nowMillis))
    }

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
                pendingResourceMutation = null,
                updatedAtMillis = nowMillis,
                completedAtMillis = nowMillis.takeIf { completed },
            ),
        )
    }

    private fun transitionTo(target: BuilderConstructionProjectRecord): BuilderConstructionProjectRecord =
        target.also { BuilderConstructionProjectTransitionRules.validate(this, it) }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        private val OUTPUT_STATES = setOf(
            BuilderConstructionProjectState.OUTPUT_PENDING,
            BuilderConstructionProjectState.WAITING_OUTPUT_SPACE,
            BuilderConstructionProjectState.DELIVERING_OUTPUT,
        )

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
        if (before.state == BuilderConstructionProjectState.COMPLETED) {
            require(before.completionFinalizedAtMillis == null) {
                "A finalized builder construction project cannot transition"
            }
            require(after == before.copy(completionFinalizedAtMillis = after.completionFinalizedAtMillis)) {
                "Builder construction project finalization changed unrelated data"
            }
            require(after.completionFinalizedAtMillis != null) {
                "Builder construction project finalization timestamp is missing"
            }
            return
        }
        require(!before.terminal) { "A terminal builder construction project cannot transition" }
        require(after.completionFinalizedAtMillis == before.completionFinalizedAtMillis) {
            "Builder construction project was finalized before completion"
        }
        if (before.instantBuildRequestedBy != null || after.instantBuildRequestedBy != null) {
            BuilderInstantConstruction.validateTransition(before, after)
            return
        }
        val sameCursor = after.cursor == before.cursor
        val advancedOne = after.cursor == before.cursor + 1
        val valid = when (before.state) {
            BuilderConstructionProjectState.PREPARED ->
                after.state == BuilderConstructionProjectState.ACTIVE && sameCursor ||
                    after.state == BuilderConstructionProjectState.RECOVERY_REQUIRED && sameCursor ||
                    after.state == BuilderConstructionProjectState.CANCELLED && sameCursor
            BuilderConstructionProjectState.ACTIVE ->
                after.state == BuilderConstructionProjectState.INPUT_PREPARED && sameCursor ||
                    after.state == BuilderConstructionProjectState.WORLD_PREPARED && sameCursor ||
                    after.state == BuilderConstructionProjectState.ACTIVE && advancedOne ||
                    after.state == BuilderConstructionProjectState.WAITING_MATERIALS && sameCursor ||
                    after.state == BuilderConstructionProjectState.OUTPUT_PENDING && sameCursor ||
                    after.state == BuilderConstructionProjectState.PAUSED && sameCursor ||
                    after.state == BuilderConstructionProjectState.RECOVERY_REQUIRED && sameCursor ||
                    after.state == BuilderConstructionProjectState.COMPLETED && advancedOne ||
                    after.state == BuilderConstructionProjectState.CANCELLED && sameCursor
            BuilderConstructionProjectState.WAITING_MATERIALS ->
                after.state == BuilderConstructionProjectState.INPUT_PREPARED && sameCursor ||
                    after.state == BuilderConstructionProjectState.WORLD_PREPARED && sameCursor ||
                    after.state == BuilderConstructionProjectState.ACTIVE && advancedOne ||
                    after.state == BuilderConstructionProjectState.OUTPUT_PENDING && sameCursor ||
                    after.state == BuilderConstructionProjectState.PAUSED && sameCursor ||
                    after.state == BuilderConstructionProjectState.COMPLETED && advancedOne ||
                    after.state == BuilderConstructionProjectState.RECOVERY_REQUIRED && sameCursor ||
                    after.state == BuilderConstructionProjectState.CANCELLED && sameCursor
            BuilderConstructionProjectState.INPUT_PREPARED ->
                after.state == BuilderConstructionProjectState.WORLD_PREPARED && sameCursor ||
                    after.state == BuilderConstructionProjectState.WAITING_MATERIALS && sameCursor ||
                    after.state == BuilderConstructionProjectState.RECOVERY_REQUIRED && sameCursor ||
                    after.state == BuilderConstructionProjectState.CANCELLED && sameCursor
            BuilderConstructionProjectState.WORLD_PREPARED ->
                after.state == BuilderConstructionProjectState.ACTIVE && advancedOne ||
                    after.state == BuilderConstructionProjectState.OUTPUT_PENDING && sameCursor ||
                    after.state == BuilderConstructionProjectState.COMPLETED && advancedOne ||
                    after.state == BuilderConstructionProjectState.RECOVERY_REQUIRED && sameCursor ||
                    after.state == BuilderConstructionProjectState.CANCELLED && sameCursor
            BuilderConstructionProjectState.OUTPUT_PENDING ->
                after.state == BuilderConstructionProjectState.DELIVERING_OUTPUT && sameCursor ||
                    after.state == BuilderConstructionProjectState.WAITING_OUTPUT_SPACE && sameCursor ||
                    after.state == BuilderConstructionProjectState.RECOVERY_REQUIRED && sameCursor ||
                    after.state == BuilderConstructionProjectState.CANCELLED && sameCursor
            BuilderConstructionProjectState.WAITING_OUTPUT_SPACE ->
                after.state == BuilderConstructionProjectState.DELIVERING_OUTPUT && sameCursor ||
                    after.state == BuilderConstructionProjectState.RECOVERY_REQUIRED && sameCursor ||
                    after.state == BuilderConstructionProjectState.CANCELLED && sameCursor
            BuilderConstructionProjectState.DELIVERING_OUTPUT ->
                after.state == BuilderConstructionProjectState.WAITING_OUTPUT_SPACE && sameCursor ||
                    after.state == BuilderConstructionProjectState.ACTIVE && advancedOne ||
                    after.state == BuilderConstructionProjectState.COMPLETED && advancedOne ||
                    after.state == BuilderConstructionProjectState.RECOVERY_REQUIRED && sameCursor ||
                    after.state == BuilderConstructionProjectState.CANCELLED && sameCursor
            BuilderConstructionProjectState.RECOVERY_REQUIRED ->
                after.state == BuilderConstructionProjectState.ACTIVE && advancedOne ||
                    after.state == BuilderConstructionProjectState.COMPLETED && advancedOne ||
                    after.state == BuilderConstructionProjectState.CANCELLED && sameCursor
            BuilderConstructionProjectState.PAUSED ->
                after.state == BuilderConstructionProjectState.ACTIVE && sameCursor ||
                    after.state == BuilderConstructionProjectState.CANCELLED && sameCursor
            BuilderConstructionProjectState.COMPLETED,
            BuilderConstructionProjectState.CANCELLED,
            -> false
        }
        require(valid) { "Builder construction project transition is invalid" }
    }

    private fun BuilderConstructionProjectRecord.immutableIdentity(): List<Any?> = listOf(
        schemaVersion,
        projectId,
        playerId,
        playerName,
        projectTitle,
        sitePanelFace,
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
        return try {
            journal.commit(checked.projectId.toString(), checked)
        } catch (commitFailure: Throwable) {
            val afterFailure = try {
                journal.loadOrNull(checked.projectId.toString())
            } catch (readFailure: Throwable) {
                throw BuilderConstructionProjectUnknownOutcomeException(commitFailure, readFailure)
            }
            when (afterFailure) {
                checked -> checked
                null -> throw BuilderConstructionProjectTransitionRejectedException(commitFailure)
                else -> throw BuilderConstructionProjectUnknownOutcomeException(commitFailure)
            }
        }
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
        return try {
            journal.commit(checkedTarget.projectId.toString(), checkedTarget)
        } catch (commitFailure: Throwable) {
            val afterFailure = try {
                journal.loadOrNull(checkedExpected.projectId.toString())
            } catch (readFailure: Throwable) {
                throw BuilderConstructionProjectUnknownOutcomeException(commitFailure, readFailure)
            }
            when (afterFailure) {
                checkedTarget -> checkedTarget
                checkedExpected -> throw BuilderConstructionProjectTransitionRejectedException(commitFailure)
                else -> throw BuilderConstructionProjectUnknownOutcomeException(commitFailure)
            }
        }
    }

    fun loadAll(): List<BuilderConstructionProjectRecord> =
        journal.loadAll().map { it.value.validated(maxChanges) }

    fun loadOrNull(projectId: UUID): BuilderConstructionProjectRecord? =
        journal.loadOrNull(projectId.toString())?.validated(maxChanges)
}

internal class BuilderConstructionProjectTransitionRejectedException(cause: Throwable) : RuntimeException(cause)

internal class BuilderConstructionProjectUnknownOutcomeException(
    cause: Throwable,
    readFailure: Throwable? = null,
) : RuntimeException("Builder construction project's durable transition outcome is unknown", cause) {
    init {
        readFailure?.let(::addSuppressed)
    }
}

internal interface BuilderConstructionProjectPort {
    fun currentBlockData(position: BuilderBlockPos): String

    /** Includes durable block-entity metadata when a step owns any. */
    fun isStepApplied(step: BuilderConstructionStep): Boolean =
        currentBlockData(step.change.position) == step.change.afterBlockData

    fun canModify(project: BuilderConstructionProjectRecord, step: BuilderConstructionStep): Boolean

    fun prepareInput(
        playerId: UUID,
        project: BuilderConstructionProjectRecord,
        input: BuilderItemAmount,
    ): BuilderResourceMutation?

    fun prepareOutput(
        playerId: UUID,
        project: BuilderConstructionProjectRecord,
        output: BuilderItemAmount,
    ): BuilderResourceMutation?

    fun reconcileResource(
        project: BuilderConstructionProjectRecord,
        mutation: BuilderResourceMutation,
    ): BuilderResourceMutationResult

    fun rollbackResource(
        project: BuilderConstructionProjectRecord,
        mutation: BuilderResourceMutation,
    ): BuilderResourceMutationResult

    fun apply(project: BuilderConstructionProjectRecord, step: BuilderConstructionStep)
}

internal class BuilderConstructionTemporarilyUnavailableException : RuntimeException()

internal object BuilderConstructionRecoveryPolicy {
    fun canResumeAppliedNoExchangeStep(record: BuilderConstructionProjectRecord): Boolean =
        record.state == BuilderConstructionProjectState.RECOVERY_REQUIRED &&
            record.pendingResourceMutation == null &&
            record.steps.getOrNull(record.cursor)?.let { step ->
                step.requiredMaterial == null && step.output == null
            } == true

    fun normalizeLoaded(
        record: BuilderConstructionProjectRecord,
        nowMillis: Long,
    ): BuilderConstructionProjectRecord = when {
        record.state == BuilderConstructionProjectState.PREPARED && record.pendingResourceMutation == null ->
            record.recoveryRequired(nowMillis)
        record.state == BuilderConstructionProjectState.INPUT_PREPARED && record.pendingResourceMutation == null ->
            record.recoveryRequired(nowMillis)
        record.state == BuilderConstructionProjectState.WORLD_PREPARED && record.instantBuildRequestedBy == null &&
            record.steps[record.cursor].requiredMaterial != null && record.pendingResourceMutation == null ->
            record.recoveryRequired(nowMillis)
        record.state == BuilderConstructionProjectState.DELIVERING_OUTPUT && record.pendingResourceMutation == null ->
            record.recoveryRequired(nowMillis)
        else -> record
    }

    /**
     * A paired block (bed, door, tall plant) may apply its companion before the
     * companion's durable cursor transition. This is the only recovery state
     * that is safe to resume automatically: no item exchange is pending and the
     * complete step, including owned block-entity data, already matches.
     */
    fun resumeAppliedNoExchangeStep(
        record: BuilderConstructionProjectRecord,
        nowMillis: Long,
        port: BuilderConstructionProjectPort,
    ): BuilderConstructionProjectRecord = if (
        canResumeAppliedNoExchangeStep(record) &&
        runCatching { port.isStepApplied(record.steps[record.cursor]) }.getOrDefault(false)
    ) {
        record.recoveredAppliedStep(nowMillis)
    } else {
        record
    }
}

internal object BuilderConstructionTransitionFailurePolicy {
    /** A confirmed write rejection must never cause an already-run value mutation to be retried. */
    fun requiresRecovery(
        expected: BuilderConstructionProjectRecord,
        target: BuilderConstructionProjectRecord,
    ): Boolean = if (expected.instantBuildRequestedBy != null) false else when (expected.state) {
        BuilderConstructionProjectState.PREPARED ->
            target.state == BuilderConstructionProjectState.ACTIVE
        BuilderConstructionProjectState.INPUT_PREPARED ->
            target.state == BuilderConstructionProjectState.WORLD_PREPARED
        BuilderConstructionProjectState.WORLD_PREPARED ->
            target.state == BuilderConstructionProjectState.ACTIVE ||
                target.state == BuilderConstructionProjectState.OUTPUT_PENDING ||
                target.state == BuilderConstructionProjectState.COMPLETED
        BuilderConstructionProjectState.DELIVERING_OUTPUT ->
            target.state == BuilderConstructionProjectState.ACTIVE ||
                target.state == BuilderConstructionProjectState.COMPLETED
        else -> false
    }
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
        if (current.instantBuildRequestedBy != null && current.state == BuilderConstructionProjectState.WORLD_PREPARED) {
            return BuilderInstantConstruction.applyBatch(current, nowMillis, port)
        }
        return when (current.state) {
            BuilderConstructionProjectState.RECOVERY_REQUIRED,
            BuilderConstructionProjectState.COMPLETED,
            BuilderConstructionProjectState.CANCELLED,
            BuilderConstructionProjectState.PAUSED,
            -> null
            BuilderConstructionProjectState.PREPARED -> activate(current, nowMillis, port)
            BuilderConstructionProjectState.WAITING_MATERIALS -> prepareStep(current, nowMillis, port)
            BuilderConstructionProjectState.INPUT_PREPARED -> debitInput(current, nowMillis, port)
            BuilderConstructionProjectState.WORLD_PREPARED -> applyWorld(current, nowMillis, port)
            BuilderConstructionProjectState.OUTPUT_PENDING,
            BuilderConstructionProjectState.WAITING_OUTPUT_SPACE,
            -> prepareOutput(current, nowMillis, port)
            BuilderConstructionProjectState.DELIVERING_OUTPUT -> deliverOutput(current, nowMillis, port)
            BuilderConstructionProjectState.ACTIVE -> prepareStep(current, nowMillis, port)
        }
    }

    private fun activate(
        record: BuilderConstructionProjectRecord,
        nowMillis: Long,
        port: BuilderConstructionProjectPort,
    ): BuilderConstructionProjectRecord? {
        val mutation = record.pendingResourceMutation ?: return record.recoveryRequired(nowMillis)
        return when (reconcileResource(record, mutation, port)) {
            BuilderResourceMutationResult.APPLIED -> record.activated(nowMillis)
            BuilderResourceMutationResult.RETRY -> null
            BuilderResourceMutationResult.STALE -> record.cancelled(nowMillis)
            BuilderResourceMutationResult.CONFLICT -> record.recoveryRequired(nowMillis)
        }
    }

    private fun deliverOutput(
        record: BuilderConstructionProjectRecord,
        nowMillis: Long,
        port: BuilderConstructionProjectPort,
    ): BuilderConstructionProjectRecord? {
        val step = record.steps[record.cursor]
        when (worldMatchesAfter(step, port)) {
            null -> return null
            false -> return record.recoveryRequired(nowMillis)
            true -> Unit
        }
        val mutation = record.pendingResourceMutation ?: return record.recoveryRequired(nowMillis)
        return when (reconcileResource(record, mutation, port)) {
            BuilderResourceMutationResult.APPLIED -> record.outputDelivered(nowMillis)
            BuilderResourceMutationResult.RETRY -> null
            BuilderResourceMutationResult.STALE -> record.waitingForOutput(nowMillis)
            BuilderResourceMutationResult.CONFLICT -> record.recoveryRequired(nowMillis)
        }
    }

    private fun prepareOutput(
        record: BuilderConstructionProjectRecord,
        nowMillis: Long,
        port: BuilderConstructionProjectPort,
    ): BuilderConstructionProjectRecord? {
        val output = checkNotNull(record.pendingOutput)
        val mutation = try {
            port.prepareOutput(record.playerId, record, output)
        } catch (_: Throwable) {
            null
        } ?: return if (record.state == BuilderConstructionProjectState.OUTPUT_PENDING) {
            record.waitingForOutput(nowMillis)
        } else {
            null
        }
        return record.deliveringOutput(mutation, nowMillis)
    }

    private fun prepareStep(
        record: BuilderConstructionProjectRecord,
        nowMillis: Long,
        port: BuilderConstructionProjectPort,
    ): BuilderConstructionProjectRecord? {
        val step = record.steps[record.cursor]
        when (worldMatchesBefore(step, port)) {
            null -> return null
            false -> return when (worldMatchesAfter(step, port)) {
                null -> null
                false -> record.recoveryRequired(nowMillis)
                true -> appliedStep(record, step, nowMillis)
            }
            true -> Unit
        }
        when (canModify(record, step, port)) {
            null -> return null
            false -> return record.recoveryRequired(nowMillis)
            true -> Unit
        }
        val input = step.requiredMaterial
        if (input == null) return record.worldPrepared(nowMillis)
        val mutation = try {
            port.prepareInput(record.playerId, record, input)
        } catch (_: Throwable) {
            null
        }
        if (mutation != null) return record.inputPrepared(mutation, nowMillis)
        return if (record.state == BuilderConstructionProjectState.WAITING_MATERIALS) {
            null
        } else {
            record.waitingForMaterials(nowMillis)
        }
    }

    private fun debitInput(
        record: BuilderConstructionProjectRecord,
        nowMillis: Long,
        port: BuilderConstructionProjectPort,
    ): BuilderConstructionProjectRecord? {
        val step = record.steps[record.cursor]
        when (worldMatchesBefore(step, port)) {
            null -> return null
            false -> return record.recoveryRequired(nowMillis)
            true -> Unit
        }
        when (canModify(record, step, port)) {
            null -> return null
            false -> return record.recoveryRequired(nowMillis)
            true -> Unit
        }
        val mutation = record.pendingResourceMutation ?: return record.recoveryRequired(nowMillis)
        return when (reconcileResource(record, mutation, port)) {
            BuilderResourceMutationResult.APPLIED -> record.worldPrepared(nowMillis)
            BuilderResourceMutationResult.RETRY -> null
            BuilderResourceMutationResult.STALE -> record.waitingForMaterials(nowMillis)
            BuilderResourceMutationResult.CONFLICT -> record.recoveryRequired(nowMillis)
        }
    }

    private fun applyWorld(
        record: BuilderConstructionProjectRecord,
        nowMillis: Long,
        port: BuilderConstructionProjectPort,
    ): BuilderConstructionProjectRecord? {
        val step = record.steps[record.cursor]
        val before = worldMatchesBefore(step, port)
        if (before == null) return null
        if (before) {
            when (canModify(record, step, port)) {
                null -> return null
                false -> return compensateOrRecover(record, step, nowMillis, port)
                true -> Unit
            }
        } else {
            when (worldBlockDataMatchesAfter(step, port)) {
                null -> return null
                false -> return record.recoveryRequired(nowMillis)
                true -> Unit
            }
            when (worldMatchesAfter(step, port)) {
                null -> return null
                false -> if (step.lootTableKey == null) return record.recoveryRequired(nowMillis)
                true -> return appliedStep(record, step, nowMillis)
            }
        }
        try {
            port.apply(record, step)
        } catch (_: Throwable) {
            return when (worldMatchesAfter(step, port)) {
                true -> appliedStep(record, step, nowMillis)
                false -> compensateOrRecover(record, step, nowMillis, port)
                null -> record.recoveryRequired(nowMillis)
            }
        }
        return appliedStep(record, step, nowMillis)
    }

    private fun appliedStep(
        record: BuilderConstructionProjectRecord,
        step: BuilderConstructionStep,
        nowMillis: Long,
    ): BuilderConstructionProjectRecord {
        val output = step.output ?: return record.advanced(nowMillis)
        return record.outputPending(output, nowMillis)
    }

    private fun compensateOrRecover(
        record: BuilderConstructionProjectRecord,
        step: BuilderConstructionStep,
        nowMillis: Long,
        port: BuilderConstructionProjectPort,
    ): BuilderConstructionProjectRecord {
        record.pendingResourceMutation?.let { mutation ->
            runCatching { port.rollbackResource(record, mutation) }
        }
        return record.recoveryRequired(nowMillis)
    }

    private fun reconcileResource(
        record: BuilderConstructionProjectRecord,
        mutation: BuilderResourceMutation,
        port: BuilderConstructionProjectPort,
    ): BuilderResourceMutationResult = try {
        port.reconcileResource(record, mutation)
    } catch (_: Throwable) {
        BuilderResourceMutationResult.CONFLICT
    }

    private fun worldMatchesBefore(
        step: BuilderConstructionStep,
        port: BuilderConstructionProjectPort,
    ): Boolean? = try {
        port.currentBlockData(step.change.position) == step.change.beforeBlockData
    } catch (_: BuilderConstructionTemporarilyUnavailableException) {
        null
    } catch (_: Throwable) {
        null
    }

    private fun worldMatchesAfter(
        step: BuilderConstructionStep,
        port: BuilderConstructionProjectPort,
    ): Boolean? = try {
        port.isStepApplied(step)
    } catch (_: BuilderConstructionTemporarilyUnavailableException) {
        null
    } catch (_: Throwable) {
        null
    }

    private fun worldBlockDataMatchesAfter(
        step: BuilderConstructionStep,
        port: BuilderConstructionProjectPort,
    ): Boolean? = try {
        port.currentBlockData(step.change.position) == step.change.afterBlockData
    } catch (_: BuilderConstructionTemporarilyUnavailableException) {
        null
    } catch (_: Throwable) {
        null
    }

    private fun canModify(
        record: BuilderConstructionProjectRecord,
        step: BuilderConstructionStep,
        port: BuilderConstructionProjectPort,
    ): Boolean? = try {
        port.canModify(record, step)
    } catch (_: BuilderConstructionTemporarilyUnavailableException) {
        null
    } catch (_: Throwable) {
        false
    }
}
