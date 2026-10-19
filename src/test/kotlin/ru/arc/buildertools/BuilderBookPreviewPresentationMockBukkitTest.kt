package ru.arc.buildertools

import com.sk89q.worldedit.math.BlockVector3
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
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemStack
import ru.arc.autobuild.BuildBookData
import ru.arc.autobuild.BuildBookPreviewAdjustment
import ru.arc.autobuild.BuildBookPreviewMove
import ru.arc.autobuild.ConstructionSite
import ru.arc.autobuild.ConstructionSiteSnapshot
import ru.arc.autobuild.SystemBuildBookDefinition
import ru.arc.autobuild.schematicTestSnapshot
import ru.arc.config.ConfigManager
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.loadPlugin
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.scene.PaperSpatialSpace
import ru.arc.paper.api.ArcSchematicBlock
import ru.arc.paper.api.ArcSchematicPoint
import ru.arc.paper.api.ArcSchematicSnapshot
import ru.ruscrafting.builder.paper.ArcBuilderPlugin
import java.time.Duration
import java.util.function.Consumer
import java.util.Locale
import java.util.UUID

class BuilderBookPreviewPresentationMockBukkitTest : FunSpec({
    test("plaque occupancy coalesces bursts and preserves one worker through cancellation and reopening") {
        ConfigManager.clear()
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<ArcBuilderPlugin>()
            installArcVisualPacketBudgetFixture(paper.createSimplePlugin("ArcCoreVisualFixture"))
            BuilderToolsModule.shutdown()
            try {
                val config = BuilderToolsConfig(ConfigManager.ofModule(plugin.dataPath, "builder-tools.yml")).validated()
                val world = paper.addSimpleWorld("async-plaque")
                val player = paper.addPlayer("AsyncPlaqueOwner")
                player.teleport(world.getBlockAt(10, 64, 20).location)
                val site = previewSite(player, player.location, BuildBookData("house.schem", "Дом"))
                val mainThread = Thread.currentThread()
                var reads = 0
                val scanned = mutableListOf<Int>()
                fun snapshot(x: Int, fail: Boolean = false) = mockk<ArcSchematicSnapshot> {
                    every { blocks() } answers {
                        check(Thread.currentThread() !== mainThread)
                        reads++
                        scanned += x
                        if (fail) error("Obsolete schematic calculation failed")
                        listOf(ArcSchematicBlock(ArcSchematicPoint(x, 2, 0), "minecraft:birch_log", false, false))
                    }
                }
                var view = snapshot(-2)
                every { site.view } answers { view }
                val tasks = QueuedBuilderPlanningTasks()
                val packet = BuilderPlaqueDisplays()
                val host = PreviewHost(site, BuilderBookPreviewConfirmation(
                    BuilderBookPreviewConfirmationKind.CONSTRUCTION, 1, "Дом", Duration.ZERO, emptyList(),
                ))
                previewPresentation(plugin, config, host, packet.owner, unblockedPlaqueSpace(), tasks).use { presentation ->
                    presentation.retainConfirmation(site)
                    presentation.refresh(site)
                    packet.texts.size shouldBe 0
                    reads shouldBe 0
                    tasks.runComputations()
                    tasks.tick()
                    reads shouldBe 1
                    packet.texts.map { it.location.x }.distinct() shouldBe listOf(7.6)
                    presentation.refresh(site)
                    tasks.runComputations()
                    reads shouldBe 1

                    view = snapshot(-5)
                    presentation.refresh(site)
                    repeat(20) { index ->
                        view = snapshot(-6 - index)
                        presentation.refresh(site)
                    }
                    view = snapshot(-3)
                    presentation.refresh(site)
                    // The old, complete scene remains until the latest model is ready.
                    packet.texts.map { it.location.x }.distinct() shouldBe listOf(7.6)
                    tasks.runComputations()
                    scanned shouldBe listOf(-2, -5)
                    tasks.tick()
                    // Completing an obsolete pose starts only the newest queued source.
                    packet.texts.map { it.location.x }.distinct() shouldBe listOf(7.6)
                    tasks.runComputations()
                    tasks.tick()
                    scanned shouldBe listOf(-2, -5, -3)
                    packet.texts.size shouldBe 2
                    packet.texts.map { it.location.x }.distinct() shouldBe listOf(6.6)

                    view = snapshot(-8, fail = true)
                    presentation.refresh(site)
                    presentation.clearPlayer(player.uniqueId)
                    val reopened = previewSite(player, player.location, BuildBookData("house.schem", "Дом"))
                    var reopenedView = snapshot(-4)
                    every { reopened.view } answers { reopenedView }
                    presentation.retainConfirmation(reopened)
                    reopenedView = snapshot(-1)
                    presentation.refresh(reopened)
                    tasks.runComputations()
                    scanned shouldBe listOf(-2, -5, -3, -8)
                    tasks.tick()
                    // Closing cannot free the old worker's slot or let its failure remove the new preview.
                    packet.texts.size shouldBe 2
                    packet.texts.forEach { io.mockk.verify(exactly = 1) { it.display.remove() } }
                    tasks.runComputations()
                    tasks.tick()
                    scanned shouldBe listOf(-2, -5, -3, -8, -1)
                    packet.texts.size shouldBe 4
                    packet.texts.takeLast(2).map { it.location.x }.distinct() shouldBe listOf(8.6)

                    reopenedView = snapshot(-9)
                    presentation.refresh(reopened)
                    reopenedView = snapshot(-10)
                    presentation.refresh(reopened)
                    presentation.close()
                    tasks.runComputations()
                    tasks.tick()
                    tasks.runComputations()
                    scanned shouldBe listOf(-2, -5, -3, -8, -1, -9)
                    packet.texts.size shouldBe 4
                    packet.texts.forEach { io.mockk.verify(exactly = 1) { it.display.remove() } }
                }
            } finally { ConfigManager.clear() }
        }
    }

    test("fixed plan retains a clickable world hologram until confirmation cleanup") {
        ConfigManager.clear()
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<ArcBuilderPlugin>()
            installArcVisualPacketBudgetFixture(paper.createSimplePlugin("ArcCoreVisualFixture"))
            BuilderToolsModule.shutdown()
            try {
                val config = BuilderToolsConfig(ConfigManager.ofModule(plugin.dataPath, "builder-tools.yml")).validated()
                val world = paper.addSimpleWorld("fixed-plan-plaque")
                val player = paper.addPlayer("FixedPlanOwner").also { it.setLocale(Locale.forLanguageTag("ru-RU")) }
                player.teleport(world.getBlockAt(10, 64, 20).location)
                val site = previewSite(player, player.location, BuildBookData("house.schem", "Дом"))
                every { site.view } returns schematicTestSnapshot(listOf(
                    ArcSchematicBlock(ArcSchematicPoint(-2, 2, 0), "minecraft:birch_log", false, false),
                    ArcSchematicBlock(ArcSchematicPoint(3, 2, 5), "minecraft:birch_log", false, false),
                    ArcSchematicBlock(ArcSchematicPoint(-6, 0, -3), "minecraft:dirt_path", false, false),
                    ArcSchematicBlock(ArcSchematicPoint(-5, 8, 0), "minecraft:birch_stairs", false, false),
                ))
                val panelWorld = mockk<org.bukkit.World>(relaxed = true)
                every { panelWorld.uid } returns world.uid
                every { panelWorld.minHeight } returns -64
                every { panelWorld.maxHeight } returns 320
                every { panelWorld.isChunkLoaded(any<Int>(), any<Int>()) } returns true
                val packet = BuilderPlaqueDisplays()
                val panels = mutableListOf<org.bukkit.entity.Interaction>()
                every { panelWorld.spawn(any<org.bukkit.Location>(), org.bukkit.entity.Interaction::class.java, any<java.util.function.Consumer<org.bukkit.entity.Interaction>>()) } answers {
                    var targetLocation = firstArg<org.bukkit.Location>().clone()
                    var width = 0f
                    var height = 0f
                    mockk<org.bukkit.entity.Interaction>(relaxed = true).also { panel ->
                        every { panel.uniqueId } returns UUID.randomUUID()
                        every { panel.isValid } returns true
                        every { panel.location } answers { targetLocation.clone() }
                        every { panel.teleport(any<org.bukkit.Location>()) } answers { targetLocation = firstArg(); true }
                        every { panel.interactionWidth } answers { width }
                        every { panel.interactionWidth = any() } answers { width = firstArg() }
                        every { panel.interactionHeight } answers { height }
                        every { panel.interactionHeight = any() } answers { height = firstArg() }
                        thirdArg<Consumer<org.bukkit.entity.Interaction>>().accept(panel)
                        panels += panel
                    }
                }
                every { site.world } returns panelWorld
                every { site.adjustedCenter } returns player.location
                val host = PreviewHost(site, BuilderBookPreviewConfirmation(
                    kind = BuilderBookPreviewConfirmationKind.CONSTRUCTION,
                    blockCount = 1, title = "Дом", cooldownRemaining = Duration.ZERO,
                    requiredMaterials = emptyList(),
                ))
                previewPresentation(plugin, config, host, packet.owner, unblockedPlaqueSpace()).use { presentation ->
                    presentation.close(player.uniqueId)
                    presentation.retainConfirmation(site)
                    presentation.captureMockedPlaqueControls()
                    packet.texts.size shouldBe 2
                    // The preview's birch columns are packet-only, extending west of the book origin.
                    // Their minimum world X is 8; both faces must start outside that full block volume.
                    packet.texts.map { it.location.x }.distinct() shouldBe listOf(7.6)
                    packet.texts.forEach { record ->
                        io.mockk.verify(exactly = 1) { record.display.displayWidth = 3f }
                        io.mockk.verify(exactly = 1) { record.display.displayHeight = 1.5f }
                    }
                    panels.size shouldBe 4
                    panels.forEach { panel ->
                        panel.interactionWidth shouldBe 2.35f
                        (panel.interactionHeight > 1.5f) shouldBe true
                    }
                    presentation.refresh(site)
                    presentation.captureMockedPlaqueControls()
                    packet.texts.size shouldBe 2
                    io.mockk.verify(exactly = 4) {
                        panelWorld.spawn(any<org.bukkit.Location>(), org.bukkit.entity.Interaction::class.java, any<Consumer<org.bukkit.entity.Interaction>>())
                    }
                    val admin = mockk<org.bukkit.entity.Player> {
                        every { uniqueId } returns UUID.randomUUID()
                        every { hasPermission(BUILDER_PREVIEW_ADMIN_PERMISSION) } returns true
                    }
                    val stranger = mockk<org.bukkit.entity.Player> {
                        every { uniqueId } returns UUID.randomUUID()
                        every { hasPermission(BUILDER_PREVIEW_ADMIN_PERMISSION) } returns false
                    }
                    packet.audiencePredicates.single()(player) shouldBe true
                    packet.audiencePredicates.single()(admin) shouldBe true
                    packet.audiencePredicates.single()(stranger) shouldBe false
                    val updatedBackground = Color.fromARGB(100, 10, 30, 50)
                    presentation.updatePlaqueAppearance(config.plaqueAppearance().copy(background = updatedBackground,
                        trail = config.plaqueAppearance().trail.copy(enabled = false))) shouldBe 1
                    presentation.captureMockedPlaqueControls()
                    packet.texts.size shouldBe 2
                    panels.size shouldBe 4
                    packet.texts.forEach { record ->
                        io.mockk.verify { record.display.backgroundColor = updatedBackground }
                        io.mockk.verify(exactly = 0) { record.display.remove() }
                    }
                    panels.forEach { panel -> io.mockk.verify(exactly = 0) { panel.remove() } }
                    val glowing = config.plaqueAppearance().copy(glowEnabled = true)
                    presentation.updatePlaqueAppearance(glowing) shouldBe 1
                    presentation.captureMockedPlaqueControls()
                    packet.blocks.size shouldBe 4
                    packet.blocks.forEach { block ->
                        io.mockk.verify { block.isGlowing = true }
                        io.mockk.verify { block.glowColorOverride = glowing.glowColor }
                    }
                    presentation.updatePlaqueAppearance(glowing.copy(glowColor = Color.ORANGE)) shouldBe 1
                    presentation.captureMockedPlaqueControls()
                    packet.blocks.size shouldBe 4
                    presentation.updatePlaqueAppearance(glowing.copy(glowEnabled = false)) shouldBe 1
                    presentation.captureMockedPlaqueControls()
                    packet.blocks.forEach { block -> io.mockk.verify(exactly = 1) { block.remove() } }
                    packet.texts.size shouldBe 2
                    panels.size shouldBe 4
                    presentation.openCurrentPlacement(player, null) shouldBe true
                    val chatMenu = player.openInventory.topInventory
                    val event = org.bukkit.event.player.PlayerInteractEntityEvent(
                        player, panels.first(), org.bukkit.inventory.EquipmentSlot.HAND,
                    )
                    paper.callEvent(event)
                    event.isCancelled.shouldBeTrue()
                    (player.openInventory.topInventory === chatMenu) shouldBe true
                    player.openInventory.topInventory.getItem(25)?.type shouldBe Material.LIME_CONCRETE
                    plain(player.openInventory.topInventory.getItem(25)!!.itemMeta.displayName()!!) shouldContain "Подтвердить строительство"
                    presentation.clearConfirmation(player.uniqueId)
                    panels.forEach { panel -> io.mockk.verify(exactly = 1) { panel.remove() } }
                    packet.texts.forEach { record -> io.mockk.verify(exactly = 1) { record.display.remove() } }
                    io.mockk.verify(exactly = 0) {
                        panelWorld.spawn(any<org.bukkit.Location>(), org.bukkit.entity.TextDisplay::class.java, any<Consumer<org.bukkit.entity.TextDisplay>>())
                    }
                    host.restoreCalls shouldBe 0
                    presentation.openCurrentPlacement(player, null) shouldBe false
                }
            } finally { ConfigManager.clear() }
        }
    }

    test("permitted admin opens a read-only foreign preview status") {
        ConfigManager.clear()
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<ArcBuilderPlugin>()
            installArcVisualPacketBudgetFixture(paper.createSimplePlugin("ArcCoreVisualFixture"))
            BuilderToolsModule.shutdown()
            try {
                val config = BuilderToolsConfig(ConfigManager.ofModule(plugin.dataPath, "builder-tools.yml")).validated()
                val world = paper.addSimpleWorld("book-preview-inspection")
                val owner = paper.addPlayer("HouseOwner").also { it.gameMode = GameMode.CREATIVE }
                val admin = paper.addPlayer("PreviewAdmin").also {
                    it.gameMode = GameMode.CREATIVE
                    it.setLocale(Locale.forLanguageTag("ru-RU"))
                }
                val book = BuildBookData(
                    buildingId = "house.schem",
                    title = "Дом",
                    playerCreated = true,
                    creatorId = owner.uniqueId,
                    creatorName = owner.name,
                    blueprintId = UUID.randomUUID(),
                    contentSha256 = "a".repeat(64),
                    schematicSha256 = "b".repeat(64),
                ).validated()
                val site = previewSite(owner, world.getBlockAt(10, 64, 20).location, book)
                val host = PreviewHost(
                    site,
                    BuilderBookPreviewConfirmation(
                        kind = BuilderBookPreviewConfirmationKind.DRAFT_ACTIVATION,
                        blockCount = 401,
                        title = "Дом",
                        cooldownRemaining = Duration.ZERO,
                        requiredMaterials = emptyList(),
                    ),
                )

                previewPresentation(plugin, config, host).use { presentation ->
                    presentation.openInspectionForTest(admin, site)

                    val inventory = admin.openInventory.topInventory
                    inventory.size shouldBe 27
                    plain(inventory.getItem(13)!!.itemMeta.displayName()!!) shouldContain "Контур дома"
                    plainLore(inventory.getItem(13)!!) shouldContain "HouseOwner"
                    host.adjustments shouldBe emptyList()
                    host.confirmCalls shouldBe 0
                }
            } finally {
                ConfigManager.clear()
            }
        }
    }

    test("closing build confirmation restores the world hologram and placement stage") {
        ConfigManager.clear()
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<ArcBuilderPlugin>()
            installArcVisualPacketBudgetFixture(paper.createSimplePlugin("ArcCoreVisualFixture"))
            BuilderToolsModule.shutdown()
            try {
                val config = BuilderToolsConfig(ConfigManager.ofModule(plugin.dataPath, "builder-tools.yml")).validated()
                val world = paper.addSimpleWorld("book-preview-close")
                val player = paper.addPlayer("PreviewCloser").also {
                    it.gameMode = GameMode.CREATIVE
                    it.setLocale(Locale.forLanguageTag("ru-RU"))
                }
                val site = previewSite(player, world.getBlockAt(10, 64, 20).location, BuildBookData("house.schem", "Дом"))
                val host = PreviewHost(
                    site,
                    BuilderBookPreviewConfirmation(
                        kind = BuilderBookPreviewConfirmationKind.CONSTRUCTION,
                        blockCount = 1,
                        title = "Дом",
                        cooldownRemaining = Duration.ZERO,
                        requiredMaterials = emptyList(),
                    ),
                    deferPreparation = true,
                    hasCurrentConfirmation = false,
                    deferConfirmation = true,
                )

                previewPresentation(plugin, config, host).use { presentation ->
                    presentation.openPlacementForTest(player, site)
                    click(paper, player, 25).isCancelled.shouldBeTrue()
                    val staleCompletion = checkNotNull(host.pendingPreparation)
                    paper.callEvent(InventoryCloseEvent(player.openInventory))

                    host.restoreCalls shouldBe 1

                    presentation.openPlacementForTest(player, site)
                    click(paper, player, 25).isCancelled.shouldBeTrue()
                    host.hasCurrentConfirmation = true
                    staleCompletion(host.confirmation)
                    host.confirmCalls shouldBe 0
                    checkNotNull(host.pendingPreparation)(host.confirmation)
                    host.confirmCalls shouldBe 1

                    presentation.openPlacementForTest(player, site)
                    val reopened = player.openInventory.topInventory
                    reopened.getItem(25)?.type shouldBe Material.LIME_CONCRETE
                    plain(reopened.getItem(25)!!.itemMeta.displayName()!!) shouldContain "Подтвердить строительство"
                    host.restoreCalls shouldBe 1
                }
            } finally {
                ConfigManager.clear()
            }
        }
    }

    test("placement menu moves only the preview session and confirms from the same hologram menu") {
        ConfigManager.clear()
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<ArcBuilderPlugin>()
            installArcVisualPacketBudgetFixture(paper.createSimplePlugin("ArcCoreVisualFixture"))
            BuilderToolsModule.shutdown()
            try {
                val moduleConfig = ConfigManager.ofModule(plugin.dataPath, "builder-tools.yml")
                val config = BuilderToolsConfig(moduleConfig).validated()
                moduleConfig.setInt(
                    "paper-menus.preview.layouts.book-preview-placement.elements.left.slot",
                    18,
                )
                BuilderToolsConfiguration.install(
                    BuilderToolsConfiguration.current(plugin.dataPath).copy(builder = moduleConfig, settings = config),
                )
                val world = paper.addSimpleWorld("book-preview-menu")
                val player = paper.addPlayer("PreviewOwner").also {
                    it.gameMode = GameMode.CREATIVE
                    it.setLocale(Locale.forLanguageTag("ru-RU"))
                }
                val book = BuildBookData("house.schem", "Дом")
                val site = mockk<ConstructionSite>()
                every { site.transforming } returns false
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
                    displays = mockk(relaxed = true),
                    planningTasks = ImmediateBuilderPlanningTasks,
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

                    every { site.transforming } returns true
                    repeat(3) { click(paper, player, 25).isCancelled.shouldBeTrue() }
                    player.nextComponentMessage() shouldBe null
                    host.confirmCalls shouldBe 0
                    io.mockk.verify(exactly = 0) { site.snapshot() }
                    every { site.transforming } returns false

                    click(paper, player, 25).isCancelled.shouldBeTrue()
                    plain(checkNotNull(player.nextComponentMessage())) shouldContain "7 ч 0 мин"
                    paper.performTicks(1)
                    val stillPlacement = player.openInventory.topInventory
                    (stillPlacement === placement) shouldBe true
                    stillPlacement.size shouldBe 45
                    stillPlacement.getItem(21)?.type shouldBe Material.ARROW
                    stillPlacement.getItem(25)?.type shouldBe Material.LIME_CONCRETE
                    plain(stillPlacement.getItem(25)!!.itemMeta.displayName()!!) shouldContain "Подтвердить строительство"
                    host.confirmCalls shouldBe 0
                    // Even after conversion to a prepared plan, the same menu
                    // must restore editable placement before moving it again.
                    click(paper, player, 18).isCancelled.shouldBeTrue()
                    host.restoreCalls shouldBe 1
                    host.adjustments.last() shouldBe BuildBookPreviewAdjustment.Move(BuildBookPreviewMove.LEFT)

                    host.confirmation = host.confirmation.copy(
                        cooldownRemaining = Duration.ZERO,
                        requiredMaterials = emptyList(),
                    )
                    click(paper, player, 25).isCancelled.shouldBeTrue()
                    host.confirmCalls shouldBe 1
                    host.completedConfirmations shouldBe listOf(BuilderBookPreviewConfirmationKind.CONSTRUCTION)
                }
            } finally {
                ConfigManager.clear()
            }
        }
    }

    test("draft activation stays in the preview gui and uses its green button to mint") {
        ConfigManager.clear()
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<ArcBuilderPlugin>()
            installArcVisualPacketBudgetFixture(paper.createSimplePlugin("ArcCoreVisualFixture"))
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
                every { site.transforming } returns false
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
                    hasCurrentConfirmation = false,
                    deferConfirmation = true,
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
                    displays = mockk(relaxed = true),
                    planningTasks = ImmediateBuilderPlanningTasks,
                ).use { presentation ->
                    presentation.openPlacementForTest(player, site)
                    val originalMenu = player.openInventory.topInventory
                    plain(originalMenu.getItem(25)!!.itemMeta.displayName()!!) shouldContain "Рассчитать активацию"
                    click(paper, player, 25).isCancelled.shouldBeTrue()
                    host.confirmCalls shouldBe 0
                    paper.performTicks(1)
                    (player.openInventory.topInventory === originalMenu) shouldBe true
                    plain(player.openInventory.topInventory.getItem(25)!!.itemMeta.displayName()!!) shouldContain
                        "148.45"
                    host.confirmation = host.confirmation.copy(issuePriceMinor = 15_000)
                    click(paper, player, 25).isCancelled.shouldBeTrue()
                    host.confirmCalls shouldBe 0
                    paper.performTicks(1)
                    plain(player.openInventory.topInventory.getItem(25)!!.itemMeta.displayName()!!) shouldContain "150.00"
                    click(paper, player, 25).isCancelled.shouldBeTrue()
                    host.confirmCalls shouldBe 1
                    host.completedConfirmations shouldBe emptyList()
                    (player.openInventory.topInventory === originalMenu) shouldBe true
                    click(paper, player, 25).isCancelled.shouldBeTrue()
                    host.confirmCalls shouldBe 1
                    checkNotNull(host.pendingConfirmation).invoke(true)
                    host.completedConfirmations shouldBe listOf(BuilderBookPreviewConfirmationKind.DRAFT_ACTIVATION)
                }
            } finally {
                ConfigManager.clear()
            }
        }
    }

    test("system-book selector marks the current choice and forwards selection without confirming construction") {
        ConfigManager.clear()
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<ArcBuilderPlugin>()
            installArcVisualPacketBudgetFixture(paper.createSimplePlugin("ArcCoreVisualFixture"))
            BuilderToolsModule.shutdown()
            try {
                val config = BuilderToolsConfig(ConfigManager.ofModule(plugin.dataPath, "builder-tools.yml")).validated()
                val player = paper.addPlayer("SelectorOwner").also {
                    it.gameMode = GameMode.CREATIVE
                    it.setLocale(Locale.forLanguageTag("ru-RU"))
                }
                val choices = listOf("birch.schem", "oak.schem", "spruce.schem", "stone.schem").mapIndexed { index, id ->
                    SystemBuildBookDefinition(
                        buildingId = id,
                        title = "Дом ${index + 1}",
                        playerEnabled = true,
                        materialsIncluded = false,
                    ).validated()
                }
                val book = BuildBookData(
                    buildingId = "spruce.schem",
                    title = "Дом 3",
                    selectableBuildingIds = choices.map(SystemBuildBookDefinition::buildingId),
                ).validated()
                val site = mockk<ConstructionSite>(relaxed = true)
                val host = PreviewHost(
                    site,
                    BuilderBookPreviewConfirmation(
                        kind = BuilderBookPreviewConfirmationKind.CONSTRUCTION,
                        blockCount = 1,
                        title = book.title,
                        cooldownRemaining = Duration.ZERO,
                        requiredMaterials = emptyList(),
                    ),
                )
                val selected = mutableListOf<String>()

                previewPresentation(plugin, config, host).use { presentation ->
                    presentation.openSelector(player, book, choices, selected::add) { error("selector edit should not be called") }

                    val selector = player.openInventory.topInventory
                    selector.size shouldBe 36
                    selector.getItem(10)?.type shouldBe Material.OAK_DOOR
                    selector.getItem(12)?.type shouldBe Material.OAK_DOOR
                    selector.getItem(14)?.type shouldBe Material.LIME_DYE
                    selector.getItem(16)?.type shouldBe Material.OAK_DOOR

                    click(paper, player, 16).isCancelled.shouldBeTrue()
                    selected shouldBe listOf("stone.schem")
                    host.confirmCalls shouldBe 0
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
    private val deferPreparation: Boolean = false,
    var hasCurrentConfirmation: Boolean = true,
    var deferConfirmation: Boolean = false,
) : BuilderBookPreviewPresentationHost {
    val adjustments = mutableListOf<BuildBookPreviewAdjustment>()
    var confirmCalls = 0
    var restoreCalls = 0
    val completedConfirmations = mutableListOf<BuilderBookPreviewConfirmationKind>()
    var pendingPreparation: ((BuilderBookPreviewConfirmation?) -> Unit)? = null
    var pendingConfirmation: ((Boolean) -> Unit)? = null

    override fun adjust(player: org.bukkit.entity.Player, adjustment: BuildBookPreviewAdjustment): ConstructionSite {
        adjustments += adjustment
        return site
    }

    override fun prepare(
        player: org.bukkit.entity.Player,
        site: ConstructionSite,
        complete: (BuilderBookPreviewConfirmation?) -> Unit,
    ) {
        if (deferPreparation) pendingPreparation = complete else {
            hasCurrentConfirmation = true
            complete(confirmation)
        }
    }

    override fun currentConfirmation(player: org.bukkit.entity.Player): BuilderBookPreviewConfirmation? =
        confirmation.takeIf { hasCurrentConfirmation }

    override fun confirm(player: org.bukkit.entity.Player, complete: (Boolean) -> Unit) {
        confirmCalls += 1
        if (deferConfirmation) pendingConfirmation = complete else complete(true)
    }

    override fun complete(player: org.bukkit.entity.Player, kind: BuilderBookPreviewConfirmationKind) {
        completedConfirmations += kind
    }

    override fun restore(player: org.bukkit.entity.Player, snapshot: ConstructionSiteSnapshot): ConstructionSite {
        restoreCalls += 1
        hasCurrentConfirmation = false
        return site
    }

    override fun cancel(player: org.bukkit.entity.Player) = Unit
}

private fun previewSite(
    player: org.bukkit.entity.Player,
    location: org.bukkit.Location,
    book: BuildBookData,
): ConstructionSite {
    val site = mockk<ConstructionSite>()
                every { site.transforming } returns false
    val snapshot = mockk<ConstructionSiteSnapshot>()
    every { site.player } returns player
    every { site.world } returns location.world!!
    every { site.adjustedCenter } returns location
    every { site.view } returns schematicTestSnapshot(listOf(
        ArcSchematicBlock(ArcSchematicPoint(0, 2, 0), "minecraft:birch_log", false, false),
    ))
    every { site.corners } returns ConstructionSite.Corners(BlockVector3.ZERO, BlockVector3.ZERO)
    every { site.worldLocation(any()) } answers {
        val relative = firstArg<BlockVector3>()
        location.clone().add(relative.x().toDouble(), relative.y().toDouble(), relative.z().toDouble())
    }
    every { site.bookData } returns book
    every { site.centerBlock } returns location
    every { site.rotation } returns 0
    every { site.mirrored } returns false
    every { site.expiresAtMillis } returns System.currentTimeMillis() + 180_000L
    every { site.snapshot() } returns snapshot
    return site
}

private fun previewPresentation(
    plugin: ArcBuilderPlugin,
    config: BuilderToolsConfig,
    host: BuilderBookPreviewPresentationHost,
    displays: PaperPacketDisplays = mockk(relaxed = true),
    space: PaperSpatialSpace = unblockedPlaqueSpace(),
    planningTasks: BuilderPlanningTasks = ImmediateBuilderPlanningTasks,
): BuilderBookPreviewPresentation = BuilderBookPreviewPresentation(
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
    displays = displays,
    space = space,
    planningTasks = planningTasks,
)

/** The mocked display owner has no tick; drive its Core participant's native capture explicitly. */
private fun BuilderBookPreviewPresentation.captureMockedPlaqueControls() {
    val controls = javaClass.getDeclaredField("controls").apply { isAccessible = true }.get(this)
    val participant = controls.javaClass.getDeclaredField("participant").apply { isAccessible = true }.get(controls)
    val capture = participant.javaClass.getMethod("captureSteps", List::class.java).apply { isAccessible = true }
    val steps = capture.invoke(participant, emptyList<Any>()) as Iterator<*>
    while (steps.hasNext()) steps.next()
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
