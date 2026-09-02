package ru.arc.buildertools

import org.bukkit.Material
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
import java.nio.file.Path
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
    val schematicsRoot: String
        get() = runtimeOverride?.stringOrNull("storage.schematics-root")
            ?: config.string("storage.schematics-root", "schematics")
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
    val bookApplicationCooldown: Duration
        get() = config.duration("construction.book-application-cooldown", Duration.ofHours(12))
    val constructionMaxContainerProbesPerTick: Int
        get() = config.integer("construction.max-container-probes-per-tick", 512)
    val constructionMaxCachedContainersPerProject: Int
        get() = config.integer(
            "construction.max-cached-containers-per-project",
            DEFAULT_CONSTRUCTION_MAX_CACHED_CONTAINERS_PER_PROJECT,
        )
    val constructionMaxResolvedContainersPerCall: Int
        get() = config.integer(
            "construction.max-resolved-containers-per-call",
            DEFAULT_CONSTRUCTION_MAX_RESOLVED_CONTAINERS_PER_CALL,
        )
    val constructionEffectsEnabled: Boolean get() = config.bool("construction.effects.enabled", true)
    val constructionEffectIntervalBlocks: Int get() = config.integer("construction.effects.interval-blocks", 4)
    val constructionSoundsEnabled: Boolean get() = config.bool("construction.effects.sounds.enabled", true)
    val constructionSoundVolume: Float get() = config.double("construction.effects.sounds.volume", 0.55).toFloat()
    val constructionSoundPitch: Float get() = config.double("construction.effects.sounds.pitch", 1.0).toFloat()
    val constructionParticlesEnabled: Boolean get() = config.bool("construction.effects.particles.enabled", true)
    val constructionParticleCount: Int get() = config.integer("construction.effects.particles.count", 3)
    val constructionParticleSpread: Double get() = config.double("construction.effects.particles.spread", 0.2)
    val constructionSiteEnabled: Boolean get() = config.bool("construction.site.enabled", true)
    val constructionSiteOutlineEnabled: Boolean get() = config.bool("construction.site.outline.enabled", true)
    val constructionSiteOutlineMaterial: Material
        get() = Material.matchMaterial(config.string("construction.site.outline.material", "ORANGE_STAINED_GLASS"))
            ?: Material.AIR
    val constructionSiteOutlineThickness: Float
        get() = config.double("construction.site.outline.thickness", 0.035).toFloat()
    val constructionSiteGlowColor: String get() = config.string("construction.site.outline.glow-color", "#FFB142")
    val constructionSitePanelEnabled: Boolean get() = config.bool("construction.site.panel.enabled", true)
    val constructionSitePanelFace: BuilderConstructionSitePanelFace
        get() = BuilderConstructionSitePanelFace.valueOf(
            config.string("construction.site.panel.face", "MAX_Z").uppercase(Locale.ROOT),
        )
    val constructionSitePanelHeightOffset: Double
        get() = config.double("construction.site.panel.height-offset", 2.25)
    val constructionSitePanelFrontOffset: Double
        get() = config.double("construction.site.panel.front-offset", 0.4)
    val constructionSitePanelInteractionWidth: Float
        get() = config.double("construction.site.panel.interaction-width", 3.0).toFloat()
    val constructionSitePanelInteractionHeight: Float
        get() = config.double("construction.site.panel.interaction-height", 1.5).toFloat()
    val constructionSitePanelLineWidth: Int get() = config.integer("construction.site.panel.line-width", 180)
    val constructionSiteMaxMaterialLines: Int
        get() = config.integer("construction.site.panel.max-material-lines", 8)
    val constructionSitePanelBackgroundColor: String
        get() = config.string("construction.site.panel.background-color", "#B21C2328")
    val constructionSiteMenuRows: Int get() = config.integer("construction.site.menu.rows", 3)
    val constructionSiteMenuRefreshPeriodTicks: Long
        get() = config.long("construction.site.menu.refresh-period-ticks", 10L)
    val constructionSiteMenuBackgroundItem: String
        get() = config.string("construction.site.menu.background-item", "arc:background")
    val constructionSiteMenuBackgroundFallback: Material
        get() = Material.matchMaterial(
            config.string("construction.site.menu.background-fallback", "GRAY_STAINED_GLASS_PANE"),
        ) ?: Material.AIR
    val constructionSiteMenuOverviewSlot: Int get() = config.integer("construction.site.menu.slots.overview", 10)
    val constructionSiteMenuProgressSlot: Int get() = config.integer("construction.site.menu.slots.progress", 12)
    val constructionSiteMenuResourcesSlot: Int get() = config.integer("construction.site.menu.slots.resources", 14)
    val constructionSiteMenuControlSlot: Int get() = config.integer("construction.site.menu.slots.control", 16)
    val constructionSiteMenuOverviewMaterial: Material get() = configuredMenuMaterial("overview", "BOOK")
    val constructionSiteMenuProgressMaterial: Material get() = configuredMenuMaterial("progress", "CLOCK")
    val constructionSiteMenuResourcesMaterial: Material get() = configuredMenuMaterial("resources", "CHEST")
    val constructionSiteMenuPauseMaterial: Material get() = configuredMenuMaterial("pause", "REDSTONE_TORCH")
    val constructionSiteMenuResumeMaterial: Material get() = configuredMenuMaterial("resume", "LIME_DYE")
    val constructionSiteMenuUnavailableMaterial: Material get() = configuredMenuMaterial("unavailable", "GRAY_DYE")
    val constructionSiteViewRange: Double get() = config.double("construction.site.view-range", 64.0)
    val healthRefreshPeriodTicks: Long get() = config.long("runtime.health-refresh-period-ticks", 20L)
    val playerRecoveryRetryPeriodTicks: Long get() = config.long("runtime.player-recovery-retry-period-ticks", 100L)
    val progressEveryBatches: Int get() = config.integer("runtime.progress-every-batches", 10)
    val previewPeriodTicks: Long get() = config.long("preview.period-ticks", 10L)
    val previewRadius: Double get() = config.double("preview.radius", 32.0)
    val previewSpacing: Double get() = config.double("preview.outline-spacing", 0.75)
    val previewMaxSelectionParticles: Int get() = config.integer("preview.max-selection-particles", 512)
    val previewMaxPlanDisplays: Int get() = config.integer("preview.max-plan-displays", 512)
    val previewBlockDisplayScale: Float get() = config.double("preview.block-display-scale", 1.0).toFloat()
    val previewPlanDisplayRange: Double get() = config.double("preview.plan-display-range", 64.0)
    val previewGuidancePeriodTicks: Long get() = config.long("preview.guidance-period-ticks", 20L)
    val bookPreviewTtl: Duration get() = config.duration("preview.book-ttl", Duration.ofMinutes(3))
    val bookPreviewMaxOffset: Int get() = config.integer("preview.book-max-offset", 16)
    val previewPlanTitleFadeInTicks: Int get() = config.integer("preview.plan-title.fade-in-ticks", 5)
    val previewPlanTitleStayTicks: Int get() = config.integer("preview.plan-title.stay-ticks", 45)
    val previewPlanTitleFadeOutTicks: Int get() = config.integer("preview.plan-title.fade-out-ticks", 10)
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
    val bookAuctionRecoveryRetry: Duration
        get() = config.duration("book-contracts.auction-recovery-retry", Duration.ofSeconds(30))
    val bookPlayerMaterialsSummaryLimit: Int
        get() = config.integer("book-contracts.player-materials-summary-limit", 4)
    val planTtl: Duration get() = config.duration("timers.plan-ttl", Duration.ofSeconds(30))
    val clipboardTtl: Duration get() = config.duration("timers.clipboard-ttl", Duration.ofMinutes(15))
    val undoTtl: Duration get() = config.duration("timers.undo-ttl", Duration.ofMinutes(30))
    val journalRetention: Duration get() = config.duration("timers.journal-retention", Duration.ofHours(2))
    val requireLands: Boolean get() = config.bool("safety.require-lands", false)
    val requireCoreProtect: Boolean get() = config.bool("safety.require-coreprotect", true)
    val replaceableMaterials: Set<String>
        get() = config.stringList("safety.replaceable-materials").map { it.uppercase(Locale.ROOT) }.toSet()
    val defaultLocaleTag: String get() = config.string("default-locale", "ru")

    fun allowsWorld(worldName: String): Boolean =
        "*" in allowedWorlds || worldName.lowercase(Locale.ROOT) in allowedWorlds

    private fun configuredMenuMaterial(path: String, fallback: String): Material =
        Material.matchMaterial(config.string("construction.site.menu.materials.$path", fallback)) ?: Material.AIR

    fun validated(): BuilderToolsConfig = apply {
        if (enabled) {
            require(allowedWorlds.isNotEmpty() && allowedWorlds.all { it == "*" || WORLD_NAME.matches(it) }) {
                "Builder-tools allowed-worlds must contain safe world names or a wildcard"
            }
            require("*" !in allowedWorlds || allowedWorlds.size == 1) {
                "Builder-tools world wildcard must be the only allowed-worlds entry"
            }
        }
        require(schematicsRoot.isNotBlank() && schematicsRoot.length <= 512 && schematicsRoot.none(Char::isISOControl)) {
            "Builder-tools schematic root is invalid"
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
        require(bookApplicationCooldown in Duration.ofHours(12)..Duration.ofDays(7)) {
            "Builder build-book application cooldown must be between 12 hours and 7 days"
        }
        require(constructionMaxContainerProbesPerTick in 1..4_096) {
            "Builder construction container probe budget is invalid"
        }
        require(constructionMaxCachedContainersPerProject in 16..1_024) {
            "Builder construction container cache limit is invalid"
        }
        require(constructionMaxResolvedContainersPerCall in 1..128) {
            "Builder construction container resolution budget is invalid"
        }
        require(constructionMaxResolvedContainersPerCall <= constructionMaxCachedContainersPerProject) {
            "Builder construction container resolution budget cannot exceed its cache limit"
        }
        require(constructionEffectIntervalBlocks in 1..128) {
            "Builder construction effect interval is invalid"
        }
        require(constructionSoundVolume.isFinite() && constructionSoundVolume in 0.0f..2.0f) {
            "Builder construction sound volume is invalid"
        }
        require(constructionSoundPitch.isFinite() && constructionSoundPitch in 0.5f..2.0f) {
            "Builder construction sound pitch is invalid"
        }
        require(constructionParticleCount in 0..16) {
            "Builder construction particle count is invalid"
        }
        require(constructionParticleSpread.isFinite() && constructionParticleSpread in 0.0..1.0) {
            "Builder construction particle spread is invalid"
        }
        require(constructionSiteOutlineMaterial.isBlock && !constructionSiteOutlineMaterial.isAir) {
            "Builder construction site outline material is invalid"
        }
        require(constructionSiteOutlineThickness.isFinite() && constructionSiteOutlineThickness in 0.02f..0.25f) {
            "Builder construction site outline thickness is invalid"
        }
        require(RGB_COLOR.matches(constructionSiteGlowColor)) {
            "Builder construction site glow color is invalid"
        }
        require(constructionSitePanelHeightOffset.isFinite() && constructionSitePanelHeightOffset in 0.5..8.0) {
            "Builder construction site panel height offset is invalid"
        }
        require(constructionSitePanelFrontOffset.isFinite() && constructionSitePanelFrontOffset in 0.1..4.0) {
            "Builder construction site panel front offset is invalid"
        }
        require(constructionSitePanelInteractionWidth.isFinite() && constructionSitePanelInteractionWidth in 0.5f..8.0f) {
            "Builder construction site panel interaction width is invalid"
        }
        require(constructionSitePanelInteractionHeight.isFinite() && constructionSitePanelInteractionHeight in 0.5f..4.0f) {
            "Builder construction site panel interaction height is invalid"
        }
        require(constructionSitePanelLineWidth in 80..400) {
            "Builder construction site panel line width is invalid"
        }
        require(constructionSiteMaxMaterialLines in 1..32) {
            "Builder construction site material line limit is invalid"
        }
        require(ARGB_COLOR.matches(constructionSitePanelBackgroundColor)) {
            "Builder construction site panel background color is invalid"
        }
        require(constructionSiteMenuRows in 1..6) { "Builder construction site menu row count is invalid" }
        require(constructionSiteMenuRefreshPeriodTicks in 5L..100L) {
            "Builder construction site menu refresh period is invalid"
        }
        require(ITEMS_ADDER_KEY.matches(constructionSiteMenuBackgroundItem)) {
            "Builder construction site menu background item is invalid"
        }
        require(constructionSiteMenuBackgroundFallback.isItem && !constructionSiteMenuBackgroundFallback.isAir) {
            "Builder construction site menu background fallback is invalid"
        }
        val menuSlots = listOf(
            constructionSiteMenuOverviewSlot,
            constructionSiteMenuProgressSlot,
            constructionSiteMenuResourcesSlot,
            constructionSiteMenuControlSlot,
        )
        require(menuSlots.distinct().size == menuSlots.size && menuSlots.all { it in 0 until constructionSiteMenuRows * 9 }) {
            "Builder construction site menu slots are invalid"
        }
        require(
            listOf(
                constructionSiteMenuOverviewMaterial,
                constructionSiteMenuProgressMaterial,
                constructionSiteMenuResourcesMaterial,
                constructionSiteMenuPauseMaterial,
                constructionSiteMenuResumeMaterial,
                constructionSiteMenuUnavailableMaterial,
            ).all { it.isItem && !it.isAir },
        ) { "Builder construction site menu material is invalid" }
        require(constructionSiteViewRange.isFinite() && constructionSiteViewRange in 16.0..128.0) {
            "Builder construction site view range is invalid"
        }
        require(healthRefreshPeriodTicks in 10L..1_200L) { "Builder-tools health refresh period is invalid" }
        require(playerRecoveryRetryPeriodTicks in 20L..1_200L) { "Builder-tools recovery retry period is invalid" }
        require(progressEveryBatches in 1..100) { "Builder-tools progress cadence is invalid" }
        require(previewPeriodTicks in 5L..40L) { "Builder-tools preview period is invalid" }
        require(previewRadius.isFinite() && previewRadius in 8.0..64.0) { "Builder-tools preview radius is invalid" }
        require(previewSpacing.isFinite() && previewSpacing in 0.25..2.0) { "Builder-tools preview spacing is invalid" }
        require(previewMaxSelectionParticles in 48..1_024) { "Builder-tools selection preview limit is invalid" }
        require(previewMaxPlanDisplays in 32..512) { "Builder-tools plan preview limit is invalid" }
        require(previewBlockDisplayScale.isFinite() && previewBlockDisplayScale in 0.5f..1.0f) {
            "Builder-tools block display scale is invalid"
        }
        require(previewPlanDisplayRange.isFinite() && previewPlanDisplayRange in 8.0..128.0) {
            "Builder-tools plan display range is invalid"
        }
        require(previewGuidancePeriodTicks in 5L..100L) { "Builder-tools preview guidance period is invalid" }
        require(bookPreviewTtl in Duration.ofSeconds(10)..Duration.ofMinutes(3)) {
            "Builder build-book preview TTL must be between 10 seconds and 3 minutes"
        }
        require(bookPreviewMaxOffset in 1..64) { "Builder build-book preview offset is invalid" }
        require(previewPlanTitleFadeInTicks in 0..100) { "Builder-tools plan title fade-in is invalid" }
        require(previewPlanTitleStayTicks in 1..400) { "Builder-tools plan title stay is invalid" }
        require(previewPlanTitleFadeOutTicks in 0..100) { "Builder-tools plan title fade-out is invalid" }
        require(shopMaxAutoBuyItems in 1..BuilderPlan.ABSOLUTE_MAX_ITEMS.toInt()) {
            "Builder-tools shop item limit is invalid"
        }
        require(shopMaxQuotedMaterials in 1..256) { "Builder-tools quoted material limit is invalid" }
        require(shopMaxAutoBuyPriceMinor in 100L..100_000_000_000L) {
            "Builder-tools shop price limit is invalid"
        }
        if (enabled && bookContractsEnabled) {
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
        require(bookAuctionRecoveryRetry in Duration.ofSeconds(5)..Duration.ofMinutes(10)) {
            "Builder-book auction recovery retry is invalid"
        }
        require(bookPlayerMaterialsSummaryLimit in 1..16) {
            "Builder-book player-material summary limit is invalid"
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
        private val RGB_COLOR = Regex("#[0-9A-Fa-f]{6}")
        private val ARGB_COLOR = Regex("#[0-9A-Fa-f]{8}")
        private val ITEMS_ADDER_KEY = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")
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
                "reload.success",
                "reload.busy",
                "reload.failed",
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
                "book.preview-panel",
                "book.preview-menu.placement.title",
                "book.preview-menu.confirmation.title",
                "book.plan-ready.title",
                "book.plan-ready.subtitle",
                "book.preview-cancelled",
                "book.preview-expired",
                "book.nothing-to-cancel",
                "book.cooldown",
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
                "construction.site.unknown-name",
                "construction.site.panel",
                "construction.site.details",
                "construction.site.material-line",
                "construction.site.material-more",
                "construction.site.missing",
                "construction.site.missing-none",
                "construction.site.menu.title",
                "construction.site.menu.overview.name",
                "construction.site.menu.progress.name",
                "construction.site.menu.resources.name",
                "construction.site.menu.resources.line",
                "construction.site.menu.resources.more",
                "construction.site.menu.resources.none",
                "construction.site.menu.resources.missing",
                "construction.site.menu.control.pause.name",
                "construction.site.menu.control.resume.name",
                "construction.site.menu.control.unavailable.name",
                "construction.site.menu.control.readonly.name",
                "items.none",
                "items.summary",
                "status.selection",
                "status.selection-first",
                "status.selection-second",
                "status.plan",
                "status.idle",
                "book.preview-menu.placement.left.name",
                "book.preview-menu.placement.up.name",
                "book.preview-menu.placement.rotate.name",
                "book.preview-menu.placement.mirror.name",
                "book.preview-menu.placement.reset.name",
                "book.preview-menu.placement.down.name",
                "book.preview-menu.placement.right.name",
                "book.preview-menu.placement.cancel.name",
                "book.preview-menu.placement.continue.name",
                "book.preview-menu.confirmation.overview.name",
                "book.preview-menu.confirmation.materials.name",
                "book.preview-menu.confirmation.material-line",
                "book.preview-menu.confirmation.material-more",
                "book.preview-menu.confirmation.materials-none",
                "book.preview-menu.confirmation.back.name",
                "book.preview-menu.confirmation.start.name",
                "book.preview-menu.confirmation.blocked.name",
                "book.preview-menu.confirmation.cancel.name",
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
                "construction.site.menu.overview.lore",
                "construction.site.menu.progress.lore",
                "construction.site.menu.resources.lore",
                "construction.site.menu.control.pause.lore",
                "construction.site.menu.control.resume.lore",
                "construction.site.menu.control.unavailable.lore",
                "construction.site.menu.control.readonly.lore",
                "book.preview-menu.placement.left.lore",
                "book.preview-menu.placement.up.lore",
                "book.preview-menu.placement.rotate.lore",
                "book.preview-menu.placement.mirror.lore",
                "book.preview-menu.placement.reset.lore",
                "book.preview-menu.placement.down.lore",
                "book.preview-menu.placement.right.lore",
                "book.preview-menu.placement.cancel.lore",
                "book.preview-menu.placement.continue.lore",
                "book.preview-menu.confirmation.overview.lore",
                "book.preview-menu.confirmation.materials.lore",
                "book.preview-menu.confirmation.back.lore",
                "book.preview-menu.confirmation.start.lore",
                "book.preview-menu.confirmation.blocked.lore",
                "book.preview-menu.confirmation.cancel.lore",
            ),
        )

        fun load(): BuilderToolsConfig {
            val dataRoot = ARC.instance.dataPath
            val base = ConfigManager.ofModule(dataRoot, "builder-tools.yml").also {
                it.mergeMissingFromBundled(ConfigManager.bundledModuleResource("builder-tools.yml"))
            }
            val overridePath = ConfigManager.moduleYamlPath(dataRoot, "builder-tools-runtime.yml")
            val override = if (java.nio.file.Files.isRegularFile(overridePath)) {
                ConfigManager.ofModule(dataRoot, "builder-tools-runtime.yml")
            } else {
                null
            }
            return BuilderToolsConfig(
                config = base,
                runtimeOverride = override,
            )
        }

        internal fun mergeBundledDefaults(dataRoot: Path): Boolean =
            Config(dataRoot, ConfigManager.moduleYamlRelative(dataRoot, "builder-tools.yml"))
                .mergeMissingFromBundled(ConfigManager.bundledModuleResource("builder-tools.yml"))

        internal fun loadFresh(dataRoot: Path): BuilderToolsConfig {
            val base = Config(dataRoot, ConfigManager.moduleYamlRelative(dataRoot, "builder-tools.yml"))
            val overridePath = ConfigManager.moduleYamlPath(dataRoot, "builder-tools-runtime.yml")
            val override = if (java.nio.file.Files.isRegularFile(overridePath)) {
                Config(dataRoot, ConfigManager.moduleYamlRelative(dataRoot, "builder-tools-runtime.yml"))
            } else {
                null
            }
            return BuilderToolsConfig(base, override)
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
