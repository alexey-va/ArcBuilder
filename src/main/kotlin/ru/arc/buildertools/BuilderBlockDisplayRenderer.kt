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
    private val bookSites = mutableMapOf<UUID, ConstructionSite>()
    private val bookModels = mutableMapOf<UUID, BookModel>()
    private val bookBossBars = mutableMapOf<UUID, BossBar>()
    private var viewerRefresh: ScheduledTask? = null
    private val blockTransform = BuilderDisplayGeometry.blockTransform(blockDisplayScale)
    private var closed = false

    init {
        require(maxPlanDisplays in 32..4096)
        require(planDisplayRange.isFinite() && planDisplayRange in 8.0..128.0)
        require(guidancePeriodTicks in 5L..100L)
        checkNotNull(
            taskScope.runTimer(0L, guidancePeriodTicks) {
                bookSites.values.toList().forEach { site ->
                    if (site.player.isOnline) {
                        renderBook(site)
                        showBookActionBar(site)
                    }
                }
            },
        ) { "Builder preview guidance task scope is inactive" }
        plugin.server.pluginManager.registerEvents(this, plugin)
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
            remove(player.uniqueId, Layer.PLAN)
            return
        }
        val eye = player.eyeLocation
        val visible = BuilderPreviewWindow.nearest(
            values = plan.changes,
            limit = maxPlanDisplays,
        ) { change ->
            val dx = change.position.x + .5 - eye.x
            val dy = change.position.y + .5 - eye.y
            val dz = change.position.z + .5 - eye.z
            dx * dx + dy * dy + dz * dz
        }
        val specs = buildList {
            visible.forEach { change ->
                val after = Bukkit.createBlockData(change.afterBlockData)
                val removal = after.material.isAir
                val position = change.position
                val blockTransform = previewTransform(player.world, position.x, position.y, position.z, after, removal)
                    ?: return@forEach
                add(
                    DisplaySpec(
                        x = change.position.x + blockTransform.offset.toDouble(),
                        y = change.position.y + blockTransform.offset.toDouble(),
                        z = change.position.z + blockTransform.offset.toDouble(),
                        blockData = if (removal) Material.RED_STAINED_GLASS.createBlockData() else after,
                        scaleX = blockTransform.scale,
                        scaleY = blockTransform.scale,
                        scaleZ = blockTransform.scale,
                        glow = if (removal) Color.RED else Color.fromRGB(197, 116, 255),
                    ),
                )
            }
            plan.changes.takeIf(List<*>::isNotEmpty)?.let { changes ->
                addAll(bounds(changes.map(BuilderBlockChange::position), Material.MAGENTA_STAINED_GLASS, Color.fromRGB(197, 116, 255)))
            }
        }
        replace(player, Layer.PLAN, specs)
    }

    override fun clearPlan(playerId: UUID) = remove(playerId, Layer.PLAN)

    override fun open(site: ConstructionSite) {
        bookModels[site.player.uniqueId] = bookModel(site)
        renderBook(site)
        showBookGuidance(site, showTitle = true)
    }

    override fun refresh(site: ConstructionSite) {
        bookModels[site.player.uniqueId] = bookModel(site)
        renderBook(site)
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
        bookModels.remove(playerId)
        bookBossBars.remove(playerId)?.let { bar -> player?.hideBossBar(bar) }
        player?.sendActionBar(Component.empty())
    }

    private fun renderBook(site: ConstructionSite) {
        if (site.player.world.uid != site.world.uid) {
            remove(site.player.uniqueId, Layer.BOOK)
            return
        }
        val model = bookModels[site.player.uniqueId] ?: bookModel(site).also {
            bookModels[site.player.uniqueId] = it
        }
        val eye = site.player.eyeLocation
        val visible = BuilderPreviewWindow.nearest(
            values = model.blocks,
            limit = maxPlanDisplays,
        ) { block ->
            val dx = block.location.blockX + .5 - eye.x
            val dy = block.location.blockY + .5 - eye.y
            val dz = block.location.blockZ + .5 - eye.z
            dx * dx + dy * dy + dz * dz
        }
        val specs = buildList {
            visible.forEach { block ->
                val location = block.location
                val blockTransform = previewTransform(site.world, location.blockX, location.blockY, location.blockZ, block.blockData)
                    ?: return@forEach
                add(
                    DisplaySpec(
                        block.location.blockX + blockTransform.offset.toDouble(),
                        block.location.blockY + blockTransform.offset.toDouble(),
                        block.location.blockZ + blockTransform.offset.toDouble(),
                        block.blockData,
                        blockTransform.scale,
                        blockTransform.scale,
                        blockTransform.scale,
                        glow = Color.fromRGB(255, 177, 66),
                    ),
                )
            }
            model.bounds.takeIf(List<*>::isNotEmpty)?.let {
                addAll(bounds(it, Material.ORANGE_STAINED_GLASS, Color.fromRGB(255, 177, 66)))
            }
            val anchor = site.centerBlock.block
            val origin = BuilderDisplayGeometry.originMarker(
                BuilderBlockPos(site.world.uid, anchor.x, anchor.y, anchor.z),
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
        replace(site.player, Layer.BOOK, specs)
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
        Layer.entries.forEach { remove(playerId, it) }
        closeBookGuidance(playerId)
    }

    override fun close() {
        if (closed) return
        closed = true
        HandlerList.unregisterAll(this)
        viewerRefresh?.cancel()
        viewerRefresh = null
        bookBossBars.keys.toList().forEach(::closeBookGuidance)
        scenes.values.forEach { it.audience.close() }
        scenes.clear()
        packets.close()
    }
}
