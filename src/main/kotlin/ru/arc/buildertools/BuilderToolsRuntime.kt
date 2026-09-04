package ru.arc.buildertools

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.type.Leaves
import org.bukkit.loot.LootTable
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
import ru.arc.autobuild.BuilderStoragePaths
import ru.arc.autobuild.gui.BuildBookEditorGui
import ru.arc.autobuild.BuildingManager
import ru.arc.autobuild.ConstructionSite
import ru.arc.autobuild.SystemBuildBookCatalog
import ru.arc.autobuild.SystemBuildBookDefinition
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
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
import java.time.Duration
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.nio.file.Files

internal sealed interface BuilderBookPlacementResult {
    data object Unchanged : BuilderBookPlacementResult
    data object SkippedUnsafe : BuilderBookPlacementResult
    data class Change(
        val block: BuilderBlockChange,
        val placementItem: ItemStack?,
        val refund: ItemStack?,
    ) : BuilderBookPlacementResult
}

internal data class BuilderBookPlannedProject(
    val plan: BuilderPlan,
    val project: BuilderConstructionProjectRecord,
)

private data class BuilderConstructionWorldMutation(
    val step: BuilderConstructionStep,
    val target: Block,
    val before: BlockData,
    val after: BlockData,
    val required: Boolean,
)

internal class BuilderToolsRuntime(
    private val plugin: JavaPlugin,
    private val config: BuilderToolsConfig,
    private val taskScope: LifecycleTaskScope = LifecycleTaskScope(),
    private val displayRenderer: BuilderDisplayRenderer = BuilderBlockDisplayRenderer(
        plugin,
        config.previewMaxPlanDisplays,
        config.previewBlockDisplayScale,
        config.previewPlanDisplayRange,
        config.previewGuidancePeriodTicks,
        config.messages(),
        taskScope,
    ),
    blockDataRotation: BuilderBlockDataRotation = PaperBuilderBlockDataRotation,
    draftStorage: BuilderDraftStorage = PlayerBuildBookDraftStorage,
    bookSchematicVerifier: BuilderBookSchematicVerifier = PlayerBuildBookSchematicVerifier,
    private val bookReplacementRefund: (Block) -> ItemStack? = BuilderDeconstructionRefunds::fromSilkTouch,
    private val systemBuildBookResolver: (BuildBookData) -> SystemBuildBookDefinition? =
        loadSystemBuildBookResolver(plugin, config),
    private val lootTableResolver: (NamespacedKey) -> LootTable? = Bukkit::getLootTable,
    private val lootTableAccess: BuilderLootTableAccess = PaperBuilderLootTableAccess,
    private val sendPlayerMessage: (Player, Component) -> Unit = { player, message -> player.sendMessage(message) },
) : Listener, CommandExecutor, TabCompleter, AutoCloseable {
    private val messages: LocalizedMiniMessage = config.messages()
    private val shop = BuilderShopCoordinator(config, messages)
    private val safety = BuilderBlockSafety(plugin, config.replaceableMaterials)
    private val planningHost = object : BuilderPlanningHost {
        override fun ensurePermission(player: Player, feature: BuilderFeature) = ensureFeaturePermission(player, feature)

        override fun requiredSelection(player: Player): BuilderSelection = this@BuilderToolsRuntime.requiredSelection(player)

        override fun world(worldId: UUID): World = requireWorld(worldId)

        override fun placementData(material: Material) = this@BuilderToolsRuntime.placementData(material)

        override fun ensureMutable(player: Player, block: Block, placing: Material?) =
            this@BuilderToolsRuntime.ensureMutable(player, block, placing)

        override fun createPlan(
            player: Player,
            kind: BuilderPlanKind,
            changes: List<BuilderBlockChange>,
            costs: List<BuilderItemAmount>,
            rewards: List<BuilderItemAmount>,
            skippedUnsafeBlocks: Int,
        ): BuilderPlan = newPlan(
            player = player,
            kind = kind,
            changes = changes,
            costs = costs,
            rewards = rewards,
            skippedUnsafeBlocks = skippedUnsafeBlocks,
        )

        override fun fail(path: String): Nothing = throw BuilderUserFailure(path)
    }
    private val coreProtect = BuilderCoreProtectBridge.resolve()
    private val journal = BuilderJournalStore(plugin.dataPath, config.maxChanges)
    private val constructionStore = BuilderConstructionProjectStore(plugin.dataPath, config.maxChanges)
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
        host = planningHost,
    )
    private val replaceController = BuilderReplaceController(
        safety = safety,
        maximumChanges = config.maxChanges,
        host = planningHost,
    )
    private val fenceConnectionController = BuilderFenceConnectionController(
        maximumChanges = config.maxChanges,
        host = planningHost,
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

            override fun canDeconstructWithoutTool(player: Player): Boolean =
                BuilderPermissionPolicy.canDeconstructWithoutTool(player::hasPermission)

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
    private val constructionPlayerLeases: BuilderConstructionPlayerLeases
    private val playerRecoveries: BuilderPlayerRecoveryCoordinator
    private val committedRecords = mutableMapOf<UUID, BuilderJournalRecord>()
    private val consumedUndoSources = mutableSetOf<UUID>()
    private val plannedConstructionProjects = mutableMapOf<UUID, BuilderConstructionProjectRecord>()
    private val constructionProjects = mutableMapOf<UUID, BuilderConstructionProjectRecord>()
    private val constructionPauseRequests = BuilderConstructionPauseRequests()
    private val constructionInstantRequests = mutableSetOf<UUID>()
    private val constructionMenus: BuilderConstructionMenuManager
    private val constructionProjectsMenu: BuilderConstructionProjectsMenuManager
    private val constructionSiteDisplays: BuilderConstructionSiteDisplayManager
    private val bookPreviewPresentation: BuilderBookPreviewPresentation
    private val bookHoldHints = BuilderBookHoldHintTracker()
    private val constructionWrites = mutableSetOf<UUID>()
    private val constructionCompletions = mutableSetOf<UUID>()
    private val constructionLocks = mutableSetOf<UUID>()
    private val constructionResources = BuilderConstructionResources(
        containerRadius = config.constructionContainerRadius,
        onlineRange = config.constructionOnlineInventoryRange,
        worldProvider = Bukkit::getWorld,
        onlinePlayerProvider = Bukkit::getPlayer,
        canOpenContainer = { playerId, block -> HookRegistry.landsHook?.canOpenContainer(playerId, block) ?: true },
        maxContainerProbesPerCall = config.constructionMaxContainerProbesPerTick,
        maxCachedContainersPerProject = config.constructionMaxCachedContainersPerProject,
        maxResolvedContainersPerCall = config.constructionMaxResolvedContainersPerCall,
    )
    private val constructionFeedbackSettings = BuilderConstructionFeedbackSettings(
        enabled = config.constructionEffectsEnabled,
        intervalBlocks = config.constructionEffectIntervalBlocks,
        soundsEnabled = config.constructionSoundsEnabled,
        soundVolume = config.constructionSoundVolume,
        soundPitch = config.constructionSoundPitch,
        particlesEnabled = config.constructionParticlesEnabled,
        particleCount = config.constructionParticleCount,
        particleSpread = config.constructionParticleSpread,
    )
    private val constructionPort = object : BuilderConstructionProjectPort {
        override fun currentBlockData(position: BuilderBlockPos): String {
            val world = Bukkit.getWorld(position.worldId)
                ?: throw IllegalStateException("Builder construction world is unavailable")
            if (!world.isChunkLoaded(position.x shr 4, position.z shr 4)) {
                throw BuilderConstructionTemporarilyUnavailableException()
            }
            return block(world, position).blockData.asString
        }

        override fun isStepApplied(step: BuilderConstructionStep): Boolean {
            if (currentBlockData(step.change.position) != step.change.afterBlockData) return false
            val rawKey = step.lootTableKey ?: return true
            val world = Bukkit.getWorld(step.change.position.worldId) ?: return false
            return lootTableAccess.matches(block(world, step.change.position), requiredLootTable(rawKey))
        }

        override fun canModify(
            project: BuilderConstructionProjectRecord,
            step: BuilderConstructionStep,
        ): Boolean {
            val playerId = project.playerId
            val change = step.change
            val world = Bukkit.getWorld(change.position.worldId) ?: return false
            if (!config.allowsWorld(world.name) || !world.isChunkLoaded(change.position.x shr 4, change.position.z shr 4)) {
                throw BuilderConstructionTemporarilyUnavailableException()
            }
            val target = block(world, change.position)
            if (!world.worldBorder.isInside(target.location)) return false
            val after = Bukkit.createBlockData(change.afterBlockData)
            val safeSystemContainer = step.lootTableKey != null && safety.isSafeSystemLootContainer(after)
            if (!after.material.isAir && !safety.isSafePlacement(after) && !safeSystemContainer) return false
            val replaceable = safety.isReplaceable(target)
            if (!replaceable && !safety.isSafeExisting(target)) return false
            return HookRegistry.landsHook?.canModify(
                playerId,
                target,
                after.material.takeUnless(Material::isAir),
            ) ?: true
        }

        override fun prepareInput(
            playerId: UUID,
            project: BuilderConstructionProjectRecord,
            input: BuilderItemAmount,
        ): BuilderResourceMutation? = constructionResources.prepareInput(playerId, project, input)

        override fun prepareOutput(
            playerId: UUID,
            project: BuilderConstructionProjectRecord,
            output: BuilderItemAmount,
        ): BuilderResourceMutation? = constructionResources.prepareOutput(playerId, project, output)

        override fun reconcileResource(
            project: BuilderConstructionProjectRecord,
            mutation: BuilderResourceMutation,
        ): BuilderResourceMutationResult = constructionResources.reconcile(project, mutation)

        override fun rollbackResource(
            project: BuilderConstructionProjectRecord,
            mutation: BuilderResourceMutation,
        ): BuilderResourceMutationResult = constructionResources.rollback(project, mutation)

        private fun atomicSteps(
            project: BuilderConstructionProjectRecord,
            step: BuilderConstructionStep,
        ): List<BuilderConstructionStep> {
            val after = Bukkit.createBlockData(step.change.afterBlockData)
            if (!BuilderBookMultiBlockPolicy.isPrimary(after)) return listOf(step)
            val companionPosition = BuilderBookMultiBlockPolicy.companionPosition(step.change.position, after)
                ?: return listOf(step)
            val companion = project.steps.singleOrNull { it.change.position == companionPosition }
                ?: return listOf(step)
            val companionAfter = Bukkit.createBlockData(companion.change.afterBlockData)
            return if (
                companion.requiredMaterial == null &&
                BuilderBookMultiBlockPolicy.matchingPair(after, companionAfter)
            ) {
                listOf(step, companion)
            } else {
                listOf(step)
            }
        }

        override fun apply(project: BuilderConstructionProjectRecord, step: BuilderConstructionStep) {
            val mutations = atomicSteps(project, step).map { atomicStep ->
                val change = atomicStep.change
                val world = Bukkit.getWorld(change.position.worldId)
                    ?: throw IllegalStateException("Builder construction world is unavailable")
                if (!world.isChunkLoaded(change.position.x shr 4, change.position.z shr 4)) {
                    throw BuilderConstructionTemporarilyUnavailableException()
                }
                val target = block(world, change.position)
                val before = Bukkit.createBlockData(change.beforeBlockData)
                val after = Bukkit.createBlockData(change.afterBlockData)
                val current = target.blockData.asString
                check(current == change.beforeBlockData || current == change.afterBlockData) {
                    "Builder construction block changed before atomic apply"
                }
                val required = current == change.beforeBlockData
                if (required) {
                    check(canModify(project, atomicStep)) {
                        "Builder construction atomic companion cannot be modified"
                    }
                }
                BuilderConstructionWorldMutation(atomicStep, target, before, after, required)
            }

            val changed = mutableListOf<BuilderConstructionWorldMutation>()
            try {
                mutations.filter(BuilderConstructionWorldMutation::required).forEach { mutation ->
                    mutation.target.setBlockData(mutation.after, false)
                    changed += mutation
                }
                mutations.forEach { mutation ->
                    mutation.step.lootTableKey?.let { lootTableKey ->
                        lootTableAccess.apply(mutation.target, requiredLootTable(lootTableKey))
                    }
                }
            } catch (failure: Throwable) {
                changed.asReversed().forEach { mutation ->
                    runCatching { mutation.target.setBlockData(mutation.before, false) }
                        .exceptionOrNull()
                        ?.let(failure::addSuppressed)
                }
                throw failure
            }

            changed.forEach { mutation ->
                coreProtect?.logChange(project.playerName, mutation.target.location, mutation.before, mutation.after)
            }
            val current = mutations.first()
            BuilderConstructionFeedback.play(
                current.target.world,
                current.target.location,
                current.before,
                current.after,
                project.cursor,
                constructionFeedbackSettings,
            )
        }
    }
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
        constructionPlayerLeases = BuilderConstructionPlayerLeases(operationLocks)
        var initializedPreviews: BuilderPreviewSessions? = null
        var initializedCrown: BuilderCrownController? = null
        var initializedBooks: BuilderBookLifecycle? = null
        var initializedPlayerRecoveries: BuilderPlayerRecoveryCoordinator? = null
        var initializedConstructionMenus: BuilderConstructionMenuManager? = null
        var initializedConstructionProjectsMenu: BuilderConstructionProjectsMenuManager? = null
        var initializedConstructionSiteDisplays: BuilderConstructionSiteDisplayManager? = null
        var initializedBookPreviewPresentation: BuilderBookPreviewPresentation? = null
        try {
            constructionMenus = BuilderConstructionMenuManager(
                plugin = plugin,
                settings = config.constructionMenuSettings(),
                messages = messages,
                taskScope = taskScope,
                projectLookup = constructionProjects::get,
                canControl = ::canControlConstruction,
                requestPaused = ::requestConstructionPaused,
                canBuildInstantly = { it.hasPermission(CONSTRUCTION_ADMIN_PERMISSION) },
                requestInstant = ::requestInstantConstruction,
            ).also { initializedConstructionMenus = it }
            constructionProjectsMenu = BuilderConstructionProjectsMenuManager(
                plugin = plugin,
                messages = messages,
                taskScope = taskScope,
                projects = { playerId -> constructionProjects.values.filter { it.playerId == playerId } },
                onTeleport = ::teleportToConstruction,
                onInspect = constructionMenus::open,
            ).also { initializedConstructionProjectsMenu = it }
            constructionSiteDisplays = BuilderConstructionSiteDisplayManager(
                plugin = plugin,
                settings = config.constructionSiteDisplaySettings(),
                messages = messages,
                projectLookup = constructionProjects::get,
                onInspect = constructionMenus::open,
            ).also { initializedConstructionSiteDisplays = it }
            bookPreviewPresentation = BuilderBookPreviewPresentation(
                plugin = plugin,
                renderer = displayRenderer,
                messages = messages,
                host = object : BuilderBookPreviewPresentationHost {
                    override fun adjust(player: Player, adjustment: ru.arc.autobuild.BuildBookPreviewAdjustment): ConstructionSite? =
                        adjustBookPreview(player, adjustment)

                    override fun prepare(
                        player: Player,
                        site: ConstructionSite,
                        complete: (BuilderBookPreviewConfirmation?) -> Unit,
                    ) = prepareBookPreviewConfirmation(player, site, complete)

                    override fun currentConfirmation(player: Player): BuilderBookPreviewConfirmation? =
                        currentBookPreviewConfirmation(player)

                    override fun confirm(player: Player): Boolean = confirmBookPreview(player)

                    override fun complete(player: Player, kind: BuilderBookPreviewConfirmationKind) {
                        if (kind == BuilderBookPreviewConfirmationKind.DRAFT_ACTIVATION) {
                            BuildingManager.closePreview(player.uniqueId)
                        }
                    }

                    override fun restore(
                        player: Player,
                        snapshot: ru.arc.autobuild.ConstructionSiteSnapshot,
                    ): ConstructionSite? = restoreBookPreview(player, snapshot)

                    override fun cancel(player: Player) {
                        books.cancelPreviewActivation(player.uniqueId)
                        runBookPreviewAction(player) { cancelPlan(player) }
                    }
                },
                panelHeightOffset = config.constructionSitePanelHeightOffset,
                panelFrontOffset = config.constructionSitePanelFrontOffset,
                panelInteractionWidth = config.constructionSitePanelInteractionWidth,
                panelInteractionHeight = config.constructionSitePanelInteractionHeight,
                panelLineWidth = config.constructionSitePanelLineWidth,
                panelBackgroundColor = Color.fromARGB(
                    config.constructionSitePanelBackgroundColor.removePrefix("#").toLong(16).toInt(),
                ),
                panelGlowColor = Color.fromRGB(config.constructionSiteGlowColor.removePrefix("#").toInt(16)),
                viewRange = config.constructionSiteViewRange,
                materialLineLimit = config.bookPlayerMaterialsSummaryLimit,
            ).also { initializedBookPreviewPresentation = it }
            BuildingManager.installPreviewBridge(bookPreviewPresentation)
            Bukkit.getPluginManager().registerEvents(this, plugin)
            checkNotNull(
                taskScope.runTimer(20L, 20L) {
                    BuildingManager.expirePreviews(System.currentTimeMillis()).forEach { playerId ->
                        Bukkit.getPlayer(playerId)?.takeIf(Player::isOnline)?.let { player ->
                            send(player, "book.preview-expired")
                        }
                    }
                    Bukkit.getOnlinePlayers().forEach(::refreshBookHoldHint)
                },
            ) { "Builder build-book preview expiry task was not scheduled" }
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
                        this@BuilderToolsRuntime.startConstructionProject(
                            player,
                            plannedConstructionProjects.remove(plan.id)
                                ?: throw IllegalStateException("Builder construction project metadata is missing"),
                            plannedMode,
                        )

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
                retryPeriodTicks = config.playerRecoveryRetryPeriodTicks,
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
            checkNotNull(taskScope.runTimer(0L, config.healthRefreshPeriodTicks, ::publishRuntimeHealth)) {
                "Builder-tools health publication task was not scheduled"
            }
            loadRecoveryState()
            books.start()
            loadConstructionProjects()
            checkNotNull(
                taskScope.runTimer(
                    config.constructionTickPeriod,
                    config.constructionTickPeriod,
                    ::tickConstructionProjects,
                ),
            ) { "Builder construction project task was not scheduled" }
        } catch (failure: Throwable) {
            HandlerList.unregisterAll(this)
            initializedPlayerRecoveries?.close()
            initializedBooks?.close()
            initializedCrown?.close()
            initializedPreviews?.close()
            initializedConstructionSiteDisplays?.close()
            initializedBookPreviewPresentation?.close()
            initializedConstructionProjectsMenu?.close()
            initializedConstructionMenus?.close()
            taskScope.close()
            operationLocks.close()
            closeStorageExecutor()
            shop.close()
            BuildingManager.clearPreviews()
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
            return filterPrefix(
                BuilderRootCommand.entries.filter { rootCommandAvailable(sender, it) }.map(BuilderRootCommand::literal),
                args[0],
            )
        }
        val root = BuilderRootCommand.parse(args.firstOrNull()) ?: return emptyList()
        if (!rootCommandAvailable(sender, root)) return emptyList()
        if (root == BuilderRootCommand.CROWN) return crown.tabComplete(args)
        if (root == BuilderRootCommand.REPLACE) {
            val suggestions = when (args.size) {
                2, 3 -> safeMaterialNames()
                4 -> listOf("confirm")
                else -> emptyList()
            }
            return filterPrefix(suggestions, args.lastOrNull())
        }
        if (args.size != 2) return emptyList()
        val suggestions = when (root) {
            BuilderRootCommand.CONFIRM -> listOf("buy")
            BuilderRootCommand.DISCONNECT -> listOf("confirm")
            BuilderRootCommand.PASTE -> listOf("rotate", "left", "right")
            BuilderRootCommand.BOOK -> listOf("guide", "status", "draft", "activate", "copy", "sell", "confirm", "cancel")
            BuilderRootCommand.FILL -> safeMaterialNames()
            else -> emptyList()
        }
        return filterPrefix(suggestions, args[1])
    }

    private fun handleBuilder(player: Player, args: Array<out String>) {
        val root = BuilderRootCommand.parse(args.firstOrNull()) ?: BuilderRootCommand.HELP
        if (root == BuilderRootCommand.PROJECTS) {
            if (!hasUsePermission(player)) throw BuilderUserFailure("errors.no-permission")
            constructionProjectsMenu.open(player)
            return
        }
        ensureAvailable(player)
        when (root) {
            BuilderRootCommand.HELP -> messages.renderLines("help", locale(player)).forEach { sendPlayerMessage(player, it) }
            BuilderRootCommand.WAND -> giveWand(player)
            BuilderRootCommand.CLEAR -> clearSelection(player)
            BuilderRootCommand.FILL -> preparePlan(player, fillController.plan(player, materialArgument(player, args.getOrNull(1))))
            BuilderRootCommand.REPLACE -> {
                val request = replaceRequest(args)
                val plan = replaceController.plan(player, request.source, request.target)
                if (request.confirmed) confirmImmediately(player, plan) else preparePlan(player, plan)
            }
            BuilderRootCommand.DISCONNECT -> {
                val confirmed = when (args.getOrNull(1)?.lowercase(Locale.ROOT)) {
                    null -> false
                    "confirm" -> true
                    else -> throw BuilderUserFailure("errors.material")
                }
                val plan = fenceConnectionController.planDisconnect(player)
                if (confirmed) confirmImmediately(player, plan) else preparePlan(player, plan)
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
                else -> messages.renderLines("help", locale(player)).forEach { sendPlayerMessage(player, it) }
            }
            BuilderRootCommand.CANCEL -> cancelPlan(player)
            BuilderRootCommand.UNDO -> prepareUndo(player)
            BuilderRootCommand.STATUS -> showStatus(player)
            BuilderRootCommand.PROJECTS -> error("Projects command is handled before operational context checks")
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

    private fun rootCommandAvailable(player: Player, command: BuilderRootCommand): Boolean = when (command) {
        BuilderRootCommand.FILL -> BuilderPermissionPolicy.canUse(BuilderFeature.FILL, player::hasPermission)
        BuilderRootCommand.REPLACE -> BuilderPermissionPolicy.canUse(BuilderFeature.REPLACE, player::hasPermission)
        BuilderRootCommand.DISCONNECT -> BuilderPermissionPolicy.canUse(BuilderFeature.FENCE_DISCONNECT, player::hasPermission)
        BuilderRootCommand.COPY -> BuilderPermissionPolicy.canUse(BuilderFeature.COPY, player::hasPermission)
        BuilderRootCommand.PASTE -> BuilderPermissionPolicy.canUse(BuilderFeature.PASTE, player::hasPermission)
        BuilderRootCommand.DECONSTRUCT -> BuilderPermissionPolicy.canUse(BuilderFeature.DECONSTRUCT, player::hasPermission)
        BuilderRootCommand.CROWN -> BuilderPermissionPolicy.canUse(BuilderFeature.CROWN, player::hasPermission)
        BuilderRootCommand.BOOK -> BuilderPermissionPolicy.canUseBook(player::hasPermission)
        else -> true
    }

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

    private fun planBuildBook(player: Player, site: ConstructionSite, book: ItemStack): BuilderBookPlannedProject {
        if (!player.hasPermission("arcbuild.book.use")) throw BuilderUserFailure("errors.no-permission")
        val data = site.bookData
        val systemDefinition = if (data.playerCreated) {
            if (data.draft) throw BuilderUserFailure("book.unactivated")
            if (data.deliveryPending) throw BuilderUserFailure("book.delivery-pending")
            if (!data.available) throw BuilderUserFailure("book.invalid")
            books.verifySchematic(data)
            null
        } else {
            systemBuildBookResolver(data) ?: throw BuilderUserFailure("book.invalid")
        }
        if (BuilderBookAuctionTokenCodec.read(book) != null) throw BuilderUserFailure("book.auction-locked")
        if (!BuildBookCodec.matches(book, data)) throw BuilderUserFailure("book.missing")
        if (site.building.volume > config.maxScanVolume) throw BuilderUserFailure("errors.selection-too-large")

        val lootTableKey = systemDefinition?.containerLootTableKey?.also { requiredLootTable(it) }
        val cells = site.relativePositionsBottomUp().map { relative ->
            val after = rotateBlockData(
                BukkitAdapter.adapt(site.sourceBlock(relative)),
                site.fullRotation,
            )
            val target = site.worldLocation(relative).block
            BuilderBookPlannedCell(
                position = BuilderBlockPos(target.world.uid, target.x, target.y, target.z),
                after = after,
                placement = planBuildBookBlock(
                    player,
                    target,
                    after,
                    allowSystemLootContainer = lootTableKey != null,
                ),
            )
        }.toList()
        val rejectedMultiBlocks = BuilderBookMultiBlockPolicy.rejectedPositions(cells)
        val skippedUnsafe = cells.count { cell ->
            cell.placement == BuilderBookPlacementResult.SkippedUnsafe ||
                cell.position in rejectedMultiBlocks && cell.placement is BuilderBookPlacementResult.Change
        }
        val orderedCells = BuilderBookMultiBlockPolicy.primaryFirst(
            cells.filterNot { it.position in rejectedMultiBlocks },
        )
        val placements = orderedCells.asSequence().mapNotNull { cell ->
            val placement = cell.placement as? BuilderBookPlacementResult.Change ?: return@mapNotNull null
            BuilderBookPlannedChange(
                change = placement.block,
                placementItem = placement.placementItem,
                refund = placement.refund,
                lootTableKey = lootTableKey.takeIf { safety.isSafeSystemLootContainer(cell.after) },
            )
        }.take(config.maxChanges + 1).toList()
        requireChanges(placements.map(BuilderBookPlannedChange::change))
        val construction = BuilderBookConstructionCosts.calculate(
            book,
            data,
            player.gameMode,
            placements,
            systemMaterialsIncluded = systemDefinition?.materialsIncluded == true,
        )
        val plan = newPlan(
            player = player,
            kind = BuilderPlanKind.BUILD_BOOK,
            changes = construction.steps.map(BuilderConstructionStep::change),
            costs = construction.costs,
            rewards = construction.rewards,
            bookBlueprintId = data.blueprintId,
            bookInstanceId = data.instanceId,
            bookInstanceGeneration = data.instanceGeneration,
            bookBuildingId = data.buildingId.takeIf { data.registered },
            bookSchematicSha256 = data.schematicSha256.takeIf { data.registered },
            skippedUnsafeBlocks = skippedUnsafe,
        )
        return BuilderBookPlannedProject(
            plan = plan,
            project = BuilderConstructionProjectRecord(
                projectId = plan.id,
                playerId = player.uniqueId,
                playerName = player.name,
                projectTitle = data.title,
                plan = plan,
                steps = construction.steps,
                bookCost = construction.bookCost,
                state = BuilderConstructionProjectState.PREPARED,
                cursor = 0,
                createdAtMillis = plan.createdAtMillis,
                updatedAtMillis = plan.createdAtMillis,
            ).validated(config.maxChanges),
        )
    }

    internal fun planBuildBookBlock(
        player: Player,
        block: Block,
        after: org.bukkit.block.data.BlockData,
        allowSystemLootContainer: Boolean = false,
    ): BuilderBookPlacementResult {
        val safeSystemContainer = allowSystemLootContainer && safety.isSafeSystemLootContainer(after)
        if (!after.material.isAir && !safety.isSafePlacement(after) && !safeSystemContainer) {
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
            BuilderPlacementCost.itemOrNull(after),
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

    private fun replaceRequest(args: Array<out String>): ReplaceRequest {
        if (args.size !in 3..4 || args.size == 4 && !args[3].equals("confirm", true)) {
            throw BuilderUserFailure("errors.material")
        }
        return ReplaceRequest(
            source = explicitMaterialArgument(args[1]),
            target = explicitMaterialArgument(args[2]),
            confirmed = args.size == 4,
        )
    }

    private fun explicitMaterialArgument(raw: String): Material =
        Material.matchMaterial(raw) ?: Material.matchMaterial(raw.uppercase(Locale.ROOT))
        ?: throw BuilderUserFailure("errors.material")

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

    private fun preparePlan(player: Player, plan: BuilderPlan, announce: Boolean = true) {
        preflightPlan(player, plan)
        crown.clearAnchor(player.uniqueId)
        previews.open(
            player = player,
            plan = BuilderPendingPlan(plan, player.gameMode),
            expireAfterTicks = config.planTtl.toTicks(),
        )
        if (announce) showPlanSummary(player, plan, includeShop = true)
    }

    private fun showPlanSummary(player: Player, plan: BuilderPlan, includeShop: Boolean) {
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
        if (plan.kind == BuilderPlanKind.BUILD_BOOK) {
            player.showTitle(
                Title.title(
                    messages.render("book.plan-ready.title", locale(player)),
                    messages.render("book.plan-ready.subtitle", locale(player)),
                    config.previewPlanTitleFadeInTicks,
                    config.previewPlanTitleStayTicks,
                    config.previewPlanTitleFadeOutTicks,
                ),
            )
        }
        val marketShown = includeShop && shop.preview(player, plan)
        if (!marketShown) send(player, "plan.actions.ready")
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
        val construction = plannedConstructionProjects[plan.id]
        val immediateCosts = construction?.let { listOf(it.bookCost) } ?: plan.costs
        val immediateRewards = if (construction == null) plan.rewards else emptyList()
        val immediateToolFingerprint = if (construction == null) plan.toolFingerprintBase64 else null
        val immediateToolDamage = if (construction == null) plan.toolDamage else 0
        val canApplyNow = BuilderInventory.canApply(
            player,
            immediateCosts,
            immediateRewards,
            immediateToolFingerprint,
            immediateToolDamage,
        )
        if (!canApplyNow) {
            if (construction != null) throw BuilderUserFailure("book.missing")
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

    private fun startPlayerBuildBook(
        player: Player,
        site: ConstructionSite,
        book: ItemStack,
        announce: Boolean = true,
    ): Boolean = try {
        ensureBuildBookAvailable(player)
        val planned = planBuildBook(player, site, book)
        site.cancelSilently()
        plannedConstructionProjects[planned.plan.id] = planned.project
        try {
            preparePlan(player, planned.plan, announce)
        } catch (failure: Throwable) {
            plannedConstructionProjects.remove(planned.plan.id)
            throw failure
        }
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

    private fun adjustBookPreview(
        player: Player,
        adjustment: ru.arc.autobuild.BuildBookPreviewAdjustment,
    ): ConstructionSite? = runBookPreviewAction(player) {
        val site = BuildingManager.pending(player.uniqueId) ?: throw BuilderUserFailure("errors.expired")
        requireMatchingPreviewBook(player, site)
        BuildingManager.adjustPendingPlacement(player, adjustment, System.currentTimeMillis())
            ?: throw BuilderUserFailure("errors.expired")
    }

    private fun prepareBookPreviewConfirmation(
        player: Player,
        site: ConstructionSite,
        complete: (BuilderBookPreviewConfirmation?) -> Unit,
    ) {
        val located = runBookPreviewAction(player) { requireMatchingPreviewBook(player, site) }
            ?: return complete(null)
        val data = located.data
        if (data.draft) {
            try {
                books.preparePreviewActivation(player, located) { blueprint ->
                    complete(blueprint?.toPreviewConfirmation())
                }
            } catch (failure: BuilderUserFailure) {
                send(player, failure.path, failure.values)
                complete(null)
            } catch (failure: Throwable) {
                error("Builder-book preview activation quote failed for ${player.name}", failure)
                send(player, "book.failed")
                complete(null)
            }
            return
        }
        if (!startPlayerBuildBook(player, site, located.item, announce = false)) return complete(null)
        complete(currentBookPreviewConfirmation(player))
    }

    private fun currentBookPreviewConfirmation(player: Player): BuilderBookPreviewConfirmation? {
        val plan = previews.plan(player.uniqueId)?.takeIf { it.kind == BuilderPlanKind.BUILD_BOOK }
        if (plan != null) {
            val project = plannedConstructionProjects[plan.id] ?: return null
            return BuilderBookPreviewConfirmation(
                kind = BuilderBookPreviewConfirmationKind.CONSTRUCTION,
                blockCount = plan.changes.size,
                title = project.projectTitle ?: project.plan.bookBuildingId ?: "Постройка",
                cooldownRemaining = bookApplicationCooldownRemaining(player),
                requiredMaterials = project.steps
                    .mapNotNull(BuilderConstructionStep::requiredMaterial)
                    .groupBy { it.itemBase64 to it.materialKey }
                    .values
                    .map { grouped ->
                        grouped.first().copy(amount = grouped.sumOf(BuilderItemAmount::amount)).validated()
                    }
                    .sortedBy(BuilderItemAmount::materialKey),
            )
        }
        val site = BuildingManager.pending(player.uniqueId) ?: return null
        return books.currentPreviewActivation(player, site.bookData)?.toPreviewConfirmation()
    }

    private fun confirmBookPreview(player: Player): Boolean {
        val confirmation = currentBookPreviewConfirmation(player) ?: return false
        if (confirmation.kind == BuilderBookPreviewConfirmationKind.DRAFT_ACTIVATION) {
            val site = BuildingManager.pending(player.uniqueId) ?: return false
            return books.confirmPreviewActivation(player, site.bookData)
        }
        return runBookPreviewAction(player) {
            confirm(player, buildBook = true)
            previews.plan(player.uniqueId)?.kind != BuilderPlanKind.BUILD_BOOK
        } ?: false
    }

    private fun BuilderBookBlueprint.toPreviewConfirmation() = BuilderBookPreviewConfirmation(
        kind = BuilderBookPreviewConfirmationKind.DRAFT_ACTIVATION,
        blockCount = blockCount,
        title = title,
        cooldownRemaining = Duration.ZERO,
        requiredMaterials = emptyList(),
        materialCostMinor = materialCostMinor,
        constructionFeeMinor = constructionFeeMinor,
        issuePriceMinor = issuePriceMinor,
    )

    private fun restoreBookPreview(
        player: Player,
        snapshot: ru.arc.autobuild.ConstructionSiteSnapshot,
    ): ConstructionSite? = runBookPreviewAction(player) {
        requireMatchingPreviewBook(player, snapshot.bookData)
        books.cancelPreviewActivation(player.uniqueId)
        discardPreparedBookPlan(player.uniqueId)
        BuildingManager.restorePreview(snapshot, System.currentTimeMillis())
            ?: throw BuilderUserFailure("errors.expired")
    }

    private fun requireMatchingPreviewBook(player: Player, site: ConstructionSite): BuilderLocatedBook =
        requireMatchingPreviewBook(player, site.bookData)

    private fun requireMatchingPreviewBook(player: Player, expected: BuildBookData): BuilderLocatedBook =
        BuilderBookInventoryLocator.find(player, expected) { slot, item, raw ->
            canonicalBook(player, slot, item, raw)
        } ?: throw BuilderUserFailure("book.missing")

    private fun <T> runBookPreviewAction(player: Player, action: () -> T): T? = try {
        action()
    } catch (failure: BuilderUserFailure) {
        send(player, failure.path, failure.values)
        null
    } catch (failure: IllegalArgumentException) {
        warn("Builder-book preview action was rejected for {}: {}", player.name, failure.message)
        send(player, "book.failed")
        null
    } catch (failure: Throwable) {
        error("Builder-book preview action failed for ${player.name}", failure)
        send(player, "book.failed")
        null
    }

    private fun confirm(player: Player, buyMissing: Boolean = false, buildBook: Boolean = false) {
        val pending = previews[player.uniqueId] ?: throw BuilderUserFailure("errors.expired")
        val plan = pending.plan
        if (buildBook || plan.kind == BuilderPlanKind.BUILD_BOOK) ensureBuildBookAvailable(player) else ensureAvailable(player)
        if (operationLocks.isPlayerLocked(player.uniqueId)) throw BuilderUserFailure("errors.busy")
        if (plan.expiresAtMillis <= System.currentTimeMillis()) {
            discardPendingPlan(player.uniqueId)
            throw BuilderUserFailure("errors.expired")
        }
        val plannedMode = pending.gameMode
        if (player.gameMode != plannedMode) {
            discardPendingPlan(player.uniqueId)
            throw BuilderUserFailure("errors.game-mode-changed")
        }
        val construction = plannedConstructionProjects[plan.id]
        if (construction != null) ensureBookApplicationCooldown(player)
        revalidatePlan(
            player,
            plan,
            construction?.steps.orEmpty()
                .asSequence()
                .filter { it.lootTableKey != null }
                .mapTo(mutableSetOf()) { it.change.position },
        )
        if (buyMissing && construction != null) throw BuilderUserFailure("errors.shop-not-supported")
        if (buyMissing) {
            when (val result = shop.procure(player, plan)) {
                BuilderShopConfirmation.Ready -> Unit
                is BuilderShopConfirmation.Rejected -> throw BuilderUserFailure(
                    result.messagePath,
                    result.values,
                )
            }
        }
        val immediateCosts = construction?.let { listOf(it.bookCost) } ?: plan.costs
        val immediateRewards = if (construction == null) plan.rewards else emptyList()
        val immediateToolFingerprint = if (construction == null) plan.toolFingerprintBase64 else null
        val immediateToolDamage = if (construction == null) plan.toolDamage else 0
        if (!BuilderInventory.canApply(
                player,
                immediateCosts,
                immediateRewards,
                immediateToolFingerprint,
                immediateToolDamage,
            )
        ) {
            throw BuilderUserFailure("errors.inventory")
        }
        if (construction != null) {
            if (constructionProjects.values.any { it.playerId == player.uniqueId && !it.terminal }) {
                throw BuilderUserFailure("errors.busy")
            }
            previews.remove(player.uniqueId, pending)
            shop.clear(player.uniqueId)
            crown.clearAnchor(player.uniqueId)
            if (plan.bookInstanceId == null) {
                startConstructionProject(player, construction, pending.gameMode)
            } else {
                books.reserveForBuild(player, plan, pending.gameMode)
            }
            return
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

    private fun startConstructionProject(
        player: Player,
        planned: BuilderConstructionProjectRecord,
        plannedMode: GameMode,
    ) {
        ensureBookApplicationCooldown(player)
        plannedConstructionProjects.remove(planned.projectId)
        if (player.gameMode != plannedMode) {
            books.releasePlanReservation(planned.plan)
            throw BuilderUserFailure("errors.game-mode-changed")
        }
        if (constructionProjects.values.any { it.playerId == player.uniqueId && !it.terminal }) {
            books.releasePlanReservation(planned.plan)
            throw BuilderUserFailure("errors.busy")
        }
        val prepared = planned.copy(
            sitePanelFace = BuilderConstructionSiteDisplayLayout.nearestFace(
                planned,
                player.location.x,
                player.location.z,
            ),
        ).validated(config.maxChanges)
        var durablePrepared: BuilderConstructionProjectRecord? = null
        var attemptedTarget: BuilderConstructionProjectRecord? = null
        var constructionLeaseHeld = false
        try {
            val activationMutation = checkNotNull(
                constructionResources.preparePlayerDebit(player.uniqueId, prepared, prepared.bookCost),
            ) { "Builder construction book disappeared before durable preparation" }
            val applicationStartedAtMillis = System.currentTimeMillis()
            val activationPrepared = prepared.activationPrepared(activationMutation).copy(
                applicationStartedAtMillis = applicationStartedAtMillis,
                updatedAtMillis = applicationStartedAtMillis,
            ).validated(config.maxChanges)
            check(lockConstruction(activationPrepared)) { "Builder construction area is already locked" }
            val now = System.currentTimeMillis()
            durablePrepared = constructionStore.commit(activationPrepared.copy(updatedAtMillis = now))
            check(
                constructionPlayerLeases.acquire(
                    durablePrepared.projectId,
                    durablePrepared.playerId,
                    durablePrepared.pendingResourceMutation,
                ),
            ) { "Builder construction activation resource is already locked" }
            constructionLeaseHeld = true
            attemptedTarget = BuilderConstructionProjectController.tick(
                durablePrepared,
                System.currentTimeMillis(),
                constructionPort,
            )
            player.updateInventory()
            val current = if (attemptedTarget == null) {
                durablePrepared
            } else {
                constructionStore.transition(durablePrepared, attemptedTarget)
            }
            constructionProjects[current.projectId] = current
            constructionSiteDisplays.upsert(current)
            if (current.state != BuilderConstructionProjectState.RECOVERY_REQUIRED) {
                constructionPlayerLeases.release(current.projectId)
                constructionLeaseHeld = false
            }
            when (current.state) {
                BuilderConstructionProjectState.ACTIVE -> send(
                    player,
                    "construction.started",
                    mapOf("count" to messages.literal(current.steps.size)),
                )
                BuilderConstructionProjectState.CANCELLED -> {
                    books.releasePlanReservation(current.plan)
                    constructionResources.forget(current.projectId)
                    unlockConstruction(current)
                    send(player, "book.failed")
                }
                BuilderConstructionProjectState.RECOVERY_REQUIRED -> send(player, "construction.recovery-required")
                BuilderConstructionProjectState.PREPARED -> send(player, "construction.started", mapOf("count" to messages.literal(current.steps.size)))
                else -> error("Unexpected builder construction activation state: ${current.state}")
            }
        } catch (failure: Throwable) {
            val durable = durablePrepared
            if (durable != null) {
                constructionProjects[durable.projectId] = durable
                constructionSiteDisplays.upsert(durable)
                val postEffectRejection = attemptedTarget?.let { target ->
                    BuilderConstructionTransitionFailurePolicy.requiresRecovery(durable, target)
                } == true
                if (failure is BuilderConstructionProjectTransitionRejectedException && postEffectRejection) {
                    recoverRejectedConstructionMutation(durable, failure)
                } else if (failure is BuilderConstructionProjectUnknownOutcomeException || postEffectRejection) {
                    recoveryBlocked = true
                } else if (constructionLeaseHeld) {
                    constructionPlayerLeases.release(durable.projectId)
                    constructionLeaseHeld = false
                }
                error("Builder construction activation is awaiting durable recovery for ${prepared.projectId}", failure)
                send(player, "construction.recovery-required")
                return
            }
            if (failure is BuilderConstructionProjectUnknownOutcomeException) {
                recoveryBlocked = true
                error("Builder construction initial durable commit has an unknown outcome for ${prepared.projectId}", failure)
                send(player, "construction.recovery-required")
                return
            }
            books.releasePlanReservation(prepared.plan)
            unlockConstruction(prepared)
            error("Builder construction activation failed for ${prepared.projectId}", failure)
            throw BuilderUserFailure("book.failed")
        }
    }

    private fun loadConstructionProjects() {
        try {
            constructionStore.loadAll().forEach { loaded ->
                val now = System.currentTimeMillis()
                val normalized = BuilderConstructionRecoveryPolicy.normalizeLoaded(loaded, now)
                val safelyResumed = if (loaded.state == BuilderConstructionProjectState.RECOVERY_REQUIRED) {
                    BuilderConstructionRecoveryPolicy.resumeAppliedNoExchangeStep(normalized, now, constructionPort)
                } else {
                    normalized
                }
                val record = if (safelyResumed == loaded) loaded else constructionStore.transition(loaded, safelyResumed)
                if (safelyResumed != normalized) {
                    info(
                        debugLine.line(
                            "event" to "construction_reconciled_applied_companion",
                            "operation" to safelyResumed.projectId,
                            "cursor" to safelyResumed.cursor,
                        ),
                    )
                }
                check(record.terminal || lockConstruction(record)) {
                    "Builder construction area overlaps an existing operation: ${record.projectId}"
                }
                if (!record.terminal && record.pendingResourceMutation != null) {
                    check(
                        constructionPlayerLeases.acquire(
                            record.projectId,
                            record.playerId,
                            record.pendingResourceMutation,
                        ),
                    ) { "Builder construction receipt source overlaps another active flow: ${record.projectId}" }
                }
                constructionProjects[record.projectId] = record
                constructionSiteDisplays.upsert(record)
                when (record.state) {
                    BuilderConstructionProjectState.COMPLETED -> if (record.completionFinalizedAtMillis == null) {
                        finalizeConstructionCompletion(record)
                    }
                    BuilderConstructionProjectState.RECOVERY_REQUIRED -> {
                        Bukkit.getPlayer(record.playerId)?.takeIf(Player::isOnline)?.let {
                            send(it, "construction.recovery-required")
                        }
                    }
                    else -> Unit
                }
            }
        } catch (failure: Throwable) {
            recoveryBlocked = true
            error("Builder construction project recovery failed", failure)
        }
    }

    private fun tickConstructionProjects() {
        if (closed || recoveryBlocked) return
        val now = System.currentTimeMillis()
        plannedConstructionProjects.entries.removeIf { (_, project) -> project.plan.expiresAtMillis <= now }
        val candidates = constructionProjects.values.filter { record ->
            record.state == BuilderConstructionProjectState.PREPARED ||
                record.state == BuilderConstructionProjectState.ACTIVE ||
                record.state == BuilderConstructionProjectState.WAITING_MATERIALS ||
                record.state == BuilderConstructionProjectState.INPUT_PREPARED ||
                record.state == BuilderConstructionProjectState.WORLD_PREPARED ||
                record.state == BuilderConstructionProjectState.OUTPUT_PENDING ||
                record.state == BuilderConstructionProjectState.WAITING_OUTPUT_SPACE ||
                record.state == BuilderConstructionProjectState.DELIVERING_OUTPUT ||
                canRetryAppliedConstructionRecovery(record)
        }
        candidates.forEach { expected ->
            if (!constructionWrites.add(expected.projectId)) return@forEach
            if (
                !constructionPlayerLeases.acquire(
                    expected.projectId,
                    expected.playerId,
                    expected.pendingResourceMutation,
                )
            ) {
                constructionWrites.remove(expected.projectId)
                return@forEach
            }
            val target = try {
                if (expected.state == BuilderConstructionProjectState.RECOVERY_REQUIRED) {
                    BuilderConstructionRecoveryPolicy.resumeAppliedNoExchangeStep(
                        expected,
                        System.currentTimeMillis(),
                        constructionPort,
                    ).takeUnless { it == expected }
                } else {
                    constructionPauseRequests.pauseTarget(expected, System.currentTimeMillis())
                        ?: BuilderConstructionProjectController.tick(expected, System.currentTimeMillis(), constructionPort)
                }
            } catch (failure: Throwable) {
                constructionWrites.remove(expected.projectId)
                constructionPlayerLeases.release(expected.projectId)
                error("Builder construction tick failed for ${expected.projectId}", failure)
                return@forEach
            }
            if (target == null) {
                constructionWrites.remove(expected.projectId)
                constructionPlayerLeases.release(expected.projectId)
                return@forEach
            }
            writeAsync(
                action = { constructionStore.transition(expected, target) },
                callback = { durable, failure ->
                    constructionWrites.remove(expected.projectId)
                    if (failure != null || durable == null) {
                        if (target.state == BuilderConstructionProjectState.PAUSED) {
                            constructionPauseRequests.complete(expected.projectId)
                        }
                        if (failure is BuilderConstructionProjectTransitionRejectedException &&
                            BuilderConstructionTransitionFailurePolicy.requiresRecovery(expected, target)
                        ) {
                            recoverRejectedConstructionMutation(expected, failure)
                            return@writeAsync
                        }
                        if (
                            failure is BuilderConstructionProjectUnknownOutcomeException ||
                            BuilderConstructionTransitionFailurePolicy.requiresRecovery(expected, target)
                        ) {
                            recoveryBlocked = true
                        } else {
                            constructionPlayerLeases.release(expected.projectId)
                        }
                        error("Builder construction transition failed for ${expected.projectId}", failure)
                        return@writeAsync
                    }
                    constructionProjects[durable.projectId] = durable
                    if (durable.state == BuilderConstructionProjectState.PAUSED) {
                        val requester = constructionPauseRequests.requester(durable.projectId)
                        constructionPauseRequests.complete(durable.projectId)
                        info(
                            debugLine.line(
                                "event" to "construction_paused",
                                "operation" to durable.projectId,
                                "player" to requester,
                                "cursor" to durable.cursor,
                            ),
                        )
                    } else if (durable.terminal) {
                        constructionPauseRequests.complete(durable.projectId)
                    }
                    constructionSiteDisplays.upsert(durable)
                    constructionMenus.refreshProject(durable.projectId)
                    if (
                        durable.state != BuilderConstructionProjectState.RECOVERY_REQUIRED ||
                        durable.pendingResourceMutation == null
                    ) {
                        constructionPlayerLeases.release(durable.projectId)
                    }
                    if (durable.state == BuilderConstructionProjectState.CANCELLED) {
                        books.releasePlanReservation(durable.plan)
                        constructionResources.forget(durable.projectId)
                        unlockConstruction(durable)
                    }
                    notifyConstructionTransition(expected, durable)
                    if (durable.state == BuilderConstructionProjectState.COMPLETED) {
                        finalizeConstructionCompletion(durable)
                    }
                    continueInstantConstruction(durable)
                },
            )
        }
    }

    private fun canRetryAppliedConstructionRecovery(record: BuilderConstructionProjectRecord): Boolean {
        if (!BuilderConstructionRecoveryPolicy.canResumeAppliedNoExchangeStep(record)) return false
        val position = record.steps[record.cursor].change.position
        val world = Bukkit.getWorld(position.worldId) ?: return false
        return world.isChunkLoaded(position.x shr 4, position.z shr 4)
    }

    private fun recoverRejectedConstructionMutation(
        expected: BuilderConstructionProjectRecord,
        failure: Throwable,
    ) {
        check(constructionWrites.add(expected.projectId)) {
            "Builder construction output recovery write is already active"
        }
        val recovery = expected.recoveryRequired(System.currentTimeMillis())
        writeAsync(
            action = { constructionStore.transition(expected, recovery) },
            callback = { durable, recoveryFailure ->
                constructionWrites.remove(expected.projectId)
                if (recoveryFailure != null || durable == null) {
                    recoveryBlocked = true
                    error(
                        "Builder construction value mutation requires operator recovery for ${expected.projectId}",
                        recoveryFailure ?: failure,
                    )
                    return@writeAsync
                }
                constructionProjects[durable.projectId] = durable
                constructionSiteDisplays.upsert(durable)
                constructionMenus.refreshProject(durable.projectId)
                notifyConstructionTransition(expected, durable)
                // RECOVERY_REQUIRED deliberately retains the player/container lease. The durable
                // receipt must remain isolated until an operator resolves the ambiguous value flow.
            },
        )
    }

    private fun notifyConstructionTransition(
        previous: BuilderConstructionProjectRecord,
        current: BuilderConstructionProjectRecord,
    ) {
        if (previous.state == current.state) return
        val player = Bukkit.getPlayer(current.playerId)?.takeIf(Player::isOnline) ?: return
        when (current.state) {
            BuilderConstructionProjectState.ACTIVE -> if (previous.state == BuilderConstructionProjectState.PREPARED) {
                send(
                    player,
                    "construction.started",
                    mapOf("count" to messages.literal(current.steps.size)),
                )
            }
            BuilderConstructionProjectState.WAITING_MATERIALS -> send(
                player,
                "construction.waiting-materials",
                mapOf(
                    "count" to messages.literal(current.cursor),
                    "total" to messages.literal(current.steps.size),
                    "material" to messages.literal(
                        checkNotNull(current.steps[current.cursor].requiredMaterial).materialKey.removePrefix("minecraft:"),
                    ),
                ),
            )
            BuilderConstructionProjectState.WAITING_OUTPUT_SPACE -> send(
                player,
                "construction.waiting-output",
                mapOf(
                    "count" to messages.literal(current.cursor),
                    "total" to messages.literal(current.steps.size),
                ),
            )
            BuilderConstructionProjectState.RECOVERY_REQUIRED -> send(player, "construction.recovery-required")
            else -> Unit
        }
    }

    private fun finalizeConstructionCompletion(record: BuilderConstructionProjectRecord) {
        if (record.completionFinalizedAtMillis != null) return
        if (!constructionCompletions.add(record.projectId)) return
        constructionSiteDisplays.remove(record.projectId)
        val completed = {
            val finalized = record.completionFinalized(System.currentTimeMillis())
            writeAsync(
                action = { constructionStore.transition(record, finalized) },
                callback = { durable, failure ->
                    constructionCompletions.remove(record.projectId)
                    if (failure != null || durable == null) {
                        recoveryBlocked = true
                        error("Builder construction completion finalization requires recovery for ${record.projectId}", failure)
                        Bukkit.getPlayer(record.playerId)?.takeIf(Player::isOnline)?.let { player ->
                            send(player, "construction.recovery-required")
                        }
                        return@writeAsync
                    }
                    constructionProjects[durable.projectId] = durable
                    constructionResources.forget(durable.projectId)
                    unlockConstruction(durable)
                    Bukkit.getPlayer(durable.playerId)?.takeIf(Player::isOnline)?.let { player ->
                        send(
                            player,
                            "construction.completed",
                            mapOf("count" to messages.literal(durable.steps.size)),
                        )
                    }
                    info(
                        debugLine.line(
                            "event" to "construction_completed",
                            "operation" to durable.projectId,
                            "player" to durable.playerId,
                            "blocks" to durable.steps.size,
                        ),
                    )
                },
            )
        }
        if (record.plan.bookInstanceId == null) {
            completed()
            return
        }
        books.commitPlanReservation(record.plan) { consumed, failure ->
            if (!consumed) {
                recoveryBlocked = true
                error("Builder construction book consumption requires recovery for ${record.projectId}", failure)
                Bukkit.getPlayer(record.playerId)?.takeIf(Player::isOnline)?.let { player ->
                    send(player, "construction.recovery-required")
                }
                return@commitPlanReservation
            }
            completed()
        }
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
                BuilderInventory.applyToolDamage(
                    player,
                    checkNotNull(record.plan.toolFingerprintBase64),
                    record.plan.toolDamage,
                )
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
            if (BuilderProgressCadence.shouldRender(operation.mutationBatches, completed, config.progressEveryBatches)) {
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
        sendPlayerMessage(
            player,
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

    private fun revalidatePlan(
        player: Player,
        plan: BuilderPlan,
        systemLootContainerPositions: Set<BuilderBlockPos> = emptySet(),
    ) {
        plan.validated(config.maxChanges)
        if (
            BuilderDeconstructionToolPolicy.requiresBypass(player.gameMode, plan) &&
            !BuilderPermissionPolicy.canDeconstructWithoutTool(player::hasPermission)
        ) {
            throw BuilderUserFailure("errors.no-permission")
        }
        plan.changes.forEach { change ->
            val block = block(requireWorld(change.position.worldId), change.position)
            val after = Bukkit.createBlockData(change.afterBlockData)
            ensureMutable(player, block, after.material.takeUnless(Material::isAir))
            if (block.blockData.asString != change.beforeBlockData) throw BuilderUserFailure("errors.expired")
            if (!block.type.isAir && !safety.isSafeExisting(block) && !safety.isReplaceable(block)) {
                throw BuilderUserFailure("errors.expired")
            }
            val safeSystemContainer =
                change.position in systemLootContainerPositions && safety.isSafeSystemLootContainer(after)
            if (!safety.isSafePlacement(after) && !safeSystemContainer && after.material !in safety.replaceable) {
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
            bookPreviewPresentation.clearPlayer(player.uniqueId)
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
        val construction = constructionProjects.values
            .filter { it.playerId == player.uniqueId && !it.terminal }
            .maxByOrNull(BuilderConstructionProjectRecord::updatedAtMillis)
        val plan = previews.plan(player.uniqueId)
        val selection = selectionOrNull(player)
        val selectionPoints = selections.points(player.uniqueId, player.world.uid)
        when {
            active != null -> send(player, "status.plan", mapOf("kind" to kindLabel(player, active.record.plan.kind), "count" to messages.literal(active.appliedChanges), "total" to messages.literal(active.record.plan.changes.size)))
            construction != null -> send(
                player,
                "construction.status",
                mapOf(
                    "state" to constructionStateLabel(player, construction.state),
                    "count" to messages.literal(construction.cursor),
                    "total" to messages.literal(construction.steps.size),
                ),
            )
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

    private fun canControlConstruction(
        player: Player,
        construction: BuilderConstructionProjectRecord,
    ): Boolean = player.uniqueId == construction.playerId || player.hasPermission(CONSTRUCTION_ADMIN_PERMISSION)

    private fun requestConstructionPaused(player: Player, projectId: UUID, pause: Boolean): Boolean {
        val expected = constructionProjects[projectId]?.takeUnless(BuilderConstructionProjectRecord::terminal)
            ?: return false
        if (!canControlConstruction(player, expected)) return false
        if (pause) return constructionPauseRequests.request(expected, player.uniqueId)
        if (expected.state != BuilderConstructionProjectState.PAUSED || !constructionWrites.add(projectId)) return false
        val target = runCatching { expected.resumed(System.currentTimeMillis()) }.getOrNull()
        if (target == null) {
            constructionWrites.remove(projectId)
            return false
        }
        writeAsync(
            action = { constructionStore.transition(expected, target) },
            callback = { durable, failure ->
                constructionWrites.remove(projectId)
                if (failure != null || durable == null) {
                    error("Builder construction pause transition failed for $projectId", failure)
                    constructionMenus.refreshProject(projectId)
                    return@writeAsync
                }
                constructionProjects[projectId] = durable
                constructionSiteDisplays.upsert(durable)
                constructionMenus.refreshProject(projectId)
                info(
                    debugLine.line(
                        "event" to "construction_resumed",
                        "operation" to projectId,
                        "player" to player.uniqueId,
                        "cursor" to durable.cursor,
                    ),
                )
                continueInstantConstruction(durable)
            },
        )
        return true
    }

    private fun requestInstantConstruction(player: Player, projectId: UUID): Boolean {
        if (!player.hasPermission(CONSTRUCTION_ADMIN_PERMISSION)) return false
        val project = constructionProjects[projectId]?.takeUnless(BuilderConstructionProjectRecord::terminal)
            ?: return false
        if (project.state == BuilderConstructionProjectState.RECOVERY_REQUIRED) return false
        constructionInstantRequests += projectId
        val accepted = if (project.state == BuilderConstructionProjectState.PAUSED) {
            requestConstructionPaused(player, projectId, pause = false)
        } else {
            taskScope.runSync(::tickConstructionProjects) != null
        }
        if (!accepted) constructionInstantRequests -= projectId
        if (accepted) send(player, "construction.instant-started")
        return accepted
    }

    private fun continueInstantConstruction(project: BuilderConstructionProjectRecord) {
        if (project.projectId !in constructionInstantRequests) return
        if (project.terminal || project.state == BuilderConstructionProjectState.RECOVERY_REQUIRED) {
            constructionInstantRequests -= project.projectId
            return
        }
        taskScope.runSync(::tickConstructionProjects)
    }

    private fun teleportToConstruction(player: Player, requested: BuilderConstructionProjectRecord) {
        val project = constructionProjects[requested.projectId]
            ?.takeIf { it.playerId == player.uniqueId && !it.terminal }
            ?: run {
                send(player, "construction.projects.teleport.missing")
                return
            }
        val world = Bukkit.getWorld(BuilderConstructionTeleport.worldId(project)) ?: run {
            send(player, "construction.projects.teleport.world-unavailable")
            return
        }
        val chunkLoads = BuilderConstructionTeleport.candidateColumns(project)
            .map { column -> (column.x shr 4) to (column.z shr 4) }
            .distinct()
            .map { (x, z) -> world.getChunkAtAsync(x, z, true) }
        CompletableFuture.allOf(*chunkLoads.toTypedArray()).whenCompleteSync(taskScope) { _, loadFailure ->
            if (!player.isOnline) return@whenCompleteSync
            val current = constructionProjects[project.projectId]
                ?.takeIf { it.playerId == player.uniqueId && !it.terminal }
            if (loadFailure != null || current == null) {
                send(
                    player,
                    if (current == null) "construction.projects.teleport.missing" else "construction.projects.teleport.failed",
                )
                return@whenCompleteSync
            }
            val destination = BuilderConstructionTeleport.findSafeDestination(world, current)
            if (destination == null) {
                send(player, "construction.projects.teleport.unsafe")
                return@whenCompleteSync
            }
            player.teleportAsync(destination).whenCompleteSync(taskScope) { success, teleportFailure ->
                if (!player.isOnline) return@whenCompleteSync
                if (teleportFailure != null || success != true) {
                    send(player, "construction.projects.teleport.failed")
                } else {
                    send(
                        player,
                        "construction.projects.teleport.success",
                        mapOf(
                            "x" to messages.literal(destination.blockX),
                            "y" to messages.literal(destination.blockY),
                            "z" to messages.literal(destination.blockZ),
                        ),
                    )
                }
            }
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

    private fun requiredLootTable(rawKey: String): org.bukkit.loot.LootTable {
        val key = checkNotNull(NamespacedKey.fromString(rawKey)) {
            "Builder construction loot-table key is invalid"
        }
        return checkNotNull(lootTableResolver(key)) {
            "Builder construction loot table is unavailable: $rawKey"
        }
    }

    private fun requireWorld(id: UUID): World = Bukkit.getWorld(id) ?: throw BuilderUserFailure("errors.world-not-allowed")

    private fun requireChanges(changes: List<BuilderBlockChange>) {
        if (changes.isEmpty()) throw BuilderUserFailure("errors.nothing-to-change")
        if (changes.size > config.maxChanges) throw BuilderUserFailure("errors.selection-too-large")
    }

    private fun hourlyUsage(playerId: UUID, now: Long): Int = committedRecords.values
        .asSequence()
        .filter { it.playerId == playerId && it.plan.kind != BuilderPlanKind.UNDO }
        .filter { (it.committedAtMillis ?: 0L) >= now - 3_600_000L }
        .sumOf { it.plan.changes.size } + constructionProjects.values
        .asSequence()
        .filter { it.playerId == playerId && it.state == BuilderConstructionProjectState.COMPLETED }
        .filter { (it.completedAtMillis ?: 0L) >= now - 3_600_000L }
        .sumOf { it.steps.size }

    private fun lockConstruction(record: BuilderConstructionProjectRecord): Boolean {
        if (record.projectId in constructionLocks) return true
        if (!operationLocks.tryLock(record.plan)) return false
        constructionLocks += record.projectId
        return true
    }

    private fun unlockConstruction(record: BuilderConstructionProjectRecord) {
        if (constructionLocks.remove(record.projectId)) operationLocks.unlock(record.plan)
    }

    private fun hourlyLimit(player: Player): Int =
        BuilderPermissionPolicy.hourlyChanges(player::hasPermission, config.baseHourlyChanges)

    private fun bookApplicationCooldownRemaining(player: Player): java.time.Duration =
        BuilderBookApplicationCooldown.remaining(
            playerId = player.uniqueId,
            records = constructionProjects.values,
            nowMillis = System.currentTimeMillis(),
            cooldown = config.bookApplicationCooldown,
            bypass = player.hasPermission(BOOK_COOLDOWN_BYPASS_PERMISSION),
        )

    private fun ensureBookApplicationCooldown(player: Player) {
        val remaining = bookApplicationCooldownRemaining(player)
        if (remaining.isZero) return
        val totalMinutes = (remaining.seconds + 59L) / 60L
        throw BuilderUserFailure(
            "book.cooldown",
            mapOf(
                "hours" to messages.literal(totalMinutes / 60L),
                "minutes" to messages.literal(totalMinutes % 60L),
            ),
        )
    }

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

    private fun constructionStateLabel(player: Player, state: BuilderConstructionProjectState): Component =
        messages.render("construction.states.${state.name.lowercase(Locale.ROOT)}", locale(player))

    private fun discardPendingPlan(playerId: UUID) {
        previews.plan(playerId)?.let { plan -> plannedConstructionProjects.remove(plan.id) }
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
        sendPlayerMessage(player, messages.render(path, locale(player), values))
    }

    private fun refreshBookHoldHint(player: Player) {
        val data = BuildBookCodec.read(player.inventory.itemInMainHand)?.takeIf(BuildBookData::draft)
        val identity = data?.let { "${it.blueprintId}:${it.contentSha256}" }
        if (!bookHoldHints.shouldShow(player.uniqueId, identity, BuildingManager.pending(player.uniqueId) != null)) return
        player.showTitle(
            Title.title(
                messages.render("book.hold-hint.title", locale(player)),
                messages.render("book.hold-hint.subtitle", locale(player)),
                5,
                40,
                10,
            ),
        )
    }

    private fun locale(player: Player): String = player.locale().toLanguageTag()

    private fun java.time.Duration.toTicks(): Long = (toMillis() / 50L).coerceAtLeast(1L)

    private fun filterPrefix(values: List<String>, raw: String?): List<String> {
        val prefix = raw.orEmpty().lowercase(Locale.ROOT)
        return values.filter { it.startsWith(prefix) }.take(100)
    }

    private fun safeMaterialNames(): List<String> = Material.entries.asSequence()
        .filter(safety::isSafeMaterial)
        .map { it.name.lowercase(Locale.ROOT) }
        .toList()

    private data class ReplaceRequest(
        val source: Material,
        val target: Material,
        val confirmed: Boolean,
    )

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
            val (effectiveItem, effectiveData) = canonicalBook(player, item, data)
            if (player.isSneaking) {
                discardPreparedBookPlan(player.uniqueId)
                BuildBookEditorGui.open(player, ::prepareBookCopy)
                return
            }
            val current = BuildingManager.pending(player.uniqueId)
            val preparedPlan = preparedBookPlan(player.uniqueId, effectiveItem)
            val decision = BuilderBookInteractionPolicy.decide(
                action = if (action == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
                    BuilderBookClick.BLOCK
                } else {
                    BuilderBookClick.AIR
                },
                exactBookPreviewOpen = current?.isExactOpenPreview(player, effectiveData) == true,
                preparedPlan = preparedPlan,
            )
            when (decision) {
                BuilderBookInteractionDecision.OPEN_PREVIEW,
                BuilderBookInteractionDecision.REPLACE_PLAN_WITH_PREVIEW,
                -> {
                    if (decision == BuilderBookInteractionDecision.REPLACE_PLAN_WITH_PREVIEW) {
                        discardPendingPlan(player.uniqueId)
                    }
                    val opened = BuildingManager.openPreview(
                        player = player,
                        location = checkNotNull(clickedLocation),
                        data = effectiveData,
                        expiresAtMillis = System.currentTimeMillis() + config.bookPreviewTtl.toMillis(),
                        maxPlacementOffset = config.bookPreviewMaxOffset,
                    )
                    ?: throw BuilderUserFailure("book.invalid")
                    if (current?.isExactOpenPreview(player, effectiveData) != true) {
                        send(
                            player,
                            "book.preview-opened",
                            mapOf(
                                "name" to messages.literal(effectiveData.title),
                                "state" to messages.render(
                                    if (effectiveData.draft) "book.state.draft" else "book.state.active",
                                    locale(player),
                                ),
                            ),
                        )
                    }
                }
                BuilderBookInteractionDecision.PREPARE_PLAN -> {
                    val site = checkNotNull(current)
                    if (effectiveData.draft) {
                        books.handleCommand(player, listOf("activate"))
                    } else {
                        startPlayerBuildBook(player, site, effectiveItem)
                    }
                }
                BuilderBookInteractionDecision.RESHOW_PREPARED_PLAN -> {
                    val plan = checkNotNull(previews.plan(player.uniqueId))
                    showPlanSummary(player, plan, includeShop = false)
                }
                BuilderBookInteractionDecision.DISCARD_PLAN_AND_REQUIRE_PREVIEW -> {
                    discardPendingPlan(player.uniqueId)
                    throw BuilderUserFailure("book.preview-required")
                }
                BuilderBookInteractionDecision.REQUIRE_PREVIEW -> throw BuilderUserFailure("book.preview-required")
            }
        } catch (failure: BuilderUserFailure) {
            send(player, failure.path, failure.values)
        } catch (failure: Throwable) {
            error("Builder-book interaction failed for ${player.name}", failure)
            send(player, "book.failed")
        }
    }

    private fun canonicalBook(player: Player, item: ItemStack, data: BuildBookData): Pair<ItemStack, BuildBookData> {
        return canonicalBook(player, player.inventory.heldItemSlot, item, data)
    }

    private fun canonicalBook(
        player: Player,
        slot: Int,
        item: ItemStack,
        data: BuildBookData,
    ): Pair<ItemStack, BuildBookData> {
        val canonical = if (data.playerCreated) {
            if (data.deliveryPending) throw BuilderUserFailure("book.delivery-pending")
            books.verifySchematic(data)
            data
        } else {
            val definition = systemBuildBookResolver(data) ?: throw BuilderUserFailure("book.invalid")
            data.copy(
                title = definition.title,
                systemMaterialsIncluded = definition.materialsIncluded,
            ).validated()
        }
        if (canonical == data) return item to data
        val updated = BuildBookCodec.update(item, canonical)
        player.inventory.setItem(slot, updated)
        return updated to canonical
    }

    private fun preparedBookPlan(playerId: UUID, book: ItemStack): BuilderBookPreparedPlan {
        val plan = previews.plan(playerId) ?: return BuilderBookPreparedPlan.NONE
        if (plan.kind != BuilderPlanKind.BUILD_BOOK) return BuilderBookPreparedPlan.OTHER_BOOK
        val construction = plannedConstructionProjects[plan.id] ?: return BuilderBookPreparedPlan.OTHER_BOOK
        val (expected, amount) = BuilderItemCodec.decode(construction.bookCost)
        val presented = book.clone().also { it.amount = 1 }
        return if (amount == 1 && expected.isSimilar(presented)) {
            BuilderBookPreparedPlan.SAME_BOOK
        } else {
            BuilderBookPreparedPlan.OTHER_BOOK
        }
    }

    private fun discardPreparedBookPlan(playerId: UUID) {
        if (previews.plan(playerId)?.kind == BuilderPlanKind.BUILD_BOOK) discardPendingPlan(playerId)
    }

    private fun prepareBookCopy(player: Player) {
        try {
            books.handleCommand(player, listOf("copy"))
        } catch (failure: BuilderUserFailure) {
            send(player, failure.path, failure.values)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onJoin(event: PlayerJoinEvent) {
        if (!playerRecoveries.onPlayerAvailable(event.player)) books.onPlayerAvailable(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        discardPendingPlan(event.player.uniqueId)
        bookHoldHints.clear(event.player.uniqueId)
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

    internal fun reloadBlocker(): BuilderToolsReloadBlocker? {
        val bookHealth = books.health()
        return when {
            closed || recovering || recoveryBlocked || playerRecoveries.pendingCount > 0 ->
                BuilderToolsReloadBlocker.STARTING_OR_RECOVERING
            operationLocks.activeOperationCount > 0 -> BuilderToolsReloadBlocker.ACTIVE_OPERATION
            operationLocks.bookLockedPlayerCount > 0 || operationLocks.recoveryLockedPlayerCount > 0 ||
                bookHealth.deliveryWaitingForSpace > 0 || bookHealth.reservationReleaseBacklog > 0 ||
                bookHealth.recoveryBlocked -> BuilderToolsReloadBlocker.DURABLE_BOOK_FLOW
            previews.pendingCount > 0 || plannedConstructionProjects.isNotEmpty() ->
                BuilderToolsReloadBlocker.PENDING_PREVIEW
            selections.pendingCount > 0 || clipboardController.pendingCount > 0 || BuildingManager.pendingCount > 0 ->
                BuilderToolsReloadBlocker.VOLATILE_PLAYER_STATE
            builderConstructionReloadBlocked(constructionWrites.size, constructionCompletions.size) ->
                BuilderToolsReloadBlocker.ACTIVE_CONSTRUCTION
            else -> null
        }
    }

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
        constructionSiteDisplays.close()
        constructionProjectsMenu.close()
        constructionMenus.close()
        constructionPauseRequests.clear()
        constructionInstantRequests.clear()
        bookHoldHints.clear()
        bookPreviewPresentation.close()
        taskScope.close()
        closeStorageExecutor()
        shop.close()
        constructionPlayerLeases.close()
        operationLocks.close()
        selections.clear()
        clipboardController.close()
        BuildingManager.clearPreviews()
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
        const val STORAGE_SHUTDOWN_TIMEOUT_SECONDS = 5L
        const val CONSTRUCTION_ADMIN_PERMISSION = "arcbuild.admin.construction"
        const val BOOK_COOLDOWN_BYPASS_PERMISSION = "arcbuild.book.cooldown.bypass"
    }
}

private fun loadSystemBuildBookResolver(
    plugin: JavaPlugin,
    config: BuilderToolsConfig,
): (BuildBookData) -> SystemBuildBookDefinition? {
    val path = plugin.dataPath.resolve("modules/system-build-books.yml")
    require(Files.isRegularFile(path)) { "System build-book catalog is missing" }
    val catalog = SystemBuildBookCatalog.load(path, BuilderStoragePaths.schematicsRoot(config.schematicsRoot))
    return catalog::resolve
}
