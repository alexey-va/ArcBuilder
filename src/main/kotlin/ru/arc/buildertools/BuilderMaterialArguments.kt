package ru.arc.buildertools

import com.google.gson.JsonParser
import org.bukkit.Material
import java.util.Locale

/** Vanilla Russian names are bundled, so completion needs no network or client language. */
internal object BuilderMaterialArguments {
    private val russianLabels: Map<Material, String> = checkNotNull(
        javaClass.getResourceAsStream("/materials/ru_ru.json"),
    ).bufferedReader(Charsets.UTF_8).use { reader ->
        JsonParser.parseReader(reader).asJsonObject.entrySet().mapNotNull { (key, value) ->
            Material.matchMaterial(key.removePrefix("block.minecraft."))
                ?.takeIf { it.isBlock && !it.isLegacy }
                ?.let { it to value.asString }
        }.toMap()
    }
    private val russianNames = russianLabels.mapValues { (_, label) -> camelCase(label) }
    private val aliases = russianNames.entries.groupBy { normalize(it.value) }
        .filterValues { it.size == 1 }
        .mapValues { (_, entries) -> entries.single().key }

    fun parse(raw: String): Material? = Material.matchMaterial(raw) ?: aliases[normalize(raw)]

    fun names(materials: Iterable<Material>): List<String> = materials.flatMap { material ->
        val russian = russianNames[material]?.takeIf { aliases[normalize(it)] == material }
        listOfNotNull(russian, material.name.lowercase(Locale.ROOT))
    }

    fun russianLabel(material: Material): String? = russianLabels[material]

    /** Searches only the supplied candidates by Russian display text or English material id. */
    fun search(
        materials: Iterable<Material>,
        rawQuery: String,
        inventoryMaterials: Set<Material> = emptySet(),
        recentMaterials: List<Material> = emptyList(),
    ): List<Material> {
        val query = normalize(rawQuery)
        val recentOrder = recentMaterials.distinct().take(RECENT_LIMIT)
            .withIndex().associate { it.value to it.index }
        return materials.distinct().mapNotNull { material ->
            val russian = russianLabels[material].orEmpty()
            val english = material.name
            val rank = if (query.isEmpty()) {
                0
            } else when {
                sequenceOf(russian, english).any { normalize(it) == query } -> 0
                sequenceOf(russian, english).any { normalize(it).startsWith(query) } -> 1
                sequenceOf(russian, english).any { normalize(it).contains(query) } -> 2
                else -> null
            }
            rank?.let { material to it }
        }.sortedWith(
            compareBy<Pair<Material, Int>> { recentOrder[it.first] ?: Int.MAX_VALUE }
                .thenBy { if (query.isEmpty() && it.first in inventoryMaterials) 0 else 1 }
                .thenBy { it.second }
                .thenBy { normalize(russianLabels[it.first].orEmpty()) }
                .thenBy { it.first.name },
        ).map { it.first }
    }

    fun isRussianName(name: String): Boolean = name.firstOrNull()?.let { it in 'А'..'я' || it == 'Ё' || it == 'ё' } == true

    private fun camelCase(label: String): String = label.lowercase(Locale.ROOT)
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter(String::isNotEmpty)
        .mapIndexed { index, word -> if (index == 0) word else word.replaceFirstChar(Char::uppercaseChar) }
        .joinToString("")

    private fun normalize(raw: String): String = raw.trim().lowercase(Locale.ROOT)
        .removePrefix("minecraft:")
        .replace('ё', 'е').filter(Char::isLetterOrDigit)

    internal const val RECENT_LIMIT = 6
}
