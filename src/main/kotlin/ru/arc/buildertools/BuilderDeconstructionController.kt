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

/** Generic protection and transaction boundary consumed by deconstruction planning. */
internal interface BuilderDeconstructionHost {
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
    fun fail(path: String): Nothing
}

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

    fun plan(player: Player): BuilderPlan {
        host.ensurePermission(player)
        val selection = host.requiredSelection(player)
        val world = host.world(selection.worldId)
        val usesInventory = BuilderGameModePolicy.usesInventory(player.gameMode)
        val canBypassTool = usesInventory && host.canDeconstructWithoutTool(player)
        val usesTools = usesInventory && BuilderToolDurability.maximumDamage(player.inventory.itemInMainHand) > 0
        if (usesInventory && !usesTools && !canBypassTool) host.fail("errors.tool")
        val tools = if (usesTools) pooledTools(player) else emptyList()
        val changes = mutableListOf<BuilderBlockChange>()
        val refunds = mutableListOf<ItemStack>()
        val air = Material.AIR.createBlockData().asString
        var skippedUnsafe = 0
        var toolBypassUsed = usesInventory && !usesTools

        selection.positionsTopDown().forEach { position ->
            val block = world.getBlockAt(position.x, position.y, position.z)
            if (block.type.isAir || safety.isReplaceable(block)) return@forEach
            if (!safety.isSafeExisting(block)) {
                skippedUnsafe += 1
                return@forEach
            }
            val pooledTool = tools.firstOrNull { tool -> tool.available && isPreferredTool(block, tool.item) }
            if (usesInventory && pooledTool == null && !canBypassTool) host.fail("errors.tool")
            host.ensureMutable(player, block)
            if (usesInventory) {
                val dropTool = pooledTool?.use()?.let(BuilderDeconstructionDrops::withoutSilkTouch)
                if (dropTool == null) toolBypassUsed = true
                refunds += blockDrops(block, dropTool, player)
            }
            changes += BuilderBlockChange(position, block.blockData.asString, air)
            if (changes.size > maximumChanges) host.fail("errors.selection-too-large")
        }

        if (changes.isEmpty()) host.fail("errors.nothing-to-change")
        val rewards = BuilderItemCodec.aggregate(refunds)
        val toolUses = tools.mapNotNull(PooledTool::plannedUse)
        val toolDamage = toolUses.sumOf(BuilderPooledToolUse::damage)
        val fingerprint = toolUses.takeIf { it.isNotEmpty() }?.let { uses ->
            BuilderPooledToolCodec.encode(BuilderPooledToolPlan(uses, toolBypassUsed))
        }
        if (!BuilderInventory.canApply(player, emptyList(), rewards, fingerprint, toolDamage)) {
            host.fail("errors.inventory")
        }
        return host.createPlan(player, changes, rewards, fingerprint, toolDamage, skippedUnsafe)
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
        if (plan.kind != BuilderPlanKind.DECONSTRUCT || !BuilderGameModePolicy.usesInventory(gameMode)) return false
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
