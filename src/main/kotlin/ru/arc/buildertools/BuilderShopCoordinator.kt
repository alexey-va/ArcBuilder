package ru.arc.buildertools

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.entity.Player
import ru.arc.hooks.HookRegistry
import ru.arc.hooks.economyshop.ShopPurchaseService
import ru.arc.hooks.economyshop.ShopPurchaseStatus
import ru.arc.text.LocalizedMiniMessage
import ru.arc.util.Logging.warn
import java.util.Locale
import java.util.UUID

internal sealed interface BuilderShopConfirmation {
    data object Ready : BuilderShopConfirmation

    data class Rejected(
        val messagePath: String,
        val values: Map<String, Component> = emptyMap(),
    ) : BuilderShopConfirmation
}

/** Owns volatile quotes, player-facing estimates, and pre-construction procurement. */
internal class BuilderShopCoordinator(
    private val config: BuilderToolsConfig,
    private val messages: LocalizedMiniMessage,
    private val serviceProvider: () -> ShopPurchaseService? = { HookRegistry.shopPurchaseService },
    private val materialLabel: (Player, Material) -> Component = BuilderMaterialPresentation::label,
) : AutoCloseable {
    private val estimates = mutableMapOf<UUID, BuilderShopEstimate>()

    fun preview(player: Player, plan: BuilderPlan) {
        val estimate = createEstimate(player, plan)
        if (estimate == null) {
            estimates.remove(player.uniqueId)
            return
        }
        estimates[player.uniqueId] = estimate
        sendEstimate(player, estimate)
    }

    fun clear(playerId: UUID) {
        estimates.remove(playerId)
    }

    fun procure(player: Player, plan: BuilderPlan): BuilderShopConfirmation {
        if (!BuilderShopEstimateRules.supportsAutoBuy(plan.kind)) return rejected("errors.shop-not-supported")
        val missing = BuilderInventory.missingCosts(player, plan.costs)
        if (missing.isEmpty()) return BuilderShopConfirmation.Ready
        if (!config.shopEnabled) return rejected("errors.shop-unavailable")
        val service = serviceProvider() ?: return rejected("errors.shop-unavailable")
        val missingItems = missing.sumOf { it.amount.toLong() }
        if (missing.size > config.shopMaxQuotedMaterials || missingItems > config.shopMaxAutoBuyItems) {
            return rejected(
                "errors.shop-limit",
                "items" to messages.literal(missingItems),
                "price" to priceLabel("—"),
            )
        }

        val preview = estimates[player.uniqueId]
        val current = checkNotNull(createEstimate(player, plan, missing))
        if (current.missingUnavailable.isNotEmpty()) {
            estimates[player.uniqueId] = current
            current.missingUnavailable.distinctBy { it.material }.forEach { line ->
                send(player, "plan.market-unavailable", "material" to materialLabel(player, line.material))
            }
            return rejected("errors.shop-material-unavailable")
        }
        val comparison = if (preview == null) {
            BuilderShopEstimateComparison.REQUEST_CHANGED
        } else {
            BuilderShopEstimateRules.compareMissing(preview, current)
        }
        if (comparison != BuilderShopEstimateComparison.ACCEPT) {
            estimates[player.uniqueId] = current
            sendEstimate(player, current)
            return rejected("errors.shop-estimate-changed")
        }
        if (current.missingTotalMinor > config.shopMaxAutoBuyPriceMinor) {
            return rejected(
                "errors.shop-limit",
                "items" to messages.literal(missingItems),
                "price" to priceLabel(formatTotal(service, current.missingTotalMinor, true)),
            )
        }
        val balance = service.vaultBalanceMinor(player) ?: return rejected("errors.shop-unavailable")
        if (balance < current.missingTotalMinor) {
            return rejected(
                "errors.shop-insufficient-funds",
                "price" to priceLabel(formatTotal(service, current.missingTotalMinor, true)),
                "balance" to priceLabel(formatTotal(service, balance, true)),
            )
        }
        if (
            !BuilderInventory.canApplyAfterReceiving(
                player,
                missing,
                plan.costs,
                plan.rewards,
                plan.toolFingerprintBase64,
                plan.toolDamage,
            )
        ) {
            return rejected("errors.inventory")
        }

        val requests = missing.zip(current.missing).map { (cost, line) ->
            BuilderShopProcurementRequest(cost, checkNotNull(line.quote))
        }
        val procurement = BuilderShopProcurementExecutor(service).execute(player, requests)
        val purchasedItems: Int
        val purchasedPrices: List<String>
        when (procurement) {
            is BuilderShopProcurementResult.Success -> {
                purchasedItems = procurement.purchasedItems
                purchasedPrices = procurement.formattedPrices.map(::plainPrice)
            }
            is BuilderShopProcurementResult.Failed -> {
                refresh(player, plan)
                return partialFailure(
                    player,
                    procurement.material,
                    procurement.purchasedItems,
                    procurement.status,
                )
            }
            is BuilderShopProcurementResult.Ambiguous -> {
                procurement.failure?.let { failure ->
                    warn(
                        "Builder-tools shop purchase outcome is ambiguous for {} {}: type={}",
                        player.name,
                        procurement.material.key,
                        BuilderToolsFailureType.of(failure),
                    )
                }
                refresh(player, plan)
                sendPurchaseStopped(player, procurement.purchasedItems, messages.render("shop.status.ambiguous", locale(player)))
                return rejected(
                    "errors.shop-purchase-ambiguous",
                    "material" to materialLabel(player, procurement.material),
                )
            }
        }
        if (BuilderInventory.missingCosts(player, plan.costs).isNotEmpty()) {
            refresh(player, plan)
            sendPurchaseStopped(
                player,
                purchasedItems,
                messages.render("shop.status.delivery-mismatch", locale(player)),
            )
            return rejected("errors.shop-purchase-ambiguous", "material" to messages.literal("—"))
        }
        send(
            player,
            "shop.purchased",
            "items" to messages.literal(purchasedItems),
            "price" to priceLabel(
                purchasedPrices.joinToString(" + ").ifBlank { formatTotal(service, current.missingTotalMinor, true) },
            ),
        )
        return BuilderShopConfirmation.Ready
    }

    private fun createEstimate(
        player: Player,
        plan: BuilderPlan,
        missing: List<BuilderItemAmount> = BuilderInventory.missingCosts(player, plan.costs),
    ): BuilderShopEstimate? {
        if (!BuilderShopEstimateRules.supportsAutoBuy(plan.kind) || plan.costs.isEmpty()) return null
        val service = serviceProvider().takeIf { config.shopEnabled }
        fun lines(costs: List<BuilderItemAmount>): List<BuilderShopEstimateLine> = costs.mapIndexed { index, cost ->
            val prototype = BuilderItemCodec.decodePrototype(cost.itemBase64)
            val plainMaterial = BuilderInventory.plainMaterial(cost)
            val quote = if (
                index < config.shopMaxQuotedMaterials &&
                service != null &&
                plainMaterial != null &&
                cost.amount <= config.shopMaxAutoBuyItems
            ) {
                runCatching { service.quotePlainMaterial(player, plainMaterial, cost.amount) }.getOrNull()
            } else {
                null
            }
            BuilderShopEstimateLine(prototype.type, cost.amount, quote)
        }
        return BuilderShopEstimate(plan.id, lines(plan.costs), lines(missing))
    }

    private fun sendEstimate(player: Player, estimate: BuilderShopEstimate) {
        val service = serviceProvider().takeIf { config.shopEnabled }
        messages.renderLines(
            "plan.market",
            locale(player),
            mapOf(
                "full_price" to priceLabel(
                    formatTotal(
                        service,
                        estimate.fullTotalMinor,
                        estimate.full.isNotEmpty() && estimate.fullUnavailable.isEmpty(),
                    ),
                ),
                "missing_price" to priceLabel(
                    formatTotal(service, estimate.missingTotalMinor, estimate.missingUnavailable.isEmpty()),
                ),
            ),
        ).forEach(player::sendMessage)
    }

    private fun partialFailure(
        player: Player,
        material: Material,
        purchasedItems: Int,
        status: ShopPurchaseStatus,
    ): BuilderShopConfirmation.Rejected {
        sendPurchaseStopped(player, purchasedItems, purchaseStatusLabel(player, status))
        return rejected("errors.shop-purchase-failed", "material" to materialLabel(player, material))
    }

    private fun sendPurchaseStopped(player: Player, purchasedItems: Int, status: Component) {
        messages.renderLines(
            "shop.purchase-detail",
            locale(player),
            mapOf("status" to status, "purchased" to messages.literal(purchasedItems)),
        ).forEach(player::sendMessage)
        send(player, "shop.world-untouched")
    }

    private fun purchaseStatusLabel(player: Player, status: ShopPurchaseStatus): Component = messages.render(
        "shop.status.${status.name.lowercase(Locale.ROOT).replace('_', '-')}",
        locale(player),
    )

    private fun refresh(player: Player, plan: BuilderPlan) {
        createEstimate(player, plan)?.let { estimates[player.uniqueId] = it }
    }

    private fun formatTotal(service: ShopPurchaseService?, amountMinor: Long, available: Boolean): String {
        if (!available) return "—"
        return service?.formatVaultPrice(amountMinor)?.let(::plainPrice)
            ?: BuilderMoney.decimal(amountMinor).toPlainString()
    }

    private fun plainPrice(raw: String): String =
        PlainTextComponentSerializer.plainText()
            .serialize(LegacyComponentSerializer.legacySection().deserialize(raw))
            .take(MAX_FORMATTED_PRICE_LENGTH)

    private fun priceLabel(formatted: String): Component {
        if (formatted == "—") return messages.literal(formatted)
        return messages.literal(formatted.trim().removeSuffix("💰").trimEnd())
            .append(Component.space())
            .append(Component.text("💰", NamedTextColor.WHITE))
    }

    private fun send(player: Player, path: String, vararg values: Pair<String, Component>) {
        player.sendMessage(
            messages.render(
                path,
                locale(player),
                values.toMap(),
            ),
        )
    }

    private fun rejected(path: String, vararg values: Pair<String, Component>): BuilderShopConfirmation.Rejected =
        BuilderShopConfirmation.Rejected(path, values.toMap())

    private fun locale(player: Player): String = player.locale().toLanguageTag()

    override fun close() {
        estimates.clear()
    }

    private companion object {
        const val MAX_FORMATTED_PRICE_LENGTH = 80
    }
}
