package ru.arc.buildertools

import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.autobuild.BuildBookCodec
import ru.arc.autobuild.BuildBookData

internal data class BuilderLocatedBook(
    val slot: Int,
    val item: ItemStack,
    val data: BuildBookData,
)

internal object BuilderBookInventoryLocator {
    fun find(
        player: Player,
        expected: BuildBookData,
        canonicalize: (Int, ItemStack, BuildBookData) -> Pair<ItemStack, BuildBookData>,
    ): BuilderLocatedBook? {
        val held = player.inventory.heldItemSlot
        val slots = sequenceOf(held) + (0 until player.inventory.size).asSequence().filter { it != held }
        return slots.mapNotNull { slot ->
            val item = player.inventory.getItem(slot) ?: return@mapNotNull null
            val raw = BuildBookCodec.read(item) ?: return@mapNotNull null
            if (!mayRepresent(raw, expected)) return@mapNotNull null
            val (canonicalItem, canonicalData) = canonicalize(slot, item, raw)
            if (canonicalData == expected) BuilderLocatedBook(slot, canonicalItem, canonicalData) else null
        }.firstOrNull()
    }

    private fun mayRepresent(candidate: BuildBookData, expected: BuildBookData): Boolean =
        candidate.buildingId == expected.buildingId &&
            candidate.transform == expected.transform &&
            candidate.playerCreated == expected.playerCreated &&
            candidate.blueprintId == expected.blueprintId &&
            candidate.instanceId == expected.instanceId &&
            candidate.instanceGeneration == expected.instanceGeneration
}
