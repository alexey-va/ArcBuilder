package ru.arc.buildertools

import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.Container
import org.bukkit.block.DoubleChest
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import java.util.UUID

internal const val DEFAULT_CONSTRUCTION_MAX_CACHED_CONTAINERS_PER_PROJECT = 256
internal const val DEFAULT_CONSTRUCTION_MAX_RESOLVED_CONTAINERS_PER_CALL = 32

internal class BuilderConstructionResources(
    private val containerRadius: Int,
    private val onlineRange: Double,
    private val worldProvider: (UUID) -> World?,
    private val onlinePlayerProvider: (UUID) -> Player?,
    private val canOpenContainer: (UUID, Block) -> Boolean,
    private val maxContainerProbesPerCall: Int = 512,
    private val maxCachedContainersPerProject: Int = DEFAULT_CONSTRUCTION_MAX_CACHED_CONTAINERS_PER_PROJECT,
    private val maxResolvedContainersPerCall: Int = DEFAULT_CONSTRUCTION_MAX_RESOLVED_CONTAINERS_PER_CALL,
    private val blockProvider: (World, Int, Int, Int) -> Block = World::getBlockAt,
) {
    private val containerScans = mutableMapOf<UUID, ContainerScan>()

    init {
        require(containerRadius in 1..16) { "Builder construction container radius is invalid" }
        require(onlineRange.isFinite() && onlineRange in 1.0..128.0) {
            "Builder construction online inventory range is invalid"
        }
        require(maxContainerProbesPerCall in 1..4_096) {
            "Builder construction container probe budget is invalid"
        }
        require(maxCachedContainersPerProject in 1..4_096) {
            "Builder construction container cache limit is invalid"
        }
        require(maxResolvedContainersPerCall in 1..512) {
            "Builder construction container resolution budget is invalid"
        }
        require(maxResolvedContainersPerCall <= maxCachedContainersPerProject) {
            "Builder construction container resolution budget cannot exceed its cache limit"
        }
    }

    fun removeInput(
        playerId: UUID,
        project: BuilderConstructionProjectRecord,
        input: BuilderItemAmount,
    ): Boolean = prepareInput(playerId, project, input)?.let { mutation ->
        reconcile(project, mutation) == BuilderResourceMutationResult.APPLIED
    } ?: false

    fun hasInput(
        playerId: UUID,
        project: BuilderConstructionProjectRecord,
        input: BuilderItemAmount,
    ): Boolean = prepareInput(playerId, project, input) != null

    fun storeOutput(
        playerId: UUID,
        project: BuilderConstructionProjectRecord,
        output: BuilderItemAmount,
    ): Boolean = prepareOutput(playerId, project, output)?.let { mutation ->
        reconcile(project, mutation) == BuilderResourceMutationResult.APPLIED
    } ?: false

    fun returnInput(
        playerId: UUID,
        project: BuilderConstructionProjectRecord,
        input: BuilderItemAmount,
    ): Boolean = storeOutput(playerId, project, input)

    fun prepareInput(
        playerId: UUID,
        project: BuilderConstructionProjectRecord,
        input: BuilderItemAmount,
    ): BuilderResourceMutation? {
        val playerSources = listOfNotNull(nearbyPlayerSource(playerId, project))
        return planExchange(playerSources, input, insert = false)
            ?: planExchange(playerSources + containerSources(playerId, project), input, insert = false)
    }

    fun prepareOutput(
        playerId: UUID,
        project: BuilderConstructionProjectRecord,
        output: BuilderItemAmount,
    ): BuilderResourceMutation? = planExchange(
        sources = containerSources(playerId, project) + listOfNotNull(nearbyPlayerSource(playerId, project)),
        amount = output,
        insert = true,
    )

    fun preparePlayerDebit(
        playerId: UUID,
        project: BuilderConstructionProjectRecord,
        input: BuilderItemAmount,
    ): BuilderResourceMutation? = planExchange(
        sources = listOfNotNull(playerSource(playerId, project, requireNearProject = false)),
        amount = input,
        insert = false,
    )

    fun reconcile(
        project: BuilderConstructionProjectRecord,
        mutation: BuilderResourceMutation,
    ): BuilderResourceMutationResult = reconcile(project, mutation, forward = true)

    fun rollback(
        project: BuilderConstructionProjectRecord,
        mutation: BuilderResourceMutation,
    ): BuilderResourceMutationResult = reconcile(project, mutation, forward = false)

    fun forget(projectId: UUID) {
        containerScans.remove(projectId)
    }

    private fun planExchange(
        sources: List<ResourceInventory>,
        amount: BuilderItemAmount,
        insert: Boolean,
    ): BuilderResourceMutation? {
        if (sources.isEmpty()) return null
        val (prototype, requested) = BuilderItemCodec.decode(amount.validated())
        var remaining = requested
        val planned = mutableListOf<BuilderResourceInventoryMutation>()
        for (source in sources) {
            if (remaining == 0 || planned.size == BuilderResourceMutation.MAX_MUTATION_SOURCES) break
            val before = source.inventory.storageContents.map { it?.clone() }
            val after = before.map { it?.clone() }.toMutableList()
            val nextRemaining = if (insert) {
                insertAvailable(after, prototype, remaining)
            } else {
                removeAvailable(after, prototype, remaining)
            }
            if (nextRemaining == remaining) continue
            if (!source.stillUsable()) return null
            planned += source.mutation(before, after)
            remaining = nextRemaining
        }
        if (remaining != 0) return null
        return BuilderResourceMutation(amount.validated(), insert, planned).validated(projectPlayerId = sources.first().ownerId)
    }

    private fun nearbyPlayerSource(
        playerId: UUID,
        project: BuilderConstructionProjectRecord,
    ): ResourceInventory? = playerSource(playerId, project, requireNearProject = true)

    private fun playerSource(
        playerId: UUID,
        project: BuilderConstructionProjectRecord,
        requireNearProject: Boolean,
    ): ResourceInventory? {
        val player = onlinePlayerProvider(playerId)?.takeIf(Player::isOnline) ?: return null
        if (requireNearProject && !isNearProject(player, project)) return null
        return ResourceInventory(
            inventory = player.inventory,
            ownerId = playerId,
            reference = ResourceSourceReference(
                kind = BuilderResourceSourceKind.PLAYER,
                playerId = playerId,
                requireNearProject = requireNearProject,
            ),
            stillUsable = {
                val current = onlinePlayerProvider(playerId)
                current != null && current.isOnline && current.uniqueId == playerId &&
                    (!requireNearProject || isNearProject(current, project))
            },
        )
    }

    private fun reconcile(
        project: BuilderConstructionProjectRecord,
        rawMutation: BuilderResourceMutation,
        forward: Boolean,
    ): BuilderResourceMutationResult {
        val mutation = rawMutation.validated(project.playerId)
        val resolved = mutableListOf<Pair<ResourceInventory, BuilderResourceInventoryMutation>>()
        for (source in mutation.sources) {
            when (val resolution = resolveMutationSource(project, source)) {
                is ResourceSourceResolution.Ready -> resolved += resolution.source to source
                ResourceSourceResolution.Retry -> return BuilderResourceMutationResult.RETRY
                ResourceSourceResolution.Conflict -> return BuilderResourceMutationResult.CONFLICT
            }
        }

        val states = resolved.map { (source, receipt) ->
            InventoryReceiptState(
                before = matchesSnapshot(source.inventory, receipt.before),
                after = matchesSnapshot(source.inventory, receipt.after),
            )
        }
        val desiredMatches = states.map { if (forward) it.after else it.before }
        val baselineMatches = states.map { if (forward) it.before else it.after }
        if (desiredMatches.all { it }) return BuilderResourceMutationResult.APPLIED
        if (states.indices.any { !desiredMatches[it] && !baselineMatches[it] }) {
            return if (forward && desiredMatches.none { it }) {
                BuilderResourceMutationResult.STALE
            } else {
                BuilderResourceMutationResult.CONFLICT
            }
        }

        try {
            resolved.indices.forEach { index ->
                if (desiredMatches[index]) return@forEach
                val (source, receipt) = resolved[index]
                if (!source.stillUsable()) throw BuilderResourceSourceUnavailableException()
                val desired = if (forward) receipt.after else receipt.before
                source.inventory.storageContents = decodeSnapshot(desired)
            }
        } catch (_: Throwable) {
            return classifyAfterWriteAttempt(resolved, forward)
        }
        return classifyAfterWriteAttempt(resolved, forward)
    }

    private fun classifyAfterWriteAttempt(
        resolved: List<Pair<ResourceInventory, BuilderResourceInventoryMutation>>,
        forward: Boolean,
    ): BuilderResourceMutationResult {
        val desired = resolved.map { (source, receipt) ->
            matchesSnapshot(source.inventory, if (forward) receipt.after else receipt.before)
        }
        if (desired.all { it }) return BuilderResourceMutationResult.APPLIED
        val baseline = resolved.map { (source, receipt) ->
            matchesSnapshot(source.inventory, if (forward) receipt.before else receipt.after)
        }
        return if (resolved.indices.all { desired[it] || baseline[it] }) {
            BuilderResourceMutationResult.RETRY
        } else {
            BuilderResourceMutationResult.CONFLICT
        }
    }

    private fun resolveMutationSource(
        project: BuilderConstructionProjectRecord,
        receipt: BuilderResourceInventoryMutation,
    ): ResourceSourceResolution = when (receipt.kind) {
        BuilderResourceSourceKind.PLAYER -> {
            val playerId = receipt.playerId ?: return ResourceSourceResolution.Conflict
            playerSource(playerId, project, receipt.requireNearProject)
                ?.let(ResourceSourceResolution::Ready)
                ?: ResourceSourceResolution.Retry
        }
        BuilderResourceSourceKind.CONTAINER -> {
            val worldId = receipt.containerBlocks.firstOrNull()?.worldId
                ?: return ResourceSourceResolution.Conflict
            val world = worldProvider(worldId) ?: return ResourceSourceResolution.Retry
            if (receipt.containerBlocks.any { !world.isChunkLoaded(it.x shr 4, it.z shr 4) }) {
                return ResourceSourceResolution.Retry
            }
            resolveContainerSource(project.playerId, world, receipt.containerBlocks)
                ?.let(ResourceSourceResolution::Ready)
                ?: ResourceSourceResolution.Conflict
        }
    }

    private fun matchesSnapshot(inventory: Inventory, encoded: List<String?>): Boolean {
        val current = inventory.storageContents
        if (current.size != encoded.size) return false
        return current.indices.all { index -> exactItem(current[index], encoded[index]?.let(BuilderItemCodec::decodeStack)) }
    }

    private fun decodeSnapshot(encoded: List<String?>): Array<ItemStack?> =
        encoded.map { it?.let(BuilderItemCodec::decodeStack) }.toTypedArray()

    private fun exactItem(current: ItemStack?, expected: ItemStack?): Boolean = when {
        current == null || current.type.isAir -> expected == null || expected.type.isAir
        expected == null || expected.type.isAir -> false
        else -> current.amount == expected.amount && current.isSimilar(expected)
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
        val scanBounds = ScanBounds.from(bounds, world, containerRadius)
        val scan = containerScans.getOrPut(project.projectId) { ContainerScan(scanBounds) }
        require(scan.bounds == scanBounds) { "Builder construction project bounds changed during a container scan" }
        scanContainers(playerId, world, bounds, scan)
        val candidates = scan.nextCandidates(maxResolvedContainersPerCall)
        val sources = candidates.mapNotNull { (key, positions) ->
            resolveContainerSource(playerId, world, positions)?.also { source ->
                source.key = key
            }
        }
        val liveKeys = sources.mapNotNull(ResourceInventory::key).toSet()
        candidates.mapTo(linkedSetOf()) { (key, _) -> key }
            .filterNot(liveKeys::contains)
            .forEach(scan.discovered::remove)
        return sources
    }

    private fun scanContainers(
        playerId: UUID,
        world: World,
        projectBounds: ProjectBounds,
        scan: ContainerScan,
    ) {
        var newCandidates = 0
        repeat(maxContainerProbesPerCall) {
            val (x, y, z) = scan.bounds.position(scan.cursor)
            scan.cursor = (scan.cursor + 1L) % scan.bounds.volume
            if (projectBounds.contains(x, y, z)) return@repeat
            if (!world.isChunkLoaded(x shr 4, z shr 4)) return@repeat
            val block = blockProvider(world, x, y, z)
            if (block.type !in SUPPORTED_CONTAINERS) return@repeat
            val state = block.state as? Container ?: return@repeat
            val blocks = inventoryBlocks(block, state.inventory)
            if (blocks.isEmpty() || blocks.any { !canOpenContainer(playerId, it) }) return@repeat
            val positions = blocks.map { position(it) }.sortedWith(BLOCK_POSITION_ORDER)
            if (scan.remember(sourceKey(positions), positions, maxCachedContainersPerProject)) {
                newCandidates += 1
                // Dense scans stop before new discoveries can evict themselves before resolution.
                if (newCandidates == maxResolvedContainersPerCall) return
            }
        }
    }

    private fun resolveContainerSource(
        playerId: UUID,
        world: World,
        positions: List<BuilderBlockPos>,
    ): ResourceInventory? {
        if (positions.isEmpty() || positions.any { it.worldId != world.uid }) return null
        if (positions.any { !world.isChunkLoaded(it.x shr 4, it.z shr 4) }) return null
        val first = positions.first().let { blockProvider(world, it.x, it.y, it.z) }
        if (first.type !in SUPPORTED_CONTAINERS) return null
        val state = first.state as? Container ?: return null
        val resolved = inventoryBlocks(first, state.inventory)
        if (resolved.map { position(it) }.sortedWith(BLOCK_POSITION_ORDER) != positions) return null
        if (resolved.any { !canOpenContainer(playerId, it) }) return null
        return ResourceInventory(
            inventory = state.inventory,
            ownerId = playerId,
            reference = ResourceSourceReference(
                kind = BuilderResourceSourceKind.CONTAINER,
                containerBlocks = positions,
                requireNearProject = false,
            ),
            stillUsable = { containerSourceUsable(playerId, world, positions) },
        )
    }

    private fun containerSourceUsable(
        playerId: UUID,
        world: World,
        positions: List<BuilderBlockPos>,
    ): Boolean {
        if (positions.any { !world.isChunkLoaded(it.x shr 4, it.z shr 4) }) return false
        val first = positions.first().let { blockProvider(world, it.x, it.y, it.z) }
        if (first.type !in SUPPORTED_CONTAINERS) return false
        val state = first.state as? Container ?: return false
        val currentPositions = inventoryBlocks(first, state.inventory)
            .map { position(it) }
            .sortedWith(BLOCK_POSITION_ORDER)
        return currentPositions == positions && currentPositions.all { current ->
            canOpenContainer(playerId, blockProvider(world, current.x, current.y, current.z))
        }
    }

    private data class ResourceInventory(
        val inventory: Inventory,
        val ownerId: UUID,
        val reference: ResourceSourceReference,
        val stillUsable: () -> Boolean,
        var key: String? = null,
    ) {
        fun mutation(
            before: List<ItemStack?>,
            after: List<ItemStack?>,
        ): BuilderResourceInventoryMutation = BuilderResourceInventoryMutation(
            kind = reference.kind,
            playerId = reference.playerId,
            containerBlocks = reference.containerBlocks,
            requireNearProject = reference.requireNearProject,
            before = before.map { it?.takeUnless { item -> item.type.isAir }?.let(BuilderItemCodec::encodeStack) },
            after = after.map { it?.takeUnless { item -> item.type.isAir }?.let(BuilderItemCodec::encodeStack) },
        ).validated(ownerId)
    }

    private data class ResourceSourceReference(
        val kind: BuilderResourceSourceKind,
        val playerId: UUID? = null,
        val containerBlocks: List<BuilderBlockPos> = emptyList(),
        val requireNearProject: Boolean,
    )

    private sealed interface ResourceSourceResolution {
        data class Ready(val source: ResourceInventory) : ResourceSourceResolution
        data object Retry : ResourceSourceResolution
        data object Conflict : ResourceSourceResolution
    }

    private data class InventoryReceiptState(val before: Boolean, val after: Boolean)

    private class BuilderResourceSourceUnavailableException : RuntimeException()

    private data class ContainerScan(
        val bounds: ScanBounds,
        var cursor: Long = 0L,
        val discovered: LinkedHashMap<String, List<BuilderBlockPos>> = linkedMapOf(),
        var sourceCursor: Int = 0,
    ) {
        fun remember(key: String, positions: List<BuilderBlockPos>, maximum: Int): Boolean {
            if (key in discovered) {
                discovered[key] = positions
                return false
            }
            while (discovered.size >= maximum) {
                discovered.entries.iterator().run {
                    next()
                    remove()
                }
            }
            discovered[key] = positions
            sourceCursor %= discovered.size
            return true
        }

        fun nextCandidates(maximum: Int): List<Map.Entry<String, List<BuilderBlockPos>>> {
            if (discovered.isEmpty()) return emptyList()
            val entries = discovered.entries.toList()
            val count = minOf(maximum, entries.size)
            val start = sourceCursor % entries.size
            sourceCursor = (start + count) % entries.size
            return List(count) { offset -> entries[(start + offset) % entries.size] }
        }
    }

    private data class ScanBounds(
        val minX: Int,
        val minY: Int,
        val minZ: Int,
        val sizeX: Int,
        val sizeY: Int,
        val sizeZ: Int,
    ) {
        val volume: Long = Math.multiplyExact(Math.multiplyExact(sizeX.toLong(), sizeY.toLong()), sizeZ.toLong())

        fun position(index: Long): Triple<Int, Int, Int> {
            require(index in 0 until volume) { "Builder construction scan cursor is invalid" }
            val yz = Math.multiplyExact(sizeY.toLong(), sizeZ.toLong())
            val dx = index / yz
            val remainder = index % yz
            val dy = remainder / sizeZ
            val dz = remainder % sizeZ
            return Triple(
                Math.addExact(minX, dx.toInt()),
                Math.addExact(minY, dy.toInt()),
                Math.addExact(minZ, dz.toInt()),
            )
        }

        companion object {
            fun from(bounds: ProjectBounds, world: World, radius: Int): ScanBounds {
                val minX = Math.subtractExact(bounds.minX, radius)
                val maxX = Math.addExact(bounds.maxX, radius)
                val minY = Math.subtractExact(bounds.minY, radius).coerceAtLeast(world.minHeight)
                val maxY = Math.addExact(bounds.maxY, radius).coerceAtMost(world.maxHeight - 1)
                val minZ = Math.subtractExact(bounds.minZ, radius)
                val maxZ = Math.addExact(bounds.maxZ, radius)
                return ScanBounds(
                    minX = minX,
                    minY = minY,
                    minZ = minZ,
                    sizeX = Math.addExact(Math.subtractExact(maxX, minX), 1),
                    sizeY = Math.addExact(Math.subtractExact(maxY, minY), 1),
                    sizeZ = Math.addExact(Math.subtractExact(maxZ, minZ), 1),
                )
            }
        }
    }

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
        val BLOCK_POSITION_ORDER = compareBy<BuilderBlockPos>(BuilderBlockPos::x)
            .thenBy(BuilderBlockPos::y)
            .thenBy(BuilderBlockPos::z)

        fun position(block: Block): BuilderBlockPos =
            BuilderBlockPos(block.world.uid, block.x, block.y, block.z)

        fun sourceKey(positions: List<BuilderBlockPos>): String = positions.joinToString("|") {
            "${it.worldId}:${it.x}:${it.y}:${it.z}"
        }

        fun inventoryBlocks(fallback: Block, inventory: Inventory): List<Block> {
            val holder = inventory.holder
            if (holder !is DoubleChest) return listOf(fallback)
            return listOf(holder.leftSide, holder.rightSide)
                .mapNotNull { (it as? Container)?.block }
                .distinctBy { listOf(it.world.uid, it.x, it.y, it.z) }
                .takeIf { it.size == 2 }
                ?: emptyList()
        }

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
