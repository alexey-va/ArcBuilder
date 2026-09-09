package ru.arc.buildertools

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.autobuild.BuildBookItems
import ru.arc.text.LocalizedMiniMessage
import ru.arc.util.Logging.warn
import java.util.UUID
import kotlin.math.floor

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

internal object BuilderConstructionSitePanelOrientation {
    const val SCALE = 2f

    fun apply(display: TextDisplay, yaw: Float) {
        display.billboard = Display.Billboard.FIXED
        display.setRotation(yaw, 0f)
        display.transformation = Transformation(
            Vector3f(0f, 0f, .02f), Quaternionf(), Vector3f(SCALE), Quaternionf(),
        )
    }
}

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
        val panel = panelPlacement(project.siteAnchor?.let(::listOf) ?: positions, face, settings.panelHeightOffset, settings.panelFrontOffset)
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
    ): BuilderSitePanelPlacement {
        val bounds = bounds(positions)
        val (x, z) = panelCenter(bounds, face, frontOffset)
        return BuilderSitePanelPlacement(x, bounds.minY + heightOffset, z, face.yaw)
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
) : Listener, AutoCloseable {
    private data class Scene(
        val model: BuilderConstructionSiteDisplayModel,
        val entities: List<Entity>,
        val panels: List<TextDisplay>,
    )

    private val projectKey = NamespacedKey(plugin, "construction_site_project")
    private val scenes = mutableMapOf<UUID, Scene>()
    private val reconciling = mutableSetOf<UUID>()
    private var closed = false

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
        removeLoadedOrphans()
    }

    fun upsert(project: BuilderConstructionProjectRecord) {
        if (closed || !settings.enabled || project.terminal) {
            remove(project.projectId)
            return
        }
        if (!reconciling.add(project.projectId)) return
        try {
            runCatching { upsertChecked(project.validated()) }
                .onFailure { failure ->
                    remove(project.projectId)
                    warn("Builder construction site display failed for {}: {}", project.projectId, failure.message)
                }
        } finally {
            reconciling.remove(project.projectId)
        }
    }

    fun remove(projectId: UUID) {
        scenes.remove(projectId)?.entities?.forEach(Entity::remove)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEntityEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val rawProjectId = event.rightClicked.persistentDataContainer.get(projectKey, PersistentDataType.STRING)
            ?: return
        val projectId = runCatching { UUID.fromString(rawProjectId) }.getOrNull() ?: return
        event.isCancelled = true
        val project = projectLookup(projectId)?.takeUnless(BuilderConstructionProjectRecord::terminal)
        if (project == null) {
            remove(projectId)
            return
        }
        onInspect(event.player, project)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onChunkLoad(event: ChunkLoadEvent) {
        if (closed || !settings.enabled) return
        val chunk = event.chunk
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

    private fun upsertChecked(project: BuilderConstructionProjectRecord) {
        val model = BuilderConstructionSiteDisplayLayout.create(project, settings)
        val world = Bukkit.getWorld(model.worldId) ?: return
        val existing = scenes[project.projectId]
            ?.takeIf { it.model == model && it.entities.all(Entity::isValid) }
        if (existing != null) {
            existing.panels.forEach { it.text(panelText(project)) }
            return
        }
        remove(project.projectId)
        val spawned = mutableListOf<Entity>()
        try {
            if (settings.outlineEnabled) {
                val blockData = settings.outlineMaterial.createBlockData()
                model.edges.forEach { edge ->
                    spawned += world.spawn(Location(world, edge.x, edge.y, edge.z), BlockDisplay::class.java) { display ->
                        configure(display, project.projectId)
                        display.block = blockData
                        display.transformation = Transformation(
                            Vector3f(),
                            Quaternionf(),
                            Vector3f(edge.scaleX, edge.scaleY, edge.scaleZ),
                            Quaternionf(),
                        )
                    }
                }
            }
            val panels = mutableListOf<TextDisplay>()
            if (settings.panelEnabled) {
                val location = Location(world, model.panelX, model.panelY, model.panelZ)
                for (yaw in listOf(model.panelYaw, model.panelYaw + 180f)) {
                    val panel = world.spawn(location, TextDisplay::class.java) { display ->
                        configure(display, project.projectId)
                        display.text(panelText(project))
                        BuilderConstructionSitePanelOrientation.apply(display, yaw)
                        display.lineWidth = settings.panelLineWidth
                        display.backgroundColor = settings.panelBackgroundColor
                        display.isShadowed = true
                        display.isSeeThrough = false
                        display.alignment = TextDisplay.TextAlignment.CENTER
                        display.displayWidth = settings.panelInteractionWidth * BuilderConstructionSitePanelOrientation.SCALE
                        display.displayHeight = settings.panelInteractionHeight * BuilderConstructionSitePanelOrientation.SCALE
                    }
                    spawned += panel
                    panels += panel
                }
                spawned += world.spawn(
                    location.clone().subtract(0.0, settings.panelInteractionHeight * BuilderConstructionSitePanelOrientation.SCALE / 2.0, 0.0),
                    Interaction::class.java,
                ) { interaction ->
                    configureEntity(interaction, project.projectId)
                    interaction.interactionWidth = settings.panelInteractionWidth * BuilderConstructionSitePanelOrientation.SCALE
                    interaction.interactionHeight = settings.panelInteractionHeight * BuilderConstructionSitePanelOrientation.SCALE
                    interaction.isResponsive = true
                }
            }
            scenes[project.projectId] = Scene(model, spawned.toList(), panels.toList())
        } catch (failure: Throwable) {
            spawned.forEach(Entity::remove)
            throw failure
        }
    }

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

    private fun configure(display: Display, projectId: UUID) {
        configureEntity(display, projectId)
        display.isGlowing = true
        display.glowColorOverride = settings.glowColor
        display.brightness = Display.Brightness(15, 15)
        display.viewRange = (settings.viewRange / 64.0).toFloat()
    }

    private fun configureEntity(entity: Entity, projectId: UUID) {
        entity.isPersistent = false
        entity.isInvulnerable = true
        entity.setGravity(false)
        entity.persistentDataContainer.set(projectKey, PersistentDataType.STRING, projectId.toString())
    }

    private fun BuilderConstructionSiteDisplayModel.touches(chunkX: Int, chunkZ: Int): Boolean =
        edges.any { edge -> edge.x.chunkCoordinate() == chunkX && edge.z.chunkCoordinate() == chunkZ } ||
            (panelX.chunkCoordinate() == chunkX && panelZ.chunkCoordinate() == chunkZ)

    private fun Double.chunkCoordinate(): Int = floor(this).toInt() shr 4

    private fun removeLoadedOrphans() {
        Bukkit.getWorlds().asSequence()
            .flatMap { it.entities.asSequence() }
            .filter { it.persistentDataContainer.has(projectKey, PersistentDataType.STRING) }
            .forEach(Entity::remove)
    }

    override fun close() {
        if (closed) return
        closed = true
        HandlerList.unregisterAll(this)
        scenes.values.flatMap(Scene::entities).forEach(Entity::remove)
        scenes.clear()
    }
}
