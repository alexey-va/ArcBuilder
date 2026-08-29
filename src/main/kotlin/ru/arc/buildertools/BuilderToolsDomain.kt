package ru.arc.buildertools

import ru.arc.paper.playerstate.PaperPlayerStateEnvelope
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

enum class BuilderPlanKind {
    FILL,
    FENCE_DISCONNECT,
    PASTE,
    BUILD_BOOK,
    DECONSTRUCT,
    CROWN,
    UNDO,
}

enum class BuilderJournalPhase {
    PREPARED,
    APPLYING,
    COMMITTED,
    UNDONE,
}

enum class BuilderRecoveryAction {
    KEEP_BEFORE,
    RESTORE_BEFORE,
}

object BuilderRecoveryRules {
    fun action(
        phase: BuilderJournalPhase,
        currentBlockData: String,
        beforeBlockData: String,
        afterBlockData: String,
    ): BuilderRecoveryAction = when (phase) {
        BuilderJournalPhase.PREPARED -> {
            require(currentBlockData == beforeBlockData) {
                "Prepared builder-tools recovery found world drift"
            }
            BuilderRecoveryAction.KEEP_BEFORE
        }
        BuilderJournalPhase.APPLYING -> when (currentBlockData) {
            beforeBlockData -> BuilderRecoveryAction.KEEP_BEFORE
            afterBlockData -> BuilderRecoveryAction.RESTORE_BEFORE
            else -> error("Applying builder-tools recovery found ambiguous world state")
        }
        BuilderJournalPhase.COMMITTED,
        BuilderJournalPhase.UNDONE,
        -> error("Terminal builder-tools records do not require block recovery")
    }
}

enum class BuilderJournalReconciliation {
    TARGET_COMMITTED,
    PREDECESSOR_CONFIRMED,
    UNKNOWN,
}

object BuilderJournalTransitionRules {
    fun classify(
        expected: BuilderJournalRecord,
        target: BuilderJournalRecord,
        current: BuilderJournalRecord?,
    ): BuilderJournalReconciliation {
        require(expected.operationId == target.operationId && expected.playerId == target.playerId) {
            "Builder-tools journal transition identity mismatch"
        }
        require(expected.plan == target.plan && expected.inventoryBefore == target.inventoryBefore) {
            "Builder-tools journal transition changed immutable operation data"
        }
        require(target.updatedAtMillis >= expected.updatedAtMillis) {
            "Builder-tools journal transition moved time backwards"
        }
        require(
            (expected.phase == BuilderJournalPhase.PREPARED && target.phase == BuilderJournalPhase.APPLYING) ||
                (expected.phase == BuilderJournalPhase.APPLYING && target.phase == BuilderJournalPhase.COMMITTED) ||
                (expected.phase == BuilderJournalPhase.COMMITTED && target.phase == BuilderJournalPhase.UNDONE),
        ) { "Builder-tools journal phase transition is invalid" }
        return when (current) {
            target -> BuilderJournalReconciliation.TARGET_COMMITTED
            expected -> BuilderJournalReconciliation.PREDECESSOR_CONFIRMED
            else -> BuilderJournalReconciliation.UNKNOWN
        }
    }
}

data class BuilderBlockPos(
    val worldId: UUID,
    val x: Int,
    val y: Int,
    val z: Int,
) {
    fun validated(): BuilderBlockPos = apply {
        require(x in -30_000_000..30_000_000 && z in -30_000_000..30_000_000) {
            "Builder-tools block position is outside the world coordinate bound"
        }
        require(y in -2_048..2_048) { "Builder-tools block position is outside the vertical safety bound" }
    }
}

data class BuilderBlockChange(
    val position: BuilderBlockPos,
    val beforeBlockData: String,
    val afterBlockData: String,
) {
    fun validated(): BuilderBlockChange = apply {
        position.validated()
        require(beforeBlockData.length in 3..512 && afterBlockData.length in 3..512) {
            "Builder-tools block data is outside its size bound"
        }
        require(beforeBlockData.startsWith("minecraft:") && afterBlockData.startsWith("minecraft:")) {
            "Builder-tools journal only accepts canonical vanilla block data"
        }
        require(beforeBlockData != afterBlockData) { "Builder-tools change must alter the block state" }
    }
}

/** One exact ItemStack prototype encoded with Paper's native codec plus a bounded quantity. */
data class BuilderItemAmount(
    val itemBase64: String,
    val materialKey: String,
    val amount: Int,
) {
    fun validated(): BuilderItemAmount = apply {
        require(itemBase64.length in 4..1_500_000) { "Builder-tools item payload is outside its size bound" }
        require(materialKey.matches(Regex("minecraft:[a-z0-9_./-]{1,96}"))) {
            "Builder-tools material key is invalid"
        }
        require(amount in 1..1_000_000) { "Builder-tools item quantity is outside its safety bound" }
    }
}

data class BuilderPlan(
    val id: UUID,
    val playerId: UUID,
    val kind: BuilderPlanKind,
    val changes: List<BuilderBlockChange>,
    val costs: List<BuilderItemAmount>,
    val rewards: List<BuilderItemAmount>,
    val toolFingerprintBase64: String? = null,
    val toolDamage: Int = 0,
    val sourceRecordId: UUID? = null,
    val bookBlueprintId: UUID? = null,
    val bookInstanceId: UUID? = null,
    val bookInstanceGeneration: Int? = null,
    val bookBuildingId: String? = null,
    val bookSchematicSha256: String? = null,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
    val skippedUnsafeBlocks: Int = 0,
) {
    fun validated(maxChanges: Int = ABSOLUTE_MAX_CHANGES): BuilderPlan = apply {
        require(changes.size in 1..maxChanges.coerceAtMost(ABSOLUTE_MAX_CHANGES)) {
            "Builder-tools plan change count is outside its safety bound"
        }
        require(changes.map { it.position }.toSet().size == changes.size) {
            "Builder-tools plan contains duplicate block positions"
        }
        changes.forEach(BuilderBlockChange::validated)
        require(costs.size + rewards.size <= 4_096) { "Builder-tools item entry count is outside its safety bound" }
        require((costs + rewards).sumOf { it.itemBase64.length.toLong() } <= 16_000_000L) {
            "Builder-tools item payload total is outside its safety bound"
        }
        (costs + rewards).forEach(BuilderItemAmount::validated)
        require(costs.sumOf { it.amount.toLong() } <= ABSOLUTE_MAX_ITEMS) {
            "Builder-tools plan cost is outside its safety bound"
        }
        require(rewards.sumOf { it.amount.toLong() } <= ABSOLUTE_MAX_ITEMS) {
            "Builder-tools plan reward is outside its safety bound"
        }
        require(toolDamage in 0..ABSOLUTE_MAX_CHANGES) { "Builder-tools tool damage is outside its safety bound" }
        require((toolDamage == 0) == (toolFingerprintBase64 == null)) {
            "Builder-tools tool fingerprint and damage must be present together"
        }
        toolFingerprintBase64?.let {
            require(it.length in 4..1_500_000) { "Builder-tools tool fingerprint is outside its size bound" }
        }
        require(createdAtMillis > 0L && expiresAtMillis > createdAtMillis) {
            "Builder-tools plan lifetime is invalid"
        }
        require(kind == BuilderPlanKind.UNDO || sourceRecordId == null) {
            "Only an undo plan may reference a source operation"
        }
        require(kind != BuilderPlanKind.UNDO || sourceRecordId != null) {
            "An undo plan must reference its source operation"
        }
        val bookValues = listOf(bookBlueprintId, bookInstanceId, bookInstanceGeneration, bookBuildingId, bookSchematicSha256)
        require(bookValues.all { it == null } || bookValues.all { it != null }) {
            "Builder-book plan identity must be present together"
        }
        require(bookInstanceId == null || kind == BuilderPlanKind.BUILD_BOOK) {
            "Only a build-book plan may reserve a book instance"
        }
        bookInstanceGeneration?.let { require(it > 0) { "Builder-book plan generation is invalid" } }
        bookSchematicSha256?.let {
            require(it.matches(Regex("[a-f0-9]{64}"))) { "Builder-book plan schematic digest is invalid" }
        }
        require(skippedUnsafeBlocks in 0..ABSOLUTE_MAX_CHANGES) { "Skipped block count is invalid" }
        bookBuildingId?.let {
            require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,159}"))) {
                "Builder-book plan building id is invalid"
            }
        }
    }

    companion object {
        const val ABSOLUTE_MAX_CHANGES = 10_000
        const val ABSOLUTE_MAX_ITEMS = 2_000_000L
    }
}

internal data class BuilderUndoExchange(
    val costs: List<BuilderItemAmount>,
    val rewards: List<BuilderItemAmount>,
)

/** Inventory side of an inverse operation; world changes are reversed separately. */
internal object BuilderUndoRules {
    fun exchangeFor(source: BuilderPlan): BuilderUndoExchange {
        require(source.kind != BuilderPlanKind.UNDO) { "An undo operation cannot be its own source" }
        return BuilderUndoExchange(
            costs = source.rewards,
            // A successful book build permanently consumes its MySQL instance.
            // Reissuing the removed ItemStack would create a locally active-looking
            // bearer that the authoritative one-time ledger must reject as stale.
            rewards = if (source.kind == BuilderPlanKind.BUILD_BOOK) emptyList() else source.costs,
        )
    }
}

data class BuilderJournalRecord(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val operationId: UUID,
    val playerId: UUID,
    val playerName: String,
    val phase: BuilderJournalPhase,
    val plan: BuilderPlan,
    val inventoryBefore: PaperPlayerStateEnvelope,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val committedAtMillis: Long? = null,
) {
    fun validated(maxChanges: Int = BuilderPlan.ABSOLUTE_MAX_CHANGES): BuilderJournalRecord = apply {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "Unsupported builder-tools journal schema" }
        require(operationId == plan.id && playerId == plan.playerId) { "Builder-tools journal identity mismatch" }
        require(playerName.matches(Regex("[A-Za-z0-9_]{1,16}"))) { "Builder-tools player name is invalid" }
        plan.validated(maxChanges)
        require(inventoryBefore.payloadBase64.length in 4..24_000_000) {
            "Builder-tools inventory payload is outside its safety bound"
        }
        require(inventoryBefore.sha256.matches(Regex("[a-f0-9]{64}"))) {
            "Builder-tools inventory checksum is invalid"
        }
        require(createdAtMillis == plan.createdAtMillis && updatedAtMillis >= createdAtMillis) {
            "Builder-tools journal timestamps are invalid"
        }
        require((phase == BuilderJournalPhase.COMMITTED || phase == BuilderJournalPhase.UNDONE) == (committedAtMillis != null)) {
            "Builder-tools committed timestamp does not match its phase"
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

data class BuilderSelection(
    val first: BuilderBlockPos,
    val second: BuilderBlockPos,
) {
    init {
        require(first.worldId == second.worldId) { "Builder-tools selection cannot cross worlds" }
    }

    val worldId: UUID get() = first.worldId
    val minX: Int get() = min(first.x, second.x)
    val maxX: Int get() = max(first.x, second.x)
    val minY: Int get() = min(first.y, second.y)
    val maxY: Int get() = max(first.y, second.y)
    val minZ: Int get() = min(first.z, second.z)
    val maxZ: Int get() = max(first.z, second.z)
    val sizeX: Int get() = maxX - minX + 1
    val sizeY: Int get() = maxY - minY + 1
    val sizeZ: Int get() = maxZ - minZ + 1
    val volume: Long get() = Math.multiplyExact(Math.multiplyExact(sizeX.toLong(), sizeY.toLong()), sizeZ.toLong())

    fun positionsTopDown(): Sequence<BuilderBlockPos> = sequence {
        for (y in maxY downTo minY) {
            for (x in minX..maxX) {
                for (z in minZ..maxZ) yield(BuilderBlockPos(worldId, x, y, z))
            }
        }
    }

    fun positionsBottomUp(): Sequence<BuilderBlockPos> = sequence {
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                for (z in minZ..maxZ) yield(BuilderBlockPos(worldId, x, y, z))
            }
        }
    }

    fun validated(maxAxis: Int, maxScanVolume: Long): BuilderSelection = apply {
        first.validated()
        second.validated()
        require(maxOf(sizeX, sizeY, sizeZ) <= maxAxis) { "selection-axis" }
        require(volume <= maxScanVolume) { "selection-volume" }
    }
}

data class BuilderClipboardBlock(
    val dx: Int,
    val dy: Int,
    val dz: Int,
    val blockData: String,
)

data class BuilderClipboard(
    val blocks: List<BuilderClipboardBlock>,
    val sizeX: Int,
    val sizeY: Int,
    val sizeZ: Int,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
    val originDx: Int = 0,
    val originDy: Int = 0,
    val originDz: Int = 0,
    val sourceRotation: Int = 0,
    val skippedUnsafeBlocks: Int = 0,
) {
    fun validated(maxBlocks: Int): BuilderClipboard = apply {
        require(blocks.size in 1..maxBlocks) { "clipboard-size" }
        require(sizeX in 1..256 && sizeY in 1..256 && sizeZ in 1..256) { "clipboard-dimensions" }
        require(blocks.map { Triple(it.dx, it.dy, it.dz) }.toSet().size == blocks.size) { "clipboard-duplicates" }
        blocks.forEach {
            require(it.dx in 0 until sizeX && it.dy in 0 until sizeY && it.dz in 0 until sizeZ) { "clipboard-offset" }
            require(it.blockData.length in 3..512 && it.blockData.startsWith("minecraft:")) { "clipboard-block-data" }
        }
        require(originDx in -1_024..1_024 && originDy in -1_024..1_024 && originDz in -1_024..1_024) {
            "clipboard-origin"
        }
        require(sourceRotation in setOf(0, 90, 180, 270)) { "clipboard-rotation" }
        require(skippedUnsafeBlocks in 0..BuilderPlan.ABSOLUTE_MAX_CHANGES) { "clipboard-skipped" }
        require(createdAtMillis > 0L && expiresAtMillis > createdAtMillis) { "clipboard-lifetime" }
    }
}

enum class BuilderCrownShape { NATURAL, ROUND, WIDE, TALL }

enum class BuilderCrownDensity { AIRY, NATURAL, DENSE }

enum class BuilderCrownNoise { SMOOTH, NATURAL, WILD }

data class BuilderCrownPaletteEntry(
    val materialName: String,
    val weight: Int,
) {
    fun validated(): BuilderCrownPaletteEntry = apply {
        require(materialName.matches(Regex("[a-z0-9_]{1,64}"))) { "Crown palette material is invalid" }
        require(weight in 1..10_000) { "Crown palette weight is invalid" }
    }
}

data class BuilderCrownSettings(
    val radius: Int = 5,
    val shape: BuilderCrownShape = BuilderCrownShape.NATURAL,
    val density: BuilderCrownDensity = BuilderCrownDensity.NATURAL,
    val noise: BuilderCrownNoise = BuilderCrownNoise.NATURAL,
    val palette: List<BuilderCrownPaletteEntry> = listOf(BuilderCrownPaletteEntry("oak_leaves", 1)),
) {
    fun validated(): BuilderCrownSettings = apply {
        require(radius in 3..10) { "Crown radius must be between 3 and 10" }
        require(palette.size in 1..8) { "Crown palette must contain between 1 and 8 materials" }
        palette.forEach(BuilderCrownPaletteEntry::validated)
        require(palette.map { it.materialName }.toSet().size == palette.size) { "Crown palette contains duplicates" }
        require(palette.sumOf { it.weight } in 1..10_000) { "Crown palette total weight is invalid" }
    }

    fun materialAt(x: Int, y: Int, z: Int, seed: Long): String {
        val total = palette.sumOf { it.weight }
        require(total in 1..10_000) { "Crown palette must be validated before material selection" }
        var slot = (BuilderCrownGeometry.unitNoise(x, y, z, seed xor PALETTE_SALT) * total)
            .toInt()
            .coerceIn(0, total - 1)
        palette.forEach { entry ->
            if (slot < entry.weight) return entry.materialName
            slot -= entry.weight
        }
        error("Validated crown palette did not resolve a material")
    }

    private companion object {
        const val PALETTE_SALT = 0x4c65616650414cL
    }
}

object BuilderCrownPaletteParser {
    /** Parses `oak_leaves90%,birch_leaves10%` or an equally weighted name list. */
    fun parse(raw: String): List<BuilderCrownPaletteEntry> {
        require(raw.length in 1..256) { "Crown palette text is outside its size bound" }
        val tokens = raw.split(',').map(String::trim)
        require(tokens.size in 1..8 && tokens.none(String::isBlank)) { "Crown palette entry count is invalid" }
        val weighted = tokens.any { it.endsWith('%') }
        require(!weighted || tokens.all { it.endsWith('%') }) { "Crown palette cannot mix weighted and equal entries" }
        val entries = if (!weighted) {
            tokens.map { token -> BuilderCrownPaletteEntry(token.lowercase(), 1).validated() }
        } else {
            tokens.map { token ->
                val body = token.dropLast(1)
                val digitStart = body.indexOfLast { !it.isDigit() } + 1
                require(digitStart in 1 until body.length) { "Crown palette percentage is invalid" }
                BuilderCrownPaletteEntry(
                    materialName = body.substring(0, digitStart).lowercase(),
                    weight = body.substring(digitStart).toInt(),
                ).validated()
            }.also { parsed ->
                require(parsed.sumOf { it.weight } == 100) { "Crown palette percentages must total 100" }
            }
        }
        require(entries.map { it.materialName }.toSet().size == entries.size) { "Crown palette contains duplicates" }
        return entries
    }
}

object BuilderCrownGeometry {
    /** Backwards-compatible default crown geometry. */
    fun offsets(radius: Int, seed: Long): List<Triple<Int, Int, Int>> =
        offsets(BuilderCrownSettings(radius = radius), seed)

    /** Deterministic coherent-noise crown. It changes no world state and is stable across restarts. */
    fun offsets(settings: BuilderCrownSettings, seed: Long): List<Triple<Int, Int, Int>> {
        val checked = settings.validated()
        val (radiusX, radiusY, radiusZ) = when (checked.shape) {
            BuilderCrownShape.NATURAL -> Triple(checked.radius.toDouble(), checked.radius * 0.70, checked.radius * 0.90)
            BuilderCrownShape.ROUND -> Triple(checked.radius.toDouble(), checked.radius * 0.78, checked.radius.toDouble())
            BuilderCrownShape.WIDE -> Triple(checked.radius * 1.20, checked.radius * 0.58, checked.radius * 1.20)
            BuilderCrownShape.TALL -> Triple(checked.radius * 0.78, checked.radius * 1.10, checked.radius * 0.78)
        }
        val (roughness, scale) = when (checked.noise) {
            BuilderCrownNoise.SMOOTH -> 0.10 to 5.5
            BuilderCrownNoise.NATURAL -> 0.24 to 3.5
            BuilderCrownNoise.WILD -> 0.38 to 2.4
        }
        val holeThreshold = when (checked.density) {
            BuilderCrownDensity.AIRY -> -0.05
            BuilderCrownDensity.NATURAL -> -0.45
            BuilderCrownDensity.DENSE -> -0.80
        }
        val boundX = ceil(radiusX).toInt()
        val boundY = ceil(radiusY).toInt()
        val boundZ = ceil(radiusZ).toInt()
        val result = ArrayList<Triple<Int, Int, Int>>()
        for (x in -boundX..boundX) {
            for (y in -boundY..boundY) {
                for (z in -boundZ..boundZ) {
                    val nx = x / radiusX
                    val ny = (y + radiusY * 0.08) / radiusY
                    val nz = z / radiusZ
                    val distance = nx * nx + ny * ny + nz * nz
                    if (distance > 1.5) continue
                    val edge = 1.0 + coherentNoise(x / scale, y / scale, z / scale, seed) * roughness
                    if (distance > edge) continue
                    if (distance > 0.38) {
                        val hole = coherentNoise(x / 1.7, y / 1.7, z / 1.7, seed xor HOLE_SALT)
                        if (hole < holeThreshold) continue
                    }
                    result += Triple(x, y, z)
                }
            }
        }
        return result.sortedWith(compareBy<Triple<Int, Int, Int>> { it.second }.thenBy { it.first }.thenBy { it.third })
    }

    internal fun unitNoise(x: Int, y: Int, z: Int, seed: Long): Double {
        var value = seed xor (x.toLong() * -7046029254386353131L)
        value = value xor (y.toLong() * -4658895280553007687L)
        value = value xor (z.toLong() * -7723592293110705685L)
        value = (value xor (value ushr 30)) * -4658895280553007687L
        value = (value xor (value ushr 27)) * -7723592293110705685L
        value = value xor (value ushr 31)
        return (value ushr 11).toDouble() / (1L shl 53).toDouble()
    }

    private fun coherentNoise(x: Double, y: Double, z: Double, seed: Long): Double {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val z0 = floor(z).toInt()
        val tx = smooth(x - x0)
        val ty = smooth(y - y0)
        val tz = smooth(z - z0)
        fun sample(dx: Int, dy: Int, dz: Int): Double = unitNoise(x0 + dx, y0 + dy, z0 + dz, seed) * 2.0 - 1.0
        val x00 = lerp(sample(0, 0, 0), sample(1, 0, 0), tx)
        val x10 = lerp(sample(0, 1, 0), sample(1, 1, 0), tx)
        val x01 = lerp(sample(0, 0, 1), sample(1, 0, 1), tx)
        val x11 = lerp(sample(0, 1, 1), sample(1, 1, 1), tx)
        return lerp(lerp(x00, x10, ty), lerp(x01, x11, ty), tz)
    }

    private fun smooth(value: Double): Double = value * value * (3.0 - 2.0 * value)

    private fun lerp(first: Double, second: Double, amount: Double): Double = first + (second - first) * amount

    private const val HOLE_SALT = 0x484f4c455f3337L
}
