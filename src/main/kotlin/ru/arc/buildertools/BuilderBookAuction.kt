package ru.arc.buildertools

import org.bukkit.NamespacedKey
import org.bukkit.block.Container
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta
import org.bukkit.inventory.meta.BundleMeta
import org.bukkit.persistence.PersistentDataType
import ru.arc.autobuild.BuildBookCodec
import ru.arc.autobuild.BuildBookData
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CompletableFuture

internal data class BuilderBookAuctionToken(
    val instanceId: UUID,
    val leaseId: UUID,
)

/**
 * A short-lived bearer token copied into the exact ItemStack handed to the
 * auction. It never replaces the MySQL state: the token identifies which
 * LISTED compare-and-set may be released when zAuctionHouse returns the item.
 */
internal object BuilderBookAuctionTokenCodec {
    @Suppress("DEPRECATION")
    private fun key(value: String) = NamespacedKey("arc", value)
    private val instanceKey get() = key("build_book_auction_instance")
    private val leaseKey get() = key("build_book_auction_lease")

    fun attach(item: ItemStack, token: BuilderBookAuctionToken): ItemStack = item.clone().also { updated ->
        val data = requireNotNull(BuildBookCodec.read(updated)) { "Auction token requires a build book" }
        require(data.available && data.instanceId == token.instanceId) { "Auction token identity does not match build book" }
        updated.editMeta { meta ->
            meta.persistentDataContainer.set(instanceKey, PersistentDataType.STRING, token.instanceId.toString())
            meta.persistentDataContainer.set(leaseKey, PersistentDataType.STRING, token.leaseId.toString())
        }
    }

    fun read(item: ItemStack): BuilderBookAuctionToken? {
        val data = BuildBookCodec.read(item)?.takeIf(BuildBookData::registered) ?: return null
        val pdc = item.itemMeta?.persistentDataContainer ?: return null
        return runCatching {
            val instanceId = UUID.fromString(pdc.get(instanceKey, PersistentDataType.STRING) ?: return null)
            val leaseId = UUID.fromString(pdc.get(leaseKey, PersistentDataType.STRING) ?: return null)
            BuilderBookAuctionToken(instanceId, leaseId).takeIf { it.instanceId == data.instanceId }
        }.getOrNull()
    }

    fun strip(item: ItemStack, expected: BuilderBookAuctionToken): ItemStack? {
        if (read(item) != expected) return null
        return item.clone().also { updated ->
            updated.editMeta { meta ->
                meta.persistentDataContainer.remove(instanceKey)
                meta.persistentDataContainer.remove(leaseKey)
            }
        }
    }
}

internal enum class BuilderBookAuctionFailure {
    REJECTED,
    AMBIGUOUS,
    UNAVAILABLE,
}

internal sealed interface BuilderBookAuctionListingResult {
    data class Listed(val listingId: String) : BuilderBookAuctionListingResult
    data class Failed(val reason: BuilderBookAuctionFailure) : BuilderBookAuctionListingResult
}

/** Optional-plugin boundary: Builder Tools never links directly to zAuctionHouse classes. */
internal interface BuilderBookAuctionPort {
    fun submit(
        player: Player,
        price: BigDecimal,
        tokenizedItem: ItemStack,
    ): CompletableFuture<BuilderBookAuctionListingResult>

    /** Main-thread, bounded cache lookup across listed, purchased and expired storage. */
    fun contains(token: BuilderBookAuctionToken): Boolean

    fun setDeliveryHandler(handler: ((Player, ItemStack) -> Unit)?)
}

internal object BuilderBookAuctionPrice {
    private val INPUT = Regex("[0-9]{1,12}(?:[.,][0-9]{1,2})?")

    fun parse(raw: String?): BigDecimal? {
        val normalized = raw?.trim()?.takeIf(INPUT::matches)?.replace(',', '.') ?: return null
        return runCatching { BigDecimal(normalized) }
            .getOrNull()
            ?.takeIf { it.signum() > 0 }
    }
}

/**
 * Bounded inspection used by every zAuctionHouse sell path. Player-created
 * books remain protected even when a sell inventory receives a shulker or a
 * bundle containing the book instead of the book itself.
 */
internal object BuilderBookAuctionItemGuard {
    private const val MAX_DEPTH = 4
    private const val MAX_ITEMS = 256

    fun containsPlayerCreatedBook(root: ItemStack): Boolean = runCatching {
        val pending = ArrayDeque<Pair<ItemStack, Int>>()
        pending.addLast(root to 0)
        var inspected = 0
        while (pending.isNotEmpty()) {
            val (item, depth) = pending.removeFirst()
            inspected += 1
            if (inspected > MAX_ITEMS) return true
            if (BuildBookCodec.read(item)?.playerCreated == true) return true
            if (depth >= MAX_DEPTH) continue
            nestedItems(item).forEach { nested ->
                if (!nested.type.isAir) pending.addLast(nested to depth + 1)
            }
        }
        false
    }.getOrElse {
        // Malformed or unsupported container metadata must not become a bypass.
        true
    }

    private fun nestedItems(item: ItemStack): List<ItemStack> = when (val meta = item.itemMeta) {
        is BundleMeta -> meta.items
        is BlockStateMeta -> (meta.blockState as? Container)?.inventory?.contents?.filterNotNull().orEmpty()
        else -> emptyList()
    }
}

/**
 * Coordinates the MySQL LISTED lease with zAuctionHouse's asynchronous sale.
 * All inventory mutations and port calls happen through [runSync]. Unknown
 * outcomes remain fail-closed in MySQL until exact auction or item evidence is
 * available.
 */
internal class BuilderBookAuctionCoordinator(
    private val registry: BuilderBookRegistry,
    private val portProvider: () -> BuilderBookAuctionPort?,
    private val serverName: String,
    private val runSync: (() -> Unit) -> Unit,
    private val send: (Player, String, Map<String, String>) -> Unit,
    private val lock: (UUID) -> Boolean,
    private val unlock: (UUID) -> Unit,
    private val recoveryRetryMillis: Long = 30_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private var installedPort: BuilderBookAuctionPort? = null
    private val recoveryInFlight = mutableSetOf<BuilderBookAuctionToken>()
    private val reviewNotified = mutableSetOf<BuilderBookAuctionToken>()
    private val retryAfterMillis = mutableMapOf<BuilderBookAuctionToken, Long>()
    private var closed = false

    fun start() {
        check(!closed) { "Builder-book auction coordinator is closed" }
        installedPort = portProvider()?.also { port ->
            port.setDeliveryHandler(::onDelivered)
        }
        reconcile()
    }

    fun sell(player: Player, data: BuildBookData, held: ItemStack, price: BigDecimal) {
        check(!closed) { "Builder-book auction coordinator is closed" }
        val port = portProvider() ?: run {
            send(player, "book.auction-unavailable", emptyMap())
            return
        }
        val instanceId = requireNotNull(data.instanceId)
        val blueprintId = requireNotNull(data.blueprintId)
        val schematicSha256 = requireNotNull(data.schematicSha256)
        if (!lock(player.uniqueId)) {
            send(player, "errors.busy", emptyMap())
            return
        }
        val leaseId = UUID.randomUUID()
        registry.reserveForAuction(
            instanceId = instanceId,
            expectedGeneration = requireNotNull(data.instanceGeneration),
            expectedBlueprintId = blueprintId,
            expectedBuildingId = data.buildingId,
            expectedSchematicSha256 = schematicSha256,
            leaseId = leaseId,
            sellerId = player.uniqueId,
            serverName = serverName,
            now = clock(),
        ).whenComplete { reserved, failure ->
            runSync {
                if (closed || !player.isOnline) {
                    unlock(player.uniqueId)
                    if (failure == null && reserved is BuilderBookAuctionReservationResult.Reserved) {
                        releaseWithoutItem(instanceId, leaseId)
                    }
                    return@runSync
                }
                if (failure != null) {
                    unlock(player.uniqueId)
                    warn(
                        "Builder-book auction reservation failed for {}: type={}",
                        player.name,
                        BuilderToolsFailureType.of(failure),
                    )
                    send(player, "book.registry-unavailable", emptyMap())
                    return@runSync
                }
                when (reserved) {
                    is BuilderBookAuctionReservationResult.Reserved -> submit(
                        player,
                        data,
                        held,
                        price,
                        BuilderBookAuctionToken(instanceId, leaseId),
                        port,
                    )
                    BuilderBookAuctionReservationResult.Missing,
                    BuilderBookAuctionReservationResult.Mismatch,
                    -> {
                        unlock(player.uniqueId)
                        send(player, "book.invalid", emptyMap())
                    }
                    BuilderBookAuctionReservationResult.Unavailable -> {
                        unlock(player.uniqueId)
                        send(player, "book.duplicate", emptyMap())
                    }
                    BuilderBookAuctionReservationResult.Stale -> {
                        unlock(player.uniqueId)
                        send(player, "book.stale", emptyMap())
                    }
                    null -> {
                        unlock(player.uniqueId)
                        send(player, "book.registry-unavailable", emptyMap())
                    }
                }
            }
        }
    }

    fun onPlayerAvailable(player: Player) {
        if (closed) return
        val port = portProvider() ?: return
        inventoryTokens(player).forEach { token ->
            if (clock() < retryAfterMillis.getOrDefault(token, 0L)) return@forEach
            when (containsSafely(port, token)) {
                true, null -> return@forEach
                false -> Unit
            }
            transferAndRestore(player, token, "book.auction-returned")
        }
    }

    private fun submit(
        player: Player,
        expectedData: BuildBookData,
        expectedHeld: ItemStack,
        price: BigDecimal,
        token: BuilderBookAuctionToken,
        port: BuilderBookAuctionPort,
    ) {
        val current = player.inventory.itemInMainHand
        if (
            current.amount != 1 || !current.isSimilar(expectedHeld) ||
            BuildBookCodec.read(current) != expectedData || BuilderBookAuctionTokenCodec.read(current) != null
        ) {
            unlock(player.uniqueId)
            releaseWithoutItem(token.instanceId, token.leaseId)
            send(player, "book.source-changed", emptyMap())
            return
        }
        val tokenized = try {
            BuilderBookAuctionTokenCodec.attach(current, token)
        } catch (failure: RuntimeException) {
            unlock(player.uniqueId)
            releaseWithoutItem(token.instanceId, token.leaseId)
            error(
                "Builder-book auction token creation failed for ${player.name}: " +
                    "type=${BuilderToolsFailureType.of(failure)}",
            )
            send(player, "book.invalid", emptyMap())
            return
        }
        player.inventory.setItemInMainHand(tokenized)
        player.updateInventory()
        val future = try {
            port.submit(player, price, tokenized)
        } catch (failure: Throwable) {
            error(
                "Builder-book auction submission failed for ${player.name}: " +
                    "type=${BuilderToolsFailureType.of(failure)}",
            )
            completeFailure(player, token, port, BuilderBookAuctionFailure.UNAVAILABLE)
            return
        }
        future.whenComplete { result, failure ->
            runSync {
                if (closed) return@runSync
                if (failure != null || result == null) {
                    warn(
                        "Builder-book auction result failed for {}: type={} result_present={}",
                        player.name,
                        BuilderToolsFailureType.of(failure),
                        result != null,
                    )
                    completeFailure(player, token, port, BuilderBookAuctionFailure.AMBIGUOUS)
                    return@runSync
                }
                when (result) {
                    is BuilderBookAuctionListingResult.Listed -> {
                        if (containsSafely(port, token) != true) {
                            warn("Builder-book auction success lacks storage evidence: instance={} lease={}", token.instanceId, token.leaseId)
                            completeFailure(player, token, port, BuilderBookAuctionFailure.AMBIGUOUS)
                        } else if (player.isOnline) {
                            unlock(player.uniqueId)
                            send(
                                player,
                                "book.auction-listed",
                                mapOf("price" to price.stripTrailingZeros().toPlainString()),
                            )
                        } else {
                            unlock(player.uniqueId)
                        }
                    }
                    is BuilderBookAuctionListingResult.Failed -> completeFailure(player, token, port, result.reason)
                }
            }
        }
    }

    private fun completeFailure(
        player: Player,
        token: BuilderBookAuctionToken,
        port: BuilderBookAuctionPort,
        reason: BuilderBookAuctionFailure,
    ) {
        when (containsSafely(port, token)) {
            true -> {
                unlock(player.uniqueId)
                if (player.isOnline) send(player, "book.auction-listed-late", emptyMap())
                return
            }
            null -> {
                unlock(player.uniqueId)
                notifyReview(player, token)
                return
            }
            false -> Unit
        }
        warn(
            "Builder-book auction submission did not commit: instance={} lease={} reason={}",
            token.instanceId,
            token.leaseId,
            reason,
        )
        transferAndRestore(player, token, "book.auction-rejected", lockAlreadyHeld = true)
    }

    private fun onDelivered(player: Player, item: ItemStack) {
        if (closed) return
        val token = BuilderBookAuctionTokenCodec.read(item) ?: return
        val port = portProvider() ?: return
        if (containsSafely(port, token) != false) {
            // zAuctionHouse also fires the listed-removal event when it moves a
            // lot to EXPIRED without giving it to the player.
            return
        }
        transferAndRestore(player, token, "book.auction-received")
    }

    private fun transferAndRestore(
        player: Player,
        token: BuilderBookAuctionToken,
        successPath: String,
        lockAlreadyHeld: Boolean = false,
    ) {
        if (!lockAlreadyHeld && !lock(player.uniqueId)) return
        if (!recoveryInFlight.add(token)) {
            unlock(player.uniqueId)
            return
        }
        if (!player.isOnline || inventoryTokenSlots(player, token).size != 1) {
            finishRecoveryAttempt(player, token)
            warn(
                "Builder-book auction item was not available for exact release: instance={} lease={}",
                token.instanceId,
                token.leaseId,
            )
            notifyReview(player, token)
            return
        }
        registry.beginAuctionTransfer(
            instanceId = token.instanceId,
            leaseId = token.leaseId,
            recipientId = player.uniqueId,
            serverName = serverName,
            now = clock(),
        ).whenComplete { transfer, failure ->
            runSync {
                if (failure != null || transfer == null) {
                    error(
                        "Builder-book auction transfer start failed: instance=${token.instanceId} lease=${token.leaseId} " +
                            "type=${BuilderToolsFailureType.of(failure)} result_present=${transfer != null}",
                    )
                    retryAfterMillis[token] = Math.addExact(clock(), recoveryRetryMillis)
                    notifyReview(player, token)
                    finishRecoveryAttempt(player, token)
                    return@runSync
                }
                when (transfer) {
                    is BuilderBookAuctionTransferResult.Pending -> completePendingTransfer(
                        player,
                        token,
                        transfer.generation,
                        successPath,
                    )
                    is BuilderBookAuctionTransferResult.Completed -> finishCompletedTransfer(
                        player,
                        token,
                        transfer.generation,
                        successPath,
                    )
                    BuilderBookAuctionTransferResult.Rejected -> {
                        warn(
                            "Builder-book auction transfer evidence was rejected: instance={} lease={}",
                            token.instanceId,
                            token.leaseId,
                        )
                        notifyReview(player, token)
                        finishRecoveryAttempt(player, token)
                    }
                }
            }
        }
    }

    private fun completePendingTransfer(
        player: Player,
        token: BuilderBookAuctionToken,
        generation: Int,
        successPath: String,
    ) {
        if (!player.isOnline) {
            finishRecoveryAttempt(player, token)
            return
        }
        if (!stageTokenGeneration(player, token, generation)) {
            warn(
                "Builder-book auction token could not be staged: instance={} lease={} generation={}",
                token.instanceId,
                token.leaseId,
                generation,
            )
            notifyReview(player, token)
            finishRecoveryAttempt(player, token)
            return
        }
        registry.completeAuctionTransfer(
            instanceId = token.instanceId,
            leaseId = token.leaseId,
            recipientId = player.uniqueId,
            generation = generation,
        ).whenComplete { completed, failure ->
            runSync {
                if (failure != null || completed != true) {
                    error(
                        "Builder-book auction transfer completion failed: instance=${token.instanceId} lease=${token.leaseId} " +
                            "type=${BuilderToolsFailureType.of(failure)} result=$completed",
                    )
                    retryAfterMillis[token] = Math.addExact(clock(), recoveryRetryMillis)
                    notifyReview(player, token)
                    finishRecoveryAttempt(player, token)
                } else {
                    finishCompletedTransfer(player, token, generation, successPath)
                }
            }
        }
    }

    private fun finishCompletedTransfer(
        player: Player,
        token: BuilderBookAuctionToken,
        generation: Int,
        successPath: String,
    ) {
        if (!player.isOnline) {
            finishRecoveryAttempt(player, token)
            return
        }
        if (!stripCompletedToken(player, token, generation)) {
            warn(
                "Builder-book completed auction transfer lacks one exact item: instance={} lease={} generation={}",
                token.instanceId,
                token.leaseId,
                generation,
            )
            notifyReview(player, token)
            finishRecoveryAttempt(player, token)
            return
        }
        clearRecoveryTracking(token)
        if (player.isOnline) send(player, successPath, emptyMap())
        finishRecoveryAttempt(player, token)
    }

    private fun finishRecoveryAttempt(player: Player, token: BuilderBookAuctionToken) {
        recoveryInFlight -= token
        unlock(player.uniqueId)
    }

    private fun reconcile() {
        val port = portProvider() ?: return
        registry.listedForServer(serverName).whenComplete { instances, failure ->
            runSync {
                if (closed) return@runSync
                if (failure != null || instances == null) {
                    error(
                        "Builder-book auction recovery scan failed: " +
                            "type=${BuilderToolsFailureType.of(failure)} result_present=${instances != null}",
                    )
                    return@runSync
                }
                instances.forEach { instance ->
                    val leaseId = instance.reservationOperationId ?: return@forEach
                    val token = BuilderBookAuctionToken(instance.instanceId, leaseId)
                    if (containsSafely(port, token) != false) return@forEach
                    val player = org.bukkit.Bukkit.getPlayer(instance.reservationPlayerId ?: return@forEach)
                    if (player != null && player.isOnline && inventoryTokens(player).contains(token)) {
                        transferAndRestore(player, token, "book.auction-returned")
                    } else {
                        warn(
                            "Builder-book auction lease has no live storage evidence and remains fail-closed: instance={} lease={}",
                            instance.instanceId,
                            leaseId,
                        )
                    }
                }
                info("Builder-book auction recovery checked ${instances.size} listed instance(s)")
            }
        }
    }

    private fun inventoryTokens(player: Player): Set<BuilderBookAuctionToken> =
        player.inventory.contents.filterNotNull().mapNotNull(BuilderBookAuctionTokenCodec::read).toSet()

    private fun inventoryTokenSlots(player: Player, token: BuilderBookAuctionToken): List<Int> =
        (0 until player.inventory.size).filter { slot ->
            player.inventory.getItem(slot)?.let(BuilderBookAuctionTokenCodec::read) == token
        }

    private fun stageTokenGeneration(player: Player, token: BuilderBookAuctionToken, generation: Int): Boolean {
        val slots = inventoryTokenSlots(player, token)
        if (slots.size != 1) return false
        val slot = slots.single()
        val item = player.inventory.getItem(slot) ?: return false
        val data = BuildBookCodec.read(item) ?: return false
        if (data.instanceId != token.instanceId || data.instanceGeneration !in setOf(generation - 1, generation)) return false
        if (data.instanceGeneration != generation) {
            player.inventory.setItem(slot, BuildBookCodec.update(item, data.copy(instanceGeneration = generation).validated()))
        }
        player.updateInventory()
        return true
    }

    private fun stripCompletedToken(player: Player, token: BuilderBookAuctionToken, generation: Int): Boolean {
        val slots = inventoryTokenSlots(player, token)
        if (slots.size != 1) return false
        val slot = slots.single()
        val item = player.inventory.getItem(slot) ?: return false
        val data = BuildBookCodec.read(item) ?: return false
        if (data.instanceGeneration != generation) return false
        val restored = BuilderBookAuctionTokenCodec.strip(item, token) ?: return false
        player.inventory.setItem(slot, restored)
        player.updateInventory()
        return true
    }

    private fun releaseWithoutItem(instanceId: UUID, leaseId: UUID) {
        registry.releaseFromAuction(instanceId, leaseId).whenComplete { released, failure ->
            if (failure != null || released != true) {
                error(
                    "Builder-book pre-auction lease release failed: instance=$instanceId lease=$leaseId " +
                        "type=${BuilderToolsFailureType.of(failure)} result=$released",
                )
            }
        }
    }

    private fun containsSafely(port: BuilderBookAuctionPort, token: BuilderBookAuctionToken): Boolean? =
        try {
            port.contains(token)
        } catch (failure: RuntimeException) {
            warn(
                "Builder-book auction lookup failed and remains fail-closed: instance={} lease={} error={}",
                token.instanceId,
                token.leaseId,
                BuilderToolsFailureType.of(failure),
            )
            null
        }

    private fun notifyReview(player: Player, token: BuilderBookAuctionToken) {
        if (player.isOnline && reviewNotified.add(token)) send(player, "book.auction-review", emptyMap())
    }

    private fun clearRecoveryTracking(token: BuilderBookAuctionToken) {
        recoveryInFlight -= token
        retryAfterMillis -= token
        reviewNotified -= token
    }

    override fun close() {
        if (closed) return
        closed = true
        installedPort?.setDeliveryHandler(null)
        installedPort = null
        recoveryInFlight.clear()
        retryAfterMillis.clear()
        reviewNotified.clear()
    }

    init {
        require(recoveryRetryMillis in 5_000L..600_000L) {
            "Builder-book auction recovery retry is invalid"
        }
    }
}
