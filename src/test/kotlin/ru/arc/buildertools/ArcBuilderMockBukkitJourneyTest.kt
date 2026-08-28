package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.opentest4j.TestAbortedException
import ru.arc.autobuild.ConstructionSite
import ru.arc.autobuild.BuildBookCodec
import ru.arc.autobuild.Building
import ru.arc.autobuild.BuildingManager
import ru.arc.autobuild.PlayerBuildBookStore
import ru.arc.autobuild.PlayerBuildBookDigestInspection
import ru.arc.autobuild.PlayerBuildBookTemplate
import ru.arc.autobuild.PreparedPlayerBuildBookTemplate
import ru.arc.config.ConfigManager
import ru.arc.observability.RuntimeHealthState
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.loadPlugin
import ru.ruscrafting.builder.paper.ArcBuilderPlugin
import java.time.Duration
import java.util.UUID

/**
 * Full player journeys through the real plugin, command executor, Bukkit events,
 * scheduler, journal barriers, inventory exchange and preview orchestration.
 * External MySQL contracts deliberately stay in the GitHub-CI integration suite.
 */
class ArcBuilderMockBukkitJourneyTest : FunSpec({
    test("survival player copies, previews, confirms and undoes a build") {
        strictMockBukkit(open = { ArcBuilderJourney.open() }) { journey ->
            val player = journey.builder("SurvivalBuilder", GameMode.SURVIVAL)
            val world = journey.world
            player.teleport(Location(world, 0.5, 64.0, 3.5, 0f, 0f))
            world.getBlockAt(0, 64, 0).type = Material.STONE
            world.getBlockAt(1, 64, 0).type = Material.OAK_PLANKS
            player.inventory.setItemInMainHand(ItemStack(Material.ECHO_SHARD))

            player.performCommand("builder wand") shouldBe true
            val wand = player.inventory.itemInMainHand
            wand.type shouldBe Material.ECHO_SHARD
            wand.itemMeta.persistentDataContainer.has(
                org.bukkit.NamespacedKey(journey.plugin, "builder_selector"),
                PersistentDataType.BYTE,
            ) shouldBe true
            checkNotNull(wand.itemMeta.displayName()).decoration(TextDecoration.ITALIC) shouldBe
                TextDecoration.State.FALSE
            checkNotNull(wand.itemMeta.lore()).all {
                it.decoration(TextDecoration.ITALIC) == TextDecoration.State.FALSE
            } shouldBe true

            journey.select(player, world, wand, 0, 64, 0, 1, 64, 0)
            val selectionFrame = checkNotNull(journey.renderer.selections[player.uniqueId])
            checkNotNull(selectionFrame.selection).volume shouldBe 2
            selectionFrame.points.first?.x shouldBe 0
            selectionFrame.points.second?.x shouldBe 1
            val selectionRenders = journey.renderer.selectionRenders
            journey.paper.performTicks(30)
            journey.renderer.selectionRenders shouldBeGreaterThan selectionRenders

            player.performCommand("builder copy") shouldBe true
            player.inventory.addItem(ItemStack(Material.STONE), ItemStack(Material.OAK_PLANKS))
            player.teleport(Location(world, 10.5, 64.0, 13.5, 0f, 0f))

            player.performCommand("builder paste") shouldBe true
            world.getBlockAt(10, 64, 10).type shouldBe Material.AIR
            world.getBlockAt(11, 64, 10).type shouldBe Material.AIR
            checkNotNull(journey.renderer.plans[player.uniqueId]).changes.size shouldBe 2
            journey.renderer.selections.containsKey(player.uniqueId) shouldBe true

            player.performCommand("builder confirm") shouldBe true
            journey.awaitSettled(player) {
                world.getBlockAt(10, 64, 10).type == Material.STONE &&
                    world.getBlockAt(11, 64, 10).type == Material.OAK_PLANKS
            }
            journey.amount(player, Material.STONE) shouldBe 0
            journey.amount(player, Material.OAK_PLANKS) shouldBe 0
            world.getBlockAt(0, 64, 0).type shouldBe Material.STONE
            world.getBlockAt(1, 64, 0).type shouldBe Material.OAK_PLANKS

            player.performCommand("builder undo") shouldBe true
            world.getBlockAt(10, 64, 10).type shouldBe Material.STONE
            world.getBlockAt(11, 64, 10).type shouldBe Material.OAK_PLANKS
            player.performCommand("builder confirm") shouldBe true
            journey.awaitSettled(player) {
                world.getBlockAt(10, 64, 10).type == Material.AIR &&
                    world.getBlockAt(11, 64, 10).type == Material.AIR
            }
            journey.amount(player, Material.STONE) shouldBe 1
            journey.amount(player, Material.OAK_PLANKS) shouldBe 1
        }
    }

    test("creative wilderness paste rotates safe falling blocks and skips unsafe blocks") {
        strictMockBukkit(open = { ArcBuilderJourney.open() }) { journey ->
            val player = journey.builder("CreativeBuilder", GameMode.CREATIVE)
            val world = journey.world
            player.teleport(Location(world, 0.5, 64.0, 3.5, 0f, 0f))
            world.getBlockAt(0, 64, 0).type = Material.SAND
            world.getBlockAt(1, 64, 0).type = Material.BROWN_CONCRETE_POWDER
            world.getBlockAt(2, 64, 0).type = Material.BEDROCK
            world.getBlockAt(3, 64, 0).type = Material.CHEST
            player.inventory.setItemInMainHand(ItemStack(Material.ECHO_SHARD))
            player.performCommand("builder wand") shouldBe true
            val wand = player.inventory.itemInMainHand
            journey.select(player, world, wand, 0, 64, 0, 3, 64, 0)

            player.performCommand("builder copy") shouldBe true

            player.teleport(Location(world, 10.5, 64.0, 10.5, 0f, 0f))
            player.performCommand("builder paste right") shouldBe true
            checkNotNull(journey.renderer.plans[player.uniqueId]).changes.size shouldBe 2
            world.getBlockAt(13, 64, 10).type shouldBe Material.AIR
            world.getBlockAt(13, 64, 11).type shouldBe Material.AIR
            player.performCommand("builder confirm") shouldBe true
            journey.awaitSettled(player) {
                world.getBlockAt(13, 64, 10).type == Material.SAND &&
                    world.getBlockAt(13, 64, 11).type == Material.BROWN_CONCRETE_POWDER
            }
            world.getBlockAt(13, 64, 12).type shouldBe Material.AIR
            world.getBlockAt(13, 64, 13).type shouldBe Material.AIR
            journey.amount(player, Material.SAND) shouldBe 0
            journey.amount(player, Material.BROWN_CONCRETE_POWDER) shouldBe 0
        }
    }

    test("fill remains a confirmed two-phase operation and respects the per-tick batch") {
        strictMockBukkit(open = { ArcBuilderJourney.open(blocksPerTick = 2) }) { journey ->
            val player = journey.builder("FillBuilder", GameMode.SURVIVAL)
            val world = journey.world
            player.teleport(Location(world, 0.5, 64.0, 3.5, 0f, 0f))
            world.getBlockAt(0, 64, 0).type = Material.DIRT
            world.getBlockAt(4, 64, 0).type = Material.DIRT
            player.inventory.setItemInMainHand(ItemStack(Material.ECHO_SHARD))
            player.performCommand("builder wand") shouldBe true
            journey.select(player, world, player.inventory.itemInMainHand, 0, 64, 0, 4, 64, 0)
            world.getBlockAt(0, 64, 0).type = Material.AIR
            world.getBlockAt(4, 64, 0).type = Material.AIR
            player.inventory.addItem(ItemStack(Material.STONE, 5))

            player.performCommand("builder fill stone") shouldBe true
            journey.countLine(world, Material.STONE) shouldBe 0
            player.performCommand("builder confirm") shouldBe true
            journey.await("first mutation batch") { journey.countLine(world, Material.STONE) > 0 }
            journey.countLine(world, Material.STONE) shouldBe 2
            journey.paper.performTicks(1)
            journey.countLine(world, Material.STONE) shouldBe 4
            journey.paper.performTicks(1)
            journey.countLine(world, Material.STONE) shouldBe 5
            journey.awaitSettled(player) { journey.countLine(world, Material.STONE) == 5 }
            journey.amount(player, Material.STONE) shouldBe 0
        }
    }

    test("cancel and quit remove only the owning player's persistent previews and clipboard") {
        strictMockBukkit(open = { ArcBuilderJourney.open() }) { journey ->
            val owner = journey.builder("PreviewOwner", GameMode.CREATIVE)
            val world = journey.world
            owner.teleport(Location(world, 0.5, 64.0, 3.5, 0f, 0f))
            world.getBlockAt(0, 64, 0).type = Material.STONE
            owner.inventory.setItemInMainHand(ItemStack(Material.ECHO_SHARD))
            owner.performCommand("builder wand") shouldBe true
            journey.select(owner, world, owner.inventory.itemInMainHand, 0, 64, 0, 0, 64, 0)
            owner.performCommand("builder copy") shouldBe true
            owner.teleport(Location(world, 5.5, 64.0, 3.5, 0f, 0f))
            owner.performCommand("builder paste") shouldBe true

            journey.renderer.selections.containsKey(owner.uniqueId) shouldBe true
            journey.renderer.plans.containsKey(owner.uniqueId) shouldBe true

            owner.performCommand("builder cancel") shouldBe true
            journey.renderer.plans.containsKey(owner.uniqueId) shouldBe false
            journey.renderer.selections.containsKey(owner.uniqueId) shouldBe true
            journey.paper.callEvent(
                PlayerQuitEvent(owner, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED),
            )
            journey.renderer.selections.containsKey(owner.uniqueId) shouldBe false
            owner.performCommand("builder paste") shouldBe true
            journey.renderer.plans.containsKey(owner.uniqueId) shouldBe false
        }
    }

    test("book draft after anchored paste journeys releases its player lease") {
        strictMockBukkit(open = { ArcBuilderJourney.open() }) { journey ->
            val player = journey.builder("DraftBuilder", GameMode.CREATIVE)
            val world = journey.world
            player.teleport(Location(world, 0.5, 64.0, 3.5, 0f, 0f))
            world.getBlockAt(0, 64, 0).type = Material.STONE
            world.getBlockAt(1, 64, 0).type = Material.OAK_PLANKS
            player.inventory.setItemInMainHand(ItemStack(Material.ECHO_SHARD))
            player.performCommand("builder wand") shouldBe true
            journey.select(player, world, player.inventory.itemInMainHand, 0, 64, 0, 1, 64, 0)
            player.performCommand("builder copy") shouldBe true

            listOf(10.5, 14.5, 18.5).forEach { x ->
                player.teleport(Location(world, x, 64.0, 3.5, 0f, 0f))
                if (x == 14.5) world.getBlockAt(14, 64, 0).type = Material.DEEPSLATE
                player.performCommand("builder paste") shouldBe true
                player.performCommand("builder confirm") shouldBe true
                journey.awaitSettled(player) {
                    world.getBlockAt(x.toInt(), 64, 0).type == Material.STONE &&
                        world.getBlockAt(x.toInt() + 1, 64, 0).type == Material.OAK_PLANKS
                }
            }

            player.teleport(Location(world, 0.5, 64.0, 3.5, 0f, 0f))
            player.inventory.setItemInMainHand(ItemStack(Material.BOOK))
            player.performCommand("builder book draft Original") shouldBe true
            val blueprintKey = checkNotNull(org.bukkit.NamespacedKey.fromString("arc:build_book_blueprint_uuid"))
            val instanceKey = checkNotNull(org.bukkit.NamespacedKey.fromString("arc:build_book_instance_uuid"))
            journey.await("anchored draft delivery and player lease release") {
                val data = player.inventory.itemInMainHand.itemMeta.persistentDataContainer
                val isDraft = data.has(blueprintKey, PersistentDataType.STRING) &&
                    !data.has(instanceKey, PersistentDataType.STRING)
                if (!isDraft) return@await false
                val commandEvent = PlayerCommandPreprocessEvent(player, "/builder status")
                journey.paper.callEvent(commandEvent)
                !commandEvent.isCancelled && journey.activeLeases() == 0
            }

            val draft = checkNotNull(BuildBookCodec.read(player.inventory.itemInMainHand))
            draft.sourceRotation shouldBe BuildingManager.rotationFromYaw(player.yaw)
            BuildingManager.addBuilding(Building(draft.buildingId))

            val firstAnchor = world.getBlockAt(6, 64, 6)
            journey.paper.callEvent(
                PlayerInteractEvent(
                    player,
                    Action.RIGHT_CLICK_BLOCK,
                    player.inventory.itemInMainHand,
                    firstAnchor,
                    BlockFace.UP,
                    EquipmentSlot.HAND,
                ),
            )
            checkNotNull(BuildingManager.pending(player.uniqueId)).also { preview ->
                preview.centerBlock.blockX shouldBe 6
                preview.centerBlock.blockZ shouldBe 6
                preview.fullRotation shouldBe 0
            }

            val secondAnchor = world.getBlockAt(9, 64, 11)
            journey.paper.callEvent(
                PlayerInteractEvent(
                    player,
                    Action.RIGHT_CLICK_BLOCK,
                    player.inventory.itemInMainHand,
                    secondAnchor,
                    BlockFace.UP,
                    EquipmentSlot.HAND,
                ),
            )
            checkNotNull(BuildingManager.pending(player.uniqueId)).also { preview ->
                preview.centerBlock.blockX shouldBe 9
                preview.centerBlock.blockZ shouldBe 11
            }

            player.performCommand("builder cancel") shouldBe true
            BuildingManager.pending(player.uniqueId) shouldBe null

            journey.paper.callEvent(
                PlayerInteractEvent(
                    player,
                    Action.RIGHT_CLICK_BLOCK,
                    player.inventory.itemInMainHand,
                    firstAnchor,
                    BlockFace.UP,
                    EquipmentSlot.HAND,
                ),
            )
            checkNotNull(BuildingManager.pending(player.uniqueId))
            player.performCommand("builder book cancel") shouldBe true
            BuildingManager.pending(player.uniqueId) shouldBe null
        }
    }
})

private class ArcBuilderJourney private constructor(
    val paper: MockBukkitTestRuntime,
    val plugin: ArcBuilderPlugin,
    val world: World,
    val renderer: RecordingBuilderDisplayRenderer,
    private val runtime: BuilderToolsRuntime,
) : AutoCloseable {
    fun builder(name: String, mode: GameMode): Player = paper.addPlayer(name).also { player ->
        player.gameMode = mode
        grantBuilderPermissions(player)
    }

    fun grantBuilderPermissions(player: Player) {
        listOf(
            "arcbuild.use",
            "arcbuild.fill",
            "arcbuild.copy",
            "arcbuild.paste",
            "arcbuild.deconstruct",
            "arcbuild.crown",
            "arcbuild.book.use",
            "arcbuild.book.create",
        ).forEach { permission -> player.addAttachment(plugin, permission, true) }
        player.recalculatePermissions()
    }

    @Suppress("DEPRECATION")
    fun select(
        player: Player,
        world: World,
        wand: ItemStack,
        x1: Int,
        y1: Int,
        z1: Int,
        x2: Int,
        y2: Int,
        z2: Int,
    ) {
        val first = PlayerInteractEvent(
            player,
            Action.LEFT_CLICK_BLOCK,
            wand,
            world.getBlockAt(x1, y1, z1),
            BlockFace.UP,
            EquipmentSlot.HAND,
        )
        paper.callEvent(first)
        first.isCancelled shouldBe true
        val second = PlayerInteractEvent(
            player,
            Action.RIGHT_CLICK_BLOCK,
            wand,
            world.getBlockAt(x2, y2, z2),
            BlockFace.UP,
            EquipmentSlot.HAND,
        )
        paper.callEvent(second)
        second.isCancelled shouldBe true
    }

    fun amount(player: Player, material: Material): Int = player.inventory.contents
        .filterNotNull()
        .filter { it.type == material }
        .sumOf(ItemStack::getAmount)

    fun countLine(world: World, material: Material): Int = (0..4).count { x ->
        world.getBlockAt(x, 64, 0).type == material
    }

    fun awaitSettled(player: Player, condition: () -> Boolean) {
        await("completed builder operation") {
            condition() && !runtime.isPlayerLeaseActive(player.uniqueId) &&
                runtime.runtimeHealthContribution().activeLeases == 0
        }
    }

    fun activeLeases(): Int = runtime.runtimeHealthContribution().activeLeases

    fun await(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (System.nanoTime() < deadline) {
            paper.performTicks(1)
            if (condition()) return
            Thread.yield()
        }
        error("Timed out waiting for $description; health=${runtime.runtimeHealthContribution()}")
    }

    override fun close() {
        runCatching { runtime.close() }
        runCatching { paper.server.pluginManager.disablePlugin(plugin) }
        paper.close()
        ConfigManager.clear()
    }

    companion object {
        fun open(blocksPerTick: Int = 2): ArcBuilderJourney {
            ConfigManager.clear()
            val paper = MockBukkitTestRuntime.open()
            try {
                val plugin = paper.loadPlugin<ArcBuilderPlugin>()
                val config = ConfigManager.ofModule(plugin.dataPath, "builder-tools.yml").apply {
                    setBoolean("enabled", true)
                    setStringList("allowed-worlds", listOf("*"))
                    setBoolean("safety.require-coreprotect", false)
                    setBoolean("shop.enabled", false)
                    setInt("limits.blocks-per-tick", blocksPerTick)
                    saveStrict()
                }
                BuilderToolsModule.shutdown()
                val renderer = RecordingBuilderDisplayRenderer()
                val runtime = BuilderToolsRuntime(
                    plugin = plugin,
                    config = BuilderToolsConfig(config).validated(),
                    displayRenderer = renderer,
                    blockDataRotation = BuilderBlockDataRotation { data, _ -> data },
                    draftStorage = InMemoryBuilderDraftStorage(),
                    bookSchematicVerifier = BuilderBookSchematicVerifier { true },
                )
                checkNotNull(plugin.getCommand("builder")).apply {
                    setExecutor(runtime)
                    tabCompleter = runtime
                }
                val world = paper.addSimpleWorld("survival")
                world.loadChunk(0, 0)
                val journey = ArcBuilderJourney(paper, plugin, world, renderer, runtime)
                journey.await("ArcBuilder runtime readiness") {
                    runtime.runtimeHealthContribution().state == RuntimeHealthState.UP
                }
                return journey
            } catch (failure: Throwable) {
                runCatching { paper.close() }
                ConfigManager.clear()
                throw failure
            }
        }
    }
}

private class InMemoryBuilderDraftStorage : BuilderDraftStorage {
    private val templates = mutableMapOf<String, PlayerBuildBookTemplate>()

    override fun prepare(creatorId: UUID, source: BuilderClipboard): PreparedPlayerBuildBookTemplate =
        PreparedPlayerBuildBookTemplate(
            creatorId = creatorId,
            fileName = PlayerBuildBookStore.fileName(creatorId, source),
            contentSha256 = PlayerBuildBookStore.contentSha256(source),
            blockCount = source.blocks.size,
            writeSchematic = {},
        )

    override fun persist(prepared: PreparedPlayerBuildBookTemplate): PlayerBuildBookTemplate =
        PlayerBuildBookTemplate(
            buildingId = prepared.fileName,
            contentSha256 = prepared.contentSha256,
            schematicSha256 = "b".repeat(64),
            blockCount = prepared.blockCount,
        ).also { templates[it.buildingId] = it }

    override fun inspectSchematic(buildingId: String): PlayerBuildBookDigestInspection =
        templates[buildingId]?.let { PlayerBuildBookDigestInspection.Ready(it.schematicSha256) }
            ?: PlayerBuildBookDigestInspection.Missing

    override fun inspectContent(buildingId: String): PlayerBuildBookDigestInspection =
        templates[buildingId]?.let { PlayerBuildBookDigestInspection.Ready(it.contentSha256) }
            ?: PlayerBuildBookDigestInspection.Missing

    override fun register(template: PlayerBuildBookTemplate) = Unit
}

private data class RecordedSelection(
    val points: BuilderSelectionPoints,
    val selection: BuilderSelection?,
)

private class RecordingBuilderDisplayRenderer : BuilderDisplayRenderer {
    val selections = mutableMapOf<UUID, RecordedSelection>()
    val plans = mutableMapOf<UUID, BuilderPlan>()
    private val books = mutableSetOf<UUID>()
    var selectionRenders = 0
        private set

    override fun selection(player: Player, points: BuilderSelectionPoints, selection: BuilderSelection?) {
        selectionRenders++
        selections[player.uniqueId] = RecordedSelection(points, selection)
    }

    override fun clearSelection(playerId: UUID) {
        selections.remove(playerId)
    }

    override fun plan(player: Player, plan: BuilderPlan) {
        plans[player.uniqueId] = plan
    }

    override fun clearPlan(playerId: UUID) {
        plans.remove(playerId)
    }

    override fun clearPlayer(playerId: UUID) {
        selections.remove(playerId)
        plans.remove(playerId)
        books.remove(playerId)
    }

    override fun open(site: ConstructionSite) {
        books += site.player.uniqueId
    }

    override fun refresh(site: ConstructionSite) {
        books += site.player.uniqueId
    }

    override fun close(playerId: UUID) {
        books.remove(playerId)
    }

    override fun close() {
        selections.clear()
        plans.clear()
        books.clear()
    }
}

private inline fun strictMockBukkit(
    open: () -> ArcBuilderJourney,
    block: (ArcBuilderJourney) -> Unit,
) {
    try {
        open().use(block)
    } catch (failure: TestAbortedException) {
        throw AssertionError("MockBukkit journey reached an unsupported platform API", failure)
    }
}
