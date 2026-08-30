package ru.arc.buildertools

import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.hooks.economyshop.ShopPurchaseStatus
import ru.arc.text.LocaleCatalog
import ru.arc.text.LocaleRequirements
import ru.arc.text.LocalizedMiniMessage
import ru.arc.sql.SqlModuleConfig
import ru.arc.sql.SqlSslMode
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.util.Locale

class BuilderToolsConfig(
    private val config: Config,
    private val runtimeOverride: Config? = null,
) {
    val enabled: Boolean get() = runtimeOverride?.bool("enabled", config.bool("enabled", false)) ?: config.bool("enabled", false)
    val allowedWorlds: Set<String>
        get() = (runtimeOverride?.stringListOrNull("allowed-worlds") ?: config.stringList("allowed-worlds"))
            .map { it.lowercase(Locale.ROOT) }
            .toSet()
    val maxChanges: Int get() = config.integer("limits.max-changes", 4_096)
    val maxClipboardBlocks: Int get() = config.integer("limits.max-clipboard-blocks", 4_096)
    val maxScanVolume: Long get() = config.long("limits.max-scan-volume", 8_192L)
    val absoluteMaxAxis: Int get() = config.integer("limits.absolute-max-axis", 48)
    val blocksPerTick: Int get() = config.integer("limits.blocks-per-tick", 16)
    val baseHourlyChanges: Int get() = config.integer("limits.base-hourly-changes", 20_000)
    val maximumRange: Double get() = config.double("limits.maximum-range", 64.0)
    val constructionContainerRadius: Int get() = config.integer("construction.container-radius", 4)
    val constructionOnlineInventoryRange: Double get() = config.double("construction.online-inventory-range", 48.0)
    val constructionTickPeriod: Long get() = config.long("construction.tick-period-ticks", 1L)
    val previewPeriodTicks: Long get() = config.long("preview.period-ticks", 10L)
    val previewRadius: Double get() = config.double("preview.radius", 32.0)
    val previewSpacing: Double get() = config.double("preview.outline-spacing", 0.75)
    val previewMaxSelectionParticles: Int get() = config.integer("preview.max-selection-particles", 512)
    val previewMaxPlanParticles: Int get() = config.integer("preview.max-plan-particles", 180)
    val shopEnabled: Boolean get() = config.bool("shop.enabled", true)
    val shopMaxQuotedMaterials: Int get() = config.integer("shop.max-quoted-materials", 64)
    val shopMaxAutoBuyItems: Int get() = config.integer("shop.max-auto-buy-items", 4_096)
    val shopMaxAutoBuyPriceMinor: Long
        get() = BuilderMoney.parseMinor(config.string("shop.max-auto-buy-price", "250000.00"))
    val bookContractsEnabled: Boolean
        get() = runtimeOverride?.booleanOrNull("book-contracts.enabled")
            ?: config.bool("book-contracts.enabled", false)
    val bookConstructionMarkupBasisPoints: Int
        get() = BigDecimal(
            runtimeOverride?.stringOrNull("book-contracts.construction-markup-percent")
                ?: config.string("book-contracts.construction-markup-percent", "15.00"),
        )
            .movePointRight(2)
            .setScale(0, RoundingMode.UNNECESSARY)
            .intValueExact()
    val bookMaxIssuePriceMinor: Long
        get() = BuilderMoney.parseMinor(
            runtimeOverride?.stringOrNull("book-contracts.max-issue-price")
                ?: config.string("book-contracts.max-issue-price", "50000000.00"),
        )
    val planTtl: Duration get() = config.duration("timers.plan-ttl", Duration.ofSeconds(30))
    val clipboardTtl: Duration get() = config.duration("timers.clipboard-ttl", Duration.ofMinutes(15))
    val undoTtl: Duration get() = config.duration("timers.undo-ttl", Duration.ofMinutes(30))
    val journalRetention: Duration get() = config.duration("timers.journal-retention", Duration.ofHours(2))
    val requireLands: Boolean get() = false
    val requireCoreProtect: Boolean get() = config.bool("safety.require-coreprotect", true)
    val replaceableMaterials: Set<String>
        get() = config.stringList("safety.replaceable-materials").map { it.uppercase(Locale.ROOT) }.toSet()

    fun allowsWorld(worldName: String): Boolean =
        "*" in allowedWorlds || worldName.lowercase(Locale.ROOT) in allowedWorlds

    fun validated(): BuilderToolsConfig = apply {
        if (!enabled) return@apply
        require(allowedWorlds.isNotEmpty() && allowedWorlds.all { it == "*" || WORLD_NAME.matches(it) }) {
            "Builder-tools allowed-worlds must contain safe world names or a wildcard"
        }
        require("*" !in allowedWorlds || allowedWorlds.size == 1) {
            "Builder-tools world wildcard must be the only allowed-worlds entry"
        }
        require(maxChanges in 1..BuilderPlan.ABSOLUTE_MAX_CHANGES) { "Builder-tools max-changes is invalid" }
        require(maxClipboardBlocks in 1..BuilderPlan.ABSOLUTE_MAX_CHANGES) { "Builder-tools clipboard limit is invalid" }
        require(maxClipboardBlocks <= maxChanges) {
            "Builder-tools clipboard limit cannot exceed the per-operation change limit"
        }
        require(maxScanVolume in maxChanges.toLong()..1_000_000L) { "Builder-tools scan volume limit is invalid" }
        require(absoluteMaxAxis in 3..100) { "Builder-tools maximum axis is invalid" }
        require(blocksPerTick in 1..256) { "Builder-tools blocks-per-tick is invalid" }
        require(baseHourlyChanges in maxChanges..200_000) { "Builder-tools hourly limit is invalid" }
        require(maximumRange.isFinite() && maximumRange in 8.0..128.0) { "Builder-tools maximum range is invalid" }
        require(constructionContainerRadius in 1..16) { "Builder construction container radius is invalid" }
        require(constructionOnlineInventoryRange.isFinite() && constructionOnlineInventoryRange in 1.0..128.0) {
            "Builder construction online inventory range is invalid"
        }
        require(constructionTickPeriod in 1L..100L) { "Builder construction tick period is invalid" }
        require(previewPeriodTicks in 5L..40L) { "Builder-tools preview period is invalid" }
        require(previewRadius.isFinite() && previewRadius in 8.0..64.0) { "Builder-tools preview radius is invalid" }
        require(previewSpacing.isFinite() && previewSpacing in 0.25..2.0) { "Builder-tools preview spacing is invalid" }
        require(previewMaxSelectionParticles in 48..1_024) { "Builder-tools selection preview limit is invalid" }
        require(previewMaxPlanParticles in 32..512) { "Builder-tools plan preview limit is invalid" }
        require(shopMaxAutoBuyItems in 1..BuilderPlan.ABSOLUTE_MAX_ITEMS.toInt()) {
            "Builder-tools shop item limit is invalid"
        }
        require(shopMaxQuotedMaterials in 1..256) { "Builder-tools quoted material limit is invalid" }
        require(shopMaxAutoBuyPriceMinor in 100L..100_000_000_000L) {
            "Builder-tools shop price limit is invalid"
        }
        if (bookContractsEnabled) {
            require(shopEnabled) { "Builder-book contracts require admin-shop pricing" }
            require(bookConstructionMarkupBasisPoints in 0..10_000) {
                "Builder-book construction markup must be between 0 and 100 percent"
            }
            require(bookMaxIssuePriceMinor in 1..BuilderBookBlueprint.MAX_PRICE_MINOR) {
                "Builder-book maximum issue price is invalid"
            }
            require(bookSqlConfig().enabled) { "Builder-book contracts require MySQL" }
            bookSqlConfig().connection()
        }
        require(planTtl in Duration.ofSeconds(10)..Duration.ofMinutes(2)) { "Builder-tools plan TTL is invalid" }
        require(clipboardTtl in Duration.ofMinutes(1)..Duration.ofHours(2)) { "Builder-tools clipboard TTL is invalid" }
        require(undoTtl in Duration.ofMinutes(1)..Duration.ofHours(2)) { "Builder-tools undo TTL is invalid" }
        require(journalRetention >= undoTtl && journalRetention <= Duration.ofDays(1)) {
            "Builder-tools journal retention must cover undo and remain bounded"
        }
        require(replaceableMaterials.isNotEmpty()) { "Builder-tools replaceable material list cannot be empty" }
        messages().validate(MESSAGE_REQUIREMENTS)
    }

    fun messages(): LocalizedMiniMessage {
        val catalogs = config.keys("locales").associateWith { locale -> PrefixLocaleCatalog(config, "locales.$locale") }
        return LocalizedMiniMessage(
            catalogs = catalogs,
            defaultLocale = { config.string("default-locale", "ru") },
            missingMessage = { "<red>Missing builder-tools message: $it" },
        )
    }

    fun bookSqlConfig(): SqlModuleConfig = LayeredSqlModuleConfig(
        base = config,
        override = runtimeOverride,
        prefix = "book-contracts.mysql",
    )

    companion object {
        private val WORLD_NAME = Regex("[A-Za-z0-9_./-]{1,128}")
        private val MESSAGE_REQUIREMENTS = LocaleRequirements(
            scalarPaths = setOf(
                "prefix",
                "errors.disabled",
                "errors.player-only",
                "errors.no-permission",
                "errors.game-mode",
                "errors.game-mode-changed",
                "errors.world-not-allowed",
                "errors.selection-missing",
                "errors.selection-too-large",
                "errors.plan-failed",
                "errors.empty-copy",
                "errors.hourly-limit",
                "errors.chunk-unloaded",
                "errors.too-far",
                "errors.nothing-to-change",
                "errors.busy",
                "errors.expired",
                "errors.inventory",
                "errors.protection",
                "errors.material",
                "errors.crown-material",
                "errors.crown-setting",
                "errors.tool",
                "errors.recovering",
                "errors.undo-missing",
                "errors.shop-unavailable",
                "errors.shop-not-supported",
                "errors.shop-material-unavailable",
                "errors.shop-limit",
                "errors.shop-insufficient-funds",
                "errors.shop-estimate-changed",
                "errors.shop-purchase-failed",
                "errors.shop-purchase-ambiguous",
                "selection.first",
                "selection.second",
                "selection.complete",
                "selection.cleared",
                "selection.world-reset",
                "wand.name",
                "wand.received",
                "wand.inventory-full",
                "wand.material-required",
                "crown-brush.name",
                "crown-brush.received",
                "crown-brush.inventory-full",
                "crown-brush.material-required",
                "crown.same-face",
                "crown.settings-updated",
                "crown.palette-updated",
                "crown.palette-row",
                "crown.labels.settings.shape",
                "crown.labels.settings.radius",
                "crown.labels.settings.density",
                "crown.labels.settings.noise",
                "crown.labels.shape.natural",
                "crown.labels.shape.round",
                "crown.labels.shape.wide",
                "crown.labels.shape.tall",
                "crown.labels.density.airy",
                "crown.labels.density.natural",
                "crown.labels.density.dense",
                "crown.labels.noise.smooth",
                "crown.labels.noise.natural",
                "crown.labels.noise.wild",
                "clipboard.saved",
                "book.draft-saving",
                "book.draft-created",
                "book.draft-recovery-starting",
                "book.draft-pending",
                "book.draft-recovering",
                "book.draft-recovered",
                "book.draft-recovery-failed",
                "book.status.start",
                "book.status.first-point",
                "book.status.second-point",
                "book.status.selection",
                "book.status.clipboard",
                "book.status.draft",
                "book.status.draft-recovery",
                "book.status.preview",
                "book.status.quote",
                "book.status.delivery",
                "book.status.checking",
                "book.status.changed",
                "book.status.active",
                "book.material-required",
                "book.inventory-full",
                "book.invalid-name",
                "book.limit",
                "book.failed",
                "book.invalid",
                "book.missing",
                "book.draft-required",
                "book.preview-required",
                "book.preview-opened",
                "book.preview.title",
                "book.preview.subtitle",
                "book.preview.actionbar",
                "book.preview.bossbar-draft",
                "book.preview.bossbar-active",
                "book.plan-ready.title",
                "book.plan-ready.subtitle",
                "book.preview-cancelled",
                "book.nothing-to-cancel",
                "book.state.draft",
                "book.state.active",
                "book.active-required",
                "book.creator-only",
                "book.unactivated",
                "book.duplicate",
                "book.stale",
                "book.source-changed",
                "book.contracts-disabled",
                "book.registry-starting",
                "book.registry-unavailable",
                "book.shop-unavailable",
                "book.player-materials.included",
                "book.price-limit",
                "book.quote",
                "book.quote-kind.activation",
                "book.quote-kind.copy",
                "book.quote-expired",
                "book.quote-cancelled",
                "book.economy-unavailable",
                "book.insufficient-funds",
                "book.payment-failed",
                "book.refunded",
                "book.manual-review",
                "book.delivery-space",
                "book.delivery-pending",
                "book.delivery-recovered",
                "book.activated",
                "book.activated-preview",
                "book.copied",
                "book.auction-price",
                "book.auction-unavailable",
                "book.auction-locked",
                "book.auction-use-safe-command",
                "book.auction-listed",
                "book.auction-listed-late",
                "book.auction-rejected",
                "book.auction-review",
                "book.auction-returned",
                "book.auction-received",
                "plan.ready",
                "plan.actions.ready",
                "plan.skipped",
                "plan.market-item",
                "plan.market-unavailable",
                "plan.market-more",
                "plan.cancelled",
                "clipboard.retained",
                "shop.purchased",
                "shop.world-untouched",
                "operation.started",
                "operation.progress",
                "operation.completed",
                "operation.paste-again",
                "operation.rolled-back",
                "construction.started",
                "construction.waiting-materials",
                "construction.waiting-output",
                "construction.completed",
                "construction.recovery-required",
                "construction.status",
                "items.none",
                "items.summary",
                "status.selection",
                "status.selection-first",
                "status.selection-second",
                "status.plan",
                "status.idle",
            ) + BuilderPlanKind.entries.map { kind ->
                "kinds.${kind.name.lowercase(Locale.ROOT)}"
            } + BuilderConstructionProjectState.entries.map { state ->
                "construction.states.${state.name.lowercase(Locale.ROOT)}"
            } + ShopPurchaseStatus.entries.map { status ->
                "shop.status.${status.name.lowercase(Locale.ROOT).replace('_', '-')}"
            } + setOf(
                "shop.status.ambiguous",
                "shop.status.delivery-mismatch",
            ),
            listPaths = setOf(
                "help",
                "book.guide",
                "wand.lore",
                "crown-brush.lore",
                "crown.help",
                "crown.status",
                "plan.market",
                "shop.purchase-detail",
            ),
        )

        fun load(): BuilderToolsConfig {
            val dataRoot = ARC.instance.dataPath
            val overridePath = ConfigManager.moduleYamlPath(dataRoot, "builder-tools-runtime.yml")
            val override = if (java.nio.file.Files.isRegularFile(overridePath)) {
                ConfigManager.ofModule(dataRoot, "builder-tools-runtime.yml")
            } else {
                null
            }
            return BuilderToolsConfig(
                config = ConfigManager.ofModule(dataRoot, "builder-tools.yml"),
                runtimeOverride = override,
            )
        }
    }
}

private class PrefixLocaleCatalog(
    private val config: Config,
    private val root: String,
) : LocaleCatalog {
    override fun scalar(path: String): String? = config.stringOrNull("$root.$path")
    override fun lines(path: String): List<String>? = config.stringListOrNull("$root.$path")
}

/**
 * Keeps portable defaults in the bundled module while allowing one runtime-only
 * file to own credentials and node policy without copying the full locale file.
 */
private class LayeredSqlModuleConfig(
    base: Config,
    private val override: Config?,
    private val prefix: String,
) : SqlModuleConfig(base, prefix) {
    override val enabled: Boolean get() = override?.booleanOrNull("$prefix.enabled") ?: super.enabled
    override val host: String get() = override?.stringOrNull("$prefix.host") ?: super.host
    override val port: Int get() = override?.intOrNull("$prefix.port") ?: super.port
    override val database: String get() = override?.stringOrNull("$prefix.database") ?: super.database
    override val username: String get() = override?.stringOrNull("$prefix.username") ?: super.username
    override val password: String get() = override?.stringOrNull("$prefix.password") ?: super.password
    override val sslMode: SqlSslMode get() = override?.enumOrNull<SqlSslMode>("$prefix.ssl-mode") ?: super.sslMode
    override val minimumIdle: Int get() = override?.intOrNull("$prefix.pool.minimum-idle") ?: super.minimumIdle
    override val maximumPoolSize: Int get() = override?.intOrNull("$prefix.pool.maximum-size") ?: super.maximumPoolSize
    override val connectionTimeoutMs: Long
        get() = override?.longOrNull("$prefix.pool.connection-timeout-ms") ?: super.connectionTimeoutMs
    override val socketTimeoutMs: Long
        get() = override?.longOrNull("$prefix.pool.socket-timeout-ms") ?: super.socketTimeoutMs
    override val validationTimeoutMs: Long
        get() = override?.longOrNull("$prefix.pool.validation-timeout-ms") ?: super.validationTimeoutMs
    override val maxLifetimeMs: Long
        get() = override?.longOrNull("$prefix.pool.max-lifetime-ms") ?: super.maxLifetimeMs
    override val failFast: Boolean get() = override?.booleanOrNull("$prefix.fail-fast") ?: super.failFast
}
