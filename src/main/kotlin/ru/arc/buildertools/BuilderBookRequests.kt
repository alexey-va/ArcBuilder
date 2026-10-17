package ru.arc.buildertools

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.arc.core.LifecycleTaskScope
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture

/** Main-thread continuations: previews replace old requests; accepted grants retain independent tickets. */
internal class BuilderBookRequests(private val scope: LifecycleTaskScope) : AutoCloseable {
    private data class Ticket(val independent: Boolean, val failed: (Throwable?) -> Unit)
    private val pending = mutableMapOf<UUID, MutableMap<Any, Ticket>>()
    private var closed = false
    val size: Int get() = pending.values.sumOf { it.size }

    fun <T> run(
        player: Player,
        source: BuilderLocatedBook?,
        request: (isCurrent: () -> Boolean) -> CompletableFuture<T>,
        failed: (Throwable?) -> Unit,
        latestOnly: Boolean = true,
        ready: (T) -> Unit,
    ) {
        if (closed) { failed(CancellationException("Builder runtime is closed")); return }
        if (!latestOnly && size >= MAX_INDEPENDENT_REQUESTS) {
            failed(IllegalStateException("Too many pending system-book issuances; retry after completion"))
            return
        }
        val token = Any()
        val id = player.uniqueId
        val world = player.world.uid
        val mode = player.gameMode
        val heldSlot = player.inventory.heldItemSlot
        val expected = source?.item?.clone()
        if (latestOnly) pending[id]?.entries?.removeIf { !it.value.independent }
        pending.getOrPut(id) { linkedMapOf() }[token] = Ticket(!latestOnly, failed)
        val lifecycle = scope.token()
        // This predicate is server-thread only: capture steps use the same exact
        // context fence as the final continuation, through the entire pipeline.
        fun current(): Boolean = !closed && scope.isCurrent(lifecycle) && pending[id]?.containsKey(token) == true &&
            player.isOnline && (source != null || Bukkit.getPlayer(id) === player) &&
            (source == null || player.world.uid == world && player.gameMode == mode &&
                player.inventory.heldItemSlot == heldSlot && player.inventory.getItem(source.slot) == expected)
        val future = try { request(::current) } catch (failure: Throwable) { CompletableFuture.failedFuture(failure) }
        future.whenComplete { value, failure ->
            scope.runSync(lifecycle) continuation@{
                if (closed || pending[id]?.remove(token) == null) return@continuation
                if (pending[id]?.isEmpty() == true) pending.remove(id)
                if (!player.isOnline || source == null && Bukkit.getPlayer(id) !== player || source != null && (
                    player.world.uid != world || player.gameMode != mode ||
                    player.inventory.heldItemSlot != heldSlot || player.inventory.getItem(source.slot) != expected)) {
                    failed(null)
                    return@continuation
                }
                if (failure != null || value == null) failed(failure)
                else ready(value)
            }
        }
    }

    fun invalidate(playerId: UUID): Boolean {
        val removed = pending.remove(playerId) ?: return false
        removed.values.filter(Ticket::independent).forEach {
            runCatching { it.failed(CancellationException("System-book issuance was cancelled")) }
        }
        return true
    }

    override fun close() {
        closed = true
        pending.keys.toList().forEach(::invalidate)
    }

    private companion object { const val MAX_INDEPENDENT_REQUESTS = 256 }
}
