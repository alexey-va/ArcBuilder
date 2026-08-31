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
import ru.arc.util.BlockUtils.rotateBlockData

/** Exact Paper block-state rotation boundary; MockBukkit does not implement BlockData.rotate. */
internal fun interface BuilderBlockDataRotation {
    fun rotate(data: BlockData, degrees: Int): BlockData
}

internal object PaperBuilderBlockDataRotation : BuilderBlockDataRotation {
    override fun rotate(data: BlockData, degrees: Int) = rotateBlockData(data, degrees)
}

/** Generic protection and plan boundary consumed by the copy/paste lifecycle. */
internal interface BuilderClipboardHost {
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
    private var closed = false

    init {
        require(maximumBlocks in 1..BuilderPlan.ABSOLUTE_MAX_CHANGES) {
            "Builder clipboard maximum blocks must stay inside the absolute plan bound"
        }
        require(ttlMillis in Duration.ofMinutes(1).toMillis()..Duration.ofHours(2).toMillis()) {
            "Builder clipboard TTL must stay inside the configured safety bound"
        }
    }

    fun copy(player: Player): BuilderClipboard {
        check(!closed) { "Builder clipboard controller is closed" }
        host.ensureCopyPermission(player)
        val selection = host.requiredSelection(player)
        val world = host.world(selection.worldId)
        val blocks = mutableListOf<BuilderClipboardBlock>()
        var skippedUnsafe = 0
        selection.positionsBottomUp().forEach { position ->
            val block = world.getBlockAt(position.x, position.y, position.z)
            host.ensureInRangeAndLoaded(player, block)
            if (block.type.isAir) return@forEach
            if (safety.isReplaceable(block)) return@forEach
            if (!safety.isSafeExisting(block)) {
                skippedUnsafe += 1
                return@forEach
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
        }
        if (blocks.isEmpty()) host.fail("errors.empty-copy")
        val now = nowMillis()
        val clipboard = BuilderClipboard(
            blocks = blocks,
            sizeX = selection.sizeX,
            sizeY = selection.sizeY,
            sizeZ = selection.sizeZ,
            originDx = player.location.blockX - selection.minX,
            originDy = player.location.blockY - selection.minY,
            originDz = player.location.blockZ - selection.minZ,
            sourceRotation = rotationFromYaw(player.yaw),
            skippedUnsafeBlocks = skippedUnsafe,
            createdAtMillis = now,
            expiresAtMillis = Math.addExact(now, ttlMillis),
        ).validated(maximumBlocks)
        clipboards[player.uniqueId] = clipboard
        rotationAdjustments.remove(player.uniqueId)
        return clipboard
    }

    fun planPaste(player: Player): BuilderPlan {
        check(!closed) { "Builder clipboard controller is closed" }
        host.ensurePastePermission(player)
        val clipboard = current(player.uniqueId) ?: host.fail("errors.expired")
        val anchor = BuilderBlockPos(player.world.uid, player.location.blockX, player.location.blockY, player.location.blockZ)
        val world = player.world
        val rotation = normalizeRotation(
            rotationFromYaw(player.yaw) - clipboard.sourceRotation + rotationAdjustments.getOrDefault(player.uniqueId, 0),
        )
        val costs = mutableListOf<ItemStack>()
        val rewards = mutableListOf<ItemStack>()
        var skippedUnsafe = 0
        val changes = clipboard.blocks.mapNotNull { copied ->
            val localX = copied.dx - clipboard.originDx
            val localZ = copied.dz - clipboard.originDz
            val (rotatedX, rotatedZ) = rotate(localX, localZ, rotation)
            val position = BuilderBlockPos(
                worldId = anchor.worldId,
                x = Math.addExact(anchor.x, rotatedX),
                y = Math.addExact(anchor.y, copied.dy - clipboard.originDy),
                z = Math.addExact(anchor.z, rotatedZ),
            ).validated()
            val block = world.getBlockAt(position.x, position.y, position.z)
            val after = blockDataRotation.rotate(Bukkit.createBlockData(copied.blockData), rotation)
            if (!safety.isSafePlacement(after)) {
                skippedUnsafe += 1
                return@mapNotNull null
            }
            if (block.blockData.asString == after.asString) return@mapNotNull null
            val replaceable = safety.isReplaceable(block)
            if (!replaceable && !safety.isSafeExisting(block)) {
                skippedUnsafe += 1
                return@mapNotNull null
            }
            host.ensurePlacement(player, block, after.material)
            if (BuilderGameModePolicy.usesInventory(player.gameMode)) {
                BuilderPlacementCost.itemOrNull(after)?.let(costs::add)
                if (!replaceable) replacementRefund(block)?.let(rewards::add)
            }
            BuilderBlockChange(position, block.blockData.asString, after.asString)
        }
        if (changes.isEmpty()) host.fail("errors.nothing-to-change")
        return host.createPastePlan(
            player,
            changes,
            BuilderItemCodec.aggregate(costs),
            BuilderItemCodec.aggregate(rewards),
            skippedUnsafe,
        )
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
