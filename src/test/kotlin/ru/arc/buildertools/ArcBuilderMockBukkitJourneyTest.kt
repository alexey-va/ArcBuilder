package ru.arc.buildertools

import com.destroystokyo.paper.event.brigadier.AsyncPlayerSendSuggestionsEvent
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.world.block.BaseBlock
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.MultipleFacing
import org.bukkit.block.data.type.Door
import org.bukkit.block.data.type.Stairs
import org.bukkit.block.structure.StructureRotation
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.loot.LootTable
import org.opentest4j.TestAbortedException
import ru.arc.autobuild.ConstructionSite
import ru.arc.autobuild.BuildBookCodec
import ru.arc.autobuild.BuildBookData
import ru.arc.autobuild.BuildBookItems
import ru.arc.autobuild.Building
import ru.arc.autobuild.BuildingManager
import ru.arc.autobuild.PlayerBuildBookStore
import ru.arc.autobuild.PlayerBuildBookDigestInspection
import ru.arc.autobuild.PlayerBuildBookTemplate
import ru.arc.autobuild.PreparedPlayerBuildBookTemplate
import ru.arc.autobuild.SystemBuildBookDefinition
import ru.arc.config.ConfigManager
import ru.arc.observability.RuntimeHealthState
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.loadPlugin
import ru.arc.util.BlockUtils.rotateBlockData
import ru.ruscrafting.builder.paper.ArcBuilderPlugin
import java.time.Duration
import java.util.Locale
import java.util.UUID

/**
 * Full player journeys through the real plugin, command executor, Bukkit events,
 * scheduler, journal barriers, inventory exchange and preview orchestration.
 * External MySQL contracts deliberately stay in the GitHub-CI integration suite.
 */
class ArcBuilderMockBukkitJourneyTest : FunSpec({
    test("block-data rotation never mutates a shared schematic rail state") {
        val shared = mockk<BlockData>()
        val firstClone = mockk<BlockData>(relaxed = true)
        val secondClone = mockk<BlockData>(relaxed = true)
        every { shared.clone() } returnsMany listOf(firstClone, secondClone)

        val first = rotateBlockData(shared, 90)
        val second = rotateBlockData(shared, 90)

        first shouldBeSameInstanceAs firstClone
        second shouldBeSameInstanceAs secondClone
        verify(exactly = 0) { shared.rotate(any()) }
        verify(exactly = 1) { firstClone.rotate(StructureRotation.CLOCKWISE_90) }
        verify(exactly = 1) { secondClone.rotate(StructureRotation.CLOCKWISE_90) }
    }

    test("reviewed books place empty furniture and preserve player container protection") {
        strictMockBukkit(open = { ArcBuilderJourney.open() }) { journey ->
            val player = journey.builder("FurnitureBuilder", GameMode.SURVIVAL)
            player.teleport(Location(journey.world, 6.5, 64.0, 6.5))
            val target = journey.world.getBlockAt(6, 64, 6)
            listOf(Material.RED_BED, Material.BARREL, Material.FURNACE, Material.CHEST, Material.POTTED_POPPY).forEach { material ->
                val data = material.createBlockData()
                journey.planBuildBookBlock(player, target, data) shouldBe BuilderBookPlacementResult.SkippedUnsafe
                val change = journey.planBuildBookBlock(player, target, data, allowSystemFurniture = true)
                    as BuilderBookPlacementResult.Change
                change.block.afterBlockData shouldBe data.asString
                if (material == Material.POTTED_POPPY) change.placementItem?.type shouldBe Material.FLOWER_POT
            }
            listOf(Material.SPAWNER, Material.COMMAND_BLOCK, Material.SHULKER_BOX, Material.HOPPER).forEach { material ->
                journey.planBuildBookBlock(player, target, material.createBlockData(), allowSystemFurniture = true) shouldBe
                    BuilderBookPlacementResult.SkippedUnsafe
            }
            target.type = Material.CHEST
            journey.planBuildBookBlock(player, target, Material.FURNACE.createBlockData(), allowSystemFurniture = true) shouldBe
                BuilderBookPlacementResult.SkippedUnsafe
        }
    }

    test("build-book plan replaces terrain and fluids, carves andesite, and gates system loot chests") {
        strictMockBukkit(open = { ArcBuilderJourney.open() }) { journey ->
            val player = journey.builder("BookGroundBuilder", GameMode.SURVIVAL)
            val world = journey.world
            player.teleport(Location(world, 6.5, 64.0, 6.5, 0f, 0f))
            world.getBlockAt(6, 64, 6).type = Material.DIRT
            world.getBlockAt(7, 64, 6).type = Material.DIRT
            world.getBlockAt(8, 64, 6).type = Material.CHEST
            world.getBlockAt(10, 64, 6).type = Material.WATER
            world.getBlockAt(11, 64, 6).type = Material.LAVA
            world.getBlockAt(12, 64, 6).type = Material.ANDESITE

            val replacement = journey.planBuildBookBlock(
                player,
                world.getBlockAt(6, 64, 6),
                Material.STONE.createBlockData(),
            ) as BuilderBookPlacementResult.Change
            val carving = journey.planBuildBookBlock(
                player,
                world.getBlockAt(7, 64, 6),
                Material.AIR.createBlockData(),
            ) as BuilderBookPlacementResult.Change
            val container = journey.planBuildBookBlock(
                player,
                world.getBlockAt(8, 64, 6),
                Material.AIR.createBlockData(),
            )
            val ore = journey.planBuildBookBlock(
                player,
                world.getBlockAt(9, 64, 6),
                Material.ANCIENT_DEBRIS.createBlockData(),
            )
            val water = journey.planBuildBookBlock(
                player,
                world.getBlockAt(10, 64, 6),
                Material.STONE.createBlockData(),
            ) as BuilderBookPlacementResult.Change
            val lava = journey.planBuildBookBlock(
                player,
                world.getBlockAt(11, 64, 6),
                Material.AIR.createBlockData(),
            ) as BuilderBookPlacementResult.Change
            val andesite = journey.planBuildBookBlock(
                player,
                world.getBlockAt(12, 64, 6),
                Material.AIR.createBlockData(),
            ) as BuilderBookPlacementResult.Change
            val ordinaryChest = journey.planBuildBookBlock(
                player,
                world.getBlockAt(13, 64, 6),
                Material.CHEST.createBlockData(),
            )
            val reviewedSystemChest = journey.planBuildBookBlock(
                player,
                world.getBlockAt(13, 64, 6),
                Material.CHEST.createBlockData(),
                allowSystemLootContainer = true,
            )

            replacement.block.beforeBlockData to replacement.block.afterBlockData shouldBe
                ("minecraft:dirt" to "minecraft:stone")
            carving.block.beforeBlockData to carving.block.afterBlockData shouldBe
                ("minecraft:dirt" to "minecraft:air")
            replacement.refund?.type shouldBe Material.DIRT
            carving.refund?.type shouldBe Material.DIRT
            container shouldBe BuilderBookPlacementResult.SkippedUnsafe
            ore shouldBe BuilderBookPlacementResult.SkippedUnsafe
            water.block.beforeBlockData shouldBe "minecraft:water[level=0]"
            water.refund shouldBe null
            lava.block.afterBlockData shouldBe "minecraft:air"
            lava.refund shouldBe null
            andesite.block.beforeBlockData to andesite.block.afterBlockData shouldBe
                ("minecraft:andesite" to "minecraft:air")
            andesite.refund?.type shouldBe Material.ANDESITE
            ordinaryChest shouldBe BuilderBookPlacementResult.SkippedUnsafe
            (reviewedSystemChest as BuilderBookPlacementResult.Change).block.afterBlockData shouldContain "minecraft:chest"
        }
    }

    test("fill rejects reward ore materials before creating a plan") {
        strictMockBukkit(open = { ArcBuilderJourney.open() }) { journey ->
            val player = journey.builder("OreFillBuilder", GameMode.CREATIVE)
            player.setLocale(Locale.forLanguageTag("ru-RU"))
            val world = journey.world
            player.teleport(Location(world, 0.5, 64.0, 3.5, 0f, 0f))
            player.inventory.setItemInMainHand(ItemStack(Material.ECHO_SHARD))
            player.performCommand("builder wand") shouldBe true
            journey.select(player, world, player.inventory.itemInMainHand, 0, 64, 0, 0, 64, 0)
            while (player.nextComponentMessage() != null) {
                // Isolate the command rejection from selection feedback.
            }

            player.performCommand("builder fill ancient_debris") shouldBe true

            val rejection = PlainTextComponentSerializer.plainText().serialize(
                checkNotNull(player.nextComponentMessage()),
            )
            rejection shouldContain "Этот материал нельзя использовать."
            rejection shouldNotContain "крон"
            journey.renderer.plans.containsKey(player.uniqueId) shouldBe false
            world.getBlockAt(0, 64, 0).type shouldBe Material.AIR
        }
    }

    test("system book journey keeps schematic air and removes obstructing andesite") {
        val definition = SystemBuildBookDefinition(
            buildingId = "carve-test.schem",
            title = "Carve test",
            schematicSha256 = "c".repeat(64),
            playerEnabled = true,
            materialsIncluded = true,
        )
        val schematicAir = mockk<BaseBlock>()
        every { schematicAir.blockType.id } returns "minecraft:air"
        mockkStatic(BukkitAdapter::class)
        try {
            strictMockBukkit(open = { ArcBuilderJourney.open(systemResolver = { definition }) }) { journey ->
                every { BukkitAdapter.adapt(schematicAir) } returns Bukkit.createBlockData(Material.AIR)
                val building = mockk<Building>()
                every { building.fileName } returns definition.buildingId
                every { building.volume } returns 1L
                every { building.blockCount } returns 1
                every { building.getCorner1(any()) } returns BlockVector3.ZERO
                every { building.getCorner2(any()) } returns BlockVector3.ZERO
                every { building.getBlock(any(), any()) } returns schematicAir
                BuildingManager.addBuilding(building)

                val player = journey.builder("AndesiteCarver", GameMode.CREATIVE)
                player.teleport(player.location.apply { yaw = -180f })
                val book = ru.arc.autobuild.BuildBookItems.create(
                    ru.arc.autobuild.BuildBookData(definition.buildingId, definition.buildingId),
                )
                player.inventory.setItemInMainHand(book)
                val anchor = journey.world.getBlockAt(5, 64, 5).also { it.type = Material.DIRT }
                journey.rightClickBook(player, Action.RIGHT_CLICK_BLOCK, anchor)
                val site = checkNotNull(BuildingManager.pending(player.uniqueId))
                val target = site.worldLocation(BlockVector3.ZERO).block.also { it.type = Material.ANDESITE }

                journey.rightClickBook(player, Action.RIGHT_CLICK_AIR, null)

                checkNotNull(journey.renderer.plans[player.uniqueId]).changes.single().let { change ->
                    change.position shouldBe BuilderBlockPos(target.world.uid, target.x, target.y, target.z)
                    change.beforeBlockData shouldBe "minecraft:andesite"
                    change.afterBlockData shouldBe "minecraft:air"
                }
            }
        } finally {
            unmockkStatic(BukkitAdapter::class)
        }
    }

    test("system book places both door halves in the same construction tick") {
        val definition = SystemBuildBookDefinition(
            buildingId = "atomic-door-test.schem",
            title = "Atomic door test",
            schematicSha256 = "e".repeat(64),
            playerEnabled = true,
            materialsIncluded = true,
        )
        val bottomBlock = mockk<BaseBlock>()
        every { bottomBlock.blockType.id } returns "minecraft:oak_door"
        val topBlock = mockk<BaseBlock>()
        every { topBlock.blockType.id } returns "minecraft:oak_door"
        mockkStatic(BukkitAdapter::class)
        try {
            strictMockBukkit(open = { ArcBuilderJourney.open(systemResolver = { definition }) }) { journey ->
                val bottom = Material.OAK_DOOR.createBlockData() as Door
                bottom.half = Bisected.Half.BOTTOM
                val top = bottom.clone() as Door
                top.half = Bisected.Half.TOP
                every { BukkitAdapter.adapt(bottomBlock) } returns bottom
                every { BukkitAdapter.adapt(topBlock) } returns top
                val building = mockk<Building>()
                every { building.fileName } returns definition.buildingId
                every { building.volume } returns 2L
                every { building.blockCount } returns 2
                every { building.getCorner1(any()) } returns BlockVector3.ZERO
                every { building.getCorner2(any()) } returns BlockVector3.at(0, 1, 0)
                every { building.getBlock(any(), any()) } answers {
                    if (firstArg<BlockVector3>().y() == 0) bottomBlock else topBlock
                }
                BuildingManager.addBuilding(building)

                val player = journey.builder("DoorBookBuilder", GameMode.SURVIVAL)
                player.teleport(player.location.apply { yaw = -180f })
                player.inventory.setItemInMainHand(
                    BuildBookItems.create(BuildBookData(definition.buildingId, definition.buildingId)),
                )
                val anchor = journey.world.getBlockAt(5, 64, 5)
                journey.rightClickBook(player, Action.RIGHT_CLICK_BLOCK, anchor)
                val site = checkNotNull(BuildingManager.pending(player.uniqueId))
                val bottomTarget = site.worldLocation(BlockVector3.ZERO).block
                val topTarget = site.worldLocation(BlockVector3.at(0, 1, 0)).block
                journey.rightClickBook(player, Action.RIGHT_CLICK_AIR, null)
                val pendingPlan = journey.renderer.plans[player.uniqueId] ?: error(
                    generateSequence(player::nextComponentMessage)
                        .joinToString(" | ") { PlainTextComponentSerializer.plainText().serialize(it) },
                )
                pendingPlan.changes.size shouldBe 2
                player.performCommand("builder confirm") shouldBe true
                journey.await("first atomic door mutation") {
                    bottomTarget.type == Material.OAK_DOOR || topTarget.type == Material.OAK_DOOR
                }

                bottomTarget.type shouldBe Material.OAK_DOOR
                topTarget.type shouldBe Material.OAK_DOOR
                (bottomTarget.blockData as Door).half shouldBe Bisected.Half.BOTTOM
                (topTarget.blockData as Door).half shouldBe Bisected.Half.TOP
                journey.awaitSettled(player) {
                    bottomTarget.type == Material.OAK_DOOR && topTarget.type == Material.OAK_DOOR
                }
            }
        } finally {
            unmockkStatic(BukkitAdapter::class)
        }
    }

    test("admin instant menu completes a waiting book with an empty material inventory") {
        val definition = SystemBuildBookDefinition(
            buildingId = "instant-door-test.schem",
            title = "Atomic door test",
            schematicSha256 = "e".repeat(64),
            playerEnabled = true,
            materialsIncluded = false,
        )
        val bottomBlock = mockk<BaseBlock>()
        every { bottomBlock.blockType.id } returns "minecraft:oak_door"
        val topBlock = mockk<BaseBlock>()
        every { topBlock.blockType.id } returns "minecraft:oak_door"
        mockkStatic(BukkitAdapter::class)
        try {
            strictMockBukkit(open = { ArcBuilderJourney.open(systemResolver = { definition }) }) { journey ->
                val bottom = Material.OAK_DOOR.createBlockData() as Door
                bottom.half = Bisected.Half.BOTTOM
                val top = bottom.clone() as Door
                top.half = Bisected.Half.TOP
                every { BukkitAdapter.adapt(bottomBlock) } returns bottom
                every { BukkitAdapter.adapt(topBlock) } returns top
                val building = mockk<Building>()
                every { building.fileName } returns definition.buildingId
                every { building.volume } returns 2L
                every { building.blockCount } returns 2
                every { building.getCorner1(any()) } returns BlockVector3.ZERO
                every { building.getCorner2(any()) } returns BlockVector3.at(0, 1, 0)
                every { building.getBlock(any(), any()) } answers {
                    if (firstArg<BlockVector3>().y() == 0) bottomBlock else topBlock
                }
                BuildingManager.addBuilding(building)

                val player = journey.builder("InstantBuilder", GameMode.SURVIVAL)
                player.teleport(player.location.apply { yaw = -180f })
                player.inventory.setItemInMainHand(
                    BuildBookItems.create(BuildBookData(definition.buildingId, definition.buildingId)),
                )
                val anchor = journey.world.getBlockAt(5, 64, 5)
                journey.rightClickBook(player, Action.RIGHT_CLICK_BLOCK, anchor)
                val site = checkNotNull(BuildingManager.pending(player.uniqueId))
                val bottomTarget = site.worldLocation(BlockVector3.ZERO).block
                val topTarget = site.worldLocation(BlockVector3.at(0, 1, 0)).block
                journey.rightClickBook(player, Action.RIGHT_CLICK_AIR, null)
                val pendingPlan = journey.renderer.plans[player.uniqueId] ?: error(
                    generateSequence(player::nextComponentMessage)
                        .joinToString(" | ") { PlainTextComponentSerializer.plainText().serialize(it) },
                )
                pendingPlan.changes.size shouldBe 2
                player.performCommand("builder confirm") shouldBe true
                val store = BuilderConstructionProjectStore(journey.plugin.dataPath, 10000)
                journey.await("waiting for missing materials") {
                    store.loadOrNull(pendingPlan.id)?.state == BuilderConstructionProjectState.WAITING_MATERIALS
                }
                bottomTarget.type shouldBe Material.AIR
                player.addAttachment(journey.plugin, "arcbuild.admin.construction", true)
                player.recalculatePermissions()
                player.performCommand("builder projects") shouldBe true
                fun click(slot: Int, type: org.bukkit.event.inventory.ClickType) = journey.paper.callEvent(
                    org.bukkit.event.inventory.InventoryClickEvent(player.openInventory,
                        org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER, slot, type,
                        org.bukkit.event.inventory.InventoryAction.PICKUP_ALL))
                click(10, org.bukkit.event.inventory.ClickType.RIGHT)
                journey.paper.performTicks(2)
                player.openInventory.topInventory.getItem(22)?.type shouldBe Material.NETHER_STAR
                click(22, org.bukkit.event.inventory.ClickType.LEFT)
                journey.await("admin completion through the real menu callback") {
                    store.loadOrNull(pendingPlan.id)?.state == BuilderConstructionProjectState.COMPLETED
                }
                player.inventory.contains(Material.OAK_DOOR) shouldBe false

                bottomTarget.type shouldBe Material.OAK_DOOR
                topTarget.type shouldBe Material.OAK_DOOR
                (bottomTarget.blockData as Door).half shouldBe Bisected.Half.BOTTOM
                (topTarget.blockData as Door).half shouldBe Bisected.Half.TOP
                journey.awaitSettled(player) {
                    bottomTarget.type == Material.OAK_DOOR && topTarget.type == Material.OAK_DOOR
                }
            }
        } finally {
            unmockkStatic(BukkitAdapter::class)
        }
    }

    test("reviewed system book places a chest with the configured vanilla loot table") {
        val definition = SystemBuildBookDefinition(
            buildingId = "loot-chest-test.schem",
            title = "Loot chest test",
            schematicSha256 = "d".repeat(64),
            playerEnabled = true,
            materialsIncluded = true,
            containerLootTableKey = "minecraft:chests/spawn_bonus_chest",
        )
        val schematicChest = mockk<BaseBlock>()
        every { schematicChest.blockType.id } returns "minecraft:chest"
        val lootTable = mockk<LootTable>()
        every { lootTable.key } returns NamespacedKey.minecraft("chests/spawn_bonus_chest")
        var appliedLootKey: NamespacedKey? = null
        val lootTableAccess = object : BuilderLootTableAccess {
            override fun matches(block: org.bukkit.block.Block, table: LootTable): Boolean = appliedLootKey == table.key

            override fun apply(block: org.bukkit.block.Block, table: LootTable) {
                appliedLootKey = table.key
            }
        }
        mockkStatic(BukkitAdapter::class)
        try {
            strictMockBukkit(
                open = {
                    ArcBuilderJourney.open(
                        systemResolver = { definition },
                        lootTableResolver = { lootTable },
                        lootTableAccess = lootTableAccess,
                    )
                },
            ) { journey ->
                every { BukkitAdapter.adapt(schematicChest) } returns Bukkit.createBlockData(Material.CHEST)
                val building = mockk<Building>()
                every { building.fileName } returns definition.buildingId
                every { building.volume } returns 1L
                every { building.blockCount } returns 1
                every { building.getCorner1(any()) } returns BlockVector3.ZERO
                every { building.getCorner2(any()) } returns BlockVector3.ZERO
                every { building.getBlock(any(), any()) } returns schematicChest
                BuildingManager.addBuilding(building)

                val player = journey.builder("StarterLootChest", GameMode.CREATIVE)
                player.teleport(player.location.apply { yaw = -180f })
                player.inventory.setItemInMainHand(
                    ru.arc.autobuild.BuildBookItems.create(
                        ru.arc.autobuild.BuildBookData(definition.buildingId, definition.buildingId),
                    ),
                )
                val anchor = journey.world.getBlockAt(7, 64, 7).also { it.type = Material.DIRT }
                journey.rightClickBook(player, Action.RIGHT_CLICK_BLOCK, anchor)
                val target = checkNotNull(BuildingManager.pending(player.uniqueId))
                    .worldLocation(BlockVector3.ZERO)
                    .block
                    .also { it.type = Material.AIR }
                journey.rightClickBook(player, Action.RIGHT_CLICK_AIR, null)

                player.performCommand("builder confirm") shouldBe true
                journey.await("loot-table assignment") { appliedLootKey != null }
                journey.awaitSettled(player) { target.type == Material.CHEST }

                appliedLootKey shouldBe NamespacedKey.minecraft("chests/spawn_bonus_chest")
            }
        } finally {
            unmockkStatic(BukkitAdapter::class)
        }
    }

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
            player.inventory.setItemInMainHand(ItemStack(Material.AIR))
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

    test("an all-unsafe copy keeps the previous clipboard and never leaves the player locked") {
        strictMockBukkit(open = { ArcBuilderJourney.open() }) { journey ->
            val player = journey.builder("UnsafeBuilder", GameMode.CREATIVE)
            val world = journey.world
            player.teleport(Location(world, 0.5, 64.0, 3.5, 0f, 0f))
            world.getBlockAt(0, 64, 0).type = Material.STONE
            player.inventory.setItemInMainHand(ItemStack(Material.ECHO_SHARD))
            player.performCommand("builder wand") shouldBe true
            val wand = player.inventory.itemInMainHand
            journey.select(player, world, wand, 0, 64, 0, 0, 64, 0)
            player.performCommand("builder copy") shouldBe true

            world.getBlockAt(2, 64, 0).type = Material.BEDROCK
            journey.select(player, world, wand, 2, 64, 0, 2, 64, 0)
            player.performCommand("builder copy") shouldBe true

            journey.activeLeases() shouldBe 0
            PlayerCommandPreprocessEvent(player, "/builder status").also(journey.paper::callEvent).isCancelled shouldBe false
            BlockBreakEvent(world.getBlockAt(4, 64, 0), player).also(journey.paper::callEvent).isCancelled shouldBe false

            player.teleport(Location(world, 10.5, 64.0, 3.5, 0f, 0f))
            player.performCommand("builder paste") shouldBe true
            checkNotNull(journey.renderer.plans[player.uniqueId]).changes.size shouldBe 1
            player.performCommand("builder confirm") shouldBe true
            journey.awaitSettled(player) { world.getBlockAt(10, 64, 0).type == Material.STONE }
        }
    }

    test("survival paste replaces safe blocks skips containers and retains its clipboard") {
        strictMockBukkit(open = { ArcBuilderJourney.open() }) { journey ->
            val player = journey.builder("ReplaceBuilder", GameMode.SURVIVAL)
            val world = journey.world
            player.teleport(Location(world, 0.5, 64.0, 3.5, 0f, 0f))
            world.getBlockAt(0, 64, 0).type = Material.STONE
            world.getBlockAt(1, 64, 0).type = Material.OAK_PLANKS
            player.inventory.setItemInMainHand(ItemStack(Material.ECHO_SHARD))
            player.performCommand("builder wand") shouldBe true
            journey.select(player, world, player.inventory.itemInMainHand, 0, 64, 0, 1, 64, 0)
            player.performCommand("builder copy") shouldBe true

            world.getBlockAt(10, 64, 0).type = Material.DEEPSLATE
            world.getBlockAt(11, 64, 0).type = Material.CHEST
            player.inventory.addItem(ItemStack(Material.STONE))
            player.teleport(Location(world, 10.5, 64.0, 3.5, 0f, 0f))
            player.performCommand("builder paste") shouldBe true
            checkNotNull(journey.renderer.plans[player.uniqueId]).changes.size shouldBe 1
            player.performCommand("builder confirm") shouldBe true
            journey.awaitSettled(player) { world.getBlockAt(10, 64, 0).type == Material.STONE }
            world.getBlockAt(11, 64, 0).type shouldBe Material.CHEST

            player.inventory.addItem(ItemStack(Material.STONE), ItemStack(Material.OAK_PLANKS))
            player.teleport(Location(world, 14.5, 64.0, 3.5, 0f, 0f))
            player.performCommand("builder paste") shouldBe true
            checkNotNull(journey.renderer.plans[player.uniqueId]).changes.size shouldBe 2
            player.performCommand("builder confirm") shouldBe true
            journey.awaitSettled(player) {
                world.getBlockAt(14, 64, 0).type == Material.STONE &&
                    world.getBlockAt(15, 64, 0).type == Material.OAK_PLANKS
            }
        }
    }

    test("survival clipboard copies and rebuilds both halves of a door for one door item") {
        strictMockBukkit(open = { ArcBuilderJourney.open() }) { journey ->
            val player = journey.builder("DoorBuilder", GameMode.SURVIVAL)
            val world = journey.world
            player.teleport(Location(world, 0.5, 64.0, 3.5, 0f, 0f))
            val bottom = Material.OAK_DOOR.createBlockData() as Door
            bottom.half = Bisected.Half.BOTTOM
            val top = bottom.clone() as Door
            top.half = Bisected.Half.TOP
            world.getBlockAt(0, 64, 0).setBlockData(bottom, false)
            world.getBlockAt(0, 65, 0).setBlockData(top, false)
            player.inventory.setItemInMainHand(ItemStack(Material.ECHO_SHARD))
            player.performCommand("builder wand") shouldBe true
            journey.select(player, world, player.inventory.itemInMainHand, 0, 64, 0, 0, 65, 0)
            player.performCommand("builder copy") shouldBe true
            player.inventory.addItem(ItemStack(Material.OAK_DOOR))

            player.teleport(Location(world, 10.5, 64.0, 3.5, 0f, 0f))
            player.performCommand("builder paste") shouldBe true
            checkNotNull(journey.renderer.plans[player.uniqueId]).changes.size shouldBe 2
            player.performCommand("builder confirm") shouldBe true
            journey.awaitSettled(player) {
                world.getBlockAt(10, 64, 0).type == Material.OAK_DOOR &&
                    world.getBlockAt(10, 65, 0).type == Material.OAK_DOOR
            }
            (world.getBlockAt(10, 64, 0).blockData as Door).half shouldBe Bisected.Half.BOTTOM
            (world.getBlockAt(10, 65, 0).blockData as Door).half shouldBe Bisected.Half.TOP
            journey.amount(player, Material.OAK_DOOR) shouldBe 0
        }
    }

    test("changing game mode discards only the stale preview and releases every operation lock") {
        strictMockBukkit(open = { ArcBuilderJourney.open() }) { journey ->
            val player = journey.builder("ModeBuilder", GameMode.CREATIVE)
            val world = journey.world
            player.teleport(Location(world, 0.5, 64.0, 3.5, 0f, 0f))
            world.getBlockAt(0, 64, 0).type = Material.STONE
            player.inventory.setItemInMainHand(ItemStack(Material.ECHO_SHARD))
            player.performCommand("builder wand") shouldBe true
            journey.select(player, world, player.inventory.itemInMainHand, 0, 64, 0, 0, 64, 0)
            player.performCommand("builder copy") shouldBe true
            player.teleport(Location(world, 10.5, 64.0, 3.5, 0f, 0f))
            player.performCommand("builder paste") shouldBe true

            player.gameMode = GameMode.SURVIVAL
            player.performCommand("builder confirm") shouldBe true
            journey.renderer.plans.containsKey(player.uniqueId) shouldBe false
            world.getBlockAt(10, 64, 0).type shouldBe Material.AIR
            journey.activeLeases() shouldBe 0
            BlockBreakEvent(world.getBlockAt(4, 64, 0), player).also(journey.paper::callEvent).isCancelled shouldBe false

            player.gameMode = GameMode.CREATIVE
            player.performCommand("builder paste") shouldBe true
            player.performCommand("builder confirm") shouldBe true
            journey.awaitSettled(player) { world.getBlockAt(10, 64, 0).type == Material.STONE }
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

    test("completion preserves feature permissions argument positions and case insensitive prefixes") {
        strictMockBukkit(open = { ArcBuilderJourney.open() }) { journey ->
            val queries = listOf(
                "builder Fi" to "fill",
                "builder ReP" to "replace",
                "builder replace dirt stone C" to "confirm",
                "builder disconnect C" to "confirm",
                "builder paste R" to "rotate",
                "builder book G" to "guide",
                "builder crown shape R" to "round",
            )
            val permissions = listOf(
                "arcbuild.fill", "arcbuild.replace", "arcbuild.disconnect", "arcbuild.paste",
                "arcbuild.book.use", "arcbuild.crown", "arcbuild.use",
            )
            permissions.forEachIndexed { index, permission ->
                val player = journey.paper.addPlayer("Completion$index")
                player.addAttachment(journey.plugin, permission, true)
                player.recalculatePermissions()
                queries.forEach { (query, expected) ->
                    val command = when (query) {
                        "builder Fi" -> "fill"
                        "builder ReP" -> "replace"
                        else -> query.split(' ')[1].lowercase()
                    }
                    val allowed = permission == "arcbuild.use" || permission == "arcbuild.$command" ||
                        command == "book" && permission == "arcbuild.book.use"
                    val matches = journey.paper.server.getCommandTabComplete(player, query)
                    if (allowed) (expected in matches) shouldBe true else matches shouldBe emptyList()
                }
                journey.paper.server.getCommandTabComplete(player, "builder confirm B") shouldBe listOf("buy")
                listOf("builder unknown x", "builder fill stone extra", "builder replace dirt stone confirm extra").forEach {
                    journey.paper.server.getCommandTabComplete(player, it) shouldBe emptyList()
                }
            }
            val denied = journey.paper.addPlayer("CompletionDenied")
            journey.paper.server.getCommandTabComplete(denied, "builder fill stone") shouldBe emptyList()
            journey.paper.server.getCommandTabComplete(denied, "builder confirm b") shouldBe emptyList()
        }
    }

    test("runtime keeps feature permissions and plan contracts isolated") {
        strictMockBukkit(open = { ArcBuilderJourney.open() }) { journey ->
            fun Player.grant(permission: String) {
                addAttachment(journey.plugin, permission, true)
                recalculatePermissions()
            }

            val fill = journey.paper.addPlayer("RuntimeFillOnly").also {
                it.gameMode = GameMode.SURVIVAL
                it.teleport(Location(journey.world, 0.5, 64.0, 3.5))
                it.grant("arcbuild.fill")
                it.inventory.setItemInMainHand(ItemStack(Material.ECHO_SHARD))
            }
            journey.world.getBlockAt(0, 64, 0).type = Material.DIRT
            fill.performCommand("builder wand") shouldBe true
            journey.select(fill, journey.world, fill.inventory.itemInMainHand, 0, 64, 0, 0, 64, 0)
            fill.performCommand("builder replace dirt stone") shouldBe true
            journey.renderer.plans[fill.uniqueId] shouldBe null
            journey.world.getBlockAt(0, 64, 0).type = Material.AIR
            fill.inventory.addItem(ItemStack(Material.STONE))
            fill.performCommand("builder fill stone") shouldBe true
            val fillPlan = checkNotNull(journey.renderer.plans[fill.uniqueId])
            fillPlan.kind shouldBe BuilderPlanKind.FILL
            fillPlan.costs.map { it.materialKey to it.amount } shouldBe listOf("minecraft:stone" to 1)
            fill.performCommand("builder cancel") shouldBe true

            val replace = journey.paper.addPlayer("RuntimeReplaceOnly").also {
                it.gameMode = GameMode.SURVIVAL
                it.teleport(Location(journey.world, 1.5, 64.0, 3.5))
                it.grant("arcbuild.replace")
                it.inventory.setItemInMainHand(ItemStack(Material.ECHO_SHARD))
            }
            journey.world.getBlockAt(1, 64, 0).type = Material.AIR
            replace.performCommand("builder wand") shouldBe true
            journey.select(replace, journey.world, replace.inventory.itemInMainHand, 1, 64, 0, 1, 64, 0)
            replace.performCommand("builder fill deepslate") shouldBe true
            journey.renderer.plans[replace.uniqueId] shouldBe null
            journey.world.getBlockAt(1, 64, 0).type = Material.STONE
            replace.inventory.addItem(ItemStack(Material.DEEPSLATE))
            replace.performCommand("builder replace stone deepslate") shouldBe true
            val replacePlan = checkNotNull(journey.renderer.plans[replace.uniqueId])
            replacePlan.kind shouldBe BuilderPlanKind.REPLACE
            replacePlan.costs.map { it.materialKey to it.amount } shouldBe listOf("minecraft:deepslate" to 1)
            replacePlan.rewards.map { it.materialKey to it.amount } shouldBe listOf("minecraft:stone" to 1)
            replace.performCommand("builder cancel") shouldBe true

            val disconnect = journey.paper.addPlayer("RuntimeDisconnectOnly").also {
                it.gameMode = GameMode.CREATIVE
                it.teleport(Location(journey.world, 2.5, 64.0, 3.5))
                it.grant("arcbuild.disconnect")
                it.inventory.setItemInMainHand(ItemStack(Material.ECHO_SHARD))
            }
            val fence = Material.OAK_FENCE.createBlockData() as MultipleFacing
            fence.setFace(BlockFace.NORTH, true)
            journey.world.getBlockAt(2, 64, 0).setBlockData(fence, false)
            disconnect.performCommand("builder wand") shouldBe true
            journey.select(disconnect, journey.world, disconnect.inventory.itemInMainHand, 2, 64, 0, 2, 64, 0)
            disconnect.performCommand("builder replace oak_fence stone") shouldBe true
            journey.renderer.plans[disconnect.uniqueId] shouldBe null
            disconnect.performCommand("builder disconnect") shouldBe true
            val disconnectPlan = checkNotNull(journey.renderer.plans[disconnect.uniqueId])
            disconnectPlan.kind shouldBe BuilderPlanKind.FENCE_DISCONNECT
            disconnectPlan.costs shouldBe emptyList()
            disconnectPlan.rewards shouldBe emptyList()
        }
    }

    test("replace previews an exact survival exchange preserves state and remains undoable") {
        strictMockBukkit(open = { ArcBuilderJourney.open() }) { journey ->
            val player = journey.builder("StateReplacer", GameMode.SURVIVAL)
            val world = journey.world
            player.teleport(Location(world, 0.5, 64.0, 3.5, 0f, 0f))

            val oak = Material.OAK_STAIRS.createBlockData() as Stairs
            oak.facing = BlockFace.WEST
            oak.half = Bisected.Half.TOP
            oak.shape = Stairs.Shape.OUTER_RIGHT
            world.getBlockAt(0, 64, 0).setBlockData(oak, false)
            world.getBlockAt(1, 64, 0).type = Material.OAK_PLANKS

            player.inventory.setItemInMainHand(ItemStack(Material.ECHO_SHARD))
            player.performCommand("builder wand") shouldBe true
            journey.select(player, world, player.inventory.itemInMainHand, 0, 64, 0, 1, 64, 0)
            player.inventory.addItem(ItemStack(Material.SPRUCE_STAIRS))

            player.performCommand("builder replace oak_stairs spruce_stairs") shouldBe true

            val plan = checkNotNull(journey.renderer.plans[player.uniqueId])
            plan.kind shouldBe BuilderPlanKind.REPLACE
            plan.changes.size shouldBe 1
            world.getBlockAt(0, 64, 0).blockData.asString shouldBe oak.asString
            journey.amount(player, Material.SPRUCE_STAIRS) shouldBe 1

            player.performCommand("builder confirm") shouldBe true
            journey.awaitSettled(player) { world.getBlockAt(0, 64, 0).type == Material.SPRUCE_STAIRS }
            val spruce = world.getBlockAt(0, 64, 0).blockData as Stairs
            spruce.facing shouldBe BlockFace.WEST
            spruce.half shouldBe Bisected.Half.TOP
            spruce.shape shouldBe Stairs.Shape.OUTER_RIGHT
            world.getBlockAt(1, 64, 0).type shouldBe Material.OAK_PLANKS
            journey.amount(player, Material.SPRUCE_STAIRS) shouldBe 0
            journey.amount(player, Material.OAK_STAIRS) shouldBe 1

            player.performCommand("builder undo") shouldBe true
            player.performCommand("builder confirm") shouldBe true
            journey.awaitSettled(player) { world.getBlockAt(0, 64, 0).type == Material.OAK_STAIRS }
            world.getBlockAt(0, 64, 0).blockData.asString shouldBe oak.asString
            journey.amount(player, Material.OAK_STAIRS) shouldBe 0
            journey.amount(player, Material.SPRUCE_STAIRS) shouldBe 1
        }
    }

    test("physics visits each changed block only after full placement and durable commit") {
        lateinit var journey: ArcBuilderJourney
        val ticks = mutableListOf<Long>()
        val updater = object : BuilderPhysicsUpdater {
            override fun validate() = Unit
            override fun update(block: org.bukkit.block.Block, before: BlockData) {
                journey.countLine(journey.world, Material.STONE) shouldBe 5
                val records = BuilderJournalStore(journey.plugin.dataPath, 64).loadAll()
                records.single().value.phase shouldBe BuilderJournalPhase.COMMITTED
                val event = org.bukkit.event.block.BlockPhysicsEvent(block, block.blockData)
                journey.paper.callEvent(event)
                event.isCancelled shouldBe false
                ticks += journey.paper.server.scheduler.currentTick
            }
        }
        strictMockBukkit(open = { ArcBuilderJourney.open(blocksPerTick = 2, physicsUpdater = updater) }) { opened ->
            journey = opened
            val player = journey.builder("PhysicsBuilder", GameMode.SURVIVAL)
            player.teleport(Location(journey.world, 0.5, 64.0, 3.5))
            player.inventory.setItemInMainHand(ItemStack(Material.ECHO_SHARD))
            player.performCommand("builder wand") shouldBe true
            journey.select(player, journey.world, player.inventory.itemInMainHand, 0, 64, 0, 4, 64, 0)
            player.inventory.addItem(ItemStack(Material.STONE, 5))
            player.performCommand("builder fill stone") shouldBe true
            ticks shouldBe emptyList()
            player.performCommand("builder confirm") shouldBe true
            journey.awaitSettled(player) { ticks.size == 5 }
            ticks.groupingBy { it }.eachCount().values.all { it <= 2 } shouldBe true
            journey.amount(player, Material.STONE) shouldBe 0
        }
    }

    test("Russian block aliases complete and replace through confirmation and undo") {
        strictMockBukkit(open = { ArcBuilderJourney.open() }) { journey ->
            val player = journey.builder("RussianReplacer", GameMode.SURVIVAL)
            val world = journey.world
            player.teleport(Location(world, 0.5, 64.0, 3.5, 0f, 0f))
            world.getBlockAt(0, 64, 0).type = Material.STONE
            player.inventory.setItemInMainHand(ItemStack(Material.ECHO_SHARD))
            player.performCommand("builder wand") shouldBe true
            journey.select(player, world, player.inventory.itemInMainHand, 0, 64, 0, 0, 64, 0)
            player.inventory.addItem(ItemStack(Material.DIAMOND_BLOCK))
            journey.paper.server.getCommandTabComplete(player, "builder replace кам").contains("камень") shouldBe true
            journey.paper.server.getCommandTabComplete(player, "builder replace stone АЛМ").contains("алмазныйБлок") shouldBe true
            journey.paper.server.getCommandTabComplete(player, "builder fill алм").contains("алмазныйБлок") shouldBe true
            journey.paper.server.getCommandTabComplete(player, "builder replace stone diamond_b").contains("diamond_block") shouldBe true
            journey.paper.server.getCommandTabComplete(player, "builder replace stone oak_door") shouldBe emptyList()
            for (buffer in listOf("/builder replace ", "/builder replace stone ", "/arcbuilder:builder fill ")) {
                val suggestions = SuggestionsBuilder(buffer, buffer.length)
                    .suggest("stone").suggest("камень").suggest("diamond_block").suggest("алмазныйБлок").build()
                val event = AsyncPlayerSendSuggestionsEvent(player, suggestions, buffer)
                journey.paper.server.pluginManager.callEvent(event)
                event.suggestions.list.map { it.text } shouldBe
                    listOf("алмазныйБлок", "камень", "diamond_block", "stone")
                event.suggestions.range shouldBe suggestions.range
            }
            player.performCommand("builder replace КАМЕНЬ АлмазныйБлок") shouldBe true
            checkNotNull(journey.renderer.plans[player.uniqueId]).kind shouldBe BuilderPlanKind.REPLACE
            world.getBlockAt(0, 64, 0).type shouldBe Material.STONE
            player.performCommand("builder confirm") shouldBe true
            journey.awaitSettled(player) { world.getBlockAt(0, 64, 0).type == Material.DIAMOND_BLOCK }
            journey.amount(player, Material.DIAMOND_BLOCK) shouldBe 0
            journey.amount(player, Material.STONE) shouldBe 1
            player.performCommand("builder undo") shouldBe true
            player.performCommand("builder confirm") shouldBe true
            journey.awaitSettled(player) { world.getBlockAt(0, 64, 0).type == Material.STONE }
            journey.amount(player, Material.DIAMOND_BLOCK) shouldBe 1
            journey.amount(player, Material.STONE) shouldBe 0
        }
    }

    test("air replacement consumes only target items and undo restores each original air variant") {
        strictMockBukkit(open = { ArcBuilderJourney.open() }) { journey ->
            val player = journey.builder("AirReplacer", GameMode.SURVIVAL)
            val world = journey.world
            player.teleport(Location(world, 0.5, 64.0, 3.5))
            val originals = listOf(Material.AIR, Material.CAVE_AIR, Material.VOID_AIR)
            originals.forEachIndexed { x, material -> world.getBlockAt(x, 64, 0).type = material }
            world.getBlockAt(3, 64, 0).type = Material.DIRT
            player.inventory.setItemInMainHand(ItemStack(Material.ECHO_SHARD))
            player.performCommand("builder wand")
            journey.select(player, world, player.inventory.itemInMainHand, 0, 64, 0, 3, 64, 0)
            journey.paper.server.getCommandTabComplete(player, "builder replace воз").contains("воздух") shouldBe true
            journey.paper.server.getCommandTabComplete(player, "builder replace a").contains("air") shouldBe true
            journey.paper.server.getCommandTabComplete(player, "builder replace stone air") shouldBe emptyList()
            player.performCommand("builder replace воздух камень")
            val plan = checkNotNull(journey.renderer.plans[player.uniqueId])
            plan.changes.size shouldBe 3
            plan.costs.map { it.materialKey to it.amount } shouldBe listOf("minecraft:stone" to 3)
            plan.rewards shouldBe emptyList()
            player.performCommand("builder confirm")
            originals.forEachIndexed { x, material -> world.getBlockAt(x, 64, 0).type shouldBe material }
            player.inventory.addItem(ItemStack(Material.STONE, 3))
            player.performCommand("builder confirm")
            journey.awaitSettled(player) { (0..2).all { world.getBlockAt(it, 64, 0).type == Material.STONE } }
            journey.amount(player, Material.STONE) shouldBe 0
            world.getBlockAt(3, 64, 0).type shouldBe Material.DIRT
            player.performCommand("builder undo")
            player.performCommand("builder confirm")
            journey.awaitSettled(player) { world.getBlockAt(0, 64, 0).type == Material.AIR }
            originals.forEachIndexed { x, material -> world.getBlockAt(x, 64, 0).type shouldBe material }
            journey.amount(player, Material.STONE) shouldBe 3
        }
    }

    test("replace confirm applies immediately only when confirm is the final argument") {
        strictMockBukkit(open = { ArcBuilderJourney.open() }) { journey ->
            val player = journey.builder("DirectReplace", GameMode.SURVIVAL)
            val world = journey.world
            player.teleport(Location(world, 0.5, 64.0, 3.5, 0f, 0f))
            world.getBlockAt(0, 64, 0).type = Material.STONE

            player.inventory.setItemInMainHand(ItemStack(Material.ECHO_SHARD))
            player.performCommand("builder wand") shouldBe true
            journey.select(player, world, player.inventory.itemInMainHand, 0, 64, 0, 0, 64, 0)
            player.performCommand("builder replace stone deepslate nope") shouldBe true
            journey.renderer.plans[player.uniqueId] shouldBe null
            world.getBlockAt(0, 64, 0).type shouldBe Material.STONE
            player.inventory.addItem(ItemStack(Material.DEEPSLATE))
            journey.paper.server.getCommandTabComplete(
                player,
                "builder replace stone deepslate c",
            ) shouldBe listOf("confirm")

            player.performCommand("builder replace stone deepslate confirm") shouldBe true

            journey.renderer.plans[player.uniqueId] shouldBe null
            journey.awaitSettled(player) { world.getBlockAt(0, 64, 0).type == Material.DEEPSLATE }
            journey.amount(player, Material.DEEPSLATE) shouldBe 0
            journey.amount(player, Material.STONE) shouldBe 1
            player.performCommand("builder undo") shouldBe true
            player.performCommand("builder confirm") shouldBe true
            journey.awaitSettled(player) { world.getBlockAt(0, 64, 0).type == Material.STONE }
            journey.amount(player, Material.DEEPSLATE) shouldBe 1
            journey.amount(player, Material.STONE) shouldBe 0
        }
    }

    test("disconnect previews only fence state changes and the confirmed operation remains undoable") {
        strictMockBukkit(open = { ArcBuilderJourney.open() }) { journey ->
            val player = journey.builder("FenceBuilder", GameMode.CREATIVE)
            val world = journey.world
            player.teleport(Location(world, 0.5, 64.0, 3.5, 0f, 0f))

            val oak = Material.OAK_FENCE.createBlockData() as MultipleFacing
            oak.setFace(BlockFace.NORTH, true)
            oak.setFace(BlockFace.EAST, true)
            val nether = Material.NETHER_BRICK_FENCE.createBlockData() as MultipleFacing
            nether.setFace(BlockFace.SOUTH, true)
            world.getBlockAt(0, 64, 0).setBlockData(oak, false)
            world.getBlockAt(1, 64, 0).setBlockData(nether, false)
            world.getBlockAt(2, 64, 0).type = Material.STONE

            player.inventory.setItemInMainHand(ItemStack(Material.ECHO_SHARD))
            player.performCommand("builder wand") shouldBe true
            journey.select(player, world, player.inventory.itemInMainHand, 0, 64, 0, 2, 64, 0)

            player.performCommand("builder disconnect") shouldBe true

            val plan = checkNotNull(journey.renderer.plans[player.uniqueId])
            plan.kind.name shouldBe "FENCE_DISCONNECT"
            plan.changes.map { it.position.x } shouldBe listOf(0, 1)
            plan.changes.forEach { change ->
                (Bukkit.createBlockData(change.afterBlockData) as MultipleFacing).faces shouldBe emptySet<BlockFace>()
            }
            (world.getBlockAt(0, 64, 0).blockData as MultipleFacing).faces shouldBe setOf(BlockFace.NORTH, BlockFace.EAST)
            (world.getBlockAt(1, 64, 0).blockData as MultipleFacing).faces shouldBe setOf(BlockFace.SOUTH)
            world.getBlockAt(2, 64, 0).type shouldBe Material.STONE

            player.performCommand("builder confirm") shouldBe true
            journey.awaitSettled(player) {
                (world.getBlockAt(0, 64, 0).blockData as MultipleFacing).faces.isEmpty() &&
                    (world.getBlockAt(1, 64, 0).blockData as MultipleFacing).faces.isEmpty()
            }
            world.getBlockAt(2, 64, 0).type shouldBe Material.STONE

            player.performCommand("builder undo") shouldBe true
            player.performCommand("builder confirm") shouldBe true
            journey.awaitSettled(player) {
                (world.getBlockAt(0, 64, 0).blockData as MultipleFacing).faces == setOf(BlockFace.NORTH, BlockFace.EAST) &&
                    (world.getBlockAt(1, 64, 0).blockData as MultipleFacing).faces == setOf(BlockFace.SOUTH)
            }
        }
    }

    test("disconnect confirm skips the preview and the applied operation remains undoable") {
        strictMockBukkit(open = { ArcBuilderJourney.open() }) { journey ->
            val player = journey.builder("DirectFencer", GameMode.CREATIVE)
            val world = journey.world
            player.teleport(Location(world, 0.5, 64.0, 3.5, 0f, 0f))

            val fence = Material.OAK_FENCE.createBlockData() as MultipleFacing
            fence.setFace(BlockFace.NORTH, true)
            fence.setFace(BlockFace.EAST, true)
            world.getBlockAt(0, 64, 0).setBlockData(fence, false)

            player.inventory.setItemInMainHand(ItemStack(Material.ECHO_SHARD))
            player.performCommand("builder wand") shouldBe true
            journey.select(player, world, player.inventory.itemInMainHand, 0, 64, 0, 0, 64, 0)
            journey.paper.server.getCommandTabComplete(player, "builder disconnect c") shouldBe listOf("confirm")

            player.performCommand("builder disconnect confirm") shouldBe true

            journey.renderer.plans[player.uniqueId] shouldBe null
            journey.awaitSettled(player) {
                (world.getBlockAt(0, 64, 0).blockData as MultipleFacing).faces.isEmpty()
            }

            player.performCommand("builder undo") shouldBe true
            player.performCommand("builder confirm") shouldBe true
            journey.awaitSettled(player) {
                (world.getBlockAt(0, 64, 0).blockData as MultipleFacing).faces ==
                    setOf(BlockFace.NORTH, BlockFace.EAST)
            }
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
            player.inventory.setItemInMainHand(ItemStack(Material.AIR))
            player.performCommand("builder book draft Original") shouldBe true
            val blueprintKey = checkNotNull(org.bukkit.NamespacedKey.fromString("arc:build_book_blueprint_uuid"))
            val instanceKey = checkNotNull(org.bukkit.NamespacedKey.fromString("arc:build_book_instance_uuid"))
            journey.await("anchored draft delivery and player lease release") {
                val data = player.inventory.itemInMainHand.itemMeta?.persistentDataContainer ?: return@await false
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

    test("random build-book clicks never leave an orphan plan preview") {
        val definition = SystemBuildBookDefinition(
            buildingId = "viking.schem",
            title = "Стартовый дом",
            schematicSha256 = "a".repeat(64),
            playerEnabled = true,
            materialsIncluded = true,
        )
        val schematicBlock = mockk<BaseBlock>()
        every { schematicBlock.blockType.id } returns "minecraft:stone"
        mockkStatic(BukkitAdapter::class)
        try {
            strictMockBukkit(open = { ArcBuilderJourney.open(systemResolver = { definition }) }) { journey ->
                every { BukkitAdapter.adapt(schematicBlock) } returns Bukkit.createBlockData(Material.STONE)
                val building = mockk<Building>()
                every { building.fileName } returns "viking.schem"
                every { building.volume } returns 1L
                every { building.blockCount } returns 1
                every { building.getCorner1(any()) } returns BlockVector3.ZERO
                every { building.getCorner2(any()) } returns BlockVector3.ZERO
                every { building.getBlock(any(), any()) } returns schematicBlock
                BuildingManager.addBuilding(building)

                val player = journey.builder("StarterBook", GameMode.SURVIVAL)
                player.teleport(player.location.apply { yaw = -180f })
                val original = ru.arc.autobuild.BuildBookItems.create(
                    ru.arc.autobuild.BuildBookData("viking.schem", "viking.schem"),
                )
                player.inventory.setItemInMainHand(original)
                val first = journey.world.getBlockAt(4, 64, 4).also { it.type = Material.DIRT }
                val second = journey.world.getBlockAt(9, 64, 9).also { it.type = Material.DIRT }

                journey.rightClickBook(player, Action.RIGHT_CLICK_BLOCK, first)
                checkNotNull(BuildingManager.pending(player.uniqueId)).bookData.title shouldBe "Стартовый дом"
                BuildBookCodec.read(player.inventory.itemInMainHand) shouldBe
                    checkNotNull(BuildingManager.pending(player.uniqueId)).bookData
                journey.renderer.hasBook(player.uniqueId) shouldBe true

                journey.rightClickBook(player, Action.RIGHT_CLICK_AIR, null)
                journey.renderer.hasBook(player.uniqueId) shouldBe false
                journey.renderer.plans.containsKey(player.uniqueId) shouldBe true
                val firstPlan = checkNotNull(journey.renderer.plans[player.uniqueId])
                firstPlan.costs.map { it.materialKey to it.amount } shouldBe listOf("minecraft:book" to 1)

                journey.rightClickBook(player, Action.RIGHT_CLICK_AIR, null)
                journey.renderer.plans[player.uniqueId] shouldBe firstPlan

                journey.rightClickBook(player, Action.RIGHT_CLICK_BLOCK, second)
                journey.renderer.plans.containsKey(player.uniqueId) shouldBe false
                journey.renderer.hasBook(player.uniqueId) shouldBe true
                checkNotNull(BuildingManager.pending(player.uniqueId)).centerBlock.blockX shouldBe 9
            }
        } finally {
            unmockkStatic(BukkitAdapter::class)
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
    fun builder(name: String, mode: GameMode) = paper.addPlayer(name).also { player ->
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

    fun planBuildBookBlock(
        player: Player,
        block: org.bukkit.block.Block,
        after: BlockData,
        allowSystemLootContainer: Boolean = false,
        allowSystemFurniture: Boolean = false,
    ): BuilderBookPlacementResult = runtime.planBuildBookBlock(player, block, after, allowSystemLootContainer, allowSystemFurniture)

    fun rightClickBook(player: Player, action: Action, block: org.bukkit.block.Block?) {
        paper.callEvent(
            PlayerInteractEvent(
                player,
                action,
                player.inventory.itemInMainHand,
                block,
                BlockFace.UP,
                EquipmentSlot.HAND,
            ),
        )
    }

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
        fun open(
            blocksPerTick: Int = 2,
            physicsUpdater: BuilderPhysicsUpdater = RecordingBuilderPhysicsUpdater(),
            systemResolver: (ru.arc.autobuild.BuildBookData) -> SystemBuildBookDefinition? = { null },
            lootTableResolver: (NamespacedKey) -> LootTable? = Bukkit::getLootTable,
            lootTableAccess: BuilderLootTableAccess = PaperBuilderLootTableAccess,
        ): ArcBuilderJourney {
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
                    physicsUpdater = physicsUpdater,
                    draftStorage = InMemoryBuilderDraftStorage(),
                    bookSchematicVerifier = BuilderBookSchematicVerifier { true },
                    bookReplacementRefund = { block -> ItemStack(block.type) },
                    systemBuildBookResolver = systemResolver,
                    lootTableResolver = lootTableResolver,
                    lootTableAccess = lootTableAccess,
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

    fun hasBook(playerId: UUID): Boolean = playerId in books

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
