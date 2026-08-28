package ru.arc.buildertools

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.autobuild.BuildBookCodec
import ru.arc.autobuild.BuildBookData
import ru.arc.autobuild.BuildBookItems
import ru.arc.autobuild.BuildBookSettings
import ru.arc.autobuild.BuildingManager
import ru.arc.autobuild.PlayerBuildBookLimitException
import ru.arc.autobuild.PlayerBuildBookDigestInspection
import ru.arc.autobuild.PlayerBuildBookStore
import ru.arc.autobuild.PlayerBuildBookTemplate
import ru.arc.autobuild.PreparedPlayerBuildBookTemplate
import ru.arc.core.LifecycleTaskScope
import ru.arc.text.LocalizedMiniMessage
import ru.arc.util.Logging.error
import ru.arc.util.Logging.warn
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException

internal data class BuilderDraftLifecycleHealth(
    val ready: Boolean,
    val failed: Boolean,
    val recoveryBlocked: Boolean,
)

internal data class BuilderDraftInventoryEvidence(
    val matchingItems: Int,
    val conflictingItems: Int,
) {
    companion object {
        /** Captures the current main-thread view immediately before a recovery decision. */
        fun capture(player: Player, record: BuilderDraftRecord): BuilderDraftInventoryEvidence {
            check(Bukkit.isPrimaryThread()) { "Builder-draft inventory evidence must be captured on the primary thread" }
            val presented = sequence {
                player.inventory.contents.filterNotNull().forEach { yield(it) }
                player.itemOnCursor.takeUnless { it.type.isAir }?.let { yield(it) }
            }
                .mapNotNull(BuildBookCodec::read)
                .filter { data -> data.draft && data.blueprintId == record.blueprintId }
                .toList()
            val matching = presented.count { data -> matches(record, data) }
            return BuilderDraftInventoryEvidence(matching, presented.size - matching)
        }

        private fun matches(record: BuilderDraftRecord, data: BuildBookData): Boolean =
            data.draft &&
                data.creatorId == record.playerId &&
                data.creatorName == record.playerName &&
                data.blueprintId == record.blueprintId &&
                data.buildingId == record.buildingId &&
                data.title == record.title &&
                data.contentSha256 == record.contentSha256 &&
                data.schematicSha256 == record.schematicSha256 &&
                data.blockCount == record.blockCount
    }
}

/**
 * Durable free-draft issuance. The journal is committed before the schematic is
 * published and acknowledged only after the exact draft is in the inventory.
 */
internal class BuilderDraftLifecycle(
    private val config: BuilderToolsConfig,
    private val messages: LocalizedMiniMessage,
    private val taskScope: LifecycleTaskScope,
    private val storageExecutor: ExecutorService,
    private val operationLocks: BuilderOperationLocks,
    private val host: BuilderBookLifecycleHost,
    private val journal: BuilderDraftJournal,
) : AutoCloseable {
    private sealed interface PersistenceOutcome {
        data class Ready(val record: BuilderDraftRecord) : PersistenceOutcome
        data class CleanFailure(val failure: Throwable) : PersistenceOutcome
        data class Recoverable(val record: BuilderDraftRecord, val failure: Throwable) : PersistenceOutcome
    }

    private val pending = mutableMapOf<UUID, BuilderDraftRecord>()
    private val conflictedPlayers = mutableSetOf<UUID>()
    private val recoveries = mutableSetOf<UUID>()
    private val uncertainAcknowledgements = mutableSetOf<UUID>()
    private var ready = false
    private var failed = false
    private var closed = false

    fun start() {
        writeAsync(
            action = journal::loadAll,
            callback = { records, failure ->
                if (failure != null || records == null) {
                    failed = true
                    error(
                        "Builder-draft journal initialization failed: " +
                            "type=${BuilderToolsFailureType.of(failure)} result_present=${records != null}",
                    )
                    return@writeAsync
                }
                records.groupBy(BuilderDraftRecord::playerId).forEach { (playerId, playerRecords) ->
                    if (playerRecords.size == 1) {
                        pending[playerId] = playerRecords.single()
                    } else {
                        conflictedPlayers += playerId
                        error("Builder-draft journal has ${playerRecords.size} open records for player=$playerId")
                    }
                }
                ready = true
                Bukkit.getOnlinePlayers().forEach(::onPlayerAvailable)
            },
        )
    }

    fun health(): BuilderDraftLifecycleHealth = BuilderDraftLifecycleHealth(
        ready = ready,
        failed = failed,
        recoveryBlocked = failed || conflictedPlayers.isNotEmpty() || uncertainAcknowledgements.isNotEmpty(),
    )

    fun hasPending(playerId: UUID): Boolean = playerId in pending || playerId in conflictedPlayers

    fun showPendingStatus(player: Player) {
        send(player, if (player.uniqueId in conflictedPlayers) "book.manual-review" else "book.status.draft-recovery")
    }

    fun createDraft(player: Player, rawTitle: List<String>) {
        host.ensureCopyPermission(player)
        if (!player.hasPermission("arc.build.book.create")) fail("errors.no-permission")
        if (!ready) fail(if (failed) "book.failed" else "book.draft-recovery-starting")
        if (player.uniqueId in conflictedPlayers) fail("book.manual-review")
        if (pending[player.uniqueId] != null) {
            recover(player, allowDelivery = true, announce = true, createdNow = false)
            return
        }
        val clipboard = host.currentClipboard(player.uniqueId) ?: fail("errors.expired")
        val held = player.inventory.itemInMainHand
        if (!isPlainBook(held)) fail("book.material-required")
        if (held.amount > 1 && player.inventory.firstEmpty() == -1) fail("book.inventory-full")
        val title = rawTitle.joinToString(" ").trim().ifEmpty { BuildBookSettings.defaultTitle }
        if (title.length > 48 || title.any(Char::isISOControl)) fail("book.invalid-name")
        val prepared = try {
            PlayerBuildBookStore.prepare(player.uniqueId, clipboard)
        } catch (failure: Throwable) {
            error("Could not prepare player build book for ${player.name}: type=${BuilderToolsFailureType.of(failure)}")
            fail("book.failed")
        }
        if (!operationLocks.tryBookLock(player.uniqueId)) fail("errors.busy")
        val expectedBook = held.clone()
        val now = System.currentTimeMillis()
        val record = BuilderDraftRecord(
            operationId = UUID.randomUUID(),
            playerId = player.uniqueId,
            playerName = player.name,
            title = title,
            buildingId = prepared.fileName,
            blueprintId = UUID.randomUUID(),
            contentSha256 = prepared.contentSha256,
            blockCount = prepared.blockCount,
            phase = BuilderDraftPhase.PREPARED,
            createdAtMillis = now,
            updatedAtMillis = now,
        ).validated(config.maxClipboardBlocks)
        send(player, "book.draft-saving")
        writeAsync(
            action = { persist(record, prepared) },
            callback = { outcome, failure ->
                when {
                    failure != null || outcome == null -> {
                        operationLocks.unlockBook(player.uniqueId)
                        error(
                            "Could not start durable player build-book draft for ${player.name}: " +
                                "type=${BuilderToolsFailureType.of(failure)}",
                        )
                        if (player.isOnline) send(player, "book.failed")
                    }
                    outcome is PersistenceOutcome.CleanFailure -> {
                        operationLocks.unlockBook(player.uniqueId)
                        if (player.isOnline) {
                            send(
                                player,
                                if (outcome.failure is PlayerBuildBookLimitException) "book.limit" else "book.failed",
                            )
                        }
                    }
                    outcome is PersistenceOutcome.Ready -> {
                        pending[player.uniqueId] = outcome.record
                        if (player.isOnline) {
                            recover(
                                player,
                                allowDelivery = true,
                                announce = true,
                                expectedBook = expectedBook,
                                createdNow = true,
                            )
                        } else {
                            operationLocks.unlockBook(player.uniqueId)
                        }
                    }
                    outcome is PersistenceOutcome.Recoverable -> {
                        pending[player.uniqueId] = outcome.record
                        warn(
                            "Builder-draft persistence requires recovery: operation={} player={} type={}",
                            outcome.record.operationId,
                            outcome.record.playerId,
                            BuilderToolsFailureType.of(outcome.failure),
                        )
                        if (player.isOnline) {
                            recover(
                                player,
                                allowDelivery = true,
                                announce = true,
                                expectedBook = expectedBook,
                                createdNow = true,
                            )
                        } else {
                            operationLocks.unlockBook(player.uniqueId)
                        }
                    }
                }
            },
        )
    }

    fun onPlayerAvailable(player: Player) {
        if (!ready || operationLocks.isRecoveryLocked(player.uniqueId)) return
        if (player.uniqueId in conflictedPlayers) {
            send(player, "book.manual-review")
            return
        }
        if (pending[player.uniqueId] != null) recover(player, allowDelivery = false, announce = true, createdNow = false)
    }

    fun retryLockedPlayers() {
        if (!ready) return
        (operationLocks.bookLockedPlayerIds() intersect pending.keys).toList()
            .mapNotNull(Bukkit::getPlayer)
            .filter(Player::isOnline)
            .forEach { recover(it, allowDelivery = false, announce = false, createdNow = false) }
    }

    fun onPlayerQuit(playerId: UUID) {
        if (playerId in pending) operationLocks.unlockBook(playerId)
        recoveries -= playerId
    }

    private fun persist(
        initial: BuilderDraftRecord,
        prepared: PreparedPlayerBuildBookTemplate,
    ): PersistenceOutcome {
        val durable = journal.commit(initial)
        return try {
            val template = PlayerBuildBookStore.persist(prepared)
            PersistenceOutcome.Ready(
                journal.transition(
                    durable,
                    durable.ready(template.schematicSha256, transitionTime(durable)),
                ),
            )
        } catch (failure: Throwable) {
            when (val inspection = PlayerBuildBookStore.inspectSchematic(durable.buildingId)) {
                PlayerBuildBookDigestInspection.Missing -> {
                    if (runCatching { journal.acknowledgeConfirmed(durable.operationId) }.getOrDefault(false)) {
                        PersistenceOutcome.CleanFailure(failure)
                    } else {
                        PersistenceOutcome.Recoverable(durable, failure)
                    }
                }
                is PlayerBuildBookDigestInspection.Ready -> {
                    val recovered = runCatching {
                        journal.transition(
                            durable,
                            durable.ready(inspection.sha256, transitionTime(durable)),
                        )
                    }.getOrNull()
                    if (recovered != null) {
                        PersistenceOutcome.Ready(recovered)
                    } else {
                        PersistenceOutcome.Recoverable(
                            runCatching { journal.loadOrNull(durable.operationId) }.getOrNull() ?: durable,
                            failure,
                        )
                    }
                }
                is PlayerBuildBookDigestInspection.Failed -> PersistenceOutcome.Recoverable(
                    durable,
                    inspection.failure,
                )
            }
        }
    }

    private fun recover(
        player: Player,
        allowDelivery: Boolean,
        announce: Boolean,
        expectedBook: ItemStack? = null,
        createdNow: Boolean,
    ) {
        val playerId = player.uniqueId
        val record = pending[playerId] ?: return
        if (!recoveries.add(playerId)) return
        if (!operationLocks.isBookLocked(playerId) && !operationLocks.tryBookLock(playerId)) {
            recoveries -= playerId
            if (announce) send(player, "errors.busy")
            return
        }
        writeAsync(
            action = { PlayerBuildBookStore.inspectSchematic(record.buildingId) },
            callback = recovery@{ inspection, failure ->
                if (!player.isOnline) {
                    finishRecovery(playerId)
                    return@recovery
                }
                if (failure != null || inspection == null || inspection is PlayerBuildBookDigestInspection.Failed) {
                    uncertainAcknowledgements += record.operationId
                    recoveries -= playerId
                    warn(
                        "Builder-draft file inspection requires retry: operation={} player={} type={}",
                        record.operationId,
                        playerId,
                        BuilderToolsFailureType.of(
                            (inspection as? PlayerBuildBookDigestInspection.Failed)?.failure ?: failure,
                        ),
                    )
                    send(player, "book.draft-recovering")
                    return@recovery
                }
                val actualSha256 = (inspection as? PlayerBuildBookDigestInspection.Ready)?.sha256
                if (actualSha256 != null) {
                    when (val content = PlayerBuildBookStore.inspectContent(record.buildingId)) {
                        is PlayerBuildBookDigestInspection.Ready -> if (content.sha256 != record.contentSha256) {
                            manualReview(player, record)
                            return@recovery
                        }
                        PlayerBuildBookDigestInspection.Missing,
                        is PlayerBuildBookDigestInspection.Failed,
                        -> {
                            uncertainAcknowledgements += record.operationId
                            recoveries -= playerId
                            warn(
                                "Builder-draft content verification requires retry: operation={} player={} type={}",
                                record.operationId,
                                playerId,
                                BuilderToolsFailureType.of(
                                    (content as? PlayerBuildBookDigestInspection.Failed)?.failure,
                                ),
                            )
                            send(player, "book.draft-recovering")
                            return@recovery
                        }
                    }
                }
                uncertainAcknowledgements -= record.operationId
                // Storage inspection is asynchronous. Re-snapshot the locked
                // inventory now so a moved cursor/item cannot make the journal
                // acknowledge stale evidence or issue a second draft.
                val inventoryEvidence = BuilderDraftInventoryEvidence.capture(player, record)
                when (
                    BuilderDraftRecoveryRules.action(
                        record,
                        actualSha256,
                        inventoryEvidence.matchingItems,
                        inventoryEvidence.conflictingItems,
                    )
                ) {
                    BuilderDraftRecoveryAction.ACK_FAILED -> acknowledgeFailed(player, record)
                    BuilderDraftRecoveryAction.ADVANCE_READY -> advanceReady(
                        player,
                        record,
                        checkNotNull(actualSha256),
                        allowDelivery,
                        announce,
                        expectedBook,
                        createdNow,
                    )
                    BuilderDraftRecoveryAction.AWAIT_SOURCE_BOOK -> awaitOrDeliver(
                        player,
                        record,
                        allowDelivery,
                        announce,
                        expectedBook,
                        createdNow,
                    )
                    BuilderDraftRecoveryAction.ACK_DELIVERED -> acknowledgeDelivered(
                        player,
                        record,
                        createdNow = false,
                        recoveredItem = true,
                    )
                    BuilderDraftRecoveryAction.MANUAL_REVIEW -> manualReview(player, record)
                }
            },
        )
    }

    private fun advanceReady(
        player: Player,
        record: BuilderDraftRecord,
        schematicSha256: String,
        allowDelivery: Boolean,
        announce: Boolean,
        expectedBook: ItemStack?,
        createdNow: Boolean,
    ) {
        val target = record.ready(schematicSha256, transitionTime(record))
        writeAsync(
            action = { journal.transition(record, target) },
            callback = { durable, failure ->
                if (failure != null || durable == null) {
                    uncertainAcknowledgements += record.operationId
                    recoveries -= player.uniqueId
                    warn(
                        "Builder-draft READY transition requires retry: operation={} player={} type={}",
                        record.operationId,
                        player.uniqueId,
                        BuilderToolsFailureType.of(failure),
                    )
                    if (player.isOnline) send(player, "book.draft-recovering")
                    return@writeAsync
                }
                pending[player.uniqueId] = durable
                if (!player.isOnline) {
                    finishRecovery(player.uniqueId)
                    return@writeAsync
                }
                awaitOrDeliver(player, durable, allowDelivery, announce, expectedBook, createdNow)
            },
        )
    }

    private fun awaitOrDeliver(
        player: Player,
        record: BuilderDraftRecord,
        allowDelivery: Boolean,
        announce: Boolean,
        expectedBook: ItemStack?,
        createdNow: Boolean,
    ) {
        if (!player.isOnline) {
            finishRecovery(player.uniqueId)
            return
        }
        if (!allowDelivery) {
            finishRecovery(player.uniqueId)
            if (announce) send(player, "book.draft-pending")
            return
        }
        val held = player.inventory.itemInMainHand
        val sourceMatches = if (expectedBook == null) {
            isPlainBook(held)
        } else {
            isPlainBook(held) && held.amount == expectedBook.amount && held.isSimilar(expectedBook)
        }
        if (!sourceMatches) {
            finishRecovery(player.uniqueId)
            send(player, if (expectedBook == null) "book.material-required" else "book.source-changed")
            send(player, "book.draft-pending")
            return
        }
        if (held.amount > 1 && player.inventory.firstEmpty() == -1) {
            finishRecovery(player.uniqueId)
            send(player, "book.inventory-full")
            return
        }
        val data = draftData(record)
        val output = try {
            BuildBookItems.create(data)
        } catch (failure: Throwable) {
            error("Could not reconstruct durable builder draft ${record.operationId}", failure)
            manualReview(player, record)
            return
        }
        try {
            replaceOneHeldBook(player, held, output)
            runCatching { PlayerBuildBookStore.register(template(record)) }
                .onFailure { failure ->
                    warn(
                        "Builder-draft cache registration failed but lazy file lookup remains available: " +
                            "operation=${record.operationId} type=${BuilderToolsFailureType.of(failure)}",
                    )
                }
            player.updateInventory()
        } catch (failure: Throwable) {
            error("Could not deliver durable builder draft ${record.operationId}", failure)
            manualReview(player, record)
            return
        }
        acknowledgeDelivered(player, record, createdNow = createdNow, recoveredItem = false)
    }

    private fun acknowledgeFailed(player: Player, record: BuilderDraftRecord) {
        writeAsync(
            action = { journal.acknowledgeConfirmed(record.operationId) },
            callback = { acknowledged, failure ->
                if (failure != null || acknowledged != true) {
                    uncertainAcknowledgements += record.operationId
                    recoveries -= player.uniqueId
                    warn(
                        "Builder-draft failed-write acknowledgement requires retry: operation={} player={} type={}",
                        record.operationId,
                        player.uniqueId,
                        BuilderToolsFailureType.of(failure),
                    )
                    if (player.isOnline) send(player, "book.draft-recovering")
                    return@writeAsync
                }
                pending.remove(player.uniqueId, record)
                uncertainAcknowledgements -= record.operationId
                finishRecovery(player.uniqueId)
                if (player.isOnline) send(player, "book.draft-recovery-failed")
            },
        )
    }

    private fun acknowledgeDelivered(
        player: Player,
        record: BuilderDraftRecord,
        createdNow: Boolean,
        recoveredItem: Boolean,
    ) {
        writeAsync(
            action = { journal.acknowledgeConfirmed(record.operationId) },
            callback = { acknowledged, failure ->
                if (failure != null || acknowledged != true) {
                    uncertainAcknowledgements += record.operationId
                    recoveries -= player.uniqueId
                    warn(
                        "Builder-draft delivery acknowledgement requires retry: operation={} player={} type={}",
                        record.operationId,
                        player.uniqueId,
                        BuilderToolsFailureType.of(failure),
                    )
                    if (player.isOnline) send(player, "book.draft-recovering")
                    return@writeAsync
                }
                pending.remove(player.uniqueId, record)
                uncertainAcknowledgements -= record.operationId
                finishRecovery(player.uniqueId)
                if (!player.isOnline) return@writeAsync
                send(
                    player,
                    if (createdNow && !recoveredItem) "book.draft-created" else "book.draft-recovered",
                    mapOf(
                        "name" to messages.literal(BuildBookItems.compactTitle(record.title, 22)),
                        "count" to messages.literal(record.blockCount),
                    ),
                )
            },
        )
    }

    private fun manualReview(player: Player, record: BuilderDraftRecord) {
        error("Builder-draft recovery requires manual review: operation=${record.operationId} player=${record.playerId}")
        conflictedPlayers += player.uniqueId
        uncertainAcknowledgements += record.operationId
        finishRecovery(player.uniqueId)
        if (player.isOnline) send(player, "book.manual-review")
    }

    private fun draftData(record: BuilderDraftRecord): BuildBookData = BuildBookData(
        buildingId = record.buildingId,
        title = record.title,
        playerCreated = true,
        creatorId = record.playerId,
        creatorName = record.playerName,
        blueprintId = record.blueprintId,
        contentSha256 = record.contentSha256,
        schematicSha256 = checkNotNull(record.schematicSha256),
        blockCount = record.blockCount,
        cooldownSeconds = 0,
    ).validated()

    private fun template(record: BuilderDraftRecord): PlayerBuildBookTemplate = PlayerBuildBookTemplate(
        buildingId = record.buildingId,
        contentSha256 = record.contentSha256,
        schematicSha256 = checkNotNull(record.schematicSha256),
        blockCount = record.blockCount,
    )

    private fun finishRecovery(playerId: UUID) {
        recoveries -= playerId
        operationLocks.unlockBook(playerId)
    }

    private fun transitionTime(record: BuilderDraftRecord): Long =
        maxOf(System.currentTimeMillis(), record.updatedAtMillis)

    private fun isPlainBook(item: ItemStack): Boolean {
        if (item.type != Material.BOOK || item.amount <= 0) return false
        return item.clone().also { it.amount = 1 }.isSimilar(ItemStack(Material.BOOK))
    }

    private fun replaceOneHeldBook(player: Player, held: ItemStack, replacement: ItemStack) {
        if (held.amount == 1) {
            player.inventory.setItemInMainHand(replacement)
            return
        }
        if (player.inventory.firstEmpty() == -1) fail("book.inventory-full")
        player.inventory.setItemInMainHand(held.clone().also { it.amount = held.amount - 1 })
        check(player.inventory.addItem(replacement).isEmpty()) { "Durable builder draft did not fit after preflight" }
    }

    private fun <T> writeAsync(action: () -> T, callback: (T?, Throwable?) -> Unit) {
        if (closed) return
        try {
            storageExecutor.submit {
                val result = runCatching(action)
                taskScope.runSync { callback(result.getOrNull(), result.exceptionOrNull()) }
            }
        } catch (failure: RejectedExecutionException) {
            taskScope.runSync { callback(null, failure) }
        }
    }

    private fun send(player: Player, path: String, values: Map<String, Component> = emptyMap()) =
        host.send(player, path, values)

    private fun fail(path: String): Nothing = throw BuilderUserFailure(path)

    override fun close() {
        if (closed) return
        closed = true
        pending.clear()
        conflictedPlayers.clear()
        recoveries.clear()
        uncertainAcknowledgements.clear()
    }
}
