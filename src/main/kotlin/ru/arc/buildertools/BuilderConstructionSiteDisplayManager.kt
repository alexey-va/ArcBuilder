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

internal data class BuilderConstructionSiteDisplaySettings(
    val enabled: Boolean,
    val outlineEnabled: Boolean,
    val outlineMaterial: Material,
    val outlineThickness: Float,
    val glowColor: Color,
    val panelEnabled: Boolean,
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
        val minX = positions.minOf { it.x }.toDouble()
        val minY = positions.minOf { it.y }.toDouble()
        val minZ = positions.minOf { it.z }.toDouble()
        val maxX = positions.maxOf { it.x } + 1.0
        return BuilderConstructionSiteDisplayModel(
            worldId = worldId,
            edges = BuilderDisplayGeometry.bounds(positions, settings.outlineThickness),
            panelX = (minX + maxX) / 2.0,
            panelY = minY + settings.panelHeightOffset,
            panelZ = minZ - settings.panelFrontOffset,
        )
    }
}

/** Global, non-persistent construction-site outline and right-click information panel. */
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
        val panel: TextDisplay?,
    )

    private val projectKey = NamespacedKey(plugin, "construction_site_project")
    private val scenes = mutableMapOf<UUID, Scene>()
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
        runCatching { upsertChecked(project.validated()) }
            .onFailure { failure ->
                remove(project.projectId)
                warn("Builder construction site display failed for {}: {}", project.projectId, failure.message)
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

    private fun upsertChecked(project: BuilderConstructionProjectRecord) {
        val model = BuilderConstructionSiteDisplayLayout.create(project, settings)
        val world = Bukkit.getWorld(model.worldId) ?: return
        val existing = scenes[project.projectId]
            ?.takeIf { it.model == model && it.entities.all(Entity::isValid) }
        if (existing != null) {
            existing.panel?.text(panelText(project))
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
            var panel: TextDisplay? = null
            if (settings.panelEnabled) {
                val location = Location(world, model.panelX, model.panelY, model.panelZ)
                panel = world.spawn(location, TextDisplay::class.java) { display ->
                    configure(display, project.projectId)
                    display.text(panelText(project))
                    display.billboard = Display.Billboard.CENTER
                    display.lineWidth = settings.panelLineWidth
                    display.backgroundColor = settings.panelBackgroundColor
                    display.isShadowed = true
                    display.isSeeThrough = false
                    display.alignment = TextDisplay.TextAlignment.CENTER
                    display.displayWidth = settings.panelInteractionWidth
                    display.displayHeight = settings.panelInteractionHeight
                }
                spawned += panel
                spawned += world.spawn(
                    location.clone().subtract(0.0, settings.panelInteractionHeight / 2.0, 0.0),
                    Interaction::class.java,
                ) { interaction ->
                    configureEntity(interaction, project.projectId)
                    interaction.interactionWidth = settings.panelInteractionWidth
                    interaction.interactionHeight = settings.panelInteractionHeight
                    interaction.isResponsive = true
                }
            }
            scenes[project.projectId] = Scene(model, spawned.toList(), panel)
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
