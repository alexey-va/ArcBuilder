package ru.arc.buildertools

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
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.autobuild.BuildBookPreviewBridge
import ru.arc.autobuild.ConstructionSite
import ru.arc.util.BlockUtils.rotateBlockData
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
}

/** Player-only native BlockDisplay scenes; no packets, fake blocks, or particles. */
internal class BuilderBlockDisplayRenderer(
    private val plugin: JavaPlugin,
    private val maxPlanDisplays: Int,
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

    private data class Scene(val worldId: UUID, val signature: Int, val entities: List<Entity>)
    private val scenes = mutableMapOf<Pair<UUID, Layer>, Scene>()

    init {
        require(maxPlanDisplays in 32..512)
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
        val visible = plan.changes.asSequence().filter { change ->
            val dx = change.position.x + .5 - eye.x
            val dy = change.position.y + .5 - eye.y
            val dz = change.position.z + .5 - eye.z
            dx * dx + dy * dy + dz * dz <= 64.0 * 64.0
        }.toList()
        val step = kotlin.math.ceil(visible.size / maxPlanDisplays.toDouble()).toInt().coerceAtLeast(1)
        val sampled = visible.asSequence().filterIndexed { index, _ -> index % step == 0 }.take(maxPlanDisplays).toList()
        val specs = buildList {
            sampled.forEach { change ->
                val after = Bukkit.createBlockData(change.afterBlockData)
                val removal = after.material.isAir
                add(
                    DisplaySpec(
                        x = change.position.x + .04,
                        y = change.position.y + .04,
                        z = change.position.z + .04,
                        blockData = if (removal) Material.RED_STAINED_GLASS.createBlockData() else after,
                        scaleX = .92f,
                        scaleY = .92f,
                        scaleZ = .92f,
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

    override fun open(site: ConstructionSite) = renderBook(site)
    override fun refresh(site: ConstructionSite) = renderBook(site)
    override fun close(playerId: UUID) = remove(playerId, Layer.BOOK)

    private fun renderBook(site: ConstructionSite) {
        if (site.player.world.uid != site.world.uid) {
            remove(site.player.uniqueId, Layer.BOOK)
            return
        }
        val specs = buildList {
            val positions = site.relativePositionsBottomUp().mapNotNull { relative ->
                val data = runCatching {
                    rotateBlockData(
                        org.bukkit.Bukkit.createBlockData(
                            com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(site.building.getBlock(relative, site.fullRotation)).asString,
                        ),
                        site.fullRotation,
                    )
                }.getOrNull()?.takeUnless { it.material.isAir } ?: return@mapNotNull null
                Triple(site.worldLocation(relative), relative, data)
            }.toList()
            val step = kotlin.math.ceil(positions.size / maxPlanDisplays.toDouble()).toInt().coerceAtLeast(1)
            positions.asSequence().filterIndexed { index, _ -> index % step == 0 }.take(maxPlanDisplays).forEach { (location, _, data) ->
                add(
                    DisplaySpec(
                        location.blockX + .04,
                        location.blockY + .04,
                        location.blockZ + .04,
                        data,
                        .92f,
                        .92f,
                        .92f,
                        glow = Color.fromRGB(255, 177, 66),
                    ),
                )
            }
            positions.map { (location, _, _) ->
                BuilderBlockPos(site.world.uid, location.blockX, location.blockY, location.blockZ)
            }.takeIf(List<*>::isNotEmpty)?.let {
                addAll(bounds(it, Material.ORANGE_STAINED_GLASS, Color.fromRGB(255, 177, 66)))
            }
        }
        replace(site.player, Layer.BOOK, specs)
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
        val signature = specs.hashCode()
        if (scenes[key]?.let { it.worldId == player.world.uid && it.signature == signature } == true) return
        remove(player.uniqueId, layer)
        if (specs.isEmpty() || !player.isOnline) return
        val spawned = mutableListOf<Entity>()
        try {
            specs.forEach { spec ->
                val display = player.world.spawn(Location(player.world, spec.x, spec.y, spec.z), BlockDisplay::class.java) { entity ->
                    entity.block = spec.blockData
                    entity.setVisibleByDefault(false)
                    entity.isPersistent = false
                    entity.isInvulnerable = true
                    entity.setGravity(false)
                    entity.isGlowing = true
                    entity.glowColorOverride = spec.glow
                    entity.brightness = Display.Brightness(15, 15)
                    entity.viewRange = 1.0f
                    entity.transformation = Transformation(
                        Vector3f(spec.translateX, spec.translateY, spec.translateZ),
                        Quaternionf(),
                        Vector3f(spec.scaleX, spec.scaleY, spec.scaleZ),
                        Quaternionf(),
                    )
                }
                player.showEntity(plugin, display)
                spawned += display
            }
            scenes[key] = Scene(player.world.uid, signature, spawned)
        } catch (failure: Throwable) {
            spawned.forEach(Entity::remove)
            throw failure
        }
    }

    private fun remove(playerId: UUID, layer: Layer) {
        scenes.remove(playerId to layer)?.entities?.forEach(Entity::remove)
    }

    override fun clearPlayer(playerId: UUID) = Layer.entries.forEach { remove(playerId, it) }

    override fun close() {
        scenes.values.flatMap(Scene::entities).forEach(Entity::remove)
        scenes.clear()
    }
}
