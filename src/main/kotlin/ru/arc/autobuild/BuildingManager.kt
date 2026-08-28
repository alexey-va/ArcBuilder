package ru.arc.autobuild

import org.bukkit.Location
import org.bukkit.entity.Player
import ru.arc.ARC
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal interface BuildBookPreviewBridge {
    fun open(site: ConstructionSite)
    fun refresh(site: ConstructionSite)
    fun close(playerId: UUID)
}

object BuildingManager {
    private val buildings = ConcurrentHashMap<String, Building>()
    private val previews = ConcurrentHashMap<UUID, ConstructionSite>()
    @Volatile private var previewBridge: BuildBookPreviewBridge? = null

    internal fun installPreviewBridge(bridge: BuildBookPreviewBridge?) {
        previewBridge = bridge
    }

    @JvmStatic fun addBuilding(building: Building) { buildings[building.fileName] = building }

    @JvmStatic fun getBuilding(fileName: String): Building? {
        buildings[fileName]?.let { return it }
        if (!fileName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,159}"))) return null
        val path = BuilderStoragePaths.schematicsRoot().resolve(fileName)
        if (!Files.isRegularFile(path)) return null
        return Building(fileName).also { buildings.putIfAbsent(fileName, it) }
    }

    @JvmStatic fun hasExactOpenPreview(player: Player, expected: BuildBookData): Boolean =
        previews[player.uniqueId]?.isExactOpenPreview(player, expected) == true

    @JvmStatic internal fun updatePendingTransform(player: Player, next: BuildBookData): PreviewTransformUpdateResult {
        val site = previews[player.uniqueId] ?: return PreviewTransformUpdateResult.NO_PREVIEW
        val result = site.update(next)
        if (result == PreviewTransformUpdateResult.UPDATED) previewBridge?.refresh(site)
        return result
    }

    internal fun openPreview(player: Player, location: Location, data: BuildBookData): ConstructionSite? {
        val building = getBuilding(data.buildingId) ?: return null
        val center = location.block.location
        val site = ConstructionSite(building, center, player, rotationFromYaw(player.yaw), player.world, data)
        previews.put(player.uniqueId, site)?.let { previewBridge?.close(player.uniqueId) }
        previewBridge?.open(site)
        return site
    }

    internal fun closePreview(playerId: UUID) {
        previews.remove(playerId)
        previewBridge?.close(playerId)
    }

    internal fun pending(playerId: UUID): ConstructionSite? = previews[playerId]

    @JvmStatic fun rotationFromYaw(yaw: Float): Int {
        val adjusted = (((yaw + 180f) % 360f) + 360f) % 360f
        return when {
            adjusted > 315 || adjusted <= 45 -> 0
            adjusted <= 135 -> 90
            adjusted <= 225 -> 180
            else -> 270
        }
    }
}
