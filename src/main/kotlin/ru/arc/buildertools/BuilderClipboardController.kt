package ru.arc.buildertools

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.type.Leaves
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import ru.arc.util.BlockUtils.rotateBlockData

/** Exact Paper block-state rotation boundary; MockBukkit does not implement BlockData.rotate. */
internal fun interface BuilderBlockDataRotation {
    fun rotate(data: BlockData, degrees: Int): BlockData
}

internal object PaperBuilderBlockDataRotation : BuilderBlockDataRotation {
    override fun rotate(data: BlockData, degrees: Int) = rotateBlockData(data, degrees)
}

/** Generic protection and plan boundary consumed by the copy/paste lifecycle. */
internal interface BuilderClipboardHost : BuilderPlanningTasks {
    fun ensureCopyPermission(player: Player)
    fun ensurePastePermission(player: Player)
    fun requiredSelection(player: Player): BuilderSelection
    fun world(worldId: UUID): World
    fun ensureInRangeAndLoaded(player: Player, block: Block)
    fun ensureProtected(player: Player, block: Block)
    fun ensureMutable(player: Player, block: Block)
    fun ensurePlacement(player: Player, block: Block, material: Material) = ensureMutable(player, block)
    fun createPastePlan(
        player: Player,
        changes: List<BuilderBlockChange>,
        costs: List<BuilderItemAmount>,
        rewards: List<BuilderItemAmount>,
        skippedUnsafeBlocks: Int,
    ): BuilderPlan
    fun createPastePlanAsync(
        player: Player,
        changes: List<BuilderBlockChange>,
        costs: List<BuilderItemAmount>,
        rewards: List<BuilderItemAmount>,
        skippedUnsafeBlocks: Int,
    ): CompletableFuture<BuilderPlan> = onMain { createPastePlan(player, changes, costs, rewards, skippedUnsafeBlocks) }
    fun fail(path: String): Nothing
}

/**
 * Main-thread owner of bounded, non-durable player clipboards.
 *
 * Creation, expiry, copy filtering, paste planning, quit cleanup and shutdown
 * cleanup stay together. Generic permissions, Lands/range checks and plan
 * transactions remain delegated to [BuilderClipboardHost].
 */
internal class BuilderClipboardController(
    private val safety: BuilderBlockSafety,
    private val selections: BuilderSelectionController,
    private val maximumBlocks: Int,
    clipboardTtl: Duration,
    private val host: BuilderClipboardHost,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val blockDataRotation: BuilderBlockDataRotation = PaperBuilderBlockDataRotation,
    private val replacementRefund: (Block) -> ItemStack? = BuilderDeconstructionRefunds::fromSilkTouch,
) : AutoCloseable {
    private val ttlMillis = clipboardTtl.toMillis()
    private val clipboards = mutableMapOf<UUID, BuilderClipboard>()
    private val rotationAdjustments = mutableMapOf<UUID, Int>()
    private val copyRequests = mutableMapOf<UUID, Any>()
    private var closed = false

    init {
        require(maximumBlocks in 1..BuilderPlan.ABSOLUTE_MAX_CHANGES) {
            "Builder clipboard maximum blocks must stay inside the absolute plan bound"
        }
        require(ttlMillis in Duration.ofMinutes(1).toMillis()..Duration.ofHours(2).toMillis()) {
            "Builder clipboard TTL must stay inside the configured safety bound"
        }
    }

    fun copy(player: Player, isCurrent: () -> Boolean = { true }): CompletableFuture<BuilderClipboard> {
        check(!closed) { "Builder clipboard controller is closed" }
        host.ensureCopyPermission(player)
        val selection = host.requiredSelection(player)
        val world = host.world(selection.worldId)
        val playerId = player.uniqueId
        val request = Any().also { copyRequests[playerId] = it }
        val origin = player.location
        val sourceRotation = rotationFromYaw(player.yaw)
        val current = { isCurrent() && !closed && copyRequests[playerId] === request && player.isOnline &&
            player.world.uid == selection.worldId && host.requiredSelection(player) == selection }
        val blocks = mutableListOf<BuilderClipboardBlock>()
        val positions = selection.positionsBottomUp().iterator()
        var skippedUnsafe = 0
        return host.capture(current) {
            if (!positions.hasNext()) return@capture true
            val position = positions.next()
            if (!world.isChunkLoaded(position.x shr 4, position.z shr 4)) host.fail("errors.chunk-unloaded")
            val block = world.getBlockAt(position.x, position.y, position.z)
            host.ensureInRangeAndLoaded(player, block)
            if (block.type.isAir) return@capture false
            if (safety.isReplaceable(block)) return@capture false
            if (!safety.isSafeExisting(block)) {
                skippedUnsafe += 1
                return@capture false
            }
            host.ensureProtected(player, block)
            val copiedData = block.blockData.clone().also { data ->
                if (data is Leaves) data.isPersistent = true
            }
            blocks += BuilderClipboardBlock(
                dx = position.x - selection.minX,
                dy = position.y - selection.minY,
                dz = position.z - selection.minZ,
                blockData = copiedData.asString,
            )
            if (blocks.size > maximumBlocks) host.fail("errors.selection-too-large")
            false
        }.thenCompose {
            if (blocks.isEmpty()) host.fail("errors.empty-copy")
            val now = nowMillis()
            val captured = BuilderClipboard(
                blocks = blocks,
                sizeX = selection.sizeX,
                sizeY = selection.sizeY,
                sizeZ = selection.sizeZ,
                originDx = origin.blockX - selection.minX,
                originDy = origin.blockY - selection.minY,
                originDz = origin.blockZ - selection.minZ,
                sourceRotation = sourceRotation,
                skippedUnsafeBlocks = skippedUnsafe,
                createdAtMillis = now,
                expiresAtMillis = Math.addExact(now, ttlMillis),
            )
            host.compute { captured.copy(blocks = captured.blocks.toList()).validated(maximumBlocks) }
        }.thenCompose { clipboard ->
            host.onMain {
                requireCurrentBuilderPlanning(current)
                clipboards[playerId] = clipboard
                rotationAdjustments.remove(playerId)
                copyRequests.remove(playerId, request)
                clipboard
            }
        }.whenComplete { _, _ ->
            host.onMain { copyRequests.remove(playerId, request) }
        }
    }

    fun planPaste(player: Player, isCurrent: () -> Boolean = { true }): CompletableFuture<BuilderPlan> {
        check(!closed) { "Builder clipboard controller is closed" }
        host.ensurePastePermission(player)
        val clipboard = current(player.uniqueId) ?: host.fail("errors.expired")
        val anchor = BuilderBlockPos(player.world.uid, player.location.blockX, player.location.blockY, player.location.blockZ)
        val world = player.world
        val playerId = player.uniqueId
        val gameMode = player.gameMode
        val usesInventory = BuilderGameModePolicy.usesInventory(gameMode)
        val adjustment = rotationAdjustments.getOrDefault(playerId, 0)
        val rotation = normalizeRotation(
            rotationFromYaw(player.yaw) - clipboard.sourceRotation + adjustment,
        )
        val current = { isCurrent() && !closed && player.isOnline && player.world.uid == anchor.worldId &&
            player.gameMode == gameMode && this.current(playerId) === clipboard &&
            rotationAdjustments.getOrDefault(playerId, 0) == adjustment }
        val costs = mutableListOf<BuilderItemAmount>()
        val rewards = mutableListOf<BuilderItemAmount>()
        val prototypes = BuilderItemCodec.PrototypeCache()
        val rotatedData = mutableMapOf<String, BlockData>()
        val changes = mutableListOf<BuilderBlockChange>()
        var skippedUnsafe = 0
        return host.compute {
            clipboard.blocks.map { copied ->
                val (rotatedX, rotatedZ) = rotate(copied.dx - clipboard.originDx, copied.dz - clipboard.originDz, rotation)
                BuilderBlockPos(
                    worldId = anchor.worldId,
                    x = Math.addExact(anchor.x, rotatedX),
                    y = Math.addExact(anchor.y, copied.dy - clipboard.originDy),
                    z = Math.addExact(anchor.z, rotatedZ),
                ).validated() to copied.blockData
            }
        }.thenCompose { targets ->
            host.onMain {
                val positions = targets.iterator()
                host.capture(current) {
                    if (!positions.hasNext()) return@capture true
                    val (position, blockData) = positions.next()
                    if (!world.isChunkLoaded(position.x shr 4, position.z shr 4)) host.fail("errors.chunk-unloaded")
                    val block = world.getBlockAt(position.x, position.y, position.z)
                    val after = rotatedData.getOrPut(blockData) {
                        blockDataRotation.rotate(Bukkit.createBlockData(blockData), rotation)
                    }
                    if (!safety.isSafePlacement(after)) {
                        skippedUnsafe += 1
                        return@capture false
                    }
                    val before = block.blockData.asString
                    if (before == after.asString) return@capture false
                    val replaceable = safety.isReplaceable(block)
                    if (!replaceable && !safety.isSafeExisting(block)) {
                        skippedUnsafe += 1
                        return@capture false
                    }
                    host.ensurePlacement(player, block, after.material)
                    if (usesInventory) {
                        BuilderPlacementCost.itemOrNull(after)?.let { costs += prototypes.capture(it) }
                        if (!replaceable) replacementRefund(block)?.let { rewards += prototypes.capture(it) }
                    }
                    changes += BuilderBlockChange(position, before, after.asString)
                    false
                }
            }.thenCompose { it }
        }.thenCompose {
            if (changes.isEmpty()) host.fail("errors.nothing-to-change")
            host.compute { BuilderItemCodec.aggregateAmounts(costs) to BuilderItemCodec.aggregateAmounts(rewards) }
        }.thenCompose { (capturedCosts, capturedRewards) ->
            host.onMain {
                requireCurrentBuilderPlanning(current)
                host.createPastePlanAsync(player, changes, capturedCosts, capturedRewards, skippedUnsafe)
            }.thenCompose { it }
        }
    }

    fun rotate(playerId: UUID, delta: Int) {
        require(delta % 90 == 0)
        rotationAdjustments[playerId] = normalizeRotation(rotationAdjustments.getOrDefault(playerId, 0) + delta)
    }

    fun current(playerId: UUID): BuilderClipboard? {
        if (closed) return null
        val clipboard = clipboards[playerId] ?: return null
        if (clipboard.expiresAtMillis <= nowMillis()) {
            clipboards.remove(playerId, clipboard)
            return null
        }
        return clipboard
    }

    fun clear(playerId: UUID) {
        copyRequests.remove(playerId)
        clipboards.remove(playerId)
        rotationAdjustments.remove(playerId)
    }

    internal fun hasState(playerId: UUID): Boolean = playerId in clipboards

    val pendingCount: Int
        get() {
            val now = nowMillis()
            clipboards.entries.removeIf { (_, clipboard) -> clipboard.expiresAtMillis <= now }
            rotationAdjustments.keys.retainAll(clipboards.keys)
            return clipboards.size
        }

    override fun close() {
        if (closed) return
        closed = true
        clipboards.clear()
        rotationAdjustments.clear()
        copyRequests.clear()
    }

    private fun rotationFromYaw(yaw: Float): Int {
        val adjusted = (((yaw + 180f) % 360f) + 360f) % 360f
        return when {
            adjusted > 315 || adjusted <= 45 -> 0
            adjusted <= 135 -> 90
            adjusted <= 225 -> 180
            else -> 270
        }
    }

    private fun rotate(x: Int, z: Int, rotation: Int): Pair<Int, Int> = when (normalizeRotation(rotation)) {
        90 -> -z to x
        180 -> -x to -z
        270 -> z to -x
        else -> x to z
    }

    private fun normalizeRotation(rotation: Int): Int = ((rotation % 360) + 360) % 360
}
