package ru.arc.buildertools

import org.bukkit.Material
import ru.arc.hooks.economyshop.ShopMaterialQuote
import java.util.UUID

internal data class BuilderShopEstimateLine(
    val material: Material,
    val amount: Int,
    val quote: ShopMaterialQuote?,
) {
    init {
        require(material.isItem && !material.isAir) { "Builder shop estimate requires an item material" }
        require(amount > 0) { "Builder shop estimate amount must be positive" }
        require(quote == null || quote.material == material && quote.amount == amount) {
            "Builder shop estimate quote does not match its material request"
        }
    }
}

internal data class BuilderShopEstimate(
    val planId: UUID,
    val full: List<BuilderShopEstimateLine>,
    val missing: List<BuilderShopEstimateLine>,
) {
    val fullTotalMinor: Long get() = full.mapNotNull { it.quote?.totalPriceMinor }.fold(0L, Math::addExact)
    val missingTotalMinor: Long get() = missing.mapNotNull { it.quote?.totalPriceMinor }.fold(0L, Math::addExact)
    val fullUnavailable: List<BuilderShopEstimateLine> get() = full.filter { it.quote == null }
    val missingUnavailable: List<BuilderShopEstimateLine> get() = missing.filter { it.quote == null }
}

internal enum class BuilderShopEstimateComparison {
    ACCEPT,
    REQUEST_CHANGED,
}

internal object BuilderShopEstimateRules {
    private val BUYABLE_KINDS = setOf(
        BuilderPlanKind.FILL,
        BuilderPlanKind.REPLACE,
        BuilderPlanKind.PASTE,
        BuilderPlanKind.CROWN,
    )

    fun supportsAutoBuy(kind: BuilderPlanKind): Boolean = kind in BUYABLE_KINDS

    fun compareMissing(
        preview: BuilderShopEstimate,
        current: BuilderShopEstimate,
    ): BuilderShopEstimateComparison {
        if (preview.planId != current.planId) return BuilderShopEstimateComparison.REQUEST_CHANGED
        val previewRequests = preview.missing.map { it.material to it.amount }.sortedBy { it.first.key.toString() }
        val currentRequests = current.missing.map { it.material to it.amount }.sortedBy { it.first.key.toString() }
        if (previewRequests != currentRequests || preview.missingUnavailable.isNotEmpty() || current.missingUnavailable.isNotEmpty()) {
            return BuilderShopEstimateComparison.REQUEST_CHANGED
        }
        return BuilderShopEstimateComparison.ACCEPT
    }
}
