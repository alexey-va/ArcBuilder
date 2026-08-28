package ru.arc.hooks.economyshop

import me.gypopo.economyshopgui.api.EconomyShopGUIHook
import me.gypopo.economyshopgui.objects.ShopItem
import me.gypopo.economyshopgui.util.EcoType
import me.gypopo.economyshopgui.util.EconomyType
import me.gypopo.economyshopgui.util.Transaction
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.buildertools.BuilderMoney

internal class EconomyShopGuiPurchaseService(
    private val translateItem: (ItemStack?) -> String,
) : ShopPurchaseService {
    override fun itemQueries(player: Player): List<String> = allItems().asSequence()
        .filter { !it.hasItemError() && !it.isHidden && !it.isDisplayItem && it.isBuyAble }
        .filter { EconomyShopGUIHook.hasPermissions(it, player) }
        .map(::descriptor)
        .toList()
        .let(ShopItemQueryIndex::preferredQueries)

    override fun purchase(player: Player, itemPath: String, amount: Int): ShopPurchaseOutcome {
        val item = resolveItem(itemPath)
            ?: return ShopPurchaseOutcome(ShopPurchaseStatus.ITEM_NOT_FOUND, itemPath, amount)
        val itemName = runCatching { translateItem(item.shopItem) }.getOrNull()?.takeIf(String::isNotBlank)
        if (item.isHidden || item.isDisplayItem || !item.isBuyAble) {
            return ShopPurchaseOutcome(ShopPurchaseStatus.NOT_BUYABLE, item.itemPath, amount, itemName = itemName)
        }
        val result = EconomyShopGUIHook.purchaseItem(player, item, amount, true, true, false, false)
        return ShopPurchaseOutcome(
            result.result.toPurchaseStatus(),
            item.itemPath,
            result.amount.takeIf { it > 0 } ?: amount,
            formatPrices(result.prices),
            itemName,
        )
    }

    override fun quotePlainMaterial(player: Player, material: Material, amount: Int): ShopMaterialQuote? {
        if (!material.isItem || material.isAir || amount <= 0) return null
        val candidates = allItems().mapNotNull { item ->
            runCatching { quoteCandidate(player, material, amount, item) }.getOrNull()
        }
        val selected = ShopMaterialOfferSelector.cheapest(candidates.map { it.first }) ?: return null
        return candidates.first { it.first == selected }.second
    }

    override fun vaultBalanceMinor(player: Player): Long? = vaultProvider()
        ?.let { provider -> runCatching { provider.getBalance(player) }.getOrNull() }
        ?.let { raw -> runCatching { BuilderMoney.balanceFromProvider(raw) }.getOrNull() }

    override fun formatVaultPrice(minor: Long): String? = vaultProvider()
        ?.let { provider -> runCatching { provider.formatPrice(BuilderMoney.toProviderDouble(minor)) }.getOrNull() }
        ?.takeIf(String::isNotBlank)

    private fun quoteCandidate(
        player: Player,
        material: Material,
        amount: Int,
        item: ShopItem,
    ): Pair<ShopMaterialOffer, ShopMaterialQuote>? {
        if (
            item.hasItemError() || item.isHidden || item.isDisplayItem || !item.isBuyAble ||
            item.isBuyCommand || item.isABuyPricing || item.ecoType.type != EconomyType.VAULT ||
            item.isMinBuy(amount) || item.isMaxBuy(amount)
        ) return null
        if (!EconomyShopGUIHook.hasPermissions(item, player)) return null
        if (!runCatching { item.meetsRequirements(player, true) }.getOrDefault(false)) return null
        if (item.limitedStockMode > 0) {
            val stock = runCatching { EconomyShopGUIHook.getItemStock(item, player.uniqueId) }.getOrNull() ?: return null
            if (stock < amount) return null
        }
        val given = runCatching { item.itemToGive }.getOrNull() ?: return null
        if (given.type != material || given.amount != 1 || !given.isSimilar(ItemStack(material))) return null
        val rawTotal = runCatching { item.getBuyPrice(player, amount) }.getOrNull() ?: return null
        val totalMinor = runCatching { BuilderMoney.priceFromProvider(rawTotal) }.getOrNull() ?: return null
        val formatted = runCatching { EconomyShopGUIHook.getEcon(item.ecoType)?.formatPrice(rawTotal) }
            .getOrNull()?.takeIf(String::isNotBlank) ?: return null
        val offer = ShopMaterialOffer(item.itemPath, totalMinor)
        return offer to ShopMaterialQuote(material, item.itemPath, amount, totalMinor, formatted)
    }

    private fun vaultProvider() = allItems().asSequence()
        .mapNotNull { runCatching { it.ecoType }.getOrNull() }
        .firstOrNull { it.type == EconomyType.VAULT }
        ?.let(EconomyShopGUIHook::getEcon)

    private fun resolveItem(itemPath: String): ShopItem? {
        EconomyShopGUIHook.getShopItem(itemPath)?.let { return it }
        val items = allItems()
        val path = ShopItemQueryIndex.resolve(itemPath, items.map(::descriptor)) ?: return null
        return items.firstOrNull { it.itemPath.equals(path, ignoreCase = true) }
    }

    private fun allItems(): List<ShopItem> = EconomyShopGUIHook.getSections().values.flatMap { it.shopItems }
    private fun descriptor(item: ShopItem) = ShopItemDescriptor(
        item.itemPath,
        item.itemPath.substringBefore('.'),
        item.itemPath.substringAfter('.', item.itemPath),
        runCatching { item.shopItem.type.name }.getOrNull(),
    )

    private fun formatPrices(prices: Map<EcoType, Double>?): String? = prices?.entries
        ?.sortedBy { it.key.toString() }
        ?.joinToString(" + ") { (type, price) -> EconomyShopGUIHook.getEcon(type)?.formatPrice(price) ?: price.toString() }
        ?.takeIf(String::isNotBlank)

    private fun Transaction.Result.toPurchaseStatus() = when (this) {
        Transaction.Result.SUCCESS, Transaction.Result.SUCCESS_COMMANDS_EXECUTED -> ShopPurchaseStatus.SUCCESS
        Transaction.Result.ITEM_ERROR -> ShopPurchaseStatus.ITEM_ERROR
        Transaction.Result.DISPLAY_ITEM, Transaction.Result.NEGATIVE_ITEM_PRICE, Transaction.Result.NO_ITEMS_FOUND -> ShopPurchaseStatus.NOT_BUYABLE
        Transaction.Result.NO_PERMISSIONS -> ShopPurchaseStatus.NO_PERMISSIONS
        Transaction.Result.REQUIREMENTS_FAILED -> ShopPurchaseStatus.REQUIREMENTS_FAILED
        Transaction.Result.INSUFFICIENT_FUNDS -> ShopPurchaseStatus.INSUFFICIENT_FUNDS
        Transaction.Result.NO_INVENTORY_SPACE -> ShopPurchaseStatus.NO_INVENTORY_SPACE
        Transaction.Result.TRANSACTION_CANCELLED -> ShopPurchaseStatus.TRANSACTION_CANCELLED
        Transaction.Result.NOT_ENOUGH_ITEMS -> ShopPurchaseStatus.BELOW_MINIMUM
        Transaction.Result.TO_MANY_ITEMS -> ShopPurchaseStatus.ABOVE_MAXIMUM
        Transaction.Result.NO_ITEM_STOCK_LEFT -> ShopPurchaseStatus.OUT_OF_STOCK
        Transaction.Result.REACHED_SELL_LIMIT, Transaction.Result.CANT_STORE_PAYMENT -> ShopPurchaseStatus.FAILED
    }
}

internal data class ShopItemDescriptor(
    val canonicalPath: String,
    val section: String,
    val relativeLocation: String,
    val material: String?,
)

internal object ShopItemQueryIndex {
    fun preferredQueries(items: List<ShopItemDescriptor>): List<String> = items
        .groupBy { it.section.lowercase() }
        .values
        .flatMap { sectionItems ->
            sectionItems.map { item ->
                val short = item.relativeLocation.substringAfterLast('.')
                val selector = listOfNotNull(item.material, short, item.relativeLocation)
                    .distinctBy(String::lowercase)
                    .firstOrNull { candidate -> sectionItems.count { matches(it, candidate) } == 1 }
                    ?: item.relativeLocation
                "${item.section}.$selector"
            }
        }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)

    fun resolve(query: String, items: List<ShopItemDescriptor>): String? {
        items.singleOrNull { it.canonicalPath.equals(query, true) }?.let { return it.canonicalPath }
        val split = query.indexOf('.')
        if (split <= 0 || split == query.lastIndex) return null
        val section = query.substring(0, split)
        val selector = query.substring(split + 1)
        return items.filter { it.section.equals(section, true) && matches(it, selector) }
            .singleOrNull()?.canonicalPath
    }

    private fun matches(item: ShopItemDescriptor, selector: String) =
        item.relativeLocation.equals(selector, true) ||
            item.relativeLocation.substringAfterLast('.').equals(selector, true) ||
            item.material?.equals(selector, true) == true
}
