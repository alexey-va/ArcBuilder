package ru.arc.buildertools

import org.bukkit.GameMode
import org.bukkit.block.Block
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockFadeEvent
import org.bukkit.event.block.BlockFormEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockGrowEvent
import org.bukkit.event.block.BlockPhysicsEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.BlockSpreadEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.inventory.InventoryPickupItemEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.entity.Player
import org.bukkit.block.Container
import org.bukkit.block.DoubleChest
import org.bukkit.inventory.Inventory
import org.bukkit.plugin.Plugin
import java.util.Locale
import java.util.UUID

internal enum class BuilderCommitBoundary {
    /** The durable record is not committed, so interruption may still roll the mutation back. */
    ROLLBACK_SAFE,

    /** The COMMITTED transition has been submitted and its durable outcome is not known yet. */
    COMMIT_IN_FLIGHT,

    /** A durable outcome is ambiguous or its downstream book claim still needs restart recovery. */
    RECOVERY_REQUIRED,
}

internal data class BuilderActiveOperation(
    var record: BuilderJournalRecord,
    val gameMode: GameMode,
    var appliedChanges: Int = 0,
    var mutationBatches: Int = 0,
    var inventoryMutated: Boolean = false,
    var cancelled: Boolean = false,
) {
    var commitBoundary: BuilderCommitBoundary = BuilderCommitBoundary.ROLLBACK_SAFE
        private set

    val interruptionDeferred: Boolean
        get() = commitBoundary != BuilderCommitBoundary.ROLLBACK_SAFE

    fun beginCommit() {
        check(commitBoundary == BuilderCommitBoundary.ROLLBACK_SAFE) {
            "Builder operation already crossed its rollback-safe commit boundary"
        }
        commitBoundary = BuilderCommitBoundary.COMMIT_IN_FLIGHT
    }

    fun markCommitFailureKnown() {
        check(commitBoundary == BuilderCommitBoundary.COMMIT_IN_FLIGHT) {
            "Builder operation has no in-flight commit to reject"
        }
        commitBoundary = BuilderCommitBoundary.ROLLBACK_SAFE
    }

    fun requireCommitRecovery() {
        check(commitBoundary != BuilderCommitBoundary.ROLLBACK_SAFE) {
            "Builder operation cannot require commit recovery before commit starts"
        }
        commitBoundary = BuilderCommitBoundary.RECOVERY_REQUIRED
    }
}

/**
 * Primary-thread owner of Builder Tools' player and block mutation locks.
 *
 * The registry owns acquisition, release, event isolation, and listener
 * cleanup together. Async storage callbacks must marshal to the Paper primary
 * thread before calling it. A block lock is identified by the exact operation
 * UUID so an old completion cannot release a newer operation's blocks.
 */
internal class BuilderOperationLocks(plugin: Plugin) : Listener, AutoCloseable {
    private val activeOperations = mutableMapOf<UUID, BuilderActiveOperation>()
    private val lockedBlocks = mutableMapOf<BuilderBlockPos, UUID>()
    private val resourceLockedBlocks = mutableMapOf<BuilderBlockPos, UUID>()
    private val bookLockedPlayers = mutableSetOf<UUID>()
    private val recoveryLockedPlayers = mutableSetOf<UUID>()
    private var closed = false

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    fun tryLock(plan: BuilderPlan): Boolean {
        if (closed || plan.changes.any { lockedBlocks.containsKey(it.position) }) return false
        plan.changes.forEach { lockedBlocks[it.position] = plan.id }
        return true
    }

    fun unlock(plan: BuilderPlan) {
        plan.changes.forEach { change -> lockedBlocks.remove(change.position, plan.id) }
    }

    /**
     * Isolates every physical holder of a durable resource receipt until its successor state is
     * committed. This closes the inventory ABA window where an applied receipt could otherwise be
     * externally restored to its exact `before` snapshot and replayed.
     */
    fun tryResourceLock(projectId: UUID, positions: Collection<BuilderBlockPos>): Boolean {
        if (closed) return false
        val distinct = positions.toSet()
        if (distinct.any { position ->
                lockedBlocks[position]?.let { it != projectId } == true ||
                    resourceLockedBlocks[position]?.let { it != projectId } == true
            }
        ) return false
        distinct.forEach { position -> resourceLockedBlocks[position] = projectId }
        return true
    }

    fun unlockResources(projectId: UUID) {
        resourceLockedBlocks.entries.removeIf { (_, owner) -> owner == projectId }
    }

    fun register(operation: BuilderActiveOperation) {
        check(!closed) { "Builder operation locks are closed" }
        val playerId = operation.record.playerId
        check(playerId !in activeOperations) { "Builder player already has an active operation" }
        check(operation.record.plan.changes.all { lockedBlocks[it.position] == operation.record.operationId }) {
            "Builder operation must own every block lock before registration"
        }
        activeOperations[playerId] = operation
    }

    fun operation(playerId: UUID): BuilderActiveOperation? = activeOperations[playerId]

    fun operations(): List<BuilderActiveOperation> = activeOperations.values.toList()

    fun finish(operation: BuilderActiveOperation) {
        activeOperations.remove(operation.record.playerId, operation)
        unlock(operation.record.plan)
    }

    fun tryBookLock(playerId: UUID): Boolean = !closed && !isPlayerLocked(playerId) && bookLockedPlayers.add(playerId)

    fun lockBook(playerId: UUID) {
        check(!closed) { "Builder operation locks are closed" }
        bookLockedPlayers.add(playerId)
    }

    fun unlockBook(playerId: UUID) {
        bookLockedPlayers.remove(playerId)
    }

    fun lockRecovery(playerId: UUID) {
        check(!closed) { "Builder operation locks are closed" }
        recoveryLockedPlayers.add(playerId)
    }

    fun unlockRecovery(playerId: UUID) {
        recoveryLockedPlayers.remove(playerId)
    }

    fun bookLockedPlayerIds(): Set<UUID> = bookLockedPlayers.toSet()

    fun isBookLocked(playerId: UUID): Boolean = playerId in bookLockedPlayers

    fun isRecoveryLocked(playerId: UUID): Boolean = playerId in recoveryLockedPlayers

    fun isPlayerLocked(playerId: UUID): Boolean =
        playerId in activeOperations || playerId in bookLockedPlayers || playerId in recoveryLockedPlayers

    val activeOperationCount: Int get() = activeOperations.size
    val bookLockedPlayerCount: Int get() = bookLockedPlayers.size
    val recoveryLockedPlayerCount: Int get() = recoveryLockedPlayers.size

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onInventoryClick(event: InventoryClickEvent) {
        if (
            (event.whoClicked as? Player)?.uniqueId?.let(::isPlayerLocked) == true ||
            isResourceInventoryLocked(event.view.topInventory) ||
            event.clickedInventory?.let(::isResourceInventoryLocked) == true
        ) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onInventoryDrag(event: InventoryDragEvent) {
        if (
            (event.whoClicked as? Player)?.uniqueId?.let(::isPlayerLocked) == true ||
            isResourceInventoryLocked(event.view.topInventory)
        ) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onInventoryOpen(event: InventoryOpenEvent) {
        if (isResourceInventoryLocked(event.inventory)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onInventoryMove(event: InventoryMoveItemEvent) {
        if (isResourceInventoryLocked(event.source) || isResourceInventoryLocked(event.destination)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onInventoryPickup(event: InventoryPickupItemEvent) {
        if (isResourceInventoryLocked(event.inventory)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onDrop(event: PlayerDropItemEvent) {
        if (isPlayerLocked(event.player.uniqueId)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onPickup(event: EntityPickupItemEvent) {
        if ((event.entity as? Player)?.uniqueId?.let(::isPlayerLocked) == true) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onSwap(event: PlayerSwapHandItemsEvent) {
        if (isPlayerLocked(event.player.uniqueId)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onHeldSlot(event: PlayerItemHeldEvent) {
        if (isPlayerLocked(event.player.uniqueId)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onCommandDuringOperation(event: PlayerCommandPreprocessEvent) {
        if (!isPlayerLocked(event.player.uniqueId)) return
        if (isBookLocked(event.player.uniqueId) || isRecoveryLocked(event.player.uniqueId)) {
            event.isCancelled = true
            return
        }
        val normalized = event.message.trim().lowercase(Locale.ROOT).split(Regex("\\s+"))
        val safeControl = normalized.firstOrNull() == "/builder" &&
            BuilderRootCommand.parse(normalized.getOrNull(1))?.safeDuringOperation == true
        if (!safeControl) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onDamage(event: EntityDamageEvent) {
        if ((event.entity as? Player)?.uniqueId?.let(::isPlayerLocked) == true) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onBreak(event: BlockBreakEvent) {
        if (isPlayerLocked(event.player.uniqueId) || isLocked(event.block)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onPlace(event: BlockPlaceEvent) {
        if (isPlayerLocked(event.player.uniqueId) || isLocked(event.blockPlaced)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onPhysics(event: BlockPhysicsEvent) {
        if (isLocked(event.block) || isLocked(event.sourceBlock)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onFromTo(event: BlockFromToEvent) {
        if (isLocked(event.block) || isLocked(event.toBlock)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onGrow(event: BlockGrowEvent) {
        if (isLocked(event.block)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onSpread(event: BlockSpreadEvent) {
        if (isLocked(event.block) || isLocked(event.source)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onFade(event: BlockFadeEvent) {
        if (isLocked(event.block)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onBurn(event: BlockBurnEvent) {
        if (isLocked(event.block)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onForm(event: BlockFormEvent) {
        if (isLocked(event.block)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onEntityChange(event: EntityChangeBlockEvent) {
        if (isLocked(event.block)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onEntityExplode(event: EntityExplodeEvent) {
        if (event.blockList().any(::isLocked)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onBlockExplode(event: BlockExplodeEvent) {
        if (event.blockList().any(::isLocked) || isLocked(event.block)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        if (event.blocks.any { isLocked(it) || isLocked(it.getRelative(event.direction)) }) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        if (event.blocks.any { isLocked(it) || isLocked(it.getRelative(event.direction)) }) event.isCancelled = true
    }

    private fun isLocked(block: Block): Boolean =
        BuilderBlockPos(block.world.uid, block.x, block.y, block.z).let { position ->
            position in lockedBlocks || position in resourceLockedBlocks
        }

    private fun isResourceInventoryLocked(inventory: Inventory): Boolean =
        inventoryHolderBlocks(inventory).any { block ->
            BuilderBlockPos(block.world.uid, block.x, block.y, block.z) in resourceLockedBlocks
        }

    private fun inventoryHolderBlocks(inventory: Inventory): List<Block> = when (val holder = inventory.holder) {
        is Container -> listOf(holder.block)
        is DoubleChest -> listOf(holder.leftSide, holder.rightSide)
            .mapNotNull { (it as? Container)?.block }
        else -> emptyList()
    }

    override fun close() {
        if (closed) return
        closed = true
        HandlerList.unregisterAll(this)
        activeOperations.clear()
        lockedBlocks.clear()
        resourceLockedBlocks.clear()
        bookLockedPlayers.clear()
        recoveryLockedPlayers.clear()
    }
}
