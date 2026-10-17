package ru.arc.buildertools

import ru.arc.awaitArcBuilderBootstrap
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.PacketEventsAPI
import com.destroystokyo.paper.event.brigadier.AsyncPlayerSendSuggestionsEvent
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.Called
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
import org.bukkit.block.Container
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
import org.bukkit.plugin.ServicePriority
import org.bukkit.loot.LootTable
import org.opentest4j.TestAbortedException
import ru.arc.autobuild.ConstructionSite
import ru.arc.autobuild.BuildBookCodec
import ru.arc.autobuild.BuildBookData
import ru.arc.autobuild.BuildBookItems
import ru.arc.autobuild.Building
import ru.arc.autobuild.schematicTestBuilding
import ru.arc.autobuild.BuildingManager
import ru.arc.autobuild.PlayerBuildBookStore
import ru.arc.autobuild.PlayerBuildBookDigestInspection
import ru.arc.autobuild.PlayerBuildBookTemplate
import ru.arc.autobuild.PreparedPlayerBuildBookTemplate
import ru.arc.autobuild.SystemBuildBookDefinition
import ru.arc.config.ConfigManager
import ru.arc.observability.RuntimeHealthState
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.api.ArcBlockProtectionService
import ru.arc.paper.playerstate.PaperPlayerStateService
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
    test("later startup drift cannot restore a recovery hold for an already acknowledged player") {
        strictMockBukkit(open = { ArcBuilderJourney.open(blocksPerTick = 1) }) { journey ->
            val worlds = listOf(journey.world, journey.paper.addSimpleWorld("recovery-second"))
            worlds.forEach { world -> world.loadChunk(0, 0); world.loadChunk(1, 0) }
            val players = worlds.mapIndexed { index, world ->
                journey.builder("Recovery${index}", GameMode.SURVIVAL).also { player ->
                    player.teleport(Location(world, 0.5, 64.0, 3.5))
                    player.inventory.setItemInMainHand(ItemStack(Material.ECHO_SHARD))
                    player.performCommand("builder wand") shouldBe true
                    player.inventory.addItem(ItemStack(Material.STONE, 30))
                    journey.select(player, world, player.inventory.itemInMainHand, 0, 64, 0, 19, 64, 0)
                    journey.command(player, "builder fill stone") shouldBe true
                }
            }
            val before = players.associate { it.uniqueId to it.inventory.contents.map { item -> item?.clone() } }
            val records = journey.seedInterruptedPlans(players, appliedChanges = 1)
            records.size shouldBe 2
            val first = records.first()
            val later = records.last()
            val firstPlayer = players.single { it.uniqueId == first.playerId }
            val laterPlayer = players.single { it.uniqueId == later.playerId }
            val laterWorld = worlds.single { it.uid == later.plan.changes.first().position.worldId }
            val pausedWorld = mockk<World>()
            every { pausedWorld.isChunkLoaded(any<Int>(), any<Int>()) } returns false
            every { pausedWorld.getChunkAtAsync(any<Int>(), any<Int>(), false) } returns java.util.concurrent.CompletableFuture()
            mockkStatic(Bukkit::class)
            try {
                every { Bukkit.getWorld(laterWorld.uid) } returns pausedWorld
                journey.restartForRecovery(awaitReady = false)
                journey.await("first exact acknowledgement while later chunk is unavailable") {
                    !journey.playerLeaseActive(firstPlayer.uniqueId) && firstPlayer.inventory.contents.toList() == before[firstPlayer.uniqueId]
                }
                PlayerCommandPreprocessEvent(firstPlayer, "/spawn").also(journey.paper::callEvent).isCancelled shouldBe false
                val drift = later.plan.changes.last().position
                laterWorld.getBlockAt(drift.x, drift.y, drift.z).type = Material.DIAMOND_BLOCK
                every { Bukkit.getWorld(laterWorld.uid) } returns laterWorld
                journey.await("later world drift retained for review") { journey.health().state == RuntimeHealthState.DOWN }
                journey.health().recoveryBacklog shouldBe 1
                journey.playerLeaseActive(firstPlayer.uniqueId) shouldBe false
                journey.playerLeaseActive(laterPlayer.uniqueId) shouldBe true
                firstPlayer.inventory.contents.toList() shouldBe before[firstPlayer.uniqueId]
                journey.amount(laterPlayer, Material.STONE) shouldBe 10
                journey.stopForRecovery().map { it.operationId } shouldBe listOf(later.operationId)
            } finally {
                unmockkStatic(Bukkit::class)
            }
        }
    }

    test("startup isolates every interrupted player and block before sliced recovery restores inventories") {
        strictMockBukkit(open = { ArcBuilderJourney.open(blocksPerTick = 1) }) { journey ->
            journey.world.loadChunk(0, 2)
            val players = listOf("RecoverFirst", "RecoverLater").mapIndexed { index, name ->
                val player = journey.builder(name, GameMode.SURVIVAL)
                val z = index * 20
                player.teleport(Location(journey.world, 0.5, 64.0, z + 3.5))
                player.inventory.setItemInMainHand(ItemStack(Material.ECHO_SHARD))
                player.performCommand("builder wand") shouldBe true
                player.inventory.addItem(ItemStack(Material.STONE, 300))
                journey.select(player, journey.world, player.inventory.itemInMainHand, 0, 64, z, 15, 64, z + 15)
                journey.command(player, "builder fill stone") shouldBe true
                player
            }
            val before = players.map { it.inventory.contents.map { item -> item?.clone() } }
            fun placed(index: Int) = (0..15).sumOf { x -> (0..15).count { z ->
                journey.world.getBlockAt(x, 64, index * 20 + z).type == Material.STONE
            } }
            val interrupted = journey.seedInterruptedPlans(players, appliedChanges = 150)
            interrupted.size shouldBe 2
            interrupted.all { it.phase == BuilderJournalPhase.APPLYING } shouldBe true
            players.forEach { journey.amount(it, Material.STONE) shouldBe 44 }
            val priorCounts = players.indices.map(::placed)
            journey.restartForRecovery(awaitReady = false)
            // This runs before the journal read callback and its first capture.
            players.forEach { player ->
                PlayerCommandPreprocessEvent(player, "/spawn").also(journey.paper::callEvent).isCancelled shouldBe true
            }
            val visitor = journey.paper.addPlayer("RecoveryVisitor")
            BlockBreakEvent(journey.world.getBlockAt(15, 64, 35), visitor).also(journey.paper::callEvent).isCancelled shouldBe true
            journey.await("partial sliced world recovery") { players.indices.any { placed(it) in 1 until priorCounts[it] } }
            players.forEach { player ->
                PlayerCommandPreprocessEvent(player, "/spawn").also(journey.paper::callEvent).isCancelled shouldBe true
                journey.amount(player, Material.STONE) shouldBe 44
            }
            BlockBreakEvent(journey.world.getBlockAt(15, 64, 35), visitor).also(journey.paper::callEvent).isCancelled shouldBe true
            journey.await("exact recovery acknowledgements") {
                journey.health().state == RuntimeHealthState.UP && journey.activeLeases() == 0 &&
                    players.indices.all { index -> placed(index) == 0 && players[index].inventory.contents.toList() == before[index] }
            }
            journey.stopForRecovery() shouldBe emptyList()
        }
    }

    test("disconnect after APPLYING durability but before native capture cannot resurrect a cancelled operation") {
        strictMockBukkit(open = { ArcBuilderJourney.open(blocksPerTick = 1) }) { journey ->
            val player = journey.builder("CancelPreApply", GameMode.SURVIVAL)
            player.teleport(Location(journey.world, 0.5, 64.0, 3.5))
            player.inventory.setItemInMainHand(ItemStack(Material.ECHO_SHARD))
            player.performCommand("builder wand") shouldBe true
            player.inventory.addItem(ItemStack(Material.STONE, 300))
            journey.select(player, journey.world, player.inventory.itemInMainHand, 0, 64, 0, 19, 64, 14)
            val before = player.inventory.contents.map { it?.clone() }
            journey.command(player, "builder fill stone") shouldBe true
            player.performCommand("builder confirm") shouldBe true
            journey.await("APPLYING journal before capture") {
                journey.operation(player)?.let { it.record.phase == BuilderJournalPhase.APPLYING && !it.inventoryMutated && it.appliedChanges == 0 } == true
            }
            journey.paper.callEvent(PlayerQuitEvent(player, Component.empty()))
            journey.await("cancelled operation acknowledgement") { journey.activeLeases() == 0 }
            journey.paper.performTicks(10)
            journey.health().state shouldBe RuntimeHealthState.UP
            (0..19).all { x -> (0..14).all { z -> journey.world.getBlockAt(x, 64, z).type == Material.AIR } } shouldBe true
            player.inventory.contents.toList() shouldBe before
            journey.stopForRecovery() shouldBe emptyList()
        }
    }

    test("shutdown preserves a partial operation journal and restart restores exact world and inventory") {
        strictMockBukkit(open = { ArcBuilderJourney.open(blocksPerTick = 1) }) { journey ->
            val player = journey.builder("DrainBuilder", GameMode.SURVIVAL)
            player.teleport(Location(journey.world, 0.5, 64.0, 3.5))
            player.inventory.setItemInMainHand(ItemStack(Material.ECHO_SHARD))
            player.performCommand("builder wand") shouldBe true
            player.inventory.addItem(ItemStack(Material.STONE, 10))
            journey.select(player, journey.world, player.inventory.itemInMainHand, 0, 64, 0, 4, 64, 0)
            val before = player.inventory.contents.map { it?.clone() }
            player.performCommand("builder fill stone") shouldBe true
            journey.await("asynchronous fill plan") { journey.renderer.plans[player.uniqueId]?.changes?.size == 5 }
            player.performCommand("builder confirm") shouldBe true
            journey.await("first durable mutation slice") { journey.countLine(journey.world, Material.STONE) == 1 }
            val records = journey.stopForRecovery()
            records.single().phase shouldBe BuilderJournalPhase.APPLYING
            records.single().plan.changes.size shouldBe 5
            journey.countLine(journey.world, Material.STONE) shouldBe 1
            journey.amount(player, Material.STONE) shouldBe 5
            journey.restartForRecovery()
            journey.awaitSettled(player) {
                journey.countLine(journey.world, Material.AIR) == 5 && player.inventory.contents.toList() == before
            }
            journey.amount(player, Material.STONE) shouldBe 10
        }
    }

    test("feature protection vetoes book planning and a fill confirmed after protection changed") {
        strictMockBukkit(open = { ArcBuilderJourney.open() }) { journey ->
            val player = journey.builder("ProtectedBuilder", GameMode.CREATIVE)
            player.teleport(Location(journey.world, 0.5, 64.0, 3.5))
            var protected = false
            journey.paper.server.servicesManager.register(ArcBlockProtectionService::class.java,
                ArcBlockProtectionService { id, x, _, _ -> protected && id == journey.world.uid && x == 0 },
                journey.plugin, ServicePriority.Lowest)
            player.inventory.setItemInMainHand(ItemStack(Material.ECHO_SHARD))
            player.performCommand("builder wand") shouldBe true
            journey.select(player, journey.world, player.inventory.itemInMainHand, 0, 64, 0, 0, 64, 0)
            journey.command(player, "builder fill stone") shouldBe true
            checkNotNull(journey.renderer.plans[player.uniqueId]).changes.size shouldBe 1
            protected = true
            player.performCommand("builder confirm") shouldBe true
            journey.awaitSettled(player) { journey.world.getBlockAt(0, 64, 0).type == Material.AIR }
            shouldThrow<BuilderUserFailure> {
                journey.planBuildBookBlock(player, journey.world.getBlockAt(0, 64, 0), Material.STONE.createBlockData())
            }.path shouldBe "errors.protection"
            (journey.planBuildBookBlock(player, journey.world.getBlockAt(1, 64, 0), Material.STONE.createBlockData())
                is BuilderBookPlacementResult.Change) shouldBe true
        }
    }

    test("deconstruction rechecks every batch and does not roll back through newly protected blocks") {
        strictMockBukkit(open = { ArcBuilderJourney.open(blocksPerTick = 2) }) { journey ->
            val player = journey.builder("ProtectedDigger", GameMode.CREATIVE)
            player.teleport(Location(journey.world, 0.5, 64.0, 3.5))
            (0..4).forEach { journey.world.getBlockAt(it, 64, 0).type = Material.STONE }
            var protected = false
            journey.paper.server.servicesManager.register(ArcBlockProtectionService::class.java,
                ArcBlockProtectionService { id, _, _, _ -> protected && id == journey.world.uid },
                journey.plugin, ServicePriority.Normal)
            player.inventory.setItemInMainHand(ItemStack(Material.ECHO_SHARD))
            player.performCommand("builder wand") shouldBe true
            journey.select(player, journey.world, player.inventory.itemInMainHand, 0, 64, 0, 4, 64, 0)
            journey.command(player, "builder deconstruct") shouldBe true
            checkNotNull(journey.renderer.plans[player.uniqueId]).changes.size shouldBe 5
            player.performCommand("builder confirm") shouldBe true
            journey.await("first deconstruction batch") { journey.countLine(journey.world, Material.AIR) == 2 }
            protected = true
            journey.await("protected deconstruction recovery hold") { journey.health().recoveryBacklog == 1 }
            journey.health().state shouldBe RuntimeHealthState.DOWN
            journey.activeLeases() shouldBe 1
            journey.countLine(journey.world, Material.AIR) shouldBe 2
            journey.countLine(journey.world, Material.STONE) shouldBe 3
            journey.amount(player, Material.STONE) shouldBe 0
        }
    }

    test("construction cannot recognize foreign protected afterimages or repair protected loot metadata") {
        val loot = mockk<BuilderLootTableAccess>()
        strictMockBukkit(open = { ArcBuilderJourney.open(lootTableAccess = loot) }) { journey ->
            val player = journey.builder("ProtectedConstruction", GameMode.SURVIVAL)
            player.teleport(Location(journey.world, 0.5, 64.0, 3.5))
            val target = journey.world.getBlockAt(0, 64, 0).apply { type = Material.CHEST }
            val change = BuilderBlockChange(BuilderBlockPos(journey.world.uid, 0, 64, 0),
                Material.AIR.createBlockData().asString, target.blockData.asString)
            val step = BuilderConstructionStep(change, null, null, lootTableKey = "minecraft:chests/simple_dungeon")
            val project = protectionProject(player.uniqueId, listOf(step))
            val port = journey.constructionPort()
            journey.paper.server.servicesManager.register(ArcBlockProtectionService::class.java,
                ArcBlockProtectionService { id, _, _, _ -> id == journey.world.uid }, journey.plugin, ServicePriority.Normal)
            port.canModify(project, step) shouldBe false
            shouldThrow<BuilderConstructionTemporarilyUnavailableException> { port.currentBlockData(change.position) }
            shouldThrow<BuilderConstructionTemporarilyUnavailableException> { port.isStepApplied(step) }
            shouldThrow<BuilderConstructionTemporarilyUnavailableException> { port.apply(project, step) }
            BuilderConstructionProjectController.tick(project, project.updatedAtMillis + 1, port) shouldBe null
            target.type shouldBe Material.CHEST
            verify { loot wasNot Called }
        }
    }

    test("construction resource receipts cannot withdraw from a newly protected container") {
        strictMockBukkit(open = { ArcBuilderJourney.open() }) { journey ->
            val player = journey.builder("ProtectedResources", GameMode.SURVIVAL)
            player.teleport(Location(journey.world, 0.5, 64.0, 3.5))
            val target = journey.world.getBlockAt(0, 64, 0)
            val input = BuilderItemCodec.aggregate(listOf(ItemStack(Material.STONE, 2))).single()
            val change = BuilderBlockChange(BuilderBlockPos(journey.world.uid, 0, 64, 0),
                target.blockData.asString, Material.STONE.createBlockData().asString)
            val project = protectionProject(player.uniqueId, listOf(BuilderConstructionStep(change, input, null)))
            val chest = journey.world.getBlockAt(1, 64, 0).apply { type = Material.CHEST }.state as Container
            chest.inventory.addItem(ItemStack(Material.STONE, 64))
            val port = journey.constructionPort()
            var receipt: BuilderResourceMutation? = null
            repeat(100) { if (receipt == null) receipt = port.prepareInput(player.uniqueId, project, input) }
            val reserved = checkNotNull(receipt)
            journey.paper.server.servicesManager.register(ArcBlockProtectionService::class.java,
                ArcBlockProtectionService { id, x, _, _ -> id == journey.world.uid && x == 1 }, journey.plugin, ServicePriority.Normal)
            port.reconcileResource(project, reserved) shouldBe BuilderResourceMutationResult.CONFLICT
            chest.inventory.contents.filterNotNull().sumOf(ItemStack::getAmount) shouldBe 64
            repeat(100) { port.prepareInput(player.uniqueId, project, input) shouldBe null }
        }
    }

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

            journey.command(player, "builder fill ancient_debris") shouldBe true

            val rejection = PlainTextComponentSerializer.plainText().serialize(
                checkNotNull(player.nextComponentMessage()),
            )
            rejection shouldContain "Этот материал нельзя использовать."
            rejection shouldNotContain "крон"
            journey.renderer.plans.containsKey(player.uniqueId) shouldBe false
            world.getBlockAt(0, 64, 0).type shouldBe Material.AIR
        }
    }

    test("deferred click preserves original facing and action with identical books in two slots") {
        val definition = SystemBuildBookDefinition("civs/house", "House", true, true)
        val loading = java.util.concurrent.CompletableFuture<Building>()
        strictMockBukkit(open = { ArcBuilderJourney.open(systemResolver = { definition }, schematicLoader = { _, _ -> loading }) }) { journey ->
            val player = journey.builder("DelayedBook", GameMode.CREATIVE)
            player.teleport(player.location.apply { yaw = -180f })
            val book = BuildBookItems.create(BuildBookData(definition.buildingId, definition.title))
            player.inventory.setItem(0, book)
            player.inventory.setItem(1, book.clone())
            player.inventory.heldItemSlot = 0
            player.isSneaking = false
            journey.rightClickBook(player, Action.RIGHT_CLICK_BLOCK, journey.world.getBlockAt(5, 64, 5))
            BuildingManager.pending(player.uniqueId) shouldBe null
            player.nextComponentMessage() shouldBe null
            player.isSneaking = true
            player.setRotation(-90f, 0f)
            loading.complete(schematicTestBuilding(definition.buildingId))
            journey.paper.performTicks(3)
            val preview = checkNotNull(BuildingManager.pending(player.uniqueId))
            preview.rotation shouldBe 0
            preview.transforming shouldBe false
            journey.renderer.hasBook(player.uniqueId) shouldBe true
            PlainTextComponentSerializer.plainText().serialize(checkNotNull(player.nextComponentMessage())) shouldContain "House"
        }
    }

    test("an existing book loads the canonical moved schema asynchronously and retains its identity") {
        val definition = SystemBuildBookDefinition("players/viking", "Viking", true, true)
        val loading = java.util.concurrent.CompletableFuture<Building>()
        val requests = mutableListOf<Pair<String, String?>>()
        strictMockBukkit(open = { ArcBuilderJourney.open(
            systemResolver = { if (it.buildingId == "viking.schem") definition else null },
            schematicLoader = { id, digest -> requests += id to digest; loading },
        ) }) { journey ->
            val player = journey.builder("LegacyBook", GameMode.CREATIVE)
            val original = BuildBookData("viking.schem", "Viking",
                contentSha256 = "a".repeat(64), schematicSha256 = "b".repeat(64))
            player.inventory.setItemInMainHand(BuildBookItems.create(original))
            journey.rightClickBook(player, Action.RIGHT_CLICK_BLOCK, journey.world.getBlockAt(5, 64, 5))
            requests shouldBe listOf(definition.buildingId to null)
            BuildingManager.pending(player.uniqueId) shouldBe null
            loading.complete(schematicTestBuilding(definition.buildingId))
            journey.paper.performTicks(3)
            journey.renderer.hasBook(player.uniqueId) shouldBe true
            BuildBookCodec.read(player.inventory.itemInMainHand)?.buildingId shouldBe original.buildingId
        }
    }

    test("book menu waits safely for the initial shared transform") {
        val definition = SystemBuildBookDefinition("civs/house", "House", true, true)
        val base = schematicTestBuilding(definition.buildingId)
        val transformed = java.util.concurrent.CompletableFuture<ru.arc.paper.api.ArcSchematicSnapshot>()
        val library = mockk<ru.arc.paper.api.ArcSchematicLibrary>()
        every { library.transform(base.snapshot, any()) } returns transformed
        val building = Building(definition.buildingId, base.snapshot, library)
        strictMockBukkit(open = { ArcBuilderJourney.open(systemResolver = { definition }, schematicLoader = { _, _ -> java.util.concurrent.CompletableFuture.completedFuture(building) }) }) { journey ->
            val player = journey.builder("PendingTransform", GameMode.CREATIVE)
            player.inventory.setItemInMainHand(BuildBookItems.create(BuildBookData(definition.buildingId, definition.title)))
            journey.rightClickBook(player, Action.RIGHT_CLICK_BLOCK, journey.world.getBlockAt(5, 64, 5))
            checkNotNull(BuildingManager.pending(player.uniqueId)).transforming shouldBe true
            PlainTextComponentSerializer.plainText().serialize(checkNotNull(player.nextComponentMessage())) shouldContain "House"
            repeat(3) { player.performCommand("builder book menu") shouldBe true }
            player.nextComponentMessage() shouldBe null
            journey.renderer.hasBook(player.uniqueId) shouldBe false
            transformed.complete(base.snapshot)
            journey.paper.performTicks(2)
            checkNotNull(BuildingManager.pending(player.uniqueId)).transforming shouldBe false
            journey.renderer.hasBook(player.uniqueId) shouldBe true
            player.performCommand("builder book menu") shouldBe true
            player.openInventory.topInventory.type shouldBe org.bukkit.event.inventory.InventoryType.CHEST
        }
    }

    test("quiet schematic loading still reports a failed load") {
        val definition = SystemBuildBookDefinition("civs/house", "House", true, true)
        val loading = java.util.concurrent.CompletableFuture<Building>()
        strictMockBukkit(open = { ArcBuilderJourney.open(systemResolver = { definition }, schematicLoader = { _, _ -> loading }) }) { journey ->
            val player = journey.builder("FailedBookLoad", GameMode.CREATIVE)
            player.inventory.setItemInMainHand(BuildBookItems.create(BuildBookData(definition.buildingId, definition.title)))
            journey.rightClickBook(player, Action.RIGHT_CLICK_BLOCK, journey.world.getBlockAt(5, 64, 5))
            player.nextComponentMessage() shouldBe null

            loading.completeExceptionally(IllegalStateException("schematic unavailable"))
            journey.paper.performTicks(3)

            PlainTextComponentSerializer.plainText().serialize(checkNotNull(player.nextComponentMessage())) shouldContain
                "Эта книга повреждена или её чертёж недоступен."
            player.nextComponentMessage() shouldBe null
            BuildingManager.pending(player.uniqueId) shouldBe null
            journey.renderer.hasBook(player.uniqueId) shouldBe false
        }
    }

    test("book cancel and clear invalidate an initial load before any preview exists") {
        for (command in listOf("builder book cancel", "builder clear")) {
            val definition = SystemBuildBookDefinition("civs/house", "House", true, true)
            val loading = java.util.concurrent.CompletableFuture<Building>()
            strictMockBukkit(open = { ArcBuilderJourney.open(systemResolver = { definition }, schematicLoader = { _, _ -> loading }) }) { journey ->
                val player = journey.builder("CancelledLoad", GameMode.CREATIVE)
                player.inventory.setItemInMainHand(BuildBookItems.create(BuildBookData(definition.buildingId, definition.title)))
                journey.rightClickBook(player, Action.RIGHT_CLICK_BLOCK, journey.world.getBlockAt(5, 64, 5))
                player.performCommand(command) shouldBe true
                loading.complete(schematicTestBuilding(definition.buildingId))
                journey.paper.performTicks(3)
                BuildingManager.pending(player.uniqueId) shouldBe null
                journey.renderer.hasBook(player.uniqueId) shouldBe false
            }
        }
    }

    test("deferred sneak click opens its selector after the modifier is released") {
        val definition = SystemBuildBookDefinition("civs/house", "House", true, true)
        val loading = java.util.concurrent.CompletableFuture<Building>()
        strictMockBukkit(open = { ArcBuilderJourney.open(systemResolver = { definition.copy(buildingId = it.buildingId) }, schematicLoader = { _, _ -> loading }) }) { journey ->
            val player = journey.builder("DelayedSelector", GameMode.CREATIVE)
            val data = BuildBookData(definition.buildingId, definition.title,
                selectableBuildingIds = listOf("civs/house", "civs/tower"))
            player.inventory.setItemInMainHand(BuildBookItems.create(data))
            player.isSneaking = true
            journey.rightClickBook(player, Action.RIGHT_CLICK_BLOCK, journey.world.getBlockAt(5, 64, 5))
            player.isSneaking = false
            loading.complete(schematicTestBuilding(definition.buildingId))
            journey.paper.performTicks(3)
            BuildingManager.pending(player.uniqueId) shouldBe null
            player.openInventory.topInventory.type shouldBe org.bukkit.event.inventory.InventoryType.CHEST
        }
    }

    test("system book journey keeps schematic air and removes obstructing andesite") {
        val definition = SystemBuildBookDefinition(
            buildingId = "carve-test.schem",
            title = "Carve test",
            playerEnabled = true,
            materialsIncluded = true,
        )
        val schematicAir = mockk<BaseBlock>()
        every { schematicAir.blockType.id } returns "minecraft:air"
        mockkStatic(BukkitAdapter::class)
        try {
            strictMockBukkit(open = { ArcBuilderJourney.open(systemResolver = { definition }) }) { journey ->
                every { BukkitAdapter.adapt(schematicAir) } returns Bukkit.createBlockData(Material.AIR)
                journey.addBuilding(schematicTestBuilding(definition.buildingId, listOf(BukkitAdapter.adapt(schematicAir).asString)))

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

                journey.confirmBookHologram(player)
                journey.awaitSettled(player) { target.type == Material.AIR }
                target.type shouldBe Material.AIR
            }
        } finally {
            unmockkStatic(BukkitAdapter::class)
        }
    }

    test("system book keeps a technical heart out of preview and construction without clearing its world cell") {
        val definition = SystemBuildBookDefinition("heart.schem", "House", true, true)
        strictMockBukkit(open = { ArcBuilderJourney.open(systemResolver = { definition }) }) { journey ->
            val building = schematicTestBuilding(definition.buildingId,
                listOf("minecraft:structure_void", "minecraft:stone", "minecraft:barrier"))
            journey.addBuilding(building)
            val player = journey.builder("HeartBuilder", GameMode.SURVIVAL)
            player.teleport(player.location.apply { yaw = -180f })
            player.inventory.setItemInMainHand(BuildBookItems.create(BuildBookData(definition.buildingId, definition.title)))
            val anchor = journey.world.getBlockAt(5, 64, 5).also { it.type = Material.DIRT }

            journey.rightClickBook(player, Action.RIGHT_CLICK_BLOCK, anchor)
            val site = checkNotNull(BuildingManager.pending(player.uniqueId))
            val heartTarget = site.worldLocation(BlockVector3.ZERO).block.also { it.type = Material.ANDESITE }
            val buildingTarget = site.worldLocation(BlockVector3.at(0, 1, 0)).block
            val barrierTarget = site.worldLocation(BlockVector3.at(0, 2, 0)).block.also { it.type = Material.DIRT }
            site.relativePositionsBottomUp().toList() shouldBe listOf(BlockVector3.at(0, 1, 0))
            checkNotNull(BuildBookCodec.read(player.inventory.itemInMainHand)).blockCount shouldBe 1

            journey.confirmBookHologram(player)
            journey.awaitSettled(player) { buildingTarget.type == Material.STONE }

            heartTarget.type shouldBe Material.ANDESITE
            barrierTarget.type shouldBe Material.DIRT
            journey.amount(player, Material.STRUCTURE_VOID) shouldBe 0
            journey.amount(player, Material.ANDESITE) shouldBe 0
            building.snapshot.blockAt(0, 0, 0).blockData shouldBe "minecraft:structure_void"
        }
    }

    test("system book places both door halves in the same construction tick") {
        val definition = SystemBuildBookDefinition(
            buildingId = "atomic-door-test.schem",
            title = "Atomic door test",
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
                journey.addBuilding(schematicTestBuilding(definition.buildingId, listOf(bottom.asString, top.asString)))

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
                journey.confirmBookHologram(player)
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
                journey.addBuilding(schematicTestBuilding(definition.buildingId, listOf(bottom.asString, top.asString)))

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
                journey.confirmBookHologram(player)
                val store = BuilderConstructionProjectStore(journey.plugin.dataPath, 10000)
                var projectId: UUID? = null
                journey.awaitStableConstruction("waiting for missing materials") {
                    val record = store.loadAll().firstOrNull { it.playerId == player.uniqueId }
                    projectId = record?.projectId
                    record?.state == BuilderConstructionProjectState.WAITING_MATERIALS
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
                journey.await("asynchronous construction menu resource index") {
                    player.openInventory.topInventory.getItem(22)?.type == Material.NETHER_STAR
                }
                player.openInventory.topInventory.getItem(22)?.type shouldBe Material.NETHER_STAR
                click(22, org.bukkit.event.inventory.ClickType.LEFT)
                journey.awaitStableConstruction("admin completion through the real menu callback") {
                    store.loadOrNull(checkNotNull(projectId))?.state == BuilderConstructionProjectState.COMPLETED
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
                journey.addBuilding(schematicTestBuilding(definition.buildingId, listOf(BukkitAdapter.adapt(schematicChest).asString)))

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
                journey.confirmBookHologram(player)
                journey.await("loot-table assignment") { appliedLootKey != null }
                journey.awaitSettled(player) { target.type == Material.CHEST }

                appliedLootKey shouldBe NamespacedKey.minecraft("chests/spawn_bonus_chest")
            }
        } finally {
            unmockkStatic(BukkitAdapter::class)
        }
    }

    test("survival copy paste and undo omit technical hearts while preserving their target cells") {
        strictMockBukkit(open = { ArcBuilderJourney.open() }) { journey ->
            val player = journey.builder("SurvivalBuilder", GameMode.SURVIVAL)
            val world = journey.world
            player.teleport(Location(world, 0.5, 64.0, 3.5, 0f, 0f))
            world.getBlockAt(0, 64, 0).type = Material.STONE
            world.getBlockAt(1, 64, 0).type = Material.OAK_PLANKS
            world.getBlockAt(2, 64, 0).type = Material.STRUCTURE_VOID
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

            journey.select(player, world, wand, 0, 64, 0, 2, 64, 0)
            val selectionFrame = checkNotNull(journey.renderer.selections[player.uniqueId])
            checkNotNull(selectionFrame.selection).volume shouldBe 3
            selectionFrame.points.first?.x shouldBe 0
            selectionFrame.points.second?.x shouldBe 2
            val selectionRenders = journey.renderer.selectionRenders
            journey.paper.performTicks(30)
            journey.renderer.selectionRenders shouldBeGreaterThan selectionRenders

            journey.command(player, "builder copy") shouldBe true
            player.inventory.addItem(ItemStack(Material.STONE), ItemStack(Material.OAK_PLANKS))
            player.teleport(Location(world, 10.5, 64.0, 13.5, 0f, 0f))
            val heartTarget = world.getBlockAt(12, 64, 10).also { it.type = Material.BEDROCK }

            journey.command(player, "builder paste") shouldBe true
            world.getBlockAt(10, 64, 10).type shouldBe Material.AIR
            world.getBlockAt(11, 64, 10).type shouldBe Material.AIR
            checkNotNull(journey.renderer.plans[player.uniqueId]).changes.size shouldBe 2
            checkNotNull(journey.renderer.plans[player.uniqueId]).costs.sumOf { it.amount } shouldBe 2
            heartTarget.type shouldBe Material.BEDROCK
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
            world.getBlockAt(2, 64, 0).type shouldBe Material.STRUCTURE_VOID
            heartTarget.type shouldBe Material.BEDROCK

            journey.command(player, "builder undo") shouldBe true
            checkNotNull(journey.renderer.plans[player.uniqueId]).changes.size shouldBe 2
            world.getBlockAt(10, 64, 10).type shouldBe Material.STONE
            world.getBlockAt(11, 64, 10).type shouldBe Material.OAK_PLANKS
            player.performCommand("builder confirm") shouldBe true
            journey.awaitSettled(player) {
                world.getBlockAt(10, 64, 10).type == Material.AIR &&
                    world.getBlockAt(11, 64, 10).type == Material.AIR
            }
            journey.amount(player, Material.STONE) shouldBe 1
            journey.amount(player, Material.OAK_PLANKS) shouldBe 1
            journey.amount(player, Material.STRUCTURE_VOID) shouldBe 0
            heartTarget.type shouldBe Material.BEDROCK
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

            journey.command(player, "builder copy") shouldBe true

            player.teleport(Location(world, 10.5, 64.0, 10.5, 0f, 0f))
            journey.command(player, "builder paste right") shouldBe true
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
            journey.command(player, "builder copy") shouldBe true

            world.getBlockAt(2, 64, 0).type = Material.BEDROCK
            journey.select(player, world, wand, 2, 64, 0, 2, 64, 0)
            journey.command(player, "builder copy") shouldBe true

            journey.activeLeases() shouldBe 0
            PlayerCommandPreprocessEvent(player, "/builder status").also(journey.paper::callEvent).isCancelled shouldBe false
            BlockBreakEvent(world.getBlockAt(4, 64, 0), player).also(journey.paper::callEvent).isCancelled shouldBe false

            player.teleport(Location(world, 10.5, 64.0, 3.5, 0f, 0f))
            journey.command(player, "builder paste") shouldBe true
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
            journey.command(player, "builder copy") shouldBe true

            world.getBlockAt(10, 64, 0).type = Material.DEEPSLATE
            world.getBlockAt(11, 64, 0).type = Material.CHEST
            player.inventory.addItem(ItemStack(Material.STONE))
            player.teleport(Location(world, 10.5, 64.0, 3.5, 0f, 0f))
            journey.command(player, "builder paste") shouldBe true
            checkNotNull(journey.renderer.plans[player.uniqueId]).changes.size shouldBe 1
            player.performCommand("builder confirm") shouldBe true
            journey.awaitSettled(player) { world.getBlockAt(10, 64, 0).type == Material.STONE }
            world.getBlockAt(11, 64, 0).type shouldBe Material.CHEST

            player.inventory.addItem(ItemStack(Material.STONE), ItemStack(Material.OAK_PLANKS))
            player.teleport(Location(world, 14.5, 64.0, 3.5, 0f, 0f))
            journey.command(player, "builder paste") shouldBe true
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
            journey.command(player, "builder copy") shouldBe true
            player.inventory.addItem(ItemStack(Material.OAK_DOOR))

            player.teleport(Location(world, 10.5, 64.0, 3.5, 0f, 0f))
            journey.command(player, "builder paste") shouldBe true
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
            journey.command(player, "builder copy") shouldBe true
            player.teleport(Location(world, 10.5, 64.0, 3.5, 0f, 0f))
            journey.command(player, "builder paste") shouldBe true

            player.gameMode = GameMode.SURVIVAL
            player.performCommand("builder confirm") shouldBe true
            journey.renderer.plans.containsKey(player.uniqueId) shouldBe false
            world.getBlockAt(10, 64, 0).type shouldBe Material.AIR
            journey.activeLeases() shouldBe 0
            BlockBreakEvent(world.getBlockAt(4, 64, 0), player).also(journey.paper::callEvent).isCancelled shouldBe false

            player.gameMode = GameMode.CREATIVE
            journey.command(player, "builder paste") shouldBe true
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

            journey.command(player, "builder fill stone") shouldBe true
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

    test("selection panel stays near the nearest selection edge and returns without losing selection") {
        strictMockBukkit(open = { ArcBuilderJourney.open() }) { journey ->
            val player = journey.builder("LocalPanelBuilder", GameMode.CREATIVE)
            player.teleport(Location(journey.world, 0.5, 64.0, 3.5))
            player.inventory.setItemInMainHand(ItemStack(Material.ECHO_SHARD))
            player.performCommand("builder wand") shouldBe true
            journey.select(player, journey.world, player.inventory.itemInMainHand, 0, 64, 0, 12, 64, 0)
            val selected = checkNotNull(journey.selectionPanel(player)).selection

            player.teleport(Location(journey.world, 21.0, 64.0, 0.5))
            checkNotNull(journey.selectionPanel(player)).selection shouldBe selected
            player.teleport(Location(journey.world, 21.1, 64.0, 0.5))
            journey.selectionPanel(player) shouldBe null
            journey.selectionPanelAction(player, BuilderPanelAction.CLEAR)
            player.teleport(Location(journey.world, 0.5, 74.0, 0.5))
            journey.selectionPanel(player) shouldBe null
            player.teleport(Location(journey.world, 0.5, 64.0, 3.5))
            checkNotNull(journey.selectionPanel(player)).selection shouldBe selected
        }
    }

    test("selection panel confirmation preserves the durable fill and held-tool boundary") {
        strictMockBukkit(open = { ArcBuilderJourney.open() }) { journey ->
            val player = journey.builder("PanelBuilder", GameMode.SURVIVAL)
            player.teleport(Location(journey.world, 0.5, 64.0, 3.5))
            player.inventory.setItemInMainHand(ItemStack(Material.ECHO_SHARD))
            player.performCommand("builder wand") shouldBe true
            val wand = player.inventory.itemInMainHand.clone()
            journey.select(player, journey.world, wand, 0, 64, 0, 1, 64, 0)
            journey.world.getBlockAt(0, 64, 0).type = Material.DIRT
            journey.selectionPanelAction(player, BuilderPanelAction.COPY)
            journey.world.getBlockAt(0, 64, 0).type = Material.AIR
            val firstPage = checkNotNull(journey.selectionPanel(player)).actions
            val panelText = PlainTextComponentSerializer.plainText()
            panelText.serialize(checkNotNull(journey.selectionPanel(player)).status) shouldNotContain "1/2"
            panelText.serialize(checkNotNull(journey.selectionPanel(player)).status) shouldContain "ЛКМ / ПКМ"
            panelText.serialize(checkNotNull(journey.selectionPanel(player)).pagination) shouldBe "Стр. 1 из 2"
            firstPage.contains(BuilderPanelAction.PASTE) shouldBe true
            firstPage.count { it !in setOf(BuilderPanelAction.PREVIOUS_PAGE, BuilderPanelAction.NEXT_PAGE) } shouldBe 6
            firstPage.containsAll(listOf(BuilderPanelAction.FILL, BuilderPanelAction.REPLACE, BuilderPanelAction.COPY,
                BuilderPanelAction.DECONSTRUCT, BuilderPanelAction.DRAFT)) shouldBe true
            firstPage.contains(BuilderPanelAction.DISCONNECT) shouldBe false
            journey.selectionPanelAction(player, BuilderPanelAction.NEXT_PAGE)
            val secondPage = checkNotNull(journey.selectionPanel(player)).actions
            secondPage.contains(BuilderPanelAction.CLEAR) shouldBe true
            secondPage.contains(BuilderPanelAction.DISCONNECT) shouldBe true
            secondPage.indexOf(BuilderPanelAction.CROWN) shouldBe secondPage.indexOf(BuilderPanelAction.DISCONNECT) + 1
            secondPage.last { it !in setOf(BuilderPanelAction.PREVIOUS_PAGE, BuilderPanelAction.NEXT_PAGE) } shouldBe
                BuilderPanelAction.CROWN
            secondPage.contains(BuilderPanelAction.FILL) shouldBe false
            journey.selectionPanelAction(player, BuilderPanelAction.PREVIOUS_PAGE)
            checkNotNull(journey.selectionPanel(player)).actions shouldBe firstPage
            player.inventory.addItem(ItemStack(Material.STONE, 2))
            journey.command(player, "builder fill stone") shouldBe true
            checkNotNull(journey.selectionPanel(player)).actions.contains(BuilderPanelAction.CONFIRM) shouldBe true

            player.inventory.setItemInMainHand(ItemStack(Material.STICK))
            journey.selectionPanelAction(player, BuilderPanelAction.CONFIRM)
            journey.world.getBlockAt(0, 64, 0).type shouldBe Material.AIR
            journey.amount(player, Material.STONE) shouldBe 2
            journey.activeLeases() shouldBe 0

            player.inventory.setItemInMainHand(wand)
            journey.select(player, journey.world, wand, 0, 64, 0, 1, 64, 0)
            journey.renderer.plans[player.uniqueId] shouldBe null
            checkNotNull(journey.selectionPanel(player)).actions.contains(BuilderPanelAction.CONFIRM) shouldBe false
            journey.command(player, "builder fill stone") shouldBe true
            journey.selectionPanelAction(player, BuilderPanelAction.CONFIRM)
            journey.awaitSettled(player) {
                (0..1).all { journey.world.getBlockAt(it, 64, 0).type == Material.STONE }
            }
            journey.amount(player, Material.STONE) shouldBe 0
            journey.selectionPanelAction(player, BuilderPanelAction.NEXT_PAGE)
            checkNotNull(journey.selectionPanel(player)).actions.contains(BuilderPanelAction.UNDO) shouldBe true
            journey.selectionPanelAction(player, BuilderPanelAction.UNDO)
            journey.selectionPanelAction(player, BuilderPanelAction.CONFIRM)
            journey.awaitSettled(player) {
                (0..1).all { journey.world.getBlockAt(it, 64, 0).type == Material.AIR }
            }
            journey.amount(player, Material.STONE) shouldBe 2
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
            journey.command(fill, "builder replace dirt stone") shouldBe true
            journey.renderer.plans[fill.uniqueId] shouldBe null
            journey.world.getBlockAt(0, 64, 0).type = Material.AIR
            fill.inventory.addItem(ItemStack(Material.STONE))
            journey.command(fill, "builder fill stone") shouldBe true
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
            journey.command(replace, "builder fill deepslate") shouldBe true
            journey.renderer.plans[replace.uniqueId] shouldBe null
            journey.world.getBlockAt(1, 64, 0).type = Material.STONE
            replace.inventory.addItem(ItemStack(Material.DEEPSLATE))
            journey.command(replace, "builder replace stone deepslate") shouldBe true
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
            journey.command(disconnect, "builder replace oak_fence stone") shouldBe true
            journey.renderer.plans[disconnect.uniqueId] shouldBe null
            journey.command(disconnect, "builder disconnect") shouldBe true
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

            journey.command(player, "builder replace oak_stairs spruce_stairs") shouldBe true

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

            journey.command(player, "builder undo") shouldBe true
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
            journey.command(player, "builder fill stone") shouldBe true
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
            journey.command(player, "builder replace КАМЕНЬ АлмазныйБлок") shouldBe true
            checkNotNull(journey.renderer.plans[player.uniqueId]).kind shouldBe BuilderPlanKind.REPLACE
            world.getBlockAt(0, 64, 0).type shouldBe Material.STONE
            player.performCommand("builder confirm") shouldBe true
            journey.awaitSettled(player) { world.getBlockAt(0, 64, 0).type == Material.DIAMOND_BLOCK }
            journey.amount(player, Material.DIAMOND_BLOCK) shouldBe 0
            journey.amount(player, Material.STONE) shouldBe 1
            journey.command(player, "builder undo") shouldBe true
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
            journey.command(player, "builder replace воздух камень")
            val plan = checkNotNull(journey.renderer.plans[player.uniqueId]) {
                generateSequence { player.nextComponentMessage() }.joinToString(" | ") {
                    PlainTextComponentSerializer.plainText().serialize(it)
                }
            }
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
            journey.command(player, "builder undo")
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
            journey.command(player, "builder replace stone deepslate nope") shouldBe true
            journey.renderer.plans[player.uniqueId] shouldBe null
            world.getBlockAt(0, 64, 0).type shouldBe Material.STONE
            player.inventory.addItem(ItemStack(Material.DEEPSLATE))
            journey.paper.server.getCommandTabComplete(
                player,
                "builder replace stone deepslate c",
            ) shouldBe listOf("confirm")

            journey.command(player, "builder replace stone deepslate confirm") shouldBe true

            journey.renderer.plans[player.uniqueId] shouldBe null
            journey.awaitSettled(player) { world.getBlockAt(0, 64, 0).type == Material.DEEPSLATE }
            journey.amount(player, Material.DEEPSLATE) shouldBe 0
            journey.amount(player, Material.STONE) shouldBe 1
            journey.command(player, "builder undo") shouldBe true
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

            journey.command(player, "builder disconnect") shouldBe true

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

            journey.command(player, "builder undo") shouldBe true
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

            journey.command(player, "builder disconnect confirm") shouldBe true

            journey.renderer.plans[player.uniqueId] shouldBe null
            journey.awaitSettled(player) {
                (world.getBlockAt(0, 64, 0).blockData as MultipleFacing).faces.isEmpty()
            }

            journey.command(player, "builder undo") shouldBe true
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
            journey.command(owner, "builder copy") shouldBe true
            owner.teleport(Location(world, 5.5, 64.0, 3.5, 0f, 0f))
            journey.command(owner, "builder paste") shouldBe true

            journey.renderer.selections.containsKey(owner.uniqueId) shouldBe true
            journey.renderer.plans.containsKey(owner.uniqueId) shouldBe true

            owner.performCommand("builder cancel") shouldBe true
            journey.renderer.plans.containsKey(owner.uniqueId) shouldBe false
            journey.renderer.selections.containsKey(owner.uniqueId) shouldBe true
            journey.paper.callEvent(
                PlayerQuitEvent(owner, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED),
            )
            journey.renderer.selections.containsKey(owner.uniqueId) shouldBe false
            journey.command(owner, "builder paste") shouldBe true
            journey.renderer.plans.containsKey(owner.uniqueId) shouldBe false
        }
    }

    test("panel creates books from the current selection and consumes inventory books while retaining the selector") {
        strictMockBukkit(open = { ArcBuilderJourney.open() }) { journey ->
            val player = journey.builder("PanelDraft", GameMode.SURVIVAL)
            player.addAttachment(journey.plugin, "arcbuild.book.use", false)
            player.recalculatePermissions()
            player.teleport(Location(journey.world, 0.5, 64.0, 3.5))
            player.inventory.setItemInMainHand(ItemStack(Material.ECHO_SHARD))
            player.performCommand("builder wand") shouldBe true
            val wand = player.inventory.itemInMainHand.clone()
            player.inventory.setItem(10, ItemStack(Material.BOOK, 2))
            journey.world.getBlockAt(0, 64, 0).type = Material.STONE
            journey.select(player, journey.world, wand, 0, 64, 0, 0, 64, 0)
            checkNotNull(journey.selectionPanel(player)).actions.contains(BuilderPanelAction.DRAFT) shouldBe true
            journey.selectionPanelAction(player, BuilderPanelAction.DRAFT)
            journey.await("draft from selection without a prior copy") {
                player.inventory.storageContents.filterNotNull().any { BuildBookCodec.read(it)?.blockCount == 1 } &&
                    journey.selectionPanel(player) != null
            }
            player.inventory.getItem(10)?.amount shouldBe 1
            player.inventory.itemInMainHand shouldBe wand

            journey.world.getBlockAt(2, 64, 0).type = Material.OAK_PLANKS
            journey.world.getBlockAt(3, 64, 0).type = Material.DEEPSLATE
            journey.select(player, journey.world, wand, 2, 64, 0, 3, 64, 0)
            journey.selectionPanelAction(player, BuilderPanelAction.DRAFT)
            journey.await("draft replaces the stale clipboard with the new selection") {
                player.inventory.storageContents.filterNotNull().any { BuildBookCodec.read(it)?.blockCount == 2 } &&
                    journey.selectionPanel(player) != null
            }
            BuildBookCodec.read(checkNotNull(player.inventory.getItem(10)))?.blockCount shouldBe 2
            player.inventory.storageContents.filterNotNull().count { BuildBookCodec.read(it) != null } shouldBe 2
            player.inventory.itemInMainHand shouldBe wand
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
            journey.command(player, "builder copy") shouldBe true

            listOf(10.5, 14.5, 18.5).forEach { x ->
                player.teleport(Location(world, x, 64.0, 3.5, 0f, 0f))
                if (x == 14.5) world.getBlockAt(14, 64, 0).type = Material.DEEPSLATE
                journey.command(player, "builder paste") shouldBe true
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
            journey.addBuilding(schematicTestBuilding(draft.buildingId))

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
            journey.paper.performTicks(3)
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
            journey.paper.performTicks(3)
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
            journey.paper.performTicks(3)
            checkNotNull(BuildingManager.pending(player.uniqueId))
            player.performCommand("builder book cancel") shouldBe true
            BuildingManager.pending(player.uniqueId) shouldBe null
        }
    }

    test("random build-book clicks never leave an orphan plan preview") {
        val definition = SystemBuildBookDefinition(
            buildingId = "viking.schem",
            title = "Стартовый дом",
            playerEnabled = true,
            materialsIncluded = true,
        )
        val schematicBlock = mockk<BaseBlock>()
        every { schematicBlock.blockType.id } returns "minecraft:stone"
        mockkStatic(BukkitAdapter::class)
        try {
            strictMockBukkit(open = { ArcBuilderJourney.open(systemResolver = { definition }) }) { journey ->
                every { BukkitAdapter.adapt(schematicBlock) } returns Bukkit.createBlockData(Material.STONE)
                journey.addBuilding(schematicTestBuilding("viking.schem", listOf(BukkitAdapter.adapt(schematicBlock).asString)))

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
                journey.renderer.hasBook(player.uniqueId) shouldBe true
                journey.renderer.plans[player.uniqueId] shouldBe null
                journey.openBookHologramMenu(player)
                player.openInventory.topInventory.size shouldBe 45
                journey.renderer.plans[player.uniqueId] shouldBe null
                player.closeInventory()

                journey.rightClickBook(player, Action.RIGHT_CLICK_BLOCK, second)
                journey.renderer.plans.containsKey(player.uniqueId) shouldBe false
                journey.renderer.hasBook(player.uniqueId) shouldBe true
                checkNotNull(BuildingManager.pending(player.uniqueId)).centerBlock.blockX shouldBe 9
                player.performCommand("builder book menu") shouldBe true
                player.openInventory.topInventory.size shouldBe 45
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
    private var runtime: BuilderToolsRuntime,
    private val buildings: MutableMap<String, Building>,
) : AutoCloseable {
    fun addBuilding(building: Building) { buildings[building.fileName] = building }

    /** Drive only deferred planning, preserving the separate confirm/mutation assertions. */
    fun command(player: Player, command: String): Boolean {
        val accepted = player.performCommand(command)
        awaitPlanning()
        return accepted
    }

    private fun awaitPlanning() {
        val requests = BuilderToolsRuntime::class.java.getDeclaredField("bookRequests")
            .apply { isAccessible = true }.get(runtime) as BuilderBookRequests
        if (requests.size > 0) await("deferred builder planning") { requests.size == 0 }
    }

    fun stopForRecovery(): List<BuilderJournalRecord> {
        runtime.close()
        runtime.closeCompletion.get(5, java.util.concurrent.TimeUnit.SECONDS)
        return java.util.concurrent.CompletableFuture.supplyAsync {
            BuilderJournalStore(plugin.dataPath, 10_000).loadAll().map { it.value }
        }.get(5, java.util.concurrent.TimeUnit.SECONDS)
    }

    /**
     * A deterministic restart fixture: real planner output and native preimages,
     * followed by durable APPLYING records before a fixed partial afterimage.
     * The separate shutdown journey exercises interruption of a live mutation.
     */
    fun seedInterruptedPlans(players: List<Player>, appliedChanges: Int): List<BuilderJournalRecord> {
        val now = System.currentTimeMillis()
        val stateService = PaperPlayerStateService()
        val records = players.map { player ->
            val plan = checkNotNull(renderer.plans[player.uniqueId])
            require(appliedChanges in 1 until plan.changes.size)
            BuilderJournalRecord(
                operationId = plan.id,
                playerId = player.uniqueId,
                playerName = player.name,
                phase = BuilderJournalPhase.APPLYING,
                plan = plan,
                inventoryBefore = stateService.captureEnvelope(player, now),
                createdAtMillis = plan.createdAtMillis,
                updatedAtMillis = now,
            )
        }
        stopForRecovery() shouldBe emptyList()
        val root = plugin.dataPath
        val durable = java.util.concurrent.CompletableFuture.supplyAsync {
            val journal = BuilderJournalStore(root, 10_000)
            records.forEach(journal::commit)
            journal.loadAll().map { it.value }
        }.get(5, java.util.concurrent.TimeUnit.SECONDS)
        records.forEach { record ->
            val player = players.single { it.uniqueId == record.playerId }
            BuilderInventory.removeCosts(player.inventory, record.plan.costs) shouldBe true
            record.plan.changes.take(appliedChanges).forEach { change ->
                val world = checkNotNull(Bukkit.getWorld(change.position.worldId))
                world.getBlockAt(change.position.x, change.position.y, change.position.z)
                    .setBlockData(Bukkit.createBlockData(change.afterBlockData), false)
            }
        }
        return durable
    }

    fun restartForRecovery(awaitReady: Boolean = true) {
        runtime = BuilderToolsRuntime(
            plugin, BuilderToolsConfig(ConfigManager.ofModule(plugin.dataPath, "builder-tools.yml")).validated(),
            displayRenderer = renderer, physicsUpdater = RecordingBuilderPhysicsUpdater(),
            draftStorage = InMemoryBuilderDraftStorage(), systemBuildBookResolver = { null },
            schematicLoader = { id, _ -> java.util.concurrent.CompletableFuture.completedFuture(buildings[id] ?: schematicTestBuilding(id)) },
        )
        checkNotNull(plugin.getCommand("builder")).setExecutor(runtime)
        if (awaitReady) await("sliced startup journal recovery") { runtime.runtimeHealthContribution().state == RuntimeHealthState.UP }
    }

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

    fun playerLeaseActive(playerId: UUID): Boolean = runtime.isPlayerLeaseActive(playerId)

    fun operation(player: Player): BuilderActiveOperation? =
        (BuilderToolsRuntime::class.java.getDeclaredField("operationLocks").apply { isAccessible = true }.get(runtime)
            as BuilderOperationLocks).operation(player.uniqueId)

    fun health() = runtime.runtimeHealthContribution()

    fun constructionPort(): BuilderConstructionProjectPort = BuilderToolsRuntime::class.java
        .getDeclaredField("constructionPort").apply { isAccessible = true }.get(runtime) as BuilderConstructionProjectPort

    fun awaitStableConstruction(description: String, condition: () -> Boolean) {
        // Read the journal only after the runtime has acknowledged its async writes.
        await(description) { runtime.reloadBlocker() == null && condition() }
    }

    fun selectionPanel(player: Player) = runtime.selectionPanelView(player)

    fun selectionPanelAction(player: Player, action: BuilderPanelAction) {
        runtime.onSelectionPanelAction(player, action)
        if (action in setOf(BuilderPanelAction.COPY, BuilderPanelAction.PASTE, BuilderPanelAction.UNDO, BuilderPanelAction.DRAFT)) {
            awaitPlanning()
        }
    }

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
        paper.performTicks(3)
    }

    fun openBookHologramMenu(player: Player) = runtime.openBookPreviewMenuForTest(player)

    fun confirmOpenBookMenu(player: Player) {
        val event = org.bukkit.event.inventory.InventoryClickEvent(
            player.openInventory,
            org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,
            25,
            org.bukkit.event.inventory.ClickType.LEFT,
            org.bukkit.event.inventory.InventoryAction.PICKUP_ALL,
        )
        paper.callEvent(event)
        event.isCancelled shouldBe true
    }

    fun confirmBookHologram(player: Player) {
        openBookHologramMenu(player)
        confirmOpenBookMenu(player)
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
        try {
            runCatching { runtime.close() }
            runCatching { paper.server.pluginManager.disablePlugin(plugin) }
            paper.close()
            ConfigManager.clear()
        } finally {
            unmockkStatic(PacketEvents::class)
        }
    }

    companion object {
        fun open(
            blocksPerTick: Int = 2,
            physicsUpdater: BuilderPhysicsUpdater = RecordingBuilderPhysicsUpdater(),
            systemResolver: (ru.arc.autobuild.BuildBookData) -> SystemBuildBookDefinition? = { null },
            lootTableResolver: (NamespacedKey) -> LootTable? = Bukkit::getLootTable,
            lootTableAccess: BuilderLootTableAccess = PaperBuilderLootTableAccess,
            schematicLoader: ((String, String?) -> java.util.concurrent.CompletableFuture<Building>)? = null,
        ): ArcBuilderJourney {
            mockkStatic(PacketEvents::class)
            try {
                val packetEventsApi = mockk<PacketEventsAPI<Any>>(relaxed = true)
                every { PacketEvents.getAPI() } returns packetEventsApi
            } catch (failure: Throwable) {
                unmockkStatic(PacketEvents::class)
                throw failure
            }
            ConfigManager.clear()
            val paper = try {
                MockBukkitTestRuntime.open()
            } catch (failure: Throwable) {
                unmockkStatic(PacketEvents::class)
                throw failure
            }
            try {
                val plugin = paper.loadPlugin<ArcBuilderPlugin>()
                awaitArcBuilderBootstrap(paper, plugin)
                installArcVisualPacketBudgetFixture(plugin)
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
                val buildings = mutableMapOf<String, Building>()
                val runtime = BuilderToolsRuntime(
                    plugin = plugin,
                    config = BuilderToolsConfig(config).validated(),
                    displayRenderer = renderer,
                    blockDataRotation = BuilderBlockDataRotation { data, _ -> data },
                    physicsUpdater = physicsUpdater,
                    draftStorage = InMemoryBuilderDraftStorage(),
                    schematicLoader = schematicLoader ?: { id, _ -> java.util.concurrent.CompletableFuture.completedFuture(
                        buildings[id] ?: schematicTestBuilding(id),
                    ) },
                    bookSchematicVerifier = BuilderBookSchematicVerifier { _, _ -> true },
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
                // Every journey target is already loaded; planners must never load chunks implicitly.
                for (chunkX in -1..1) for (chunkZ in -1..1) world.loadChunk(chunkX, chunkZ)
                val journey = ArcBuilderJourney(paper, plugin, world, renderer, runtime, buildings)
                journey.await("ArcBuilder runtime readiness") {
                    runtime.runtimeHealthContribution().state == RuntimeHealthState.UP
                }
                return journey
            } catch (failure: Throwable) {
                try {
                    runCatching { paper.close() }
                    ConfigManager.clear()
                } finally {
                    unmockkStatic(PacketEvents::class)
                }
                throw failure
            }
        }
    }
}

private fun protectionProject(playerId: UUID, steps: List<BuilderConstructionStep>): BuilderConstructionProjectRecord {
    val id = UUID.randomUUID()
    val now = System.currentTimeMillis()
    val book = BuilderItemCodec.aggregate(listOf(ItemStack(Material.BOOK))).single()
    return BuilderConstructionProjectRecord(
        projectId = id,
        playerId = playerId,
        playerName = "Builder",
        plan = BuilderPlan(id, playerId, BuilderPlanKind.BUILD_BOOK, steps.map { it.change },
            listOf(book) + steps.mapNotNull { it.requiredMaterial }, steps.mapNotNull { it.output },
            createdAtMillis = now, expiresAtMillis = now + 60_000),
        steps = steps,
        bookCost = book,
        state = BuilderConstructionProjectState.PREPARED,
        cursor = 0,
        createdAtMillis = now,
        updatedAtMillis = now,
    ).validated().activated(now + 1)
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

    override fun inspectContent(buildingId: String, expectedSha256: String): java.util.concurrent.CompletableFuture<PlayerBuildBookDigestInspection> =
        java.util.concurrent.CompletableFuture.completedFuture(templates[buildingId]?.let { PlayerBuildBookDigestInspection.Ready(it.contentSha256) }
            ?: PlayerBuildBookDigestInspection.Missing)

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
