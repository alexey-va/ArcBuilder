package ru.arc.autobuild

import org.bukkit.configuration.file.YamlConfiguration
import java.nio.file.Files
import java.nio.file.Path
import ru.arc.paper.api.ArcSchematicIds

internal data class SystemBuildBookDefinition(
    val buildingId: String,
    val title: String,
    val playerEnabled: Boolean,
    val materialsIncluded: Boolean,
    val starterEnabled: Boolean = false,
    val containerLootTableKey: String? = null,
    val aliases: List<String> = emptyList(),
) {
    fun validated(): SystemBuildBookDefinition = apply {
        require(buildingId.length <= 160 && ArcSchematicIds.isValid(buildingId)) {
            "System build-book building id is invalid"
        }
        require(aliases.all { it.length <= 160 && ArcSchematicIds.isValid(it) }) {
            "System build-book alias is invalid"
        }
        require(title.isNotBlank() && title.length <= 64 && title.none(Char::isISOControl)) {
            "System build-book title is invalid"
        }
        containerLootTableKey?.let { key ->
            require(key.length <= 256 && LOOT_TABLE_KEY.matches(key)) {
                "System build-book container loot-table key is invalid"
            }
        }
    }

    private companion object {
        val LOOT_TABLE_KEY = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")
    }
}

internal class SystemBuildBookCatalog private constructor(
    definitions: List<SystemBuildBookDefinition>,
) {
    private val definitions = buildMap {
        definitions.forEach { definition ->
            (listOf(definition.buildingId) + definition.aliases).forEach { id ->
                require(put(catalogueKey(id), definition) == null) {
                    "System build-book catalog contains duplicate ids or aliases"
                }
            }
        }
    }

    val enabledBuildingIds: List<String> = definitions.filter { it.playerEnabled }.map { it.buildingId }.sorted()
    val starterBuildingIds: List<String> = definitions
        .filter { it.playerEnabled && it.starterEnabled }
        .map { it.buildingId }
        .sorted()

    fun resolve(data: BuildBookData): SystemBuildBookDefinition? {
        if (data.playerCreated) return null
        val definition = definitions[catalogueKey(data.buildingId)]?.takeIf(SystemBuildBookDefinition::playerEnabled) ?: return null
        return definition
    }

    companion object {
        private const val MAX_CATALOG_ENTRIES = 65_536
        private const val MAX_CATALOG_BYTES = 64 * 1024 * 1024

        fun load(configPath: Path, @Suppress("UNUSED_PARAMETER") schematicsRoot: Path): SystemBuildBookCatalog {
            require(Files.isRegularFile(configPath)) { "System build-book catalog file is missing" }
            require(Files.size(configPath) <= MAX_CATALOG_BYTES) { "System build-book catalog exceeds 64 MiB" }
            val yaml = YamlConfiguration().apply {
                options().codePointLimit(MAX_CATALOG_BYTES)
                load(configPath.toFile())
            }
            val entries = yaml.getMapList("books")
            require(entries.size in 1..MAX_CATALOG_ENTRIES) { "System build-book catalog size is invalid" }
            val definitions = entries.map { raw ->
                SystemBuildBookDefinition(
                    buildingId = raw["building-id"] as? String
                        ?: throw IllegalArgumentException("System build-book building id is missing"),
                    title = raw["title"] as? String
                        ?: throw IllegalArgumentException("System build-book title is missing"),
                    playerEnabled = raw["player-enabled"] as? Boolean
                        ?: throw IllegalArgumentException("System build-book player-enabled flag is missing"),
                    materialsIncluded = raw["materials-included"]?.let { value ->
                        value as? Boolean
                            ?: throw IllegalArgumentException("System build-book materials-included flag is invalid")
                    } ?: true,
                    starterEnabled = raw["starter-enabled"]?.let { value ->
                        value as? Boolean
                            ?: throw IllegalArgumentException("System build-book starter-enabled flag is invalid")
                    } ?: false,
                    containerLootTableKey = raw["container-loot-table"]?.let { value ->
                        value as? String
                            ?: throw IllegalArgumentException("System build-book container loot-table key is invalid")
                    },
                    aliases = raw["aliases"]?.let { value ->
                        require(value is List<*> && value.all { it is String }) {
                            "System build-book aliases must be a list of ids"
                        }
                        value.map { it as String }
                    } ?: emptyList(),
                ).validated()
            }
            return SystemBuildBookCatalog(definitions)
        }

        private fun catalogueKey(id: String): String = when {
            id.endsWith(".schem") -> id.removeSuffix(".schem")
            id.endsWith(".schematic") -> id.removeSuffix(".schematic")
            else -> id
        }
    }
}
