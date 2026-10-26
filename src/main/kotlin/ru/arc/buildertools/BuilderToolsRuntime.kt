package ru.arc.buildertools

import com.destroystokyo.paper.event.brigadier.AsyncPlayerSendSuggestionsEvent
import com.mojang.brigadier.suggestion.Suggestions
import io.papermc.paper.command.brigadier.MessageComponentSerializer
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
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.ARC
import ru.arc.buildertools.display.BuilderDisplayEditor
import ru.arc.autobuild.BuildBookCodec
import ru.arc.autobuild.BuildBookData
import ru.arc.autobuild.BuildBookSettings
import ru.arc.autobuild.gui.BuildBookEditorGui
import ru.arc.autobuild.BuildingManager
import ru.arc.autobuild.Building
import ru.arc.autobuild.BuilderSchematics
import ru.arc.autobuild.ConstructionSite
import ru.arc.autobuild.SystemBuildBookDefinition
import ru.arc.autobuild.isBuilderIgnored
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.hooks.HookRegistry
import ru.arc.observability.RuntimeHealthContribution
import ru.arc.observability.RuntimeHealthState
import ru.arc.observability.StructuredDebugLine
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.playerstate.PaperPlayerStateCodec
import ru.arc.paper.playerstate.PaperPlayerStateService
import ru.arc.paper.protection.PaperBlockProtection
import ru.arc.text.LocalizedMiniMessage
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import java.time.Duration
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicReference

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

internal class BuilderToolsRuntimeActivationException(
    cause: Throwable,
    val cleanupFailure: Throwable,
) : RuntimeException(cause)

private const val CONSTRUCTION_WORLD_READ_PHASE = "construction_world_read"

/** Keeps the original port behavior while adding the project identity missing from world reads. */
internal fun builderConstructionWorldReadPort(
    projectId: UUID,
    delegate: BuilderConstructionProjectPort,
    diagnostics: BuilderActionDiagnostics,
    context: (BuilderBlockPos) -> BuilderActionContext,
): BuilderConstructionProjectPort = object : BuilderConstructionProjectPort by delegate {
    override fun currentBlockData(position: BuilderBlockPos): String = read(position, "block_data") {
        delegate.currentBlockData(position)
    }

    override fun isStepApplied(step: BuilderConstructionStep): Boolean = read(step.change.position, "step_applied") {
        delegate.isStepApplied(step)
    }

    private fun <T> read(position: BuilderBlockPos, check: String, action: () -> T): T = try {
        action().also {
            diagnostics.constructionAllowed(projectId, "$CONSTRUCTION_WORLD_READ_PHASE.$check", position)
        }
    } catch (failure: Throwable) {
        val expected = failure is BuilderConstructionTemporarilyUnavailableException
        diagnostics.constructionBlocked(
            context(position),
            if (expected) failure.diagnosticReason ?: "block_temporarily_unavailable" else "unexpected_${BuilderToolsFailureType.of(failure)}",
            failure.takeUnless { expected },
            phase = "$CONSTRUCTION_WORLD_READ_PHASE.$check",
        )
        throw failure
    }
}

internal class BuilderToolsRuntime(
    private val plugin: JavaPlugin,
    private val config: BuilderToolsConfig,
    private val taskScope: LifecycleTaskScope = LifecycleTaskScope(),
    displayRenderer: BuilderDisplayRenderer? = null,
    blockDataRotation: BuilderBlockDataRotation = PaperBuilderBlockDataRotation,
    private val physicsUpdater: BuilderPhysicsUpdater = PaperBuilderPhysicsUpdater,
    draftStorage: BuilderDraftStorage? = null,
    bookSchematicVerifier: BuilderBookSchematicVerifier = PlayerBuildBookSchematicVerifier,
    private val bookReplacementRefund: (Block) -> ItemStack? = BuilderDeconstructionRefunds::fromSilkTouch,
    systemBuildBookResolver: ((BuildBookData) -> SystemBuildBookDefinition?)? = null,
    systemBuildBookIds: List<String>? = null,
    systemBuildBookStarterIds: List<String>? = null,
    schematicLoader: ((String, String?) -> CompletableFuture<Building>)? = null,
    preparedSchematicsRoot: java.nio.file.Path? = null,
    private val lootTableResolver: (NamespacedKey) -> LootTable? = Bukkit::getLootTable,
    private val lootTableAccess: BuilderLootTableAccess = PaperBuilderLootTableAccess,
    private val sendPlayerMessage: (Player, Component) -> Unit = { player, message -> player.sendMessage(message) },
    private val selectionTargetResolver: (Player, Double) -> BuilderBlockPos? = BuilderSelectionTarget::resolve,
) : Listener, CommandExecutor, TabCompleter, AutoCloseable {
    private val systemBuildBookCatalog = if (systemBuildBookResolver == null) requireNotNull(BuilderToolsConfiguration.current(plugin.dataPath).systemBooks) { "System build-book catalog is not prepared" } else null
    private val systemBuildBookResolver = systemBuildBookResolver ?: requireNotNull(systemBuildBookCatalog)::resolve
    private val systemBuildBookIds = systemBuildBookIds ?: systemBuildBookCatalog?.enabledBuildingIds.orEmpty()
    private val systemBuildBookStarterIds = systemBuildBookStarterIds
        ?: systemBuildBookCatalog?.starterBuildingIds
        ?: this.systemBuildBookIds
    private val schematics = BuilderSchematics(preparedSchematicsRoot
        ?: requireNotNull(BuilderToolsConfiguration.current(plugin.dataPath).schematicsRoot) { "Schematics root is not prepared" })
    private val loadBuilding = schematicLoader ?: schematics::load
    private val bookRequests = BuilderBookRequests(taskScope)
    private val planningTasks = BuilderPlanningWork(taskScope)
    private val displayRenderer = displayRenderer ?: BuilderBlockDisplayRenderer(
        plugin,
        config.previewMaxPlanDisplays,
        config.previewBlockDisplayScale,
        config.previewAntiZFighting,
        config.previewPlanDisplayRange,
        config.previewGuidancePeriodTicks,
        config.messages(),
        taskScope,
        movementPeriodTicks = config.previewMovementPeriodTicks,
        planningTasks = planningTasks,
        blockDataRotation = blockDataRotation,
        worldBookControlsEnabled = config.bookPreviewWorldControlsEnabled,
    )
    internal val closeCompletion = CompletableFuture<Unit>()
    private val capturedMaximumRange = config.maximumRange
    private val capturedRequireLands = config.requireLands
    private val messages: LocalizedMiniMessage = config.messages()
    private val shop = BuilderShopCoordinator(config, messages)
    private val safety = BuilderBlockSafety(plugin, config.replaceableMaterials)
    private val planningHost = object : BuilderMoveHost, BuilderPlanningTasks by planningTasks {
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

        override fun createPlanAsync(
            player: Player, kind: BuilderPlanKind, changes: List<BuilderBlockChange>,
            costs: List<BuilderItemAmount>, rewards: List<BuilderItemAmount>, skippedUnsafeBlocks: Int,
        ): CompletableFuture<BuilderPlan> = newPlanAsync(
            player, kind, changes, costs, rewards, skippedUnsafeBlocks = skippedUnsafeBlocks,
        )

        override fun createMovePlanAsync(
            player: Player, changes: List<BuilderBlockChange>, deconstruction: BuilderDeconstructionCapture?,
            displaced: List<BuilderBlockPos>, skippedUnsafeBlocks: Int,
        ): CompletableFuture<BuilderPlan> = newPlanAsync(
            player, BuilderPlanKind.MOVE, changes, emptyList(), deconstruction?.rewards.orEmpty(),
            deconstruction?.toolFingerprint, deconstruction?.toolDamage ?: 0,
            skippedUnsafeBlocks = skippedUnsafeBlocks,
            moveDeconstruction = displaced.takeIf { it.isNotEmpty() }?.let {
                BuilderMoveDeconstruction(it, deconstruction?.toolBypassUsed == true)
            },
        )

        override fun fail(path: String): Nothing = throw BuilderUserFailure(path)
    }
    private val coreProtect = BuilderCoreProtectBridge.resolve()
    private val journal = BuilderJournalStore(plugin.dataPath, config.maxChanges)
    private val constructionStore = BuilderConstructionProjectStore(plugin.dataPath, config.maxConstructionChanges)
    private val stateService = PaperPlayerStateService()
    private val stateCodec = PaperPlayerStateCodec()
    private val storageExecutor = ru.arc.core.BoundedAsyncExecutor("arc-builder-tools-storage", 1, 256)
    private var storageSealed = false
    private val debugLine = StructuredDebugLine("ARC_BUILDER_TOOLS")
    private val actionDiagnostics = BuilderActionDiagnostics()
    private val wandKey = org.bukkit.NamespacedKey(plugin, "builder_selector")
    private val selections = BuilderSelectionController(
        previewRadius = config.previewRadius,
        previewSpacing = config.previewSpacing,
        maximumOutlinePoints = config.previewMaxSelectionParticles,
    )
    private val worldEditSelection = if (Bukkit.getPluginManager().isPluginEnabled("WorldEdit") ||
        Bukkit.getPluginManager().isPluginEnabled("FastAsyncWorldEdit")
    ) BuilderWorldEditSelection() else null
    private val worldEditSelectionPoints = mutableMapOf<UUID, BuilderSelectionPoints>()
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
        host = object : BuilderClipboardHost, BuilderPlanningTasks by planningTasks {
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

            override fun createPastePlanAsync(
                player: Player, changes: List<BuilderBlockChange>, costs: List<BuilderItemAmount>,
                rewards: List<BuilderItemAmount>, skippedUnsafeBlocks: Int,
            ): CompletableFuture<BuilderPlan> = newPlanAsync(
                player, BuilderPlanKind.PASTE, changes, costs, rewards, skippedUnsafeBlocks = skippedUnsafeBlocks,
            )

            override fun fail(path: String): Nothing = throw BuilderUserFailure(path)
        },
    )
    private val pastePreview = BuilderPastePreview(
        clipboard = clipboardController,
        renderer = this.displayRenderer,
        tasks = planningTasks,
        target = { selectionTargetResolver(it, config.maximumRange) },
        allowed = { player ->
            !operationLocks.isPlayerLocked(player.uniqueId) && entityEditor?.isEditing(player) != true &&
                runCatching {
                    ensureAvailable(player)
                    ensureFeaturePermission(player, BuilderFeature.PASTE)
                }.isSuccess
        },
        onLock = ::prepareLockedPaste,
        onClear = ::clearLockedPastePlan,
        onFailure = ::reportPlanningFailure,
        hint = { player, key ->
            if (key == "clipboard.moving") send(player, key)
            else player.sendActionBar(messages.render(key, locale(player)))
        },
    )
    private val deconstructionController = BuilderDeconstructionController(
        safety = safety,
        maximumChanges = config.maxChanges,
        host = object : BuilderDeconstructionHost, BuilderPlanningTasks by planningTasks {
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

            override fun createPlanAsync(
                player: Player, changes: List<BuilderBlockChange>, rewards: List<BuilderItemAmount>,
                toolFingerprint: String?, toolDamage: Int, skippedUnsafeBlocks: Int,
            ): CompletableFuture<BuilderPlan> = newPlanAsync(
                player, BuilderPlanKind.DECONSTRUCT, changes, emptyList(), rewards,
                toolFingerprint, toolDamage, skippedUnsafeBlocks = skippedUnsafeBlocks,
            )

            override fun fail(path: String): Nothing = throw BuilderUserFailure(path)
        },
    )
    private val moveController = BuilderMoveController(safety, config.maxChanges, planningHost, deconstructionController)
    private val previews: BuilderPreviewSessions
    private val crown: BuilderCrownController
    private val books: BuilderBookLifecycle
    private val operationLocks: BuilderOperationLocks
    private val constructionPlayerLeases: BuilderConstructionPlayerLeases
    private val playerRecoveries: BuilderPlayerRecoveryCoordinator
    private val committedRecords = mutableMapOf<UUID, BuilderJournalRecord>()
    private val consumedUndoSources = mutableSetOf<UUID>()
    private val plannedConstructionProjects = mutableMapOf<UUID, BuilderConstructionProjectRecord>()
    private data class WorldBookCooldown(val planId: UUID, val projectCount: Int, val bypass: Boolean, val untilMillis: Long)
    private val worldBookCooldowns = mutableMapOf<UUID, WorldBookCooldown>()
    private val constructionProjects = mutableMapOf<UUID, BuilderConstructionProjectRecord>()
    private val constructionPauseRequests = BuilderConstructionPauseRequests()
    private val constructionInstantRequests = mutableMapOf<UUID, UUID>()
    private val constructionMenus: BuilderConstructionMenuManager
    private val constructionProjectsMenu: BuilderConstructionProjectsMenuManager
    private val constructionSiteDisplays: BuilderConstructionSiteDisplayManager
    private val bookPreviewPresentation: BuilderBookPreviewPresentation
    private val bookHoldHints = BuilderBookHoldHintTracker()
    private var constructionRecovering = true
    private val constructionWrites = mutableSetOf<UUID>()
    private val rollbackPending = mutableSetOf<UUID>()
    private val constructionStarts = mutableSetOf<UUID>()
    private val constructionCompletions = mutableSetOf<UUID>()
    private val constructionLocks = mutableMapOf<UUID, BuilderBlockLockLease>()
    private val startupRecoveryLocks = mutableMapOf<UUID, BuilderBlockLockLease>()
    private val constructionResources = BuilderConstructionResources(
        containerRadius = config.constructionContainerRadius,
        onlineRange = config.constructionOnlineInventoryRange,
        worldProvider = Bukkit::getWorld,
        onlinePlayerProvider = Bukkit::getPlayer,
        canOpenContainer = { playerId, block ->
            !PaperBlockProtection.isProtected(block) && (HookRegistry.landsHook?.canOpenContainer(playerId, block) ?: true)
        },
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
                throw BuilderConstructionTemporarilyUnavailableException("chunk_unloaded")
            }
            if (PaperBlockProtection.isProtected(position.worldId, position.x, position.y, position.z)) {
                throw BuilderConstructionTemporarilyUnavailableException("block_protection")
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
            fun context() = constructionActionContext(project, "construction_step", step.change.position)
            fun denied(reason: String): Boolean {
                actionDiagnostics.constructionBlocked(context(), reason)
                return false
            }
            try {
                val playerId = project.playerId
                val change = step.change
                val world = Bukkit.getWorld(change.position.worldId) ?: return denied("world_unavailable")
                if (!config.allowsWorld(world.name) || !world.isChunkLoaded(change.position.x shr 4, change.position.z shr 4)) {
                    actionDiagnostics.constructionBlocked(context(), if (!config.allowsWorld(world.name)) "world_not_allowed" else "chunk_unloaded")
                    throw BuilderConstructionTemporarilyUnavailableException()
                }
                val target = block(world, change.position)
                if (PaperBlockProtection.isProtected(target)) return denied("block_protection")
                if (!world.worldBorder.isInside(target.location)) return denied("world_border")
                val after = Bukkit.createBlockData(change.afterBlockData)
                val safeSystemContainer = step.lootTableKey != null && safety.isSafeSystemLootContainer(after)
                if (!after.material.isAir && !safety.isSafePlacement(after) && !safeSystemContainer &&
                    !(step.systemFurniture && safety.isSafeSystemFurniture(after))) return denied("unsafe_placement")
                val replaceable = safety.isReplaceable(target)
                if (!replaceable && !safety.isSafeExisting(target)) return denied("unsafe_existing_block")
                val allowed = HookRegistry.landsHook?.canModify(
                    playerId,
                    target,
                    after.material.takeUnless(Material::isAir),
                ) ?: true
                if (!allowed) return denied("lands_build_permission")
                actionDiagnostics.constructionAllowed(project.projectId)
                return true
            } catch (failure: BuilderConstructionTemporarilyUnavailableException) {
                throw failure
            } catch (failure: Throwable) {
                actionDiagnostics.constructionBlocked(context(), "unexpected_${BuilderToolsFailureType.of(failure)}", failure)
                throw failure
            }
        }

        override fun prepareInput(
            playerId: UUID,
            project: BuilderConstructionProjectRecord,
            input: BuilderItemAmount,
        ): BuilderResourceMutation? = constructionResourceAction(project, "construction_input") {
            constructionResources.prepareInput(playerId, project, input)
        }

        override fun prepareOutput(
            playerId: UUID,
            project: BuilderConstructionProjectRecord,
            output: BuilderItemAmount,
        ): BuilderResourceMutation? = constructionResourceAction(project, "construction_output") {
            constructionResources.prepareOutput(playerId, project, output)
        }

        override fun reconcileResource(
            project: BuilderConstructionProjectRecord,
            mutation: BuilderResourceMutation,
        ): BuilderResourceMutationResult = constructionResourceAction(project, "construction_resource_reconcile") {
            constructionResources.reconcile(project, mutation)
        }

        override fun rollbackResource(
            project: BuilderConstructionProjectRecord,
            mutation: BuilderResourceMutation,
        ): BuilderResourceMutationResult = constructionResourceAction(project, "construction_resource_rollback") {
            constructionResources.rollback(project, mutation)
        }

        private fun atomicSteps(
            project: BuilderConstructionProjectRecord,
            step: BuilderConstructionStep,
        ): List<BuilderConstructionStep> {
            val after = Bukkit.createBlockData(step.change.afterBlockData)
            if (!BuilderBookMultiBlockPolicy.isPrimary(after)) return listOf(step)
            val companionPosition = BuilderBookMultiBlockPolicy.companionPosition(step.change.position, after)
                ?: return listOf(step)
            val companion = project.stepAt(companionPosition)
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

        override fun apply(project: BuilderConstructionProjectRecord, step: BuilderConstructionStep): Int =
            constructionResourceAction(project, "construction_apply", step.change.position) {
            val mutations = atomicSteps(project, step).map { atomicStep ->
                val change = atomicStep.change
                val world = Bukkit.getWorld(change.position.worldId)
                    ?: throw IllegalStateException("Builder construction world is unavailable")
                if (!world.isChunkLoaded(change.position.x shr 4, change.position.z shr 4)) {
                    throw BuilderConstructionTemporarilyUnavailableException()
                }
                val target = block(world, change.position)
                if (PaperBlockProtection.isProtected(target)) throw BuilderConstructionTemporarilyUnavailableException()
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
                    if (PaperBlockProtection.isProtected(mutation.target)) throw BuilderConstructionTemporarilyUnavailableException()
                    mutation.target.setBlockData(mutation.after, false)
                    changed += mutation
                }
                mutations.forEach { mutation ->
                    mutation.step.lootTableKey?.let { lootTableKey ->
                        if (PaperBlockProtection.isProtected(mutation.target)) throw BuilderConstructionTemporarilyUnavailableException()
                        lootTableAccess.apply(mutation.target, requiredLootTable(lootTableKey))
                    }
                }
            } catch (failure: Throwable) {
                changed.asReversed().forEach { mutation ->
                    runCatching {
                        check(!PaperBlockProtection.isProtected(mutation.target)) { "Builder construction rollback target is protected" }
                        mutation.target.setBlockData(mutation.before, false)
                    }
                        .exceptionOrNull()
                        ?.let(failure::addSuppressed)
                }
                throw failure
            }

            changed.forEach { mutation ->
                coreProtect?.logChange(project.playerName, mutation.target.location, mutation.before, mutation.after)
            }
            val current = mutations.first()
            if (project.instantBuildRequestedBy == null) BuilderConstructionFeedback.play(
                current.target.world,
                current.target.location,
                current.before,
                current.after,
                project.cursor,
                constructionFeedbackSettings,
            )
            changed.size
        }

        override fun appliedBlockCount(project: BuilderConstructionProjectRecord, step: BuilderConstructionStep): Int =
            atomicSteps(project, step).count { atomicStep ->
                runCatching {
                    currentBlockData(atomicStep.change.position) == atomicStep.change.afterBlockData
                }.getOrDefault(false)
            }.coerceAtLeast(1)
    }
    private var recovering = true
    private var journalRecoveryLocksReady = false
    private var draftRecoveryLocksReady = false
    private var startupBarrierReleased = false
    private var recoveryBlocked = false
    private var closed = false
    private var selectionActionPanel: BuilderSelectionActionPanel? = null
    private var materialPicker: BuilderMaterialPicker? = null
    private var crownSelectionDialog: BuilderCrownSelectionDialog? = null
    private var entityEditor: BuilderDisplayEditor? = null
    private var placementAssist: BuilderPlacementAssist? = null
    private val selectionPanelPages = mutableMapOf<UUID, Int>()
    private val materialScans = mutableMapOf<UUID, Any>()
    private val panelPlanSummaries = mutableMapOf<UUID, Pair<UUID, Component>>()
    private val selectionUndoAvailable = mutableMapOf<UUID, Pair<Long, Boolean>>()
    private val selectionTargets = mutableMapOf<UUID, BuilderBlockPos>()
    private val selectionTargetFailures = mutableSetOf<UUID>()
    private var constructionSchedulerTick = 0L
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
        operationLocks = BuilderOperationLocks(plugin).also(BuilderOperationLocks::beginRecoveryBarrier)
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
                requestCancelled = ::requestConstructionCancelled,
                canCancel = constructionPauseRequests::canRequestCancel,
                canBuildInstantly = { it.hasPermission(CONSTRUCTION_ADMIN_PERMISSION) },
                requestInstant = ::requestInstantConstruction,
                planningTasks = planningTasks,
            ).also { initializedConstructionMenus = it }
            constructionProjectsMenu = BuilderConstructionProjectsMenuManager(
                plugin = plugin,
                messages = messages,
                taskScope = taskScope,
                projects = { playerId -> constructionProjects.values.filter { it.playerId == playerId } },
                onTeleport = ::teleportToConstruction,
                onInspect = { player, project -> openConstructionMenu(player, project, "projects_menu") },
            ).also { initializedConstructionProjectsMenu = it }
            constructionSiteDisplays = BuilderConstructionSiteDisplayManager(
                plugin = plugin,
                settings = config.constructionSiteDisplaySettings(),
                messages = messages,
                projectLookup = constructionProjects::get,
                onInspect = { player, project -> openConstructionMenu(player, project, "construction_plaque") },
                planningTasks = planningTasks,
            ).also { initializedConstructionSiteDisplays = it }
            bookPreviewPresentation = BuilderBookPreviewPresentation(
                plugin = plugin,
                renderer = this.displayRenderer,
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

                    override fun currentWorldConfirmation(player: Player): BuilderBookPreviewConfirmation? =
                        currentBookPreviewConfirmation(player, worldControls = true)

                    override fun confirm(player: Player, complete: (Boolean) -> Unit) = confirmBookPreview(player, complete)

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
                        runBookPreviewAction(player, "book_preview_cancel") { cancelPlan(player) }
                    }
                },
                panelHeightOffset = config.constructionSitePanelHeightOffset,
                panelFrontOffset = config.constructionSitePanelFrontOffset,
                panelInteractionWidth = config.constructionSitePanelInteractionWidth,
                panelInteractionHeight = config.constructionSitePanelInteractionHeight,
                panelLineWidth = config.constructionSitePanelLineWidth,
                panelBackgroundColor = config.plaqueAppearance().background,
                panelGlowColor = Color.fromRGB(config.constructionSiteGlowColor.removePrefix("#").toInt(16)),
                viewRange = config.constructionSiteViewRange,
                planningTasks = planningTasks,
                panelAppearance = config.plaqueAppearance(),
                worldControlsEnabled = config.bookPreviewWorldControlsEnabled,
                plaqueFollowPlayer = config.bookPreviewPlaqueFollowPlayer,
            ).also { initializedBookPreviewPresentation = it }
            BuildingManager.installPreviewBridge(
                bookPreviewPresentation,
                { action -> taskScope.runSync(action) },
                { player, failure -> error("Builder schematic transform failed", failure); send(player, "book.failed") },
            )
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
                renderSelection = ::renderSelection,
                renderPlan = this.displayRenderer::plan,
                clearPlan = { playerId ->
                    this.displayRenderer.clearPlan(playerId)
                    bookPreviewPresentation.clearConfirmation(playerId)
                },
                onExpired = { playerId ->
                    pastePreview.clear(playerId)
                    shop.clear(playerId)
                    crown.clearAnchor(playerId)
                },
                onRenderFailure = { player, failure ->
                    warn("Builder-tools preview failed for {}: {}", player.name, failure.message)
                },
            ).also { initializedPreviews = it }
            crown = BuilderCrownController(
                plugin = plugin,
                inputSuppressed = { entityEditor?.isEditing(it) == true || pastePreview.isActive(it.uniqueId) },
                messages = messages,
                safety = safety,
                selections = selections,
                maximumChanges = config.maxChanges,
                maximumScanVolume = config.maxScanVolume,
                host = object : BuilderCrownHost, BuilderPlanningTasks by planningTasks {
                    override fun operationLocked(playerId: UUID): Boolean = operationLocks.isPlayerLocked(playerId)

                    override fun ensureAvailable(player: Player) = this@BuilderToolsRuntime.ensureAvailable(player)

                    override fun ensurePermission(player: Player) = ensureFeaturePermission(player, BuilderFeature.CROWN)

                    override fun ensureMutable(player: Player, block: Block) = this@BuilderToolsRuntime.ensureMutable(player, block)

                    override fun ensureScannable(player: Player, block: Block) {
                        ensureInRangeAndLoaded(player, block)
                        if (!block.world.worldBorder.isInside(block.location)) {
                            throw blockRejection("errors.protection", "world_border", block)
                        }
                    }

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

                    override fun createPlanAsync(
                        player: Player, changes: List<BuilderBlockChange>, costs: List<BuilderItemAmount>,
                    ): CompletableFuture<BuilderPlan> = newPlanAsync(player, BuilderPlanKind.CROWN, changes, costs, emptyList())

                    override fun startPlanning(
                        player: Player, request: (() -> Boolean) -> CompletableFuture<BuilderPlan>,
                    ) = this@BuilderToolsRuntime.startPlanning(player, request = request)

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
                draftStorage = draftStorage ?: PlayerBuildBookDraftStorage(
                    loadBuilding, planningTasks, plugin.dataPath, config.schematicsRoot, BuildBookSettings.maxBooksPerPlayer,
                ),
                schematicVerifier = bookSchematicVerifier,
                loadBuilding = loadBuilding,
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
                    releaseStartupRecoveryLock(playerId)
                    Bukkit.getPlayer(playerId)?.takeIf(Player::isOnline)?.let(books::onPlayerAvailable)
                },
            ).also { initializedPlayerRecoveries = it }
            publishRuntimeHealth()
            checkNotNull(taskScope.runTimer(0L, config.healthRefreshPeriodTicks, ::publishRuntimeHealth)) {
                "Builder-tools health publication task was not scheduled"
            }
            loadRecoveryState()
            books.recoveryIsolationReady.whenCompleteSync(taskScope) { _, failure ->
                if (failure != null) {
                    recoveryBlocked = true
                    error("Builder draft recovery isolation failed", failure)
                } else {
                    draftRecoveryLocksReady = true
                    endStartupBarrierIfReady()
                }
            }
            books.start()
            loadConstructionProjects()
            checkNotNull(
                taskScope.runTimer(
                    BuilderConstructionCadencePolicy.TIMER_PERIOD_TICKS,
                    BuilderConstructionCadencePolicy.TIMER_PERIOD_TICKS,
                    ::tickConstructionProjects,
                ),
            ) { "Builder construction project task was not scheduled" }
            val nativeDialogs = PaperDialogRuntime(plugin)
            materialPicker = BuilderMaterialPicker(plugin, messages, nativeDialogs)
            crownSelectionDialog = BuilderCrownSelectionDialog(
                messages = messages,
                dialogs = nativeDialogs,
                materialPicker = checkNotNull(materialPicker),
                leafMaterials = Material.entries.filter(safety::isLeaf).sortedBy(Material::name),
                isCurrent = ::crownSelectionDialogCurrent,
                onPreview = ::prepareSelectionCrown,
                presentation = BuilderDialogPresentation(config.dialogDecorationFont),
            )
            selectionActionPanel = BuilderSelectionActionPanel(
                plugin, config.selectionPanelSettings(), messages, ::selectionPanelView, ::onSelectionPanelAction,
                onSelectionGlowSuppression = this.displayRenderer::suppressSelectionGlow,
            )
            entityEditor = BuilderDisplayEditor(
                plugin = plugin,
                presentation = BuilderDialogPresentation(config.dialogDecorationFont),
                text = { player, key, values ->
                    messages.render(key, locale(player), values.mapValues { messages.literal(it.value) })
                },
                canBuild = { player, block ->
                    runCatching {
                        ensureAvailable(player)
                        if (operationLocks.isPlayerLocked(player.uniqueId)) throw BuilderUserFailure("errors.busy")
                        // Decorations often occupy air: request the placement flag explicitly.
                        ensureMutable(player, block, Material.STONE)
                    }.isSuccess
                },
            )
            placementAssist = BuilderPlacementAssist(
                plugin = plugin,
                renderer = this.displayRenderer,
                messages = messages,
                taskScope = taskScope,
                isContextAllowed = { player ->
                    !closed && !player.isDead && entityEditor?.isEditing(player) != true &&
                        !pastePreview.isActive(player.uniqueId) &&
                        !operationLocks.isPlayerLocked(player.uniqueId) &&
                        runCatching { ensureOperationalContext(player) }.isSuccess
                },
                canBuild = { player, block, material ->
                    runCatching { ensureMutable(player, block, material) }.isSuccess
                },
                canUseSupport = { block -> safety.isSafeExisting(block) },
                sendMessage = sendPlayerMessage,
            )
            checkNotNull(taskScope.runTimer(1L, 1L) {
                selectionActionPanel?.tick()
                bookPreviewPresentation.tick()
                pastePreview.tick()
                tickSelectionTargets()
                entityEditor?.tick()
                placementAssist?.tick()
            }) {
                "Builder selection action panel task was not scheduled"
            }
        } catch (failure: Throwable) {
            var cleanupFailure: Throwable? = null
            fun attempt(action: () -> Unit) {
                try { action() } catch (closeFailure: Throwable) {
                    if (cleanupFailure == null) cleanupFailure = closeFailure
                    else if (cleanupFailure !== closeFailure) cleanupFailure!!.addSuppressed(closeFailure)
                }
            }
            attempt { HandlerList.unregisterAll(this) }
            attempt { pastePreview.close() }
            attempt { selectionActionPanel?.close() }
            attempt { crownSelectionDialog?.close() }
            attempt { entityEditor?.close() }
            attempt { placementAssist?.close() }
            attempt { materialPicker?.close() }
            attempt { initializedPlayerRecoveries?.close() }
            attempt { initializedBooks?.close() }
            attempt { initializedCrown?.close() }
            attempt { initializedPreviews?.close() }
            attempt { initializedConstructionSiteDisplays?.close() }
            attempt { initializedConstructionProjectsMenu?.close() }
            attempt { initializedConstructionMenus?.close() }
            val visualDrains = listOf(
                runCatching { entityEditor?.closeAsync() ?: CompletableFuture.completedFuture(null) }
                    .getOrElse { CompletableFuture.failedFuture(it) },
                runCatching { initializedBookPreviewPresentation?.closeAsync() ?: CompletableFuture.completedFuture(null) }
                    .getOrElse { CompletableFuture.failedFuture(it) },
                runCatching { this.displayRenderer.closeAsync() }.getOrElse { CompletableFuture.failedFuture(it) },
            )
            attempt { bookRequests.close() }
            attempt { planningTasks.close() }
            attempt { schematics.close() }
            attempt { taskScope.close() }
            attempt { operationLocks.close() }
            attempt { shop.close() }
            attempt { BuildingManager.clearPreviews() }
            attempt { BuildingManager.installPreviewBridge(null) }
            attempt { closeStorageExecutor(visualDrains) { initializedBooks?.closeRegistry() } }
            cleanupFailure?.let { throw BuilderToolsRuntimeActivationException(failure, it) }
            throw failure
        }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.firstOrNull()?.equals("systembook", ignoreCase = true) == true) {
            return BuilderSystemBookIssuer.issue(
                sender,
                args.drop(1),
                systemBuildBookResolver,
                messages,
                { systemBuildBookStarterIds },
                { sender.sendMessage(messages.render("errors.no-permission")) },
            )
        }
        val player = sender as? Player ?: run {
            sender.sendMessage(messages.render("errors.player-only"))
            return true
        }
        runBuilderAction(player, "command_${BuilderRootCommand.parse(args.firstOrNull())?.name?.lowercase(Locale.ROOT) ?: "help"}") {
            handleBuilder(player, args)
        }
        return true
    }

    private fun constructionActionContext(
        project: BuilderConstructionProjectRecord,
        action: String,
        position: BuilderBlockPos = project.siteAnchor ?: project.plan.changes.first().position,
    ) = BuilderActionContext(
        project.playerId, project.playerName, action,
        BuilderActionTarget(position.worldId, Bukkit.getWorld(position.worldId)?.name, position.x, position.y, position.z),
        operationId = project.projectId, buildingId = project.plan.bookBuildingId,
        blueprintId = project.plan.bookBlueprintId, bookInstanceId = project.plan.bookInstanceId,
    )

    private fun constructionPortFor(project: BuilderConstructionProjectRecord): BuilderConstructionProjectPort =
        builderConstructionWorldReadPort(project.projectId, constructionPort, actionDiagnostics) { position ->
            constructionActionContext(project, CONSTRUCTION_WORLD_READ_PHASE, position)
        }

    private inline fun <T> constructionResourceAction(
        project: BuilderConstructionProjectRecord,
        phase: String,
        position: BuilderBlockPos = project.steps.getOrNull(project.cursor)?.change?.position
            ?: project.siteAnchor ?: project.plan.changes.first().position,
        action: () -> T,
    ): T = try {
        action().also { actionDiagnostics.constructionAllowed(project.projectId, phase) }
    } catch (failure: Throwable) {
        val context = constructionActionContext(project, phase, position)
        if (failure is BuilderConstructionTemporarilyUnavailableException) {
            actionDiagnostics.constructionBlocked(context, "temporarily_unavailable")
        } else {
            actionDiagnostics.constructionBlocked(context, "unexpected_${BuilderToolsFailureType.of(failure)}", failure)
        }
        throw failure
    }

    private fun beginAction(
        player: Player,
        action: String,
        target: Location? = null,
        site: ConstructionSite? = BuildingManager.pending(player.uniqueId),
        data: BuildBookData? = site?.bookData,
        project: BuilderConstructionProjectRecord? = null,
        requestedOperationId: UUID? = null,
    ): BuilderActionDiagnostics.Attempt {
        val plan = project?.plan ?: previews.plan(player.uniqueId)
        val position = project?.siteAnchor ?: plan?.changes?.firstOrNull()?.position
        val at = target?.let(BuilderActionTarget::at) ?: site?.adjustedCenter?.let(BuilderActionTarget::at)
            ?: position?.let { BuilderActionTarget(it.worldId, Bukkit.getWorld(it.worldId)?.name, it.x, it.y, it.z) }
            ?: BuilderActionTarget.at(player.location)
        return actionDiagnostics.begin(BuilderActionContext(
            player.uniqueId, player.name, action, at,
            operationId = requestedOperationId ?: project?.projectId ?: plan?.id,
            previewId = site?.let { "${it.player.uniqueId}:${it.expiresAtMillis}" },
            buildingId = data?.buildingId ?: plan?.bookBuildingId,
            blueprintId = data?.blueprintId ?: plan?.bookBlueprintId,
            bookInstanceId = data?.instanceId ?: plan?.bookInstanceId,
        ))
    }

    private fun runBuilderAction(
        player: Player,
        actionName: String = "builder_action",
        diagnostic: BuilderActionDiagnostics.Attempt = beginAction(player, actionName),
        action: () -> Unit,
    ) {
        try {
            action()
            if (!diagnostic.outcomeRecorded) diagnostic.outcome("accepted", "handler_returned")
        } catch (failure: BuilderUserFailure) {
            diagnostic.failed(failure)
            send(player, failure.path, failure.values)
        } catch (failure: IllegalArgumentException) {
            diagnostic.failed(failure)
            send(player, "errors.plan-failed")
        } catch (failure: Throwable) {
            diagnostic.failed(failure)
            send(player, "errors.plan-failed")
        }
    }

    private data class SelectionPanelContext(val selection: BuilderSelection, val state: Any?)

    private data class MoveDirectionPick(
        val selection: BuilderSelection,
        val gameMode: GameMode,
        val slot: Int,
        val item: ItemStack,
        val startedTick: Int,
    )
    private data class MoveSelection(val planId: UUID, val before: BuilderSelection, val after: BuilderSelection)
    private val moveDirectionPicks = mutableMapOf<UUID, MoveDirectionPick>()
    private val moveSelections = mutableMapOf<UUID, MoveSelection>()

    private fun canUseSelectionPanel(player: Player): Boolean =
        !closed && player.isOnline && !recovering && !recoveryBlocked &&
            !pastePreview.capturesInput(player.uniqueId) &&
            player.uniqueId !in moveDirectionPicks &&
            entityEditor?.isEditing(player) != true &&
            isSelector(player.inventory.itemInMainHand) && hasUsePermission(player) &&
            BuilderGameModePolicy.allows(player.gameMode) && config.allowsWorld(player.world.name) &&
            !playerRecoveries.contains(player.uniqueId) && !books.health().recoveryBlocked

    internal fun selectionPanelView(player: Player): BuilderPanelView? {
        val id = player.uniqueId
        val selection = selectionOrNull(player)
        if (selection == null || !canUseSelectionPanel(player)) {
            materialScans.remove(id)
            panelPlanSummaries.remove(id)
            selectionUndoAvailable.remove(id)
            selectionPanelPages.remove(id)
            crownSelectionDialog?.close(id)
            materialPicker?.close(id)
            return null
        }
        if (materialPicker?.isOpen(id) == true || crownSelectionDialog?.isOpen(id) == true) return null
        fun text(key: String, values: Map<String, Component> = emptyMap()) =
            messages.render("selection-panel.$key", locale(player), values)
        materialScans[id]?.let { token ->
            return BuilderPanelView(
                SelectionPanelContext(selection, token), selection, text("scanning"),
                listOf(BuilderPanelAction.CANCEL),
            )
        }
        operationLocks.operation(id)?.let { operation ->
            return BuilderPanelView(
                SelectionPanelContext(selection, operation.record.operationId), selection,
                text("progress", mapOf(
                    "done" to messages.literal(operation.appliedChanges),
                    "count" to messages.literal(operation.record.plan.changes.size),
                )),
                emptyList(),
            )
        }
        if (operationLocks.isPlayerLocked(id) || id in constructionStarts) return null
        previews.plan(id)?.let { plan ->
            if (plan.kind == BuilderPlanKind.BUILD_BOOK) return null
            val summary = panelPlanSummaries[id]?.takeIf { it.first == plan.id }?.second
                ?: text("plan", mapOf(
                    "kind" to kindLabel(player, plan.kind),
                    "count" to messages.literal(plan.changes.size),
                    "cost" to itemsSummary(player, plan.costs),
                )).also { panelPlanSummaries[id] = plan.id to it }
            return BuilderPanelView(
                SelectionPanelContext(selection, plan.id), selection,
                summary,
                buildList {
                    add(BuilderPanelAction.CONFIRM)
                    add(BuilderPanelAction.CANCEL)
                    if (plan.kind == BuilderPlanKind.CROWN &&
                        rootCommandAvailable(player, BuilderRootCommand.CROWN)
                    ) add(BuilderPanelAction.CROWN)
                    if (plan.kind == BuilderPlanKind.PASTE) {
                        add(BuilderPanelAction.ROTATE_LEFT)
                        add(BuilderPanelAction.ROTATE_RIGHT)
                    }
                },
            )
        }
        panelPlanSummaries.remove(id)
        val available = buildList {
            fun offer(action: BuilderPanelAction, command: BuilderRootCommand) {
                if (rootCommandAvailable(player, command)) add(action)
            }
            offer(BuilderPanelAction.FILL, BuilderRootCommand.FILL)
            offer(BuilderPanelAction.REPLACE, BuilderRootCommand.REPLACE)
            offer(BuilderPanelAction.COPY, BuilderRootCommand.COPY)
            offer(BuilderPanelAction.MOVE, BuilderRootCommand.MOVE)
            if (clipboardController.current(id) != null) offer(BuilderPanelAction.PASTE, BuilderRootCommand.PASTE)
            add(BuilderPanelAction.CLEAR)
            offer(BuilderPanelAction.DECONSTRUCT, BuilderRootCommand.DECONSTRUCT)
            if (player.hasPermission("arcbuild.book.create") &&
                rootCommandAvailable(player, BuilderRootCommand.COPY) &&
                rootCommandAvailable(player, BuilderRootCommand.BOOK)
            ) add(BuilderPanelAction.DRAFT)
            if (selectionCanUndo(id)) add(BuilderPanelAction.UNDO)
            offer(BuilderPanelAction.DISCONNECT, BuilderRootCommand.DISCONNECT)
            offer(BuilderPanelAction.CROWN, BuilderRootCommand.CROWN)
            add(if (placementAssist?.isEnabled(player) == true) BuilderPanelAction.ASSIST_OFF else BuilderPanelAction.ASSIST_ON)
        }
        val pageCount = (available.size + 5) / 6
        val page = (selectionPanelPages[id] ?: 0).mod(pageCount)
        selectionPanelPages[id] = page
        val actions = available.drop(page * 6).take(6) + if (pageCount > 1) {
            listOf(BuilderPanelAction.PREVIOUS_PAGE, BuilderPanelAction.NEXT_PAGE)
        } else emptyList()
        return BuilderPanelView(
            SelectionPanelContext(selection, page), selection,
            text("selection", mapOf(
                "x" to messages.literal(selection.sizeX),
                "y" to messages.literal(selection.sizeY),
                "z" to messages.literal(selection.sizeZ),
            )), actions,
            pagination = if (pageCount > 1) text("pagination", mapOf(
                "current" to messages.literal(page + 1),
                "total" to messages.literal(pageCount),
            )) else Component.empty(),
        )
    }

    private fun selectionCanUndo(playerId: UUID): Boolean {
        val now = System.currentTimeMillis()
        val cached = selectionUndoAvailable[playerId]
        if (cached != null && now - cached.first < 1000L) return cached.second
        val available = committedRecords.values.any {
            it.playerId == playerId && it.phase == BuilderJournalPhase.COMMITTED &&
                it.plan.kind != BuilderPlanKind.UNDO && it.operationId !in consumedUndoSources &&
                (it.committedAtMillis ?: 0L) + config.undoTtl.toMillis() > now
        }
        selectionUndoAvailable[playerId] = now to available
        return available
    }

    internal fun onSelectionPanelAction(player: Player, action: BuilderPanelAction) {
        val diagnostic = beginAction(player, "selection_${action.name.lowercase(Locale.ROOT)}")
        runBuilderAction(player, diagnostic = diagnostic) {
            val current = selectionPanelView(player) ?: return@runBuilderAction diagnostic.outcome("ignored", "stale_context")
            if (action !in current.actions) return@runBuilderAction diagnostic.outcome("ignored", "action_unavailable")
            ensureAvailable(player)
            when (action) {
                BuilderPanelAction.PREVIOUS_PAGE -> selectionPanelPages.compute(player.uniqueId) { _, page -> (page ?: 0) - 1 }
                BuilderPanelAction.NEXT_PAGE -> selectionPanelPages.compute(player.uniqueId) { _, page -> (page ?: 0) + 1 }
                BuilderPanelAction.FILL -> openFillMaterialPicker(player)
                BuilderPanelAction.REPLACE -> openReplaceMaterialPicker(player)
                BuilderPanelAction.COPY -> handleBuilder(player, arrayOf("copy"))
                BuilderPanelAction.MOVE -> armSelectionMove(player)
                BuilderPanelAction.PASTE -> handleBuilder(player, arrayOf("paste"))
                BuilderPanelAction.DECONSTRUCT -> handleBuilder(player, arrayOf("deconstruct"))
                BuilderPanelAction.DISCONNECT -> handleBuilder(player, arrayOf("disconnect"))
                BuilderPanelAction.CROWN -> openSelectionCrownDialog(player)
                BuilderPanelAction.DRAFT -> {
                    requestPlanning(player, { current -> clipboardController.copy(player, current) }) { books.createSelectionDraft(player) }
                }
                BuilderPanelAction.CLEAR -> handleBuilder(player, arrayOf("clear"))
                BuilderPanelAction.UNDO -> handleBuilder(player, arrayOf("undo"))
                BuilderPanelAction.CONFIRM -> handleBuilder(player, arrayOf("confirm"))
                BuilderPanelAction.CANCEL -> if (materialScans.remove(player.uniqueId) == null) handleBuilder(player, arrayOf("cancel")) else Unit
                BuilderPanelAction.ROTATE_LEFT -> handleBuilder(player, arrayOf("paste", "left"))
                BuilderPanelAction.ROTATE_RIGHT -> handleBuilder(player, arrayOf("paste", "right"))
                BuilderPanelAction.ASSIST_ON -> handleBuilder(player, arrayOf("assist", "on"))
                BuilderPanelAction.ASSIST_OFF -> handleBuilder(player, arrayOf("assist", "off"))
            }
        }
    }

    private fun ensureMovePermission(player: Player) {
        ensureFeaturePermission(player, BuilderFeature.COPY)
        ensureFeaturePermission(player, BuilderFeature.PASTE)
    }

    private fun armSelectionMove(player: Player) {
        ensureMovePermission(player)
        if (operationLocks.isPlayerLocked(player.uniqueId)) throw BuilderUserFailure("errors.busy")
        val selection = requiredSelection(player)
        bookRequests.invalidate(player.uniqueId)
        discardPendingPlan(player.uniqueId)
        placementAssist?.clearPlayer(player.uniqueId)
        dismissSelectionControls(player.uniqueId)
        moveDirectionPicks[player.uniqueId] = MoveDirectionPick(selection, player.gameMode,
            player.inventory.heldItemSlot, player.inventory.itemInMainHand.clone(), Bukkit.getCurrentTick())
        send(player, "move.choose-direction")
    }

    private fun currentMoveDirectionPick(player: Player, pick: MoveDirectionPick): Boolean =
        !closed && player.isOnline && !player.isDead && player.gameMode == pick.gameMode &&
            player.world.uid == pick.selection.worldId && selectionOrNull(player) == pick.selection &&
            player.inventory.heldItemSlot == pick.slot && player.inventory.itemInMainHand == pick.item &&
            isSelector(player.inventory.itemInMainHand) && !operationLocks.isPlayerLocked(player.uniqueId) &&
            runCatching { ensureAvailable(player); ensureMovePermission(player) }.isSuccess

    private fun prepareMove(player: Player) {
        ensureMovePermission(player)
        val selection = requiredSelection(player)
        val direction = BuilderMoveController.lookDirection(player.eyeLocation.direction)
        val movedSelection = selection.shifted(direction)
        requestPlanning(player, { current -> moveController.plan(player, direction, current) }) { plan ->
            preparePlan(player, plan)
            moveSelections[player.uniqueId] = MoveSelection(plan.id, selection, movedSelection)
        }
    }

    private fun materialPickerCurrent(player: Player, selection: BuilderSelection, feature: BuilderFeature): Boolean =
        canUseSelectionPanel(player) && selectionOrNull(player) == selection &&
            !operationLocks.isPlayerLocked(player.uniqueId) && !previews.contains(player.uniqueId) &&
            player.uniqueId !in constructionStarts && BuilderPermissionPolicy.canUse(feature, player::hasPermission)

    private fun openSelectionCrownDialog(player: Player) {
        ensureAvailable(player)
        ensureFeaturePermission(player, BuilderFeature.CROWN)
        val selection = requiredSelection(player)
        val pending = previews.plan(player.uniqueId)
        if (pending != null && pending.kind != BuilderPlanKind.CROWN) throw BuilderUserFailure("errors.expired")
        val settings = crown.settings(player.uniqueId)
        val initial = BuilderCrownSelectionOptions(
            density = settings.density,
            materialName = settings.palette.firstOrNull()?.materialName ?: "oak_leaves",
        )
        crownSelectionDialog?.open(player, selection, pending?.id, initial)
            ?: throw IllegalStateException("Selection crown dialog is unavailable")
    }

    private fun crownSelectionDialogCurrent(
        player: Player,
        selection: BuilderSelection,
        expectedPlanId: UUID?,
    ): Boolean {
        if (!canUseSelectionPanel(player) || !BuilderPermissionPolicy.canUse(BuilderFeature.CROWN, player::hasPermission)) {
            return false
        }
        if (operationLocks.isPlayerLocked(player.uniqueId) || player.uniqueId in constructionStarts) return false
        val current = runCatching { requiredSelection(player) }.getOrNull() ?: return false
        if (current != selection) return false
        val pending = previews.plan(player.uniqueId)
        return if (expectedPlanId == null) {
            pending == null
        } else {
            pending?.id == expectedPlanId && pending.kind == BuilderPlanKind.CROWN
        }
    }

    private fun prepareSelectionCrown(
        player: Player,
        selection: BuilderSelection,
        expectedPlanId: UUID?,
        options: BuilderCrownSelectionOptions,
    ): Boolean {
        if (!crownSelectionDialogCurrent(player, selection, expectedPlanId)) return false
        return try {
            startPlanning(player) { current -> crown.prepareSelection(player, selection, options, current) }
            true
        } catch (failure: BuilderUserFailure) {
            send(player, failure.path, failure.values)
            false
        } catch (failure: IllegalArgumentException) {
            warn("Builder-tools rejected crown selection for {}: {}", player.name, failure.message)
            send(player, "errors.plan-failed")
            false
        } catch (failure: Throwable) {
            error("Builder-tools crown selection failed for ${player.name}", failure)
            send(player, "errors.plan-failed")
            false
        }
    }

    private fun dismissSelectionControls(playerId: UUID) {
        moveDirectionPicks.remove(playerId)
        pastePreview.clear(playerId)
        clearSelectionTarget(playerId)
        if (worldEditSelectionPoints.remove(playerId) != null) {
            Bukkit.getPlayer(playerId)?.let(::renderSelection)
        }
        materialScans.remove(playerId)
        selectionPanelPages.remove(playerId)
        crownSelectionDialog?.close(playerId)
        materialPicker?.close(playerId)
        selectionActionPanel?.clear(playerId)
    }

    private fun invalidatePendingBookRequests(playerId: UUID) {
        bookRequests.invalidate(playerId)
        books.invalidateSchematicRequests(playerId)
        BuildingManager.invalidatePendingTransform(playerId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSelectionToolSlotChange(event: PlayerItemHeldEvent) {
        invalidatePendingBookRequests(event.player.uniqueId)
        dismissSelectionControls(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSelectionToolSwap(event: PlayerSwapHandItemsEvent) {
        invalidatePendingBookRequests(event.player.uniqueId)
        dismissSelectionControls(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSelectionToolDrop(event: PlayerDropItemEvent) {
        invalidatePendingBookRequests(event.player.uniqueId)
        dismissSelectionControls(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSelectionTeleport(event: PlayerTeleportEvent) {
        invalidatePendingBookRequests(event.player.uniqueId)
        dismissSelectionControls(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onSelectionWorldChange(event: PlayerChangedWorldEvent) = dismissSelectionControls(event.player.uniqueId)

    private fun clearSelectionTarget(playerId: UUID) {
        selectionTargets.remove(playerId)
        displayRenderer.clearSelectionTarget(playerId)
    }

    private fun canUseWorldEditSelection(player: Player): Boolean =
        !closed && player.isOnline && !player.isDead &&
            !operationLocks.isPlayerLocked(player.uniqueId) && player.uniqueId !in constructionStarts &&
            !pastePreview.capturesInput(player.uniqueId) && player.uniqueId !in moveDirectionPicks &&
            entityEditor?.isEditing(player) != true && BuildingManager.pending(player.uniqueId) == null &&
            !isSelector(player.inventory.itemInMainHand) && BuildBookCodec.read(player.inventory.itemInMainHand) == null

    private fun renderSelection(player: Player) {
        val points = worldEditSelectionPoints[player.uniqueId]
            ?: if (BuilderGameModePolicy.allows(player.gameMode) && config.allowsWorld(player.world.name)) {
                selections.points(player.uniqueId, player.world.uid)
            } else BuilderSelectionPoints(null, null)
        val selection = points.first?.let { first -> points.second?.let { BuilderSelection(first, it) } }
        displayRenderer.selection(player, points, selection)
    }

    private fun refreshWorldEditSelection(player: Player, initializeSession: Boolean = false): Boolean {
        val points = if (worldEditSelection != null && canUseWorldEditSelection(player)) {
            worldEditSelection.points(player, initializeSession)
        } else null
        if (points == null) {
            if (worldEditSelectionPoints.remove(player.uniqueId) != null) renderSelection(player)
            return false
        }
        if (worldEditSelectionPoints.put(player.uniqueId, points) != points) renderSelection(player)
        return true
    }

    private fun refreshSelectionTarget(player: Player): BuilderBlockPos? {
        val worldEdit = player.uniqueId in worldEditSelectionPoints
        val target = if (worldEdit) selectionTargetResolver(player, BuilderSelectionTarget.RANGE)
        else if (canUseSelectionPanel(player) && !player.isDead && !constructionRecovering &&
            player.uniqueId !in constructionStarts && !operationLocks.isPlayerLocked(player.uniqueId) &&
            selectionActionPanel?.isTargeted(player) != true
        ) selectionTargetResolver(player, config.maximumRange) else null
        if (target == null) {
            if (selectionTargets.containsKey(player.uniqueId)) clearSelectionTarget(player.uniqueId)
        } else if (selectionTargets[player.uniqueId] != target) {
            displayRenderer.selectionTarget(player, target)
            selectionTargets[player.uniqueId] = target
        }
        return target
    }

    private fun tickSelectionTargets() {
        if (closed) return
        Bukkit.getOnlinePlayers().forEach { player ->
            try {
                moveDirectionPicks[player.uniqueId]?.let { pick ->
                    if (!currentMoveDirectionPick(player, pick)) moveDirectionPicks.remove(player.uniqueId, pick)
                }
                refreshWorldEditSelection(player)
                refreshSelectionTarget(player)
                selectionTargetFailures.remove(player.uniqueId)
            } catch (failure: Exception) {
                if (worldEditSelectionPoints.remove(player.uniqueId) != null) renderSelection(player)
                clearSelectionTarget(player.uniqueId)
                if (selectionTargetFailures.add(player.uniqueId)) {
                    error("Builder selector target failed for ${player.name}", failure)
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBookGameModeChange(event: org.bukkit.event.player.PlayerGameModeChangeEvent) {
        invalidatePendingBookRequests(event.player.uniqueId)
        moveDirectionPicks.remove(event.player.uniqueId)
        pastePreview.clear(event.player.uniqueId)
    }

    private fun openFillMaterialPicker(player: Player) {
        ensureFeaturePermission(player, BuilderFeature.FILL)
        val selection = requiredSelection(player)
        materialPicker?.open(player, "material-picker.fill-title", placementMaterials,
            { materialPickerCurrent(player, selection, BuilderFeature.FILL) },
            { material -> runBuilderAction(player) { startPlanning(player) { current -> fillController.plan(player, material, current) } } },
        )
    }

    private fun openReplaceMaterialPicker(player: Player) {
        ensureFeaturePermission(player, BuilderFeature.REPLACE)
        val selection = requiredSelection(player)
        val diagnostic = beginAction(player, "replace_material_scan")
        val id = player.uniqueId
        val token = Any()
        materialScans[id] = token
        val positions = selection.positionsBottomUp().iterator()
        val materials = linkedSetOf<Material>()
        val world = player.world
        planningTasks.capture({ materialScans[id] === token && materialPickerCurrent(player, selection, BuilderFeature.REPLACE) }) {
            if (!positions.hasNext()) true else {
                val position = positions.next()
                if (!world.isChunkLoaded(position.x shr 4, position.z shr 4)) throw BuilderUserFailure("errors.chunk-unloaded")
                val block = world.getBlockAt(position.x, position.y, position.z)
                ensureInRangeAndLoaded(player, block)
                if (safety.isSafeExisting(block, allowAir = true) && !BuilderReplaceController.isCoupledMultiBlock(block.blockData)) {
                    materials += if (block.type.isAir) Material.AIR else block.type
                }
                !positions.hasNext()
            }
        }.whenCompleteSync(taskScope) { _, failure ->
            if (!materialScans.remove(id, token)) {
                diagnostic.outcome("cancelled", "scan_superseded")
                return@whenCompleteSync
            }
            if (failure != null) {
                diagnostic.failed(failure)
                return@whenCompleteSync reportPlanningFailure(player, failure)
            }
            diagnostic.outcome("ready", "material_choices_scanned")
            materialPicker?.open(player, "material-picker.replace-source-title", materials.toList(),
                { materialPickerCurrent(player, selection, BuilderFeature.REPLACE) },
                { source ->
                    materialPicker?.open(player, "material-picker.replace-target-title", replaceMaterials.filter { it != source },
                        { materialPickerCurrent(player, selection, BuilderFeature.REPLACE) },
                        { target -> runBuilderAction(player) { startPlanning(player) { current -> replaceController.plan(player, source, target, current) } } },
                        beginFlow = false,
                    )
                }, closeOnSelect = false,
            )
        }
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> {
        val systemSuggestions = BuilderSystemBookIssuer.tabComplete(sender, args, { systemBuildBookIds }) {
            Bukkit.getOnlinePlayers().map(Player::getName)
        }
        if (args.size > 1 && args[0].equals("systembook", ignoreCase = true)) return systemSuggestions
        if (sender !is Player) return systemSuggestions
        if (args.size == 1) {
            return filterPrefix(
                BuilderRootCommand.entries.filter {
                    (it == BuilderRootCommand.ASSIST || hasUsePermission(sender)) && rootCommandAvailable(sender, it)
                }.map(BuilderRootCommand::literal) + systemSuggestions,
                args[0],
            )
        }
        val root = BuilderRootCommand.parse(args.firstOrNull()) ?: return emptyList()
        if (root != BuilderRootCommand.ASSIST && !hasUsePermission(sender)) return emptyList()
        if (!rootCommandAvailable(sender, root)) return emptyList()
        if (root == BuilderRootCommand.ASSIST) {
            return if (args.size == 2) filterPrefix(listOf("on", "off"), args[1]) else emptyList()
        }
        if (root == BuilderRootCommand.DISPLAY) return entityEditor?.tabComplete(args.drop(1)).orEmpty()
        if (root == BuilderRootCommand.CROWN) return crown.tabComplete(args)
        if (root == BuilderRootCommand.REPLACE) {
            if (args.size !in 2..4) return emptyList()
            if (args.size >= 3 && BuilderMaterialArguments.parse(args[1])?.let { it.isAir || it in replaceMaterials } != true) {
                return emptyList()
            }
            if (args.size == 4 && BuilderMaterialArguments.parse(args[2]) !in replaceMaterials) return emptyList()
            val suggestions = when (args.size) {
                2 -> replaceSourceMaterialNames
                3 -> replaceMaterialNames
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
            BuilderRootCommand.BOOK -> listOf("guide", "status", "draft", "activate", "copy", "sell", "confirm", "cancel", "menu")
                .filter { subcommand ->
                    when (subcommand) {
                        "draft" -> sender.hasPermission("arcbuild.book.create") &&
                            BuilderPermissionPolicy.canUse(BuilderFeature.COPY, sender::hasPermission)
                        "activate", "copy" -> sender.hasPermission("arcbuild.book.create")
                        "sell" -> sender.hasPermission("arcbuild.book.sell")
                        "menu" -> sender.hasPermission("arcbuild.book.use")
                        else -> true
                    }
                }
            BuilderRootCommand.FILL -> safeMaterialNames
            else -> emptyList()
        }
        return filterPrefix(suggestions, args[1])
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSendSuggestions(event: AsyncPlayerSendSuggestionsEvent) {
        val parts = event.buffer.removePrefix("/").lowercase(Locale.ROOT).split(' ')
        if (parts.firstOrNull() !in setOf("builder", "arcbuilder:builder")) return
        if (parts.getOrNull(1) == "systembook") {
            event.suggestions = BuilderSystemBookIssuer.clientSuggestions(event.suggestions,
                MessageComponentSerializer.message().serialize(
                    messages.render("book.system-issue.directory-suggestion", locale(event.player)),
                ),
            )
            return
        }
        if (!(parts.getOrNull(1) == "fill" && parts.size == 3 ||
                parts.getOrNull(1) == "replace" && parts.size in 3..4)) return
        // Brigadier sorts Bukkit completions again; reorder the final packet without adding suggestions.
        val suggestions = event.suggestions
        event.suggestions = Suggestions(suggestions.range, suggestions.list.sortedBy {
            !BuilderMaterialArguments.isRussianName(it.text)
        })
    }

    private fun handleBuilder(player: Player, args: Array<out String>) {
        val root = BuilderRootCommand.parse(args.firstOrNull()) ?: BuilderRootCommand.HELP
        if (root !in setOf(BuilderRootCommand.CANCEL, BuilderRootCommand.STATUS, BuilderRootCommand.HELP)) {
            moveDirectionPicks.remove(player.uniqueId)
        }
        if (root == BuilderRootCommand.ASSIST) {
            checkNotNull(placementAssist).command(player, args.drop(1))
            return
        }
        if (root !in setOf(BuilderRootCommand.PASTE, BuilderRootCommand.CONFIRM, BuilderRootCommand.CANCEL,
                BuilderRootCommand.STATUS, BuilderRootCommand.HELP)) pastePreview.clear(player.uniqueId)
        if (root != BuilderRootCommand.DISPLAY && entityEditor?.hasSession(player) == true) {
            entityEditor?.command(player, listOf("exit"))
        }
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
            BuilderRootCommand.FILL -> {
                val material = materialArgument(player, args.getOrNull(1))
                startPlanning(player) { current -> fillController.plan(player, material, current) }
            }
            BuilderRootCommand.REPLACE -> {
                val request = replaceRequest(args)
                startPlanning(player, request.confirmed) { current -> replaceController.plan(player, request.source, request.target, current) }
            }
            BuilderRootCommand.DISCONNECT -> {
                val confirmed = when (args.getOrNull(1)?.lowercase(Locale.ROOT)) {
                    null -> false
                    "confirm" -> true
                    else -> throw BuilderUserFailure("errors.material")
                }
                startPlanning(player, confirmed) { current -> fenceConnectionController.planDisconnect(player, current) }
            }
            BuilderRootCommand.COPY -> {
                requestPlanning(player, { current -> clipboardController.copy(player, current) }) { copied -> send(
                    player,
                    "clipboard.saved-cursor",
                    mapOf(
                        "count" to messages.literal(copied.blocks.size),
                        "skipped" to messages.literal(copied.skippedUnsafeBlocks),
                    ),
                ) }
            }
            BuilderRootCommand.MOVE -> prepareMove(player)
            BuilderRootCommand.BOOK -> when (args.getOrNull(1)?.lowercase(Locale.ROOT)) {
                "cancel" -> {
                    val loading = bookRequests.invalidate(player.uniqueId)
                    books.cancelMint(player, loading)
                    bookPreviewPresentation.clearPlayer(player.uniqueId)
                }
                "menu" -> openBookPreviewMenu(player)
                else -> books.handleCommand(player, args.drop(1))
            }
            BuilderRootCommand.PASTE -> {
                val rotation = when (args.getOrNull(1)?.lowercase(Locale.ROOT)) {
                    null -> 0
                    "rotate", "right" -> 90
                    "left" -> -90
                    else -> throw BuilderUserFailure("errors.material")
                }
                ensureFeaturePermission(player, BuilderFeature.PASTE)
                if (operationLocks.isPlayerLocked(player.uniqueId)) throw BuilderUserFailure("errors.busy")
                if (rotation == 0 || !pastePreview.rotate(player, rotation)) {
                    if (rotation != 0) clipboardController.rotate(player.uniqueId, rotation)
                    bookRequests.invalidate(player.uniqueId)
                    discardPendingPlan(player.uniqueId)
                    placementAssist?.clearPlayer(player.uniqueId)
                    dismissSelectionControls(player.uniqueId)
                    pastePreview.begin(player)
                }
            }
            BuilderRootCommand.DECONSTRUCT -> startPlanning(player) { current -> deconstructionController.plan(player, current) }
            BuilderRootCommand.CROWN -> crown.handle(player, args.drop(1))
            BuilderRootCommand.DISPLAY -> {
                if (!BuilderPermissionPolicy.canEditDisplays(player::hasPermission)) throw BuilderUserFailure("errors.no-permission")
                checkNotNull(entityEditor).command(player, args.drop(1))
            }
            BuilderRootCommand.CONFIRM -> when (args.getOrNull(1)?.lowercase(Locale.ROOT)) {
                null -> confirm(player)
                "buy" -> confirm(player, buyMissing = true)
                else -> messages.renderLines("help", locale(player)).forEach { sendPlayerMessage(player, it) }
            }
            BuilderRootCommand.CANCEL -> cancelPlan(player)
            BuilderRootCommand.UNDO -> prepareUndo(player)
            BuilderRootCommand.STATUS -> showStatus(player)
            BuilderRootCommand.PROJECTS -> error("Projects command is handled before operational context checks")
            BuilderRootCommand.ASSIST -> error("Assistant preferences are handled before operational context checks")
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
        if (player.uniqueId in constructionStarts) throw BuilderUserFailure("errors.busy", diagnostic = BuilderActionRejection("construction_start_pending"))
        if (recovering || constructionRecovering || recoveryBlocked || books.health().recoveryBlocked || playerRecoveries.contains(player.uniqueId)) {
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
        BuilderRootCommand.MOVE -> BuilderPermissionPolicy.canMove(player::hasPermission)
        BuilderRootCommand.PASTE -> BuilderPermissionPolicy.canUse(BuilderFeature.PASTE, player::hasPermission)
        BuilderRootCommand.DECONSTRUCT -> BuilderPermissionPolicy.canUse(BuilderFeature.DECONSTRUCT, player::hasPermission)
        BuilderRootCommand.CROWN -> BuilderPermissionPolicy.canUse(BuilderFeature.CROWN, player::hasPermission)
        BuilderRootCommand.DISPLAY -> BuilderPermissionPolicy.canEditDisplays(player::hasPermission)
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
        val points = selections.points(player.uniqueId, player.world.uid)
        if ((if (first) points.first else points.second) == position) return
        materialScans.remove(player.uniqueId)
        selectionPanelPages.remove(player.uniqueId)
        crownSelectionDialog?.close(player.uniqueId)
        materialPicker?.close(player.uniqueId)
        previews.plan(player.uniqueId)?.takeIf { it.kind != BuilderPlanKind.BUILD_BOOK }
            ?.let { discardPendingPlan(player.uniqueId) }
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

    private data class BookSourceCell(val position: BuilderBlockPos, val blockData: String)

    private fun planBuildBook(
        player: Player, site: ConstructionSite, book: ItemStack, isCurrent: () -> Boolean,
    ): CompletableFuture<BuilderBookPlannedProject> {
        if (!player.hasPermission("arcbuild.book.use")) throw BuilderUserFailure("errors.no-permission")
        val data = site.bookData
        val systemDefinition = if (data.playerCreated) {
            if (data.draft) throw BuilderUserFailure("book.unactivated")
            if (data.deliveryPending) throw BuilderUserFailure("book.delivery-pending")
            if (!data.available) throw BuilderUserFailure("book.invalid")
            books.verifySchematic(data, site.building)
            null
        } else systemBuildBookResolver(data) ?: throw BuilderUserFailure("book.invalid")
        if (BuilderBookAuctionTokenCodec.read(book) != null) throw BuilderUserFailure("book.auction-locked")
        if (!BuildBookCodec.matches(book, data)) throw BuilderUserFailure("book.missing")
        if (site.building.volume > config.maxScanVolume) throw BuilderUserFailure("errors.selection-too-large")
        val snapshot = site.snapshot()
        val view = snapshot.view
        val world = site.world
        val anchor = site.adjustedCenter.let { BuilderBlockPos(world.uid, it.blockX, it.blockY, it.blockZ) }
        val maximumChanges = config.maxConstructionChanges
        val playerName = player.name
        val lootTableKey = systemDefinition?.containerLootTableKey?.also { requiredLootTable(it) }
        val usesInventory = BuilderGameModePolicy.usesInventory(player.gameMode)
        val suppliedTypes: MutableSet<String>? = if (usesInventory && !data.playerCreated && systemDefinition?.materialsIncluded != true) null else mutableSetOf()
        val suppliedRequirements = if (usesInventory && data.playerCreated) data.playerMaterials else emptyList()
        val template = newPlan(
            player, BuilderPlanKind.BUILD_BOOK, emptyList(), emptyList(), emptyList(),
            bookBlueprintId = data.blueprintId, bookInstanceId = data.instanceId,
            bookInstanceGeneration = data.instanceGeneration, bookBuildingId = data.buildingId.takeIf { data.registered },
            bookSchematicSha256 = data.schematicSha256.takeIf { data.registered }, validate = false,
        )
        val exactBook = book.clone().also { it.amount = 1 }
        fun current(): Boolean = isCurrent() && BuildingManager.pending(player.uniqueId) === site && !site.transforming &&
            site.snapshot() == snapshot
        // Enumerating all cells (including ignored markers) is pure and can be large.
        return planningTasks.compute {
            buildList {
                for (y in view.minimum.y..view.maximum.y) for (x in view.minimum.x..view.maximum.x) for (z in view.minimum.z..view.maximum.z) {
                    val source = view.blockAt(x, y, z)
                    if (!source.isBuilderIgnored) add(BookSourceCell(anchor.copy(x = anchor.x + x, y = anchor.y + y, z = anchor.z + z), source.blockData))
                }
            }
        }.thenCompose { source ->
            planningTasks.onMain {
                requireCurrentBuilderPlanning(::current)
                val cells = ArrayList<BuilderBookCapturedCell>(source.size)
                val prototypes = BuilderItemCodec.PrototypeCache()
                var bookCost: BuilderItemAmount? = null
                var index = 0
                var materialIndex = 0
                planningTasks.capture(::current) {
                    if (bookCost == null) {
                        bookCost = prototypes.capture(exactBook)
                        source.isEmpty() && suppliedRequirements.isEmpty()
                    } else if (materialIndex < suppliedRequirements.size) {
                        val requirement = suppliedRequirements[materialIndex++]
                        require(requirement.material.isItem) { "Build-book player material is not available as an item" }
                        checkNotNull(suppliedTypes).add(requirement.material.key.toString())
                        source.isEmpty() && materialIndex >= suppliedRequirements.size
                    } else if (index >= source.size) true else {
                        val cell = source[index++]
                        val position = cell.position
                        // Reject before retrieving a native block: unloaded chunks must never be pulled in by a scan.
                        if (!world.isChunkLoaded(position.x shr 4, position.z shr 4)) throw BuilderUserFailure("errors.chunk-unloaded")
                        val target = block(world, position)
                        val after = Bukkit.createBlockData(cell.blockData)
                        val placement = planBuildBookBlock(player, target, after, lootTableKey != null, systemDefinition != null)
                        val captured = (placement as? BuilderBookPlacementResult.Change)?.let {
                            BuilderBookCapturedChange(
                                it.block,
                                it.placementItem?.takeIf { item -> suppliedTypes == null || item.type.key.toString() in suppliedTypes }?.let(prototypes::capture),
                                it.refund?.let(prototypes::capture),
                                lootTableKey.takeIf { safety.isSafeSystemLootContainer(after) },
                                systemDefinition != null && safety.isSafeSystemFurniture(after),
                            )
                        }
                        cells += BuilderBookMultiBlockPolicy.capture(BuilderBookPlannedCell(position, after, placement), captured)
                        index >= source.size
                    }
                }.thenCompose {
                    planningTasks.compute {
                        BuilderBookPlanCalculation.assemble(cells, checkNotNull(bookCost), suppliedTypes, template, playerName, data.title, anchor, maximumChanges)
                    }
                }
            }.thenCompose { it }
        }.thenCompose { result -> planningTasks.onMain {
            requireCurrentBuilderPlanning(::current)
            result
        } }
    }

    internal fun planBuildBookBlock(
        player: Player,
        block: Block,
        after: org.bukkit.block.data.BlockData,
        allowSystemLootContainer: Boolean = false,
        allowSystemFurniture: Boolean = false,
    ): BuilderBookPlacementResult {
        val safeSystemContainer = allowSystemLootContainer && safety.isSafeSystemLootContainer(after)
        if (!after.material.isAir && !safety.isSafePlacement(after) && !safeSystemContainer &&
            !(allowSystemFurniture && safety.isSafeSystemFurniture(after))) {
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
            if (allowSystemFurniture && after.material.name.startsWith("POTTED_")) ItemStack(Material.FLOWER_POT)
            else BuilderPlacementCost.itemOrNull(after),
            refund,
        )
    }

    /** Opens the owner preview menu for MockBukkit, whose world does not expose Interaction entities. */
    internal fun openBookPreviewMenuForTest(player: Player) {
        val site = checkNotNull(BuildingManager.pending(player.uniqueId)) {
            "Build-book preview is missing"
        }
        bookPreviewPresentation.openPlacementForOwner(player, site)
    }

    private fun openBookPreviewMenu(player: Player) {
        books.ensureAvailable(player)
        if (!bookPreviewPresentation.openCurrentPlacement(player, BuildingManager.pending(player.uniqueId))) {
            throw BuilderUserFailure("book.preview-required")
        }
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
        return explicitMaterialArgument(raw)
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
        BuilderMaterialArguments.parse(raw)
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
        validate: Boolean = true,
        moveDeconstruction: BuilderMoveDeconstruction? = null,
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
            moveDeconstruction = moveDeconstruction,
            createdAtMillis = now,
            expiresAtMillis = now + config.planTtl.toMillis(),
        ).let { if (validate) it.validated(config.maxChangesFor(kind)) else it }
    }

    private fun newPlanAsync(
        player: Player, kind: BuilderPlanKind, changes: List<BuilderBlockChange>,
        costs: List<BuilderItemAmount>, rewards: List<BuilderItemAmount>,
        toolFingerprint: String? = null, toolDamage: Int = 0, skippedUnsafeBlocks: Int = 0,
        moveDeconstruction: BuilderMoveDeconstruction? = null,
    ): CompletableFuture<BuilderPlan> {
        val maximum = config.maxChangesFor(kind)
        val draft = newPlan(player, kind, changes, costs, rewards, toolFingerprint, toolDamage,
            skippedUnsafeBlocks = skippedUnsafeBlocks, validate = false, moveDeconstruction = moveDeconstruction)
        return planningTasks.compute { draft.validated(maximum) }
    }

    private fun <T> requestPlanning(
        player: Player,
        request: (isCurrent: () -> Boolean) -> CompletableFuture<T>,
        failed: (Throwable?) -> Unit = { failure -> reportPlanningFailure(player, failure) },
        ready: (T) -> Unit,
    ) {
        val diagnostic = beginAction(player, "plan_prepare")
        val world = player.world.uid
        val mode = player.gameMode
        val slot = player.inventory.heldItemSlot
        val expectedItem = player.inventory.itemInMainHand.clone()
        bookRequests.run(player, null, request = { ticketCurrent ->
            fun current() = ticketCurrent() && player.world.uid == world && player.gameMode == mode &&
                player.inventory.heldItemSlot == slot && player.inventory.itemInMainHand == expectedItem
            request(::current).thenCompose { value -> planningTasks.onMain {
                requireCurrentBuilderPlanning(::current)
                value
            } }
        }, failed = { failure -> diagnostic.failed(failure); failed(failure) }, ready = { value ->
            if (value is BuilderPlan) diagnostic.operation(value.id)
            runBuilderAction(player, diagnostic = diagnostic) { ready(value) }
        })
    }

    private fun startPlanning(
        player: Player,
        confirmImmediately: Boolean = false,
        request: (isCurrent: () -> Boolean) -> CompletableFuture<BuilderPlan>,
    ) = requestPlanning(player, request, ready = { plan ->
        if (confirmImmediately) confirmImmediately(player, plan) else preparePlan(player, plan)
    })

    private fun prepareLockedPaste(player: Player, placement: BuilderPastePlacement, current: () -> Boolean) {
        previews.discard(player.uniqueId)
        shop.clear(player.uniqueId)
        requestPlanning(player,
            request = { requestCurrent -> clipboardController.planPaste(player, placement) { current() && requestCurrent() } },
            failed = { failure ->
                if (current()) pastePreview.clear(player.uniqueId)
                reportPlanningFailure(player, failure)
            },
            ready = { plan ->
                requireCurrentBuilderPlanning(current)
                try {
                    preparePlan(player, plan)
                    pastePreview.prepared(player.uniqueId, placement, plan.id)
                    send(player, "clipboard.fixed-actions")
                } catch (failure: Throwable) {
                    pastePreview.clear(player.uniqueId)
                    throw failure
                }
            },
        )
    }

    private fun clearLockedPastePlan(playerId: UUID, planId: UUID?) {
        if (planId != null && previews.plan(playerId)?.id == planId) discardPendingPlan(playerId)
    }

    private fun reportPlanningFailure(player: Player, raw: Throwable?) {
        var failure = raw ?: return
        while (failure is CompletionException && failure.cause != null) failure = checkNotNull(failure.cause)
        when (failure) {
            is CancellationException -> Unit
            is BuilderUserFailure -> send(player, failure.path, failure.values)
            else -> {
                send(player, "errors.plan-failed")
            }
        }
    }

    private fun preparePlan(player: Player, plan: BuilderPlan, announce: Boolean = true) {
        preflightPlan(player, plan)
        moveSelections.remove(player.uniqueId)
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
        if (operationLocks.isPlayerLocked(player.uniqueId)) throw BuilderUserFailure("errors.busy", diagnostic = BuilderActionRejection("player_lock"))
        val used = hourlyUsage(player.uniqueId, System.currentTimeMillis())
        if (plan.kind.usesHourlyLimit() && used + plan.changes.size > hourlyLimit(player)) {
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
        player: Player, site: ConstructionSite, located: BuilderLocatedBook,
        announce: Boolean = true, complete: (Boolean) -> Unit,
    ) {
        val diagnostic = beginAction(player, "book_prepare", site = site)
        if (site.transforming) {
            diagnostic.outcome("rejected", "transform_pending")
            return complete(false)
        }
        ensureBuildBookAvailable(player)
        bookRequests.run(player, located,
            request = { current -> planBuildBook(player, site, located.item, current) },
            failed = { failure -> diagnostic.failed(failure); reportPlanningFailure(player, failure); complete(false) },
            ready = { planned ->
                diagnostic.operation(planned.plan.id)
                val succeeded = runBookPreviewAction(player, diagnostic = diagnostic) {
                    ensureBuildBookAvailable(player)
                    site.cancelSilently()
                    plannedConstructionProjects[planned.plan.id] = planned.project
                    try {
                        preparePlan(player, planned.plan, announce)
                        bookPreviewPresentation.retainConfirmation(site)
                    } catch (failure: Throwable) {
                        plannedConstructionProjects.remove(planned.plan.id)
                        throw failure
                    }
                    true
                } ?: false
                complete(succeeded)
            },
        )
    }

    private fun adjustBookPreview(
        player: Player,
        adjustment: ru.arc.autobuild.BuildBookPreviewAdjustment,
    ): ConstructionSite? = runBookPreviewAction(player, "book_preview_adjust_${when (adjustment) {
        is ru.arc.autobuild.BuildBookPreviewAdjustment.Move -> adjustment.direction.name.lowercase(Locale.ROOT)
        is ru.arc.autobuild.BuildBookPreviewAdjustment.Rotate -> "rotate_${adjustment.delta}"
        ru.arc.autobuild.BuildBookPreviewAdjustment.ToggleMirror -> "mirror"
        ru.arc.autobuild.BuildBookPreviewAdjustment.Reset -> "reset"
    }}") {
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
        val diagnostic = beginAction(player, "book_confirmation_prepare", site = site)
        if (site.transforming) {
            diagnostic.outcome("rejected", "transform_pending")
            return complete(null)
        }
        val located = runBookPreviewAction(player, diagnostic = diagnostic) { requireMatchingPreviewBook(player, site) }
            ?: return complete(null)
        val data = located.data
        if (data.draft) {
            try {
                books.preparePreviewActivation(player, located) { blueprint ->
                    diagnostic.outcome(if (blueprint == null) "rejected" else "quoted", if (blueprint == null) "activation_quote_unavailable" else "activation_quote_ready")
                    complete(blueprint?.toPreviewConfirmation())
                }
            } catch (failure: BuilderUserFailure) {
                diagnostic.failed(failure)
                send(player, failure.path, failure.values)
                complete(null)
            } catch (failure: Throwable) {
                diagnostic.failed(failure)
                send(player, "book.failed")
                complete(null)
            }
            return
        }
        requestBook(player, located, data, failed = { complete(null) }) { building ->
            if (BuildingManager.pending(player.uniqueId) !== site || site.transforming ||
                building.schematicSha256 != site.building.schematicSha256) {
                diagnostic.outcome("rejected", "book_source_changed")
                send(player, "book.source-changed")
                complete(null)
            } else {
                startPlayerBuildBook(player, site, located, announce = false) { success ->
                    complete(if (success) currentBookPreviewConfirmation(player) else null)
                }
            }
        }
    }

    private fun currentBookPreviewConfirmation(player: Player, worldControls: Boolean = false): BuilderBookPreviewConfirmation? {
        val plan = previews.plan(player.uniqueId)?.takeIf { it.kind == BuilderPlanKind.BUILD_BOOK }
        if (plan != null) {
            val project = plannedConstructionProjects[plan.id] ?: return null
            val now = System.currentTimeMillis()
            val bypass = player.hasPermission(BOOK_COOLDOWN_BYPASS_PERMISSION)
            val cached = worldBookCooldowns[player.uniqueId]?.takeIf {
                it.planId == plan.id && it.projectCount == constructionProjects.size && it.bypass == bypass
            }
            // Only the world display uses this deadline; actual confirmation always revalidates the records.
            val cooldown = if (worldControls && cached != null) {
                Duration.ofMillis((cached.untilMillis - now).coerceAtLeast(0L))
            } else bookApplicationCooldownRemaining(player, now).also { remaining ->
                worldBookCooldowns[player.uniqueId] = WorldBookCooldown(plan.id, constructionProjects.size, bypass,
                    now + remaining.toMillis())
            }
            return BuilderBookPreviewConfirmation(
                kind = BuilderBookPreviewConfirmationKind.CONSTRUCTION,
                blockCount = plan.changes.size,
                title = project.projectTitle ?: project.plan.bookBuildingId ?: "Постройка",
                cooldownRemaining = cooldown.let { remaining ->
                    if (worldControls && !remaining.isZero) Duration.ofMinutes((remaining.toMillis() + 59_999L) / 60_000L)
                    else remaining
                },
                requiredMaterials = if (worldControls) emptyList() else project.plan.costs.mapNotNull { item ->
                    val amount = item.amount - if (item.itemBase64 == project.bookCost.itemBase64 && item.materialKey == project.bookCost.materialKey) project.bookCost.amount else 0
                    item.copy(amount = amount).takeIf { amount > 0 }
                }.sortedBy(BuilderItemAmount::materialKey),
            )
        }
        val site = BuildingManager.pending(player.uniqueId) ?: return null
        return books.currentPreviewActivation(player, site.bookData)?.toPreviewConfirmation()
    }

    private fun confirmBookPreview(player: Player, complete: (Boolean) -> Unit) {
        val diagnostic = beginAction(player, "book_preview_confirm")
        fun rejected(reason: String) { diagnostic.outcome("rejected", reason); complete(false) }
        val current = currentBookPreviewConfirmation(player) ?: return rejected("confirmation_missing")
        if (!current.cooldownRemaining.isZero) return rejected("book_application_cooldown")
        if (current.kind == BuilderBookPreviewConfirmationKind.DRAFT_ACTIVATION) {
            val site = BuildingManager.pending(player.uniqueId) ?: return rejected("preview_missing")
            books.confirmPreviewActivation(player, site.bookData) { accepted ->
                diagnostic.outcome(if (accepted) "accepted" else "rejected", "activation_callback")
                complete(accepted)
            }
            return
        }
        runBookPreviewAction(player, diagnostic = diagnostic) {
            confirm(player, buildBook = true, complete = { accepted ->
                diagnostic.outcome(if (accepted) "submitted" else "rejected", "construction_confirmation_callback")
                complete(accepted)
            })
            true
        } ?: complete(false)
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
    ): ConstructionSite? = runBookPreviewAction(player, "book_preview_restore") {
        requireMatchingPreviewBook(player, snapshot.bookData)
        books.cancelPreviewActivation(player.uniqueId)
        discardPreparedBookPlan(player.uniqueId)
        BuildingManager.restorePreview(snapshot, System.currentTimeMillis())
            ?: throw BuilderUserFailure("errors.expired")
    }

    private fun requireMatchingPreviewBook(player: Player, site: ConstructionSite): BuilderLocatedBook =
        requireMatchingPreviewBook(player, site.bookData)

    private fun requireMatchingPreviewBook(player: Player, expected: BuildBookData): BuilderLocatedBook =
        BuilderBookInventoryLocator.find(player, expected) { _, item, raw -> item to raw } ?: throw BuilderUserFailure("book.missing")

    private fun <T> runBookPreviewAction(
        player: Player,
        actionName: String = "book_preview",
        site: ConstructionSite? = BuildingManager.pending(player.uniqueId),
        diagnostic: BuilderActionDiagnostics.Attempt = beginAction(player, actionName, site = site),
        action: () -> T,
    ): T? = try {
        action().also { if (!diagnostic.outcomeRecorded) diagnostic.outcome("accepted", "handler_returned") }
    } catch (failure: BuilderUserFailure) {
        diagnostic.failed(failure)
        send(player, failure.path, failure.values)
        null
    } catch (failure: IllegalArgumentException) {
        diagnostic.failed(failure)
        send(player, "book.failed")
        null
    } catch (failure: Throwable) {
        diagnostic.failed(failure)
        send(player, "book.failed")
        null
    }

    private fun confirm(
        player: Player, buyMissing: Boolean = false, buildBook: Boolean = false,
        complete: (Boolean) -> Unit = {},
    ) {
        val pending = previews[player.uniqueId] ?: throw BuilderUserFailure("errors.expired")
        if (pending.plan.kind == BuilderPlanKind.BUILD_BOOK && !buildBook) throw BuilderUserFailure("book.preview-required")
        if (buildBook) ensureBuildBookAvailable(player) else ensureAvailable(player)
        if (player.gameMode != pending.gameMode) {
            discardPendingPlan(player.uniqueId)
            throw BuilderUserFailure("errors.game-mode-changed")
        }
        val construction = plannedConstructionProjects[pending.plan.id]
        requestPlanning(player,
            request = { current -> revalidatePlanAsync(player, pending.plan, construction) {
                current() && previews[player.uniqueId] === pending && pending.plan.expiresAtMillis > System.currentTimeMillis()
            } },
            failed = { failure -> reportPlanningFailure(player, failure); complete(false) },
            ready = {
                val success = runBookPreviewAction(player) {
                    if (previews[player.uniqueId] !== pending) throw BuilderUserFailure("errors.expired")
                    confirmReady(player, buyMissing, buildBook)
                    true
                } ?: false
                complete(success)
            },
        )
    }

    private fun confirmReady(player: Player, buyMissing: Boolean, buildBook: Boolean) {
        val pending = previews[player.uniqueId] ?: throw BuilderUserFailure("errors.expired")
        val plan = pending.plan
        if (plan.kind == BuilderPlanKind.BUILD_BOOK && !buildBook) {
            throw BuilderUserFailure("book.preview-required")
        }
        if (buildBook || plan.kind == BuilderPlanKind.BUILD_BOOK) ensureBuildBookAvailable(player) else ensureAvailable(player)
        if (plan.kind == BuilderPlanKind.MOVE) ensureMovePermission(player)
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
            throw BuilderUserFailure("errors.inventory", diagnostic = BuilderActionRejection("inventory_exchange_unavailable"))
        }
        if (construction != null) {
            if (player.uniqueId in constructionStarts || constructionProjects.values.any { it.playerId == player.uniqueId && !it.terminal }) {
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
        if (updatesPhysics(plan)) physicsUpdater.validate()
        if (!operationLocks.tryLock(plan)) throw BuilderUserFailure("errors.busy")
        previews.remove(player.uniqueId, pending)
        pastePreview.clear(player.uniqueId)
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
        )
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
                        BuilderOnboardingProgressBridge.started(durableApplying.plan)
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
        val diagnostic = beginAction(player, "construction_start", site = null, project = planned)
        try {
            ensureBookApplicationCooldown(player)
            plannedConstructionProjects.remove(planned.projectId)
            if (player.gameMode != plannedMode) {
                books.releasePlanReservation(planned.plan)
                throw BuilderUserFailure("errors.game-mode-changed")
            }
            if (player.uniqueId in constructionStarts || constructionProjects.values.any { it.playerId == player.uniqueId && !it.terminal }) {
                books.releasePlanReservation(planned.plan)
                throw BuilderUserFailure("errors.busy")
            }
            constructionStarts.add(player.uniqueId)
            val location = player.location
            val x = location.x
            val z = location.z
            planningTasks.compute {
                planned.copy(sitePanelFace = BuilderConstructionSiteDisplayLayout.nearestFace(planned, x, z))
            }.thenCompose { prepared -> planningTasks.onMain {
                lockConstructionAsync(prepared) { !closed && player.isOnline && player.gameMode == plannedMode }
                    .thenApply { locked -> prepared to locked }
            }.thenCompose { it } }.whenCompleteSync(taskScope) { result, failure ->
                if (failure != null || result?.second != true) {
                    constructionStarts.remove(player.uniqueId)
                    books.releasePlanReservation(planned.plan)
                    unlockConstruction(planned)
                    val rejected = failure ?: BuilderUserFailure("errors.busy", diagnostic = BuilderActionRejection("region_lock_unavailable"))
                    diagnostic.failed(rejected)
                    reportPlanningFailure(player, rejected)
                } else {
                    runBookPreviewAction(player, diagnostic = diagnostic) { activateLockedConstruction(player, result.first, diagnostic) }
                }
            }
        } catch (failure: Throwable) {
            diagnostic.failed(failure)
            throw failure
        }
    }

    private fun activateLockedConstruction(
        player: Player,
        prepared: BuilderConstructionProjectRecord,
        diagnostic: BuilderActionDiagnostics.Attempt,
    ) {
        // Capture the receipt on the server thread, but perform validation, encoding and
        // durable I/O on the storage executor. No inventory mutation precedes the commit.
        val activation = try {
            val mutation = checkNotNull(
                constructionResources.preparePlayerDebit(player.uniqueId, prepared, prepared.bookCost),
            ) { "Builder construction book disappeared before durable preparation" }
            val now = System.currentTimeMillis()
            prepared.copy(
                pendingResourceMutation = mutation,
                applicationStartedAtMillis = now,
                updatedAtMillis = now,
            )
        } catch (failure: Throwable) {
            constructionStarts.remove(player.uniqueId)
            books.releasePlanReservation(prepared.plan)
            unlockConstruction(prepared)
            diagnostic.failed(failure)
            throw BuilderUserFailure("book.failed")
        }
        constructionWrites.add(activation.projectId)
        writeAsync(
            action = { constructionStore.commit(activation) },
            callback = { durable, failure ->
                constructionStarts.remove(player.uniqueId)
                constructionWrites.remove(activation.projectId)
                if (failure != null || durable == null) {
                    if (failure is BuilderConstructionProjectUnknownOutcomeException) {
                        recoveryBlocked = true
                        diagnostic.failed(failure)
                        diagnostic.outcome("recovery_required", "initial_commit_unknown")
                        if (player.isOnline) send(player, "construction.recovery-required")
                    } else {
                        books.releasePlanReservation(activation.plan)
                        unlockConstruction(activation)
                        diagnostic.failed(failure ?: IllegalStateException("Construction initial commit returned no record"))
                        if (player.isOnline) send(player, "book.failed")
                    }
                    return@writeAsync
                }
                // The ordinary construction cycle owns receipt reconciliation and the
                // asynchronous PREPARED -> ACTIVE commit, including disconnect recovery.
                constructionProjects[durable.projectId] = durable
                constructionSiteDisplays.upsert(durable)
                diagnostic.outcome("durable_prepared", "initial_commit_confirmed", "state" to durable.state)
            },
        )
        if (!diagnostic.outcomeRecorded) diagnostic.outcome("submitted", "initial_commit_pending")
    }

    private fun loadConstructionProjects() {
        writeAsync(action = {
            constructionStore.loadAll().map { loaded ->
                loaded to BuilderConstructionRecoveryPolicy.normalizeLoaded(loaded, System.currentTimeMillis())
            }
        }, callback = { records, loadFailure ->
            if (loadFailure != null || records == null) {
                constructionRecovering = false
                recoveryBlocked = true
                error("Builder construction project recovery failed", loadFailure ?: IllegalStateException("missing project records"))
                return@writeAsync
            }
            var index = 0
            var waiting = false
            var lockWaiting = false
            var durable: BuilderConstructionProjectRecord? = null
            var transitionFailure: Throwable? = null
            planningTasks.capture({ !closed }) {
                transitionFailure?.let { throw it }
                if (index >= records.size) true else {
                    val ready = durable
                    if (ready != null) {
                        if (!ready.terminal && ready.projectId !in constructionLocks) {
                            if (!lockWaiting) {
                                lockWaiting = true
                                lockConstructionAsync(ready) { !closed }.whenComplete { locked, failure ->
                                    transitionFailure = failure ?: if (locked != true) IllegalStateException("Construction recovery area is already locked") else null
                                }
                            }
                            return@capture false
                        }
                        installLoadedConstruction(ready)
                        index++
                        durable = null
                        waiting = false
                        lockWaiting = false
                    } else if (!waiting) {
                        val (loaded, normalized) = records[index]
                        val resumed = if (loaded.state == BuilderConstructionProjectState.RECOVERY_REQUIRED) {
                            BuilderConstructionRecoveryPolicy.resumeAppliedNoExchangeStep(normalized, System.currentTimeMillis(), constructionPortFor(normalized))
                        } else normalized
                        if (resumed == loaded) durable = loaded else {
                            waiting = true
                            writeAsync(action = { constructionStore.transition(loaded, resumed) }, callback = { committed, failure ->
                                durable = committed
                                transitionFailure = failure ?: if (committed == null) IllegalStateException("Missing construction recovery commit") else null
                            })
                        }
                    }
                    index >= records.size
                }
            }.whenCompleteSync(taskScope) { _, failure ->
                constructionRecovering = false
                if (failure != null) {
                    recoveryBlocked = true
                    error("Builder construction project recovery failed", failure)
                } else endStartupBarrierIfReady()
            }
        })
    }

    private fun installLoadedConstruction(record: BuilderConstructionProjectRecord) {
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

    private fun tickConstructionProjects() {
        if (closed || recovering || constructionRecovering || recoveryBlocked) return
        val schedulerTick = constructionSchedulerTick++
        val now = System.currentTimeMillis()
        plannedConstructionProjects.entries.removeIf { (_, project) -> project.plan.expiresAtMillis <= now }
        val candidates = constructionProjects.values.filter { record ->
            (
                (record.state == BuilderConstructionProjectState.PAUSED && constructionPauseRequests.cancellationPending(record.projectId)) ||
                    record.state == BuilderConstructionProjectState.PREPARED ||
                    record.state == BuilderConstructionProjectState.ACTIVE ||
                    record.state == BuilderConstructionProjectState.WAITING_MATERIALS ||
                    record.state == BuilderConstructionProjectState.INPUT_PREPARED ||
                    record.state == BuilderConstructionProjectState.WORLD_PREPARED ||
                    record.state == BuilderConstructionProjectState.OUTPUT_PENDING ||
                    record.state == BuilderConstructionProjectState.WAITING_OUTPUT_SPACE ||
                    record.state == BuilderConstructionProjectState.DELIVERING_OUTPUT ||
                    canRetryAppliedConstructionRecovery(record)
            ) && (
                record.projectId in constructionInstantRequests ||
                    BuilderConstructionCadencePolicy.shouldRun(
                        record,
                        schedulerTick,
                        config.constructionTickPeriod,
                    )
            )
        }
        candidates.forEach { expected ->
            if (!constructionWrites.add(expected.projectId)) return@forEach
            val maximumBlocks = config.constructionBlocksPerCycle
            var target: BuilderConstructionProjectRecord? = null
            var ordinary = false
            var leased = false
            val observedPort = constructionPortFor(expected)
            fun current() = !closed && constructionProjects[expected.projectId] === expected
            planningTasks.capture(::current) {
                leased = constructionPlayerLeases.acquire(expected.projectId, expected.playerId, expected.pendingResourceMutation)
                if (leased) {
                    if (expected.state == BuilderConstructionProjectState.RECOVERY_REQUIRED) {
                        target = BuilderConstructionRecoveryPolicy.resumeAppliedNoExchangeStep(expected, now, observedPort).takeUnless { it == expected }
                    } else {
                        target = constructionPauseRequests.pauseTarget(expected, now)
                            ?: constructionInstantRequests[expected.projectId]?.let { BuilderInstantConstruction.prepare(expected, it, now) }
                        ordinary = target == null
                    }
                }
                true
            }.thenCompose {
                if (leased && ordinary) BuilderConstructionProjectController.tickAsync(expected, now, observedPort, maximumBlocks, planningTasks, ::current)
                else CompletableFuture.completedFuture(target)
            }.whenCompleteSync(taskScope) { next, failure ->
                if (failure != null || next == null) {
                    constructionWrites.remove(expected.projectId)
                    constructionPlayerLeases.release(expected.projectId)
                    if (failure != null && failure !is CancellationException) error("Builder construction tick failed for ${expected.projectId}", failure)
                } else persistConstructionTransition(expected, next)
            }
        }
    }

    private fun persistConstructionTransition(expected: BuilderConstructionProjectRecord, target: BuilderConstructionProjectRecord) {
        writeAsync(
            action = { constructionStore.transition(expected, target) },
            callback = { durable, failure ->
                constructionWrites.remove(expected.projectId)
                if (failure != null || durable == null) {
                    if (target.state == BuilderConstructionProjectState.PAUSED || target.state == BuilderConstructionProjectState.CANCELLED) {
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
                if (expected.instantBuildRequestedBy == null && durable.instantBuildRequestedBy != null) {
                    info(debugLine.line("event" to "construction_instant_prepared", "operation" to durable.projectId,
                        "admin" to durable.instantBuildRequestedBy, "remaining" to durable.steps.size - durable.cursor))
                }
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
        val diagnosticState = when {
            previous.state == BuilderConstructionProjectState.PREPARED && current.state == BuilderConstructionProjectState.ACTIVE -> "active"
            current.state in setOf(
                BuilderConstructionProjectState.WAITING_MATERIALS, BuilderConstructionProjectState.WAITING_OUTPUT_SPACE,
                BuilderConstructionProjectState.CANCELLED, BuilderConstructionProjectState.RECOVERY_REQUIRED,
                BuilderConstructionProjectState.COMPLETED,
            ) -> current.state.name.lowercase(Locale.ROOT)
            else -> null
        }
        if (diagnosticState != null) {
            val position = current.steps.getOrNull(current.cursor)?.change?.position
                ?: current.siteAnchor ?: current.plan.changes.first().position
            actionDiagnostics.constructionTransition(constructionActionContext(current, "construction_state", position), diagnosticState, current.cursor)
        }
        if (current.terminal) actionDiagnostics.forgetConstruction(current.projectId)
        if (previous.state == BuilderConstructionProjectState.PREPARED && current.state == BuilderConstructionProjectState.ACTIVE) {
            BuilderOnboardingProgressBridge.started(current)
        }
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
            BuilderConstructionProjectState.CANCELLED -> send(player, "construction.cancelled")
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
                    repairCompletedConstructionConnections(durable)
                    BuilderOnboardingProgressBridge.completed(durable)
                    Bukkit.getPlayer(durable.playerId)?.takeIf(Player::isOnline)?.let { player ->
                        if (durable.instantBuildRequestedBy == null) emitCommittedOperation(durable.plan, player.gameMode)
                    }
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

    private fun repairCompletedConstructionConnections(record: BuilderConstructionProjectRecord) {
        // Connection shape changes happen only after the durable completion barrier;
        // they can never invalidate an unfinished step or trigger its rollback.
        val changes = record.plan.changes
        val worldId = changes.firstOrNull()?.position?.worldId ?: return
        planningTasks.compute {
            changes.map { change ->
                val pos = change.position
                ru.arc.paper.api.ArcSchematicBlock(ru.arc.paper.api.ArcSchematicPoint(pos.x, pos.y, pos.z), change.afterBlockData, false, false)
            }
        }.whenCompleteSync(taskScope) { blocks, calculationFailure ->
            if (calculationFailure != null || blocks == null) return@whenCompleteSync
            runCatching { schematics.library().repairConnections(worldId, blocks) }
                .onSuccess { operation -> operation.completion.whenComplete { _, failure ->
                    if (failure != null) warn("Builder completed connection repair failed: {}", failure.message)
                } }
                .onFailure { failure -> warn("Builder completed connection repair unavailable: {}", failure.message) }
        }
    }

    private fun beginMutation(player: Player, operation: BuilderActiveOperation) {
        revalidatePlanAsync(player, operation.record.plan, null) {
            !closed && !operation.cancelled && player.isOnline && player.gameMode == operation.gameMode &&
                operationLocks.operation(player.uniqueId) === operation
        }.whenCompleteSync(taskScope) { _, failure ->
            // Disconnect/cancel may have already acknowledged and finished an
            // untouched operation while this capture was waiting in the queue.
            if (closed || operationLocks.operation(player.uniqueId) !== operation) return@whenCompleteSync
            if (rollbackPending.contains(operation.record.operationId)) return@whenCompleteSync
            if (failure != null) rollback(player, operation, failure.message ?: "pre-apply validation failed")
            else beginValidatedMutation(player, operation)
        }
    }

    private fun beginValidatedMutation(player: Player, operation: BuilderActiveOperation) {
        if (closed || operationLocks.operation(player.uniqueId) !== operation) return
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
        if (operation.record.operationId in rollbackPending || operationLocks.operation(player.uniqueId) !== operation) return
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
                selectionUndoAvailable.remove(durable.playerId)
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

    private fun updatesPhysics(plan: BuilderPlan): Boolean =
        plan.kind != BuilderPlanKind.FENCE_DISCONNECT &&
            !(plan.kind == BuilderPlanKind.UNDO &&
                committedRecords[plan.sourceRecordId]?.plan?.kind == BuilderPlanKind.FENCE_DISCONNECT)

    private fun finalizeCommittedOperation(
        player: Player,
        operation: BuilderActiveOperation,
        physicsCursor: Int = 0,
        physicsFailures: Int = 0,
    ) {
        val durable = operation.record
        check(durable.phase == BuilderJournalPhase.COMMITTED)
        var failures = physicsFailures
        if (updatesPhysics(durable.plan)) {
            // Release world locks for vanilla neighbour events, but retain the player lease until finished.
            if (physicsCursor == 0) operationLocks.unlock(durable.plan)
            val end = minOf(physicsCursor + config.blocksPerTick, durable.plan.changes.size)
            for (index in physicsCursor until end) {
                val change = durable.plan.changes[index]
                try {
                    val target = block(requireWorld(change.position.worldId), change.position)
                    // Earlier physics or another player may already have changed this block. Never rewrite it.
                    if (target.blockData.asString == change.afterBlockData && !PaperBlockProtection.isProtected(target)) {
                        physicsUpdater.update(target, Bukkit.createBlockData(change.beforeBlockData))
                    }
                } catch (failure: Throwable) {
                    if (failures++ == 0) error("Builder-tools committed physics failed for ${durable.operationId}", failure)
                }
            }
            if (end < durable.plan.changes.size) {
                taskScope.runLater(1L) { finalizeCommittedOperation(player, operation, end, failures) }
                return
            }
        }
        // Physics is outside the rollback-safe transaction; drops must never be duplicated by restoration.
        val movedSelection = moveSelections[player.uniqueId]?.takeIf { it.planId == durable.plan.id }
        finishOperation(operation)
        movedSelection?.let { moved ->
            if (player.isOnline && selectionOrNull(player) == moved.before) {
                selections.set(player.uniqueId, moved.after.first, first = true)
                selections.set(player.uniqueId, moved.after.second, first = false)
                displayRenderer.selection(player, selections.points(player.uniqueId, player.world.uid), moved.after)
            }
        }
        if (failures > 0) send(player, "operation.physics-failed")
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
        ExternalArcBuilderTelemetryBridge.completed(durable.plan.playerId, durable.operationId.toString())
        BuilderOnboardingProgressBridge.completed(durable.plan)
        emitCommittedOperation(durable.plan, player.gameMode)
        durable.plan.sourceRecordId?.let { markSourceUndone(it) }
        cleanupOldRecords()
    }

    private fun emitCommittedOperation(plan: BuilderPlan, gameMode: GameMode) {
        val event = BuilderOperationCommittedEventFactory.create(plan, gameMode) ?: return
        runCatching { Bukkit.getPluginManager().callEvent(event) }
            .onFailure { failure -> warn("Builder operation listener failed for {}", plan.id, failure) }
    }

    private fun rollback(player: Player, operation: BuilderActiveOperation, reason: String) {
        if (closed || operationLocks.operation(player.uniqueId) !== operation) return
        if (!rollbackPending.add(operation.record.operationId)) return
        operation.cancelled = true
        var index = operation.appliedChanges - 1
        var chunkLoad: CompletableFuture<org.bukkit.Chunk>? = null
        planningTasks.capture({ !closed && operationLocks.operation(player.uniqueId) === operation }) {
            if (index < 0) true else {
                val change = operation.record.plan.changes[index]
                val world = requireWorld(change.position.worldId)
                if (!world.isChunkLoaded(change.position.x shr 4, change.position.z shr 4)) {
                    val load = chunkLoad ?: world.getChunkAtAsync(change.position.x shr 4, change.position.z shr 4, false).also { chunkLoad = it }
                    if (!load.isDone) return@capture false
                    load.join() // already complete; never wait for chunk I/O on the server thread
                    check(world.isChunkLoaded(change.position.x shr 4, change.position.z shr 4)) { "Rollback chunk is unavailable" }
                }
                chunkLoad = null
                val target = block(world, change.position)
                val current = target.blockData.asString
                check(current == change.afterBlockData || current == change.beforeBlockData) { "rollback encountered an externally changed block" }
                if (current == change.afterBlockData) {
                    check(!PaperBlockProtection.isProtected(target)) { "Builder rollback target is protected" }
                    val after = Bukkit.createBlockData(change.afterBlockData)
                    val before = Bukkit.createBlockData(change.beforeBlockData)
                    target.setBlockData(before, false)
                    coreProtect?.logChange(operation.record.playerName, target.location, after, before)
                }
                index--
                index < 0
            }
        }.whenCompleteSync(taskScope) { _, failure ->
            rollbackPending.remove(operation.record.operationId)
            var recoveryAccepted = false
            try {
                if (failure != null) throw failure
                recoveryAccepted = playerRecoveries.add(operation.record, inventoryRestored = !operation.inventoryMutated)
            } catch (rollbackFailure: Throwable) {
                recoveryBlocked = true
                runCatching { playerRecoveries.hold(operation.record) }.onFailure { holdFailure ->
                    error("Builder-tools could not retain recovery hold for ${operation.record.operationId}", holdFailure)
                }
                error("Builder-tools rollback requires operator attention for ${operation.record.operationId}", rollbackFailure)
            }
            finishOperation(operation)
            if (recoveryAccepted && player.isOnline) send(player, "operation.rolled-back")
            warn(debugLine.line("event" to "rolled_back", "operation" to operation.record.operationId, "player" to operation.record.playerId, "reason" to reason))
        }
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

    private fun finishOperation(operation: BuilderActiveOperation) {
        moveSelections[operation.record.playerId]?.takeIf { it.planId == operation.record.plan.id }?.let {
            moveSelections.remove(operation.record.playerId, it)
        }
        operationLocks.finish(operation)
    }

    private fun revalidatePlanAsync(
        player: Player, plan: BuilderPlan, construction: BuilderConstructionProjectRecord?, isCurrent: () -> Boolean,
    ): CompletableFuture<Unit> {
        val maximum = config.maxChangesFor(plan.kind)
        if (plan.kind == BuilderPlanKind.MOVE) ensureMovePermission(player)
        if (plan.moveDeconstruction != null) ensureFeaturePermission(player, BuilderFeature.DECONSTRUCT)
        if (BuilderDeconstructionToolPolicy.requiresBypass(player.gameMode, plan) &&
            !BuilderPermissionPolicy.canDeconstructWithoutTool(player::hasPermission)) throw BuilderUserFailure("errors.no-permission")
        return planningTasks.compute {
            plan.validated(maximum)
            construction?.steps.orEmpty().asSequence().filter { it.lootTableKey != null || it.systemFurniture }
                .mapTo(mutableSetOf()) { it.change.position }
        }.thenCompose { systemBookExceptionPositions -> planningTasks.onMain {
            var index = 0
            planningTasks.capture(isCurrent) {
                if (index >= plan.changes.size) true else {
                    val change = plan.changes[index++]
                    val world = requireWorld(change.position.worldId)
                    if (!world.isChunkLoaded(change.position.x shr 4, change.position.z shr 4)) throw BuilderUserFailure("errors.chunk-unloaded")
                    val block = block(world, change.position)
                    val after = Bukkit.createBlockData(change.afterBlockData)
                    ensureMutable(player, block, after.material.takeUnless(Material::isAir))
                    if (block.blockData.asString != change.beforeBlockData) throw BuilderUserFailure("errors.expired")
                    if (!block.type.isAir && !safety.isSafeExisting(block) && !safety.isReplaceable(block)) {
                        throw BuilderUserFailure("errors.expired")
                    }
                    val safeSystemContainer =
                        change.position in systemBookExceptionPositions &&
                            (safety.isSafeSystemLootContainer(after) || safety.isSafeSystemFurniture(after))
                    if (!after.material.isAir && !safety.isSafePlacement(after) && !safeSystemContainer && after.material !in safety.replaceable) {
                        throw BuilderUserFailure("errors.plan-failed")
                    }
                    index >= plan.changes.size
                }
            }
        }.thenCompose { it } }
    }

    private fun cancelPlan(player: Player) {
        val pickingMoveDirection = moveDirectionPicks.remove(player.uniqueId) != null
        val movingPaste = pastePreview.clear(player.uniqueId)
        val loading = bookRequests.invalidate(player.uniqueId)
        val quoting = books.invalidateSchematicRequests(player.uniqueId)
        books.cancelPreviewActivation(player.uniqueId)
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
            bookPreviewPresentation.clearPlayer(player.uniqueId)
            send(player, "book.preview-cancelled")
        } else if (loading || quoting || movingPaste || pickingMoveDirection) {
            bookPreviewPresentation.clearPlayer(player.uniqueId)
            send(player, "plan.cancelled")
        } else {
            throw BuilderUserFailure("errors.expired")
        }
    }

    private fun clearSelection(player: Player) {
        if (operationLocks.isPlayerLocked(player.uniqueId)) throw BuilderUserFailure("errors.busy")
        bookRequests.invalidate(player.uniqueId)
        books.cancelPreviewActivation(player.uniqueId)
        bookPreviewPresentation.clearPlayer(player.uniqueId)
        discardPendingPlan(player.uniqueId)
        selections.clear(player.uniqueId)
        selectionActionPanel?.clear(player.uniqueId)
        crownSelectionDialog?.close(player.uniqueId)
        materialPicker?.close(player.uniqueId)
        materialScans.remove(player.uniqueId)
        selectionPanelPages.remove(player.uniqueId)
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
        val maximum = config.maxChanges
        val template = newPlan(player, BuilderPlanKind.UNDO, emptyList(), emptyList(), emptyList(), sourceRecordId = source.operationId, validate = false)
        startPlanning(player) { _ -> planningTasks.compute {
            val changes = source.plan.changes.asReversed().map { BuilderBlockChange(it.position, it.afterBlockData, it.beforeBlockData) }
            val exchange = BuilderUndoRules.exchangeFor(source.plan)
            template.copy(changes = changes, costs = exchange.costs, rewards = exchange.rewards).validated(maximum)
        } }
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
            pastePreview.capturesInput(player.uniqueId) -> send(player, "clipboard.moving")
            player.uniqueId in moveDirectionPicks -> send(player, "move.choose-direction")
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

    private fun openConstructionMenu(player: Player, project: BuilderConstructionProjectRecord, source: String) =
        runBuilderAction(player, "construction_menu_$source", beginAction(player, "construction_menu_$source", site = null, project = project)) {
            constructionMenus.open(player, project)
        }

    private fun requestConstructionCancelled(player: Player, projectId: UUID): Boolean {
        val diagnostic = beginAction(player, "construction_cancel", site = null, project = constructionProjects[projectId], requestedOperationId = projectId)
        fun rejected(reason: String): Boolean { diagnostic.outcome("rejected", reason); return false }
        val project = constructionProjects[projectId] ?: return rejected("project_missing")
        if (!canControlConstruction(player, project)) return rejected("not_owner_or_admin")
        if (recoveryBlocked) return rejected("recovery_blocked")
        if (projectId in constructionInstantRequests) return rejected("instant_request_pending")
        if (!constructionPauseRequests.requestCancel(project, player.uniqueId)) return rejected("cancellation_unavailable")
        send(player, "construction.cancel-requested")
        diagnostic.outcome("accepted", "cancel_requested")
        return true
    }

    private fun requestConstructionPaused(player: Player, projectId: UUID, pause: Boolean): Boolean {
        val diagnostic = beginAction(player, if (pause) "construction_pause" else "construction_resume", site = null,
            project = constructionProjects[projectId], requestedOperationId = projectId)
        fun rejected(reason: String): Boolean { diagnostic.outcome("rejected", reason); return false }
        val expected = constructionProjects[projectId]?.takeUnless(BuilderConstructionProjectRecord::terminal)
            ?: return rejected("project_missing_or_terminal")
        if (!canControlConstruction(player, expected)) return rejected("not_owner_or_admin")
        if (expected.instantBuildRequestedBy != null) return rejected("instant_build_active")
        if (pause) return constructionPauseRequests.request(expected, player.uniqueId).also {
            diagnostic.outcome(if (it) "accepted" else "rejected", if (it) "pause_requested" else "pause_unavailable")
        }
        if (expected.state != BuilderConstructionProjectState.PAUSED) return rejected("project_not_paused")
        if (!constructionWrites.add(projectId)) return rejected("project_write_pending")
        val target = runCatching { expected.resumed(System.currentTimeMillis()) }.getOrNull()
        if (target == null) {
            constructionWrites.remove(projectId)
            return rejected("resume_transition_unavailable")
        }
        writeAsync(
            action = { constructionStore.transition(expected, target) },
            callback = { durable, failure ->
                constructionWrites.remove(projectId)
                if (failure != null || durable == null) {
                    constructionInstantRequests.remove(projectId)
                    diagnostic.failed(failure ?: IllegalStateException("Construction resume commit returned no record"))
                    constructionMenus.refreshProject(projectId)
                    return@writeAsync
                }
                constructionProjects[projectId] = durable
                constructionSiteDisplays.upsert(durable)
                constructionMenus.refreshProject(projectId)
                diagnostic.outcome("durable", "resume_transition_committed", "cursor" to durable.cursor)
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
        if (!diagnostic.outcomeRecorded) diagnostic.outcome("accepted", "resume_requested")
        return true
    }

    private fun requestInstantConstruction(player: Player, projectId: UUID): Boolean {
        val diagnostic = beginAction(player, "construction_instant", site = null, project = constructionProjects[projectId], requestedOperationId = projectId)
        fun rejected(reason: String): Boolean { diagnostic.outcome("rejected", reason); return false }
        if (!player.hasPermission(CONSTRUCTION_ADMIN_PERMISSION)) return rejected("admin_permission_required")
        val project = constructionProjects[projectId]?.takeUnless(BuilderConstructionProjectRecord::terminal)
            ?: return rejected("project_missing_or_terminal")
        if (project.state == BuilderConstructionProjectState.RECOVERY_REQUIRED) return rejected("recovery_required")
        constructionPauseRequests.complete(projectId)
        constructionInstantRequests[projectId] = player.uniqueId
        val accepted = if (project.state == BuilderConstructionProjectState.PAUSED) {
            requestConstructionPaused(player, projectId, pause = false)
        } else {
            taskScope.runSync(::tickConstructionProjects) != null
        }
        if (!accepted) constructionInstantRequests -= projectId
        if (accepted) send(player, "construction.instant-started")
        diagnostic.outcome(if (accepted) "accepted" else "rejected", if (accepted) "instant_requested" else "scheduling_unavailable")
        return accepted
    }

    private fun continueInstantConstruction(project: BuilderConstructionProjectRecord) {
        if (project.projectId !in constructionInstantRequests && project.instantBuildRequestedBy == null) return
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
        if (!block.world.worldBorder.isInside(block.location)) throw blockRejection("errors.protection", "world_border", block)
    }

    private fun ensureInRangeAndLoaded(player: Player, block: Block) {
        if (!block.world.isChunkLoaded(block.x shr 4, block.z shr 4)) throw blockRejection("errors.chunk-unloaded", "chunk_unloaded", block)
        if (player.world.uid != block.world.uid || player.location.distanceSquared(block.location.clone().add(0.5, 0.5, 0.5)) > capturedMaximumRange * capturedMaximumRange) {
            throw blockRejection("errors.too-far", "out_of_range", block)
        }
    }

    private fun ensureProtected(player: Player, block: Block, placing: Material? = null) {
        if (PaperBlockProtection.isProtected(block)) throw blockRejection("errors.protection", "block_protection", block)
        val lands = HookRegistry.landsHook
        if (lands != null && !lands.canModify(player, block, placing)) {
            throw blockRejection("errors.protection", "lands_build_permission", block)
        }
        if (lands == null && capturedRequireLands) {
            throw blockRejection("errors.protection", "lands_unavailable", block)
        }
    }

    private fun blockRejection(path: String, reason: String, block: Block) = BuilderUserFailure(
        path, diagnostic = BuilderActionRejection(reason, BuilderActionTarget.at(block.location)),
    )

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
        .sumOf { it.plan.changes.size }

    private fun lockConstructionAsync(record: BuilderConstructionProjectRecord, isCurrent: () -> Boolean): CompletableFuture<Boolean> {
        if (record.projectId in constructionLocks) return CompletableFuture.completedFuture(true)
        return operationLocks.acquireAsync(record.plan, planningTasks, isCurrent).thenApply { lease ->
            if (lease == null) false else {
                constructionLocks[record.projectId] = lease
                true
            }
        }
    }

    private fun unlockConstruction(record: BuilderConstructionProjectRecord) {
        val lease = constructionLocks.remove(record.projectId) ?: return
        if (!closed) operationLocks.releaseAsync(lease, planningTasks)
    }

    private fun hourlyLimit(player: Player): Int =
        BuilderPermissionPolicy.hourlyChanges(player::hasPermission, config.baseHourlyChanges)

    private fun bookApplicationCooldownRemaining(player: Player, nowMillis: Long = System.currentTimeMillis()): java.time.Duration =
        BuilderBookApplicationCooldown.remaining(
            playerId = player.uniqueId,
            records = constructionProjects.values,
            nowMillis = nowMillis,
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
        messages.render(if (kind == BuilderPlanKind.PASTE) "kinds.paste-cursor"
            else "kinds.${kind.name.lowercase(Locale.ROOT)}", locale(player))

    private fun constructionStateLabel(player: Player, state: BuilderConstructionProjectState): Component =
        messages.render("construction.states.${state.name.lowercase(Locale.ROOT)}", locale(player))

    private fun discardPendingPlan(playerId: UUID) {
        worldBookCooldowns.remove(playerId)
        moveSelections.remove(playerId)
        pastePreview.clear(playerId)
        previews.plan(playerId)?.let { plan -> plannedConstructionProjects.remove(plan.id) }
        previews.discard(playerId)
        shop.clear(playerId)
        crown.clearAnchor(playerId)
    }

    private fun loadRecoveryState() {
        writeAsync(action = {
            val records = journal.loadAll().map { it.value }
            val interrupted = records.filter { it.phase == BuilderJournalPhase.PREPARED || it.phase == BuilderJournalPhase.APPLYING }
            require(interrupted.groupingBy { it.playerId }.eachCount().values.all { it == 1 }) {
                "Multiple interrupted builder-tools operations exist for one player"
            }
            records to interrupted
        }, callback = { loaded, failure ->
            if (failure != null || loaded == null) {
                recoveryBlocked = true
                recovering = false
                error("Builder-tools journal recovery failed", failure ?: IllegalStateException("missing journal result"))
                return@writeAsync
            }
            val (records, interrupted) = loaded
            // The startup barrier already covers the unknown journal while it loads.
            // Isolate every interrupted player before the first budgeted acquisition,
            // including players whose record will be processed after another record.
            interrupted.forEach { operationLocks.lockRecovery(it.playerId) }
            val acquisitions = interrupted.map { record ->
                operationLocks.acquireAsync(record.plan, planningTasks) { !closed }.thenApply { lease ->
                    startupRecoveryLocks[record.playerId] = checkNotNull(lease) { "Builder recovery world lease overlaps another operation" }
                }
            }
            CompletableFuture.allOf(*acquisitions.toTypedArray()).whenCompleteSync(taskScope) { _, lockFailure ->
                if (lockFailure != null) {
                    holdStartupRecovery(records, lockFailure)
                } else {
                    // Every recoverable position now has an exact lease. Native
                    // event isolation continues while unrelated gameplay resumes.
                    journalRecoveryLocksReady = true
                    endStartupBarrierIfReady()
                    recoverLoadedRecords(records)
                }
            }
        })
    }

    private fun recoverLoadedRecords(records: List<BuilderJournalRecord>) {
        val acknowledge = mutableListOf<BuilderJournalRecord>()
        var recordIndex = 0
        var changeIndex = -1
        var chunkLoad: CompletableFuture<org.bukkit.Chunk>? = null
        planningTasks.capture({ !closed }) {
            if (recordIndex >= records.size) true else {
                val record = records[recordIndex]
                if (record.phase == BuilderJournalPhase.COMMITTED || record.phase == BuilderJournalPhase.UNDONE) {
                    committedRecords[record.operationId] = record
                    record.plan.sourceRecordId?.let { consumedUndoSources += it }
                    recordIndex++
                } else {
                    if (changeIndex < 0) changeIndex = record.plan.changes.lastIndex
                    val change = record.plan.changes[changeIndex]
                    val world = Bukkit.getWorld(change.position.worldId) ?: throw IllegalStateException("Builder-tools recovery world is unavailable")
                    if (!world.isChunkLoaded(change.position.x shr 4, change.position.z shr 4)) {
                        val load = chunkLoad ?: world.getChunkAtAsync(change.position.x shr 4, change.position.z shr 4, false).also { chunkLoad = it }
                        if (!load.isDone) return@capture false
                        load.join()
                        check(world.isChunkLoaded(change.position.x shr 4, change.position.z shr 4)) { "Recovery chunk is unavailable" }
                    }
                    chunkLoad = null
                    recoverBlock(record, change, world)
                    changeIndex--
                    if (changeIndex < 0) {
                        // Inventory restoration is admitted only after the entire
                        // world preimage has been restored under the block lease.
                        if (record.phase == BuilderJournalPhase.PREPARED) acknowledge += record else playerRecoveries.add(record)
                        recordIndex++
                    }
                }
                recordIndex >= records.size
            }
        }.whenCompleteSync(taskScope) { _, recoveryFailure ->
            if (recoveryFailure != null) {
                holdStartupRecovery(records, recoveryFailure)
            } else if (acknowledge.isEmpty()) finishRecovery(records.size) else writeAsync(action = {
                acknowledge.forEach { record -> check(journal.acknowledgeExactly(record)) { "Builder recovery acknowledgement failed" } }
            }, callback = { _, acknowledgeFailure ->
                if (acknowledgeFailure != null) {
                    recoveryBlocked = true
                    recovering = false
                    error("Builder-tools recovery acknowledgement failed", acknowledgeFailure)
                } else {
                    acknowledge.forEach { record ->
                        operationLocks.unlockRecovery(record.playerId)
                        releaseStartupRecoveryLock(record.playerId)
                    }
                    finishRecovery(records.size)
                }
            })
        }
    }

    private fun holdStartupRecovery(records: List<BuilderJournalRecord>, failure: Throwable) {
        recoveryBlocked = true
        recovering = false
        records.asSequence().filter { it.phase == BuilderJournalPhase.APPLYING }
            // A prior record may already have been restored and exactly
            // acknowledged while a later world's recovery was still waiting.
            .filter { !journalRecoveryLocksReady || it.playerId in startupRecoveryLocks }
            .filterNot { playerRecoveries.contains(it.playerId) }.forEach { record ->
                runCatching { playerRecoveries.hold(record) }.onFailure { error("Builder startup recovery hold failed", it) }
            }
        error("Builder-tools recovery stopped on ambiguous state", failure)
    }

    private fun releaseStartupRecoveryLock(playerId: UUID) {
        val lease = startupRecoveryLocks.remove(playerId) ?: return
        if (!closed) operationLocks.releaseAsync(lease, planningTasks)
    }

    private fun endStartupBarrierIfReady() {
        if (!startupBarrierReleased && journalRecoveryLocksReady && draftRecoveryLocksReady && !constructionRecovering && !recoveryBlocked) {
            startupBarrierReleased = true
            operationLocks.endRecoveryBarrier()
            // Draft startup installed exact holds while the global guard was in
            // place. Admit recovery now, before another native event can run.
            Bukkit.getOnlinePlayers().forEach(books::onPlayerAvailable)
        }
    }

    private fun finishRecovery(recordCount: Int) {
        recovering = false
        cleanupOldRecords()
        books.onGeneralRecoveryFinished()
        info(debugLine.line("event" to "recovery_ready", "records" to recordCount, "pending_players" to playerRecoveries.pendingCount))
    }

    private fun recoverBlock(record: BuilderJournalRecord, change: BuilderBlockChange, world: World) {
        val target = block(world, change.position)
        when (BuilderRecoveryRules.action(record.phase, target.blockData.asString, change.beforeBlockData, change.afterBlockData)) {
            BuilderRecoveryAction.KEEP_BEFORE -> Unit
            BuilderRecoveryAction.RESTORE_BEFORE -> {
                check(!PaperBlockProtection.isProtected(target)) { "Builder recovery target is protected" }
                val after = Bukkit.createBlockData(change.afterBlockData)
                val before = Bukkit.createBlockData(change.beforeBlockData)
                target.setBlockData(before, false)
                coreProtect?.logChange(record.playerName, target.location, after, before)
            }
        }
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
            storageExecutor.execute {
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

    private fun locale(player: Player): String = BuilderLocalePolicy.localeTag(player)

    private fun java.time.Duration.toTicks(): Long = (toMillis() / 50L).coerceAtLeast(1L)

    private fun filterPrefix(values: List<String>, raw: String?): List<String> {
        val prefix = raw.orEmpty().lowercase(Locale.ROOT)
        return values.filter { it.startsWith(prefix, ignoreCase = true) }.take(100)
            .sortedBy { !BuilderMaterialArguments.isRussianName(it) }
    }

    private val safeMaterials by lazy { Material.entries.filter(safety::isSafeMaterial) }
    private val placementMaterials by lazy { safeMaterials.filter { safety.isSafePlacement(it.createBlockData()) } }
    private val safeMaterialNames by lazy { BuilderMaterialArguments.names(safeMaterials) }
    private val replaceMaterials by lazy {
        listOf(Material.AIR) + safeMaterials.filter { material ->
            val data = material.createBlockData()
            safety.isSafePlacement(data) && !BuilderReplaceController.isCoupledMultiBlock(data)
        }
    }
    private val replaceMaterialNames by lazy { BuilderMaterialArguments.names(replaceMaterials) }
    private val replaceSourceMaterialNames by lazy {
        (BuilderMaterialArguments.names(listOf(Material.AIR, Material.CAVE_AIR, Material.VOID_AIR)) + replaceMaterialNames).distinct()
    }

    private data class ReplaceRequest(
        val source: Material,
        val target: Material,
        val confirmed: Boolean,
    )

    // WorldEdit/FAWE's NORMAL listener skips useItemInHand == DENY. Claim Shift
    // before it runs; an already-denied item stays denied, including in the air.
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    fun onWorldEditSelectionInteract(event: PlayerInteractEvent) {
        if (worldEditSelection == null || event.hand != EquipmentSlot.HAND ||
            event.useItemInHand() == org.bukkit.event.Event.Result.DENY || !event.player.isSneaking
        ) return
        val first = when (event.action) {
            Action.LEFT_CLICK_BLOCK, Action.LEFT_CLICK_AIR -> true
            Action.RIGHT_CLICK_BLOCK, Action.RIGHT_CLICK_AIR -> false
            else -> return
        }
        val player = event.player
        if (!refreshWorldEditSelection(player, initializeSession = true)) return
        event.isCancelled = true
        val target = refreshSelectionTarget(player) ?: return
        worldEditSelection.select(player, target, first)
        refreshWorldEditSelection(player)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onInteract(event: PlayerInteractEvent) {
        val player = event.player
        if (operationLocks.isPlayerLocked(player.uniqueId)) {
            event.isCancelled = true
            val held = event.item
            if (held != null && event.hand == EquipmentSlot.HAND) {
                val data = BuildBookCodec.read(held)
                if (data != null || isSelector(held)) {
                    beginAction(player, if (data != null) "book_place" else "selection_click", event.clickedBlock?.location, data = data)
                        .outcome("rejected", "player_lock")
                }
            }
            return
        }
        moveDirectionPicks[player.uniqueId]?.let { pick ->
            if (event.action in listOf(
                    Action.LEFT_CLICK_AIR, Action.LEFT_CLICK_BLOCK, Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK,
                )) {
                event.isCancelled = true
                if (!currentMoveDirectionPick(player, pick)) {
                    moveDirectionPicks.remove(player.uniqueId, pick)
                } else if (event.hand == EquipmentSlot.HAND && Bukkit.getCurrentTick() != pick.startedTick) {
                    moveDirectionPicks.remove(player.uniqueId, pick)
                    runBuilderAction(player, "move_direction") { prepareMove(player) }
                }
                return
            }
        }
        if (event.action in listOf(
                org.bukkit.event.block.Action.LEFT_CLICK_AIR, org.bukkit.event.block.Action.LEFT_CLICK_BLOCK,
                org.bukkit.event.block.Action.RIGHT_CLICK_AIR, org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK,
            ) && pastePreview.capturesInput(player.uniqueId)) {
            event.isCancelled = true
            runBuilderAction(player) { pastePreview.click(player, event.hand == EquipmentSlot.HAND) }
            return
        }
        if (entityEditor?.isEditing(player) == true) return
        if (event.hand == EquipmentSlot.HAND && event.action in listOf(
                Action.LEFT_CLICK_AIR, Action.LEFT_CLICK_BLOCK, Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK,
            ) && bookPreviewPresentation.intercept(player)) {
            event.isCancelled = true
            return
        }
        val item = event.item ?: return
        if (event.hand == EquipmentSlot.HAND) {
            BuildBookCodec.read(item)?.let { data ->
                if (event.action == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
                    event.isCancelled = true
                    handleBookInteraction(player, item, data, event.clickedBlock?.location)
                    return
                }
            }
        }
        if (!isSelector(item)) return
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action in listOf(
                org.bukkit.event.block.Action.LEFT_CLICK_AIR, org.bukkit.event.block.Action.LEFT_CLICK_BLOCK,
                org.bukkit.event.block.Action.RIGHT_CLICK_AIR, org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK,
            )) {
            if (selectionActionPanel?.intercept(player) == true) {
                event.isCancelled = true
                return
            }
        }
        val first = when (event.action) {
            org.bukkit.event.block.Action.LEFT_CLICK_BLOCK, org.bukkit.event.block.Action.LEFT_CLICK_AIR -> true
            org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK, org.bukkit.event.block.Action.RIGHT_CLICK_AIR -> false
            else -> return
        }
        event.isCancelled = true
        runBuilderAction(player, diagnostic = beginAction(player, if (first) "selection_first" else "selection_second", event.clickedBlock?.location, site = null)) {
            ensureAvailable(player)
            val target = refreshSelectionTarget(player) ?: return@runBuilderAction
            setPosition(player, Location(player.world, target.x.toDouble(), target.y.toDouble(), target.z.toDouble()), first)
        }
    }

    private fun requestBook(
        player: Player,
        source: BuilderLocatedBook,
        data: BuildBookData = source.data,
        failed: () -> Unit = {},
        diagnostic: BuilderActionDiagnostics.Attempt = beginAction(player, "book_schematic", data = data),
        ready: (Building) -> Unit,
    ) {
        val definition = if (data.playerCreated) null else systemBuildBookResolver(data)
        if (!data.playerCreated && definition == null) {
            diagnostic.failed(BuilderUserFailure("book.invalid"))
            send(player, "book.invalid"); failed(); return
        }
        bookRequests.run(player, source,
            request = { loadBuilding(definition?.buildingId ?: data.buildingId, data.schematicSha256.takeIf { data.playerCreated }) },
            failed = { failure ->
                diagnostic.failed(failure)
                if (player.isOnline) send(player, if (failure == null) "book.source-changed" else "book.invalid")
                failed()
            },
            ready = { building ->
                try {
                    ensureAvailable(player)
                    if (building.volume > config.maxScanVolume) throw BuilderUserFailure("errors.selection-too-large")
                    if (data.playerCreated) books.verifySchematic(data, building)
                    diagnostic.outcome("loaded", "schematic_loaded")
                    ready(building)
                } catch (failure: BuilderUserFailure) {
                    diagnostic.failed(failure)
                    send(player, failure.path, failure.values)
                    failed()
                } catch (failure: Throwable) {
                    diagnostic.failed(failure)
                    send(player, "book.failed")
                    failed()
                }
            },
        )
    }

    private fun handleBookInteraction(
        player: Player,
        item: ItemStack,
        data: BuildBookData,
        clickedLocation: Location?,
        loaded: Building? = null,
        requestedSneaking: Boolean = player.isSneaking,
        requestedRotation: Int = BuildingManager.rotationFromYaw(player.yaw),
        diagnostic: BuilderActionDiagnostics.Attempt = beginAction(player, "book_place", clickedLocation, data = data),
    ) {
        try {
            ensureAvailable(player)
            if (loaded == null) {
                val anchor = clickedLocation?.clone()
                val source = BuilderLocatedBook(player.inventory.heldItemSlot, item.clone(), data)
                requestBook(player, source, diagnostic = diagnostic) { building ->
                    handleBookInteraction(player, source.item, data, anchor, building, requestedSneaking, requestedRotation, diagnostic)
                }
                return
            }
            val (effectiveItem, effectiveData) = canonicalBook(player, item, data, loaded)
            if (requestedSneaking) {
                discardPreparedBookPlan(player.uniqueId)
                if (effectiveData.selectableBuildingIds.isNotEmpty()) {
                    openBookSelector(player, effectiveItem, effectiveData)
                } else {
                    BuildBookEditorGui.open(player, ::prepareBookCopy)
                }
                diagnostic.outcome("opened", "book_selector_or_editor")
                return
            }
            val current = BuildingManager.pending(player.uniqueId)
            val preparedPlan = preparedBookPlan(player.uniqueId, effectiveItem)
            val decision = BuilderBookInteractionPolicy.decide(
                action = BuilderBookClick.BLOCK,
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
                        building = loaded,
                        rotation = requestedRotation,
                    )
                    BuilderOnboardingProgressBridge.opened(player.uniqueId, opened.centerBlock)
                    diagnostic.outcome("opened", "book_preview", "opened_preview" to "${opened.player.uniqueId}:${opened.expiresAtMillis}")
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
                BuilderBookInteractionDecision.REQUIRE_PREVIEW -> throw BuilderUserFailure("book.preview-required")
            }
        } catch (failure: BuilderUserFailure) {
            diagnostic.failed(failure)
            send(player, failure.path, failure.values)
        } catch (failure: Throwable) {
            diagnostic.failed(failure)
            send(player, "book.failed")
        }
    }

    private fun openBookSelector(player: Player, item: ItemStack, data: BuildBookData) {
        val expected = item.clone()
        val slot = player.inventory.heldItemSlot
        val choices = data.selectableBuildingIds.map { id ->
            (systemBuildBookResolver(data.copy(buildingId = id)) ?: throw BuilderUserFailure("book.invalid"))
                .copy(buildingId = id)
        }
        fun verifyHeldBook() {
            ensureAvailable(player)
            if (operationLocks.isPlayerLocked(player.uniqueId)) throw BuilderUserFailure("errors.busy")
            if (player.inventory.heldItemSlot != slot || player.inventory.getItem(slot) != expected) {
                throw BuilderUserFailure("book.missing")
            }
        }
        bookPreviewPresentation.openSelector(player, data, choices, onSelect = { selected ->
            try {
                verifyHeldBook()
                val selectorInventory = player.openInventory.topInventory
                val candidate = data.copy(buildingId = selected, transform = ru.arc.autobuild.BuildBookTransform())
                val definition = systemBuildBookResolver(candidate) ?: throw BuilderUserFailure("book.invalid")
                requestBook(player, BuilderLocatedBook(slot, expected, data), candidate) { selectedBuilding ->
                verifyHeldBook()
                if (player.openInventory.topInventory !== selectorInventory) throw BuilderUserFailure("book.source-changed")
                val updated = BuildBookCodec.update(expected, candidate.copy(
                    title = definition.title,
                    systemMaterialsIncluded = definition.materialsIncluded,
                    blockCount = selectedBuilding.blockCount,
                    playerMaterials = emptyList(),
                ).validated())
                discardPreparedBookPlan(player.uniqueId)
                BuildingManager.closePreview(player.uniqueId)
                bookPreviewPresentation.clearPlayer(player.uniqueId)
                player.inventory.setItem(slot, updated)
                player.closeInventory()
                send(player, "book.selector.saved", mapOf("name" to messages.literal(definition.title)))
                }
            } catch (failure: BuilderUserFailure) {
                player.closeInventory()
                send(player, failure.path, failure.values)
            }
        }, onEdit = {
            try {
                verifyHeldBook()
                BuildBookEditorGui.open(player, ::prepareBookCopy)
            } catch (failure: BuilderUserFailure) {
                player.closeInventory()
                send(player, failure.path, failure.values)
            }
        })
    }

    private fun canonicalBook(player: Player, item: ItemStack, data: BuildBookData, building: Building): Pair<ItemStack, BuildBookData> {
        return canonicalBook(player, player.inventory.heldItemSlot, item, data, building)
    }

    private fun canonicalBook(
        player: Player,
        slot: Int,
        item: ItemStack,
        data: BuildBookData,
        building: Building,
    ): Pair<ItemStack, BuildBookData> {
        val canonical = if (data.playerCreated) {
            if (data.deliveryPending) throw BuilderUserFailure("book.delivery-pending")
            books.verifySchematic(data, building)
            data
        } else {
            val definition = systemBuildBookResolver(data) ?: throw BuilderUserFailure("book.invalid")
            data.copy(
                title = definition.title,
                systemMaterialsIncluded = definition.materialsIncluded,
                blockCount = building.blockCount,
                playerMaterials = emptyList(),
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
        moveDirectionPicks.remove(event.player.uniqueId)
        pastePreview.clear(event.player.uniqueId)
        selectionTargets.remove(event.player.uniqueId)
        selectionTargetFailures.remove(event.player.uniqueId)
        worldEditSelectionPoints.remove(event.player.uniqueId)
        bookRequests.invalidate(event.player.uniqueId)
        selectionActionPanel?.clear(event.player.uniqueId)
        crownSelectionDialog?.clearPlayer(event.player.uniqueId)
        materialPicker?.close(event.player.uniqueId)
        materialScans.remove(event.player.uniqueId)
        panelPlanSummaries.remove(event.player.uniqueId)
        selectionUndoAvailable.remove(event.player.uniqueId)
        selectionPanelPages.remove(event.player.uniqueId)
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
            closed || recovering || constructionRecovering || recoveryBlocked || playerRecoveries.pendingCount > 0 ->
                BuilderToolsReloadBlocker.STARTING_OR_RECOVERING
            operationLocks.activeOperationCount > 0 -> BuilderToolsReloadBlocker.ACTIVE_OPERATION
            operationLocks.bookLockedPlayerCount > 0 || operationLocks.recoveryLockedPlayerCount > 0 ||
                bookHealth.deliveryWaitingForSpace > 0 || bookHealth.reservationReleaseBacklog > 0 ||
                bookHealth.recoveryBlocked -> BuilderToolsReloadBlocker.DURABLE_BOOK_FLOW
            bookRequests.size > 0 || books.pendingSchematicRequests > 0 || previews.pendingCount > 0 || plannedConstructionProjects.isNotEmpty() ->
                BuilderToolsReloadBlocker.PENDING_PREVIEW
            selections.pendingCount > 0 || clipboardController.pendingCount > 0 || BuildingManager.pendingCount > 0 ->
                BuilderToolsReloadBlocker.VOLATILE_PLAYER_STATE
            builderConstructionReloadBlocked(constructionWrites.size + constructionStarts.size, constructionCompletions.size) ->
                BuilderToolsReloadBlocker.ACTIVE_CONSTRUCTION
            else -> null
        }
    }

    internal fun updatePlaqueAppearance(settings: BuilderToolsConfig) {
        check(!closed)
        val appearance = settings.plaqueAppearance()
        val previews = bookPreviewPresentation.updatePlaqueAppearance(appearance)
        val sites = constructionSiteDisplays.updatePlaqueAppearance(appearance)
        info("Builder plaque appearance applied: previews={} construction-sites={}", previews, sites)
    }

    private fun publishRuntimeHealth() {
        val bookHealth = books.health()
        runtimeHealth.set(
            BuilderToolsRuntimeHealth.contribution(
                BuilderToolsRuntimeHealthInputs(
                    closed = closed,
                    recovering = recovering || constructionRecovering,
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
        var failure: Throwable? = null
        fun attempt(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        attempt(::publishRuntimeHealth)
        attempt { HandlerList.unregisterAll(this) }
        attempt { pastePreview.close() }
        attempt { selectionActionPanel?.close() }
        attempt { crownSelectionDialog?.close() }
        attempt { entityEditor?.close() }
        attempt { placementAssist?.close() }
        attempt { materialPicker?.close() }
        materialScans.clear()
        worldBookCooldowns.clear()
        panelPlanSummaries.clear()
        selectionUndoAvailable.clear()
        selectionPanelPages.clear()
        moveDirectionPicks.clear()
        moveSelections.clear()
        actionDiagnostics.clear()
        attempt { crown.close() }
        attempt { previews.close() }
        operationLocks.operations().forEach { operation -> attempt {
            if (operation.interruptionDeferred) {
                finishOperation(operation)
            } else {
                if (operation.record.phase == BuilderJournalPhase.APPLYING && (operation.appliedChanges > 0 || operation.inventoryMutated)) {
                    // Keep the durable preimage for sliced startup recovery; never refund
                    // while shutdown leaves world changes without a completed rollback.
                    playerRecoveries.hold(operation.record)
                    warn("Builder operation {} retained for startup recovery during shutdown", operation.record.operationId)
                }
                finishOperation(operation)
            }
        } }
        attempt { playerRecoveries.close() }
        attempt { books.close() }
        attempt { constructionSiteDisplays.close() }
        attempt { constructionProjectsMenu.close() }
        attempt { constructionMenus.close() }
        constructionPauseRequests.clear()
        constructionInstantRequests.clear()
        bookHoldHints.clear()
        val visualDrains = listOf(
            runCatching { entityEditor?.closeAsync() ?: CompletableFuture.completedFuture(null) }
                .getOrElse { CompletableFuture.failedFuture(it) },
            runCatching { bookPreviewPresentation.closeAsync() }.getOrElse { CompletableFuture.failedFuture(it) },
            runCatching { displayRenderer.closeAsync() }.getOrElse { CompletableFuture.failedFuture(it) },
        )
        attempt { bookRequests.close() }
        attempt { planningTasks.close() }
        attempt { schematics.close() }
        attempt { taskScope.close() }
        attempt { shop.close() }
        attempt { constructionPlayerLeases.close() }
        attempt { operationLocks.close() }
        selections.clear()
        selectionTargets.clear()
        selectionTargetFailures.clear()
        worldEditSelectionPoints.clear()
        attempt { clipboardController.close() }
        attempt { BuildingManager.clearPreviews() }
        attempt { BuildingManager.installPreviewBridge(null) }
        runCatching { closeStorageExecutor(visualDrains, books::closeRegistry) }
            .exceptionOrNull()?.let { closeFailure ->
                if (failure == null) failure = closeFailure else failure!!.addSuppressed(closeFailure)
            }
        failure?.let { throw it }
    }

    /** Maintenance may block for I/O, but never for a continuation scheduled on Paper. */
    private fun closeStorageExecutor(
        nativeDrains: List<CompletableFuture<*>>,
        closeRegistry: () -> Unit,
    ) {
        if (storageSealed) return
        storageSealed = true
        // Native scopes are already sealed. This FIFO contains only accepted disk work
        // and SQL cleanup, whose completions do not require another server tick.
        val storageDrain = storageExecutor.closeAsync(closeRegistry)
        val actualCleanup = nativeDrains + storageDrain
        CompletableFuture.allOf(*actualCleanup.toTypedArray()).whenComplete { _, failure ->
            if (failure == null) closeCompletion.complete(Unit) else closeCompletion.completeExceptionally(failure)
        }
        storageDrain.join()
    }

    private companion object {
        const val CONSTRUCTION_ADMIN_PERMISSION = "arcbuild.admin.construction"
        const val BOOK_COOLDOWN_BYPASS_PERMISSION = "arcbuild.book.cooldown.bypass"
    }
}
