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
        val queryWords = words(rawQuery)
        val recentOrder = recentMaterials.distinct().take(RECENT_LIMIT)
            .withIndex().associate { it.value to it.index }
        return materials.distinct().mapNotNull { material ->
            val rank = if (query.isEmpty()) 0 else matchRank(
                query, queryWords, listOfNotNull(russianLabels[material], material.name),
            )
            rank?.let { material to it }
        }.sortedWith(
            compareBy<Pair<Material, Int>> { if (query.isEmpty() && it.first == Material.AIR) 0 else 1 }
                .thenBy { it.second }
                .thenBy { if (query.isEmpty()) 0 else normalize(it.first.name).length }
                .thenBy { recentOrder[it.first] ?: Int.MAX_VALUE }
                .thenBy { if (query.isEmpty() && it.first in inventoryMaterials) 0 else 1 }
                .thenBy { normalize(russianLabels[it.first].orEmpty()) }
                .thenBy { it.first.name },
        ).map { it.first }
    }

    private fun matchRank(query: String, queryWords: List<String>, labels: List<String>): Int? {
        val names = labels.map(::normalize)
        if (query in names) return 0
        if (names.any { it.startsWith(query) }) return 1
        val labelWords = labels.map(::words)
        if (labelWords.any { tokens ->
            tokens.windowed(queryWords.size).any { window ->
                window.zip(queryWords).all { (word, prefix) -> word.startsWith(prefix) }
            }
        }) return 2
        if (names.any { it.contains(query) }) return 3
        if (query.length < 4) return null
        val maximumDistance = if (query.length >= 7) 2 else 1
        val whole = names.mapNotNull { typoDistance(query, it, maximumDistance)?.let { distance -> 2 + 2 * distance } }
        val words = labelWords.flatten().mapNotNull {
            typoDistance(query, it, maximumDistance)?.let { distance -> 3 + 2 * distance }
        }
        return (whole + words).minOrNull()
    }

    /** Small bounded edit distance, including a mistyped adjacent letter pair. */
    private fun typoDistance(query: String, candidate: String, maximum: Int): Int? {
        if (kotlin.math.abs(query.length - candidate.length) > maximum) return null
        var previous = IntArray(candidate.length + 1) { it }
        var beforePrevious = previous
        for (i in query.indices) {
            val row = IntArray(candidate.length + 1)
            row[0] = i + 1
            for (j in candidate.indices) {
                row[j + 1] = minOf(previous[j + 1] + 1, row[j] + 1,
                    previous[j] + if (query[i] == candidate[j]) 0 else 1)
                if (i > 0 && j > 0 && query[i] == candidate[j - 1] && query[i - 1] == candidate[j]) {
                    row[j + 1] = minOf(row[j + 1], beforePrevious[j - 1] + 1)
                }
            }
            if (row.minOrNull()!! > maximum) return null
            beforePrevious = previous
            previous = row
        }
        return previous.last().takeIf { it <= maximum }
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

    private fun words(raw: String): List<String> = raw.trim().lowercase(Locale.ROOT)
        .removePrefix("minecraft:").split(WORD_SEPARATOR).map(::normalize).filter(String::isNotEmpty)

    private val WORD_SEPARATOR = Regex("[^\\p{L}\\p{N}]+")
    internal const val RECENT_LIMIT = 6
}
