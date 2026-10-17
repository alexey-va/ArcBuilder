package ru.arc.buildertools

import ru.arc.autobuild.SystemBuildBookCatalog
import ru.arc.config.Config
import ru.arc.text.LocalizedMiniMessage
import java.nio.file.Path

/** Prepared entirely on the configuration worker; its Config trees are never reloaded in place. */
internal data class BuilderToolsPreparedConfiguration(
    val dataRoot: Path,
    val settings: BuilderToolsConfig,
    val builder: Config,
    val autoBuild: Config,
    val systemBooks: SystemBuildBookCatalog?,
    val schematicsRoot: Path? = null,
    val messages: LocalizedMiniMessage = settings.messages(),
    val sourceHashes: Map<Path, String?> = emptyMap(),
)

/** Native consumers read one already-loaded generation without probing the filesystem. */
internal object BuilderToolsConfiguration {
    @Volatile
    private var active: BuilderToolsPreparedConfiguration? = null

    fun current(dataRoot: Path? = null): BuilderToolsPreparedConfiguration =
        checkNotNull(active?.takeIf { dataRoot == null || it.dataRoot == dataRoot }) { "Builder-tools configuration is not prepared" }

    fun builder(dataRoot: Path): Config = current(dataRoot).builder

    fun autoBuild(): Config = current().autoBuild

    fun install(prepared: BuilderToolsPreparedConfiguration) {
        active = prepared
    }

    fun clear() {
        active = null
    }
}
