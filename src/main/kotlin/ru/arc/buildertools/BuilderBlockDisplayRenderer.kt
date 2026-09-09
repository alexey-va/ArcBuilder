package ru.arc.buildertools

import com.sk89q.worldedit.bukkit.BukkitAdapter
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.data.BlockData
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.Transformation
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.autobuild.BuildBookPreviewBridge
import ru.arc.autobuild.BuildBookItems
import ru.arc.autobuild.ConstructionSite
import ru.arc.core.LifecycleTaskScope
import ru.arc.util.BlockUtils.rotateBlockData
import ru.arc.text.LocalizedMiniMessage
import java.util.UUID

/**
 * Preview lifecycle boundary owned by the runtime.
 *
 * The Paper implementation below keeps native displays player-only. Platform
 * tests replace this boundary because MockBukkit 4.110 does not implement
 * [Entity.setVisibleByDefault].
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
        viewerX: Double,
        viewerY: Double,
        viewerZ: Double,
        center: (T) -> Triple<Double, Double, Double>,
    ): List<T> {
        require(limit > 0)
        if (values.size <= limit) return values
        return values.mapIndexed { index, value ->
            val (x, y, z) = center(value)
            val dx = x - viewerX
            val dy = y - viewerY
            val dz = z - viewerZ
            Candidate(index, value, dx * dx + dy * dy + dz * dz)
        }
            .sortedWith(
                compareBy<Candidate<T>>(Candidate<T>::distanceSquared).thenBy(Candidate<T>::index),
            )
            .take(limit)
            .sortedBy(Candidate<T>::index)
            .map(Candidate<T>::value)
    }
}

/** Player-only native BlockDisplay scenes; no packets, fake blocks, or particles. */
internal class BuilderBlockDisplayRenderer(
    private val plugin: JavaPlugin,
    private val maxPlanDisplays: Int,
    blockDisplayScale: Float,
    private val antiZFighting: Boolean,
    private val planDisplayRange: Double,
    private val guidancePeriodTicks: Long,
    private val messages: LocalizedMiniMessage,
    taskScope: LifecycleTaskScope,
) : BuilderDisplayRenderer {
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

    private data class Scene(val worldId: UUID, val entities: Map<DisplayKey, Entity>)
    private data class BookBlock(val location: Location, val blockData: BlockData)
    private data class BookModel(val blocks: List<BookBlock>, val bounds: List<BuilderBlockPos>)
    private val scenes = mutableMapOf<Pair<UUID, Layer>, Scene>()
    private val bookSites = mutableMapOf<UUID, ConstructionSite>()
    private val bookModels = mutableMapOf<UUID, BookModel>()
    private val bookBossBars = mutableMapOf<UUID, BossBar>()
    private val blockTransform = BuilderDisplayGeometry.blockTransform(blockDisplayScale)

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
            viewerX = eye.x,
            viewerY = eye.y,
            viewerZ = eye.z,
        ) { change ->
            Triple(change.position.x + .5, change.position.y + .5, change.position.z + .5)
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
            viewerX = eye.x,
            viewerY = eye.y,
            viewerZ = eye.z,
        ) { block ->
            Triple(block.location.blockX + .5, block.location.blockY + .5, block.location.blockZ + .5)
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
        val previousEntities = previous?.entities.orEmpty().filterValues(Entity::isValid)
        val nextSpecs = specs.associateBy { it.key() }
        val delta = BuilderDisplaySceneDiff.between(previousEntities.keys, nextSpecs.keys.toList())
        if (delta.added.isEmpty() && delta.removed.isEmpty()) {
            if (layer == Layer.BOOK) syncBookSceneVisibility(player, previousEntities.values)
            return
        }
        val nextEntities = LinkedHashMap<DisplayKey, Entity>(delta.retained.size + delta.added.size)
        delta.retained.forEach { displayKey -> nextEntities[displayKey] = previousEntities.getValue(displayKey) }
        val spawned = mutableListOf<Entity>()
        try {
            delta.added.forEach { displayKey ->
                val spec = nextSpecs.getValue(displayKey)
                val display = player.world.spawn(Location(player.world, spec.x, spec.y, spec.z), BlockDisplay::class.java) { entity ->
                    entity.block = spec.blockData
                    entity.setVisibleByDefault(false)
                    entity.isPersistent = false
                    entity.isInvulnerable = true
                    entity.setGravity(false)
                    entity.isGlowing = true
                    entity.glowColorOverride = spec.glow
                    entity.brightness = Display.Brightness(15, 15)
                    entity.viewRange = (planDisplayRange / 64.0).toFloat()
                    entity.transformation = Transformation(
                        Vector3f(spec.translateX, spec.translateY, spec.translateZ),
                        Quaternionf(),
                        Vector3f(spec.scaleX, spec.scaleY, spec.scaleZ),
                        Quaternionf(),
                    )
                }
                if (layer == Layer.BOOK) syncBookSceneVisibility(player, listOf(display))
                else player.showEntity(plugin, display)
                spawned += display
                nextEntities[displayKey] = display
            }
            delta.removed.forEach { displayKey -> previousEntities.getValue(displayKey).remove() }
            scenes[key] = Scene(player.world.uid, nextEntities)
        } catch (failure: Throwable) {
            spawned.forEach(Entity::remove)
            throw failure
        }
    }

    override fun syncBookViewer(viewer: Player) {
        if (!viewer.isOnline || !viewer.hasPermission(BUILDER_PREVIEW_ADMIN_PERMISSION)) return
        scenes.forEach { (key, scene) ->
            if (key.second == Layer.BOOK && scene.worldId == viewer.world.uid) {
                scene.entities.values.filter(Entity::isValid).forEach { viewer.showEntity(plugin, it) }
            }
        }
    }

    private fun syncBookSceneVisibility(owner: Player, entities: Collection<Entity>) {
        Bukkit.getOnlinePlayers()
            .filter { it.world.uid == owner.world.uid }
            .forEach { viewer ->
                if (viewer.uniqueId == owner.uniqueId || viewer.hasPermission(BUILDER_PREVIEW_ADMIN_PERMISSION)) {
                    entities.forEach { viewer.showEntity(plugin, it) }
                } else {
                    entities.forEach { viewer.hideEntity(plugin, it) }
                }
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
        scenes.remove(playerId to layer)?.entities?.values?.forEach(Entity::remove)
    }

    override fun clearPlayer(playerId: UUID) {
        Layer.entries.forEach { remove(playerId, it) }
        closeBookGuidance(playerId)
    }

    override fun close() {
        bookBossBars.keys.toList().forEach(::closeBookGuidance)
        scenes.values.flatMap { scene -> scene.entities.values }.forEach(Entity::remove)
        scenes.clear()
    }
}
