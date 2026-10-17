package ru.arc.buildertools

import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.api.lowlevel.Compose
import org.snakeyaml.engine.v2.nodes.MappingNode
import org.snakeyaml.engine.v2.nodes.Node
import org.snakeyaml.engine.v2.nodes.ScalarNode
import org.snakeyaml.engine.v2.nodes.SequenceNode
import ru.arc.autobuild.BuildBookSettings
import ru.arc.autobuild.BuilderStoragePaths
import ru.arc.autobuild.SystemBuildBookCatalog
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.persistence.AtomicFileStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.Comparator
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor

internal enum class BuilderToolsReloadBlocker {
    STARTING_OR_RECOVERING,
    ACTIVE_OPERATION,
    PENDING_PREVIEW,
    VOLATILE_PLAYER_STATE,
    ACTIVE_CONSTRUCTION,
    DURABLE_BOOK_FLOW,
}

internal fun builderConstructionReloadBlocked(writesInFlight: Int, completionsInFlight: Int): Boolean {
    require(writesInFlight >= 0 && completionsInFlight >= 0)
    return writesInFlight > 0 || completionsInFlight > 0
}

internal sealed interface BuilderToolsReloadResult<out C> {
    data class Applied<C>(val config: C) : BuilderToolsReloadResult<C>
    data class Busy(val blocker: BuilderToolsReloadBlocker) : BuilderToolsReloadResult<Nothing>
    data class Rejected(val failure: Throwable, val rollbackFailure: Throwable? = null) : BuilderToolsReloadResult<Nothing>
    data object Cancelled : BuilderToolsReloadResult<Nothing>
}

/**
 * Main-thread transaction owner for one runtime generation. Only candidate preparation,
 * publication and publication rollback run on [executor]; all native lifecycle work is
 * resumed through [tasks]. There is at most one transaction in flight. Accepted storage
 * work drains before another generation can touch the same journals; close never waits.
 */
internal class BuilderToolsReloadService<C, R : AutoCloseable>(
    initialConfig: C,
    initialRuntime: R?,
    private val loadCandidate: () -> C,
    private val createRuntime: (C) -> R?,
    private val reloadBlocker: (R) -> BuilderToolsReloadBlocker?,
    private val publishConfig: (C) -> (() -> Unit),
    private val executor: Executor,
    private val tasks: LifecycleTaskScope,
    private val validateCandidate: (C) -> Unit = {},
    private val awaitClosed: (R) -> CompletionStage<Unit> = { CompletableFuture.completedFuture(Unit) },
    private val activationFailureDrain: (Throwable) -> CompletionStage<Unit> = { CompletableFuture.completedFuture(Unit) },
    private val runtimeChanged: (R?) -> Unit = {},
) : AutoCloseable {
    var config: C = initialConfig
        private set

    var runtime: R? = initialRuntime
        private set

    @Volatile
    var inProgress: Boolean = false
        private set
    private var completion: CompletableFuture<BuilderToolsReloadResult<C>>? = null
    private var closed = false

    fun reload(): CompletableFuture<BuilderToolsReloadResult<C>> {
        if (closed) return CompletableFuture.completedFuture(BuilderToolsReloadResult.Cancelled)
        if (inProgress) return CompletableFuture.completedFuture(
            BuilderToolsReloadResult.Busy(BuilderToolsReloadBlocker.STARTING_OR_RECOVERING),
        )
        runtime?.let(reloadBlocker)?.let {
            return CompletableFuture.completedFuture(BuilderToolsReloadResult.Busy(it))
        }
        val result = CompletableFuture<BuilderToolsReloadResult<C>>()
        completion = result
        inProgress = true
        val token = tasks.restart()
        prepare(token, loadCandidate) { candidate, failure ->
            if (failure != null) {
                finish(BuilderToolsReloadResult.Rejected(failure))
                return@prepare
            }
            val validated = checkNotNull(candidate)
            try {
                validateCandidate(validated)
            } catch (invalid: Throwable) {
                finish(BuilderToolsReloadResult.Rejected(invalid))
                return@prepare
            }
            // Gameplay may have started during disk preparation. Recheck before any close.
            runtime?.let(reloadBlocker)?.let {
                finish(BuilderToolsReloadResult.Busy(it))
                return@prepare
            }
            val previous = runtime
            setRuntime(null)
            try {
                previous?.close()
            } catch (closeFailure: Throwable) {
                finish(BuilderToolsReloadResult.Rejected(closeFailure, closeFailure))
                return@prepare
            }
            val drained = previous?.let(awaitClosed) ?: CompletableFuture.completedFuture(Unit)
            drained.whenCompleteSync(tasks, token) { _, drainFailure ->
                if (drainFailure != null) {
                    val cause = unwrap(drainFailure)
                    finish(BuilderToolsReloadResult.Rejected(cause, cause))
                } else {
                    publish(token, validated)
                }
            }
        }
        return result
    }

    private fun publish(token: LifecycleTaskScope.Token, candidate: C) {
        prepare(token, { publishConfig(candidate) }) { rollback, failure ->
            if (failure != null) {
                restore(token, failure, null)
                return@prepare
            }
            try {
                val replacement = createRuntime(candidate)
                config = candidate
                setRuntime(replacement)
                finish(BuilderToolsReloadResult.Applied(candidate))
            } catch (activationFailure: Throwable) {
                activationFailureDrain(activationFailure).whenCompleteSync(tasks, token) { _, drainFailure ->
                    if (drainFailure != null) {
                        val cause = unwrap(drainFailure)
                        activationFailure.addSuppressed(cause)
                        finish(BuilderToolsReloadResult.Rejected(activationFailure, cause))
                    } else {
                        restore(token, activationFailure, rollback)
                    }
                }
            }
        }
    }

    private fun restore(token: LifecycleTaskScope.Token, failure: Throwable, rollback: (() -> Unit)?) {
        prepare(token, { rollback?.invoke() }) { _, rollbackFailure ->
            var restoreFailure = rollbackFailure
                ?: (failure as? BuilderToolsConfigPublicationException)?.rollbackFailure
            // Never construct a runtime against a failed/unknown publication rollback.
            if (restoreFailure == null) {
                try {
                    setRuntime(createRuntime(config))
                } catch (runtimeFailure: Throwable) {
                    restoreFailure = runtimeFailure
                }
            }
            finish(BuilderToolsReloadResult.Rejected(failure, restoreFailure))
        }
    }

    private fun <T> prepare(
        token: LifecycleTaskScope.Token,
        action: () -> T,
        completed: (T?, Throwable?) -> Unit,
    ) {
        val work = try {
            CompletableFuture.supplyAsync({
                if (!tasks.isCurrent(token)) throw java.util.concurrent.CancellationException()
                action()
            }, executor)
        } catch (failure: Throwable) {
            completed(null, failure)
            return
        }
        work.whenCompleteSync(tasks, token) { value, failure -> completed(value, failure?.let(::unwrap)) }
    }

    private fun finish(result: BuilderToolsReloadResult<C>) {
        val pending = completion
        completion = null
        inProgress = false
        pending?.complete(result)
    }

    private fun setRuntime(value: R?) {
        runtime = value
        runtimeChanged(value)
    }

    override fun close() {
        if (closed) return
        closed = true
        tasks.close()
        val previous = runtime
        setRuntime(null)
        finish(BuilderToolsReloadResult.Cancelled)
        previous?.close()
    }

    private fun unwrap(failure: Throwable): Throwable =
        if (failure is CompletionException && failure.cause != null) checkNotNull(failure.cause) else failure
}

/** Strict, side-effect-free validation of the files consumed by a new runtime. */
internal object BuilderToolsReloadPreflight {
    fun load(dataRoot: Path): BuilderToolsConfig = prepare(dataRoot).settings.validated()

    fun prepare(dataRoot: Path): BuilderToolsPreparedConfiguration {
        val builderPath = ConfigManager.moduleYamlPath(dataRoot, "builder-tools.yml")
        val runtimePath = ConfigManager.moduleYamlPath(dataRoot, "builder-tools-runtime.yml")
        val autoBuildPath = ConfigManager.moduleYamlPath(dataRoot, "auto-build.yml")
        val systemBooksPath = dataRoot.resolve("modules/system-build-books.yml")
        val sourceHashes = listOf(builderPath, runtimePath, autoBuildPath, systemBooksPath)
            .associateWith(::builderConfigSourceHash)

        requireYamlMapping(builderPath, "builder-tools.yml").also { validateTypes(it, BUILDER_TYPES, "builder-tools.yml") }
        if (Files.exists(runtimePath)) {
            requireYamlMapping(runtimePath, "builder-tools-runtime.yml")
                .also { validateTypes(it, RUNTIME_OVERRIDE_TYPES, "builder-tools-runtime.yml") }
        }
        requireYamlMapping(autoBuildPath, "auto-build.yml").also {
            validateTypes(it, AUTO_BUILD_TYPES, "auto-build.yml")
        }
        requireYamlMapping(systemBooksPath, "system-build-books.yml")

        val candidate = loadMergedCandidate(dataRoot, builderPath, runtimePath, autoBuildPath)
        val schematicsRoot = BuilderStoragePaths.resolveCandidate(dataRoot, candidate.settings.schematicsRoot)
        val catalog = if (candidate.settings.enabled) SystemBuildBookCatalog.load(
            systemBooksPath,
            schematicsRoot,
        ) else null
        requireBuilderConfigSourcesUnchanged(sourceHashes)
        return candidate.copy(systemBooks = catalog, schematicsRoot = schematicsRoot, sourceHashes = sourceHashes)
    }

    /** Validates the merge-forward view without mutating the live operator files. */
    private fun loadMergedCandidate(
        dataRoot: Path,
        builderPath: Path,
        runtimePath: Path,
        autoBuildPath: Path,
    ): BuilderToolsPreparedConfiguration {
        val stagingRoot = Files.createTempDirectory(dataRoot, ".builder-reload-")
        return try {
            val modules = Files.createDirectories(stagingRoot.resolve(ConfigManager.MODULE_YAML_DIR))
            Files.copy(builderPath, modules.resolve("builder-tools.yml"), StandardCopyOption.REPLACE_EXISTING)
            Files.copy(autoBuildPath, modules.resolve("auto-build.yml"), StandardCopyOption.REPLACE_EXISTING)
            if (Files.isRegularFile(runtimePath)) {
                Files.copy(runtimePath, modules.resolve("builder-tools-runtime.yml"), StandardCopyOption.REPLACE_EXISTING)
            }
            BuilderToolsConfig.mergeBundledDefaults(stagingRoot)
            BuildBookSettings.mergeBundledDefaults(stagingRoot)
            val builder = Config(stagingRoot, "modules/builder-tools.yml")
            val autoBuild = Config(stagingRoot, "modules/auto-build.yml")
            val runtimeOverride = if (Files.isRegularFile(runtimePath)) {
                Config(stagingRoot, "modules/builder-tools-runtime.yml")
            } else null
            BuildBookSettings.validate(autoBuild, mergeForward = false)
            BuilderToolsPreparedConfiguration(
                dataRoot = dataRoot,
                settings = BuilderToolsConfig(builder, runtimeOverride).validated(validateNativeMaterials = false),
                builder = builder,
                autoBuild = autoBuild,
                systemBooks = null,
            )
        } finally {
            Files.walk(stagingRoot).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    private fun requireYamlMapping(path: Path, label: String): MappingNode {
        require(Files.isRegularFile(path)) { "$label is missing" }
        val content = Files.readString(path)
        // SnakeYAML Engine 3.0.1 can overrun its reader buffer at a split surrogate pair.
        val settings = LoadSettings.builder().setBufferSize(maxOf(1024, content.length + 1)).build()
        val document = Compose(settings).composeString(content).orElse(null)
        require(document is MappingNode) { "$label must contain a YAML mapping" }
        return document
    }

    private fun validateTypes(root: MappingNode, schema: Map<String, YamlValueType>, label: String) {
        schema.forEach { (path, expected) ->
            val node = findNode(root, path) ?: return@forEach
            require(expected.accepts(node)) { "$label value '$path' must be ${expected.description}" }
        }
    }

    private fun findNode(root: MappingNode, path: String): Node? {
        var current: Node = root
        path.split('.').forEach { part ->
            val mapping = current as? MappingNode ?: return null
            current = mapping.value
                .firstOrNull { (it.keyNode as? ScalarNode)?.value == part }
                ?.valueNode
                ?: return null
        }
        return current
    }

    private enum class YamlValueType(val description: String) {
        BOOLEAN("a boolean") {
            override fun accepts(node: Node): Boolean = scalar(node)?.trim()?.lowercase() in BOOLEAN_VALUES
        },
        INT("an integer") {
            override fun accepts(node: Node): Boolean = scalar(node)?.trim()?.toIntOrNull() != null
        },
        LONG("a long integer") {
            override fun accepts(node: Node): Boolean = scalar(node)?.trim()?.toLongOrNull() != null
        },
        DOUBLE("a finite number") {
            override fun accepts(node: Node): Boolean = scalar(node)?.trim()?.toDoubleOrNull()?.isFinite() == true
        },
        DURATION("a duration such as 30s or 1h 30m") {
            override fun accepts(node: Node): Boolean = scalar(node)?.matches(DURATION_VALUE) == true
        },
        DECIMAL("a decimal number") {
            override fun accepts(node: Node): Boolean = scalar(node)?.trim()?.toBigDecimalOrNull() != null
        },
        STRING("a scalar string") {
            override fun accepts(node: Node): Boolean = node is ScalarNode
        },
        STRING_LIST("a list of scalar strings") {
            override fun accepts(node: Node): Boolean = node is SequenceNode && node.value.all { it is ScalarNode }
        };

        abstract fun accepts(node: Node): Boolean

        companion object {
            private val BOOLEAN_VALUES = setOf("true", "false", "yes", "no", "1", "0")
            private val DURATION_VALUE = Regex(
                """\s*(?:\d+\s*(?:ms|s|sec|m|min|h|hour|d|day)?\s*)+""",
                RegexOption.IGNORE_CASE,
            )

            fun scalar(node: Node): String? = (node as? ScalarNode)?.value
        }
    }

    private val BUILDER_TYPES = mapOf(
        "enabled" to YamlValueType.BOOLEAN,
        "allowed-worlds" to YamlValueType.STRING_LIST,
        "default-locale" to YamlValueType.STRING,
        "locale.follow-client" to YamlValueType.BOOLEAN,
        "storage.schematics-root" to YamlValueType.STRING,
        "limits.max-changes" to YamlValueType.INT,
        "limits.max-clipboard-blocks" to YamlValueType.INT,
        "limits.max-scan-volume" to YamlValueType.LONG,
        "limits.absolute-max-axis" to YamlValueType.INT,
        "limits.blocks-per-tick" to YamlValueType.INT,
        "limits.base-hourly-changes" to YamlValueType.INT,
        "limits.maximum-range" to YamlValueType.DOUBLE,
        "timers.plan-ttl" to YamlValueType.DURATION,
        "timers.clipboard-ttl" to YamlValueType.DURATION,
        "timers.undo-ttl" to YamlValueType.DURATION,
        "timers.journal-retention" to YamlValueType.DURATION,
        "construction.container-radius" to YamlValueType.INT,
        "construction.online-inventory-range" to YamlValueType.DOUBLE,
        "construction.blocks-per-cycle" to YamlValueType.INT,
        "construction.tick-period-ticks" to YamlValueType.LONG,
        "construction.max-container-probes-per-tick" to YamlValueType.INT,
        "construction.max-cached-containers-per-project" to YamlValueType.INT,
        "construction.max-resolved-containers-per-call" to YamlValueType.INT,
        "construction.site.enabled" to YamlValueType.BOOLEAN,
        "construction.site.view-range" to YamlValueType.DOUBLE,
        "construction.site.outline.enabled" to YamlValueType.BOOLEAN,
        "construction.site.outline.material" to YamlValueType.STRING,
        "construction.site.outline.thickness" to YamlValueType.DOUBLE,
        "construction.site.outline.glow-color" to YamlValueType.STRING,
        "construction.site.panel.enabled" to YamlValueType.BOOLEAN,
        "construction.site.panel.face" to YamlValueType.STRING,
        "construction.site.panel.height-offset" to YamlValueType.DOUBLE,
        "construction.site.panel.front-offset" to YamlValueType.DOUBLE,
        "construction.site.panel.interaction-width" to YamlValueType.DOUBLE,
        "construction.site.panel.interaction-height" to YamlValueType.DOUBLE,
        "construction.site.panel.line-width" to YamlValueType.INT,
        "construction.site.panel.max-material-lines" to YamlValueType.INT,
        "construction.site.panel.background-color" to YamlValueType.STRING,
        "construction.site.menu.rows" to YamlValueType.INT,
        "construction.site.menu.refresh-period-ticks" to YamlValueType.LONG,
        "construction.site.menu.background-item" to YamlValueType.STRING,
        "construction.site.menu.background-fallback" to YamlValueType.STRING,
        "construction.site.menu.slots.overview" to YamlValueType.INT,
        "construction.site.menu.slots.progress" to YamlValueType.INT,
        "construction.site.menu.slots.resources" to YamlValueType.INT,
        "construction.site.menu.slots.control" to YamlValueType.INT,
        "construction.site.menu.materials.overview" to YamlValueType.STRING,
        "construction.site.menu.materials.progress" to YamlValueType.STRING,
        "construction.site.menu.materials.resources" to YamlValueType.STRING,
        "construction.site.menu.materials.pause" to YamlValueType.STRING,
        "construction.site.menu.materials.resume" to YamlValueType.STRING,
        "construction.site.menu.materials.unavailable" to YamlValueType.STRING,
        "construction.effects.enabled" to YamlValueType.BOOLEAN,
        "construction.effects.interval-blocks" to YamlValueType.INT,
        "construction.effects.sounds.enabled" to YamlValueType.BOOLEAN,
        "construction.effects.sounds.volume" to YamlValueType.DOUBLE,
        "construction.effects.sounds.pitch" to YamlValueType.DOUBLE,
        "construction.effects.particles.enabled" to YamlValueType.BOOLEAN,
        "construction.effects.particles.count" to YamlValueType.INT,
        "construction.effects.particles.spread" to YamlValueType.DOUBLE,
        "runtime.health-refresh-period-ticks" to YamlValueType.LONG,
        "runtime.player-recovery-retry-period-ticks" to YamlValueType.LONG,
        "runtime.progress-every-batches" to YamlValueType.INT,
        "preview.period-ticks" to YamlValueType.LONG,
        "selection-panel.distance" to YamlValueType.DOUBLE,
        "selection-panel.max-selection-distance" to YamlValueType.DOUBLE,
        "selection-panel.side-offset" to YamlValueType.DOUBLE,
        "selection-panel.height-offset" to YamlValueType.DOUBLE,
        "selection-panel.row-spacing" to YamlValueType.DOUBLE,
        "selection-panel.column-spacing" to YamlValueType.DOUBLE,
        "selection-panel.button-width" to YamlValueType.DOUBLE,
        "selection-panel.button-height" to YamlValueType.DOUBLE,
        "selection-panel.label-scale" to YamlValueType.DOUBLE,
        "selection-panel.reach" to YamlValueType.DOUBLE,
        "selection-panel.suppress-selection-glow" to YamlValueType.BOOLEAN,
        "selection-panel.obstacle-max-shift" to YamlValueType.DOUBLE,
        "preview.movement-period-ticks" to YamlValueType.LONG,
        "preview.radius" to YamlValueType.DOUBLE,
        "preview.outline-spacing" to YamlValueType.DOUBLE,
        "preview.max-selection-particles" to YamlValueType.INT,
        "preview.max-plan-displays" to YamlValueType.INT,
        "preview.anti-z-fighting" to YamlValueType.BOOLEAN,
        "preview.block-display-scale" to YamlValueType.DOUBLE,
        "preview.plan-display-range" to YamlValueType.DOUBLE,
        "preview.guidance-period-ticks" to YamlValueType.LONG,
        "preview.plan-title.fade-in-ticks" to YamlValueType.INT,
        "preview.plan-title.stay-ticks" to YamlValueType.INT,
        "preview.plan-title.fade-out-ticks" to YamlValueType.INT,
        "shop.enabled" to YamlValueType.BOOLEAN,
        "shop.max-quoted-materials" to YamlValueType.INT,
        "shop.max-auto-buy-items" to YamlValueType.INT,
        "shop.max-auto-buy-price" to YamlValueType.DECIMAL,
        "book-contracts.enabled" to YamlValueType.BOOLEAN,
        "book-contracts.construction-markup-percent" to YamlValueType.DECIMAL,
        "book-contracts.max-issue-price" to YamlValueType.DECIMAL,
        "book-contracts.auction-recovery-retry" to YamlValueType.DURATION,
        "book-contracts.player-materials-summary-limit" to YamlValueType.INT,
        "book-contracts.mysql.enabled" to YamlValueType.BOOLEAN,
        "book-contracts.mysql.host" to YamlValueType.STRING,
        "book-contracts.mysql.port" to YamlValueType.INT,
        "book-contracts.mysql.database" to YamlValueType.STRING,
        "book-contracts.mysql.username" to YamlValueType.STRING,
        "book-contracts.mysql.password" to YamlValueType.STRING,
        "book-contracts.mysql.ssl-mode" to YamlValueType.STRING,
        "book-contracts.mysql.fail-fast" to YamlValueType.BOOLEAN,
        "book-contracts.mysql.pool.minimum-idle" to YamlValueType.INT,
        "book-contracts.mysql.pool.maximum-size" to YamlValueType.INT,
        "book-contracts.mysql.pool.connection-timeout-ms" to YamlValueType.LONG,
        "book-contracts.mysql.pool.socket-timeout-ms" to YamlValueType.LONG,
        "book-contracts.mysql.pool.validation-timeout-ms" to YamlValueType.LONG,
        "book-contracts.mysql.pool.max-lifetime-ms" to YamlValueType.LONG,
        "safety.require-lands" to YamlValueType.BOOLEAN,
        "safety.require-coreprotect" to YamlValueType.BOOLEAN,
        "safety.replaceable-materials" to YamlValueType.STRING_LIST,
    )

    private val RUNTIME_OVERRIDE_TYPES = BUILDER_TYPES.filterKeys {
        it == "enabled" || it == "allowed-worlds" || it == "storage.schematics-root" ||
            it == "book-contracts.enabled" || it == "book-contracts.construction-markup-percent" ||
            it == "book-contracts.max-issue-price" || it.startsWith("book-contracts.mysql.")
    }

    private val AUTO_BUILD_TYPES = mapOf(
        "build-book.player-copy.max-offset" to YamlValueType.INT,
        "build-book.player-copy.max-per-player" to YamlValueType.INT,
        "build-book.player-copy.custom-model-data" to YamlValueType.INT,
        "build-book.player-copy.draft-custom-model-data" to YamlValueType.INT,
        "build-book.player-copy.active-custom-model-data" to YamlValueType.INT,
        "build-book.player-copy.active-custom-model-data-pool" to YamlValueType.STRING_LIST,
        "build-book.lore" to YamlValueType.STRING_LIST,
        "build-book.footer" to YamlValueType.STRING_LIST,
        "build-book.editor.overview.lore" to YamlValueType.STRING_LIST,
        "build-book.editor.axis-x.lore" to YamlValueType.STRING_LIST,
        "build-book.editor.axis-y.lore" to YamlValueType.STRING_LIST,
        "build-book.editor.axis-z.lore" to YamlValueType.STRING_LIST,
        "build-book.editor.rotation.lore" to YamlValueType.STRING_LIST,
        "build-book.editor.reset.lore" to YamlValueType.STRING_LIST,
        "build-book.editor.copy.lore" to YamlValueType.STRING_LIST,
        "build-book.editor.preview-inactive.lore" to YamlValueType.STRING_LIST,
        "build-book.editor.preview-book-mismatch.lore" to YamlValueType.STRING_LIST,
        "build-book.editor.preview-protection-denied.lore" to YamlValueType.STRING_LIST,
    )
}

internal class BuilderToolsConfigPublicationException(
    cause: Throwable,
    val rollbackFailure: Throwable?,
) : RuntimeException("Builder-tools configuration publication failed", cause)

/**
 * Merge-forwards both live YAML files with best-effort cross-file rollback. Each individual
 * replacement is atomic; if either merge fails, the exact previous bytes are restored.
 * Config trees are not reloaded in place: the module installs its prepared generation on main.
 */
internal object BuilderToolsConfigPublication {
    fun publish(prepared: BuilderToolsPreparedConfiguration): () -> Unit {
        requireBuilderConfigSourcesUnchanged(prepared.sourceHashes)
        return publish(prepared.dataRoot)
    }

    /** Worker only. The returned rollback restores exact pre-publication bytes and permissions. */
    fun publish(dataRoot: Path): () -> Unit {
        val paths = listOf(
            ConfigManager.moduleYamlPath(dataRoot, "builder-tools.yml"),
            ConfigManager.moduleYamlPath(dataRoot, "auto-build.yml"),
        )
        val snapshots = paths.associateWith(::snapshot)
        try {
            BuilderToolsConfig.mergeBundledDefaults(dataRoot)
            BuildBookSettings.mergeBundledDefaults(dataRoot)
        } catch (failure: Throwable) {
            val rollbackFailure = runCatching { restoreAll(snapshots) }.exceptionOrNull()
            throw BuilderToolsConfigPublicationException(failure, rollbackFailure)
        }
        return { restoreAll(snapshots) }
    }

    private fun restoreAll(snapshots: Map<Path, FileSnapshot>) {
        var firstFailure: Throwable? = null
        snapshots.forEach { (path, snapshot) ->
            runCatching { restore(path, snapshot) }.exceptionOrNull()?.let { failure ->
                val previous = firstFailure
                if (previous == null) firstFailure = failure else previous.addSuppressed(failure)
            }
        }
        firstFailure?.let { throw it }
    }

    private fun snapshot(path: Path): FileSnapshot {
        require(Files.isRegularFile(path)) { "Builder-tools publication source is missing: ${path.fileName}" }
        val size = Files.size(path)
        require(size <= MAX_CONFIG_SNAPSHOT_BYTES) { "Builder-tools configuration is too large: ${path.fileName}" }
        val permissions = runCatching { Files.getPosixFilePermissions(path) }.getOrNull()
        val bytes = Files.readAllBytes(path)
        require(bytes.size.toLong() == size) { "Builder-tools configuration changed while being snapshotted: ${path.fileName}" }
        return FileSnapshot(bytes, permissions)
    }

    private fun restore(path: Path, snapshot: FileSnapshot) {
        val parent = checkNotNull(path.parent) { "Builder-tools publication path has no parent" }
        AtomicFileStore(
            root = parent,
            relativePath = path.fileName,
            maxBytes = MAX_CONFIG_SNAPSHOT_BYTES,
            encode = { bytes -> bytes.copyOf() },
            decode = { bytes -> bytes.copyOf() },
            validate = { restored ->
                require(restored.contentEquals(snapshot.bytes)) {
                    "Builder-tools configuration rollback readback differs: ${path.fileName}"
                }
            },
        ).write(snapshot.bytes)
        snapshot.permissions?.let { Files.setPosixFilePermissions(path, it) }
    }

    private data class FileSnapshot(
        val bytes: ByteArray,
        val permissions: Set<PosixFilePermission>?,
    )

    private const val MAX_CONFIG_SNAPSHOT_BYTES = 4L * 1024L * 1024L
}


/** Worker-only content fence: delayed activation must publish the files it actually validated. */
private fun requireBuilderConfigSourcesUnchanged(expected: Map<Path, String?>) {
    expected.forEach { (path, hash) ->
        require(builderConfigSourceHash(path) == hash) {
            "Builder-tools configuration changed during preparation: ${path.fileName}; retry reload"
        }
    }
}

private fun builderConfigSourceHash(path: Path): String? {
    if (!Files.isRegularFile(path)) return null
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return java.util.HexFormat.of().formatHex(digest.digest())
}
