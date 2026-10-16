package ru.arc.autobuild

import org.bukkit.configuration.file.YamlConfiguration
import java.nio.file.Files
import java.nio.file.Path
import ru.arc.paper.api.ArcSchematicIds

internal data class SystemBuildBookDefinition(
    val buildingId: String,
    val title: String,
    val schematicSha256: String,
    val playerEnabled: Boolean,
    val materialsIncluded: Boolean,
    val starterEnabled: Boolean = false,
    val containerLootTableKey: String? = null,
) {
    fun validated(): SystemBuildBookDefinition = apply {
        require(buildingId.length <= 160 && ArcSchematicIds.isValid(buildingId)) {
            "System build-book building id is invalid"
        }
        require(title.isNotBlank() && title.length <= 64 && title.none(Char::isISOControl)) {
            "System build-book title is invalid"
        }
        require(SHA256.matches(schematicSha256)) { "System build-book schematic digest is invalid" }
        containerLootTableKey?.let { key ->
            require(key.length <= 256 && LOOT_TABLE_KEY.matches(key)) {
                "System build-book container loot-table key is invalid"
            }
        }
    }

    private companion object {
        val SHA256 = Regex("[a-f0-9]{64}")
        val LOOT_TABLE_KEY = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")
    }
}

internal class SystemBuildBookCatalog private constructor(
    definitions: List<SystemBuildBookDefinition>,
) {
    private val definitions = definitions.associateBy { catalogueKey(it.buildingId) }

    val enabledBuildingIds: List<String> = definitions.filter { it.playerEnabled }.map { it.buildingId }.sorted()
    val starterBuildingIds: List<String> = definitions
        .filter { it.playerEnabled && it.starterEnabled }
        .map { it.buildingId }
        .sorted()

    init {
        require(definitions.size in 1..256) { "System build-book catalog size is invalid" }
        require(this.definitions.size == definitions.size) { "System build-book catalog contains duplicate ids" }
    }

    fun resolve(data: BuildBookData): SystemBuildBookDefinition? {
        if (data.playerCreated) return null
        val definition = definitions[catalogueKey(data.buildingId)]?.takeIf(SystemBuildBookDefinition::playerEnabled) ?: return null
        return definition
    }

    companion object {
        fun load(configPath: Path, @Suppress("UNUSED_PARAMETER") schematicsRoot: Path): SystemBuildBookCatalog {
            require(Files.isRegularFile(configPath)) { "System build-book catalog file is missing" }
            val yaml = YamlConfiguration.loadConfiguration(configPath.toFile())
            val definitions = yaml.getMapList("books").map { raw ->
                SystemBuildBookDefinition(
                    buildingId = raw["building-id"] as? String
                        ?: throw IllegalArgumentException("System build-book building id is missing"),
                    title = raw["title"] as? String
                        ?: throw IllegalArgumentException("System build-book title is missing"),
                    schematicSha256 = raw["sha256"] as? String
                        ?: throw IllegalArgumentException("System build-book digest is missing"),
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
