package ru.arc.hooks.economyshop

import org.bukkit.Material
import org.bukkit.entity.Player
import java.util.Locale

internal interface ShopPurchaseService {
    fun itemQueries(player: Player): List<String>
    fun purchase(player: Player, itemPath: String, amount: Int): ShopPurchaseOutcome
    fun quotePlainMaterial(player: Player, material: Material, amount: Int): ShopMaterialQuote? = null
    fun vaultBalanceMinor(player: Player): Long? = null
    fun formatVaultPrice(minor: Long): String? = null
}

internal data class ShopMaterialQuote(
    val material: Material,
    val itemPath: String,
    val amount: Int,
    val totalPriceMinor: Long,
    val formattedPrice: String,
) {
    init {
        require(material.isItem && !material.isAir)
        require(itemPath.length in 1..512)
        require(amount > 0)
        require(totalPriceMinor > 0L)
        require(formattedPrice.length in 1..256)
    }
}

internal data class ShopMaterialOffer(val itemPath: String, val totalPriceMinor: Long)

internal object ShopMaterialOfferSelector {
    fun cheapest(offers: Iterable<ShopMaterialOffer>): ShopMaterialOffer? = offers
        .filter { it.itemPath.isNotBlank() && it.totalPriceMinor > 0L }
        .minWithOrNull(
            compareBy<ShopMaterialOffer> { it.totalPriceMinor }
                .thenBy { it.itemPath.lowercase(Locale.ROOT) }
                .thenBy { it.itemPath },
        )
}

internal data class ShopPurchaseOutcome(
    val status: ShopPurchaseStatus,
    val itemPath: String,
    val amount: Int,
    val formattedPrice: String? = null,
    val itemName: String? = null,
)

internal enum class ShopPurchaseStatus {
    SUCCESS,
    ITEM_NOT_FOUND,
    ITEM_ERROR,
    NOT_BUYABLE,
    NO_PERMISSIONS,
    REQUIREMENTS_FAILED,
    INSUFFICIENT_FUNDS,
    NO_INVENTORY_SPACE,
    TRANSACTION_CANCELLED,
    BELOW_MINIMUM,
    ABOVE_MAXIMUM,
    OUT_OF_STOCK,
    FAILED,
}
