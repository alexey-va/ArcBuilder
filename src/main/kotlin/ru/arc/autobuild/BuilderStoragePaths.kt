package ru.arc.autobuild

import ru.arc.ARC
import ru.arc.config.ConfigManager
import java.nio.file.Files
import java.nio.file.Path

internal object BuilderStoragePaths {
    fun schematicsRoot(): Path {
        val dataRoot = ARC.instance.dataPath.toAbsolutePath().normalize()
        val runtime = ConfigManager.ofModule(dataRoot, "builder-tools-runtime.yml")
        val configured = runtime.stringOrNull("storage.schematics-root")?.trim()?.takeIf(String::isNotEmpty)
        val candidate = if (configured != null) dataRoot.resolve(configured).normalize() else dataRoot.resolve("schematics")
        val pluginsRoot = checkNotNull(dataRoot.parent).normalize()
        require(candidate.startsWith(pluginsRoot)) { "ArcBuilder schematic root must stay below the plugins directory" }
        Files.createDirectories(candidate)
        return candidate.toRealPath()
    }
}
