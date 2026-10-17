package ru.arc.autobuild

import ru.arc.buildertools.BuilderToolsConfiguration
import java.nio.file.Files
import java.nio.file.Path

internal object BuilderStoragePaths {
    /** Storage-worker operation: validates the real path and creates the selected root if needed. */
    fun schematicsRoot(configuredRoot: String? = null): Path {
        val prepared = BuilderToolsConfiguration.current()
        return schematicsRoot(prepared.dataRoot, configuredRoot ?: prepared.settings.schematicsRoot)
    }

    /** Captured generation inputs keep accepted storage work independent of plugin shutdown. */
    fun schematicsRoot(dataRoot: Path, configuredRoot: String?): Path {
        val candidate = resolveCandidate(dataRoot, configuredRoot?.trim()?.takeIf(String::isNotEmpty))
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

    /** Pure lexical resolution; the shared async service validates the real root on request. */
    fun resolveCandidate(dataRoot: Path, configuredRoot: String?): Path {
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
        val existingPath = checkNotNull(existing) { "ArcBuilder schematic root has no existing ancestor" }
        if (existingPath == candidate && Files.isSymbolicLink(candidate)) return
        val ancestor = existingPath.toRealPath()
        val pluginsRoot = checkNotNull(dataRoot.toAbsolutePath().normalize().parent).toRealPath()
        require(ancestor.startsWith(pluginsRoot)) { "ArcBuilder schematic root escapes the plugins directory" }
    }

    private fun requireRealPathInsidePlugins(dataRoot: Path, candidate: Path): Path {
        val pluginsRoot = checkNotNull(dataRoot.toAbsolutePath().normalize().parent).toRealPath()
        val real = candidate.toRealPath()
        require(Files.isSymbolicLink(candidate) || real.startsWith(pluginsRoot)) {
            "ArcBuilder schematic root escapes the plugins directory"
        }
        return real
    }
}
