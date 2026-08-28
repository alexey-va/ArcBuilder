package ru.arc.buildertools

import com.sk89q.worldedit.bukkit.BukkitAdapter
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.autobuild.Building
import ru.arc.autobuild.BuildBookMaterialRequirement
import ru.arc.autobuild.BuildBookMaterialRequirements
import ru.arc.hooks.HookRegistry
import ru.arc.hooks.economyshop.ShopMaterialQuote
import ru.arc.hooks.economyshop.ShopPurchaseService

internal data class BuilderBookPriceQuote(
    val cost: BuilderBookCost,
    val materialTypes: Int,
    val materialItems: Int,
    val includedLines: List<ShopMaterialQuote>,
    val playerMaterials: List<BuildBookMaterialRequirement>,
)

internal sealed interface BuilderBookQuoteResult {
    data class Ready(val quote: BuilderBookPriceQuote) : BuilderBookQuoteResult
    data object ShopUnavailable : BuilderBookQuoteResult
    data object LimitExceeded : BuilderBookQuoteResult
}

internal class BuilderBookPricing(
    private val config: BuilderToolsConfig,
    private val serviceProvider: () -> ShopPurchaseService? = { HookRegistry.shopPurchaseService },
) {
    fun quote(player: Player, clipboard: BuilderClipboard): BuilderBookQuoteResult {
        val items = clipboard.validated(config.maxClipboardBlocks).blocks.mapNotNull { block ->
            Bukkit.createBlockData(block.blockData).takeUnless { it.material.isAir }?.let(BuilderPlacementCost::itemOrNull)
        }
        return quoteItems(player, items)
    }

    fun quote(player: Player, building: Building): BuilderBookQuoteResult {
        val clipboard = runCatching { building.clipboard }.getOrElse { return BuilderBookQuoteResult.LimitExceeded }
        if (clipboard.region.volume !in 1..config.maxScanVolume) return BuilderBookQuoteResult.LimitExceeded
        val items = clipboard.region.asSequence().mapNotNull { position ->
            BukkitAdapter.adapt(clipboard.getFullBlock(position)).takeUnless { it.material.isAir }?.let(BuilderPlacementCost::itemOrNull)
        }.take(config.maxClipboardBlocks + 1).toList()
        if (items.size > config.maxClipboardBlocks) return BuilderBookQuoteResult.LimitExceeded
        return quoteItems(player, items)
    }

    private fun quoteItems(player: Player, items: List<ItemStack>): BuilderBookQuoteResult {
        if (!config.bookContractsEnabled || !config.shopEnabled) return BuilderBookQuoteResult.ShopUnavailable
        val service = serviceProvider() ?: return BuilderBookQuoteResult.ShopUnavailable
        val costs = BuilderItemCodec.aggregate(items)
        if (
            costs.isEmpty() || costs.size > config.shopMaxQuotedMaterials ||
            costs.sumOf { it.amount.toLong() } > config.shopMaxAutoBuyItems
        ) {
            return BuilderBookQuoteResult.LimitExceeded
        }
        val playerMaterials = mutableListOf<BuildBookMaterialRequirement>()
        val lines = mutableListOf<ShopMaterialQuote>()
        costs.forEach { cost ->
            val material = BuilderInventory.plainMaterial(cost)
            if (material == null) {
                playerMaterials += BuildBookMaterialRequirement(
                    BuilderItemCodec.decodePrototype(cost.itemBase64).type,
                    cost.amount,
                )
                return@forEach
            }
            val quote = try {
                service.quotePlainMaterial(player, material, cost.amount)
            } catch (_: Exception) {
                return BuilderBookQuoteResult.ShopUnavailable
            }
            if (quote == null) {
                playerMaterials += BuildBookMaterialRequirement(material, cost.amount)
            } else {
                lines += quote
            }
        }
        val required = BuildBookMaterialRequirements.normalize(playerMaterials)
        val calculated = runCatching {
            BuilderBookCostRules.calculate(
                lines.map(ShopMaterialQuote::totalPriceMinor),
                config.bookConstructionMarkupBasisPoints,
            )
        }.getOrElse { return BuilderBookQuoteResult.LimitExceeded }
        if (calculated.issuePriceMinor > config.bookMaxIssuePriceMinor) return BuilderBookQuoteResult.LimitExceeded
        return BuilderBookQuoteResult.Ready(
            BuilderBookPriceQuote(
                cost = calculated,
                materialTypes = costs.size,
                materialItems = costs.sumOf(BuilderItemAmount::amount),
                includedLines = lines,
                playerMaterials = required,
            ),
        )
    }
}
