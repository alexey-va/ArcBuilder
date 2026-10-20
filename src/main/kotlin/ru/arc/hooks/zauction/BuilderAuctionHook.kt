package ru.arc.hooks.zauction

import fr.maxlego08.zauctionhouse.api.AuctionManager
import fr.maxlego08.zauctionhouse.api.AuctionPlugin
import fr.maxlego08.zauctionhouse.api.event.events.AuctionExpireEvent
import fr.maxlego08.zauctionhouse.api.event.events.RuleLoadEvent
import fr.maxlego08.zauctionhouse.api.event.events.remove.AuctionRemoveExpiredItemEvent
import fr.maxlego08.zauctionhouse.api.event.events.remove.AuctionRemoveListedItemEvent
import fr.maxlego08.zauctionhouse.api.event.events.remove.AuctionRemovePurchasedItemEvent
import fr.maxlego08.zauctionhouse.api.event.events.sell.AuctionPreSellEvent
import fr.maxlego08.zauctionhouse.api.item.Item
import fr.maxlego08.zauctionhouse.api.item.ItemStatus
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
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.Tasks
import ru.arc.core.whenCompleteSync
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
import java.util.concurrent.TimeUnit

/** Narrow zAuctionHouse boundary owned by ArcBuilder. */
internal class BuilderAuctionHook(
    private val plugin: JavaPlugin,
    private val lookupItems: ((List<Int>) -> CompletableFuture<List<Item>>)? = null,
) : BuilderBookAuctionPort, Listener, AutoCloseable {
    private val auctionPlugin: AuctionPlugin?
    private val auctionManager: AuctionManager?
    private var deliveryHandler: ((Player, ItemStack) -> Unit)? = null
    private var closureHandler: ((BuilderBookAuctionToken) -> Unit)? = null
    private val closureTasks = LifecycleTaskScope()
    private var closureWork = BudgetedTaskQueue(closureTasks)
    private val pendingClosures = mutableMapOf<Int, Set<BuilderBookAuctionToken>>()
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
        val manager = checkNotNull(auctionManager) { "zAuctionHouse auction manager is unavailable" }
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

    override fun setClosureHandler(handler: ((BuilderBookAuctionToken) -> Unit)?) {
        check(Bukkit.isPrimaryThread()) { "Auction closure handler must change on the primary thread" }
        closureWork.close()
        closureTasks.restart()
        closureWork = BudgetedTaskQueue(closureTasks)
        pendingClosures.clear()
        closureHandler = handler
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
    fun onExpired(event: AuctionExpireEvent) {
        check(Bukkit.isPrimaryThread()) { "Auction expiry capture must run on the primary thread" }
        if (closureHandler == null || event.type !in setOf(StorageType.EXPIRED, StorageType.PURCHASED)) return
        val lifecycle = closureTasks.token()
        readClosureTokens(event.items, lifecycle, deletedOnly = false).whenCompleteSync(closureTasks, lifecycle) { captured, failure ->
            if (failure != null) {
                warn("Builder-book auction closure capture failed", failure)
                return@whenCompleteSync
            }
            val entries = captured.orEmpty().entries.iterator()
            var batch = mutableMapOf<Int, Set<BuilderBookAuctionToken>>()
            closureWork.submit({ closureTasks.isCurrent(lifecycle) }) {
                if (entries.hasNext()) {
                    val (id, tokens) = entries.next()
                    if (pendingClosures.putIfAbsent(id, tokens) == null) batch[id] = tokens
                }
                if (batch.size == 32 || !entries.hasNext()) {
                    if (batch.isNotEmpty()) verifyClosure(batch, lifecycle, 0)
                    batch = mutableMapOf()
                }
                !entries.hasNext()
            }.whenCompleteSync(closureTasks, lifecycle) { _, batchFailure ->
                if (batchFailure != null) warn("Builder-book auction closure batching failed", batchFailure)
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onListedRemoved(event: AuctionRemoveListedItemEvent) = deliver(event.player, event.item)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onExpiredRemoved(event: AuctionRemoveExpiredItemEvent) = deliver(event.player, event.item)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPurchasedRemoved(event: AuctionRemovePurchasedItemEvent) = deliver(event.player, event.item)

    override fun close() {
        deliveryHandler = null
        closureHandler = null
        closureWork.close()
        closureTasks.close()
        pendingClosures.clear()
        authorizedTokens.clear()
        HandlerList.unregisterAll(this)
    }

    /** Native expiry events precede SQL; only an exact durable DELETED item proves closure. */
    private fun verifyClosure(
        expected: Map<Int, Set<BuilderBookAuctionToken>>,
        lifecycle: LifecycleTaskScope.Token,
        attempt: Int,
    ) {
        closureTasks.runLater(lifecycle, if (attempt == 0) 1L else 20L) {
            val query: CompletableFuture<List<Item>> = try {
                val ids = expected.keys.toList()
                lookupItems?.invoke(ids) ?: checkNotNull(auctionPlugin).let { api ->
                    // The native history service uses this same synchronous read on its storage executor.
                    CompletableFuture.supplyAsync({ api.storageManager.selectItems(ids) }, api.executorService)
                }
            } catch (failure: RuntimeException) {
                CompletableFuture.failedFuture(failure)
            }
            query.orTimeout(5L, TimeUnit.SECONDS).whenCompleteSync(closureTasks, lifecycle) { items, failure ->
                readClosureTokens(items.orEmpty(), lifecycle, deletedOnly = true).whenCompleteSync(closureTasks, lifecycle) { deleted, readFailure ->
                    val tokens = expected.asSequence().flatMap { (id, tokens) -> tokens.asSequence().map { id to it } }.iterator()
                    val remaining = mutableMapOf<Int, MutableSet<BuilderBookAuctionToken>>()
                    closureWork.submit({ closureTasks.isCurrent(lifecycle) }) {
                        if (tokens.hasNext()) {
                            val (id, token) = tokens.next()
                            if (failure == null && readFailure == null && token in deleted?.get(id).orEmpty()) {
                                closureHandler?.invoke(token)
                            } else {
                                remaining.getOrPut(id, ::mutableSetOf) += token
                            }
                        }
                        !tokens.hasNext()
                    }.whenCompleteSync(closureTasks, lifecycle) { _, dispatchFailure ->
                        expected.keys.filterNot(remaining::containsKey).forEach(pendingClosures::remove)
                        if (dispatchFailure != null) {
                            expected.keys.forEach(pendingClosures::remove)
                            warn("Builder-book auction closure dispatch failed: listings=${expected.keys}", dispatchFailure)
                        } else if (remaining.isNotEmpty()) {
                            if (attempt < 2) verifyClosure(remaining, lifecycle, attempt + 1)
                            else {
                                remaining.keys.forEach(pendingClosures::remove)
                                val evidence = remaining.mapValues { (_, tokens) -> "count=${tokens.size} sample=${tokens.take(4)}" }
                                warn("Builder-book auction closure remains unconfirmed: listings=$evidence reason=${failure ?: readFailure ?: "no exact durable DELETED item"}", failure ?: readFailure)
                            }
                        }
                    }
                }
            }
        }
    }

    /** Each queue step reads one native item header or one stack, sharing the hook's tick budget. */
    private fun readClosureTokens(
        items: List<Item>,
        lifecycle: LifecycleTaskScope.Token,
        deletedOnly: Boolean,
    ): CompletableFuture<Map<Int, Set<BuilderBookAuctionToken>>> {
        val captured = mutableMapOf<Int, Set<BuilderBookAuctionToken>>()
        var index = 0
        var id = 0
        var stacks: Iterator<ItemStack>? = null
        var tokens = mutableSetOf<BuilderBookAuctionToken>()
        return closureWork.submit({ closureTasks.isCurrent(lifecycle) }) {
            val current = stacks
            if (current != null && current.hasNext()) {
                BuilderBookAuctionTokenCodec.read(current.next())?.let(tokens::add)
                false
            } else {
                if (tokens.isNotEmpty()) captured[id] = tokens
                tokens = mutableSetOf()
                if (index == items.size) true
                else {
                    val item = items[index++]
                    id = item.id
                    stacks = if (!deletedOnly || item.status == ItemStatus.DELETED) {
                        (item as? AuctionItem)?.itemStacks?.iterator()
                    } else null
                    false
                }
            }
        }.thenApply { captured }
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
