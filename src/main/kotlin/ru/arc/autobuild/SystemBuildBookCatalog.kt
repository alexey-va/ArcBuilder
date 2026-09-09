package ru.arc.autobuild

import org.bukkit.configuration.file.YamlConfiguration
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

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
        require(BUILDING_ID.matches(buildingId) && '/' !in buildingId && '\\' !in buildingId && ".." !in buildingId) {
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
        val BUILDING_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,159}\\.(?:schem|schematic)")
        val SHA256 = Regex("[a-f0-9]{64}")
        val LOOT_TABLE_KEY = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")
    }
}

internal class SystemBuildBookCatalog private constructor(
    private val schematicsRoot: Path,
    definitions: List<SystemBuildBookDefinition>,
) {
    private val definitions = definitions.associateBy(SystemBuildBookDefinition::buildingId)

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
        val definition = definitions[data.buildingId]?.takeIf(SystemBuildBookDefinition::playerEnabled) ?: return null
        val file = secureSchematic(definition.buildingId) ?: return null
        return definition.takeIf { sha256(file) == definition.schematicSha256 }
    }

    private fun secureSchematic(buildingId: String): Path? {
        val candidate = schematicsRoot.resolve(buildingId).normalize()
        if (!candidate.startsWith(schematicsRoot) || !Files.isRegularFile(candidate)) return null
        val real = runCatching(candidate::toRealPath).getOrNull() ?: return null
        return real.takeIf { it.startsWith(schematicsRoot) && Files.size(it) in 1..MAX_SCHEMATIC_BYTES }
    }

    companion object {
        private const val MAX_SCHEMATIC_BYTES = 64L * 1024L * 1024L

        fun load(configPath: Path, schematicsRoot: Path): SystemBuildBookCatalog {
            require(Files.isRegularFile(configPath)) { "System build-book catalog file is missing" }
            val root = schematicsRoot.toRealPath()
            require(Files.isDirectory(root)) { "System build-book schematic root is missing" }
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
                    } ?: false,
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
            val catalog = SystemBuildBookCatalog(root, definitions)
            definitions.forEach { definition ->
                val file = catalog.secureSchematic(definition.buildingId)
                    ?: throw IllegalArgumentException("System build-book schematic is missing: ${definition.buildingId}")
                require(sha256(file) == definition.schematicSha256) {
                    "System build-book schematic digest mismatch: ${definition.buildingId}"
                }
            }
            return catalog
        }

        private fun sha256(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
