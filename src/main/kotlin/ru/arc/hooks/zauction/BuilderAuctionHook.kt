package ru.arc.hooks.zauction

import fr.maxlego08.zauctionhouse.api.AuctionManager
import fr.maxlego08.zauctionhouse.api.AuctionPlugin
import fr.maxlego08.zauctionhouse.api.event.events.RuleLoadEvent
import fr.maxlego08.zauctionhouse.api.event.events.remove.AuctionRemoveExpiredItemEvent
import fr.maxlego08.zauctionhouse.api.event.events.remove.AuctionRemoveListedItemEvent
import fr.maxlego08.zauctionhouse.api.event.events.remove.AuctionRemovePurchasedItemEvent
import fr.maxlego08.zauctionhouse.api.event.events.sell.AuctionPreSellEvent
import fr.maxlego08.zauctionhouse.api.item.Item
import fr.maxlego08.zauctionhouse.api.item.ItemType
import fr.maxlego08.zauctionhouse.api.item.StorageType
import fr.maxlego08.zauctionhouse.api.item.items.AuctionItem
import fr.maxlego08.zauctionhouse.api.rules.Rule
import fr.maxlego08.zauctionhouse.api.services.AuctionSellService
import fr.maxlego08.zauctionhouse.api.services.result.SellFailReason
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemStack
import ru.arc.core.Tasks
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.buildertools.BuilderBookAuctionFailure
import ru.arc.buildertools.BuilderBookAuctionItemGuard
import ru.arc.buildertools.BuilderBookAuctionListingResult
import ru.arc.buildertools.BuilderBookAuctionPort
import ru.arc.buildertools.BuilderBookAuctionToken
import ru.arc.buildertools.BuilderBookAuctionTokenCodec
import ru.arc.buildertools.BuilderToolsModule
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import java.math.BigDecimal
import java.util.concurrent.CompletableFuture

/** Narrow zAuctionHouse boundary owned by ArcBuilder. */
internal class BuilderAuctionHook(
    private val plugin: JavaPlugin,
) : BuilderBookAuctionPort, Listener, AutoCloseable {
    private val auctionPlugin: AuctionPlugin?
    private val auctionManager: AuctionManager?
    private var deliveryHandler: ((Player, ItemStack) -> Unit)? = null
    private val authorizedTokens = mutableSetOf<BuilderBookAuctionToken>()
    private val builderBookRule = Rule { context ->
        val item = context.itemStack
        BuilderBookAuctionItemGuard.containsPlayerCreatedBook(item) && !isAuthorized(item)
    }

    init {
        val installed = Bukkit.getPluginManager().getPlugin("zAuctionHouse")
            ?: Bukkit.getPluginManager().getPlugin("zAuctionHouseV3")
        auctionPlugin = installed as? AuctionPlugin
        auctionManager = auctionPlugin?.auctionManager
            ?: plugin.server.servicesManager.getRegistration(AuctionManager::class.java)?.provider
        plugin.server.pluginManager.registerEvents(this, plugin)
        ensureBuilderBookRule()
        info("ArcBuilder zAuctionHouse boundary initialized")
    }

    override fun submit(
        player: Player,
        price: BigDecimal,
        tokenizedItem: ItemStack,
    ): CompletableFuture<BuilderBookAuctionListingResult> {
        check(Bukkit.isPrimaryThread()) { "Builder-book auction submission must run on the primary thread" }
        val api = auctionPlugin ?: return unavailable()
        val manager = auctionManager ?: return unavailable()
        val token = BuilderBookAuctionTokenCodec.read(tokenizedItem)
            ?: return CompletableFuture.completedFuture(
                BuilderBookAuctionListingResult.Failed(BuilderBookAuctionFailure.REJECTED),
            )
        val economy = api.economyManager.getDefaultEconomy(ItemType.AUCTION) ?: return unavailable()
        val expirationSeconds = api.configuration.sellExpiration.getExpiration(player)
        val expiredAt = if (expirationSeconds > 0L) {
            Math.addExact(System.currentTimeMillis(), Math.multiplyExact(expirationSeconds, 1_000L))
        } else {
            0L
        }
        val future = try {
            authorizedTokens += token
            manager.sellService.sellAuctionItems(
                player,
                price,
                expiredAt,
                mapOf(AuctionSellService.MAIN_HAND_SLOT to tokenizedItem.clone()),
                economy,
            )
        } finally {
            authorizedTokens -= token
        }
        return future.handle { result, failure ->
            when {
                failure != null || result == null -> ambiguous()
                result.success() -> result.auctionItem()?.let {
                    BuilderBookAuctionListingResult.Listed(it.id.toString())
                } ?: ambiguous()
                result.failReason() == SellFailReason.DATABASE_ERROR -> ambiguous()
                else -> BuilderBookAuctionListingResult.Failed(BuilderBookAuctionFailure.REJECTED)
            }
        }
    }

    override fun contains(token: BuilderBookAuctionToken): Boolean {
        check(Bukkit.isPrimaryThread()) { "Builder-book auction lookup must run on the primary thread" }
        val manager = auctionManager ?: return false
        return listOf(StorageType.LISTED, StorageType.PURCHASED, StorageType.EXPIRED).any { storage ->
            manager.getItems(storage).any { item ->
                (item as? AuctionItem)?.itemStacks?.any { stack ->
                    BuilderBookAuctionTokenCodec.read(stack) == token
                } == true
            }
        }
    }

    override fun setDeliveryHandler(handler: ((Player, ItemStack) -> Unit)?) {
        check(Bukkit.isPrimaryThread()) { "Auction delivery handler must change on the primary thread" }
        deliveryHandler = handler
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPreSell(event: AuctionPreSellEvent) {
        if (!isBlocked(event.itemStack)) return
        event.isCancelled = true
        BuilderToolsModule.rejectUnsafeAuctionSale(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRulesLoaded(@Suppress("UNUSED_PARAMETER") event: RuleLoadEvent) {
        Tasks.scheduler.runSync(Runnable(::ensureBuilderBookRule))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onListedRemoved(event: AuctionRemoveListedItemEvent) = deliver(event.player, event.item)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onExpiredRemoved(event: AuctionRemoveExpiredItemEvent) = deliver(event.player, event.item)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPurchasedRemoved(event: AuctionRemovePurchasedItemEvent) = deliver(event.player, event.item)

    override fun close() {
        deliveryHandler = null
        authorizedTokens.clear()
        HandlerList.unregisterAll(this)
    }

    private fun ensureBuilderBookRule() {
        val manager = auctionPlugin?.itemRuleManager ?: return
        if (manager.blacklistRules().rules().none { it === builderBookRule }) {
            manager.addBlacklistRule(builderBookRule)
        }
        if (!manager.isBlacklistEnabled) {
            manager.isBlacklistEnabled = true
            warn("Enabled zAuctionHouse blacklist to protect registered ArcBuilder books")
        }
    }

    private fun isBlocked(item: ItemStack): Boolean =
        BuilderBookAuctionItemGuard.containsPlayerCreatedBook(item) && !isAuthorized(item)

    private fun isAuthorized(item: ItemStack): Boolean =
        BuilderBookAuctionTokenCodec.read(item)?.let(authorizedTokens::contains) == true

    private fun deliver(player: Player, item: Item) {
        val handler = deliveryHandler ?: return
        (item as? AuctionItem)?.itemStacks?.forEach { stack ->
            if (BuilderBookAuctionTokenCodec.read(stack) != null) handler(player, stack)
        }
    }

    private fun unavailable() = CompletableFuture.completedFuture<BuilderBookAuctionListingResult>(
        BuilderBookAuctionListingResult.Failed(BuilderBookAuctionFailure.UNAVAILABLE),
    )

    private fun ambiguous() =
        BuilderBookAuctionListingResult.Failed(BuilderBookAuctionFailure.AMBIGUOUS)
}
