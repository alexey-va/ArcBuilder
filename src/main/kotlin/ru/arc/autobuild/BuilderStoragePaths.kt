package ru.arc.autobuild

import ru.arc.ARC
import ru.arc.config.ConfigManager
import java.nio.file.Files
import java.nio.file.Path

internal object BuilderStoragePaths {
    fun schematicsRoot(configuredRoot: String? = null): Path {
        val dataRoot = ARC.instance.dataPath
        val runtimePath = ConfigManager.moduleYamlPath(dataRoot, "builder-tools-runtime.yml")
        val runtime = if (Files.isRegularFile(runtimePath)) {
            ConfigManager.ofModule(dataRoot, "builder-tools-runtime.yml")
        } else {
            null
        }
        val base = ConfigManager.ofModule(dataRoot, "builder-tools.yml")
        val configured = configuredRoot?.trim()?.takeIf(String::isNotEmpty)
            ?: runtime?.stringOrNull("storage.schematics-root")?.trim()?.takeIf(String::isNotEmpty)
            ?: base.stringOrNull("storage.schematics-root")?.trim()?.takeIf(String::isNotEmpty)
        val candidate = resolveCandidate(dataRoot, configured)
        requireExistingAncestorInsidePlugins(dataRoot, candidate)
        Files.createDirectories(candidate)
        return requireRealPathInsidePlugins(dataRoot, candidate)
    }

    /** Resolves and verifies an existing root without creating files or directories. */
    fun validateSchematicsRoot(dataRoot: Path, configuredRoot: String?): Path {
        val candidate = resolveCandidate(dataRoot, configuredRoot?.trim()?.takeIf(String::isNotEmpty))
        require(Files.isDirectory(candidate)) { "ArcBuilder schematic root does not exist" }
        return requireRealPathInsidePlugins(dataRoot, candidate)
    }

    private fun resolveCandidate(dataRoot: Path, configuredRoot: String?): Path {
        val normalizedDataRoot = dataRoot.toAbsolutePath().normalize()
        val candidate = if (configuredRoot != null) {
            normalizedDataRoot.resolve(configuredRoot).normalize()
        } else {
            normalizedDataRoot.resolve("schematics")
        }
        val pluginsRoot = checkNotNull(normalizedDataRoot.parent).normalize()
        require(candidate.startsWith(pluginsRoot)) { "ArcBuilder schematic root must stay below the plugins directory" }
        return candidate
    }

    private fun requireExistingAncestorInsidePlugins(dataRoot: Path, candidate: Path) {
        var existing: Path? = candidate
        while (existing != null && !Files.exists(existing)) existing = existing.parent
        val ancestor = checkNotNull(existing) { "ArcBuilder schematic root has no existing ancestor" }.toRealPath()
        val pluginsRoot = checkNotNull(dataRoot.toAbsolutePath().normalize().parent).toRealPath()
        require(ancestor.startsWith(pluginsRoot)) { "ArcBuilder schematic root escapes the plugins directory" }
    }

    private fun requireRealPathInsidePlugins(dataRoot: Path, candidate: Path): Path {
        val pluginsRoot = checkNotNull(dataRoot.toAbsolutePath().normalize().parent).toRealPath()
        val real = candidate.toRealPath()
        require(real.startsWith(pluginsRoot)) { "ArcBuilder schematic root escapes the plugins directory" }
        return real
    }
}
