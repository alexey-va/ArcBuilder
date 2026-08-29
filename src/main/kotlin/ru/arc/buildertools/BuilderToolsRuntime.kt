package ru.arc.buildertools

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.type.Leaves
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.ARC
import ru.arc.autobuild.BuildBookCodec
import ru.arc.autobuild.BuildBookData
import ru.arc.autobuild.gui.BuildBookEditorGui
import ru.arc.autobuild.BuildingManager
import ru.arc.autobuild.ConstructionSite
import ru.arc.core.LifecycleTaskScope
import ru.arc.hooks.HookRegistry
import ru.arc.observability.RuntimeHealthContribution
import ru.arc.observability.RuntimeHealthState
import ru.arc.observability.StructuredDebugLine
import ru.arc.paper.playerstate.PaperPlayerStateCodec
import ru.arc.paper.playerstate.PaperPlayerStateService
import ru.arc.text.LocalizedMiniMessage
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import ru.arc.util.BlockUtils.rotateBlockData
import com.sk89q.worldedit.bukkit.BukkitAdapter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal sealed interface BuilderBookPlacementResult {
    data object Unchanged : BuilderBookPlacementResult
    data object SkippedUnsafe : BuilderBookPlacementResult
    data class Change(
        val block: BuilderBlockChange,
        val refund: ItemStack?,
    ) : BuilderBookPlacementResult
}

internal class BuilderToolsRuntime(
    private val plugin: JavaPlugin,
    private val config: BuilderToolsConfig,
    private val taskScope: LifecycleTaskScope = LifecycleTaskScope(),
    private val displayRenderer: BuilderDisplayRenderer = BuilderBlockDisplayRenderer(
        plugin,
        config.previewMaxPlanParticles,
        config.messages(),
        taskScope,
    ),
    blockDataRotation: BuilderBlockDataRotation = PaperBuilderBlockDataRotation,
    draftStorage: BuilderDraftStorage = PlayerBuildBookDraftStorage,
    bookSchematicVerifier: BuilderBookSchematicVerifier = PlayerBuildBookSchematicVerifier,
    private val bookReplacementRefund: (Block) -> ItemStack? = BuilderDeconstructionRefunds::fromSilkTouch,
) : Listener, CommandExecutor, TabCompleter, AutoCloseable {
    private val messages: LocalizedMiniMessage = config.messages()
    private val shop = BuilderShopCoordinator(config, messages)
    private val safety = BuilderBlockSafety(plugin, config.replaceableMaterials)
    private val coreProtect = BuilderCoreProtectBridge.resolve()
    private val journal = BuilderJournalStore(plugin.dataPath, config.maxChanges)
    private val stateService = PaperPlayerStateService()
    private val stateCodec = PaperPlayerStateCodec()
    private val storageExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "arc-builder-tools-storage").apply { isDaemon = true }
    }
    private val debugLine = StructuredDebugLine("ARC_BUILDER_TOOLS")
    private val wandKey = org.bukkit.NamespacedKey(plugin, "builder_selector")
    private val selections = BuilderSelectionController(
        previewRadius = config.previewRadius,
        previewSpacing = config.previewSpacing,
        maximumOutlinePoints = config.previewMaxSelectionParticles,
    )
    private val fillController = BuilderFillController(
        safety = safety,
        maximumChanges = config.maxChanges,
        host = object : BuilderFillHost {
            override fun ensurePermission(player: Player) = ensureFeaturePermission(player, BuilderFeature.FILL)

            override fun requiredSelection(player: Player): BuilderSelection = this@BuilderToolsRuntime.requiredSelection(player)

            override fun world(worldId: UUID): World = requireWorld(worldId)

            override fun placementData(material: Material) = this@BuilderToolsRuntime.placementData(material)

            override fun ensureMutable(player: Player, block: Block) = this@BuilderToolsRuntime.ensureMutable(player, block)

            override fun ensurePlacement(player: Player, block: Block, material: Material) =
                this@BuilderToolsRuntime.ensureMutable(player, block, material)

            override fun createPlan(
                player: Player,
                changes: List<BuilderBlockChange>,
                costs: List<BuilderItemAmount>,
            ): BuilderPlan = newPlan(player, BuilderPlanKind.FILL, changes, costs, emptyList())

            override fun fail(path: String): Nothing = throw BuilderUserFailure(path)
        },
    )
    private val fenceConnectionController = BuilderFenceConnectionController(
        maximumChanges = config.maxChanges,
        host = object : BuilderFenceConnectionHost {
            override fun ensurePermission(player: Player) =
                ensureFeaturePermission(player, BuilderFeature.FENCE_DISCONNECT)

            override fun requiredSelection(player: Player): BuilderSelection = this@BuilderToolsRuntime.requiredSelection(player)

            override fun world(worldId: UUID): World = requireWorld(worldId)

            override fun ensureMutable(player: Player, block: Block) = this@BuilderToolsRuntime.ensureMutable(player, block)

            override fun createPlan(player: Player, changes: List<BuilderBlockChange>): BuilderPlan =
                newPlan(player, BuilderPlanKind.FENCE_DISCONNECT, changes, emptyList(), emptyList())

            override fun fail(path: String): Nothing = throw BuilderUserFailure(path)
        },
    )
    private val clipboardController = BuilderClipboardController(
        safety = safety,
        selections = selections,
        maximumBlocks = config.maxClipboardBlocks,
        clipboardTtl = config.clipboardTtl,
        blockDataRotation = blockDataRotation,
        host = object : BuilderClipboardHost {
            override fun ensureCopyPermission(player: Player) = ensureFeaturePermission(player, BuilderFeature.COPY)

            override fun ensurePastePermission(player: Player) = ensureFeaturePermission(player, BuilderFeature.PASTE)

            override fun requiredSelection(player: Player): BuilderSelection = this@BuilderToolsRuntime.requiredSelection(player)

            override fun world(worldId: UUID): World = requireWorld(worldId)

            override fun ensureInRangeAndLoaded(player: Player, block: Block) =
                this@BuilderToolsRuntime.ensureInRangeAndLoaded(player, block)

            override fun ensureProtected(player: Player, block: Block) =
                this@BuilderToolsRuntime.ensureProtected(player, block)

            override fun ensureMutable(player: Player, block: Block) = this@BuilderToolsRuntime.ensureMutable(player, block)

            override fun ensurePlacement(player: Player, block: Block, material: Material) =
                this@BuilderToolsRuntime.ensureMutable(player, block, material)

            override fun createPastePlan(
                player: Player,
                changes: List<BuilderBlockChange>,
                costs: List<BuilderItemAmount>,
                rewards: List<BuilderItemAmount>,
                skippedUnsafeBlocks: Int,
            ): BuilderPlan = newPlan(
                player,
                BuilderPlanKind.PASTE,
                changes,
                costs,
                rewards,
                skippedUnsafeBlocks = skippedUnsafeBlocks,
            )

            override fun fail(path: String): Nothing = throw BuilderUserFailure(path)
        },
    )
    private val deconstructionController = BuilderDeconstructionController(
        safety = safety,
        maximumChanges = config.maxChanges,
        host = object : BuilderDeconstructionHost {
            override fun ensurePermission(player: Player) = ensureFeaturePermission(player, BuilderFeature.DECONSTRUCT)

            override fun requiredSelection(player: Player): BuilderSelection = this@BuilderToolsRuntime.requiredSelection(player)

            override fun world(worldId: UUID): World = requireWorld(worldId)

            override fun ensureMutable(player: Player, block: Block) = this@BuilderToolsRuntime.ensureMutable(player, block)

            override fun createPlan(
                player: Player,
                changes: List<BuilderBlockChange>,
                rewards: List<BuilderItemAmount>,
                toolFingerprint: String?,
                toolDamage: Int,
                skippedUnsafeBlocks: Int,
            ): BuilderPlan = newPlan(
                player = player,
                kind = BuilderPlanKind.DECONSTRUCT,
                changes = changes,
                costs = emptyList(),
                rewards = rewards,
                toolFingerprint = toolFingerprint,
                toolDamage = toolDamage,
                skippedUnsafeBlocks = skippedUnsafeBlocks,
            )

            override fun fail(path: String): Nothing = throw BuilderUserFailure(path)
        },
    )
    private val previews: BuilderPreviewSessions
    private val crown: BuilderCrownController
    private val books: BuilderBookLifecycle
    private val operationLocks: BuilderOperationLocks
    private val playerRecoveries: BuilderPlayerRecoveryCoordinator
    private val committedRecords = mutableMapOf<UUID, BuilderJournalRecord>()
    private val consumedUndoSources = mutableSetOf<UUID>()
    private var recovering = true
    private var recoveryBlocked = false
    private var closed = false
    private val runtimeHealth = AtomicReference(
        RuntimeHealthContribution(state = RuntimeHealthState.STARTING),
    )

    init {
        require(!config.requireLands || HookRegistry.landsHook != null) {
            "Builder-tools requires the active Lands integration"
        }
        require(!config.requireCoreProtect || coreProtect != null) {
            "Builder-tools requires the active CoreProtect API"
        }
        operationLocks = BuilderOperationLocks(plugin)
        var initializedPreviews: BuilderPreviewSessions? = null
        var initializedCrown: BuilderCrownController? = null
        var initializedBooks: BuilderBookLifecycle? = null
        var initializedPlayerRecoveries: BuilderPlayerRecoveryCoordinator? = null
        try {
            BuildingManager.installPreviewBridge(displayRenderer)
            Bukkit.getPluginManager().registerEvents(this, plugin)
            previews = BuilderPreviewSessions(
                taskScope = taskScope,
                periodTicks = config.previewPeriodTicks,
                onlinePlayers = { Bukkit.getOnlinePlayers() },
                canRender = { player ->
                    BuilderGameModePolicy.allows(player.gameMode) && config.allowsWorld(player.world.name)
                },
                renderSelection = { player ->
                    displayRenderer.selection(
                        player,
                        selections.points(player.uniqueId, player.world.uid),
                        selections.selection(player.uniqueId, player.world.uid),
                    )
                },
                renderPlan = displayRenderer::plan,
                clearPlan = displayRenderer::clearPlan,
                onExpired = { playerId ->
                    shop.clear(playerId)
                    crown.clearAnchor(playerId)
                },
                onRenderFailure = { player, failure ->
                    warn("Builder-tools preview failed for {}: {}", player.name, failure.message)
                },
            ).also { initializedPreviews = it }
            crown = BuilderCrownController(
                plugin = plugin,
                messages = messages,
                safety = safety,
                selections = selections,
                maximumChanges = config.maxChanges,
                host = object : BuilderCrownHost {
                    override fun operationLocked(playerId: UUID): Boolean = operationLocks.isPlayerLocked(playerId)

                    override fun ensureAvailable(player: Player) = this@BuilderToolsRuntime.ensureAvailable(player)

                    override fun ensurePermission(player: Player) = ensureFeaturePermission(player, BuilderFeature.CROWN)

                    override fun ensureMutable(player: Player, block: Block) = this@BuilderToolsRuntime.ensureMutable(player, block)

                    override fun ensurePlacement(player: Player, block: Block, material: Material) =
                        this@BuilderToolsRuntime.ensureMutable(player, block, material)

                    override fun placementData(material: Material) = this@BuilderToolsRuntime.placementData(material)

                    override fun materialLabel(player: Player, material: Material): Component =
                        BuilderMaterialPresentation.label(player, material)

                    override fun setFirstPosition(player: Player, location: Location) = setPosition(player, location, first = true)

                    override fun createPlan(
                        player: Player,
                        changes: List<BuilderBlockChange>,
                        costs: List<BuilderItemAmount>,
                    ): BuilderPlan = newPlan(player, BuilderPlanKind.CROWN, changes, costs, emptyList())

                    override fun preparePlan(player: Player, plan: BuilderPlan) = this@BuilderToolsRuntime.preparePlan(player, plan)

                    override fun confirmPlan(player: Player) = confirm(player)

                    override fun prepareUndo(player: Player) = this@BuilderToolsRuntime.prepareUndo(player)

                    override fun cancelPlan(player: Player) = this@BuilderToolsRuntime.cancelPlan(player)

                    override fun showPlanStatus(player: Player) = showStatus(player)

                    override fun discardPendingCrown(playerId: UUID) {
                        previews.plan(playerId)?.takeIf { it.kind == BuilderPlanKind.CROWN }?.let {
                            discardPendingPlan(playerId)
                        }
                    }

                    override fun runEventAction(player: Player, action: () -> Unit) {
                        try {
                            action()
                        } catch (failure: BuilderUserFailure) {
                            send(player, failure.path, failure.values)
                        }
                    }

                    override fun fail(path: String, values: Map<String, Component>): Nothing =
                        throw BuilderUserFailure(path, values)
                },
            ).also { initializedCrown = it }
            books = BuilderBookLifecycle(
                config = config,
                messages = messages,
                taskScope = taskScope,
                storageExecutor = storageExecutor,
                operationLocks = operationLocks,
                draftJournal = BuilderDraftJournal(plugin.dataPath, BuilderPlan.ABSOLUTE_MAX_CHANGES),
                draftStorage = draftStorage,
                schematicVerifier = bookSchematicVerifier,
                host = object : BuilderBookLifecycleHost {
                    override fun ensureOperationalContext(player: Player) =
                        this@BuilderToolsRuntime.ensureOperationalContext(player)

                    override fun ensureCopyPermission(player: Player) =
                        ensureFeaturePermission(player, BuilderFeature.COPY)

                    override fun currentClipboard(playerId: UUID): BuilderClipboard? =
                        clipboardController.current(playerId)

                    override fun currentSelection(player: Player): BuilderSelection? =
                        selectionOrNull(player)

                    override fun currentSelectionPoints(player: Player): BuilderSelectionPoints =
                        selections.points(player.uniqueId, player.world.uid)

                    override fun startJournaledOperation(player: Player, plan: BuilderPlan, plannedMode: GameMode) =
                        this@BuilderToolsRuntime.startJournaledOperation(player, plan, plannedMode)

                    override fun localJournalRecord(operationId: UUID): BuilderJournalRecord? =
                        committedRecords[operationId]
                            ?: playerRecoveries.record(operationId)

                    override fun awaitingPlayerRecovery(operationId: UUID): Boolean =
                        playerRecoveries.record(operationId) != null

                    override fun recoveryInProgress(): Boolean = recovering

                    override fun send(player: Player, path: String, values: Map<String, Component>) =
                        this@BuilderToolsRuntime.send(player, path, values)
                },
            ).also { initializedBooks = it }
            playerRecoveries = BuilderPlayerRecoveryCoordinator(
                taskScope = taskScope,
                operationLocks = operationLocks,
                playerLookup = Bukkit::getPlayer,
                restoreInventory = { player, record ->
                    stateService.restoreInventoryAndVerify(player, stateCodec.decode(record.inventoryBefore))
                },
                acknowledgeAsync = { record, complete ->
                    writeAsync(
                        action = { journal.acknowledgeExactly(record) },
                        callback = { acknowledged, failure ->
                            complete(
                                when {
                                    failure != null -> failure
                                    acknowledged == true -> null
                                    else -> IllegalStateException(
                                        "Builder-tools exact recovery acknowledgement was rejected",
                                    )
                                },
                            )
                        },
                    )
                },
                releaseReservation = books::releasePlanReservation,
                onTerminalFailure = { record, failure ->
                    recoveryBlocked = true
                    error("Builder-tools player recovery failed for ${record.operationId}", failure)
                    Bukkit.getPlayer(record.playerId)
                        ?.takeIf(Player::isOnline)
                        ?.let { send(it, "errors.recovering") }
                },
                onAcknowledgementPending = { record, failure ->
                    warn(
                        "Builder-tools player recovery acknowledgement queued for retry: operation={} type={}",
                        record.operationId,
                        BuilderToolsFailureType.of(failure),
                    )
                    Bukkit.getPlayer(record.playerId)
                        ?.takeIf(Player::isOnline)
                        ?.let { send(it, "errors.recovering") }
                },
                onAcknowledgementRecovered = { record ->
                    info(
                        "Builder-tools player recovery acknowledgement recovered: operation={}",
                        record.operationId,
                    )
                },
                onResolved = { playerId ->
                    Bukkit.getPlayer(playerId)?.takeIf(Player::isOnline)?.let(books::onPlayerAvailable)
                },
            ).also { initializedPlayerRecoveries = it }
            publishRuntimeHealth()
            checkNotNull(taskScope.runTimer(0L, HEALTH_PUBLISH_PERIOD_TICKS, ::publishRuntimeHealth)) {
                "Builder-tools health publication task was not scheduled"
            }
            loadRecoveryState()
            books.start()
        } catch (failure: Throwable) {
            HandlerList.unregisterAll(this)
            initializedPlayerRecoveries?.close()
            initializedBooks?.close()
            initializedCrown?.close()
            initializedPreviews?.close()
            taskScope.close()
            operationLocks.close()
            closeStorageExecutor()
            shop.close()
            BuildingManager.installPreviewBridge(null)
            displayRenderer.close()
            throw failure
        }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val player = sender as? Player ?: run {
            sender.sendMessage(messages.render("errors.player-only"))
            return true
        }
        try {
            handleBuilder(player, args)
        } catch (failure: BuilderUserFailure) {
            send(player, failure.path, failure.values)
        } catch (failure: IllegalArgumentException) {
            warn("Builder-tools rejected command for {}: {}", player.name, failure.message)
            send(player, "errors.plan-failed")
        } catch (failure: Throwable) {
            error("Builder-tools command failed for ${player.name}", failure)
            send(player, "errors.plan-failed")
        }
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> {
        if (sender !is Player || !hasUsePermission(sender)) return emptyList()
        if (args.size == 1) {
            return filterPrefix(BuilderRootCommand.suggestions(), args[0])
        }
        if (args.size == 2 && args[0].equals("confirm", true)) return filterPrefix(listOf("buy"), args[1])
        if (args.size == 2 && args[0].equals("disconnect", true)) return filterPrefix(listOf("confirm"), args[1])
        if (args.size == 2 && args[0].equals("paste", true)) return filterPrefix(listOf("rotate", "left", "right"), args[1])
        if (args.size == 2 && args[0].equals("book", true)) {
            return filterPrefix(listOf("guide", "status", "draft", "activate", "copy", "sell", "confirm", "cancel"), args[1])
        }
        if (args.firstOrNull().equals("crown", true)) {
            return crown.tabComplete(args)
        }
        if (args.size == 2 && args[0].equals("fill", true)) return filterPrefix(safeMaterialNames(), args[1])
        return emptyList()
    }

    private fun handleBuilder(player: Player, args: Array<out String>) {
        ensureAvailable(player)
        when (BuilderRootCommand.parse(args.firstOrNull()) ?: BuilderRootCommand.HELP) {
            BuilderRootCommand.HELP -> messages.renderLines("help", locale(player)).forEach(player::sendMessage)
            BuilderRootCommand.WAND -> giveWand(player)
            BuilderRootCommand.CLEAR -> clearSelection(player)
            BuilderRootCommand.FILL -> preparePlan(player, fillController.plan(player, materialArgument(player, args.getOrNull(1))))
            BuilderRootCommand.DISCONNECT -> {
                val plan = fenceConnectionController.planDisconnect(player)
                if (args.getOrNull(1)?.equals("confirm", true) == true) {
                    confirmImmediately(player, plan)
                } else {
                    preparePlan(player, plan)
                }
            }
            BuilderRootCommand.COPY -> {
                val copied = clipboardController.copy(player)
                send(
                    player,
                    "clipboard.saved",
                    mapOf(
                        "count" to messages.literal(copied.blocks.size),
                        "skipped" to messages.literal(copied.skippedUnsafeBlocks),
                    ),
                )
            }
            BuilderRootCommand.BOOK -> books.handleCommand(player, args.drop(1))
            BuilderRootCommand.PASTE -> {
                when (args.getOrNull(1)?.lowercase(Locale.ROOT)) {
                    null -> Unit
                    "rotate", "right" -> clipboardController.rotate(player.uniqueId, 90)
                    "left" -> clipboardController.rotate(player.uniqueId, -90)
                    else -> throw BuilderUserFailure("errors.material")
                }
                preparePlan(player, clipboardController.planPaste(player))
            }
            BuilderRootCommand.DECONSTRUCT -> preparePlan(player, deconstructionController.plan(player))
            BuilderRootCommand.CROWN -> crown.handle(player, args.drop(1))
            BuilderRootCommand.CONFIRM -> when (args.getOrNull(1)?.lowercase(Locale.ROOT)) {
                null -> confirm(player)
                "buy" -> confirm(player, buyMissing = true)
                else -> messages.renderLines("help", locale(player)).forEach(player::sendMessage)
            }
            BuilderRootCommand.CANCEL -> cancelPlan(player)
            BuilderRootCommand.UNDO -> prepareUndo(player)
            BuilderRootCommand.STATUS -> showStatus(player)
        }
    }

    private fun ensureAvailable(player: Player) {
        if (!hasUsePermission(player)) throw BuilderUserFailure("errors.no-permission")
        ensureOperationalContext(player)
    }

    private fun ensureBuildBookAvailable(player: Player) {
        books.ensureAvailable(player)
    }

    private fun ensureOperationalContext(player: Player) {
        if (recovering || recoveryBlocked || books.health().recoveryBlocked || playerRecoveries.contains(player.uniqueId)) {
            throw BuilderUserFailure("errors.recovering")
        }
        if (!BuilderGameModePolicy.allows(player.gameMode)) throw BuilderUserFailure("errors.game-mode")
        if (!config.allowsWorld(player.world.name)) throw BuilderUserFailure("errors.world-not-allowed")
    }

    private fun ensureFeaturePermission(player: Player, feature: BuilderFeature) {
        if (!BuilderPermissionPolicy.canUse(feature, player::hasPermission)) {
            throw BuilderUserFailure("errors.no-permission")
        }
    }

    private fun hasUsePermission(player: Player): Boolean =
        BuilderPermissionPolicy.canUseAny(player::hasPermission)

    private fun giveWand(player: Player) {
        if (isSelector(player.inventory.itemInMainHand)) {
            player.inventory.setItemInMainHand(styleWand(player.inventory.itemInMainHand.clone(), player))
            send(player, "wand.received")
            return
        }
        val wand = styleWand(ItemStack(Material.ECHO_SHARD), player)
        if (!BuilderGameModePolicy.usesInventory(player.gameMode)) {
            when (BuilderOwnedToolExchange.replaceOnePlainHeld(player, Material.ECHO_SHARD, wand)) {
                BuilderOwnedToolExchangeResult.REPLACED -> Unit
                BuilderOwnedToolExchangeResult.WRONG_ITEM -> {
                    if (player.inventory.itemInMainHand.type.isAir) {
                        player.inventory.setItemInMainHand(wand)
                    } else if (player.inventory.addItem(wand).isNotEmpty()) {
                        throw BuilderUserFailure("wand.inventory-full")
                    }
                }
                BuilderOwnedToolExchangeResult.INVENTORY_FULL -> throw BuilderUserFailure("wand.inventory-full")
            }
            send(player, "wand.received")
            return
        }
        when (BuilderOwnedToolExchange.replaceOnePlainHeld(player, Material.ECHO_SHARD, wand)) {
            BuilderOwnedToolExchangeResult.REPLACED -> Unit
            BuilderOwnedToolExchangeResult.WRONG_ITEM -> throw BuilderUserFailure("wand.material-required")
            BuilderOwnedToolExchangeResult.INVENTORY_FULL -> throw BuilderUserFailure("wand.inventory-full")
        }
        send(player, "wand.received")
    }

    private fun styleWand(item: ItemStack, player: Player): ItemStack = item.apply {
        editMeta { meta ->
            BuilderItemPresentation.apply(
                meta,
                messages.render("wand.name", locale(player)),
                messages.renderLines("wand.lore", locale(player)),
            )
            meta.persistentDataContainer.set(wandKey, PersistentDataType.BYTE, 1)
        }
    }

    private fun setPosition(player: Player, location: Location, first: Boolean) {
        ensureAvailable(player)
        require(location.world == player.world) { "Selection world mismatch" }
        val position = BuilderBlockPos(player.world.uid, location.blockX, location.blockY, location.blockZ).validated()
        val update = selections.set(player.uniqueId, position, first)
        if (update.worldReset) send(player, "selection.world-reset")
        send(
            player,
            if (first) "selection.first" else "selection.second",
            mapOf("x" to messages.literal(position.x), "y" to messages.literal(position.y), "z" to messages.literal(position.z)),
        )
        val selection = update.selection
        if (selection != null) {
            send(
                player,
                "selection.complete",
                mapOf(
                    "x" to messages.literal(selection.sizeX),
                    "y" to messages.literal(selection.sizeY),
                    "z" to messages.literal(selection.sizeZ),
                    "volume" to messages.literal(selection.volume),
                ),
            )
            displayRenderer.selection(player, selections.points(player.uniqueId, player.world.uid), selection)
        } else {
            // Render through the same controller as the repeating preview so
            // the first point is immediately white and the second green.
            displayRenderer.selection(player, selections.points(player.uniqueId, player.world.uid), null)
        }
    }

    private fun requiredSelection(player: Player): BuilderSelection {
        val selection = selectionOrNull(player) ?: throw BuilderUserFailure("errors.selection-missing")
        return try {
            selection.validated(maxAxis(player), config.maxScanVolume)
        } catch (_: IllegalArgumentException) {
            throw BuilderUserFailure("errors.selection-too-large")
        }
    }

    private fun selectionOrNull(player: Player): BuilderSelection? {
        return selections.selection(player.uniqueId, player.world.uid)
    }

    private fun maxAxis(player: Player): Int {
        return BuilderPermissionPolicy.maximumAxis(player::hasPermission, config.absoluteMaxAxis)
    }

    private fun planBuildBook(player: Player, site: ConstructionSite, book: ItemStack): BuilderPlan {
        if (!player.hasPermission("arcbuild.book.use")) throw BuilderUserFailure("errors.no-permission")
        val data = site.bookData.takeIf { it.playerCreated } ?: throw BuilderUserFailure("book.invalid")
        if (data.draft) throw BuilderUserFailure("book.unactivated")
        if (data.deliveryPending) throw BuilderUserFailure("book.delivery-pending")
        if (!data.available) throw BuilderUserFailure("book.invalid")
        if (BuilderBookAuctionTokenCodec.read(book) != null) throw BuilderUserFailure("book.auction-locked")
        if (!BuildBookCodec.matches(book, data)) throw BuilderUserFailure("book.missing")
        books.verifySchematic(data)
        if (site.building.volume > config.maxScanVolume) throw BuilderUserFailure("errors.selection-too-large")

        val rewards = mutableListOf<ItemStack>()
        var skippedUnsafe = 0
        val changes = site.relativePositionsBottomUp().mapNotNull { relative ->
            val after = rotateBlockData(
                BukkitAdapter.adapt(site.building.getBlock(relative, site.fullRotation)),
                site.fullRotation,
            )
            when (val placement = planBuildBookBlock(player, site.worldLocation(relative).block, after)) {
                BuilderBookPlacementResult.Unchanged -> null
                BuilderBookPlacementResult.SkippedUnsafe -> {
                    skippedUnsafe += 1
                    null
                }
                is BuilderBookPlacementResult.Change -> {
                    placement.refund?.let(rewards::add)
                    placement.block
                }
            }
        }.take(config.maxChanges + 1).toList()
        requireChanges(changes)
        return newPlan(
            player = player,
            kind = BuilderPlanKind.BUILD_BOOK,
            changes = changes,
            costs = BuilderBookConstructionCosts.calculate(book, data, player.gameMode),
            rewards = BuilderItemCodec.aggregate(rewards),
            bookBlueprintId = checkNotNull(data.blueprintId),
            bookInstanceId = checkNotNull(data.instanceId),
            bookInstanceGeneration = checkNotNull(data.instanceGeneration),
            bookBuildingId = data.buildingId,
            bookSchematicSha256 = checkNotNull(data.schematicSha256),
            skippedUnsafeBlocks = skippedUnsafe,
        )
    }

    internal fun planBuildBookBlock(
        player: Player,
        block: Block,
        after: org.bukkit.block.data.BlockData,
    ): BuilderBookPlacementResult {
        if (!after.material.isAir && !safety.isSafePlacement(after)) {
            return BuilderBookPlacementResult.SkippedUnsafe
        }
        if (block.blockData.asString == after.asString) return BuilderBookPlacementResult.Unchanged
        val replaceable = safety.isReplaceable(block)
        if (!replaceable && !safety.isSafeExisting(block)) {
            return BuilderBookPlacementResult.SkippedUnsafe
        }
        ensureMutable(player, block, after.material.takeUnless(Material::isAir))
        val refund = if (BuilderGameModePolicy.usesInventory(player.gameMode) && !replaceable) {
            bookReplacementRefund(block)
        } else {
            null
        }
        return BuilderBookPlacementResult.Change(
            BuilderBlockChange(
                BuilderBlockPos(block.world.uid, block.x, block.y, block.z).validated(),
                block.blockData.asString,
                after.asString,
            ),
            refund,
        )
    }

    private fun placementData(material: Material) = material
        .takeIf(safety::isSafeMaterial)
        ?.createBlockData()
        ?.also { data ->
            if (!safety.isSafePlacement(data)) throw BuilderUserFailure("errors.material")
            if (data is Leaves) data.isPersistent = true
        } ?: throw BuilderUserFailure("errors.material")

    private fun materialArgument(player: Player, raw: String?): Material {
        if (raw == null) return player.inventory.itemInMainHand.type.takeUnless(Material::isAir) ?: throw BuilderUserFailure("errors.material")
        return Material.matchMaterial(raw) ?: Material.matchMaterial(raw.uppercase(Locale.ROOT)) ?: throw BuilderUserFailure("errors.material")
    }

    private fun newPlan(
        player: Player,
        kind: BuilderPlanKind,
        changes: List<BuilderBlockChange>,
        costs: List<BuilderItemAmount>,
        rewards: List<BuilderItemAmount>,
        toolFingerprint: String? = null,
        toolDamage: Int = 0,
        sourceRecordId: UUID? = null,
        bookBlueprintId: UUID? = null,
        bookInstanceId: UUID? = null,
        bookInstanceGeneration: Int? = null,
        bookBuildingId: String? = null,
        bookSchematicSha256: String? = null,
        skippedUnsafeBlocks: Int = 0,
    ): BuilderPlan {
        val now = System.currentTimeMillis()
        return BuilderPlan(
            id = UUID.randomUUID(),
            playerId = player.uniqueId,
            kind = kind,
            changes = changes,
            costs = costs,
            rewards = rewards,
            toolFingerprintBase64 = toolFingerprint,
            toolDamage = toolDamage,
            sourceRecordId = sourceRecordId,
            bookBlueprintId = bookBlueprintId,
            bookInstanceId = bookInstanceId,
            bookInstanceGeneration = bookInstanceGeneration,
            bookBuildingId = bookBuildingId,
            bookSchematicSha256 = bookSchematicSha256,
            skippedUnsafeBlocks = skippedUnsafeBlocks,
            createdAtMillis = now,
            expiresAtMillis = now + config.planTtl.toMillis(),
        ).validated(config.maxChanges)
    }

    private fun preparePlan(player: Player, plan: BuilderPlan) {
        preflightPlan(player, plan)
        crown.clearAnchor(player.uniqueId)
        previews.open(
            player = player,
            plan = BuilderPendingPlan(plan, player.gameMode),
            expireAfterTicks = config.planTtl.toTicks(),
        )
        send(
            player,
            "plan.ready",
            mapOf(
                "kind" to kindLabel(player, plan.kind),
                "count" to messages.literal(plan.changes.size),
                "cost" to itemsSummary(player, plan.costs),
                "reward" to itemsSummary(player, plan.rewards),
                "seconds" to messages.literal(config.planTtl.seconds),
            ),
        )
        if (plan.skippedUnsafeBlocks > 0) {
            send(player, "plan.skipped", mapOf("count" to messages.literal(plan.skippedUnsafeBlocks)))
        }
        shop.preview(player, plan)
    }

    private fun confirmImmediately(player: Player, plan: BuilderPlan) {
        preflightPlan(player, plan)
        crown.clearAnchor(player.uniqueId)
        shop.clear(player.uniqueId)
        val pending = BuilderPendingPlan(plan, player.gameMode)
        previews.store(player.uniqueId, pending)
        try {
            confirm(player)
        } catch (failure: Throwable) {
            previews.remove(player.uniqueId, pending)
            shop.clear(player.uniqueId)
            throw failure
        }
    }

    private fun preflightPlan(player: Player, plan: BuilderPlan) {
        if (operationLocks.isPlayerLocked(player.uniqueId)) throw BuilderUserFailure("errors.busy")
        val used = hourlyUsage(player.uniqueId, System.currentTimeMillis())
        if (plan.kind != BuilderPlanKind.UNDO && used + plan.changes.size > hourlyLimit(player)) {
            throw BuilderUserFailure("errors.hourly-limit")
        }
        val canApplyNow = BuilderInventory.canApply(player, plan.costs, plan.rewards, plan.toolFingerprintBase64, plan.toolDamage)
        if (!canApplyNow) {
            if (!BuilderShopEstimateRules.supportsAutoBuy(plan.kind)) throw BuilderUserFailure("errors.inventory")
            val missing = BuilderInventory.missingCosts(player, plan.costs)
            if (
                missing.isEmpty() ||
                !BuilderInventory.canApplyAfterReceiving(
                    player,
                    missing,
                    plan.costs,
                    plan.rewards,
                    plan.toolFingerprintBase64,
                    plan.toolDamage,
                )
            ) {
                throw BuilderUserFailure("errors.inventory")
            }
        }
    }

    private fun startPlayerBuildBook(player: Player, site: ConstructionSite, book: ItemStack): Boolean = try {
        ensureBuildBookAvailable(player)
        val plan = planBuildBook(player, site, book)
        site.cancelSilently()
        preparePlan(player, plan)
        true
    } catch (failure: BuilderUserFailure) {
        send(player, failure.path, failure.values)
        false
    } catch (failure: IllegalArgumentException) {
        warn("Player build-book plan was rejected for {}: {}", player.name, failure.message)
        send(player, "book.failed")
        false
    } catch (failure: Throwable) {
        error("Player build-book start failed for ${player.name}", failure)
        send(player, "book.failed")
        false
    }

    private fun confirm(player: Player, buyMissing: Boolean = false, buildBook: Boolean = false) {
        if (buildBook) ensureBuildBookAvailable(player) else ensureAvailable(player)
        if (operationLocks.isPlayerLocked(player.uniqueId)) throw BuilderUserFailure("errors.busy")
        val pending = previews[player.uniqueId] ?: throw BuilderUserFailure("errors.expired")
        val plan = pending.plan
        if (plan.expiresAtMillis <= System.currentTimeMillis()) {
            discardPendingPlan(player.uniqueId)
            throw BuilderUserFailure("errors.expired")
        }
        val plannedMode = pending.gameMode
        if (player.gameMode != plannedMode) {
            discardPendingPlan(player.uniqueId)
            throw BuilderUserFailure("errors.game-mode-changed")
        }
        revalidatePlan(player, plan)
        if (buyMissing) {
            when (val result = shop.procure(player, plan)) {
                BuilderShopConfirmation.Ready -> Unit
                is BuilderShopConfirmation.Rejected -> throw BuilderUserFailure(
                    result.messagePath,
                    result.values,
                )
            }
        }
        if (!BuilderInventory.canApply(player, plan.costs, plan.rewards, plan.toolFingerprintBase64, plan.toolDamage)) {
            throw BuilderUserFailure("errors.inventory")
        }
        if (!operationLocks.tryLock(plan)) throw BuilderUserFailure("errors.busy")
        previews.remove(player.uniqueId, pending)
        shop.clear(player.uniqueId)
        crown.clearAnchor(player.uniqueId)
        val instanceId = plan.bookInstanceId
        if (instanceId != null) {
            books.reserveForBuild(player, plan, plannedMode)
        } else {
            startJournaledOperation(player, plan, plannedMode)
        }
    }

    private fun startJournaledOperation(player: Player, plan: BuilderPlan, plannedMode: GameMode) {
        val now = System.currentTimeMillis()
        val record = BuilderJournalRecord(
            operationId = plan.id,
            playerId = player.uniqueId,
            playerName = player.name,
            phase = BuilderJournalPhase.PREPARED,
            plan = plan,
            inventoryBefore = stateService.captureEnvelope(player, now),
            createdAtMillis = plan.createdAtMillis,
            updatedAtMillis = now,
        ).validated(config.maxChanges)
        val operation = BuilderActiveOperation(record, plannedMode)
        operationLocks.register(operation)
        send(player, "operation.started", mapOf("count" to messages.literal(plan.changes.size)))
        writeAsync(
            action = { journal.commit(record) },
            callback = { durable, failure ->
                if (failure != null || durable == null) return@writeAsync failBeforeMutation(player, operation, failure)
                operation.record = durable
                if (operation.cancelled || !player.isOnline) return@writeAsync acknowledgeCancelled(operation)
                val applying = durable.copy(phase = BuilderJournalPhase.APPLYING, updatedAtMillis = System.currentTimeMillis())
                writeAsync(
                    action = { journal.commit(applying) },
                    callback = applyingCommit@{ durableApplying, applyFailure ->
                        if (applyFailure != null || durableApplying == null) {
                            return@applyingCommit failBeforeMutation(player, operation, applyFailure)
                        }
                        operation.record = durableApplying
                        if (operation.cancelled || !player.isOnline) return@applyingCommit acknowledgeCancelled(operation)
                        beginMutation(player, operation)
                    },
                )
            },
        )
    }

    private fun beginMutation(player: Player, operation: BuilderActiveOperation) {
        val record = operation.record
        if (player.gameMode != operation.gameMode) {
            rollback(player, operation, "game mode changed before apply")
            return
        }
        if (!BuilderInventory.snapshotInventoryMatches(player, record.inventoryBefore)) {
            rollback(player, operation, "inventory changed before apply")
            return
        }
        try {
            revalidatePlan(player, record.plan)
            if (record.plan.costs.isNotEmpty()) {
                operation.inventoryMutated = true
                check(BuilderInventory.removeCosts(player.inventory, record.plan.costs)) { "planned costs disappeared" }
            }
            if (record.plan.toolDamage > 0) {
                operation.inventoryMutated = true
                player.damageItemStack(EquipmentSlot.HAND, record.plan.toolDamage)
            }
            player.updateInventory()
        } catch (failure: Throwable) {
            rollback(player, operation, failure.message ?: "pre-apply validation failed")
            return
        }
        runMutationBatch(player, operation)
    }

    private fun runMutationBatch(player: Player, operation: BuilderActiveOperation) {
        if (operation.cancelled || !player.isOnline) {
            rollback(player, operation, "player disconnected")
            return
        }
        if (player.gameMode != operation.gameMode) {
            rollback(player, operation, "game mode changed during apply")
            return
        }
        try {
            var processed = 0
            val changes = operation.record.plan.changes
            while (processed < config.blocksPerTick && operation.appliedChanges < changes.size) {
                val change = changes[operation.appliedChanges]
                val block = block(requireWorld(change.position.worldId), change.position)
                check(block.blockData.asString == change.beforeBlockData) { "block changed after confirmation" }
                val before = Bukkit.createBlockData(change.beforeBlockData)
                val after = Bukkit.createBlockData(change.afterBlockData)
                ensureMutable(player, block, after.material.takeUnless(Material::isAir))
                block.setBlockData(after, false)
                coreProtect?.logChange(operation.record.playerName, block.location, before, after)
                operation.appliedChanges++
                processed++
            }
            operation.mutationBatches++
            val completed = operation.appliedChanges >= changes.size
            if (BuilderProgressCadence.shouldRender(operation.mutationBatches, completed)) {
                player.sendActionBar(
                    messages.render(
                        "operation.progress",
                        locale(player),
                        mapOf(
                            "kind" to kindLabel(player, operation.record.plan.kind),
                            "count" to messages.literal(operation.appliedChanges),
                            "total" to messages.literal(changes.size),
                        ),
                    ),
                )
            }
            if (operation.appliedChanges < changes.size) {
                taskScope.runLater(1L) { runMutationBatch(player, operation) }
                return
            }
            if (operation.record.plan.rewards.isNotEmpty()) {
                operation.inventoryMutated = true
                check(BuilderInventory.addRewards(player.inventory, operation.record.plan.rewards)) {
                    "planned rewards no longer fit"
                }
            }
            player.updateInventory()
            commitMutation(player, operation)
        } catch (failure: Throwable) {
            rollback(player, operation, failure.message ?: "mutation failed")
        }
    }

    private fun commitMutation(player: Player, operation: BuilderActiveOperation) {
        val now = System.currentTimeMillis()
        val committed = operation.record.copy(
            phase = BuilderJournalPhase.COMMITTED,
            updatedAtMillis = now,
            committedAtMillis = now,
        )
        operation.beginCommit()
        writeAsync(
            action = { journal.transition(operation.record, committed) },
            callback = { durable, failure ->
                if (failure != null || durable == null) {
                    if (failure is BuilderJournalUnknownOutcomeException) {
                        operation.requireCommitRecovery()
                        recoveryBlocked = true
                        error("Builder-tools commit outcome requires restart recovery for ${operation.record.operationId}", failure)
                        send(player, "errors.recovering")
                        return@writeAsync
                    }
                    operation.markCommitFailureKnown()
                    rollback(player, operation, failure?.message ?: "commit durability failed")
                    return@writeAsync
                }
                operation.record = durable
                committedRecords[durable.operationId] = durable
                durable.plan.sourceRecordId?.let { consumedUndoSources += it }
                val instanceId = durable.plan.bookInstanceId
                if (instanceId == null) {
                    finalizeCommittedOperation(player, operation)
                } else {
                    books.commitPlanReservation(durable.plan) { consumed, consumeFailure ->
                        if (!consumed) {
                            operation.requireCommitRecovery()
                            recoveryBlocked = true
                            error(
                                "Builder-book consume outcome requires restart recovery for ${durable.operationId}: " +
                                    "type=${BuilderToolsFailureType.of(consumeFailure)}",
                            )
                            send(player, "errors.recovering")
                        } else {
                            finalizeCommittedOperation(player, operation)
                        }
                    }
                }
            },
        )
    }

    private fun finalizeCommittedOperation(player: Player, operation: BuilderActiveOperation) {
        val durable = operation.record
        finishOperation(operation)
        val completion = messages.render(
            "operation.completed",
            locale(player),
            mapOf(
                "kind" to kindLabel(player, durable.plan.kind),
                "count" to messages.literal(durable.plan.changes.size),
            ),
        )
        val hasClipboard = clipboardController.current(player.uniqueId) != null
        player.sendMessage(
            if (BuilderOperationCompletion.repeatPaste(durable.plan.kind, hasClipboard)) {
                completion.append(Component.newline()).append(messages.render("operation.paste-again", locale(player)))
            } else {
                completion
            },
        )
        if (BuilderOperationCompletion.repeatPaste(durable.plan.kind, hasClipboard)) {
            player.sendActionBar(messages.render("clipboard.retained", locale(player)))
        }
        info(debugLine.line("event" to "committed", "operation" to durable.operationId, "player" to durable.playerId, "kind" to durable.plan.kind, "blocks" to durable.plan.changes.size))
        durable.plan.sourceRecordId?.let { markSourceUndone(it) }
        cleanupOldRecords()
    }

    private fun rollback(player: Player, operation: BuilderActiveOperation, reason: String) {
        var failure: Throwable? = null
        var recoveryAccepted = false
        try {
            for (index in operation.appliedChanges - 1 downTo 0) {
                val change = operation.record.plan.changes[index]
                val block = block(requireWorld(change.position.worldId), change.position)
                val current = block.blockData.asString
                check(current == change.afterBlockData || current == change.beforeBlockData) {
                    "rollback encountered an externally changed block"
                }
                if (current == change.afterBlockData) {
                    val after = Bukkit.createBlockData(change.afterBlockData)
                    val before = Bukkit.createBlockData(change.beforeBlockData)
                    block.setBlockData(before, false)
                    coreProtect?.logChange(operation.record.playerName, block.location, after, before)
                }
            }
            if (closed) {
                if (player.isOnline && !player.isDead) {
                    stateService.restoreInventoryAndVerify(player, stateCodec.decode(operation.record.inventoryBefore))
                }
                books.releasePlanReservation(operation.record.plan)
                recoveryAccepted = true
            } else {
                recoveryAccepted = playerRecoveries.add(
                    operation.record,
                    inventoryRestored = !operation.inventoryMutated,
                )
            }
        } catch (rollbackFailure: Throwable) {
            failure = rollbackFailure
            recoveryBlocked = true
            if (!closed) {
                runCatching { playerRecoveries.hold(operation.record) }.onFailure { holdFailure ->
                    error(
                        "Builder-tools could not retain recovery hold for ${operation.record.operationId}",
                        holdFailure,
                    )
                }
            }
            error("Builder-tools rollback requires operator attention for ${operation.record.operationId}", rollbackFailure)
        }
        finishOperation(operation)
        if (failure == null && recoveryAccepted && player.isOnline) send(player, "operation.rolled-back")
        warn(debugLine.line("event" to "rolled_back", "operation" to operation.record.operationId, "player" to operation.record.playerId, "reason" to reason))
    }

    private fun failBeforeMutation(player: Player, operation: BuilderActiveOperation, failure: Throwable?) {
        val recoveryAccepted = playerRecoveries.add(operation.record, inventoryRestored = true)
        finishOperation(operation)
        if (recoveryAccepted) send(player, "operation.rolled-back")
        failure?.let { warn("Builder-tools durability barrier failed: {}", it.message) }
    }

    private fun acknowledgeCancelled(operation: BuilderActiveOperation) {
        playerRecoveries.add(operation.record, inventoryRestored = true)
        finishOperation(operation)
    }

    private fun finishOperation(operation: BuilderActiveOperation) = operationLocks.finish(operation)

    private fun revalidatePlan(player: Player, plan: BuilderPlan) {
        plan.validated(config.maxChanges)
        plan.changes.forEach { change ->
            val block = block(requireWorld(change.position.worldId), change.position)
            val after = Bukkit.createBlockData(change.afterBlockData)
            ensureMutable(player, block, after.material.takeUnless(Material::isAir))
            if (block.blockData.asString != change.beforeBlockData) throw BuilderUserFailure("errors.expired")
            if (!block.type.isAir && !safety.isSafeExisting(block) && !safety.isReplaceable(block)) {
                throw BuilderUserFailure("errors.expired")
            }
            if (!safety.isSafePlacement(after) && after.material !in safety.replaceable) {
                throw BuilderUserFailure("errors.plan-failed")
            }
        }
    }

    private fun cancelPlan(player: Player) {
        val active = operationLocks.operation(player.uniqueId)
        if (active != null) {
            if (active.interruptionDeferred) {
                throw BuilderUserFailure(
                    if (active.commitBoundary == BuilderCommitBoundary.RECOVERY_REQUIRED) {
                        "errors.recovering"
                    } else {
                        "errors.busy"
                    },
                )
            }
            active.cancelled = true
            if (active.appliedChanges > 0 || active.inventoryMutated) rollback(player, active, "cancelled")
            else send(player, "plan.cancelled")
            return
        }
        if (previews.contains(player.uniqueId)) {
            discardPendingPlan(player.uniqueId)
            send(player, "plan.cancelled")
        } else if (BuildingManager.closePreview(player.uniqueId)) {
            send(player, "book.preview-cancelled")
        } else {
            throw BuilderUserFailure("errors.expired")
        }
    }

    private fun clearSelection(player: Player) {
        if (operationLocks.isPlayerLocked(player.uniqueId)) throw BuilderUserFailure("errors.busy")
        discardPendingPlan(player.uniqueId)
        selections.clear(player.uniqueId)
        displayRenderer.clearSelection(player.uniqueId)
        BuildingManager.closePreview(player.uniqueId)
        crown.clearAnchor(player.uniqueId)
        send(player, "selection.cleared")
    }

    private fun prepareUndo(player: Player) {
        ensureAvailable(player)
        val now = System.currentTimeMillis()
        val source = committedRecords.values
            .asSequence()
            .filter { it.playerId == player.uniqueId && it.phase == BuilderJournalPhase.COMMITTED }
            .filter { it.plan.kind != BuilderPlanKind.UNDO && it.operationId !in consumedUndoSources }
            .filter { (it.committedAtMillis ?: 0L) + config.undoTtl.toMillis() > now }
            .maxByOrNull { it.committedAtMillis ?: 0L }
            ?: throw BuilderUserFailure("errors.undo-missing")
        val changes = source.plan.changes.asReversed().map { change ->
            BuilderBlockChange(change.position, change.afterBlockData, change.beforeBlockData)
        }
        val exchange = BuilderUndoRules.exchangeFor(source.plan)
        val plan = newPlan(
            player = player,
            kind = BuilderPlanKind.UNDO,
            changes = changes,
            costs = exchange.costs,
            rewards = exchange.rewards,
            sourceRecordId = source.operationId,
        )
        preparePlan(player, plan)
    }

    private fun showStatus(player: Player) {
        val active = operationLocks.operation(player.uniqueId)
        val plan = previews.plan(player.uniqueId)
        val selection = selectionOrNull(player)
        val selectionPoints = selections.points(player.uniqueId, player.world.uid)
        when {
            active != null -> send(player, "status.plan", mapOf("kind" to kindLabel(player, active.record.plan.kind), "count" to messages.literal(active.appliedChanges), "total" to messages.literal(active.record.plan.changes.size)))
            plan != null -> send(player, "status.plan", mapOf("kind" to kindLabel(player, plan.kind), "count" to messages.literal(0), "total" to messages.literal(plan.changes.size)))
            selection != null -> send(player, "status.selection", mapOf("x" to messages.literal(selection.sizeX), "y" to messages.literal(selection.sizeY), "z" to messages.literal(selection.sizeZ), "volume" to messages.literal(selection.volume)))
            selectionPoints.first != null -> send(
                player,
                "status.selection-first",
                mapOf(
                    "x" to messages.literal(selectionPoints.first.x),
                    "y" to messages.literal(selectionPoints.first.y),
                    "z" to messages.literal(selectionPoints.first.z),
                ),
            )
            selectionPoints.second != null -> send(
                player,
                "status.selection-second",
                mapOf(
                    "x" to messages.literal(selectionPoints.second.x),
                    "y" to messages.literal(selectionPoints.second.y),
                    "z" to messages.literal(selectionPoints.second.z),
                ),
            )
            else -> send(player, "status.idle")
        }
    }

    private fun ensureMutable(player: Player, block: Block, placing: Material? = null) {
        ensureInRangeAndLoaded(player, block)
        ensureProtected(player, block, placing)
        if (!block.world.worldBorder.isInside(block.location)) throw BuilderUserFailure("errors.protection")
    }

    private fun ensureInRangeAndLoaded(player: Player, block: Block) {
        if (!block.world.isChunkLoaded(block.x shr 4, block.z shr 4)) throw BuilderUserFailure("errors.chunk-unloaded")
        if (player.world.uid != block.world.uid || player.location.distanceSquared(block.location.clone().add(0.5, 0.5, 0.5)) > config.maximumRange * config.maximumRange) {
            throw BuilderUserFailure("errors.too-far")
        }
    }

    private fun ensureProtected(player: Player, block: Block, placing: Material? = null) {
        val lands = HookRegistry.landsHook
        if ((lands != null && !lands.canModify(player, block, placing)) || (lands == null && config.requireLands)) {
            throw BuilderUserFailure("errors.protection")
        }
    }

    private fun block(world: World, position: BuilderBlockPos): Block = world.getBlockAt(position.x, position.y, position.z)

    private fun requireWorld(id: UUID): World = Bukkit.getWorld(id) ?: throw BuilderUserFailure("errors.world-not-allowed")

    private fun requireChanges(changes: List<BuilderBlockChange>) {
        if (changes.isEmpty()) throw BuilderUserFailure("errors.nothing-to-change")
        if (changes.size > config.maxChanges) throw BuilderUserFailure("errors.selection-too-large")
    }

    private fun hourlyUsage(playerId: UUID, now: Long): Int = committedRecords.values
        .asSequence()
        .filter { it.playerId == playerId && it.plan.kind != BuilderPlanKind.UNDO }
        .filter { (it.committedAtMillis ?: 0L) >= now - 3_600_000L }
        .sumOf { it.plan.changes.size }

    private fun hourlyLimit(player: Player): Int =
        BuilderPermissionPolicy.hourlyChanges(player::hasPermission, config.baseHourlyChanges)

    private fun itemsSummary(player: Player, items: List<BuilderItemAmount>): Component =
        if (items.isEmpty()) {
            messages.render("items.none", locale(player))
        } else {
            messages.render(
                "items.summary",
                locale(player),
                mapOf(
                    "items" to messages.literal(items.sumOf { it.amount.toLong() }),
                    "types" to messages.literal(items.size),
                ),
            )
        }

    private fun kindLabel(player: Player, kind: BuilderPlanKind): Component =
        messages.render("kinds.${kind.name.lowercase(Locale.ROOT)}", locale(player))

    private fun discardPendingPlan(playerId: UUID) {
        previews.discard(playerId)
        shop.clear(playerId)
        crown.clearAnchor(playerId)
    }

    private fun loadRecoveryState() {
        writeAsync(
            action = { journal.loadAll() },
            callback = { loaded, failure ->
                if (failure != null || loaded == null) {
                    recoveryBlocked = true
                    recovering = false
                    error("Builder-tools journal recovery failed", failure ?: IllegalStateException("missing journal result"))
                    return@writeAsync
                }
                try {
                    val interruptedByPlayer = loaded.map { it.value }.filter { it.phase == BuilderJournalPhase.PREPARED || it.phase == BuilderJournalPhase.APPLYING }.groupBy { it.playerId }
                    require(interruptedByPlayer.values.all { it.size == 1 }) { "Multiple interrupted builder-tools operations exist for one player" }
                    loaded.map { it.value }.filter { it.phase == BuilderJournalPhase.COMMITTED || it.phase == BuilderJournalPhase.UNDONE }.forEach { record ->
                        committedRecords[record.operationId] = record
                        record.plan.sourceRecordId?.let { consumedUndoSources += it }
                    }
                    val acknowledgeNow = interruptedByPlayer.values.flatten().filter(::recoverRecord)
                    if (acknowledgeNow.isEmpty()) {
                        finishRecovery(loaded.size)
                    } else {
                        writeAsync(
                            action = {
                                acknowledgeNow.forEach { record ->
                                    check(journal.acknowledge(record.operationId)) {
                                        "Builder-tools recovery acknowledgement failed for ${record.operationId}"
                                    }
                                }
                            },
                            callback = { _, acknowledgeFailure ->
                                if (acknowledgeFailure != null) {
                                    recoveryBlocked = true
                                    recovering = false
                                    error("Builder-tools recovery acknowledgement failed", acknowledgeFailure)
                                } else {
                                    finishRecovery(loaded.size)
                                }
                            },
                        )
                    }
                } catch (recoveryFailure: Throwable) {
                    recoveryBlocked = true
                    recovering = false
                    loaded.asSequence()
                        .map { it.value }
                        .filter { it.phase == BuilderJournalPhase.APPLYING }
                        .filterNot { playerRecoveries.contains(it.playerId) }
                        .forEach { record ->
                            runCatching { playerRecoveries.hold(record) }.onFailure { holdFailure ->
                                error(
                                    "Builder-tools could not retain startup recovery hold for ${record.operationId}",
                                    holdFailure,
                                )
                            }
                        }
                    error("Builder-tools recovery stopped on ambiguous state", recoveryFailure)
                }
            },
        )
    }

    private fun finishRecovery(recordCount: Int) {
        recovering = false
        cleanupOldRecords()
        books.onGeneralRecoveryFinished()
        info(debugLine.line("event" to "recovery_ready", "records" to recordCount, "pending_players" to playerRecoveries.pendingCount))
    }

    /** Returns true when the durable record may be acknowledged immediately. */
    private fun recoverRecord(record: BuilderJournalRecord): Boolean {
        record.plan.changes.asReversed().forEach { change ->
            val world = Bukkit.getWorld(change.position.worldId)
                ?: throw IllegalStateException("Builder-tools recovery world is unavailable")
            val block = block(world, change.position)
            when (BuilderRecoveryRules.action(record.phase, block.blockData.asString, change.beforeBlockData, change.afterBlockData)) {
                BuilderRecoveryAction.KEEP_BEFORE -> Unit
                BuilderRecoveryAction.RESTORE_BEFORE -> {
                    val after = Bukkit.createBlockData(change.afterBlockData)
                    val before = Bukkit.createBlockData(change.beforeBlockData)
                    block.setBlockData(before, false)
                    coreProtect?.logChange(record.playerName, block.location, after, before)
                }
            }
        }
        if (record.phase == BuilderJournalPhase.PREPARED) return true
        playerRecoveries.add(record)
        return false
    }

    private fun markSourceUndone(sourceId: UUID) {
        val source = committedRecords[sourceId] ?: return
        val updated = source.copy(phase = BuilderJournalPhase.UNDONE, updatedAtMillis = System.currentTimeMillis())
        writeAsync(action = { journal.commit(updated) }, callback = { durable, failure ->
            if (durable != null && failure == null) committedRecords[sourceId] = durable
        })
    }

    private fun cleanupOldRecords() {
        val cutoff = System.currentTimeMillis() - config.journalRetention.toMillis()
        val removable = committedRecords.values.filter { (it.committedAtMillis ?: Long.MAX_VALUE) < cutoff }
        removable.forEach { record ->
            committedRecords.remove(record.operationId)
            writeAsync(action = { journal.acknowledge(record.operationId) }, callback = { _, failure ->
                if (failure != null) committedRecords[record.operationId] = record
            })
        }
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
        player.sendMessage(messages.render(path, locale(player), values))
    }

    private fun locale(player: Player): String = player.locale().toLanguageTag()

    private fun java.time.Duration.toTicks(): Long = (toMillis() / 50L).coerceAtLeast(1L)

    private fun filterPrefix(values: List<String>, raw: String?): List<String> {
        val prefix = raw.orEmpty().lowercase(Locale.ROOT)
        return values.filter { it.startsWith(prefix) }.take(100)
    }

    private fun safeMaterialNames(): List<String> = Material.entries.asSequence().filter(safety::isSafeMaterial).map { it.name.lowercase(Locale.ROOT) }.toList()

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onInteract(event: PlayerInteractEvent) {
        val player = event.player
        if (operationLocks.isPlayerLocked(player.uniqueId)) {
            event.isCancelled = true
            return
        }
        val item = event.item ?: return
        if (event.hand == EquipmentSlot.HAND) {
            BuildBookCodec.read(item)?.let { data ->
                if (
                    event.action == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK ||
                    event.action == org.bukkit.event.block.Action.RIGHT_CLICK_AIR
                ) {
                    event.isCancelled = true
                    handleBookInteraction(player, item, data, event.action, event.clickedBlock?.location)
                    return
                }
            }
        }
        if (!isSelector(item)) return
        val clicked = event.clickedBlock ?: return
        val first = when (event.action) {
            org.bukkit.event.block.Action.LEFT_CLICK_BLOCK -> true
            org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK -> false
            else -> return
        }
        event.isCancelled = true
        try {
            setPosition(player, clicked.location, first)
        } catch (failure: BuilderUserFailure) {
            send(player, failure.path, failure.values)
        }
    }

    private fun handleBookInteraction(
        player: Player,
        item: ItemStack,
        data: BuildBookData,
        action: org.bukkit.event.block.Action,
        clickedLocation: Location?,
    ) {
        try {
            ensureAvailable(player)
            if (player.isSneaking) {
                BuildBookEditorGui.open(player)
                return
            }
            val current = BuildingManager.pending(player.uniqueId)
            if (action == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
                books.verifySchematic(data)
                val opened = BuildingManager.openPreview(player, checkNotNull(clickedLocation), data)
                    ?: throw BuilderUserFailure("book.invalid")
                if (current?.isExactOpenPreview(player, data) != true) {
                    send(
                        player,
                        "book.preview-opened",
                        mapOf(
                            "name" to messages.literal(data.title),
                            "state" to messages.render(if (data.draft) "book.state.draft" else "book.state.active", locale(player)),
                        ),
                    )
                }
                return
            }
            if (current?.isExactOpenPreview(player, data) != true) throw BuilderUserFailure("book.preview-required")
            if (data.draft) {
                books.handleCommand(player, listOf("activate"))
            } else {
                startPlayerBuildBook(player, current, item)
            }
        } catch (failure: BuilderUserFailure) {
            send(player, failure.path, failure.values)
        } catch (failure: Throwable) {
            error("Builder-book interaction failed for ${player.name}", failure)
            send(player, "book.failed")
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onJoin(event: PlayerJoinEvent) {
        if (!playerRecoveries.onPlayerAvailable(event.player)) books.onPlayerAvailable(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        discardPendingPlan(event.player.uniqueId)
        selections.clear(event.player.uniqueId)
        BuildingManager.closePreview(event.player.uniqueId)
        displayRenderer.clearPlayer(event.player.uniqueId)
        clipboardController.clear(event.player.uniqueId)
        books.onPlayerQuit(event.player.uniqueId)
        crown.clearPlayer(event.player.uniqueId)
        val operation = operationLocks.operation(event.player.uniqueId) ?: return
        if (operation.interruptionDeferred) return
        operation.cancelled = true
        if (operation.appliedChanges > 0 || operation.inventoryMutated) rollback(event.player, operation, "disconnect")
        else acknowledgeCancelled(operation)
    }

    internal fun rejectUnsafeAuctionSale(player: Player) {
        books.rejectUnsafeAuctionSale(player)
    }

    internal fun runtimeHealthContribution(): RuntimeHealthContribution = runtimeHealth.get()

    internal fun isPlayerLeaseActive(playerId: UUID): Boolean = operationLocks.isPlayerLocked(playerId)

    private fun publishRuntimeHealth() {
        val bookHealth = books.health()
        runtimeHealth.set(
            BuilderToolsRuntimeHealth.contribution(
                BuilderToolsRuntimeHealthInputs(
                    closed = closed,
                    recovering = recovering,
                    recoveryBlocked = recoveryBlocked || bookHealth.recoveryBlocked,
                    recoveryPlayers = playerRecoveries.pendingCount,
                    deliveryWaitingForSpace = bookHealth.deliveryWaitingForSpace,
                    reservationReleaseBacklog = bookHealth.reservationReleaseBacklog,
                    activeOperations = operationLocks.activeOperationCount,
                    bookLockedPlayers = operationLocks.bookLockedPlayerCount,
                    recoveryLockedPlayers = operationLocks.recoveryLockedPlayerCount,
                    landsRequired = config.requireLands,
                    landsAvailable = HookRegistry.landsHook != null,
                    coreProtectRequired = config.requireCoreProtect,
                    coreProtectAvailable = coreProtect != null,
                    shopRequired = config.shopEnabled,
                    shopAvailable = HookRegistry.shopPurchaseService != null,
                    bookContractsEnabled = config.bookContractsEnabled,
                    bookRegistryReady = bookHealth.registryReady,
                    bookRegistryFailed = bookHealth.registryFailed,
                    draftJournalReady = bookHealth.draftJournalReady,
                    draftJournalFailed = bookHealth.draftJournalFailed,
                ),
            ),
        )
    }

    private fun isSelector(item: ItemStack): Boolean {
        if (item.itemMeta?.persistentDataContainer?.has(wandKey, PersistentDataType.BYTE) == true) return true
        if (item.type != Material.ECHO_SHARD) return false
        return plainDisplayName(item) == "Инструмент демонтажа"
    }

    private fun plainDisplayName(item: ItemStack): String? = item.itemMeta?.displayName()?.let {
        PlainTextComponentSerializer.plainText().serialize(it)
    }

    override fun close() {
        if (closed) return
        closed = true
        publishRuntimeHealth()
        HandlerList.unregisterAll(this)
        crown.close()
        previews.close()
        operationLocks.operations().forEach { operation ->
            if (operation.interruptionDeferred) {
                finishOperation(operation)
                return@forEach
            }
            val player = Bukkit.getPlayer(operation.record.playerId)
            if (player != null && (operation.appliedChanges > 0 || operation.inventoryMutated)) rollback(player, operation, "plugin shutdown")
            else finishOperation(operation)
        }
        playerRecoveries.close()
        books.close()
        taskScope.close()
        closeStorageExecutor()
        shop.close()
        operationLocks.close()
        selections.clear()
        clipboardController.close()
        BuildingManager.installPreviewBridge(null)
        displayRenderer.close()
    }

    private fun closeStorageExecutor() {
        storageExecutor.shutdownNow()
        try {
            if (!storageExecutor.awaitTermination(STORAGE_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                warn("Builder-tools storage executor did not stop within {} seconds", STORAGE_SHUTDOWN_TIMEOUT_SECONDS)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            warn("Builder-tools storage executor shutdown wait was interrupted")
        }
    }

    private companion object {
        const val HEALTH_PUBLISH_PERIOD_TICKS = 20L
        const val STORAGE_SHUTDOWN_TIMEOUT_SECONDS = 5L
    }
}
