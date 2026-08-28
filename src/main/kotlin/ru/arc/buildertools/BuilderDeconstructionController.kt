package ru.arc.buildertools

import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

/** Generic protection and transaction boundary consumed by deconstruction planning. */
internal interface BuilderDeconstructionHost {
    fun ensurePermission(player: Player)
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
 * Survival accepts only the exact construction item produced by canonical
 * Silk Touch semantics. This preserves the old builder-oriented behavior
 * without allowing the held tool's Fortune or other chance-based drops into
 * a previewed transaction.
 */
internal class BuilderDeconstructionController(
    private val safety: BuilderBlockSafety,
    private val maximumChanges: Int,
    private val host: BuilderDeconstructionHost,
    private val isPreferredTool: (Block, ItemStack) -> Boolean = { block, tool -> block.isPreferredTool(tool) },
    private val constructionRefund: (Block) -> ItemStack? = BuilderDeconstructionRefunds::fromSilkTouch,
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
        val tool = if (usesInventory) requireTool(player.inventory.itemInMainHand) else null
        val changes = mutableListOf<BuilderBlockChange>()
        val refunds = mutableListOf<ItemStack>()
        val air = Material.AIR.createBlockData().asString
        var skippedUnsafe = 0

        selection.positionsTopDown().forEach { position ->
            val block = world.getBlockAt(position.x, position.y, position.z)
            if (block.type.isAir || safety.isReplaceable(block)) return@forEach
            if (!safety.isSafeExisting(block)) {
                skippedUnsafe += 1
                return@forEach
            }
            if (tool != null && !isPreferredTool(block, tool)) host.fail("errors.tool")
            host.ensureMutable(player, block)
            if (tool != null) constructionRefund(block)?.let(refunds::add)
            changes += BuilderBlockChange(position, block.blockData.asString, air)
            if (changes.size > maximumChanges) host.fail("errors.selection-too-large")
        }

        if (changes.isEmpty()) host.fail("errors.nothing-to-change")
        val fingerprint = tool?.let(BuilderItemCodec::encodePrototype)
        val rewards = BuilderItemCodec.aggregate(refunds)
        val toolDamage = if (tool == null) 0 else changes.size
        if (!BuilderInventory.canApply(player, emptyList(), rewards, fingerprint, toolDamage)) {
            host.fail("errors.inventory")
        }
        return host.createPlan(player, changes, rewards, fingerprint, toolDamage, skippedUnsafe)
    }

    private fun requireTool(held: ItemStack): ItemStack {
        if (held.type.isAir || held.type.maxDurability <= 0) host.fail("errors.tool")
        return held.clone()
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
