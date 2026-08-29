package ru.arc.buildertools

import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.Container
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID

internal class BuilderConstructionResources(
    private val containerRadius: Int,
    private val onlineRange: Double,
    private val worldProvider: (UUID) -> World?,
    private val onlinePlayerProvider: (UUID) -> Player?,
    private val canOpenContainer: (UUID, Block) -> Boolean,
) {
    init {
        require(containerRadius in 1..16) { "Builder construction container radius is invalid" }
        require(onlineRange.isFinite() && onlineRange in 1.0..128.0) {
            "Builder construction online inventory range is invalid"
        }
    }

    fun removeInput(
        playerId: UUID,
        project: BuilderConstructionProjectRecord,
        input: BuilderItemAmount,
    ): Boolean = exchange(
        sources = listOfNotNull(nearbyPlayerSource(playerId, project)) + containerSources(playerId, project),
        amount = input,
        insert = false,
    )

    fun storeOutput(
        playerId: UUID,
        project: BuilderConstructionProjectRecord,
        output: BuilderItemAmount,
    ): Boolean = exchange(
        sources = containerSources(playerId, project) + listOfNotNull(nearbyPlayerSource(playerId, project)),
        amount = output,
        insert = true,
    )

    fun returnInput(
        playerId: UUID,
        project: BuilderConstructionProjectRecord,
        input: BuilderItemAmount,
    ): Boolean = storeOutput(playerId, project, input)

    private fun exchange(
        sources: List<ResourceInventory>,
        amount: BuilderItemAmount,
        insert: Boolean,
    ): Boolean {
        if (sources.isEmpty()) return false
        val (prototype, requested) = BuilderItemCodec.decode(amount.validated())
        val snapshots = sources.map { source ->
            source.inventory.storageContents.map { it?.clone() }.toMutableList()
        }
        var remaining = requested
        snapshots.forEach { contents ->
            if (remaining == 0) return@forEach
            remaining = if (insert) {
                insertAvailable(contents, prototype, remaining)
            } else {
                removeAvailable(contents, prototype, remaining)
            }
        }
        if (remaining != 0) return false
        if (sources.any { !it.stillUsable() }) return false
        sources.indices.forEach { index ->
            sources[index].inventory.storageContents = snapshots[index].toTypedArray()
        }
        return true
    }

    private fun nearbyPlayerSource(
        playerId: UUID,
        project: BuilderConstructionProjectRecord,
    ): ResourceInventory? {
        val player = onlinePlayerProvider(playerId)?.takeIf(Player::isOnline) ?: return null
        if (!isNearProject(player, project)) return null
        return ResourceInventory(player.inventory) {
            val current = onlinePlayerProvider(playerId)
            current != null && current.isOnline && current.uniqueId == playerId && isNearProject(current, project)
        }
    }

    private fun isNearProject(player: Player, project: BuilderConstructionProjectRecord): Boolean {
        val bounds = ProjectBounds.from(project)
        if (player.world.uid != bounds.worldId) return false
        val location = player.location
        val dx = distanceToRange(location.x, bounds.minX.toDouble(), bounds.maxX + 1.0)
        val dy = distanceToRange(location.y, bounds.minY.toDouble(), bounds.maxY + 1.0)
        val dz = distanceToRange(location.z, bounds.minZ.toDouble(), bounds.maxZ + 1.0)
        return dx * dx + dy * dy + dz * dz <= onlineRange * onlineRange
    }

    private fun containerSources(
        playerId: UUID,
        project: BuilderConstructionProjectRecord,
    ): List<ResourceInventory> {
        val bounds = ProjectBounds.from(project)
        val world = worldProvider(bounds.worldId) ?: return emptyList()
        val seen = Collections.newSetFromMap(IdentityHashMap<Inventory, Boolean>())
        val sources = mutableListOf<ResourceInventory>()
        val minY = (bounds.minY - containerRadius).coerceAtLeast(world.minHeight)
        val maxY = (bounds.maxY + containerRadius).coerceAtMost(world.maxHeight - 1)
        for (x in bounds.minX - containerRadius..bounds.maxX + containerRadius) {
            for (y in minY..maxY) {
                for (z in bounds.minZ - containerRadius..bounds.maxZ + containerRadius) {
                    if (bounds.contains(x, y, z)) continue
                    if (!world.isChunkLoaded(x shr 4, z shr 4)) continue
                    val block = world.getBlockAt(x, y, z)
                    if (block.type !in SUPPORTED_CONTAINERS) continue
                    val state = block.state as? Container ?: continue
                    if (!canOpenContainer(playerId, block)) continue
                    val inventory = state.inventory
                    if (!seen.add(inventory)) continue
                    sources += ResourceInventory(inventory) {
                        block.type in SUPPORTED_CONTAINERS &&
                            block.state is Container &&
                            canOpenContainer(playerId, block)
                    }
                }
            }
        }
        return sources
    }

    private data class ResourceInventory(
        val inventory: Inventory,
        val stillUsable: () -> Boolean,
    )

    private data class ProjectBounds(
        val worldId: UUID,
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int,
        val minZ: Int,
        val maxZ: Int,
    ) {
        fun contains(x: Int, y: Int, z: Int): Boolean =
            x in minX..maxX && y in minY..maxY && z in minZ..maxZ

        companion object {
            fun from(project: BuilderConstructionProjectRecord): ProjectBounds {
                val positions = project.steps.map { it.change.position }
                val worldIds = positions.map(BuilderBlockPos::worldId).toSet()
                require(worldIds.size == 1) { "Builder construction project cannot cross worlds" }
                return ProjectBounds(
                    worldId = worldIds.single(),
                    minX = positions.minOf(BuilderBlockPos::x),
                    maxX = positions.maxOf(BuilderBlockPos::x),
                    minY = positions.minOf(BuilderBlockPos::y),
                    maxY = positions.maxOf(BuilderBlockPos::y),
                    minZ = positions.minOf(BuilderBlockPos::z),
                    maxZ = positions.maxOf(BuilderBlockPos::z),
                )
            }
        }
    }

    private companion object {
        val SUPPORTED_CONTAINERS = setOf(Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL)

        fun distanceToRange(value: Double, minimum: Double, maximum: Double): Double = when {
            value < minimum -> minimum - value
            value > maximum -> value - maximum
            else -> 0.0
        }

        fun removeAvailable(contents: MutableList<ItemStack?>, prototype: ItemStack, requested: Int): Int {
            var remaining = requested
            for (index in contents.indices) {
                val current = contents[index] ?: continue
                if (!current.isSimilar(prototype)) continue
                val taken = minOf(remaining, current.amount)
                current.amount -= taken
                remaining -= taken
                if (current.amount <= 0) contents[index] = null
                if (remaining == 0) return 0
            }
            return remaining
        }

        fun insertAvailable(contents: MutableList<ItemStack?>, prototype: ItemStack, requested: Int): Int {
            var remaining = requested
            for (current in contents.filterNotNull()) {
                if (!current.isSimilar(prototype) || current.amount >= current.maxStackSize) continue
                val inserted = minOf(remaining, current.maxStackSize - current.amount)
                current.amount += inserted
                remaining -= inserted
                if (remaining == 0) return 0
            }
            for (index in contents.indices) {
                if (contents[index] != null && !contents[index]!!.type.isAir) continue
                val inserted = minOf(remaining, prototype.maxStackSize)
                contents[index] = prototype.clone().also { it.amount = inserted }
                remaining -= inserted
                if (remaining == 0) return 0
            }
            return remaining
        }
    }
}
