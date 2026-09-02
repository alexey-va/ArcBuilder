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

internal sealed interface BuildBookPreviewAdjustment {
    data class Move(val direction: BuildBookPreviewMove) : BuildBookPreviewAdjustment
    data class Rotate(val delta: Int) : BuildBookPreviewAdjustment
    data object ToggleMirror : BuildBookPreviewAdjustment
    data object Reset : BuildBookPreviewAdjustment
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
        pending(player.uniqueId)?.isExactOpenPreview(player, expected) == true

    @JvmStatic internal fun updatePendingTransform(player: Player, next: BuildBookData): PreviewTransformUpdateResult {
        val site = pending(player.uniqueId) ?: return PreviewTransformUpdateResult.NO_PREVIEW
        val result = site.update(next)
        if (result == PreviewTransformUpdateResult.UPDATED) previewBridge?.refresh(site)
        return result
    }

    internal fun openPreview(
        player: Player,
        location: Location,
        data: BuildBookData,
        expiresAtMillis: Long,
        maxPlacementOffset: Int,
    ): ConstructionSite? {
        val building = getBuilding(data.buildingId) ?: return null
        val center = location.block.location
        val site = ConstructionSite(
            building,
            center,
            player,
            rotationFromYaw(player.yaw),
            player.world,
            data,
            expiresAtMillis,
            maxPlacementOffset,
        )
        previews.put(player.uniqueId, site)?.let { previewBridge?.close(player.uniqueId) }
        previewBridge?.open(site)
        return site
    }

    internal fun restorePreview(snapshot: ConstructionSiteSnapshot, nowMillis: Long): ConstructionSite? {
        if (snapshot.expiresAtMillis <= nowMillis || !snapshot.player.isOnline || snapshot.player.world.uid != snapshot.world.uid) {
            return null
        }
        val anchor = snapshot.placement.anchor
        val site = ConstructionSite(
            building = snapshot.building,
            centerBlock = Location(snapshot.world, anchor.x().toDouble(), anchor.y().toDouble(), anchor.z().toDouble()),
            player = snapshot.player,
            rotation = snapshot.placement.rotation,
            world = snapshot.world,
            bookData = snapshot.bookData,
            expiresAtMillis = snapshot.expiresAtMillis,
            maxPlacementOffset = snapshot.placement.maxOffset,
            initialPlacement = snapshot.placement,
        )
        previews.put(snapshot.player.uniqueId, site)?.let { previewBridge?.close(snapshot.player.uniqueId) }
        previewBridge?.open(site)
        return site
    }

    internal fun adjustPendingPlacement(
        player: Player,
        adjustment: BuildBookPreviewAdjustment,
        nowMillis: Long,
    ): ConstructionSite? {
        val site = previews[player.uniqueId] ?: return null
        if (site.expiresAtMillis <= nowMillis) {
            closePreview(player.uniqueId)
            return null
        }
        when (adjustment) {
            is BuildBookPreviewAdjustment.Move -> site.move(adjustment.direction, rotationFromYaw(player.yaw))
            is BuildBookPreviewAdjustment.Rotate -> site.rotate(adjustment.delta)
            BuildBookPreviewAdjustment.ToggleMirror -> site.toggleMirror()
            BuildBookPreviewAdjustment.Reset -> site.resetPlacement()
        }
        previewBridge?.refresh(site)
        return site
    }

    internal fun expirePreviews(nowMillis: Long): List<UUID> = previews.entries
        .filter { (_, site) -> site.expiresAtMillis <= nowMillis }
        .mapNotNull { (playerId, site) ->
            if (previews.remove(playerId, site)) {
                previewBridge?.close(playerId)
                playerId
            } else {
                null
            }
        }

    internal fun closePreview(playerId: UUID): Boolean {
        val removed = previews.remove(playerId) != null
        if (removed) previewBridge?.close(playerId)
        return removed
    }

    internal fun clearPreviews() {
        previews.keys.toList().forEach(::closePreview)
    }

    internal fun pending(playerId: UUID): ConstructionSite? {
        val site = previews[playerId] ?: return null
        if (site.expiresAtMillis > System.currentTimeMillis()) return site
        if (previews.remove(playerId, site)) previewBridge?.close(playerId)
        return null
    }

    internal val pendingCount: Int get() = previews.size

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
