package ru.arc.buildertools

import org.bukkit.entity.Player
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.ScheduledTask
import java.util.UUID

/**
 * Main-thread owner of pending plan previews and their bounded lifecycle.
 * Selection rendering remains delegated to its own controller, while this
 * component owns refresh failure throttling, exact-plan expiry and cleanup.
 */
internal class BuilderPreviewSessions(
    taskScope: LifecycleTaskScope,
    periodTicks: Long,
    private val onlinePlayers: () -> Iterable<Player>,
    private val canRender: (Player) -> Boolean,
    private val renderSelection: (Player) -> Unit,
    private val renderPlan: (Player, BuilderPlan) -> Unit,
    private val clearPlan: (UUID) -> Unit,
    private val onExpired: (UUID) -> Unit,
    private val onRenderFailure: (Player, RuntimeException) -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val taskScope = taskScope
    private val pending = mutableMapOf<UUID, BuilderPendingPlan>()
    private val failedPlayers = mutableSetOf<UUID>()
    private val expiryTasks = mutableMapOf<UUID, ScheduledTask>()
    private val loop = BuilderPreviewLoop(taskScope, periodTicks, ::renderAll)

    fun open(
        player: Player,
        plan: BuilderPendingPlan,
        expireAfterTicks: Long,
        showImmediately: Boolean = true,
    ) {
        require(expireAfterTicks >= 1L) { "Builder preview expiry must be positive" }
        val playerId = player.uniqueId
        expiryTasks.remove(playerId)?.cancel()
        pending[playerId] = plan
        val planId = plan.plan.id
        lateinit var expiryTask: ScheduledTask
        expiryTask = checkNotNull(
            taskScope.runLater(expireAfterTicks) {
                if (expiryTasks[playerId] === expiryTask && pending[playerId]?.plan?.id == planId) {
                    expiryTasks.remove(playerId)
                    pending.remove(playerId)
                    failedPlayers.remove(playerId)
                    clearPlan(playerId)
                    onExpired(playerId)
                }
            },
        ) { "Builder preview expiry was not scheduled" }
        expiryTasks[playerId] = expiryTask
        if (showImmediately) renderPlan(player, plan.plan)
    }

    /** Stores a plan consumed synchronously by an already-confirmed external GUI. */
    fun store(playerId: UUID, plan: BuilderPendingPlan) {
        expiryTasks.remove(playerId)?.cancel()
        failedPlayers.remove(playerId)
        pending[playerId] = plan
    }

    operator fun get(playerId: UUID): BuilderPendingPlan? = pending[playerId]

    fun plan(playerId: UUID): BuilderPlan? = pending[playerId]?.plan

    fun contains(playerId: UUID): Boolean = playerId in pending

    fun remove(playerId: UUID, expected: BuilderPendingPlan): Boolean {
        val removed = pending.remove(playerId, expected)
        if (removed) {
            expiryTasks.remove(playerId)?.cancel()
            failedPlayers.remove(playerId)
            clearPlan(playerId)
        }
        return removed
    }

    fun discard(playerId: UUID) {
        expiryTasks.remove(playerId)?.cancel()
        pending.remove(playerId)
        failedPlayers.remove(playerId)
        clearPlan(playerId)
    }

    private fun renderAll() {
        val now = nowMillis()
        onlinePlayers().forEach { player ->
            if (!canRender(player)) return@forEach
            try {
                pending[player.uniqueId]?.plan
                    ?.takeIf { it.expiresAtMillis > now }
                    ?.let { renderPlan(player, it) }
                renderSelection(player)
                failedPlayers.remove(player.uniqueId)
            } catch (failure: RuntimeException) {
                if (failedPlayers.add(player.uniqueId)) onRenderFailure(player, failure)
            }
        }
    }

    override fun close() {
        loop.close()
        expiryTasks.values.forEach(ScheduledTask::cancel)
        expiryTasks.clear()
        pending.keys.toList().forEach(clearPlan)
        pending.clear()
        failedPlayers.clear()
    }
}
