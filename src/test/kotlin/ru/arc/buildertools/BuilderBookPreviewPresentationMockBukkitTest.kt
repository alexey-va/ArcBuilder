package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Color
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemStack
import ru.arc.autobuild.BuildBookData
import ru.arc.autobuild.BuildBookPreviewAdjustment
import ru.arc.autobuild.BuildBookPreviewMove
import ru.arc.autobuild.ConstructionSite
import ru.arc.autobuild.ConstructionSiteSnapshot
import ru.arc.config.ConfigManager
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.loadPlugin
import ru.ruscrafting.builder.paper.ArcBuilderPlugin
import java.time.Duration
import java.util.Locale
import java.util.UUID

class BuilderBookPreviewPresentationMockBukkitTest : FunSpec({
    test("placement menu moves only the preview session and continues to a cooldown-aware confirmation") {
        ConfigManager.clear()
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<ArcBuilderPlugin>()
            BuilderToolsModule.shutdown()
            try {
                val moduleConfig = ConfigManager.ofModule(plugin.dataPath, "builder-tools.yml")
                val config = BuilderToolsConfig(moduleConfig).validated()
                moduleConfig.setInt(
                    "paper-menus.preview.layouts.book-preview-placement.elements.left.slot",
                    18,
                )
                val world = paper.addSimpleWorld("book-preview-menu")
                val player = paper.addPlayer("PreviewOwner").also {
                    it.gameMode = GameMode.CREATIVE
                    it.setLocale(Locale.forLanguageTag("ru-RU"))
                }
                val book = BuildBookData("house.schem", "Дом")
                val site = mockk<ConstructionSite>()
                val snapshot = mockk<ConstructionSiteSnapshot>()
                every { site.player } returns player
                every { site.bookData } returns book
                every { site.centerBlock } returns world.getBlockAt(10, 64, 20).location
                every { site.rotation } returns 180
                every { site.snapshot() } returns snapshot
                val plan = plan(player.uniqueId, world.uid)
                val requiredMaterial = item(Material.OAK_PLANKS, 32)
                val host = PreviewHost(
                    site,
                    BuilderBookPreviewConfirmation(
                        kind = BuilderBookPreviewConfirmationKind.CONSTRUCTION,
                        blockCount = plan.changes.size,
                        title = "Дом",
                        cooldownRemaining = Duration.ofHours(7),
                        requiredMaterials = listOf(requiredMaterial),
                    ),
                )
                val renderer = mockk<BuilderDisplayRenderer>(relaxed = true)

                BuilderBookPreviewPresentation(
                    plugin = plugin,
                    renderer = renderer,
                    messages = config.messages(),
                    host = host,
                    panelHeightOffset = 2.25,
                    panelFrontOffset = 0.4,
                    panelInteractionWidth = 3f,
                    panelInteractionHeight = 1.5f,
                    panelLineWidth = 180,
                    panelBackgroundColor = Color.fromARGB(0xB2, 0x1C, 0x23, 0x28),
                    panelGlowColor = Color.fromRGB(0xFF, 0xB1, 0x42),
                    viewRange = 64.0,
                ).use { presentation ->
                    presentation.openPlacementForTest(player, site)
                    val placement = player.openInventory.topInventory

                    placement.size shouldBe 45
                    placement.getItem(18)?.type shouldBe Material.ARROW
                    placement.getItem(19)?.type shouldBe Material.GRAY_STAINED_GLASS_PANE
                    plain(placement.getItem(18)!!.itemMeta.displayName()!!) shouldContain "Влево"
                    click(paper, player, 18).isCancelled.shouldBeTrue()
                    host.adjustments shouldBe listOf(BuildBookPreviewAdjustment.Move(BuildBookPreviewMove.LEFT))

                    placement.getItem(20)?.type shouldBe Material.COMPASS
                    click(paper, player, 20).isCancelled.shouldBeTrue()
                    host.adjustments.last() shouldBe BuildBookPreviewAdjustment.Rotate(90)

                    click(paper, player, 37).isCancelled.shouldBeTrue()
                    host.adjustments.last() shouldBe BuildBookPreviewAdjustment.ToggleMirror

                    click(paper, player, 25).isCancelled.shouldBeTrue()
                    val confirmation = player.openInventory.topInventory
                    confirmation.size shouldBe 45
                    confirmation.getItem(21)?.type shouldBe Material.CHEST
                    plainLore(confirmation.getItem(21)!!) shouldContain "32× Oak Planks"
                    confirmation.getItem(25)?.type shouldBe Material.BARRIER
                    plain(confirmation.getItem(25)!!.itemMeta.displayName()!!) shouldContain "недоступно"
                    host.confirmCalls shouldBe 0

                    click(paper, player, 29).isCancelled.shouldBeTrue()
                    host.confirmation = host.confirmation.copy(
                        cooldownRemaining = Duration.ZERO,
                        requiredMaterials = emptyList(),
                    )
                    click(paper, player, 25).isCancelled.shouldBeTrue()
                    val noMaterials = player.openInventory.topInventory
                    noMaterials.getItem(21)?.type shouldBe Material.GRAY_STAINED_GLASS_PANE
                    plainLore(noMaterials.getItem(19)!!) shouldContain "Материалы не требуются"
                    noMaterials.getItem(25)?.type shouldBe Material.LIME_CONCRETE
                }
            } finally {
                ConfigManager.clear()
            }
        }
    }

    test("draft activation stays in the preview gui and presents the full price before minting") {
        ConfigManager.clear()
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<ArcBuilderPlugin>()
            BuilderToolsModule.shutdown()
            try {
                val config = BuilderToolsConfig(ConfigManager.ofModule(plugin.dataPath, "builder-tools.yml")).validated()
                val world = paper.addSimpleWorld("draft-preview-menu")
                val player = paper.addPlayer("DraftOwner").also {
                    it.gameMode = GameMode.CREATIVE
                    it.setLocale(Locale.forLanguageTag("ru-RU"))
                }
                val book = BuildBookData(
                    buildingId = "house.schem",
                    title = "Дом",
                    playerCreated = true,
                    creatorId = player.uniqueId,
                    creatorName = player.name,
                    blueprintId = UUID.randomUUID(),
                    contentSha256 = "a".repeat(64),
                    schematicSha256 = "b".repeat(64),
                    blockCount = 401,
                ).validated()
                val site = mockk<ConstructionSite>()
                val snapshot = mockk<ConstructionSiteSnapshot>()
                every { site.player } returns player
                every { site.bookData } returns book
                every { site.centerBlock } returns world.getBlockAt(10, 64, 20).location
                every { site.rotation } returns 0
                every { site.snapshot() } returns snapshot
                val host = PreviewHost(
                    site,
                    BuilderBookPreviewConfirmation(
                        kind = BuilderBookPreviewConfirmationKind.DRAFT_ACTIVATION,
                        blockCount = 401,
                        title = "Дом",
                        cooldownRemaining = Duration.ZERO,
                        requiredMaterials = emptyList(),
                        materialCostMinor = 12_345,
                        constructionFeeMinor = 2_500,
                        issuePriceMinor = 14_845,
                    ),
                )

                BuilderBookPreviewPresentation(
                    plugin = plugin,
                    renderer = mockk(relaxed = true),
                    messages = config.messages(),
                    host = host,
                    panelHeightOffset = 2.25,
                    panelFrontOffset = 0.4,
                    panelInteractionWidth = 3f,
                    panelInteractionHeight = 1.5f,
                    panelLineWidth = 180,
                    panelBackgroundColor = Color.fromARGB(0xB2, 0x1C, 0x23, 0x28),
                    panelGlowColor = Color.fromRGB(0xFF, 0xB1, 0x42),
                    viewRange = 64.0,
                ).use { presentation ->
                    presentation.openPlacementForTest(player, site)
                    click(paper, player, 25).isCancelled.shouldBeTrue()

                    val confirmation = player.openInventory.topInventory
                    plain(confirmation.getItem(19)!!.itemMeta.displayName()!!) shouldContain "Активация"
                    confirmation.getItem(21)?.type shouldBe Material.SUNFLOWER
                    plain(confirmation.getItem(21)!!.itemMeta.displayName()!!) shouldContain "148.45"
                    plain(confirmation.getItem(25)!!.itemMeta.displayName()!!) shouldContain "Активировать"

                    click(paper, player, 25).isCancelled.shouldBeTrue()
                    host.confirmCalls shouldBe 1
                }
            } finally {
                ConfigManager.clear()
            }
        }
    }
})

private fun item(material: Material, amount: Int): BuilderItemAmount = BuilderItemAmount(
    BuilderItemCodec.encodePrototype(ItemStack(material)),
    material.key.toString(),
    amount,
)

private class PreviewHost(
    private val site: ConstructionSite,
    var confirmation: BuilderBookPreviewConfirmation,
) : BuilderBookPreviewPresentationHost {
    val adjustments = mutableListOf<BuildBookPreviewAdjustment>()
    var confirmCalls = 0

    override fun adjust(player: org.bukkit.entity.Player, adjustment: BuildBookPreviewAdjustment): ConstructionSite {
        adjustments += adjustment
        return site
    }

    override fun prepare(
        player: org.bukkit.entity.Player,
        site: ConstructionSite,
        complete: (BuilderBookPreviewConfirmation?) -> Unit,
    ) = complete(confirmation)

    override fun currentConfirmation(player: org.bukkit.entity.Player): BuilderBookPreviewConfirmation = confirmation

    override fun confirm(player: org.bukkit.entity.Player): Boolean {
        confirmCalls += 1
        return true
    }

    override fun restore(player: org.bukkit.entity.Player, snapshot: ConstructionSiteSnapshot): ConstructionSite = site

    override fun cancel(player: org.bukkit.entity.Player) = Unit
}

private fun plan(playerId: UUID, worldId: UUID): BuilderPlan {
    val now = 1_800_000_000_000L
    val change = BuilderBlockChange(
        BuilderBlockPos(worldId, 10, 64, 20),
        "minecraft:air",
        "minecraft:stone",
    )
    val item = BuilderItemAmount(
        BuilderItemCodec.encodePrototype(ItemStack(Material.STONE)),
        Material.STONE.key.toString(),
        12,
    )
    return BuilderPlan(
        id = UUID.randomUUID(),
        playerId = playerId,
        kind = BuilderPlanKind.BUILD_BOOK,
        changes = listOf(change),
        costs = listOf(item),
        rewards = emptyList(),
        createdAtMillis = now,
        expiresAtMillis = now + 60_000,
    ).validated()
}

private fun click(
    paper: MockBukkitTestRuntime,
    player: org.bukkit.entity.Player,
    rawSlot: Int,
): InventoryClickEvent = paper.callEvent(
    InventoryClickEvent(
        player.openInventory,
        InventoryType.SlotType.CONTAINER,
        rawSlot,
        ClickType.LEFT,
        InventoryAction.PICKUP_ALL,
    ),
)

private fun plain(component: net.kyori.adventure.text.Component): String =
    PlainTextComponentSerializer.plainText().serialize(component)

private fun plainLore(item: ItemStack): String = item.itemMeta.lore().orEmpty().joinToString("\n", transform = ::plain)
