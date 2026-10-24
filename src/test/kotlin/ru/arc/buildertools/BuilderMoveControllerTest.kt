package ru.arc.buildertools

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.type.Door
import org.bukkit.entity.Player
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.plugin.Plugin
import org.bukkit.util.Vector
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class BuilderMoveControllerTest : FunSpec({
    test("move uses the dominant signed look axis including vertical") {
        listOf(
            Vector(1.0, 0.1, 0.2) to BlockFace.EAST,
            Vector(-1.0, 0.1, 0.2) to BlockFace.WEST,
            Vector(0.2, 1.0, 0.1) to BlockFace.UP,
            Vector(0.2, -1.0, 0.1) to BlockFace.DOWN,
            Vector(0.2, 0.1, 1.0) to BlockFace.SOUTH,
            Vector(0.2, 0.1, -1.0) to BlockFace.NORTH,
        ).forEach { (look, expected) -> BuilderMoveController.lookDirection(look) shouldBe expected }
    }

    test("overlap combines removal and placement from exact source snapshots without an item exchange") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("move-overlap")
            val player = paper.addPlayer("MoveOverlap")
            player.teleport(Location(world, 0.5, 64.0, 3.5))
            val harness = MoveHarness(paper.createSimplePlugin("MoveOverlap"), world)
            harness.select(0, 64, 0, 2, 64, 0)
            listOf(Material.STONE, Material.OAK_STAIRS, Material.GLASS).forEachIndexed { x, material ->
                world.getBlockAt(x, 64, 0).type = material
            }
            val before = (0..3).associateWith { world.getBlockAt(it, 64, 0).blockData.asString }

            val plan = harness.controller.plan(player, BlockFace.EAST).awaitBuilderPlanning()

            plan.kind shouldBe BuilderPlanKind.MOVE
            plan.changes.associate { it.position.x to (it.beforeBlockData to it.afterBlockData) } shouldBe mapOf(
                0 to (before.getValue(0) to "minecraft:air"),
                1 to (before.getValue(1) to before.getValue(0)),
                2 to (before.getValue(2) to before.getValue(1)),
                3 to (before.getValue(3) to before.getValue(2)),
            )
            plan.costs shouldBe emptyList()
            plan.rewards shouldBe emptyList()
            BuilderUndoRules.exchangeFor(plan) shouldBe BuilderUndoExchange(emptyList(), emptyList())
            harness.permissionChecks shouldBe listOf(BuilderFeature.COPY, BuilderFeature.PASTE)
            (0..3).associateWith { world.getBlockAt(it, 64, 0).blockData.asString } shouldBe before
            shouldThrow<IllegalArgumentException> {
                plan.copy(changes = plan.changes.take(1)).validated()
            }
            shouldThrow<IllegalArgumentException> {
                plan.copy(rewards = listOf(BuilderItemAmount("AAAA", "minecraft:stone", 1))).validated()
            }
        }
    }

    test("move accepts cave air at its destination while preserving non-air states") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("move-cave-air")
            val player = paper.addPlayer("MoveCaveAir")
            player.teleport(Location(world, 0.5, 64.0, 3.5))
            val harness = MoveHarness(paper.createSimplePlugin("MoveCaveAir"), world)
            harness.select(0, 64, 0, 0, 64, 0)
            world.getBlockAt(0, 64, 0).type = Material.STONE
            world.getBlockAt(1, 64, 0).type = Material.CAVE_AIR
            val plan = harness.controller.plan(player, BlockFace.EAST).awaitBuilderPlanning()
            plan.changes.single { it.position.x == 1 }.beforeBlockData shouldBe "minecraft:cave_air"
            plan.validated() shouldBe plan
        }
    }

    test("moves west and vertically preserve both source states and never depend on write order") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("move-directions")
            val player = paper.addPlayer("MoveDirections")
            player.teleport(Location(world, 0.5, 64.0, 3.5))
            val harness = MoveHarness(paper.createSimplePlugin("MoveDirections"), world)
            harness.select(0, 64, 0, 1, 64, 0)
            world.loadChunk(-1, 0)
            world.getBlockAt(0, 64, 0).type = Material.STONE
            world.getBlockAt(1, 64, 0).type = Material.DIRT
            val west = harness.controller.plan(player, BlockFace.WEST).awaitBuilderPlanning()
            west.changes.associate { it.position.x to it.afterBlockData } shouldBe
                mapOf(-1 to "minecraft:stone", 0 to "minecraft:dirt", 1 to "minecraft:air")
            listOf(BlockFace.UP to 65, BlockFace.DOWN to 63).forEach { (direction, targetY) ->
                val plan = harness.controller.plan(player, direction).awaitBuilderPlanning()
                plan.changes.filter { it.position.y == 64 }.map { it.afterBlockData } shouldBe List(2) { "minecraft:air" }
                plan.changes.filter { it.position.y == targetY }.associate { it.position.x to it.afterBlockData } shouldBe
                    mapOf(0 to "minecraft:stone", 1 to "minecraft:dirt")
                plan.costs shouldBe emptyList()
                plan.rewards shouldBe emptyList()
            }
            world.getBlockAt(0, 64, 0).type shouldBe Material.STONE
            world.getBlockAt(1, 64, 0).type shouldBe Material.DIRT
        }
    }

    test("unmatched door halves are excluded while complete protected-safe pairs move together") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("move-pairs")
            val player = paper.addPlayer("MovePairs")
            player.teleport(Location(world, 0.5, 64.0, 3.5))
            val harness = MoveHarness(paper.createSimplePlugin("MovePairs"), world)
            val lower = Material.OAK_DOOR.createBlockData() as Door
            lower.half = Bisected.Half.BOTTOM
            val upper = lower.clone() as Door
            upper.half = Bisected.Half.TOP
            world.getBlockAt(0, 64, 0).blockData = lower
            world.getBlockAt(0, 65, 0).blockData = upper
            world.getBlockAt(2, 64, 0).type = Material.STONE
            harness.select(0, 64, 0, 2, 64, 0)
            val partial = harness.controller.plan(player, BlockFace.SOUTH).awaitBuilderPlanning()
            partial.skippedUnsafeBlocks shouldBe 1
            partial.changes.map { it.position.x }.distinct() shouldBe listOf(2)
            harness.select(0, 64, 0, 0, 65, 0)
            val complete = harness.controller.plan(player, BlockFace.EAST).awaitBuilderPlanning()
            complete.changes.size shouldBe 4
            complete.skippedUnsafeBlocks shouldBe 0
            complete.changes.filter { it.position.x == 1 }.map { it.afterBlockData }.toSet() shouldBe
                setOf(lower.asString, upper.asString)
            complete.validated() shouldBe complete
            harness.protectedPosition = BuilderBlockPos(world.uid, 0, 65, 0)
            shouldThrow<MoveFailure> { harness.controller.plan(player, BlockFace.EAST).awaitBuilderPlanning() }
                .path shouldBe "errors.protection"
            world.getBlockAt(0, 64, 0).blockData.asString shouldBe lower.asString
            world.getBlockAt(0, 65, 0).blockData.asString shouldBe upper.asString
        }
    }

    test("occupied destinations use deconstruction tools and exact drops while overlap stays conserved") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("move-occupied")
            val player = paper.addPlayer("MoveOccupied")
            player.teleport(Location(world, 0.5, 64.0, 3.5))
            val named = ItemStack(Material.DIAMOND, 3).apply {
                editMeta { it.displayName(net.kyori.adventure.text.Component.text("Exact target drops")) }
            }
            val harness = MoveHarness(paper.createSimplePlugin("MoveOccupied"), world, drops = { _, tool ->
                checkNotNull(tool).containsEnchantment(Enchantment.SILK_TOUCH) shouldBe false
                tool.getEnchantmentLevel(Enchantment.FORTUNE) shouldBe 3
                listOf(named.clone())
            })
            harness.select(0, 64, 0, 1, 64, 0)
            world.getBlockAt(0, 64, 0).type = Material.STONE
            world.getBlockAt(1, 64, 0).type = Material.DIRT
            world.getBlockAt(2, 64, 0).type = Material.DIAMOND_BLOCK
            player.inventory.setItem(10, ItemStack(Material.DIAMOND_PICKAXE).apply {
                addUnsafeEnchantment(Enchantment.SILK_TOUCH, 1)
                addUnsafeEnchantment(Enchantment.FORTUNE, 3)
            })

            val plan = harness.controller.plan(player, BlockFace.EAST).awaitBuilderPlanning()

            plan.changes.associate { it.position.x to it.afterBlockData } shouldBe mapOf(
                0 to "minecraft:air", 1 to "minecraft:stone", 2 to "minecraft:dirt",
            )
            plan.rewards.single().amount shouldBe 3
            BuilderItemCodec.decodePrototype(plan.rewards.single().itemBase64).isSimilar(named) shouldBe true
            plan.toolDamage shouldBe 1
            BuilderPooledToolCodec.decode(checkNotNull(plan.toolFingerprintBase64)).uses.single().slot shouldBe 10
            plan.moveDeconstruction shouldBe BuilderMoveDeconstruction(listOf(BuilderBlockPos(world.uid, 2, 64, 0)), false)
            BuilderUndoRules.exchangeFor(plan) shouldBe BuilderUndoExchange(plan.rewards, emptyList())
            (player.inventory.getItem(10)?.itemMeta as Damageable).damage shouldBe 0
            world.getBlockAt(0, 64, 0).type shouldBe Material.STONE
            world.getBlockAt(1, 64, 0).type shouldBe Material.DIRT
            world.getBlockAt(2, 64, 0).type shouldBe Material.DIAMOND_BLOCK
        }
    }

    test("identical occupied target states remain journaled and require declared deconstruction") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("move-identical")
            val player = paper.addPlayer("MoveIdentical")
            player.teleport(Location(world, 0.5, 64.0, 3.5))
            player.inventory.setItemInMainHand(ItemStack(Material.IRON_PICKAXE))
            val harness = MoveHarness(paper.createSimplePlugin("MoveIdentical"), world)
            harness.select(0, 64, 0, 0, 64, 0)
            (0..1).forEach { world.getBlockAt(it, 64, 0).type = Material.STONE }

            val plan = harness.controller.plan(player, BlockFace.EAST).awaitBuilderPlanning()

            plan.changes.size shouldBe 2
            plan.changes.single { it.position.x == 1 }.let { it.beforeBlockData shouldBe it.afterBlockData }
            plan.rewards.single().amount shouldBe 1
            plan.validated() shouldBe plan
            shouldThrow<IllegalArgumentException> { plan.copy(moveDeconstruction = null).validated() }
            shouldThrow<IllegalArgumentException> { plan.copy(changes = plan.changes.take(1)).validated() }
            val inverse = plan.copy(kind = BuilderPlanKind.UNDO, sourceRecordId = plan.id,
                changes = plan.changes.asReversed().map { BuilderBlockChange(it.position, it.afterBlockData, it.beforeBlockData) },
                costs = plan.rewards, rewards = emptyList(), toolFingerprintBase64 = null, toolDamage = 0, moveDeconstruction = null)
            inverse.validated() shouldBe inverse
        }
    }

    test("occupied move obeys tool capacity inventory capacity permission and bypass rules") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("move-material-guards")
            val player = paper.addPlayer("MoveGuards")
            player.teleport(Location(world, 0.5, 64.0, 3.5))
            val harness = MoveHarness(paper.createSimplePlugin("MoveGuards"), world)
            harness.select(0, 64, 0, 0, 64, 0)
            (0..1).forEach { world.getBlockAt(it, 64, 0).type = Material.STONE }
            shouldThrow<MoveFailure> { harness.controller.plan(player, BlockFace.EAST).awaitBuilderPlanning() }
                .path shouldBe "errors.tool"
            player.inventory.storageContents = Array(36) { ItemStack(Material.DIRT, 64) }
            player.inventory.setItem(10, ItemStack(Material.IRON_PICKAXE))
            shouldThrow<MoveFailure> { harness.controller.plan(player, BlockFace.EAST).awaitBuilderPlanning() }
                .path shouldBe "errors.inventory"
            player.inventory.clear()
            harness.withoutToolPermission = true
            val bypass = harness.controller.plan(player, BlockFace.EAST).awaitBuilderPlanning()
            BuilderDeconstructionToolPolicy.requiresBypass(player.gameMode, bypass) shouldBe true
            bypass.toolDamage shouldBe 0
            harness.deniedFeature = BuilderFeature.DECONSTRUCT
            shouldThrow<MoveFailure> { harness.controller.plan(player, BlockFace.EAST).awaitBuilderPlanning() }
                .path shouldBe "errors.no-permission"
            harness.createdPlans shouldBe 1
            (0..1).forEach { world.getBlockAt(it, 64, 0).type shouldBe Material.STONE }
        }
    }

    test("replaceable plants have no drops or tool wear and creative deconstruction yields no items") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("move-replaceable")
            val player = paper.addPlayer("MoveReplaceable")
            player.teleport(Location(world, 0.5, 64.0, 3.5))
            val harness = MoveHarness(paper.createSimplePlugin("MoveReplaceable"), world)
            harness.select(0, 64, 0, 0, 64, 0)
            world.getBlockAt(0, 64, 0).type = Material.STONE
            world.getBlockAt(1, 64, 0).type = Material.SHORT_GRASS
            val plant = harness.controller.plan(player, BlockFace.EAST).awaitBuilderPlanning()
            plant.rewards shouldBe emptyList()
            plant.toolDamage shouldBe 0
            BuilderDeconstructionToolPolicy.requiresBypass(player.gameMode, plant) shouldBe false
            player.gameMode = GameMode.CREATIVE
            world.getBlockAt(1, 64, 0).type = Material.DIAMOND_BLOCK
            val creative = harness.controller.plan(player, BlockFace.EAST).awaitBuilderPlanning()
            creative.rewards shouldBe emptyList()
            creative.toolDamage shouldBe 0
        }
    }

    test("unsafe source blocks stay excluded and cannot be overwritten by overlap") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("move-unsafe")
            val player = paper.addPlayer("MoveUnsafe")
            player.teleport(Location(world, 0.5, 64.0, 3.5))
            val harness = MoveHarness(paper.createSimplePlugin("MoveUnsafe"), world)
            harness.select(0, 64, 0, 2, 64, 0)
            world.getBlockAt(0, 64, 0).type = Material.STONE
            world.getBlockAt(1, 64, 0).type = Material.BEDROCK
            world.getBlockAt(2, 64, 0).type = Material.CHEST

            val plan = harness.controller.plan(player, BlockFace.SOUTH).awaitBuilderPlanning()
            plan.changes.size shouldBe 2
            plan.skippedUnsafeBlocks shouldBe 2
            plan.changes.any { it.position.x != 0 } shouldBe false
            shouldThrow<MoveFailure> { harness.controller.plan(player, BlockFace.EAST).awaitBuilderPlanning() }
                .path shouldBe "errors.move-occupied"
            world.getBlockAt(1, 64, 0).type shouldBe Material.BEDROCK
            world.getBlockAt(2, 64, 0).type shouldBe Material.CHEST
        }
    }

    test("move checks source removal and destination placement protection") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("move-protection")
            val player = paper.addPlayer("MoveProtected")
            player.teleport(Location(world, 0.5, 64.0, 3.5))
            val harness = MoveHarness(paper.createSimplePlugin("MoveProtected"), world)
            harness.select(0, 64, 0, 0, 64, 0)
            world.getBlockAt(0, 64, 0).type = Material.STONE
            harness.protectedPosition = BuilderBlockPos(world.uid, 1, 64, 0)
            shouldThrow<MoveFailure> { harness.controller.plan(player, BlockFace.EAST).awaitBuilderPlanning() }
                .path shouldBe "errors.protection"
            harness.mutableChecks shouldBe 2
            harness.placementMaterials shouldBe listOf(Material.STONE)
            harness.protectedPosition = BuilderBlockPos(world.uid, 0, 64, 0)
            shouldThrow<MoveFailure> { harness.controller.plan(player, BlockFace.EAST).awaitBuilderPlanning() }
                .path shouldBe "errors.protection"
            harness.createdPlans shouldBe 0
        }
    }

    test("move rejects unloaded destinations and its combined change bound") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("move-limits")
            val player = paper.addPlayer("MoveLimited")
            player.teleport(Location(world, 0.5, 64.0, 3.5))
            val harness = MoveHarness(paper.createSimplePlugin("MoveLimited"), world, maximumChanges = 1)
            harness.select(15, 64, 0, 15, 64, 0)
            world.getBlockAt(15, 64, 0).type = Material.STONE
            shouldThrow<MoveFailure> { harness.controller.plan(player, BlockFace.EAST).awaitBuilderPlanning() }
                .path shouldBe "errors.chunk-unloaded"
            shouldThrow<MoveFailure> { harness.controller.plan(player, BlockFace.WEST).awaitBuilderPlanning() }
                .path shouldBe "errors.selection-too-large"
            harness.createdPlans shouldBe 0
            world.getBlockAt(15, 64, 0).type shouldBe Material.STONE
        }
    }

    test("move captures yield through the shared native budget and detached computation") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("move-sliced")
            val player = paper.addPlayer("MoveSliced")
            player.teleport(Location(world, 0.5, 64.0, 0.5))
            val tasks = QueuedBuilderPlanningTasks()
            val harness = MoveHarness(paper.createSimplePlugin("MoveSliced"), world, maximumChanges = 512, tasks = tasks)
            harness.select(0, 64, 0, 15, 64, 15)
            world.loadChunk(1, 0)
            for (x in 0..15) for (z in 0..15) world.getBlockAt(x, 64, z).type = Material.STONE
            val pending = harness.controller.plan(player, BlockFace.EAST)
            pending.isDone shouldBe false
            tasks.tick()
            pending.isDone shouldBe false
            harness.mutableChecks shouldBe 128

            val plan = tasks.finish(pending)
            plan.changes.size shouldBe 32
            tasks.workerRuns shouldBe 2
            world.getBlockAt(0, 64, 0).type shouldBe Material.STONE
        }
    }

    test("selection changes cancel unfinished move capture") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("move-stale")
            val player = paper.addPlayer("MoveStale")
            player.teleport(Location(world, 0.5, 64.0, 0.5))
            val tasks = QueuedBuilderPlanningTasks()
            val harness = MoveHarness(paper.createSimplePlugin("MoveStale"), world, tasks = tasks)
            harness.select(0, 64, 0, 1, 64, 0)
            world.getBlockAt(0, 64, 0).type = Material.STONE
            val pending = harness.controller.plan(player, BlockFace.EAST)
            tasks.tick(1)
            harness.select(0, 64, 1, 1, 64, 1)
            tasks.tick()

            shouldThrow<java.util.concurrent.CancellationException> { pending.awaitBuilderPlanning() }
            harness.createdPlans shouldBe 0
        }
    }

    test("selection changes cancel sliced target drops without wearing tools or modifying either block") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("move-target-cancel")
            val player = paper.addPlayer("MoveTargetCancel")
            player.teleport(Location(world, 0.5, 64.0, 3.5))
            player.inventory.setItemInMainHand(ItemStack(Material.IRON_PICKAXE))
            val tasks = QueuedBuilderPlanningTasks()
            var dropsRead = false
            val harness = MoveHarness(paper.createSimplePlugin("MoveTargetCancel"), world, tasks = tasks,
                drops = { _, _ -> dropsRead = true; List(200) { ItemStack(Material.COBBLESTONE) } })
            harness.select(0, 64, 0, 0, 64, 0)
            (0..1).forEach { world.getBlockAt(it, 64, 0).type = Material.STONE }
            val pending = harness.controller.plan(player, BlockFace.EAST)
            repeat(3) { tasks.tick(); tasks.runComputations() }
            dropsRead shouldBe true
            pending.isDone shouldBe false
            harness.select(0, 64, 1, 0, 64, 1)
            tasks.tick()
            shouldThrow<java.util.concurrent.CancellationException> { pending.awaitBuilderPlanning() }
            harness.createdPlans shouldBe 0
            (player.inventory.itemInMainHand.itemMeta as Damageable).damage shouldBe 0
            (0..1).forEach { world.getBlockAt(it, 64, 0).type shouldBe Material.STONE }
        }
    }
})

private class MoveHarness(
    plugin: Plugin,
    private val world: World,
    maximumChanges: Int = 64,
    private val tasks: BuilderPlanningTasks = ImmediateBuilderPlanningTasks,
    drops: (Block, ItemStack?) -> Collection<ItemStack> = { block, _ -> listOfNotNull(BuilderPlacementCost.itemOrNull(block.blockData)) },
) {
    private var selection: BuilderSelection? = null
    val permissionChecks = mutableListOf<BuilderFeature>()
    val placementMaterials = mutableListOf<Material>()
    var protectedPosition: BuilderBlockPos? = null
    var mutableChecks = 0
    var createdPlans = 0
    var withoutToolPermission = false
    var deniedFeature: BuilderFeature? = null
    private val safety = BuilderBlockSafety(plugin, setOf("AIR", "SHORT_GRASS"))
    private val deconstruction = BuilderDeconstructionController(safety, maximumChanges,
        object : BuilderDeconstructionHost, BuilderPlanningTasks by tasks {
            override fun ensurePermission(player: Player) {
                if (deniedFeature == BuilderFeature.DECONSTRUCT) fail("errors.no-permission")
            }
            override fun canDeconstructWithoutTool(player: Player) = withoutToolPermission
            override fun requiredSelection(player: Player) = checkNotNull(selection)
            override fun world(worldId: UUID): World = checkNotNull(Bukkit.getWorld(worldId))
            override fun ensureMutable(player: Player, block: Block) {
                (tasks as? QueuedBuilderPlanningTasks)?.assertMain()
                if (BuilderBlockPos(world.uid, block.x, block.y, block.z) == protectedPosition) fail("errors.protection")
            }
            override fun createPlan(player: Player, changes: List<BuilderBlockChange>, rewards: List<BuilderItemAmount>,
                toolFingerprint: String?, toolDamage: Int, skippedUnsafeBlocks: Int): BuilderPlan = error("MOVE uses capture only")
            override fun fail(path: String): Nothing = throw MoveFailure(path)
        }, isPreferredTool = { _, tool -> tool.type.name.endsWith("_PICKAXE") },
        blockDrops = { block, tool, _ ->
            (tasks as? QueuedBuilderPlanningTasks)?.assertMain()
            drops(block, tool)
        },
    )
    val controller = BuilderMoveController(
        safety, maximumChanges,
        object : BuilderMoveHost, BuilderPlanningTasks by tasks {
            override fun ensurePermission(player: Player, feature: BuilderFeature) {
                permissionChecks += feature
                if (feature == deniedFeature) fail("errors.no-permission")
            }
            override fun requiredSelection(player: Player) = checkNotNull(selection)
            override fun world(worldId: UUID): World = checkNotNull(Bukkit.getWorld(worldId))
            override fun placementData(material: Material): BlockData = material.createBlockData()
            override fun ensureMutable(player: Player, block: Block, placing: Material?) {
                (tasks as? QueuedBuilderPlanningTasks)?.assertMain()
                mutableChecks++
                if (BuilderBlockPos(world.uid, block.x, block.y, block.z) == protectedPosition) fail("errors.protection")
            }
            override fun ensurePlacement(player: Player, block: Block, material: Material) {
                placementMaterials += material
                ensureMutable(player, block, material)
            }
            override fun createPlan(
                player: Player, kind: BuilderPlanKind, changes: List<BuilderBlockChange>,
                costs: List<BuilderItemAmount>, rewards: List<BuilderItemAmount>, skippedUnsafeBlocks: Int,
            ): BuilderPlan {
                (tasks as? QueuedBuilderPlanningTasks)?.assertMain()
                createdPlans++
                return BuilderPlan(UUID.randomUUID(), player.uniqueId, kind, changes, costs, rewards,
                    createdAtMillis = 1L, expiresAtMillis = 2L, skippedUnsafeBlocks = skippedUnsafeBlocks).validated(maximumChanges)
            }
            override fun createMovePlanAsync(player: Player, changes: List<BuilderBlockChange>,
                deconstruction: BuilderDeconstructionCapture?, displaced: List<BuilderBlockPos>,
                skippedUnsafeBlocks: Int): java.util.concurrent.CompletableFuture<BuilderPlan> {
                (tasks as? QueuedBuilderPlanningTasks)?.assertMain()
                createdPlans++
                return java.util.concurrent.CompletableFuture.completedFuture(BuilderPlan(
                    UUID.randomUUID(), player.uniqueId, BuilderPlanKind.MOVE, changes, emptyList(), deconstruction?.rewards.orEmpty(),
                    toolFingerprintBase64 = deconstruction?.toolFingerprint, toolDamage = deconstruction?.toolDamage ?: 0,
                    createdAtMillis = 1L, expiresAtMillis = 2L, skippedUnsafeBlocks = skippedUnsafeBlocks,
                    moveDeconstruction = displaced.takeIf { it.isNotEmpty() }?.let {
                        BuilderMoveDeconstruction(it, deconstruction?.toolBypassUsed == true)
                    },
                ).validated(maximumChanges))
            }
            override fun fail(path: String): Nothing = throw MoveFailure(path)
        },
        deconstruction,
    )

    fun select(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int) {
        for (x in (minOf(x1, x2) shr 4)..(maxOf(x1, x2) shr 4)) {
            for (z in (minOf(z1, z2) shr 4)..(maxOf(z1, z2) shr 4)) world.loadChunk(x, z)
        }
        selection = BuilderSelection(BuilderBlockPos(world.uid, x1, y1, z1), BuilderBlockPos(world.uid, x2, y2, z2))
            .validated(100, 10_000L)
    }
}

private class MoveFailure(val path: String) : RuntimeException(path)
