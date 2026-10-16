package ru.arc.autobuild

import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
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
    private val previews = ConcurrentHashMap<UUID, ConstructionSite>()
    @Volatile private var previewBridge: BuildBookPreviewBridge? = null
    private var dispatch: ((() -> Unit) -> Unit)? = null
    private var transformFailed: ((Player, Throwable) -> Unit)? = null
    private val transforms = mutableMapOf<UUID, Any>()

    internal fun installPreviewBridge(
        bridge: BuildBookPreviewBridge?,
        runSync: (((() -> Unit) -> Unit))? = null,
        onFailure: ((Player, Throwable) -> Unit)? = null,
    ) {
        previewBridge = bridge
        dispatch = runSync
        transformFailed = onFailure
        if (bridge == null) transforms.clear()
    }

    private fun refreshTransform(site: ConstructionSite, opening: Boolean = false, expectedItem: ItemStack? = null) {
        val playerId = site.player.uniqueId
        val token = Any()
        transforms[playerId] = token
        site.transforming = true
        val data = site.bookData
        val worldId = site.world.uid
        val mode = site.player.gameMode
        val heldSlot = site.player.inventory.heldItemSlot
        val sourceSlot = if (expectedItem != null) heldSlot else
            (sequenceOf(heldSlot) + site.player.inventory.contents.indices.asSequence().filter { it != heldSlot }).firstOrNull {
            val item = site.player.inventory.getItem(it)
            item != null && BuildBookCodec.matches(item, data)
        }
        val source = expectedItem?.clone() ?: sourceSlot?.let { site.player.inventory.getItem(it)?.clone() }
        if (sourceSlot == null || source == null) { closePreview(playerId); return }
        site.building.transform(site.fullRotation, site.mirrored).whenComplete { view, failure ->
            dispatch?.invoke continuation@{
                if (transforms[playerId] !== token || previews[playerId] !== site) return@continuation
                transforms.remove(playerId)
                if (!site.player.isOnline || site.player.world.uid != worldId || site.player.gameMode != mode ||
                    site.player.inventory.heldItemSlot != heldSlot || site.bookData != data ||
                    site.expiresAtMillis <= System.currentTimeMillis() || site.player.inventory.getItem(sourceSlot) != source) {
                    closePreview(playerId)
                    return@continuation
                }
                if (failure != null || view == null) {
                    closePreview(playerId)
                    transformFailed?.invoke(site.player, failure ?: IllegalStateException("Schematic transform is missing"))
                    return@continuation
                }
                site.view = view
                site.transforming = false
                if (opening) previewBridge?.open(site) else previewBridge?.refresh(site)
            }
        }
    }

    @JvmStatic fun hasExactOpenPreview(player: Player, expected: BuildBookData): Boolean =
        pending(player.uniqueId)?.let { !it.transforming && it.isExactOpenPreview(player, expected) } == true

    @JvmStatic internal fun updatePendingTransform(player: Player, next: BuildBookData, expectedItem: ItemStack? = null): PreviewTransformUpdateResult {
        val site = pending(player.uniqueId) ?: return PreviewTransformUpdateResult.NO_PREVIEW
        val result = site.update(next)
        if (result == PreviewTransformUpdateResult.UPDATED) refreshTransform(site, expectedItem = expectedItem)
        return result
    }

    internal fun openPreview(
        player: Player,
        location: Location,
        data: BuildBookData,
        expiresAtMillis: Long,
        maxPlacementOffset: Int,
        building: Building,
        rotation: Int = rotationFromYaw(player.yaw),
    ): ConstructionSite {
        val center = location.block.location
        val site = ConstructionSite(
            building,
            center,
            player,
            rotation,
            player.world,
            data,
            expiresAtMillis,
            maxPlacementOffset,
        )
        previews.put(player.uniqueId, site)?.let { previewBridge?.close(player.uniqueId) }
        refreshTransform(site, opening = true)
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
            initialView = snapshot.view,
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
        refreshTransform(site)
        return site
    }

    internal fun expirePreviews(nowMillis: Long): List<UUID> = previews.entries
        .filter { (_, site) -> site.expiresAtMillis <= nowMillis }
        .mapNotNull { (playerId, site) ->
            if (previews.remove(playerId, site)) {
                transforms.remove(playerId)
                previewBridge?.close(playerId)
                playerId
            } else {
                null
            }
        }

    internal fun invalidatePendingTransform(playerId: UUID) {
        if (previews[playerId]?.transforming == true) closePreview(playerId)
    }

    internal fun closePreview(playerId: UUID): Boolean {
        transforms.remove(playerId)
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
        if (previews.remove(playerId, site)) {
            transforms.remove(playerId)
            previewBridge?.close(playerId)
        }
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
