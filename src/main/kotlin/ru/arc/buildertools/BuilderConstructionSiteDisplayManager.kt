package ru.arc.buildertools

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.autobuild.BuildBookItems
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.scene.PacketDisplayScene
import ru.arc.paper.scene.PaperSpatialControls
import ru.arc.paper.scene.PaperSpatialSpace
import ru.arc.paper.scene.SceneAudience
import ru.arc.paper.scene.SceneDisplayStyle
import ru.arc.paper.scene.SceneFrame
import ru.arc.paper.scene.ScenePart
import ru.arc.paper.scene.ScenePose
import ru.arc.paper.scene.SpatialControlController
import ru.arc.paper.scene.SpatialControlFrame
import ru.arc.paper.scene.SpatialSceneKey
import ru.arc.text.LocalizedMiniMessage
import ru.arc.util.Logging.warn
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

internal data class BuilderConstructionSiteDisplaySettings(
    val enabled: Boolean,
    val outlineEnabled: Boolean,
    val outlineMaterial: Material,
    val outlineThickness: Float,
    val glowColor: Color,
    val panelEnabled: Boolean,
    val panelFace: BuilderConstructionSitePanelFace,
    val panelHeightOffset: Double,
    val panelFrontOffset: Double,
    val panelInteractionWidth: Float,
    val panelInteractionHeight: Float,
    val panelLineWidth: Int,
    val panelBackgroundColor: Color,
    val viewRange: Double,
    val defaultLocale: String,
)

internal fun BuilderToolsConfig.constructionSiteDisplaySettings() = BuilderConstructionSiteDisplaySettings(
    enabled = constructionSiteEnabled,
    outlineEnabled = constructionSiteOutlineEnabled,
    outlineMaterial = constructionSiteOutlineMaterial,
    outlineThickness = constructionSiteOutlineThickness,
    glowColor = Color.fromRGB(constructionSiteGlowColor.removePrefix("#").toInt(16)),
    panelEnabled = constructionSitePanelEnabled,
    panelFace = constructionSitePanelFace,
    panelHeightOffset = constructionSitePanelHeightOffset,
    panelFrontOffset = constructionSitePanelFrontOffset,
    panelInteractionWidth = constructionSitePanelInteractionWidth,
    panelInteractionHeight = constructionSitePanelInteractionHeight,
    panelLineWidth = constructionSitePanelLineWidth,
    panelBackgroundColor = Color.fromARGB(constructionSitePanelBackgroundColor.removePrefix("#").toLong(16).toInt()),
    viewRange = constructionSiteViewRange,
    defaultLocale = defaultLocaleTag,
)

internal data class BuilderConstructionSiteDisplayModel(
    val worldId: UUID,
    val edges: List<BuilderDisplayEdge>,
    val panelX: Double,
    val panelY: Double,
    val panelZ: Double,
    val panelYaw: Float,
)

internal data class BuilderSitePanelPlacement(
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
)

internal object BuilderConstructionSiteDisplayLayout {
    fun create(
        project: BuilderConstructionProjectRecord,
        settings: BuilderConstructionSiteDisplaySettings,
    ): BuilderConstructionSiteDisplayModel {
        val positions = project.steps.map { it.change.position }
        require(positions.isNotEmpty())
        val worldId = positions.first().worldId
        require(positions.all { it.worldId == worldId })
        val face = project.sitePanelFace ?: settings.panelFace
        val panel = panelPlacement(
            project.siteAnchor?.let(::listOf) ?: positions, face, settings.panelHeightOffset, settings.panelFrontOffset,
            occupiedPositions = positions, panelWidth = BuilderWorldPlaque.visualWidth(settings.panelLineWidth),
        )
        return BuilderConstructionSiteDisplayModel(
            worldId = worldId,
            edges = BuilderDisplayGeometry.bounds(positions, settings.outlineThickness),
            panelX = panel.x,
            panelY = panel.y,
            panelZ = panel.z,
            panelYaw = panel.yaw,
        )
    }

    fun nearestFace(
        project: BuilderConstructionProjectRecord,
        viewerX: Double,
        viewerZ: Double,
    ): BuilderConstructionSitePanelFace = nearestFace(project.siteAnchor?.let(::listOf) ?: project.steps.map { it.change.position }, viewerX, viewerZ)

    fun nearestFace(
        positions: List<BuilderBlockPos>,
        viewerX: Double,
        viewerZ: Double,
    ): BuilderConstructionSitePanelFace {
        val bounds = bounds(positions)
        return BuilderConstructionSitePanelFace.entries.minBy { face ->
            val (panelX, panelZ) = panelCenter(bounds, face, frontOffset = 0.0)
            val deltaX = viewerX - panelX
            val deltaZ = viewerZ - panelZ
            deltaX * deltaX + deltaZ * deltaZ
        }
    }

    fun panelPlacement(
        positions: List<BuilderBlockPos>,
        face: BuilderConstructionSitePanelFace,
        heightOffset: Double,
        frontOffset: Double,
        occupiedPositions: List<BuilderBlockPos> = positions,
        panelWidth: Double = 0.0,
    ): BuilderSitePanelPlacement {
        val anchorBounds = bounds(positions)
        val occupied = bounds(occupiedPositions)
        var (x, z) = panelCenter(anchorBounds, face, frontOffset)
        // Packet preview blocks are absent from Bukkit collision shapes. Start outside their
        // full envelope before Core searches real obstacles; include its half-block side escape.
        val halfWidth = panelWidth / 2.0 + .5
        when (face) {
            BuilderConstructionSitePanelFace.MIN_X -> if (z + halfWidth >= occupied.minZ && z - halfWidth <= occupied.maxZ)
                x = min(x, occupied.minX - frontOffset)
            BuilderConstructionSitePanelFace.MAX_X -> if (z + halfWidth >= occupied.minZ && z - halfWidth <= occupied.maxZ)
                x = max(x, occupied.maxX + frontOffset)
            BuilderConstructionSitePanelFace.MIN_Z -> if (x + halfWidth >= occupied.minX && x - halfWidth <= occupied.maxX)
                z = min(z, occupied.minZ - frontOffset)
            BuilderConstructionSitePanelFace.MAX_Z -> if (x + halfWidth >= occupied.minX && x - halfWidth <= occupied.maxX)
                z = max(z, occupied.maxZ + frontOffset)
        }
        return BuilderSitePanelPlacement(x, anchorBounds.minY + heightOffset, z, face.yaw)
    }

    private data class Bounds(
        val minX: Double,
        val minY: Double,
        val minZ: Double,
        val maxX: Double,
        val maxZ: Double,
    )

    private fun bounds(positions: List<BuilderBlockPos>): Bounds {
        require(positions.isNotEmpty())
        return Bounds(
            minX = positions.minOf { it.x }.toDouble(),
            minY = positions.minOf { it.y }.toDouble(),
            minZ = positions.minOf { it.z }.toDouble(),
            maxX = positions.maxOf { it.x } + 1.0,
            maxZ = positions.maxOf { it.z } + 1.0,
        )
    }

    private fun panelCenter(
        bounds: Bounds,
        face: BuilderConstructionSitePanelFace,
        frontOffset: Double,
    ): Pair<Double, Double> {
        val centerX = (bounds.minX + bounds.maxX) / 2.0
        val centerZ = (bounds.minZ + bounds.maxZ) / 2.0
        return when (face) {
            BuilderConstructionSitePanelFace.MIN_X -> bounds.minX - frontOffset to centerZ
            BuilderConstructionSitePanelFace.MAX_X -> bounds.maxX + frontOffset to centerZ
            BuilderConstructionSitePanelFace.MIN_Z -> centerX to bounds.minZ - frontOffset
            BuilderConstructionSitePanelFace.MAX_Z -> centerX to bounds.maxZ + frontOffset
        }
    }
}

/** Global, non-persistent construction-site outline and right-click control panel. */
internal class BuilderConstructionSiteDisplayManager(
    private val plugin: JavaPlugin,
    private val settings: BuilderConstructionSiteDisplaySettings,
    private val messages: LocalizedMiniMessage,
    private val projectLookup: (UUID) -> BuilderConstructionProjectRecord?,
    private val onInspect: (Player, BuilderConstructionProjectRecord) -> Unit,
    private val displays: PaperPacketDisplays = PaperPacketDisplays(plugin, "construction-sites"),
    private val space: PaperSpatialSpace = PaperSpatialSpace(),
    private val planningTasks: BuilderPlanningTasks,
) : Listener, AutoCloseable {
    /** A new immutable source rotates native input IDs; progress updates retain this identity. */
    private class SitePanelContext
    private class ModelCalculation(val source: BuilderConstructionProjectRecord)

    private data class Scene(
        var project: BuilderConstructionProjectRecord,
        var model: BuilderConstructionSiteDisplayModel,
        val outline: PacketDisplayScene<Int>,
        val plaque: BuilderWorldPlaque?,
        var context: SitePanelContext = SitePanelContext(),
    )

    private val projectKey = NamespacedKey(plugin, "construction_site_project")
    private val scenes = mutableMapOf<UUID, Scene>()
    private val pendingModels = mutableMapOf<UUID, ModelCalculation>()
    private val reconciling = mutableSetOf<UUID>()
    private var closed = false
    private val audience = SceneAudience.Public()
    private val outlineStyle = SceneDisplayStyle(
        brightness = Display.Brightness(15, 15),
        viewRange = (settings.viewRange / 64.0).toFloat(),
        glowing = true,
        glowColor = settings.glowColor,
    )
    private val controls = PaperSpatialControls(plugin, displays, object : SpatialControlController<SitePanelContext, Unit>() {
        override fun current(player: Player, scene: SpatialSceneKey): SpatialControlFrame<SitePanelContext, Unit>? {
            val projectId = UUID.fromString(scene.value)
            val shown = scenes[projectId] ?: return null
            val project = projectLookup(projectId)?.takeUnless(BuilderConstructionProjectRecord::terminal)
            if (project == null) { remove(projectId); return null }
            if (closed || !sameSource(shown.project, project)) return null
            return shown.plaque?.controls(shown.context)
        }

        override fun activate(player: Player, scene: SpatialSceneKey, context: SitePanelContext, action: Unit) {
            val fresh = current(player, scene) ?: return
            if (fresh.context !== context) return
            val project = projectLookup(UUID.fromString(scene.value))?.takeUnless(BuilderConstructionProjectRecord::terminal) ?: return
            onInspect(player, project)
        }

        override fun inputFailed(player: Player, scene: SpatialSceneKey, context: SitePanelContext, failure: Exception) {
            val projectId = UUID.fromString(scene.value)
            if (scenes[projectId]?.context === context) remove(projectId)
        }
    })

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
        removeLoadedOrphans()
    }

    fun upsert(project: BuilderConstructionProjectRecord) {
        if (closed || !settings.enabled || project.terminal) {
            remove(project.projectId)
            return
        }
        val previous = scenes[project.projectId]
        if (previous != null && sameSource(previous.project, project)) {
            pendingModels.remove(project.projectId)
            applyModel(project, previous.model)
            return
        }
        if (pendingModels[project.projectId]?.let { sameSource(it.source, project) } == true) return
        val calculation = ModelCalculation(project)
        pendingModels[project.projectId] = calculation
        planningTasks.compute { BuilderConstructionSiteDisplayLayout.create(project, settings) }.thenCompose { model ->
            planningTasks.onMain {
                if (!closed && pendingModels[project.projectId] === calculation) {
                    pendingModels.remove(project.projectId)
                    val current = projectLookup(project.projectId)?.takeUnless(BuilderConstructionProjectRecord::terminal)
                    when {
                        current == null -> remove(project.projectId)
                        sameSource(project, current) -> applyModel(current, model)
                        else -> upsert(current)
                    }
                }
            }
        }.whenComplete { _, failure ->
            if (failure != null) planningTasks.onMain {
                if (!closed && pendingModels[project.projectId] === calculation) {
                    remove(project.projectId)
                    val cause = if (failure is CompletionException) failure.cause ?: failure else failure
                    if (cause !is CancellationException) {
                        warn("Builder construction site display failed for {}: {}", project.projectId, cause.message)
                    }
                }
            }
        }
    }

    private fun applyModel(project: BuilderConstructionProjectRecord, model: BuilderConstructionSiteDisplayModel) {
        if (!reconciling.add(project.projectId)) return
        try {
            runCatching { upsertChecked(project.validatedProgress(), model) }.onFailure { failure ->
                remove(project.projectId)
                warn("Builder construction site display failed for {}: {}", project.projectId, failure.message)
            }
        } finally {
            reconciling.remove(project.projectId)
        }
    }

    fun remove(projectId: UUID) {
        pendingModels.remove(projectId)
        scenes.remove(projectId)?.let { scene ->
            try {
                controls.clear(SpatialSceneKey(projectId.toString()))
            } finally {
                try { scene.plaque?.close() } finally { scene.outline.close() }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onChunkLoad(event: ChunkLoadEvent) {
        if (closed || !settings.enabled) return
        val chunk = event.chunk
        // Migration-only ownership tag: Core's current native targets use its own registry.
        removeChunkOrphans(chunk)
        val projects = scenes.entries
            .asSequence()
            .filter { (_, scene) ->
                scene.model.worldId == chunk.world.uid && scene.model.touches(chunk.x, chunk.z)
            }
            .mapNotNull { (projectId, _) -> projectLookup(projectId) }
            .filterNot(BuilderConstructionProjectRecord::terminal)
            .toList()
        projects.forEach(::upsert)
    }

    private fun upsertChecked(project: BuilderConstructionProjectRecord, model: BuilderConstructionSiteDisplayModel) {
        val previous = scenes[project.projectId]
        val unchangedSource = previous != null && sameSource(previous.project, project)
        val world = Bukkit.getWorld(model.worldId) ?: return
        val scene = previous ?: Scene(
            project,
            model,
            PacketDisplayScene(displays),
            if (settings.panelEnabled) BuilderWorldPlaque(displays, space, BuilderWorldPlaqueStyle(
                settings.panelInteractionWidth, settings.panelInteractionHeight, settings.panelLineWidth,
                settings.panelBackgroundColor, settings.glowColor, settings.viewRange,
            ), audience) else null,
        ).also { created ->
            scenes[project.projectId] = created
            created.plaque?.let { plaque ->
                plaque.refreshWith {
                    if (scenes[project.projectId] === created && !closed) {
                        runCatching {
                            val current = projectLookup(project.projectId)?.takeUnless(BuilderConstructionProjectRecord::terminal)
                            if (current == null) remove(project.projectId)
                            else if (sameSource(created.project, current)) {
                                plaque.refresh()
                                controls.apply(SpatialSceneKey(project.projectId.toString()), plaque.controls(created.context))
                            }
                        }.onFailure { failure ->
                            remove(project.projectId)
                            warn("Builder construction site refresh failed for {}: {}", project.projectId, failure.message)
                        }
                    }
                }
            }
        }
        if (!unchangedSource) scene.context = SitePanelContext()
        scene.project = project
        scene.model = model
        if (!unchangedSource || !scene.outline.isValid()) {
            val blockData = settings.outlineMaterial.createBlockData()
            scene.outline.apply(SceneFrame(
                if (settings.outlineEnabled) model.edges.mapIndexed { index, edge -> ScenePart.Block(
                    index,
                    ScenePose(Location(world, edge.x, edge.y, edge.z), Transformation(
                        Vector3f(), Quaternionf(), Vector3f(edge.scaleX, edge.scaleY, edge.scaleZ), Quaternionf(),
                    )),
                    outlineStyle,
                    blockData,
                ) } else emptyList(),
                audience,
            ))
        }
        scene.plaque?.let { plaque ->
            plaque.update(Location(world, model.panelX, model.panelY, model.panelZ, model.panelYaw, 0f), panelText(project))
            controls.apply(SpatialSceneKey(project.projectId.toString()), plaque.controls(scene.context))
        }
    }

    private fun sameSource(first: BuilderConstructionProjectRecord, second: BuilderConstructionProjectRecord): Boolean =
        first.plan === second.plan && first.steps === second.steps && first.siteAnchor == second.siteAnchor &&
            first.sitePanelFace == second.sitePanelFace

    private fun panelText(project: BuilderConstructionProjectRecord): Component {
        val title = project.projectTitle ?: project.plan.bookBuildingId
        val name = title?.let { messages.literal(BuildBookItems.compactTitle(it, 24)) }
            ?: messages.render("construction.site.unknown-name", settings.defaultLocale)
        val percent = (project.cursor.toLong() * 100L / project.steps.size).toInt()
        return messages.render(
            "construction.site.panel",
            settings.defaultLocale,
            mapOf(
                "name" to name,
                "count" to messages.literal(project.cursor),
                "total" to messages.literal(project.steps.size),
                "percent" to messages.literal(percent),
            ),
        )
    }

    private fun BuilderConstructionSiteDisplayModel.touches(chunkX: Int, chunkZ: Int): Boolean =
        edges.any { edge -> edge.x.chunkCoordinate() == chunkX && edge.z.chunkCoordinate() == chunkZ } ||
            (panelX.chunkCoordinate() == chunkX && panelZ.chunkCoordinate() == chunkZ)

    private fun Double.chunkCoordinate(): Int = floor(this).toInt() shr 4

    private fun removeLoadedOrphans() {
        val worlds = Bukkit.getWorlds().toList()
        var worldIndex = 0
        var chunks = emptyArray<Chunk>()
        var chunkIndex = 0
        var entities = emptyArray<Entity>()
        var entityIndex = 0
        planningTasks.capture({ !closed }) {
            when {
                entityIndex < entities.size -> removeOrphan(entities[entityIndex++])
                chunkIndex < chunks.size -> {
                    val chunk = chunks[chunkIndex++]
                    entities = if (chunk.world.isChunkLoaded(chunk.x, chunk.z)) chunk.entities else emptyArray()
                    entityIndex = 0
                }
                worldIndex < worlds.size -> {
                    chunks = worlds[worldIndex++].loadedChunks
                    chunkIndex = 0
                }
                else -> return@capture true
            }
            false
        }
    }

    private fun removeChunkOrphans(chunk: Chunk) {
        var entities: Array<Entity>? = null
        var cursor = 0
        planningTasks.capture({ !closed }) {
            val captured = entities
            if (captured == null) {
                entities = if (chunk.world.isChunkLoaded(chunk.x, chunk.z)) chunk.entities else emptyArray()
                false
            } else if (cursor < captured.size) {
                removeOrphan(captured[cursor++])
                false
            } else true
        }
    }

    private fun removeOrphan(entity: Entity) {
        if (entity.isValid && entity.persistentDataContainer.has(projectKey, PersistentDataType.STRING)) entity.remove()
    }

    override fun close() {
        if (closed) return
        closed = true
        HandlerList.unregisterAll(this)
        pendingModels.clear()
        scenes.keys.toList().forEach(::remove)
        controls.close()
        displays.close()
    }
}
