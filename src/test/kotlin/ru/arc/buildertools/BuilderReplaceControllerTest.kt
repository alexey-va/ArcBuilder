package ru.arc.buildertools

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.type.Stairs
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class BuilderReplaceControllerTest : FunSpec({
    test("air replacement retains every original air variant and only target costs without requiring inventory stock") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("replace-air-async")
            val player = paper.addPlayer("AsyncAirReplace")
            player.teleport(Location(world, .5, 64.0, 3.5))
            player.gameMode = GameMode.SURVIVAL
            val originals = listOf(Material.AIR, Material.CAVE_AIR, Material.VOID_AIR)
            originals.forEachIndexed { index, material -> world.getBlockAt(index, 64, 0).type = material }
            world.getBlockAt(3, 64, 0).type = Material.DIRT
            val tasks = QueuedBuilderPlanningTasks()
            val harness = ReplaceHarness(paper.createSimplePlugin("AsyncAirReplace"), world, tasks = tasks)
            harness.select(0, 64, 0, 3, 64, 0)
            val plan = tasks.finish(harness.controller.plan(player, Material.AIR, Material.STONE))
            plan.changes.map(BuilderBlockChange::beforeBlockData) shouldBe originals.map { it.createBlockData().asString }
            plan.costs.map { it.materialKey to it.amount } shouldBe listOf("minecraft:stone" to 3)
            plan.rewards shouldBe emptyList()
            originals.forEachIndexed { index, material -> world.getBlockAt(index, 64, 0).type shouldBe material }
        }
    }

    test("replacement aggregates detached items on a worker and returns to main before plan creation") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("replace-async")
            val player = paper.addPlayer("AsyncReplace")
            player.teleport(Location(world, 0.5, 64.0, 2.5))
            world.getBlockAt(0, 64, 0).type = Material.OAK_PLANKS
            world.getBlockAt(1, 64, 0).type = Material.OAK_PLANKS
            val tasks = QueuedBuilderPlanningTasks()
            val harness = ReplaceHarness(paper.createSimplePlugin("AsyncReplace"), world, tasks = tasks)
            harness.select(0, 64, 0, 1, 64, 0)
            val pending = harness.controller.plan(player, Material.OAK_PLANKS, Material.SPRUCE_PLANKS)

            pending.isDone shouldBe false
            tasks.tick()
            pending.isDone shouldBe false
            harness.createdPlans shouldBe 0
            val plan = tasks.finish(pending)

            tasks.workerRuns shouldBe 1
            plan.costs.map { it.materialKey to it.amount } shouldBe listOf("minecraft:spruce_planks" to 2)
            plan.rewards.map { it.materialKey to it.amount } shouldBe listOf("minecraft:oak_planks" to 2)
            world.getBlockAt(0, 64, 0).type shouldBe Material.OAK_PLANKS
        }
    }

    test("replace preserves compatible block state and plans the exact survival exchange without mutation") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("BuilderReplaceTest")
            val world = paper.addSimpleWorld("replace")
            val player = paper.addPlayer("ReplaceOwner")
            player.teleport(Location(world, 0.5, 64.0, 2.5))
            player.gameMode = GameMode.SURVIVAL

            val oak = Material.OAK_STAIRS.createBlockData() as Stairs
            oak.facing = BlockFace.WEST
            oak.half = Bisected.Half.TOP
            oak.shape = Stairs.Shape.INNER_LEFT
            world.getBlockAt(0, 64, 0).setBlockData(oak, false)
            world.getBlockAt(1, 64, 0).type = Material.OAK_PLANKS
            val harness = ReplaceHarness(plugin, world)
            harness.select(0, 64, 0, 1, 64, 0)
            val actual = world.getBlockAt(0, 64, 0)
            actual.type shouldBe Material.OAK_STAIRS
            harness.safety.isSafeExisting(actual) shouldBe true
            BuilderPlacementCost.itemOrNull(actual.blockData)?.type shouldBe Material.OAK_STAIRS

            val survival = harness.controller.plan(player, Material.OAK_STAIRS, Material.SPRUCE_STAIRS).awaitBuilderPlanning()

            survival.kind shouldBe BuilderPlanKind.REPLACE
            survival.changes.map { it.position.x } shouldContainExactly listOf(0)
            val replacement = Bukkit.createBlockData(survival.changes.single().afterBlockData) as Stairs
            replacement.material shouldBe Material.SPRUCE_STAIRS
            replacement.facing shouldBe BlockFace.WEST
            replacement.half shouldBe Bisected.Half.TOP
            replacement.shape shouldBe Stairs.Shape.INNER_LEFT
            survival.costs.map { it.materialKey to it.amount } shouldContainExactly
                listOf("minecraft:spruce_stairs" to 1)
            survival.rewards.map { it.materialKey to it.amount } shouldContainExactly
                listOf("minecraft:oak_stairs" to 1)

            player.gameMode = GameMode.CREATIVE
            val creative = harness.controller.plan(player, Material.OAK_STAIRS, Material.SPRUCE_STAIRS).awaitBuilderPlanning()
            creative.costs shouldBe emptyList()
            creative.rewards shouldBe emptyList()
            world.getBlockAt(0, 64, 0).blockData.asString shouldBe oak.asString
            world.getBlockAt(1, 64, 0).type shouldBe Material.OAK_PLANKS
        }
    }

    test("replace skips unsafe matching blocks instead of treating containers as construction items") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("BuilderReplaceUnsafe")
            val world = paper.addSimpleWorld("replace-unsafe")
            val player = paper.addPlayer("UnsafeReplacer")
            world.getBlockAt(0, 64, 0).type = Material.CHEST
            val harness = ReplaceHarness(plugin, world)
            harness.select(0, 64, 0, 0, 64, 0)

            shouldThrow<ReplaceFailure> {
                harness.controller.plan(player, Material.CHEST, Material.STONE).awaitBuilderPlanning()
            }.path shouldBe "errors.nothing-to-change"
            harness.createdPlans shouldBe 0
            harness.mutableChecks shouldBe 0
            world.getBlockAt(0, 64, 0).type shouldBe Material.CHEST
        }
    }

    test("replace skips coupled multi-block structures instead of changing only one half") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("BuilderReplaceCoupled")
            val world = paper.addSimpleWorld("replace-coupled")
            val player = paper.addPlayer("DoorReplacer")
            world.getBlockAt(0, 64, 0).type = Material.OAK_DOOR
            val harness = ReplaceHarness(plugin, world)
            harness.select(0, 64, 0, 0, 64, 0)

            shouldThrow<ReplaceFailure> {
                harness.controller.plan(player, Material.OAK_DOOR, Material.STONE).awaitBuilderPlanning()
            }.path shouldBe "errors.nothing-to-change"
            harness.createdPlans shouldBe 0
            harness.mutableChecks shouldBe 0
            world.getBlockAt(0, 64, 0).type shouldBe Material.OAK_DOOR
        }
    }

    test("replace rejects a coupled multi-block target instead of placing incomplete structures") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("BuilderReplaceCoupledTarget")
            val world = paper.addSimpleWorld("replace-coupled-target")
            val player = paper.addPlayer("DoorTargetReplacer")
            world.getBlockAt(0, 64, 0).type = Material.STONE
            val harness = ReplaceHarness(plugin, world)
            harness.select(0, 64, 0, 0, 64, 0)

            shouldThrow<ReplaceFailure> {
                harness.controller.plan(player, Material.STONE, Material.OAK_DOOR).awaitBuilderPlanning()
            }.path shouldBe "errors.material"
            harness.createdPlans shouldBe 0
            harness.mutableChecks shouldBe 0
            world.getBlockAt(0, 64, 0).type shouldBe Material.STONE
        }
    }

    test("replace enforces its exact change bound before creating a plan") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("BuilderReplaceBound")
            val world = paper.addSimpleWorld("replace-bound")
            val player = paper.addPlayer("BoundReplacer")
            world.getBlockAt(0, 64, 0).type = Material.STONE
            world.getBlockAt(1, 64, 0).type = Material.STONE
            val harness = ReplaceHarness(plugin, world, maximumChanges = 1)
            harness.select(0, 64, 0, 1, 64, 0)

            shouldThrow<ReplaceFailure> {
                harness.controller.plan(player, Material.STONE, Material.DEEPSLATE).awaitBuilderPlanning()
            }.path shouldBe "errors.selection-too-large"
            harness.createdPlans shouldBe 0
            world.getBlockAt(0, 64, 0).type shouldBe Material.STONE
            world.getBlockAt(1, 64, 0).type shouldBe Material.STONE
        }
    }
})

private class ReplaceHarness(
    plugin: Plugin,
    private val world: World,
    maximumChanges: Int = 64,
    private val tasks: BuilderPlanningTasks = ImmediateBuilderPlanningTasks,
) {
    private var selection: BuilderSelection? = null
    var mutableChecks = 0
    var createdPlans = 0

    val safety = BuilderBlockSafety(plugin, emptySet())

    val controller = BuilderReplaceController(
        safety = safety,
        maximumChanges = maximumChanges,
        host = object : BuilderPlanningHost, BuilderPlanningTasks by tasks {
            override fun ensurePermission(player: Player, feature: BuilderFeature) {
                feature shouldBe BuilderFeature.REPLACE
            }

            override fun requiredSelection(player: Player): BuilderSelection =
                selection ?: throw ReplaceFailure("errors.selection-missing")

            override fun world(worldId: UUID): World =
                Bukkit.getWorld(worldId) ?: throw ReplaceFailure("errors.world-not-allowed")

            override fun placementData(material: Material): BlockData = material.createBlockData()

            override fun ensureMutable(player: Player, block: Block, placing: Material?) {
                (tasks as? QueuedBuilderPlanningTasks)?.assertMain()
                mutableChecks++
            }

            override fun createPlan(
                player: Player,
                kind: BuilderPlanKind,
                changes: List<BuilderBlockChange>,
                costs: List<BuilderItemAmount>,
                rewards: List<BuilderItemAmount>,
                skippedUnsafeBlocks: Int,
            ): BuilderPlan {
                (tasks as? QueuedBuilderPlanningTasks)?.assertMain()
                createdPlans++
                return BuilderPlan(
                    id = UUID.randomUUID(),
                    playerId = player.uniqueId,
                    kind = kind,
                    changes = changes,
                    costs = costs,
                    rewards = rewards,
                    skippedUnsafeBlocks = skippedUnsafeBlocks,
                    createdAtMillis = 1_800_000_000_000L,
                    expiresAtMillis = 1_800_000_030_000L,
                ).validated(maximumChanges)
            }

            override fun fail(path: String): Nothing = throw ReplaceFailure(path)
        },
    )

    fun select(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int) {
        for (x in (minOf(x1, x2) shr 4)..(maxOf(x1, x2) shr 4)) {
            for (z in (minOf(z1, z2) shr 4)..(maxOf(z1, z2) shr 4)) world.loadChunk(x, z)
        }
        selection = BuilderSelection(
            BuilderBlockPos(world.uid, x1, y1, z1),
            BuilderBlockPos(world.uid, x2, y2, z2),
        ).validated(maxAxis = 100, maxScanVolume = 10_000L)
    }
}

private class ReplaceFailure(val path: String) : RuntimeException(path)
