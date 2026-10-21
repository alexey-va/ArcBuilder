package ru.arc.buildertools

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent
import io.papermc.paper.event.packet.PlayerChunkLoadEvent
import io.papermc.paper.event.packet.PlayerChunkUnloadEvent
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import ru.arc.autobuild.BuildBookPreviewBridge
import ru.arc.autobuild.BuildBookItems
import ru.arc.autobuild.ConstructionSite
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.ScheduledTask
import ru.arc.text.LocalizedMiniMessage
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.UUID
import java.util.PriorityQueue

/**
 * Preview lifecycle boundary owned by the runtime.
 *
 * The Paper implementation sends client-only BlockDisplay packets. Native
 * clickable book panels have their own presentation owner.
 */
internal interface BuilderDisplayRenderer : BuildBookPreviewBridge, AutoCloseable {
    fun selection(player: Player, points: BuilderSelectionPoints, selection: BuilderSelection?)
    fun selectionTarget(player: Player, position: BuilderBlockPos?) = Unit
    fun clearSelectionTarget(playerId: UUID) = Unit
    fun suppressSelectionGlow(playerId: UUID, suppressed: Boolean) = Unit
    fun clearSelection(playerId: UUID)
    fun plan(player: Player, plan: BuilderPlan)
    fun clearPlan(playerId: UUID)
    fun clearPlayer(playerId: UUID)
    fun syncBookViewer(viewer: Player) = Unit
    fun closeAsync(): CompletableFuture<Void> { close(); return CompletableFuture.completedFuture(null) }
}

/** Keeps every small preview and turns large previews into a deterministic player-centred window. */
internal object BuilderPreviewWindow {
    private data class Candidate<T>(val index: Int, val value: T, val distanceSquared: Double)

    fun <T> nearest(
        values: List<T>,
        limit: Int,
        distanceSquared: (T) -> Double,
    ): List<T> {
        require(limit > 0)
        if (values.size <= limit) return values
        val order = compareBy<Candidate<T>>(Candidate<T>::distanceSquared).thenBy(Candidate<T>::index)
        val nearest = PriorityQueue(limit, order.reversed())
        values.forEachIndexed { index, value ->
            val distance = distanceSquared(value)
            // Equal distances keep the earlier schematic index already in the heap.
            if (nearest.size < limit || distance < nearest.peek().distanceSquared) {
                if (nearest.size == limit) nearest.remove()
                nearest.add(Candidate(index, value, distance))
            }
        }
        return nearest.sortedBy(Candidate<T>::index).map(Candidate<T>::value)
    }
}

internal data class BuilderPlanPreviewGeometry(
    val points: BuilderPreviewPointSnapshot,
    val bounds: List<BuilderDisplayEdge>,
) {
    companion object {
        fun create(changes: List<BuilderBlockChange>): BuilderPlanPreviewGeometry {
            val positions = changes.map(BuilderBlockChange::position)
            return BuilderPlanPreviewGeometry(
                BuilderPreviewPointSnapshot.copyOf(positions.map { BuilderPreviewPoint(it.x + .5, it.y + .5, it.z + .5) }),
                BuilderDisplayGeometry.bounds(positions),
            )
        }
    }
}

/** Client-only BlockDisplay scenes; world reads stay on the server thread. */
internal class BuilderBlockDisplayRenderer(
    private val plugin: JavaPlugin,
    private val maxPlanDisplays: Int,
    blockDisplayScale: Float,
    private val antiZFighting: Boolean,
    private val planDisplayRange: Double,
    private val guidancePeriodTicks: Long,
    private val messages: LocalizedMiniMessage,
    private val taskScope: LifecycleTaskScope,
    movementPeriodTicks: Long = 2L,
    private val packets: BuilderPreviewPacketTransport = PacketEventsBuilderPreviewTransport(plugin),
    private val sentChunks: (Player) -> Set<Long> = Player::getSentChunkKeys,
    planningTasks: BuilderPlanningTasks? = null,
) : BuilderDisplayRenderer, Listener {
    private enum class Layer { SELECTION, TARGET, PLAN, BOOK }

    private data class DisplaySpec(
        val x: Double,
        val y: Double,
        val z: Double,
        val blockData: BlockData,
        val scaleX: Float = 1f,
        val scaleY: Float = 1f,
        val scaleZ: Float = 1f,
        val translateX: Float = 0f,
        val translateY: Float = 0f,
        val translateZ: Float = 0f,
        val glow: Color,
        val blockState: String = blockData.asString,
    ) {
        // Captured once on the owning thread; retained specs never read Bukkit to rebuild a key.
        val key = DisplayKey(x, y, z, blockState, scaleX, scaleY, scaleZ,
            translateX, translateY, translateZ, glow.asRGB())
    }

    private data class DisplayKey(
        val x: Double,
        val y: Double,
        val z: Double,
        val blockState: String,
        val scaleX: Float,
        val scaleY: Float,
        val scaleZ: Float,
        val translateX: Float,
        val translateY: Float,
        val translateZ: Float,
        val glowRgb: Int,
    )

    private data class Scene(
        val worldId: UUID,
        val entities: Map<DisplayKey, BuilderPacketDisplay>,
    )
    private data class BookBlock(val location: Location, val blockData: BlockData) {
        val blockState: String by lazy(LazyThreadSafetyMode.NONE) { blockData.asString }
    }
    private data class BookModel(val blocks: List<BookBlock>, val bounds: List<BuilderBlockPos>)
    private class PlanCalculation(val plan: BuilderPlan)
    private var scenes = mutableMapOf<Pair<UUID, Layer>, Scene>()
    // The packet transport replaces one viewer's complete desired set. Keep its
    // chunk/reconnect lifecycle at that same boundary, across every visible layer.
    private val audiences = mutableMapOf<UUID, BuilderPacketScene>()
    private val targetAudiences = mutableMapOf<UUID, BuilderPacketScene>()
    private var suppressedSelectionGlow = mutableSetOf<UUID>()
    private var movingPreviews = mutableMapOf<Pair<UUID, Layer>, MovingPreview>()
    private var pendingPlans = mutableMapOf<UUID, PlanCalculation>()
    private var bookSites = mutableMapOf<UUID, ConstructionSite>()
    private var bookBossBars = mutableMapOf<UUID, BossBar>()
    private var viewerRefresh: ScheduledTask? = null
    private val movementTask: ScheduledTask
    private val guidanceTask: ScheduledTask
    private val blockTransform = BuilderDisplayGeometry.blockTransform(blockDisplayScale)
    private val planningWork = planningTasks ?: BuilderPlanningWork(taskScope)
    private val ownsPlanningWork = planningTasks == null
    private var closed = false
    private val terminal = CompletableFuture<Void>()

    init {
        require(maxPlanDisplays in 32..4096)
        require(planDisplayRange.isFinite() && planDisplayRange in 8.0..128.0)
        require(guidancePeriodTicks in 5L..100L)
        require(movementPeriodTicks in 1L..20L)
        movementTask = checkNotNull(
            taskScope.runTimer(0L, movementPeriodTicks) {
                movingPreviews.values.toList().forEach(MovingPreview::update)
            },
        ) { "Builder preview movement task scope is inactive" }
        guidanceTask = checkNotNull(
            taskScope.runTimer(0L, guidancePeriodTicks) {
                bookSites.values.toList().forEach { site ->
                    if (site.player.isOnline) {
                        movingPreviews[site.player.uniqueId to Layer.BOOK]?.refreshSurface()
                        showBookActionBar(site)
                    }
                }
            },
        ) { "Builder preview guidance task scope is inactive" }
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    /** Main-thread presentation owner; the window worker sees only copied coordinates. */
    private inner class MovingPreview(
        val player: Player,
        val worldId: UUID,
        val layer: Layer,
        val sourceId: UUID?,
        points: BuilderPreviewPointSnapshot,
        private val decorations: List<DisplaySpec>,
        private val createSpec: (Int) -> DisplaySpec?,
    ) : AutoCloseable {
        private var selected: List<Int>? = null
        private var specs = emptyMap<Int, DisplaySpec?>()
        private var suspended = false
        private var failed = false
        private var rendered = false
        private var previewClosed = false
        private var renderGeneration = 0L
        private var rendering = false
        private var pendingRefresh: Boolean? = null
        private val window = BuilderAsyncPreviewWindow(
            taskScope,
            points,
            maxPlanDisplays,
            onChange = { indices ->
                if (isVisible()) {
                    selected = indices
                    render(refresh = false)
                } else suspend()
            },
            onFailure = ::renderFailed,
        )

        private fun isVisible() = !closed && !previewClosed && player.isOnline && player.world.uid == worldId

        fun update() {
            if (!isVisible()) {
                suspend()
                return
            }
            suspended = false
            val eye = player.eyeLocation
            window.update(BuilderPreviewPoint(eye.x, eye.y, eye.z))
        }

        fun refreshSurface() {
            update()
            if (!suspended) render(refresh = true)
        }

        fun suspend() {
            if (!suspended) {
                suspended = true
                window.invalidate()
                invalidateRender()
                selected = null
                specs = emptyMap()
                rendered = false
                remove(player.uniqueId, layer)
            }
        }

        private fun render(refresh: Boolean) {
            val indices = selected ?: return
            if (rendering) {
                pendingRefresh = pendingRefresh == true || refresh
                return
            }
            rendering = true
            val generation = renderGeneration
            fun current() = generation == renderGeneration && !suspended && isVisible()
            val key = player.uniqueId to layer
            val previous = scenes[key]?.takeIf { it.worldId == worldId }
            if (previous == null) remove(player.uniqueId, layer)
            val previousSpecs = specs
            val nextSpecs = LinkedHashMap<Int, DisplaySpec?>()
            val byKey = LinkedHashMap<DisplayKey, DisplaySpec>()
            val keys = ArrayList<DisplayKey>()
            fun retain(spec: DisplaySpec?) {
                if (spec != null) {
                    byKey[spec.key] = spec
                    keys += spec.key
                }
            }
            var cursor = 0
            planningWork.capture(::current) {
                if (cursor < indices.size) {
                    val index = indices[cursor++]
                    val spec = if (!refresh && previousSpecs.containsKey(index)) previousSpecs[index] else createSpec(index)
                    nextSpecs[index] = spec
                    retain(spec)
                    false
                } else {
                    decorations.forEach(::retain) // Fixed cuboid/origin edges, at most 24 specs.
                    true
                }
            }.thenCompose {
                // Only immutable keys and detached packet records cross to the worker.
                val previousEntities = previous?.entities.orEmpty()
                planningWork.compute {
                    val delta = BuilderDisplaySceneDiff.between(previousEntities.keys, keys)
                    val entities = LinkedHashMap<DisplayKey, BuilderPacketDisplay>(delta.retained.size + delta.added.size)
                    delta.retained.forEach { entities[it] = previousEntities.getValue(it) }
                    delta.added to entities
                }
            }.thenCompose { (added, entities) ->
                planningWork.onMain {
                    var addedIndex = 0
                    planningWork.capture(::current) {
                        if (addedIndex < added.size) {
                            val displayKey = added[addedIndex++]
                            entities[displayKey] = packetDisplay(byKey.getValue(displayKey))
                            false
                        } else {
                            // Keep the old scene throughout preparation, then publish one complete delta.
                            if (entities.isEmpty()) remove(player.uniqueId, layer)
                            else {
                                val scene = Scene(worldId, entities)
                                scenes[key] = scene
                                syncScene(key)
                            }
                            specs = nextSpecs
                            true
                        }
                    }
                }.thenCompose { it }
            }.whenComplete { _, failure ->
                planningWork.onMain {
                    if (generation == renderGeneration && !previewClosed && !closed) {
                        rendering = false
                        if (!isVisible()) suspend()
                        else {
                            if (failure == null) {
                                rendered = true
                                failed = false
                            } else {
                                var cause: Throwable = failure
                                while (cause is CompletionException && cause.cause != null) cause = requireNotNull(cause.cause)
                                if (cause !is CancellationException) {
                                    window.invalidate()
                                    renderFailed(cause)
                                }
                            }
                            val refreshAgain = pendingRefresh
                            pendingRefresh = null
                            if (refreshAgain != null) render(refreshAgain)
                        }
                    }
                }
            }
        }

        private fun renderFailed(failure: Throwable) {
            if (!rendered) remove(player.uniqueId, layer)
            if (!failed) {
                failed = true
                plugin.logger.warning("Builder preview window failed for ${player.uniqueId}: ${failure.message}")
            }
        }

        private fun invalidateRender() {
            renderGeneration++
            rendering = false
            pendingRefresh = null
        }

        override fun close() {
            previewClosed = true
            invalidateRender()
            window.close()
        }
    }

    override fun selection(player: Player, points: BuilderSelectionPoints, selection: BuilderSelection?) {
        val specs = buildList {
            selection?.let { addAll(bounds(it, Material.CYAN_STAINED_GLASS, Color.AQUA)) }
            points.first?.takeIf { it.worldId == player.world.uid }?.let {
                add(marker(it, Material.LIGHT_BLUE_STAINED_GLASS, Color.fromRGB(72, 170, 255)))
            }
            points.second?.takeIf { it.worldId == player.world.uid }?.let {
                add(marker(it, Material.LIME_STAINED_GLASS, Color.LIME))
            }
        }
        replace(player, Layer.SELECTION, specs)
    }

    override fun selectionTarget(player: Player, position: BuilderBlockPos?) {
        if (closed) return
        if (position == null || position.worldId != player.world.uid) {
            clearSelectionTarget(player.uniqueId)
            return
        }
        replace(player, Layer.TARGET, bounds(listOf(position), Material.WHITE_STAINED_GLASS, Color.WHITE))
    }

    override fun clearSelectionTarget(playerId: UUID) = remove(playerId, Layer.TARGET)

    override fun suppressSelectionGlow(playerId: UUID, suppressed: Boolean) {
        if (closed) return
        val changed = if (suppressed) suppressedSelectionGlow.add(playerId) else suppressedSelectionGlow.remove(playerId)
        if (!changed) return
        val key = playerId to Layer.SELECTION
        if (key in scenes) syncScene(key)
    }

    override fun clearSelection(playerId: UUID) {
        remove(playerId, Layer.SELECTION)
    }

    override fun plan(player: Player, plan: BuilderPlan) {
        if (closed) return
        if (plan.changes.firstOrNull()?.position?.worldId != player.world.uid) {
            clearPlan(player.uniqueId)
            return
        }
        val key = player.uniqueId to Layer.PLAN
        movingPreviews[key]?.takeIf { it.sourceId == plan.id }?.let {
            it.refreshSurface()
            return
        }
        if (pendingPlans[player.uniqueId]?.plan === plan) return
        val request = PlanCalculation(plan)
        val playerId = player.uniqueId
        val worldId = player.world.uid
        pendingPlans[playerId] = request
        movingPreviews.remove(key)?.close()
        try {
            val token = taskScope.token()
            checkNotNull(taskScope.runAsync(token) {
                val model = runCatching { BuilderPlanPreviewGeometry.create(plan.changes) }
                taskScope.runSync(token) {
                    if (!closed && pendingPlans[playerId] === request) {
                        pendingPlans.remove(playerId)
                        if (player.isOnline && player.world.uid == worldId) {
                            model.fold(
                                onSuccess = { geometry ->
                                    runCatching { installPlan(player, plan, geometry) }.onFailure { planFailed(playerId, it) }
                                },
                                onFailure = { planFailed(playerId, it) },
                            )
                        }
                    }
                }
            }) { "Builder preview geometry task was not scheduled" }
        } catch (failure: Throwable) {
            if (pendingPlans.remove(playerId, request)) planFailed(playerId, failure)
        }
    }

    private fun planFailed(playerId: UUID, failure: Throwable) {
        remove(playerId, Layer.PLAN)
        plugin.logger.warning("Builder preview geometry failed for $playerId: ${failure.message}")
    }

    private fun installPlan(player: Player, plan: BuilderPlan, geometry: BuilderPlanPreviewGeometry) {
        val key = player.uniqueId to Layer.PLAN
        val blockStates = mutableMapOf<String, String>()
        movingPreviews[key] = MovingPreview(
            player, player.world.uid, Layer.PLAN, plan.id, geometry.points,
            edgeSpecs(geometry.bounds, Material.MAGENTA_STAINED_GLASS, Color.fromRGB(197, 116, 255)),
        ) { index ->
            val change = plan.changes[index]
            val after = Bukkit.createBlockData(change.afterBlockData)
            val removal = after.material.isAir
            val position = change.position
            previewTransform(player.world, position.x, position.y, position.z, after, removal)?.let { transform ->
                DisplaySpec(
                    x = position.x + transform.offset.toDouble(),
                    y = position.y + transform.offset.toDouble(),
                    z = position.z + transform.offset.toDouble(),
                    blockData = if (removal) Material.RED_STAINED_GLASS.createBlockData() else after,
                    scaleX = transform.scale,
                    scaleY = transform.scale,
                    scaleZ = transform.scale,
                    glow = if (removal) Color.RED else Color.fromRGB(197, 116, 255),
                    blockState = if (removal) "minecraft:red_stained_glass"
                        else blockStates.getOrPut(change.afterBlockData) { after.asString },
                )
            }
        }.also(MovingPreview::update)
    }

    override fun clearPlan(playerId: UUID) {
        pendingPlans.remove(playerId)
        movingPreviews.remove(playerId to Layer.PLAN)?.close()
        remove(playerId, Layer.PLAN)
    }

    override fun open(site: ConstructionSite) {
        if (closed) return
        openBookWindow(site)
        showBookGuidance(site, showTitle = true)
    }

    override fun refresh(site: ConstructionSite) {
        if (closed) return
        openBookWindow(site)
        showBookGuidance(site, showTitle = false)
    }

    override fun close(playerId: UUID) {
        remove(playerId, Layer.BOOK)
        closeBookGuidance(playerId)
    }

    private fun showBookGuidance(site: ConstructionSite, showTitle: Boolean) {
        val player = site.player
        val locale = BuilderLocalePolicy.localeTag(player)
        bookSites[player.uniqueId] = site
        val name = messages.literal(BuildBookItems.compactTitle(site.bookData.title, 16))
        val bossBar = bookBossBars[player.uniqueId] ?: BossBar.bossBar(
            messages.render(
                if (site.bookData.draft) "book.preview.bossbar-draft" else "book.preview.bossbar-active",
                locale,
                mapOf("name" to name),
            ),
            1f,
            if (site.bookData.draft) BossBar.Color.YELLOW else BossBar.Color.GREEN,
            BossBar.Overlay.PROGRESS,
        ).also {
            bookBossBars[player.uniqueId] = it
            player.showBossBar(it)
        }
        bossBar.name(
            messages.render(
                if (site.bookData.draft) "book.preview.bossbar-draft" else "book.preview.bossbar-active",
                locale,
                mapOf("name" to name),
            ),
        )
        bossBar.color(if (site.bookData.draft) BossBar.Color.YELLOW else BossBar.Color.GREEN)
        showBookActionBar(site)
        if (showTitle) {
            player.showTitle(
                Title.title(
                    messages.render("book.preview.title", locale),
                    messages.render("book.preview.subtitle", locale),
                    5,
                    35,
                    10,
                ),
            )
        }
    }

    private fun showBookActionBar(site: ConstructionSite) {
        site.player.sendActionBar(messages.render("book.preview.actionbar", BuilderLocalePolicy.localeTag(site.player)))
    }

    private fun closeBookGuidance(playerId: UUID) {
        val player = bookSites.remove(playerId)?.player ?: Bukkit.getPlayer(playerId)
        movingPreviews.remove(playerId to Layer.BOOK)?.close()
        bookBossBars.remove(playerId)?.let { bar -> player?.hideBossBar(bar) }
        player?.sendActionBar(Component.empty())
    }

    private fun openBookWindow(site: ConstructionSite) {
        val key = site.player.uniqueId to Layer.BOOK
        movingPreviews.remove(key)?.close()
        if (site.player.world.uid != site.world.uid) {
            remove(site.player.uniqueId, Layer.BOOK)
            return
        }
        val model = bookModel(site)
        val decorations = buildList {
            model.bounds.takeIf(List<*>::isNotEmpty)?.let {
                addAll(bounds(it, Material.ORANGE_STAINED_GLASS, Color.fromRGB(255, 177, 66)))
            }
            val anchor = site.centerBlock
            val origin = BuilderDisplayGeometry.originMarker(
                BuilderBlockPos(site.world.uid, anchor.blockX, anchor.blockY, anchor.blockZ),
            )
            origin.forEach { edge ->
                add(
                    DisplaySpec(
                        edge.x,
                        edge.y,
                        edge.z,
                        Material.LIME_CONCRETE.createBlockData(),
                        edge.scaleX,
                        edge.scaleY,
                        edge.scaleZ,
                        glow = Color.LIME,
                    ),
                )
            }
        }
        movingPreviews[key] = MovingPreview(
            site.player, site.world.uid, Layer.BOOK, null,
            BuilderPreviewPointSnapshot.copyOf(model.bounds.map { BuilderPreviewPoint(it.x + .5, it.y + .5, it.z + .5) }),
            decorations,
        ) { index ->
            val block = model.blocks[index]
            val position = model.bounds[index]
            previewTransform(site.world, position.x, position.y, position.z, block.blockData)?.let { transform ->
                DisplaySpec(
                    position.x + transform.offset.toDouble(),
                    position.y + transform.offset.toDouble(),
                    position.z + transform.offset.toDouble(),
                    block.blockData,
                    transform.scale,
                    transform.scale,
                    transform.scale,
                    glow = Color.fromRGB(255, 177, 66),
                    blockState = block.blockState,
                )
            }
        }.also(MovingPreview::update)
    }

    private fun previewTransform(
        world: org.bukkit.World,
        x: Int,
        y: Int,
        z: Int,
        preview: BlockData,
        removal: Boolean = false,
    ): BuilderDisplayBlockTransform? {
        if (!antiZFighting) return blockTransform
        if (y !in world.minHeight until world.maxHeight || !world.isChunkLoaded(x shr 4, z shr 4)) return null
        val existing = world.getBlockAt(x, y, z).blockData
        return BuilderPreviewSurface.transform(
            blockTransform,
            matchesWorld = !removal && existing == preview,
            occupied = !existing.material.isAir,
        )
    }

    private fun bookModel(site: ConstructionSite): BookModel {
        val blocks = site.relativePositionsBottomUp().mapNotNull { relative ->
            val data = runCatching {
                Bukkit.createBlockData(site.sourceBlock(relative))
            }.getOrNull()?.takeUnless { it.material.isAir } ?: return@mapNotNull null
            BookBlock(site.worldLocation(relative), data)
        }.toList()
        return BookModel(
            blocks = blocks,
            bounds = blocks.map { block ->
                BuilderBlockPos(
                    site.world.uid,
                    block.location.blockX,
                    block.location.blockY,
                    block.location.blockZ,
                )
            },
        )
    }

    private fun bounds(selection: BuilderSelection, material: Material, glow: Color): List<DisplaySpec> = bounds(
        listOf(selection.first, selection.second), material, glow,
    )

    private fun bounds(positions: List<BuilderBlockPos>, material: Material, glow: Color): List<DisplaySpec> {
        return edgeSpecs(BuilderDisplayGeometry.bounds(positions), material, glow)
    }

    private fun edgeSpecs(edges: List<BuilderDisplayEdge>, material: Material, glow: Color): List<DisplaySpec> {
        val data = material.createBlockData()
        return edges.map { edge ->
            DisplaySpec(
                edge.x,
                edge.y,
                edge.z,
                data,
                edge.scaleX,
                edge.scaleY,
                edge.scaleZ,
                glow = glow,
            )
        }
    }

    private fun marker(position: BuilderBlockPos, material: Material, glow: Color) = DisplaySpec(
        position.x + .375,
        position.y + .375,
        position.z + .375,
        material.createBlockData(),
        .25f,
        .25f,
        .25f,
        glow = glow,
    )

    private fun replace(player: Player, layer: Layer, specs: List<DisplaySpec>) {
        val key = player.uniqueId to layer
        val previous = scenes[key]?.takeIf { it.worldId == player.world.uid }
        if (previous == null) remove(player.uniqueId, layer)
        if (specs.isEmpty() || !player.isOnline) {
            remove(player.uniqueId, layer)
            return
        }
        val previousEntities = previous?.entities.orEmpty()
        val nextSpecs = specs.associateBy(DisplaySpec::key)
        val delta = BuilderDisplaySceneDiff.between(previousEntities.keys, nextSpecs.keys.toList())
        val nextEntities = LinkedHashMap<DisplayKey, BuilderPacketDisplay>(delta.retained.size + delta.added.size)
        delta.retained.forEach { displayKey -> nextEntities[displayKey] = previousEntities.getValue(displayKey) }
        delta.added.forEach { displayKey ->
            val spec = nextSpecs.getValue(displayKey)
            nextEntities[displayKey] = packetDisplay(spec)
        }
        val scene = Scene(player.world.uid, nextEntities)
        // Publish before enqueueing so cleanup can remove even a partially sent scene.
        scenes[key] = scene
        syncScene(key)
    }

    private fun packetDisplay(spec: DisplaySpec) = BuilderPacketDisplay(
        entityId = packets.nextEntityId(), uuid = UUID.randomUUID(),
        x = spec.x, y = spec.y, z = spec.z,
        blockStateId = packets.blockStateId(spec.blockData),
        scaleX = spec.scaleX, scaleY = spec.scaleY, scaleZ = spec.scaleZ,
        translateX = spec.translateX, translateY = spec.translateY, translateZ = spec.translateZ,
        glowRgb = spec.glow.asRGB(), viewRange = (planDisplayRange / 64.0).toFloat(),
    )

    override fun syncBookViewer(viewer: Player) {
        if (viewer.isOnline) refreshViewersNextTick()
    }

    private fun syncScene(key: Pair<UUID, Layer>) {
        if (closed) return
        if (key.second == Layer.TARGET) {
            val player = Bukkit.getPlayer(key.first)
            if (player == null) targetAudiences.remove(key.first)?.close() else syncTarget(player)
            return
        }
        val candidates = if (key.second == Layer.BOOK) Bukkit.getOnlinePlayers()
            else listOfNotNull(Bukkit.getPlayer(key.first))
        candidates.forEach(::syncViewer)
    }

    private fun syncTarget(viewer: Player) {
        val id = viewer.uniqueId
        val target = scenes[id to Layer.TARGET]?.takeIf { viewer.isOnline && viewer.world.uid == it.worldId }
        val connection = if (target != null) packets.targetConnection(viewer) else null
        if (target == null || connection == null) {
            targetAudiences.remove(id)?.close()
            return
        }
        targetAudiences.getOrPut(id, ::BuilderPacketScene)
            .update(target.entities.values, mapOf(id to BuilderPacketViewer(connection, sentChunks(viewer))))
    }

    private fun syncViewer(viewer: Player) {
        val viewerId = viewer.uniqueId
        if (!viewer.isOnline) {
            audiences.remove(viewerId)?.close()
            return
        }
        val displays = buildList {
            scenes.forEach { (key, scene) ->
                if (key.second != Layer.TARGET && viewer.world.uid == scene.worldId && (viewerId == key.first ||
                    key.second == Layer.BOOK && viewer.hasPermission(BUILDER_PREVIEW_ADMIN_PERMISSION))) {
                    if (key.second == Layer.SELECTION && key.first in suppressedSelectionGlow) {
                        addAll(scene.entities.values.map { it.copy(glowing = false) })
                    } else addAll(scene.entities.values)
                }
            }
        }
        val connection = if (displays.isNotEmpty()) packets.connection(viewer) else null
        if (connection == null) {
            audiences.remove(viewerId)?.close()
            return
        }
        audiences.getOrPut(viewerId, ::BuilderPacketScene)
            .update(displays, mapOf(viewerId to BuilderPacketViewer(connection, sentChunks(viewer))))
    }

    @EventHandler
    fun onChunkLoad(event: PlayerChunkLoadEvent) = refreshViewersNextTick()

    @EventHandler
    fun onChunkUnload(event: PlayerChunkUnloadEvent) {
        audiences[event.player.uniqueId]?.forgetChunk(event.player.uniqueId, event.chunk.chunkKey)
        targetAudiences[event.player.uniqueId]?.forgetChunk(event.player.uniqueId, event.chunk.chunkKey)
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) = refreshViewersNextTick()

    @EventHandler
    fun onWorldChange(event: PlayerChangedWorldEvent) = resetViewer(event.player.uniqueId)

    @EventHandler
    fun onRespawn(event: PlayerPostRespawnEvent) = resetViewer(event.player.uniqueId)

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        pendingPlans.remove(event.player.uniqueId)
        audiences.remove(event.player.uniqueId)?.close()
        targetAudiences.remove(event.player.uniqueId)?.close()
        packets.forget(event.player)
    }

    private fun resetViewer(playerId: UUID) {
        pendingPlans.remove(playerId)
        audiences.remove(playerId)?.close()
        targetAudiences.remove(playerId)?.close()
        movingPreviews.filterKeys { it.first == playerId }.values.forEach(MovingPreview::suspend)
        refreshViewersNextTick()
    }

    private fun refreshViewersNextTick() {
        if (closed || viewerRefresh != null || scenes.isEmpty()) return
        viewerRefresh = taskScope.runLater(1L) {
            viewerRefresh = null
            if (!closed) Bukkit.getOnlinePlayers().forEach { viewer -> syncViewer(viewer); syncTarget(viewer) }
        }
    }

    private fun remove(playerId: UUID, layer: Layer) {
        val key = playerId to layer
        if (scenes.remove(key) != null) syncScene(key)
    }

    override fun clearPlayer(playerId: UUID) {
        pendingPlans.remove(playerId)
        suppressedSelectionGlow.remove(playerId)
        movingPreviews.keys.filter { it.first == playerId }.forEach { movingPreviews.remove(it)?.close() }
        Layer.entries.forEach { remove(playerId, it) }
        closeBookGuidance(playerId)
    }

    override fun close() { closeAsync() }

    override fun closeAsync(): CompletableFuture<Void> {
        if (closed) return terminal.copy()
        closed = true
        HandlerList.unregisterAll(this)
        movementTask.cancel()
        guidanceTask.cancel()
        viewerRefresh?.cancel()
        viewerRefresh = null
        var failure: Throwable? = null
        fun attempt(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        // Lifecycle cleanup is deliberately immediate; gameplay window updates remain budgeted.
        bookBossBars.forEach { (id, bar) -> attempt {
            val player = bookSites[id]?.player ?: plugin.server.getPlayer(id)
            player?.hideBossBar(bar)
            player?.sendActionBar(Component.empty())
        } }
        movingPreviews.values.forEach { attempt(it::close) }
        if (ownsPlanningWork) attempt { (planningWork as AutoCloseable).close() }
        audiences.values.forEach { audience -> attempt { audience.close() } }
        audiences.clear()
        targetAudiences.values.forEach { audience -> attempt { audience.close() } }
        targetAudiences.clear()
        bookBossBars.clear()
        bookSites.clear()
        scenes.clear()
        movingPreviews.clear()
        pendingPlans.clear()
        suppressedSelectionGlow.clear()
        val nativeFailure = failure
        val packetDrain = runCatching { packets.closeAsync() }.getOrElse { CompletableFuture.failedFuture(it) }
        packetDrain.whenComplete { _, packetFailure ->
            val primary = nativeFailure ?: packetFailure
            if (primary == null) terminal.complete(null) else {
                if (nativeFailure != null && packetFailure != null && nativeFailure !== packetFailure) nativeFailure.addSuppressed(packetFailure)
                terminal.completeExceptionally(primary)
            }
        }
        return terminal.copy()
    }
}
