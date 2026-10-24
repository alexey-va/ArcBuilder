package ru.arc.buildertools

import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Generic protection and transaction boundary consumed by deconstruction planning. */
internal interface BuilderDeconstructionHost : BuilderPlanningTasks {
    fun ensurePermission(player: Player)
    fun canDeconstructWithoutTool(player: Player): Boolean
    fun requiredSelection(player: Player): BuilderSelection
    fun world(worldId: UUID): World
    fun ensureMutable(player: Player, block: Block)
    fun createPlan(
        player: Player,
        changes: List<BuilderBlockChange>,
        rewards: List<BuilderItemAmount>,
        toolFingerprint: String?,
        toolDamage: Int,
        skippedUnsafeBlocks: Int,
    ): BuilderPlan
    fun createPlanAsync(
        player: Player,
        changes: List<BuilderBlockChange>,
        rewards: List<BuilderItemAmount>,
        toolFingerprint: String?,
        toolDamage: Int,
        skippedUnsafeBlocks: Int,
    ): CompletableFuture<BuilderPlan> = onMain {
        createPlan(player, changes, rewards, toolFingerprint, toolDamage, skippedUnsafeBlocks)
    }
    fun fail(path: String): Nothing
}

internal data class BuilderDeconstructionCapture(
    val changes: List<BuilderBlockChange>,
    val rewards: List<BuilderItemAmount>,
    val toolFingerprint: String?,
    val toolDamage: Int,
    val toolBypassUsed: Boolean,
    val skippedUnsafeBlocks: Int,
)

/**
 * Main-thread owner of bounded deconstruction planning.
 *
 * Survival pools suitable tools from player storage while reserving one point
 * of durability on each. An explicit permission may fall back to tool-free
 * drops. Silk Touch is removed from the drop query, while other tool state is
 * preserved.
 */
internal class BuilderDeconstructionController(
    private val safety: BuilderBlockSafety,
    private val maximumChanges: Int,
    private val host: BuilderDeconstructionHost,
    private val isPreferredTool: (Block, ItemStack) -> Boolean = { block, tool -> block.isPreferredTool(tool) },
    private val blockDrops: (Block, ItemStack?, Player) -> Collection<ItemStack> = { block, tool, player ->
        block.getDrops(tool, player)
    },
) {
    init {
        require(maximumChanges in 1..BuilderPlan.ABSOLUTE_MAX_CHANGES) {
            "Builder deconstruction maximum changes must stay inside the absolute plan bound"
        }
    }

    fun plan(player: Player, isCurrent: () -> Boolean = { true }): CompletableFuture<BuilderPlan> {
        host.ensurePermission(player)
        val selection = host.requiredSelection(player)
        val world = host.world(selection.worldId)
        val gameMode = player.gameMode
        val current = { isCurrent() && player.isOnline && player.world.uid == selection.worldId &&
            player.gameMode == gameMode && host.requiredSelection(player) == selection }
        return capture(player, world, selection.positionsTopDown().iterator(), current).thenCompose { captured ->
            if (captured.changes.isEmpty()) host.fail("errors.nothing-to-change")
            host.onMain {
                requireCurrentBuilderPlanning(current)
                host.createPlanAsync(player, captured.changes, captured.rewards, captured.toolFingerprint,
                    captured.toolDamage, captured.skippedUnsafeBlocks)
            }.thenCompose { it }
        }
    }

    /** MOVE consumes this same tool/drop pipeline for exact occupied target preimages. */
    fun captureBlocks(
        player: Player,
        before: Map<BuilderBlockPos, String>,
        isCurrent: () -> Boolean,
    ): CompletableFuture<BuilderDeconstructionCapture> {
        host.ensurePermission(player)
        require(before.isNotEmpty() && before.size <= maximumChanges)
        val worldId = before.keys.first().worldId
        return host.compute {
            require(before.keys.all { it.worldId == worldId })
            before.keys.sortedWith(compareByDescending<BuilderBlockPos> { it.y }.thenBy { it.x }.thenBy { it.z })
        }.thenCompose { positions -> host.onMain {
            requireCurrentBuilderPlanning(isCurrent)
            capture(player, host.world(worldId), positions.iterator(), isCurrent, before)
        }.thenCompose { it } }
    }

    private fun capture(
        player: Player,
        world: World,
        positions: Iterator<BuilderBlockPos>,
        current: () -> Boolean,
        expectedBefore: Map<BuilderBlockPos, String> = emptyMap(),
    ): CompletableFuture<BuilderDeconstructionCapture> {
        val usesInventory = BuilderGameModePolicy.usesInventory(player.gameMode)
        val canBypassTool = usesInventory && host.canDeconstructWithoutTool(player)
        val tools = if (usesInventory) pooledTools(player) else emptyList()
        val usesTools = tools.any(PooledTool::available)
        if (usesInventory && !usesTools && !canBypassTool) host.fail("errors.tool")
        val changes = mutableListOf<BuilderBlockChange>()
        val refunds = mutableListOf<BuilderItemAmount>()
        val prototypes = BuilderItemCodec.PrototypeCache()
        var pendingDrops = emptyList<ItemStack>().iterator()
        val air = Material.AIR.createBlockData().asString
        var skippedUnsafe = 0
        var toolBypassUsed = usesInventory && !usesTools

        return host.capture(current) {
            if (pendingDrops.hasNext()) {
                val drop = pendingDrops.next()
                if (!drop.type.isAir && drop.amount > 0) refunds += prototypes.capture(drop)
                return@capture false
            }
            if (!positions.hasNext()) return@capture true
            val position = positions.next()
            if (!world.isChunkLoaded(position.x shr 4, position.z shr 4)) host.fail("errors.chunk-unloaded")
            val block = world.getBlockAt(position.x, position.y, position.z)
            if (expectedBefore[position]?.let { it != block.blockData.asString } == true) host.fail("errors.expired")
            if (block.type.isAir || safety.isReplaceable(block)) return@capture false
            if (!safety.isSafeExisting(block)) {
                if (position in expectedBefore) host.fail("errors.expired")
                skippedUnsafe += 1
                return@capture false
            }
            val pooledTool = tools.firstOrNull { tool -> tool.available && isPreferredTool(block, tool.item) }
            if (usesInventory && pooledTool == null && !canBypassTool) host.fail("errors.tool")
            host.ensureMutable(player, block)
            if (usesInventory) {
                val dropTool = pooledTool?.use()?.let(BuilderDeconstructionDrops::withoutSilkTouch)
                if (dropTool == null) toolBypassUsed = true
                pendingDrops = blockDrops(block, dropTool, player).iterator()
            }
            changes += BuilderBlockChange(position, block.blockData.asString, air)
            if (changes.size > maximumChanges) host.fail("errors.selection-too-large")
            false
        }.thenCompose {
            // Storage contains at most 36 tools; native item encoding stays in its own capture steps.
            val remainingTools = tools.iterator()
            val toolUses = mutableListOf<BuilderPooledToolUse>()
            host.capture(current) {
                if (!remainingTools.hasNext()) return@capture true
                remainingTools.next().plannedUse()?.let(toolUses::add)
                false
            }.thenCompose {
                host.compute {
                    val rewards = BuilderItemCodec.aggregateAmounts(refunds)
                    val toolDamage = toolUses.sumOf(BuilderPooledToolUse::damage)
                    val fingerprint = toolUses.takeIf { it.isNotEmpty() }?.let { uses ->
                        BuilderPooledToolCodec.encode(BuilderPooledToolPlan(uses, toolBypassUsed))
                    }
                    BuilderDeconstructionCapture(changes, rewards, fingerprint, toolDamage, toolBypassUsed, skippedUnsafe)
                }
            }
        }.thenCompose { captured ->
            host.onMain {
                requireCurrentBuilderPlanning(current)
                // The inventory has 36 slots; with no costs, a non-fitting reward stops this simulation immediately.
                if (!BuilderInventory.canApply(player, emptyList(), captured.rewards, captured.toolFingerprint, captured.toolDamage)) {
                    host.fail("errors.inventory")
                }
                captured
            }
        }
    }

    private fun pooledTools(player: Player): List<PooledTool> {
        val contents = player.inventory.storageContents
        val heldSlot = player.inventory.heldItemSlot
        return (listOf(heldSlot) + contents.indices.filterNot { it == heldSlot }).mapNotNull { slot ->
            val item = contents[slot]?.takeUnless { it.type.isAir || it.amount <= 0 } ?: return@mapNotNull null
            val maximumDamage = BuilderToolDurability.maximumDamage(item)
            val meta = item.itemMeta as? Damageable ?: return@mapNotNull null
            if (maximumDamage <= 0) return@mapNotNull null
            PooledTool(
                slot = slot,
                item = item.clone().also { it.amount = 1 },
                availableDamage = if (meta.isUnbreakable) {
                    Int.MAX_VALUE
                } else {
                    (maximumDamage - meta.damage - 1).coerceAtLeast(0)
                },
            )
        }
    }

    private data class PooledTool(
        val slot: Int,
        val item: ItemStack,
        val availableDamage: Int,
        var blocksUsed: Int = 0,
    ) {
        val available: Boolean get() = blocksUsed < availableDamage

        fun use(): ItemStack {
            blocksUsed++
            return item
        }

        fun plannedUse(): BuilderPooledToolUse? = if (blocksUsed == 0) null else BuilderPooledToolUse(
            slot = slot,
            itemBase64 = BuilderItemCodec.encodePrototype(item),
            damage = blocksUsed,
        ).validated()
    }
}

internal object BuilderDeconstructionDrops {
    fun withoutSilkTouch(tool: ItemStack): ItemStack = tool.clone().also {
        it.removeEnchantment(Enchantment.SILK_TOUCH)
    }
}

internal object BuilderDeconstructionToolPolicy {
    fun requiresBypass(gameMode: org.bukkit.GameMode, plan: BuilderPlan): Boolean {
        if (!BuilderGameModePolicy.usesInventory(gameMode)) return false
        if (plan.kind == BuilderPlanKind.MOVE) return plan.moveDeconstruction?.toolBypassUsed == true
        if (plan.kind != BuilderPlanKind.DECONSTRUCT) return false
        val fingerprint = plan.toolFingerprintBase64 ?: return true
        return BuilderPooledToolCodec.isPooled(fingerprint) && BuilderPooledToolCodec.decode(fingerprint).bypassUsed
    }
}

/** Exact, non-Fortune construction refunds derived from vanilla Silk Touch semantics. */
internal object BuilderDeconstructionRefunds {
    private val silkTouchTool = ItemStack(Material.NETHERITE_PICKAXE).apply {
        addUnsafeEnchantment(Enchantment.SILK_TOUCH, 1)
    }

    fun fromSilkTouch(block: Block): ItemStack? = exactConstructionItem(
        block.blockData,
        block.getDrops(silkTouchTool),
    )

    fun exactConstructionItem(data: BlockData, drops: Collection<ItemStack>): ItemStack? {
        val expected = BuilderPlacementCost.itemOrNull(data) ?: return null
        val expectedPrototype = expected.clone().also { it.amount = 1 }
        var amount = 0
        drops.forEach { drop ->
            if (drop.type.isAir || drop.amount <= 0) return@forEach
            val prototype = drop.clone().also { it.amount = 1 }
            if (!prototype.isSimilar(expectedPrototype)) return null
            amount = Math.addExact(amount, drop.amount)
        }
        return expected.takeIf { amount == expected.amount }
    }
}
