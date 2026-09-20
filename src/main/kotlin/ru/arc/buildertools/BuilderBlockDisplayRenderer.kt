package ru.arc.buildertools

import com.sk89q.worldedit.bukkit.BukkitAdapter
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
import ru.arc.util.BlockUtils.rotateBlockData
import ru.arc.text.LocalizedMiniMessage
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
    fun clearSelection(playerId: UUID)
    fun plan(player: Player, plan: BuilderPlan)
    fun clearPlan(playerId: UUID)
    fun clearPlayer(playerId: UUID)
    fun syncBookViewer(viewer: Player) = Unit
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
    private val packets: BuilderPreviewPacketTransport = PacketEventsBuilderPreviewTransport(plugin.logger),
    private val sentChunks: (Player) -> Set<Long> = Player::getSentChunkKeys,
) : BuilderDisplayRenderer, Listener {
    private enum class Layer { SELECTION, PLAN, BOOK }

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
    )

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
        val audience: BuilderPacketScene,
    )
    private data class BookBlock(val location: Location, val blockData: BlockData)
    private data class BookModel(val blocks: List<BookBlock>, val bounds: List<BuilderBlockPos>)
    private val scenes = mutableMapOf<Pair<UUID, Layer>, Scene>()
    private val movingPreviews = mutableMapOf<Pair<UUID, Layer>, MovingPreview>()
    private val bookSites = mutableMapOf<UUID, ConstructionSite>()
    private val bookBossBars = mutableMapOf<UUID, BossBar>()
    private var viewerRefresh: ScheduledTask? = null
    private val movementTask: ScheduledTask
    private val guidanceTask: ScheduledTask
    private val blockTransform = BuilderDisplayGeometry.blockTransform(blockDisplayScale)
    private var closed = false

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
        positions: List<BuilderBlockPos>,
        private val decorations: List<DisplaySpec>,
        private val createSpec: (Int) -> DisplaySpec?,
    ) : AutoCloseable {
        private var selected: List<Int>? = null
        private var specs = emptyMap<Int, DisplaySpec?>()
        private var suspended = false
        private var failed = false
        private var rendered = false
        private val window = BuilderAsyncPreviewWindow(
            taskScope,
            positions.map { BuilderPreviewPoint(it.x + .5, it.y + .5, it.z + .5) },
            maxPlanDisplays,
            onChange = { indices ->
                if (isVisible()) {
                    selected = indices
                    render(refresh = false)
                } else suspend()
            },
            onFailure = { failure ->
                // Keep the old scene until its replacement is ready, but never
                // leave a failed replacement displaying another plan indefinitely.
                if (!rendered) remove(player.uniqueId, layer)
                if (!failed) {
                    failed = true
                    plugin.logger.warning("Builder preview window failed for ${player.uniqueId}: ${failure.message}")
                }
            },
        )

        private fun isVisible() = !closed && player.isOnline && player.world.uid == worldId

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
                selected = null
                specs = emptyMap()
                rendered = false
                remove(player.uniqueId, layer)
            }
        }

        private fun render(refresh: Boolean) {
            val indices = selected ?: return
            val previous = specs
            specs = indices.associateWith { index ->
                if (!refresh && previous.containsKey(index)) previous[index] else createSpec(index)
            }
            replace(player, layer, specs.values.filterNotNull() + decorations)
            rendered = true
            failed = false
        }

        override fun close() = window.close()
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

    override fun clearSelection(playerId: UUID) = remove(playerId, Layer.SELECTION)

    override fun plan(player: Player, plan: BuilderPlan) {
        if (plan.changes.firstOrNull()?.position?.worldId != player.world.uid) {
            clearPlan(player.uniqueId)
            return
        }
        val key = player.uniqueId to Layer.PLAN
        movingPreviews[key]?.takeIf { it.sourceId == plan.id }?.let {
            it.refreshSurface()
            return
        }
        val positions = plan.changes.map(BuilderBlockChange::position)
        movingPreviews.remove(key)?.close()
        movingPreviews[key] = MovingPreview(
            player, player.world.uid, Layer.PLAN, plan.id, positions,
            bounds(positions, Material.MAGENTA_STAINED_GLASS, Color.fromRGB(197, 116, 255)),
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
                )
            }
        }.also(MovingPreview::update)
    }

    override fun clearPlan(playerId: UUID) {
        movingPreviews.remove(playerId to Layer.PLAN)?.close()
        remove(playerId, Layer.PLAN)
    }

    override fun open(site: ConstructionSite) {
        openBookWindow(site)
        showBookGuidance(site, showTitle = true)
    }

    override fun refresh(site: ConstructionSite) {
        openBookWindow(site)
        showBookGuidance(site, showTitle = false)
    }

    override fun close(playerId: UUID) {
        remove(playerId, Layer.BOOK)
        closeBookGuidance(playerId)
    }

    private fun showBookGuidance(site: ConstructionSite, showTitle: Boolean) {
        val player = site.player
        val locale = player.locale().toLanguageTag()
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
        site.player.sendActionBar(messages.render("book.preview.actionbar", site.player.locale().toLanguageTag()))
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
            site.player, site.world.uid, Layer.BOOK, null, model.bounds, decorations,
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
                rotateBlockData(
                    Bukkit.createBlockData(BukkitAdapter.adapt(site.sourceBlock(relative)).asString),
                    site.fullRotation,
                )
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
        val data = material.createBlockData()
        return BuilderDisplayGeometry.bounds(positions).map { edge ->
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
        val nextSpecs = specs.associateBy { it.key() }
        val delta = BuilderDisplaySceneDiff.between(previousEntities.keys, nextSpecs.keys.toList())
        val nextEntities = LinkedHashMap<DisplayKey, BuilderPacketDisplay>(delta.retained.size + delta.added.size)
        delta.retained.forEach { displayKey -> nextEntities[displayKey] = previousEntities.getValue(displayKey) }
        delta.added.forEach { displayKey ->
            val spec = nextSpecs.getValue(displayKey)
            nextEntities[displayKey] = BuilderPacketDisplay(
                entityId = packets.nextEntityId(),
                uuid = UUID.randomUUID(),
                x = spec.x, y = spec.y, z = spec.z,
                blockStateId = packets.blockStateId(spec.blockData),
                scaleX = spec.scaleX, scaleY = spec.scaleY, scaleZ = spec.scaleZ,
                translateX = spec.translateX, translateY = spec.translateY, translateZ = spec.translateZ,
                glowRgb = spec.glow.asRGB(),
                viewRange = (planDisplayRange / 64.0).toFloat(),
            )
        }
        val scene = Scene(player.world.uid, nextEntities, previous?.audience ?: BuilderPacketScene())
        // Publish before enqueueing so cleanup can remove even a partially sent scene.
        scenes[key] = scene
        syncScene(key, scene)
    }

    override fun syncBookViewer(viewer: Player) {
        if (viewer.isOnline) refreshViewersNextTick()
    }

    private fun syncScene(key: Pair<UUID, Layer>, scene: Scene) {
        val candidates = if (key.second == Layer.BOOK) Bukkit.getOnlinePlayers()
            else listOfNotNull(Bukkit.getPlayer(key.first))
        val audience = candidates.mapNotNull { viewer ->
            if (!viewer.isOnline || viewer.world.uid != scene.worldId ||
                (viewer.uniqueId != key.first && !viewer.hasPermission(BUILDER_PREVIEW_ADMIN_PERMISSION))) {
                return@mapNotNull null
            }
            val connection = packets.connection(viewer) ?: return@mapNotNull null
            viewer.uniqueId to BuilderPacketViewer(connection, sentChunks(viewer))
        }.toMap()
        scene.audience.update(scene.entities.values, audience)
    }

    @EventHandler
    fun onChunkLoad(event: PlayerChunkLoadEvent) = refreshViewersNextTick()

    @EventHandler
    fun onChunkUnload(event: PlayerChunkUnloadEvent) {
        scenes.values.filter { it.worldId == event.world.uid }.forEach {
            it.audience.forgetChunk(event.player.uniqueId, event.chunk.chunkKey)
        }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) = refreshViewersNextTick()

    @EventHandler
    fun onWorldChange(event: PlayerChangedWorldEvent) = resetViewer(event.player.uniqueId)

    @EventHandler
    fun onRespawn(event: PlayerPostRespawnEvent) = resetViewer(event.player.uniqueId)

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        scenes.values.forEach { it.audience.removeViewer(event.player.uniqueId) }
        packets.forget(event.player)
    }

    private fun resetViewer(playerId: UUID) {
        scenes.values.forEach { it.audience.removeViewer(playerId) }
        movingPreviews.filterKeys { it.first == playerId }.values.forEach(MovingPreview::suspend)
        refreshViewersNextTick()
    }

    private fun refreshViewersNextTick() {
        if (closed || viewerRefresh != null || scenes.isEmpty()) return
        viewerRefresh = taskScope.runLater(1L) {
            viewerRefresh = null
            if (!closed) scenes.forEach { (key, scene) -> syncScene(key, scene) }
        }
    }

    private fun DisplaySpec.key() = DisplayKey(
        x = x,
        y = y,
        z = z,
        blockState = blockData.asString,
        scaleX = scaleX,
        scaleY = scaleY,
        scaleZ = scaleZ,
        translateX = translateX,
        translateY = translateY,
        translateZ = translateZ,
        glowRgb = glow.asRGB(),
    )

    private fun remove(playerId: UUID, layer: Layer) {
        scenes.remove(playerId to layer)?.audience?.close()
    }

    override fun clearPlayer(playerId: UUID) {
        movingPreviews.keys.filter { it.first == playerId }.forEach { movingPreviews.remove(it)?.close() }
        Layer.entries.forEach { remove(playerId, it) }
        closeBookGuidance(playerId)
    }

    override fun close() {
        if (closed) return
        closed = true
        HandlerList.unregisterAll(this)
        movementTask.cancel()
        guidanceTask.cancel()
        viewerRefresh?.cancel()
        viewerRefresh = null
        bookBossBars.keys.toList().forEach(::closeBookGuidance)
        scenes.values.forEach { it.audience.close() }
        scenes.clear()
        movingPreviews.values.forEach(MovingPreview::close)
        movingPreviews.clear()
        packets.close()
    }
}
