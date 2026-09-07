package ru.arc.buildertools

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.jeff_media.customblockdata.CustomBlockData
import dev.lone.itemsadder.api.CustomBlock
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.TileState
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.Segmentable
import org.bukkit.block.data.Waterlogged
import org.bukkit.block.data.type.Bed
import org.bukkit.block.data.type.Piston
import org.bukkit.block.data.type.Candle
import org.bukkit.block.data.type.FlowerBed
import org.bukkit.block.data.type.SeaPickle
import org.bukkit.block.data.type.Slab
import org.bukkit.block.data.type.Snow
import org.bukkit.block.data.type.Stairs
import org.bukkit.block.data.type.TrapDoor
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.bukkit.inventory.meta.Damageable
import org.bukkit.plugin.Plugin
import ru.arc.ARC
import ru.arc.hooks.HookRegistry
import ru.arc.paper.playerstate.NativePaperItemStackBinaryCodec
import ru.arc.paper.playerstate.PaperPlayerStateCodec
import ru.arc.paper.playerstate.PaperPlayerStateEnvelope
import ru.arc.persistence.DurableAcknowledgementOutcome
import ru.arc.persistence.DurableRecord
import ru.arc.persistence.DurableRecordJournal
import ru.arc.util.Logging.warn
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Base64

internal object BuilderItemCodec {
    private val native = NativePaperItemStackBinaryCodec

    fun encodePrototype(item: ItemStack): String {
        require(!item.type.isAir && item.amount > 0) { "Cannot encode an empty builder-tools item" }
        val prototype = item.clone().also { it.amount = 1 }
        return Base64.getEncoder().encodeToString(native.encodeItem(prototype))
    }

    fun decodePrototype(base64: String): ItemStack {
        val bytes = try {
            Base64.getDecoder().decode(base64)
        } catch (failure: IllegalArgumentException) {
            throw IllegalArgumentException("Builder-tools item payload is not Base64", failure)
        }
        require(bytes.size in 1..1_000_000) { "Builder-tools item payload is outside its decoded size bound" }
        val item = native.decodeItem(bytes)
        require(!item.type.isAir && item.amount == 1) { "Builder-tools item prototype is invalid" }
        return item
    }

    fun encodeStack(item: ItemStack): String {
        require(!item.type.isAir && item.amount > 0) { "Cannot encode an empty builder-tools item stack" }
        return Base64.getEncoder().encodeToString(native.encodeItem(item.clone()))
    }

    fun decodeStack(base64: String): ItemStack {
        val bytes = try {
            Base64.getDecoder().decode(base64)
        } catch (failure: IllegalArgumentException) {
            throw IllegalArgumentException("Builder-tools item stack payload is not Base64", failure)
        }
        require(bytes.size in 1..1_000_000) { "Builder-tools item stack payload is outside its decoded size bound" }
        return native.decodeItem(bytes).also { item ->
            require(!item.type.isAir && item.amount > 0) { "Builder-tools item stack payload is invalid" }
        }
    }

    fun aggregate(items: Iterable<ItemStack>): List<BuilderItemAmount> {
        val aggregates = mutableListOf<Pair<ItemStack, Int>>()
        items.filterNot { it.type.isAir || it.amount <= 0 }.forEach { input ->
            val amount = input.amount
            val prototype = input.clone().also { it.amount = 1 }
            val index = aggregates.indexOfFirst { (existing, _) -> existing.isSimilar(prototype) }
            if (index == -1) {
                aggregates += prototype to amount
            } else {
                val (existing, count) = aggregates[index]
                aggregates[index] = existing to Math.addExact(count, amount)
            }
        }
        return aggregates.map { (prototype, amount) ->
            BuilderItemAmount(
                itemBase64 = encodePrototype(prototype),
                materialKey = prototype.type.key.toString(),
                amount = amount,
            ).validated()
        }
    }

    fun decode(amount: BuilderItemAmount): Pair<ItemStack, Int> = decodePrototype(amount.itemBase64) to amount.amount
}

internal object BuilderPlacementCost {
    fun itemOrNull(data: BlockData): ItemStack? {
        if (data is Bed && data.part == Bed.Part.HEAD) return null
        if (data is Bisected && data !is Stairs && data !is TrapDoor && data.half == Bisected.Half.TOP) return null
        val material = constructionItem(data.material) ?: return null
        val amount = when (data) {
            is Slab -> if (data.type == Slab.Type.DOUBLE) 2 else 1
            is Candle -> data.candles
            is SeaPickle -> data.pickles
            is FlowerBed -> data.flowerAmount
            is Snow -> data.layers
            is Segmentable -> data.segmentAmount
            else -> 1
        }
        return ItemStack(material, amount)
    }

    fun constructionItem(material: Material): Material? = when (material) {
        Material.WALL_TORCH -> Material.TORCH
        Material.SOUL_WALL_TORCH -> Material.SOUL_TORCH
        Material.REDSTONE_WALL_TORCH -> Material.REDSTONE_TORCH
        Material.REDSTONE_WIRE -> Material.REDSTONE
        Material.TRIPWIRE -> Material.STRING
        else -> material.takeIf(Material::isItem)
    }
}

internal class BuilderJournalStore(
    dataRoot: Path,
    private val maxChanges: Int,
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create(),
) {
    private val journal = DurableRecordJournal(
        root = dataRoot,
        relativeDirectory = Path.of("data", "builder-tools-journal"),
        maxRecordBytes = 32L * 1024L * 1024L,
        encode = { record: BuilderJournalRecord -> gson.toJson(record).toByteArray(StandardCharsets.UTF_8) },
        decode = { bytes -> gson.fromJson(String(bytes, StandardCharsets.UTF_8), BuilderJournalRecord::class.java) },
        validate = { record -> record.validated(maxChanges) },
    )

    fun commit(record: BuilderJournalRecord): BuilderJournalRecord =
        journal.commit(record.operationId.toString(), record.validated(maxChanges))

    /**
     * Reconciles a commit that may have reached durable storage before its
     * readback failed. Only the exact expected predecessor is safe to report as
     * a confirmed rejection; every other state remains unknown and fail-closed.
     */
    fun transition(
        expected: BuilderJournalRecord,
        target: BuilderJournalRecord,
    ): BuilderJournalRecord {
        val checkedExpected = expected.validated(maxChanges)
        val checkedTarget = target.validated(maxChanges)
        BuilderJournalTransitionRules.classify(checkedExpected, checkedTarget, checkedExpected)
        return try {
            journal.commit(checkedTarget.operationId.toString(), checkedTarget)
        } catch (commitFailure: Throwable) {
            val current = try {
                journal.loadOrNull(checkedTarget.operationId.toString())
            } catch (readFailure: Throwable) {
                throw BuilderJournalUnknownOutcomeException(commitFailure, readFailure)
            }
            when (BuilderJournalTransitionRules.classify(checkedExpected, checkedTarget, current)) {
                BuilderJournalReconciliation.TARGET_COMMITTED -> checkedTarget
                BuilderJournalReconciliation.PREDECESSOR_CONFIRMED -> throw BuilderJournalTransitionRejectedException(commitFailure)
                BuilderJournalReconciliation.UNKNOWN -> throw BuilderJournalUnknownOutcomeException(commitFailure)
            }
        }
    }

    fun loadAll(): List<DurableRecord<BuilderJournalRecord>> = journal.loadAll()

    fun acknowledge(operationId: java.util.UUID): Boolean = journal.acknowledge(operationId.toString())

    fun acknowledgeExactly(record: BuilderJournalRecord): Boolean = when (
        journal.acknowledgeExactly(
            recordId = record.operationId.toString(),
            expected = record.validated(maxChanges),
            sameContent = { expected, current -> expected == current },
        )
    ) {
        DurableAcknowledgementOutcome.ACKNOWLEDGED,
        DurableAcknowledgementOutcome.ALREADY_ACKNOWLEDGED,
        -> true
        DurableAcknowledgementOutcome.CONTENT_MISMATCH ->
            error("Builder-tools recovery acknowledgement found different durable content")
    }
}

internal class BuilderJournalTransitionRejectedException(cause: Throwable) : RuntimeException(cause)

internal class BuilderJournalUnknownOutcomeException(
    cause: Throwable,
    readFailure: Throwable? = null,
) : RuntimeException("Builder-tools durable transition outcome is unknown", cause) {
    init {
        readFailure?.let(::addSuppressed)
    }
}

internal data class BuilderPooledToolUse(
    val slot: Int,
    val itemBase64: String,
    val damage: Int,
) {
    fun validated(): BuilderPooledToolUse = apply {
        require(slot in 0..35) { "Builder-tools pooled tool slot is outside player storage" }
        require(itemBase64.length in 4..1_000_000) { "Builder-tools pooled tool payload is outside its size bound" }
        require(damage in 1..BuilderPlan.ABSOLUTE_MAX_CHANGES) {
            "Builder-tools pooled tool damage is outside its safety bound"
        }
    }
}

internal data class BuilderPooledToolPlan(
    val uses: List<BuilderPooledToolUse>,
    val bypassUsed: Boolean,
) {
    fun validated(): BuilderPooledToolPlan = apply {
        require(uses.isNotEmpty() && uses.size <= 36) { "Builder-tools pooled tool count is invalid" }
        require(uses.map(BuilderPooledToolUse::slot).toSet().size == uses.size) {
            "Builder-tools pooled tool slots contain duplicates"
        }
        uses.forEach(BuilderPooledToolUse::validated)
        require(uses.sumOf { it.damage.toLong() } <= BuilderPlan.ABSOLUTE_MAX_CHANGES) {
            "Builder-tools pooled tool damage is outside its safety bound"
        }
    }
}

/** Versioned payload stored in the legacy plan fingerprint field to keep journal schema 1 readable. */
internal object BuilderPooledToolCodec {
    private const val MAGIC = "arc-builder-pooled-tools-v1"

    fun encode(plan: BuilderPooledToolPlan): String {
        val checked = plan.validated()
        val text = buildString {
            append(MAGIC).append('\n')
            append(if (checked.bypassUsed) '1' else '0')
            checked.uses.forEach { use ->
                append('\n').append(use.slot).append('\t').append(use.damage).append('\t').append(use.itemBase64)
            }
        }
        return Base64.getEncoder().encodeToString(text.toByteArray(StandardCharsets.UTF_8)).also { encoded ->
            require(encoded.length <= 1_500_000) { "Builder-tools pooled tool plan is outside its size bound" }
        }
    }

    fun isPooled(encoded: String): Boolean = runCatching {
        String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8).startsWith("$MAGIC\n")
    }.getOrDefault(false)

    fun decode(encoded: String): BuilderPooledToolPlan {
        val lines = String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8).split('\n')
        require(lines.size >= 3 && lines.first() == MAGIC) { "Builder-tools pooled tool payload is invalid" }
        val bypassUsed = when (lines[1]) {
            "0" -> false
            "1" -> true
            else -> throw IllegalArgumentException("Builder-tools pooled tool bypass flag is invalid")
        }
        val uses = lines.drop(2).map { line ->
            val fields = line.split('\t', limit = 3)
            require(fields.size == 3) { "Builder-tools pooled tool entry is invalid" }
            BuilderPooledToolUse(
                slot = fields[0].toInt(),
                damage = fields[1].toInt(),
                itemBase64 = fields[2],
            ).validated()
        }
        return BuilderPooledToolPlan(uses, bypassUsed).validated()
    }
}

internal object BuilderInventory {
    private val stateCodec = PaperPlayerStateCodec()

    fun snapshotInventoryMatches(player: Player, envelope: PaperPlayerStateEnvelope): Boolean {
        val expected = stateCodec.decode(envelope)
        return contentEquals(player.inventory.storageContents.toList(), expected.storage) &&
            contentEquals(player.inventory.armorContents.toList(), expected.armor) &&
            itemEquals(player.inventory.itemInOffHand, expected.offHand) &&
            itemEquals(player.itemOnCursor, expected.cursor) &&
            player.inventory.heldItemSlot == expected.selectedSlot
    }

    fun canApply(
        player: Player,
        costs: List<BuilderItemAmount>,
        rewards: List<BuilderItemAmount>,
        toolFingerprintBase64: String?,
        toolDamage: Int,
    ): Boolean = canApplyAfterReceiving(player, emptyList(), costs, rewards, toolFingerprintBase64, toolDamage)

    fun canApplyAfterReceiving(
        player: Player,
        received: List<BuilderItemAmount>,
        costs: List<BuilderItemAmount>,
        rewards: List<BuilderItemAmount>,
        toolFingerprintBase64: String?,
        toolDamage: Int,
    ): Boolean {
        val simulated = player.inventory.storageContents.map { it?.clone() }.toMutableList()
        for (addition in received) {
            val (prototype, amount) = BuilderItemCodec.decode(addition)
            if (!insert(simulated, prototype, amount)) return false
        }
        if (toolFingerprintBase64 != null) {
            if (BuilderPooledToolCodec.isPooled(toolFingerprintBase64)) {
                val pooled = runCatching { BuilderPooledToolCodec.decode(toolFingerprintBase64) }.getOrElse { return false }
                if (pooled.uses.sumOf(BuilderPooledToolUse::damage) != toolDamage) return false
                if (!pooled.uses.all { use -> toolUseMatches(simulated, use) }) return false
            } else {
                val expected = BuilderItemCodec.decodePrototype(toolFingerprintBase64)
                val held = simulated[player.inventory.heldItemSlot]
                if (!itemEquals(held, expected)) return false
                val damageable = held?.itemMeta as? Damageable ?: return false
                val remaining = BuilderToolDurability.maximumDamage(held) - damageable.damage
                if (remaining <= toolDamage) return false
            }
        }
        for (cost in costs) {
            val (prototype, amount) = BuilderItemCodec.decode(cost)
            if (!remove(simulated, prototype, amount)) return false
        }
        for (reward in rewards) {
            val (prototype, amount) = BuilderItemCodec.decode(reward)
            if (!insert(simulated, prototype, amount)) return false
        }
        return true
    }

    fun applyToolDamage(player: Player, toolFingerprintBase64: String, toolDamage: Int) {
        if (!BuilderPooledToolCodec.isPooled(toolFingerprintBase64)) {
            player.damageItemStack(EquipmentSlot.HAND, toolDamage)
            return
        }
        val pooled = BuilderPooledToolCodec.decode(toolFingerprintBase64)
        check(pooled.uses.sumOf(BuilderPooledToolUse::damage) == toolDamage) {
            "Builder-tools pooled tool damage changed after planning"
        }
        val contents = player.inventory.storageContents.map { it?.clone() }.toMutableList()
        pooled.uses.forEach { use ->
            check(toolUseMatches(contents, use)) { "Builder-tools pooled tool changed after planning" }
            val item = checkNotNull(contents[use.slot])
            val meta = item.itemMeta as Damageable
            if (meta.isUnbreakable) return@forEach
            meta.damage = Math.addExact(meta.damage, use.damage)
            item.itemMeta = meta
        }
        player.inventory.storageContents = contents.toTypedArray()
    }

    fun missingCosts(player: Player, costs: List<BuilderItemAmount>): List<BuilderItemAmount> {
        val simulated = player.inventory.storageContents.map { it?.clone() }.toMutableList()
        return costs.mapNotNull { cost ->
            val (prototype, amount) = BuilderItemCodec.decode(cost)
            val remaining = removeAvailable(simulated, prototype, amount)
            if (remaining == 0) null else cost.copy(amount = remaining).validated()
        }
    }

    fun plainMaterial(amount: BuilderItemAmount): Material? {
        val prototype = BuilderItemCodec.decodePrototype(amount.itemBase64)
        val material = prototype.type
        return material.takeIf {
            it.isItem && !it.isAir && it.key.toString() == amount.materialKey && prototype.isSimilar(ItemStack(it))
        }
    }

    fun countExact(player: Player, amount: BuilderItemAmount): Int {
        val prototype = BuilderItemCodec.decodePrototype(amount.itemBase64)
        return player.inventory.storageContents
            .asSequence()
            .filterNotNull()
            .filter { it.isSimilar(prototype) }
            .sumOf(ItemStack::getAmount)
    }

    fun removeCosts(inventory: PlayerInventory, costs: List<BuilderItemAmount>): Boolean {
        val contents = inventory.storageContents.map { it?.clone() }.toMutableList()
        for (cost in costs) {
            val (prototype, amount) = BuilderItemCodec.decode(cost)
            if (!remove(contents, prototype, amount)) return false
        }
        inventory.storageContents = contents.toTypedArray()
        return true
    }

    fun addRewards(inventory: PlayerInventory, rewards: List<BuilderItemAmount>): Boolean {
        val contents = inventory.storageContents.map { it?.clone() }.toMutableList()
        for (reward in rewards) {
            val (prototype, amount) = BuilderItemCodec.decode(reward)
            if (!insert(contents, prototype, amount)) return false
        }
        inventory.storageContents = contents.toTypedArray()
        return true
    }

    private fun remove(contents: MutableList<ItemStack?>, prototype: ItemStack, requested: Int): Boolean {
        return removeAvailable(contents, prototype, requested) == 0
    }

    private fun removeAvailable(contents: MutableList<ItemStack?>, prototype: ItemStack, requested: Int): Int {
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

    private fun insert(contents: MutableList<ItemStack?>, prototype: ItemStack, requested: Int): Boolean {
        var remaining = requested
        for (current in contents.filterNotNull()) {
            if (!current.isSimilar(prototype) || current.amount >= current.maxStackSize) continue
            val inserted = minOf(remaining, current.maxStackSize - current.amount)
            current.amount += inserted
            remaining -= inserted
            if (remaining == 0) return true
        }
        for (index in contents.indices) {
            if (contents[index] != null && !contents[index]!!.type.isAir) continue
            val inserted = minOf(remaining, prototype.maxStackSize)
            contents[index] = prototype.clone().also { it.amount = inserted }
            remaining -= inserted
            if (remaining == 0) return true
        }
        return false
    }

    private fun toolUseMatches(contents: List<ItemStack?>, use: BuilderPooledToolUse): Boolean {
        val current = contents.getOrNull(use.slot) ?: return false
        val expected = BuilderItemCodec.decodePrototype(use.itemBase64)
        if (!itemEquals(current, expected)) return false
        val damageable = current.itemMeta as? Damageable ?: return false
        if (damageable.isUnbreakable) return true
        return BuilderToolDurability.maximumDamage(current) - damageable.damage > use.damage
    }

    private fun contentEquals(actual: List<ItemStack?>, expected: List<ItemStack?>): Boolean =
        actual.size == expected.size && actual.indices.all { itemEquals(actual[it], expected[it]) }

    private fun itemEquals(first: ItemStack?, second: ItemStack?): Boolean {
        val left = first?.takeUnless { it.type.isAir || it.amount <= 0 }
        val right = second?.takeUnless { it.type.isAir || it.amount <= 0 }
        return when {
            left == null || right == null -> left == null && right == null
            else -> left.amount == right.amount && left.isSimilar(right)
        }
    }
}

internal object BuilderToolDurability {
    fun maximumDamage(item: ItemStack): Int {
        val meta = item.itemMeta as? Damageable ?: return 0
        return if (meta.hasMaxDamage()) meta.maxDamage else item.type.maxDurability.toInt()
    }
}

/**
 * Blocks whose placement would let builder operations bypass AuraSkills'
 * player-placement tracking and turn a reusable block into a mining reward.
 */
internal object BuilderRewardOrePolicy {
    fun isBlocked(material: Material): Boolean =
        !material.isLegacy && (material == Material.ANCIENT_DEBRIS || material.name.endsWith("_ORE"))
}

internal class BuilderBlockSafety(
    private val plugin: Plugin,
    replaceableNames: Set<String>,
) {
    val replaceable: Set<Material> = replaceableNames.map { name ->
        requireNotNull(Material.matchMaterial(name)) { "Unknown builder-tools replaceable material '$name'" }
    }.toSet()

    fun isReplaceable(block: Block): Boolean = block.type in replaceable && !isCustom(block) && block.state !is TileState

    fun isSafeExisting(block: Block): Boolean =
        isSafePlacement(block.blockData) && block.state !is TileState && !isCustom(block)

    fun isSafePlacement(data: BlockData): Boolean =
        isSafeMaterial(data.material) &&
            isSafeState(data)

    /** Opt-in exception for reviewed system books; player-authored containers remain rejected. */
    fun isSafeSystemLootContainer(data: BlockData): Boolean =
        data.material == Material.CHEST && isSafeState(data)

    private val tileMaterials = mutableMapOf<Material, Boolean>()

    fun isSafeMaterial(material: Material): Boolean {
        if (material.isLegacy || !material.isBlock || material.isAir || BuilderPlacementCost.constructionItem(material) == null) return false
        if (BuilderRewardOrePolicy.isBlocked(material)) return false
        if (material in UNSAFE_MATERIALS) return false
        val name = material.name
        if (UNSAFE_FRAGMENTS.any(name::contains)) return false
        return !tileMaterials.getOrPut(material) { material.createBlockData().createBlockState() is TileState }
    }

    fun isLeaf(material: Material): Boolean = material.name.endsWith("_LEAVES") && isSafeMaterial(material)

    private fun isSafeState(data: BlockData): Boolean =
        data.asString.startsWith("minecraft:") &&
            (data !is Waterlogged || !data.isWaterlogged) &&
            (data !is Piston || !data.isExtended) &&
            (data !is Bed || !data.isOccupied)

    private fun isCustom(block: Block): Boolean {
        if (CustomBlockData.hasCustomBlockData(block, plugin)) return true
        HookRegistry.sfHook?.let { hook ->
            if (hook.isSlimefunBlock(block)) return true
        }
        if (Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) {
            if (CustomBlock.byAlreadyPlaced(block) != null) return true
        }
        return false
    }

    companion object {
        private val UNSAFE_MATERIALS = setOf(
            Material.BEDROCK,
            Material.BARRIER,
            Material.LIGHT,
            Material.STRUCTURE_VOID,
            Material.REINFORCED_DEEPSLATE,
            Material.RESPAWN_ANCHOR,
            Material.TNT,
            Material.FIRE,
            Material.SOUL_FIRE,
            Material.NETHER_PORTAL,
            Material.END_PORTAL,
            Material.END_GATEWAY,
            Material.MOVING_PISTON,
        )
        // These compound/stacked blocks do not have a lossless item exchange yet.
        private val UNSAFE_FRAGMENTS = listOf("CANDLE_CAKE", "TURTLE_EGG", "FROGSPAWN")
    }
}

/** Reflection keeps CoreProtect optional at compile time while using its public runtime API. */
internal class BuilderCoreProtectBridge private constructor(
    private val api: Any,
    private val logRemovalMethod: java.lang.reflect.Method,
    private val logPlacementMethod: java.lang.reflect.Method,
) {
    fun logChange(user: String, location: Location, before: BlockData, after: BlockData) {
        if (!before.material.isAir) invoke(logRemovalMethod, user, location, before)
        if (!after.material.isAir) invoke(logPlacementMethod, user, location, after)
    }

    private fun invoke(method: java.lang.reflect.Method, user: String, location: Location, data: BlockData) {
        val accepted = runCatching { method.invoke(api, user, location, data.material, data) as? Boolean }
            .onFailure { warn("Builder-tools CoreProtect logging failed: {}", it.message) }
            .getOrNull()
        if (accepted == false) warn("Builder-tools CoreProtect rejected a block log at {}", location)
    }

    companion object {
        fun resolve(): BuilderCoreProtectBridge? {
            val plugin = Bukkit.getPluginManager().getPlugin("CoreProtect") ?: return null
            if (!plugin.isEnabled) return null
            return runCatching {
                val api = plugin.javaClass.getMethod("getAPI").invoke(plugin)
                val apiClass = api.javaClass
                val parameters = arrayOf(String::class.java, Location::class.java, Material::class.java, BlockData::class.java)
                val removal = apiClass.getMethod("logRemoval", *parameters)
                val placement = apiClass.getMethod("logPlacement", *parameters)
                BuilderCoreProtectBridge(api, removal, placement)
            }.onFailure { warn("Builder-tools could not bind CoreProtect API: {}", it.message) }.getOrNull()
        }
    }
}
