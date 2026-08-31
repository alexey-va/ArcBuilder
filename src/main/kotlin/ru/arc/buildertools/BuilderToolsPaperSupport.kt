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
import org.bukkit.block.data.Lightable
import org.bukkit.block.data.Segmentable
import org.bukkit.block.data.Powerable
import org.bukkit.block.data.Waterlogged
import org.bukkit.block.data.type.Bed
import org.bukkit.block.data.type.Candle
import org.bukkit.block.data.type.FlowerBed
import org.bukkit.block.data.type.SeaPickle
import org.bukkit.block.data.type.Slab
import org.bukkit.block.data.type.Snow
import org.bukkit.block.data.type.Stairs
import org.bukkit.block.data.type.TrapDoor
import org.bukkit.entity.Player
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
            val expected = BuilderItemCodec.decodePrototype(toolFingerprintBase64)
            val held = simulated[player.inventory.heldItemSlot]
            if (!itemEquals(held, expected)) return false
            val damageable = held?.itemMeta as? Damageable ?: return false
            val remaining = held.type.maxDurability.toInt() - damageable.damage
            if (remaining <= toolDamage) return false
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
            data.asString.startsWith("minecraft:") &&
            (data !is Waterlogged || !data.isWaterlogged) &&
            (data !is Lightable || !data.isLit) &&
            (data !is Powerable || !data.isPowered) &&
            (data !is Bed || !data.isOccupied)

    fun isSafeMaterial(material: Material): Boolean {
        if (!material.isBlock || material.isAir || BuilderPlacementCost.constructionItem(material) == null) return false
        if (BuilderRewardOrePolicy.isBlocked(material)) return false
        if (material in UNSAFE_MATERIALS) return false
        val name = material.name
        if (UNSAFE_FRAGMENTS.any(name::contains)) return false
        return true
    }

    fun isLeaf(material: Material): Boolean = material.name.endsWith("_LEAVES") && isSafeMaterial(material)

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
        private val UNSAFE_FRAGMENTS = listOf(
            "COMMAND_BLOCK",
            "STRUCTURE_BLOCK",
            "JIGSAW",
            "SPAWNER",
            "TRIAL_SPAWNER",
            "VAULT",
            "OBSERVER",
            "REDSTONE_LAMP",
            "COPPER_BULB",
            "NOTE_BLOCK",
            "COMPOSTER",
            "SCULK_SENSOR",
            "SCULK_SHRIEKER",
            "_PORTAL",
            "_SIGN",
            "_HANGING_SIGN",
            "CHEST",
            "BARREL",
            "SHULKER_BOX",
            "FURNACE",
            "SMOKER",
            "HOPPER",
            "DISPENSER",
            "DROPPER",
            "CRAFTER",
            "LECTERN",
            "JUKEBOX",
            "CHISELED_BOOKSHELF",
            "DECORATED_POT",
            "BEEHIVE",
            "BEE_NEST",
            "BREWING_STAND",
            "ENCHANTING_TABLE",
            "PISTON",
            "_HEAD",
            "_SKULL",
            "REDSTONE_TORCH",
            "_RAIL",
            "_BUTTON",
            "_PRESSURE_PLATE",
            "REDSTONE_WIRE",
            "REPEATER",
            "COMPARATOR",
            "TRIPWIRE",
            "LEVER",
            "_BANNER",
            "CANDLE_CAKE",
            "TURTLE_EGG",
            "FROGSPAWN",
        )
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
