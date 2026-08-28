package ru.arc.buildertools

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.ARC
import ru.arc.autobuild.BuildBookCodec
import ru.arc.autobuild.BuildBookData
import ru.arc.autobuild.BuildBookItems
import ru.arc.autobuild.BuildBookTransform
import ru.arc.autobuild.BuildingManager
import ru.arc.autobuild.PlayerBuildBookStore
import ru.arc.autobuild.PreviewTransformUpdateResult
import ru.arc.core.LifecycleTaskScope
import ru.arc.hooks.HookRegistry
import ru.arc.onetime.OneTimeUseClaim
import ru.arc.onetime.OneTimeUseClaimResult
import ru.arc.onetime.OneTimeUseCommitResult
import ru.arc.sql.SqlRuntime
import ru.arc.text.LocalizedMiniMessage
import ru.arc.observability.StructuredDebugLine
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException

internal class BuilderUserFailure(
    val path: String,
    val values: Map<String, Component> = emptyMap(),
) : RuntimeException(path)

internal interface BuilderBookLifecycleHost {
    fun ensureOperationalContext(player: Player)
    fun ensureCopyPermission(player: Player)
    fun currentClipboard(playerId: UUID): BuilderClipboard?
    fun currentSelection(player: Player): BuilderSelection?
    fun currentSelectionPoints(player: Player): BuilderSelectionPoints
    fun startJournaledOperation(player: Player, plan: BuilderPlan, plannedMode: GameMode)
    fun localJournalRecord(operationId: UUID): BuilderJournalRecord?
    fun awaitingPlayerRecovery(operationId: UUID): Boolean
    fun recoveryInProgress(): Boolean
    fun send(player: Player, path: String, values: Map<String, Component> = emptyMap())
}

internal data class BuilderBookLifecycleHealth(
    val deliveryWaitingForSpace: Int,
    val reservationReleaseBacklog: Int,
    val registryReady: Boolean,
    val registryFailed: Boolean,
    val draftJournalReady: Boolean,
    val draftJournalFailed: Boolean,
    val recoveryBlocked: Boolean,
)

/**
 * Owns the complete durable build-book contract lifecycle.
 *
 * Selection, clipboard capture and world mutation stay in the builder runtime;
 * minting, delivery, auction handoff and anti-duplication recovery stay here so
 * their locks and persistent state cannot be cleaned up independently.
 */
internal class BuilderBookLifecycle(
    private val config: BuilderToolsConfig,
    private val messages: LocalizedMiniMessage,
    private val taskScope: LifecycleTaskScope,
    private val storageExecutor: ExecutorService,
    private val operationLocks: BuilderOperationLocks,
    private val host: BuilderBookLifecycleHost,
    draftJournal: BuilderDraftJournal,
) : AutoCloseable {
    private data class PendingMint(
        val kind: BuilderBookMintKind,
        val sourceBlueprintId: UUID,
        val sourceInstanceId: UUID?,
        val sourceInstanceGeneration: Int?,
        val blueprint: BuilderBookBlueprint,
        val outputInstanceId: UUID,
        val expiresAtMillis: Long,
    )

    private val pricing = BuilderBookPricing(config)
    private val debugLine = StructuredDebugLine("ARC_BUILDER_TOOLS")
    private val drafts = BuilderDraftLifecycle(
        config = config,
        messages = messages,
        taskScope = taskScope,
        storageExecutor = storageExecutor,
        operationLocks = operationLocks,
        host = host,
        journal = draftJournal,
    )
    private val registry: BuilderBookRegistry? = if (config.bookContractsEnabled) {
        runCatching {
            BuilderBookSqlRegistry(
                SqlRuntime.create(config.bookSqlConfig().connection(), "arc-builder-books"),
            )
        }.onFailure { failure ->
            error("Builder-book MySQL runtime could not be created: type=${BuilderToolsFailureType.of(failure)}")
        }.getOrNull()
    } else {
        null
    }
    private val releaseQueue = registry?.let { activeRegistry ->
        BuilderBookReleaseQueue(
            release = activeRegistry.oneTimeUses::release,
            runSync = { action -> taskScope.runSync(action) },
            onPending = { claim, result, failure ->
                warn(
                    "Builder-book reservation release queued for retry: instance={} operation={} type={} result={}",
                    claim.identity.useId,
                    claim.claimId,
                    BuilderToolsFailureType.of(failure),
                    result,
                )
            },
            onRecovered = { claim ->
                info(
                    "Builder-book reservation release recovered: instance={} operation={}",
                    claim.identity.useId,
                    claim.claimId,
                )
            },
            onCallbackFailure = { failure ->
                error(
                    "Builder-book reservation release callback failed: type=${BuilderToolsFailureType.of(failure)}",
                )
            },
        )
    }
    private val mintCoordinator = registry?.let { registry ->
        BuilderBookMintCoordinator(
            registry = registry,
            wallet = RedisEconomyBuilderBookWallet(),
            runSync = { action -> taskScope.runSync(action) },
            onManualReview = { mint ->
                error(
                    "Builder-book mint requires manual review: " +
                        "transaction=${mint.transactionId} player=${mint.playerId} status=${mint.status}",
                )
            },
        )
    }
    private val statusVerifier = registry?.let { registry ->
        BuilderBookStatusVerifier(
            loadInstance = registry::loadInstance,
            loadBlueprint = registry::loadBlueprint,
            runSync = { action -> taskScope.runSync(action) },
        )
    }
    private val auctionCoordinator = registry?.let { registry ->
        val serverName = ARC.serverName ?: return@let null
        BuilderBookAuctionCoordinator(
            registry = registry,
            portProvider = { HookRegistry.auctionHook },
            serverName = serverName,
            runSync = { action -> taskScope.runSync(action) },
            send = { player, path, values ->
                send(
                    player,
                    path,
                    values.mapValues { (key, value) ->
                        if (key == "price") moneyLabel(value) else messages.literal(value)
                    },
                )
            },
            lock = operationLocks::tryBookLock,
            unlock = operationLocks::unlockBook,
        )
    }
    private val pendingMints = mutableMapOf<UUID, PendingMint>()
    private val deliveryWaitingForSpace = mutableSetOf<UUID>()
    private val deliveryRecoveries = mutableSetOf<UUID>()
    private var registryReady = !config.bookContractsEnabled
    private var registryFailed = config.bookContractsEnabled && registry == null
    private var recoveryBlocked = false
    private var closed = false

    fun start() {
        check(!closed) { "Builder-book lifecycle is closed" }
        checkNotNull(
            taskScope.runTimer(100L, 100L) {
                drafts.retryLockedPlayers()
                releaseQueue?.retryPending()
                if (config.bookContractsEnabled) {
                    (operationLocks.bookLockedPlayerIds() + deliveryWaitingForSpace).toList()
                        .mapNotNull(Bukkit::getPlayer)
                        .filter(Player::isOnline)
                        .forEach(::recoverDeliveries)
                    if (registryReady) {
                        Bukkit.getOnlinePlayers().forEach { player -> auctionCoordinator?.onPlayerAvailable(player) }
                    }
                }
            },
        ) { "Builder-book delivery retry task was not scheduled" }
        drafts.start()
        initializeContracts()
    }

    fun health(): BuilderBookLifecycleHealth {
        val draftHealth = drafts.health()
        return BuilderBookLifecycleHealth(
            deliveryWaitingForSpace = deliveryWaitingForSpace.size,
            reservationReleaseBacklog = releaseQueue?.pendingCount ?: 0,
            registryReady = registryReady,
            registryFailed = registryFailed,
            draftJournalReady = draftHealth.ready,
            draftJournalFailed = draftHealth.failed,
            recoveryBlocked = recoveryBlocked || draftHealth.recoveryBlocked,
        )
    }

    fun ensureAvailable(player: Player) {
        if (!player.hasPermission("arc.build.book.use")) fail("errors.no-permission")
        host.ensureOperationalContext(player)
    }

    fun handleCommand(player: Player, args: List<String>) {
        when (args.firstOrNull()?.lowercase(Locale.ROOT)) {
            null, "guide", "help" -> showGuide(player)
            "status" -> showStatus(player)
            "draft" -> drafts.createDraft(player, args.drop(1))
            "activate" -> prepareActivation(player)
            "copy" -> prepareCopy(player)
            "sell" -> sell(player, args.getOrNull(1))
            "confirm" -> confirmMint(player)
            "cancel" -> cancelMint(player)
            else -> showGuide(player)
        }
    }

    fun verifySchematic(data: BuildBookData) {
        val expectedFile = data.schematicSha256 ?: fail("book.invalid")
        val expectedContent = data.contentSha256 ?: fail("book.invalid")
        val actualFile = PlayerBuildBookStore.schematicSha256(data.buildingId) ?: fail("book.invalid")
        val actualContent = PlayerBuildBookStore.contentSha256(data.buildingId) ?: fail("book.invalid")
        if (actualFile != expectedFile || actualContent != expectedContent) fail("book.invalid")
    }

    fun reserveForBuild(player: Player, plan: BuilderPlan, plannedMode: GameMode) {
        val activeRegistry = requireRegistry()
        val serverName = ARC.serverName ?: run {
            operationLocks.unlock(plan)
            fail("book.registry-unavailable")
        }
        operationLocks.lockBook(player.uniqueId)
        activeRegistry.oneTimeUses.claim(BuilderBookOneTimeUse.request(plan, serverName)).whenComplete { result, failure ->
            taskScope.runSync {
                if (failure != null || result == null) {
                    releasePlanReservation(plan)
                    operationLocks.unlockBook(player.uniqueId)
                    operationLocks.unlock(plan)
                    send(player, "book.registry-unavailable")
                    return@runSync
                }
                if (result !is OneTimeUseClaimResult.Acquired) {
                    operationLocks.unlockBook(player.uniqueId)
                    operationLocks.unlock(plan)
                    send(player, if (result == OneTimeUseClaimResult.IdentityConflict) "book.stale" else "book.duplicate")
                    return@runSync
                }
                if (!player.isOnline) {
                    releasePlanReservation(plan)
                    operationLocks.unlockBook(player.uniqueId)
                    operationLocks.unlock(plan)
                    return@runSync
                }
                operationLocks.unlockBook(player.uniqueId)
                host.startJournaledOperation(player, plan, plannedMode)
            }
        }
    }

    fun releasePlanReservation(plan: BuilderPlan) {
        if (plan.bookInstanceId == null) return
        val queue = releaseQueue ?: run {
            recoveryBlocked = true
            return
        }
        val serverName = ARC.serverName ?: run {
            recoveryBlocked = true
            return
        }
        queue.request(BuilderBookOneTimeUse.claim(plan, serverName))
    }

    fun commitPlanReservation(plan: BuilderPlan, complete: (Boolean, Throwable?) -> Unit) {
        val activeRegistry = registry
        val serverName = ARC.serverName
        if (activeRegistry == null || serverName == null) {
            complete(false, IllegalStateException("Builder-book registry identity is unavailable"))
            return
        }
        activeRegistry.oneTimeUses.commit(BuilderBookOneTimeUse.claim(plan, serverName)).whenComplete { consumed, failure ->
            taskScope.runSync {
                val accepted = failure == null &&
                    (consumed == OneTimeUseCommitResult.COMMITTED || consumed == OneTimeUseCommitResult.ALREADY_COMMITTED)
                complete(
                    accepted,
                    failure ?: if (accepted) null else IllegalStateException("Builder-book reservation commit was rejected: $consumed"),
                )
            }
        }
    }

    fun onPlayerAvailable(player: Player) {
        if (operationLocks.isRecoveryLocked(player.uniqueId)) return
        drafts.onPlayerAvailable(player)
        recoverDeliveries(player)
        if (registryReady) auctionCoordinator?.onPlayerAvailable(player)
    }

    fun onPlayerQuit(playerId: UUID) {
        pendingMints.remove(playerId)
        drafts.onPlayerQuit(playerId)
    }

    fun onGeneralRecoveryFinished() {
        if (registryReady) reconcileReservations()
    }

    fun rejectUnsafeAuctionSale(player: Player) {
        send(player, "book.auction-use-safe-command")
    }

    private fun showGuide(player: Player) {
        messages.renderLines("book.guide", locale(player)).forEach(player::sendMessage)
        showStatus(player)
    }

    private fun showStatus(player: Player) {
        if (drafts.hasPending(player.uniqueId)) {
            drafts.showPendingStatus(player)
            return
        }
        val now = System.currentTimeMillis()
        val playerId = player.uniqueId
        val quote = pendingMints[playerId]?.takeIf { it.expiresAtMillis > now }
        if (quote == null) pendingMints.remove(playerId)
        val held = BuildBookCodec.read(player.inventory.itemInMainHand)
        val heldAuctionToken = BuilderBookAuctionTokenCodec.read(player.inventory.itemInMainHand)
        val presentedIdentity = BuilderBookPresentedIdentity.from(held)
        if (BuilderBookStatusLookupPolicy.shouldVerify(quote != null, presentedIdentity, heldAuctionToken != null)) {
            val expectedIdentity = checkNotNull(presentedIdentity)
            if (!registryReady) {
                send(player, if (registryFailed) "book.registry-unavailable" else "book.registry-starting")
                return
            }
            val verifier = statusVerifier
            if (verifier == null) {
                send(player, "book.registry-unavailable")
                return
            }
            val start = verifier.verify(
                playerId = playerId,
                expected = expectedIdentity,
                currentIdentity = {
                    val current = player.inventory.itemInMainHand
                    if (BuilderBookAuctionTokenCodec.read(current) != null) null
                    else BuilderBookPresentedIdentity.from(BuildBookCodec.read(current))
                },
                complete = status@{ verification ->
                    if (!player.isOnline) return@status
                    if (pendingMints[playerId]?.expiresAtMillis?.let { it > System.currentTimeMillis() } == true) {
                        showStatus(player)
                        return@status
                    }
                    when (verification) {
                        is BuilderBookStatusVerification.Active -> send(
                            player,
                            "book.status.active",
                            mapOf(
                                "name" to displayTitle(verification.blueprint.title),
                                "price" to moneyLabel(formatMinor(verification.blueprint.issuePriceMinor)),
                            ),
                        )
                        BuilderBookStatusVerification.Stale -> send(player, "book.stale")
                        BuilderBookStatusVerification.SourceChanged -> send(player, "book.status.changed")
                        BuilderBookStatusVerification.RegistryUnavailable -> send(player, "book.registry-unavailable")
                    }
                },
            )
            when (start) {
                BuilderBookStatusLookupStart.STARTED -> send(player, "book.status.checking")
                BuilderBookStatusLookupStart.ALREADY_PENDING -> Unit
                BuilderBookStatusLookupStart.CLOSED -> send(player, "book.registry-unavailable")
            }
            return
        }
        val previewOpen = held?.takeIf { it.draft }?.let { BuildingManager.hasExactOpenPreview(player, it) } == true
        val clipboard = host.currentClipboard(playerId)
        val selection = host.currentSelection(player)
        val selectionPoints = host.currentSelectionPoints(player)
        val stage = BuilderBookJourney.resolve(
            BuilderBookJourneySnapshot(
                hasQuote = quote != null,
                deliveryPending = held?.deliveryPending == true,
                auctionLocked = heldAuctionToken != null,
                draft = held?.draft == true,
                previewOpen = previewOpen,
                active = held?.available == true,
                hasClipboard = clipboard != null,
                hasSelection = selection != null,
                hasFirstPoint = selectionPoints.first != null,
                hasSecondPoint = selectionPoints.second != null,
            ),
        )
        when (stage) {
            BuilderBookJourneyStage.QUOTE -> send(
                player,
                stage.messagePath,
                mapOf(
                    "price" to moneyLabel(formatMinor(checkNotNull(quote).blueprint.issuePriceMinor)),
                    "seconds" to messages.literal(((quote.expiresAtMillis - now) / 1_000L).coerceAtLeast(1L)),
                ),
            )
            BuilderBookJourneyStage.DELIVERY,
            BuilderBookJourneyStage.AUCTION_LOCKED,
            BuilderBookJourneyStage.START,
            -> send(player, stage.messagePath)
            BuilderBookJourneyStage.FIRST_POINT,
            BuilderBookJourneyStage.SECOND_POINT,
            -> {
                val point = checkNotNull(
                    if (stage == BuilderBookJourneyStage.FIRST_POINT) selectionPoints.first else selectionPoints.second,
                )
                send(
                    player,
                    stage.messagePath,
                    mapOf(
                        "x" to messages.literal(point.x),
                        "y" to messages.literal(point.y),
                        "z" to messages.literal(point.z),
                    ),
                )
            }
            BuilderBookJourneyStage.PREVIEW,
            BuilderBookJourneyStage.DRAFT,
            -> send(player, stage.messagePath, mapOf("name" to displayTitle(checkNotNull(held).title)))
            BuilderBookJourneyStage.ACTIVE -> send(
                player,
                stage.messagePath,
                mapOf(
                    "name" to displayTitle(checkNotNull(held).title),
                    "price" to moneyLabel(formatMinor(checkNotNull(held.issuePriceMinor))),
                ),
            )
            BuilderBookJourneyStage.CLIPBOARD -> send(
                player,
                stage.messagePath,
                mapOf("count" to messages.literal(checkNotNull(clipboard).blocks.size)),
            )
            BuilderBookJourneyStage.SELECTION -> send(
                player,
                stage.messagePath,
                mapOf(
                    "x" to messages.literal(checkNotNull(selection).sizeX),
                    "y" to messages.literal(selection.sizeY),
                    "z" to messages.literal(selection.sizeZ),
                    "volume" to messages.literal(selection.volume),
                ),
            )
        }
    }

    private fun prepareActivation(player: Player) {
        if (!player.hasPermission("arc.build.book.create")) fail("errors.no-permission")
        val activeRegistry = requireRegistry()
        val held = player.inventory.itemInMainHand
        val data = BuildBookCodec.read(held)?.takeIf { it.draft } ?: fail("book.draft-required")
        if (held.amount != 1) fail("book.duplicate")
        if (data.creatorId != player.uniqueId) fail("book.creator-only")
        requireExactDraftPreview(player, data)
        verifySchematic(data)
        val blueprintId = checkNotNull(data.blueprintId)
        activeRegistry.loadBlueprint(blueprintId).whenComplete { existing, failure ->
            taskScope.runSync {
                if (!player.isOnline) return@runSync
                if (failure != null) {
                    warn("Builder-book blueprint lookup failed for {}: type={}", player.name, BuilderToolsFailureType.of(failure))
                    send(player, "book.registry-unavailable")
                    return@runSync
                }
                try {
                    requireExactDraftPreview(player, data)
                    val blueprint = if (existing != null) {
                        if (!matchesBlueprint(data, existing)) fail("book.invalid")
                        existing
                    } else {
                        val building = BuildingManager.getBuilding(data.buildingId) ?: fail("book.invalid")
                        when (val quoted = pricing.quote(player, building)) {
                            is BuilderBookQuoteResult.Ready -> BuilderBookBlueprint(
                                blueprintId = blueprintId,
                                creatorId = checkNotNull(data.creatorId),
                                creatorName = data.creatorName ?: player.name,
                                title = data.title,
                                buildingId = data.buildingId,
                                contentSha256 = checkNotNull(data.contentSha256),
                                schematicSha256 = checkNotNull(data.schematicSha256),
                                blockCount = data.blockCount ?: quoted.quote.materialItems,
                                materialTypes = quoted.quote.materialTypes,
                                materialItems = quoted.quote.materialItems,
                                materialCostMinor = quoted.quote.cost.materialCostMinor,
                                constructionFeeMinor = quoted.quote.cost.constructionFeeMinor,
                                issuePriceMinor = quoted.quote.cost.issuePriceMinor,
                                createdAtMillis = System.currentTimeMillis(),
                            ).validated()
                            BuilderBookQuoteResult.ShopUnavailable -> fail("book.shop-unavailable")
                            is BuilderBookQuoteResult.MaterialsUnavailable -> fail(
                                "book.material-unavailable",
                                mapOf("materials" to materialsSummary(player, quoted.materials)),
                            )
                            BuilderBookQuoteResult.LimitExceeded -> fail("book.price-limit")
                        }
                    }
                    pendingMints[player.uniqueId] = PendingMint(
                        kind = BuilderBookMintKind.CREATE,
                        sourceBlueprintId = blueprintId,
                        sourceInstanceId = null,
                        sourceInstanceGeneration = null,
                        blueprint = blueprint,
                        outputInstanceId = UUID.randomUUID(),
                        expiresAtMillis = System.currentTimeMillis() + config.planTtl.toMillis(),
                    )
                    sendMintQuote(player, blueprint, "activation")
                } catch (userFailure: BuilderUserFailure) {
                    send(player, userFailure.path, userFailure.values)
                } catch (unexpected: Throwable) {
                    error("Builder-book activation quote failed for ${player.name}", unexpected)
                    send(player, "book.failed")
                }
            }
        }
    }

    private fun prepareCopy(player: Player) {
        if (!player.hasPermission("arc.build.book.create")) fail("errors.no-permission")
        val activeRegistry = requireRegistry()
        val held = player.inventory.itemInMainHand
        val data = BuildBookCodec.read(held)?.takeIf { it.available } ?: fail("book.active-required")
        if (held.amount != 1) fail("book.duplicate")
        if (BuilderBookAuctionTokenCodec.read(held) != null) fail("book.auction-locked")
        if (player.inventory.firstEmpty() == -1) fail("book.inventory-full")
        verifySchematic(data)
        val instanceId = checkNotNull(data.instanceId)
        activeRegistry.loadInstance(instanceId).whenComplete { instance, instanceFailure ->
            taskScope.runSync instanceLookup@{
                if (!player.isOnline) return@instanceLookup
                if (instanceFailure != null || instance == null) {
                    send(player, if (instanceFailure == null) "book.duplicate" else "book.registry-unavailable")
                    return@instanceLookup
                }
                if (
                    instance.status != BuilderBookInstanceStatus.AVAILABLE || instance.blueprintId != data.blueprintId ||
                    instance.ownerId != player.uniqueId || instance.generation != data.instanceGeneration
                ) {
                    send(player, "book.stale")
                    return@instanceLookup
                }
                activeRegistry.loadBlueprint(instance.blueprintId).whenComplete { blueprint, blueprintFailure ->
                    taskScope.runSync blueprintLookup@{
                        if (!player.isOnline) return@blueprintLookup
                        if (blueprintFailure != null || blueprint == null) {
                            send(player, if (blueprintFailure == null) "book.invalid" else "book.registry-unavailable")
                            return@blueprintLookup
                        }
                        if (!matchesBlueprint(data, blueprint)) {
                            send(player, "book.invalid")
                            return@blueprintLookup
                        }
                        pendingMints[player.uniqueId] = PendingMint(
                            kind = BuilderBookMintKind.COPY,
                            sourceBlueprintId = blueprint.blueprintId,
                            sourceInstanceId = instanceId,
                            sourceInstanceGeneration = data.instanceGeneration,
                            blueprint = blueprint,
                            outputInstanceId = UUID.randomUUID(),
                            expiresAtMillis = System.currentTimeMillis() + config.planTtl.toMillis(),
                        )
                        sendMintQuote(player, blueprint, "copy")
                    }
                }
            }
        }
    }

    private fun sell(player: Player, rawPrice: String?) {
        if (!player.hasPermission("arc.build.book.sell")) fail("errors.no-permission")
        requireRegistry()
        if (operationLocks.isPlayerLocked(player.uniqueId)) fail("errors.busy")
        val price = BuilderBookAuctionPrice.parse(rawPrice) ?: fail("book.auction-price")
        val held = player.inventory.itemInMainHand
        val data = BuildBookCodec.read(held)?.takeIf { it.available } ?: fail("book.active-required")
        if (held.amount != 1) fail("book.duplicate")
        if (BuilderBookAuctionTokenCodec.read(held) != null) fail("book.auction-locked")
        verifySchematic(data)
        val coordinator = auctionCoordinator ?: fail("book.auction-unavailable")
        coordinator.sell(player, data, held.clone(), price)
    }

    private fun confirmMint(player: Player) {
        val activeRegistry = requireRegistry()
        val coordinator = mintCoordinator ?: fail("book.registry-unavailable")
        if (operationLocks.isPlayerLocked(player.uniqueId)) fail("errors.busy")
        val pending = pendingMints[player.uniqueId] ?: fail("book.quote-expired")
        if (pending.expiresAtMillis <= System.currentTimeMillis()) {
            pendingMints.remove(player.uniqueId)
            fail("book.quote-expired")
        }
        val held = player.inventory.itemInMainHand
        val data = BuildBookCodec.read(held) ?: fail("book.source-changed")
        if (!matchesPendingSource(data, pending) || held.amount != 1) fail("book.source-changed")
        if (pending.kind == BuilderBookMintKind.CREATE) requireExactDraftPreview(player, data)
        if (pending.kind == BuilderBookMintKind.COPY && player.inventory.firstEmpty() == -1) fail("book.inventory-full")
        verifySchematic(data)
        pendingMints.remove(player.uniqueId)
        operationLocks.lockBook(player.uniqueId)
        val transactionId = UUID.randomUUID()
        val transform = data.transform.validated()
        val intent = BuilderBookMint(
            transactionId = transactionId,
            kind = pending.kind,
            playerId = player.uniqueId,
            blueprint = pending.blueprint,
            instanceId = pending.outputInstanceId,
            sourceInstanceId = pending.sourceInstanceId,
            sourceInstanceGeneration = pending.sourceInstanceGeneration,
            placement = BuilderBookPlacement(transform.rotation, transform.offsetX, transform.offsetY, transform.offsetZ),
            createdAtMillis = System.currentTimeMillis(),
        ).validated()
        val sourceId = pending.sourceInstanceId
        if (sourceId == null) {
            startMint(player, intent, coordinator)
            return
        }
        val serverName = ARC.serverName ?: run {
            operationLocks.unlockBook(player.uniqueId)
            fail("book.registry-unavailable")
        }
        val sourceClaimRequest = BuilderBookOneTimeUse.request(
            instanceId = sourceId,
            expectedGeneration = checkNotNull(pending.sourceInstanceGeneration),
            blueprintId = pending.blueprint.blueprintId,
            buildingId = pending.blueprint.buildingId,
            schematicSha256 = pending.blueprint.schematicSha256,
            operationId = transactionId,
            playerId = player.uniqueId,
            serverName = serverName,
        )
        activeRegistry.oneTimeUses.claim(sourceClaimRequest).whenComplete { reservation, failure ->
            taskScope.runSync {
                if (failure != null || reservation == null) {
                    releaseSource(OneTimeUseClaim.acquired(sourceClaimRequest, newlyCreated = false))
                    operationLocks.unlockBook(player.uniqueId)
                    send(player, "book.registry-unavailable")
                } else if (reservation is OneTimeUseClaimResult.Acquired) {
                    startMint(player, intent, coordinator)
                } else {
                    operationLocks.unlockBook(player.uniqueId)
                    send(player, if (reservation == OneTimeUseClaimResult.IdentityConflict) "book.stale" else "book.duplicate")
                }
            }
        }
    }

    private fun startMint(player: Player, intent: BuilderBookMint, coordinator: BuilderBookMintCoordinator) {
        coordinator.mint(intent) { result ->
            when (result) {
                is BuilderBookMintResult.Issued -> deliverIssuedBook(player, result.mint)
                BuilderBookMintResult.Busy -> finishFailedMint(player, intent, "errors.busy", releaseSource = true)
                BuilderBookMintResult.EconomyUnavailable -> finishFailedMint(player, intent, "book.economy-unavailable", releaseSource = true)
                BuilderBookMintResult.InsufficientFunds -> finishFailedMint(player, intent, "book.insufficient-funds", releaseSource = true)
                BuilderBookMintResult.PaymentRejected -> finishFailedMint(player, intent, "book.payment-failed", releaseSource = true)
                BuilderBookMintResult.RegistryUnavailable -> finishFailedMint(player, intent, "book.registry-unavailable", releaseSource = true)
                BuilderBookMintResult.Refunded -> finishFailedMint(player, intent, "book.refunded", releaseSource = true)
                BuilderBookMintResult.ManualReview -> finishFailedMint(player, intent, "book.manual-review", releaseSource = false)
            }
        }
    }

    private fun deliverIssuedBook(player: Player, mint: BuilderBookMint) {
        if (!player.isOnline) return
        val data = registeredBookData(
            mint.blueprint,
            mint.instanceId,
            BuilderBookInstance.INITIAL_GENERATION,
            mint.placement,
        )
        val existing = inventoryBooksWithInstance(player, mint.instanceId)
        if (existing.size > 1 || (existing.size == 1 && existing.single().second != data)) {
            error("Builder-book issued item conflicts with local inventory: transaction=${mint.transactionId} instance=${mint.instanceId}")
            send(player, "book.manual-review")
            return
        }
        if (existing.isEmpty()) {
            val held = player.inventory.itemInMainHand
            val heldData = BuildBookCodec.read(held)
            if (
                mint.kind == BuilderBookMintKind.CREATE && heldData?.draft == true &&
                heldData.blueprintId == mint.blueprint.blueprintId
            ) {
                replaceOneHeldBook(player, held, BuildBookItems.create(data))
            } else {
                if (player.inventory.firstEmpty() == -1) {
                    waitForDeliverySpace(player)
                    return
                }
                check(player.inventory.addItem(BuildBookItems.create(data)).isEmpty()) {
                    "Paid builder book did not fit after preflight"
                }
            }
            player.updateInventory()
        }
        markDelivered(player, mint.instanceId, mint.transactionId, sourceBookClaim(mint), recovered = false)
    }

    private fun markDelivered(
        player: Player,
        instanceId: UUID,
        transactionId: UUID,
        sourceClaim: OneTimeUseClaim?,
        recovered: Boolean,
        finished: () -> Unit = {},
    ) {
        val activeRegistry = registry ?: return finished()
        activeRegistry.markDelivered(instanceId, transactionId, System.currentTimeMillis()).whenComplete { delivered, failure ->
            taskScope.runSync {
                if (failure != null || delivered != true) {
                    error(
                        "Builder-book delivery requires retry: transaction=$transactionId instance=$instanceId " +
                            "type=${BuilderToolsFailureType.of(failure)} result=$delivered",
                    )
                    if (player.isOnline) send(player, "book.delivery-pending")
                    finished()
                    return@runSync
                }
                if (!completeLocalDelivery(player, instanceId)) {
                    error("Builder-book delivery item disappeared while locked: transaction=$transactionId instance=$instanceId")
                    if (player.isOnline) send(player, "book.manual-review")
                    deliveryRecoveries += player.uniqueId
                    releaseSource(sourceClaim)
                    return@runSync
                }
                val previewRefreshed = sourceClaim == null && runCatching {
                    inventoryBooksWithInstance(player, instanceId).singleOrNull()?.second?.let { deliveredBook ->
                        BuildingManager.updatePendingTransform(player, deliveredBook) ==
                            PreviewTransformUpdateResult.UPDATED
                    } == true
                }.getOrElse { failure ->
                    warn(
                        "Builder-book paid delivery completed but preview refresh failed for {}: type={}",
                        player.name,
                        BuilderToolsFailureType.of(failure),
                    )
                    false
                }
                releaseSource(sourceClaim) {
                    operationLocks.unlockBook(player.uniqueId)
                    deliveryWaitingForSpace -= player.uniqueId
                    if (player.isOnline) {
                        send(
                            player,
                            when {
                                recovered -> "book.delivery-recovered"
                                sourceClaim != null -> "book.copied"
                                previewRefreshed -> "book.activated-preview"
                                else -> "book.activated"
                            },
                        )
                    }
                    finished()
                }
            }
        }
    }

    private fun finishFailedMint(
        player: Player,
        mint: BuilderBookMint,
        messagePath: String,
        releaseSource: Boolean,
    ) {
        val finish = {
            operationLocks.unlockBook(player.uniqueId)
            deliveryWaitingForSpace -= player.uniqueId
            if (player.isOnline) send(player, messagePath)
        }
        if (releaseSource) releaseSource(sourceBookClaim(mint), finish) else finish()
    }

    private fun releaseSource(claim: OneTimeUseClaim?, done: () -> Unit = {}) {
        if (claim == null) return done()
        val queue = releaseQueue ?: run {
            recoveryBlocked = true
            return done()
        }
        queue.request(claim, done)
    }

    private fun sourceBookClaim(mint: BuilderBookMint): OneTimeUseClaim? {
        val sourceId = mint.sourceInstanceId ?: return null
        val serverName = ARC.serverName ?: run {
            recoveryBlocked = true
            error("Builder-book copy source recovery has no backend identity: transaction=${mint.transactionId}")
            return null
        }
        return OneTimeUseClaim.acquired(
            BuilderBookOneTimeUse.request(
                instanceId = sourceId,
                expectedGeneration = checkNotNull(mint.sourceInstanceGeneration),
                blueprintId = mint.blueprint.blueprintId,
                buildingId = mint.blueprint.buildingId,
                schematicSha256 = mint.blueprint.schematicSha256,
                operationId = mint.transactionId,
                playerId = mint.playerId,
                serverName = serverName,
            ),
            newlyCreated = false,
        )
    }

    private fun cancelMint(player: Player) {
        if (pendingMints.remove(player.uniqueId) != null) send(player, "book.quote-cancelled")
        else fail("book.quote-expired")
    }

    private fun waitForDeliverySpace(player: Player) {
        operationLocks.unlockBook(player.uniqueId)
        if (deliveryWaitingForSpace.add(player.uniqueId)) send(player, "book.delivery-space")
    }

    private fun sendMintQuote(player: Player, blueprint: BuilderBookBlueprint, kind: String) {
        send(
            player,
            "book.quote",
            mapOf(
                "kind" to messages.render("book.quote-kind.$kind", locale(player)),
                "name" to displayTitle(blueprint.title),
                "blocks" to messages.literal(blueprint.blockCount),
                "items" to messages.literal(blueprint.materialItems),
                "types" to messages.literal(blueprint.materialTypes),
                "materials" to moneyLabel(formatMinor(blueprint.materialCostMinor)),
                "labor" to moneyLabel(formatMinor(blueprint.constructionFeeMinor)),
                "price" to moneyLabel(formatMinor(blueprint.issuePriceMinor)),
                "seconds" to messages.literal(config.planTtl.seconds),
            ),
        )
    }

    private fun matchesPendingSource(data: BuildBookData, pending: PendingMint): Boolean =
        data.blueprintId == pending.sourceBlueprintId &&
            data.instanceId == pending.sourceInstanceId &&
            data.instanceGeneration == pending.sourceInstanceGeneration &&
            ((pending.kind == BuilderBookMintKind.CREATE && data.draft) ||
                (pending.kind == BuilderBookMintKind.COPY && data.available)) &&
            matchesBlueprint(data, pending.blueprint)

    private fun requireExactDraftPreview(player: Player, data: BuildBookData) {
        if (!BuildingManager.hasExactOpenPreview(player, data)) fail("book.preview-required")
    }

    private fun matchesBlueprint(data: BuildBookData, blueprint: BuilderBookBlueprint): Boolean =
        data.blueprintId == blueprint.blueprintId &&
            data.creatorId == blueprint.creatorId &&
            data.creatorName == blueprint.creatorName &&
            data.title == blueprint.title &&
            data.buildingId == blueprint.buildingId &&
            data.contentSha256 == blueprint.contentSha256 &&
            data.schematicSha256 == blueprint.schematicSha256 &&
            data.blockCount == blueprint.blockCount &&
            (data.issuePriceMinor == null || data.issuePriceMinor == blueprint.issuePriceMinor)

    private fun registeredBookData(
        blueprint: BuilderBookBlueprint,
        instanceId: UUID,
        instanceGeneration: Int,
        placement: BuilderBookPlacement,
        deliveryPending: Boolean = true,
    ): BuildBookData = BuildBookData(
        buildingId = blueprint.buildingId,
        title = blueprint.title,
        transform = BuildBookTransform(placement.rotation, placement.offsetX, placement.offsetY, placement.offsetZ),
        playerCreated = true,
        creatorId = blueprint.creatorId,
        creatorName = blueprint.creatorName,
        blueprintId = blueprint.blueprintId,
        instanceId = instanceId,
        instanceGeneration = instanceGeneration,
        issuePriceMinor = blueprint.issuePriceMinor,
        contentSha256 = blueprint.contentSha256,
        schematicSha256 = blueprint.schematicSha256,
        deliveryPending = deliveryPending,
        blockCount = blueprint.blockCount,
        cooldownSeconds = 0,
    ).validated()

    private fun inventoryBooksWithInstance(player: Player, instanceId: UUID): List<Pair<Int, BuildBookData>> =
        (0 until player.inventory.size).mapNotNull { slot ->
            val data = player.inventory.getItem(slot)?.let(BuildBookCodec::read) ?: return@mapNotNull null
            if (data.instanceId == instanceId) slot to data else null
        }

    private fun completeLocalDelivery(player: Player, instanceId: UUID): Boolean {
        val matches = inventoryBooksWithInstance(player, instanceId)
        if (matches.size != 1) return false
        val (slot, data) = matches.single()
        if (!data.deliveryPending) return true
        val item = player.inventory.getItem(slot) ?: return false
        player.inventory.setItem(slot, BuildBookCodec.update(item, data.copy(deliveryPending = false).validated()))
        player.updateInventory()
        return true
    }

    private fun localPendingBookInstances(player: Player): List<UUID> = player.inventory.contents
        .filterNotNull()
        .mapNotNull { BuildBookCodec.read(it)?.takeIf(BuildBookData::deliveryPending)?.instanceId }

    private fun requireRegistry(): BuilderBookRegistry {
        if (!config.bookContractsEnabled) fail("book.contracts-disabled")
        if (registryFailed) fail("book.registry-unavailable")
        if (!registryReady) fail("book.registry-starting")
        return registry ?: fail("book.registry-unavailable")
    }

    private fun initializeContracts() {
        val activeRegistry = registry ?: return
        activeRegistry.initialize().whenComplete { _, failure ->
            taskScope.runSync {
                if (failure != null) {
                    registryFailed = true
                    error("Builder-book MySQL initialization failed: type=${BuilderToolsFailureType.of(failure)}")
                    return@runSync
                }
                registryReady = true
                registryFailed = false
                info(debugLine.line("event" to "book_registry_ready"))
                auctionCoordinator?.start()
                recoverOpenMints()
                Bukkit.getOnlinePlayers().forEach(::recoverDeliveries)
                if (!host.recoveryInProgress()) reconcileReservations()
            }
        }
    }

    private fun recoverOpenMints() {
        val activeRegistry = registry ?: return
        val coordinator = mintCoordinator ?: return
        activeRegistry.openMints().whenComplete { mints, failure ->
            taskScope.runSync {
                if (failure != null || mints == null) {
                    registryFailed = true
                    registryReady = false
                    error(
                        "Builder-book mint recovery scan failed: " +
                            "type=${BuilderToolsFailureType.of(failure)} result_present=${mints != null}",
                    )
                    return@runSync
                }
                mints.forEach { mint ->
                    coordinator.recover(mint) { result ->
                        when (result) {
                            is BuilderBookMintResult.Issued -> Bukkit.getPlayer(mint.playerId)
                                ?.takeIf(Player::isOnline)
                                ?.let(::recoverDeliveries)
                            BuilderBookMintResult.PaymentRejected,
                            BuilderBookMintResult.Refunded,
                            -> releaseSource(sourceBookClaim(mint))
                            BuilderBookMintResult.ManualReview -> Unit
                            else -> warn(
                                "Builder-book mint recovery incomplete: transaction={} status={} result={}",
                                mint.transactionId,
                                mint.status,
                                result::class.simpleName,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun recoverDeliveries(player: Player) {
        if (!registryReady || !player.isOnline || operationLocks.isRecoveryLocked(player.uniqueId)) return
        if (!deliveryRecoveries.add(player.uniqueId)) return
        val activeRegistry = registry ?: run {
            deliveryRecoveries -= player.uniqueId
            return
        }
        val localPending = localPendingBookInstances(player)
        if (localPending.isNotEmpty()) operationLocks.lockBook(player.uniqueId)
        activeRegistry.pendingDeliveries(player.uniqueId).whenComplete { deliveries, failure ->
            taskScope.runSync deliveryLookup@{
                if (!player.isOnline) {
                    deliveryRecoveries -= player.uniqueId
                    return@deliveryLookup
                }
                if (failure != null || deliveries == null) {
                    warn(
                        "Builder-book delivery lookup failed for {}: type={} result_present={}",
                        player.name,
                        BuilderToolsFailureType.of(failure),
                        deliveries != null,
                    )
                    deliveryRecoveries -= player.uniqueId
                    return@deliveryLookup
                }
                if (deliveries.isEmpty()) {
                    if (localPending.size == 1) {
                        reconcileLocalDeliveredBook(player, localPending.single()) {
                            deliveryRecoveries -= player.uniqueId
                        }
                    } else if (localPending.size > 1) {
                        deliveryWaitingForSpace -= player.uniqueId
                        error("Builder-book local delivery invariant failed for ${player.uniqueId}: ${localPending.size} pending items")
                        send(player, "book.manual-review")
                    } else {
                        deliveryRecoveries -= player.uniqueId
                    }
                    return@deliveryLookup
                }
                operationLocks.lockBook(player.uniqueId)
                if (deliveries.size != 1) {
                    deliveryWaitingForSpace -= player.uniqueId
                    error("Builder-book delivery invariant failed for ${player.uniqueId}: ${deliveries.size} pending instances")
                    send(player, "book.manual-review")
                    return@deliveryLookup
                }
                val delivery = deliveries.single()
                val instanceId = delivery.instance.instanceId
                if (localPending.isNotEmpty() && (localPending.size != 1 || localPending.single() != instanceId)) {
                    deliveryWaitingForSpace -= player.uniqueId
                    error(
                        "Builder-book pending item does not match authoritative delivery: " +
                            "player=${player.uniqueId} expected=$instanceId local=$localPending",
                    )
                    send(player, "book.manual-review")
                    return@deliveryLookup
                }
                val expectedData = registeredBookData(
                    delivery.blueprint,
                    instanceId,
                    delivery.instance.generation,
                    delivery.placement,
                )
                val existing = inventoryBooksWithInstance(player, instanceId)
                if (existing.size > 1 || (existing.size == 1 && existing.single().second != expectedData)) {
                    deliveryWaitingForSpace -= player.uniqueId
                    error("Builder-book recovered item conflicts with local inventory: player=${player.uniqueId} instance=$instanceId")
                    send(player, "book.manual-review")
                    return@deliveryLookup
                }
                if (existing.isEmpty()) {
                    val output = BuildBookItems.create(expectedData)
                    val held = player.inventory.itemInMainHand
                    val heldData = BuildBookCodec.read(held)
                    if (
                        delivery.sourceInstanceId == null && heldData?.draft == true &&
                        heldData.blueprintId == delivery.blueprint.blueprintId
                    ) {
                        if (held.amount > 1 && player.inventory.firstEmpty() == -1) {
                            waitForDeliverySpace(player)
                            deliveryRecoveries -= player.uniqueId
                            return@deliveryLookup
                        }
                        replaceOneHeldBook(player, held, output)
                    } else {
                        if (player.inventory.firstEmpty() == -1) {
                            waitForDeliverySpace(player)
                            deliveryRecoveries -= player.uniqueId
                            return@deliveryLookup
                        }
                        check(player.inventory.addItem(output).isEmpty()) {
                            "Recovered builder book did not fit after preflight"
                        }
                    }
                    player.updateInventory()
                }
                operationLocks.lockBook(player.uniqueId)
                deliveryWaitingForSpace -= player.uniqueId
                markDelivered(
                    player = player,
                    instanceId = instanceId,
                    transactionId = delivery.instance.transactionId,
                    sourceClaim = delivery.sourceInstanceId?.let { sourceId ->
                        val serverName = ARC.serverName ?: run {
                            recoveryBlocked = true
                            error(
                                "Builder-book delivery recovery has no backend identity: " +
                                    "transaction=${delivery.instance.transactionId}",
                            )
                            return@let null
                        }
                        OneTimeUseClaim.acquired(
                            BuilderBookOneTimeUse.request(
                                instanceId = sourceId,
                                expectedGeneration = checkNotNull(delivery.sourceInstanceGeneration),
                                blueprintId = delivery.blueprint.blueprintId,
                                buildingId = delivery.blueprint.buildingId,
                                schematicSha256 = delivery.blueprint.schematicSha256,
                                operationId = delivery.instance.transactionId,
                                playerId = player.uniqueId,
                                serverName = serverName,
                            ),
                            newlyCreated = false,
                        )
                    },
                    recovered = true,
                    finished = { deliveryRecoveries -= player.uniqueId },
                )
            }
        }
    }

    private fun reconcileLocalDeliveredBook(player: Player, instanceId: UUID, finished: () -> Unit) {
        val activeRegistry = registry ?: return finished()
        activeRegistry.loadInstance(instanceId).whenComplete { instance, failure ->
            taskScope.runSync {
                if (failure != null) {
                    error(
                        "Builder-book local delivery reconciliation failed for $instanceId: " +
                            "type=${BuilderToolsFailureType.of(failure)}",
                    )
                    finished()
                } else if (instance == null) {
                    error("Builder-book local delivery instance is missing: $instanceId")
                    send(player, "book.manual-review")
                } else if (instance.status == BuilderBookInstanceStatus.AVAILABLE) {
                    verifyCompletedLocalDelivery(player, instance, finished)
                } else {
                    error("Builder-book local delivery state is ambiguous: instance=$instanceId status=${instance.status}")
                    send(player, "book.manual-review")
                }
            }
        }
    }

    private fun verifyCompletedLocalDelivery(
        player: Player,
        instance: BuilderBookInstance,
        finished: () -> Unit,
    ) {
        val activeRegistry = registry ?: return finished()
        activeRegistry.loadMint(instance.transactionId).whenComplete { mint, failure ->
            taskScope.runSync {
                if (!player.isOnline) {
                    finished()
                    return@runSync
                }
                if (failure != null) {
                    error(
                        "Builder-book completed delivery lookup failed for ${instance.instanceId}: " +
                            "type=${BuilderToolsFailureType.of(failure)}",
                    )
                    finished()
                    return@runSync
                }
                val expected = mint
                    ?.takeIf { it.status == BuilderBookMintStatus.COMPLETED && it.instanceId == instance.instanceId }
                    ?.let { registeredBookData(it.blueprint, instance.instanceId, instance.generation, it.placement) }
                val local = inventoryBooksWithInstance(player, instance.instanceId)
                if (
                    expected == null || local.size != 1 || local.single().second != expected ||
                    !completeLocalDelivery(player, instance.instanceId)
                ) {
                    error("Builder-book completed delivery item failed authoritative verification: instance=${instance.instanceId}")
                    send(player, "book.manual-review")
                    return@runSync
                }
                operationLocks.unlockBook(player.uniqueId)
                deliveryWaitingForSpace -= player.uniqueId
                send(player, "book.delivery-recovered")
                finished()
            }
        }
    }

    private fun reconcileReservations() {
        val activeRegistry = registry ?: return
        val serverName = ARC.serverName ?: return
        activeRegistry.reservedForServer(serverName).whenComplete { reservations, failure ->
            taskScope.runSync {
                if (failure != null || reservations == null) {
                    recoveryBlocked = true
                    error(
                        "Builder-book reservation recovery failed: " +
                            "type=${BuilderToolsFailureType.of(failure)} result_present=${reservations != null}",
                    )
                    return@runSync
                }
                reservations.forEach { instance -> reconcileReservation(instance) }
            }
        }
    }

    private fun reconcileReservation(instance: BuilderBookInstance) {
        val activeRegistry = registry ?: return
        val operationId = instance.reservationOperationId ?: return
        val localRecord = host.localJournalRecord(operationId)
        if (localRecord != null && localRecord.plan.bookInstanceId == instance.instanceId) {
            if (localRecord.phase == BuilderJournalPhase.COMMITTED) {
                val serverName = instance.reservationServer ?: run {
                    recoveryBlocked = true
                    return
                }
                activeRegistry.oneTimeUses.commit(BuilderBookOneTimeUse.claim(localRecord.plan, serverName))
                    .whenComplete { consumed, failure ->
                        taskScope.runSync {
                            if (
                                failure != null ||
                                (consumed != OneTimeUseCommitResult.COMMITTED &&
                                    consumed != OneTimeUseCommitResult.ALREADY_COMMITTED)
                            ) {
                                recoveryBlocked = true
                                error(
                                    "Builder-book committed reservation could not be consumed: $operationId " +
                                        "type=${BuilderToolsFailureType.of(failure)} result=$consumed",
                                )
                            }
                        }
                    }
            } else if (!host.awaitingPlayerRecovery(operationId)) {
                releaseRecoveredReservation(activeRegistry, instance)
            }
            return
        }
        activeRegistry.loadMint(operationId).whenComplete { mint, failure ->
            taskScope.runSync {
                if (failure != null) {
                    recoveryBlocked = true
                    error(
                        "Builder-book reservation owner lookup failed: $operationId " +
                            "type=${BuilderToolsFailureType.of(failure)}",
                    )
                } else if (mint != null && mint.sourceInstanceId == instance.instanceId) {
                    if (mint.status.terminal) releaseRecoveredReservation(activeRegistry, instance)
                } else {
                    releaseRecoveredReservation(activeRegistry, instance)
                }
            }
        }
    }

    private fun releaseRecoveredReservation(activeRegistry: BuilderBookRegistry, instance: BuilderBookInstance) {
        activeRegistry.loadBlueprint(instance.blueprintId).whenComplete { blueprint, loadFailure ->
            taskScope.runSync {
                if (loadFailure != null || blueprint == null) {
                    recoveryBlocked = true
                    error(
                        "Builder-book recovered reservation blueprint could not be loaded: ${instance.reservationOperationId}",
                        loadFailure ?: IllegalStateException("blueprint missing"),
                    )
                    return@runSync
                }
                val request = BuilderBookOneTimeUse.request(
                    instanceId = instance.instanceId,
                    expectedGeneration = instance.generation,
                    blueprintId = blueprint.blueprintId,
                    buildingId = blueprint.buildingId,
                    schematicSha256 = blueprint.schematicSha256,
                    operationId = checkNotNull(instance.reservationOperationId),
                    playerId = checkNotNull(instance.reservationPlayerId),
                    serverName = checkNotNull(instance.reservationServer),
                )
                val queue = releaseQueue ?: run {
                    recoveryBlocked = true
                    error("Builder-book release recovery queue is unavailable: ${instance.reservationOperationId}")
                    return@runSync
                }
                queue.request(OneTimeUseClaim.acquired(request, newlyCreated = false))
            }
        }
    }

    private fun materialsSummary(player: Player, materials: List<Material>): Component {
        val first = checkNotNull(materials.firstOrNull()) { "Unavailable builder-book materials cannot be empty" }
        return BuilderMaterialPresentation.label(player, first).append(
            messages.literal(if (materials.size > 1) " +${materials.size - 1}" else ""),
        )
    }

    private fun displayTitle(title: String): Component = messages.literal(BuildBookItems.compactTitle(title, 22))

    private fun formatMinor(amount: Long): String = String.format(Locale.US, "%,.2f", amount / 100.0)

    private fun moneyLabel(formatted: String): Component = messages.literal(formatted)
        .append(Component.space())
        .append(Component.text("💰", NamedTextColor.WHITE))

    private fun replaceOneHeldBook(player: Player, held: ItemStack, replacement: ItemStack) {
        if (held.amount == 1) {
            player.inventory.setItemInMainHand(replacement)
            return
        }
        if (player.inventory.firstEmpty() == -1) fail("book.inventory-full")
        player.inventory.setItemInMainHand(held.clone().also { it.amount = held.amount - 1 })
        check(player.inventory.addItem(replacement).isEmpty()) { "Owned build book did not fit after preflight" }
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

    private fun send(player: Player, path: String, values: Map<String, Component> = emptyMap()) {
        host.send(player, path, values)
    }

    private fun locale(player: Player): String = player.locale().toLanguageTag()

    private fun fail(path: String, values: Map<String, Component> = emptyMap()): Nothing =
        throw BuilderUserFailure(path, values)

    override fun close() {
        if (closed) return
        closed = true
        auctionCoordinator?.close()
        statusVerifier?.close()
        drafts.close()
        releaseQueue?.close()
        pendingMints.clear()
        deliveryWaitingForSpace.clear()
        deliveryRecoveries.clear()
        mintCoordinator?.clear()
        registry?.close()
    }
}
