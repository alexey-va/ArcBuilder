package ru.arc.buildertools

import org.bukkit.Location
import org.bukkit.entity.Player
import ru.arc.observability.StructuredDebugLine
import ru.arc.util.Logging
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

internal data class BuilderActionTarget(
    val worldId: UUID,
    val worldName: String?,
    val x: Int,
    val y: Int,
    val z: Int,
) {
    companion object {
        fun at(location: Location): BuilderActionTarget = BuilderActionTarget(
            checkNotNull(location.world).uid, location.world?.name,
            location.blockX, location.blockY, location.blockZ,
        )
    }
}

internal data class BuilderActionRejection(val reason: String, val target: BuilderActionTarget? = null)

internal data class BuilderActionContext(
    val playerId: UUID,
    val playerName: String,
    val action: String,
    val target: BuilderActionTarget,
    val operationId: UUID? = null,
    val previewId: String? = null,
    val buildingId: String? = null,
    val blueprintId: UUID? = null,
    val bookInstanceId: UUID? = null,
)

/** User input and diagnostic transitions only; ordinary rendering, hover and ticks stay silent. */
internal class BuilderActionDiagnostics(
    private val record: (String) -> Unit = { Logging.info(it) },
    private val recordFailure: (String, Throwable) -> Unit = { line, failure -> Logging.error(line, failure) },
) {
    private val line = StructuredDebugLine("ARC_BUILDER_ACTION")
    private data class ConstructionIssue(
        val reason: String,
        val target: BuilderActionTarget,
        val attempt: BuilderActionDiagnostics.Attempt,
        var suppressed: Int = 0,
    )
    private val constructionIssues = mutableMapOf<Pair<UUID, String>, ConstructionIssue>()

    fun begin(context: BuilderActionContext): Attempt = Attempt(UUID.randomUUID(), context).also {
        record(it.format("attempt", "received"))
    }

    fun spatialInput(
        player: Player,
        surface: String,
        scene: String,
        target: Location,
        accepted: Boolean,
        reason: String,
        operationId: UUID? = null,
        previewId: String? = null,
    ) {
        // Vanilla emits paired at/entity events and an off-hand event for one gesture.
        if (reason == "duplicate" || reason == "off_hand") return
        val context = BuilderActionContext(
            player.uniqueId, player.name, surface, BuilderActionTarget.at(target), operationId, previewId,
        )
        record(Attempt(UUID.randomUUID(), context).format(
            if (accepted) "input_accepted" else "input_rejected", reason, extra = listOf("scene" to scene),
        ))
    }

    fun constructionBlocked(
        context: BuilderActionContext,
        reason: String,
        failure: Throwable? = null,
        phase: String = context.action,
    ) {
        val projectId = checkNotNull(context.operationId)
        val key = projectId to phase
        val previous = constructionIssues[key]
        if (previous?.reason == reason && previous.target == context.target) {
            previous.suppressed++
            return
        }
        val attempt = Attempt(UUID.randomUUID(), context)
        constructionIssues[key] = ConstructionIssue(reason, context.target, attempt)
        if (failure == null) attempt.outcome("blocked", reason, "suppressed_previous" to previous?.suppressed)
        else attempt.failed(failure)
    }

    fun constructionAllowed(projectId: UUID, phase: String = "construction_step", position: BuilderBlockPos? = null) {
        val key = projectId to phase
        val issue = constructionIssues[key] ?: return
        if (position != null && (issue.target.worldId != position.worldId || issue.target.x != position.x ||
                issue.target.y != position.y || issue.target.z != position.z)) return
        constructionIssues.remove(key)
        issue.attempt.outcome("recovered", "check_recovered", "suppressed" to issue.suppressed)
    }

    fun forgetConstruction(projectId: UUID) {
        val issues = constructionIssues.entries.iterator()
        while (issues.hasNext()) {
            val issue = issues.next()
            if (issue.key.first != projectId) continue
            issue.value.attempt.outcome("closed", "project_terminal", "suppressed" to issue.value.suppressed)
            issues.remove()
        }
    }

    fun constructionTransition(context: BuilderActionContext, state: String, cursor: Int) {
        Attempt(UUID.randomUUID(), context).outcome("state_changed", state, "cursor" to cursor)
    }

    fun clear() { constructionIssues.clear() }

    inner class Attempt internal constructor(val id: UUID, private var context: BuilderActionContext) {
        var outcomeRecorded: Boolean = false
            private set

        fun operation(operationId: UUID) { context = context.copy(operationId = operationId) }

        fun outcome(outcome: String, reason: String, vararg details: Pair<String, Any?>) {
            outcomeRecorded = true
            record(format(outcome, reason, extra = details.asList()))
        }

        fun failed(raw: Throwable?) {
            outcomeRecorded = true
            var failure = raw
            repeat(8) {
                if ((failure is CompletionException || failure is ExecutionException) && failure.cause != null) {
                    failure = failure.cause
                }
            }
            when (val cause = failure) {
                null -> outcome("cancelled", "context_changed")
                is CancellationException -> outcome("cancelled", "request_cancelled")
                is BuilderUserFailure -> record(format(
                    "rejected", cause.diagnostic?.reason ?: cause.path,
                    cause.diagnostic?.target, listOf("message" to cause.path),
                ))
                else -> recordFailure(format(
                    "failed", "unexpected_exception", extra = listOf("failure_type" to BuilderToolsFailureType.of(cause)),
                ), cause)
            }
        }

        internal fun format(
            outcome: String,
            reason: String,
            target: BuilderActionTarget? = null,
            extra: List<Pair<String, Any?>> = emptyList(),
        ): String {
            val at = target ?: context.target
            return line.line(listOf(
                "attempt" to id, "action" to context.action, "outcome" to outcome, "reason" to reason,
                "player" to context.playerId, "player_name" to context.playerName,
                "world" to at.worldName, "world_id" to at.worldId, "x" to at.x, "y" to at.y, "z" to at.z,
                "operation" to context.operationId, "preview" to context.previewId,
                "building" to context.buildingId, "blueprint" to context.blueprintId, "book" to context.bookInstanceId,
            ) + extra)
        }
    }
}
