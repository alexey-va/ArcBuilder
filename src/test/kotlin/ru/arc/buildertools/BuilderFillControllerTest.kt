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
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class BuilderFillControllerTest : FunSpec({
    test("large fill yields between native capture ticks and preserves exact costs") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("fill-sliced")
            val player = paper.addPlayer("SlicedFill")
            player.teleport(Location(world, 0.5, 64.0, 0.5))
            val tasks = QueuedBuilderPlanningTasks()
            val harness = FillHarness(paper.createSimplePlugin("SlicedFill"), world, maximumChanges = 256, tasks = tasks)
            harness.select(0, 64, 0, 15, 64, 15)

            val pending = harness.controller.plan(player, Material.STONE)
            pending.isDone shouldBe false
            harness.mutableChecks shouldBe 0
            tasks.tick()
            pending.isDone shouldBe false
            harness.mutableChecks shouldBe 128
            val plan = tasks.finish(pending)

            plan.changes.size shouldBe 256
            plan.costs.single().amount shouldBe 256
            world.getBlockAt(0, 64, 0).type shouldBe Material.AIR
        }
    }

    test("selection replacement cancels an unfinished fill without producing a plan") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("fill-stale")
            val player = paper.addPlayer("StaleFill")
            player.teleport(Location(world, 0.5, 64.0, 0.5))
            val tasks = QueuedBuilderPlanningTasks()
            val harness = FillHarness(paper.createSimplePlugin("StaleFill"), world, tasks = tasks)
            harness.select(0, 64, 0, 8, 64, 0)
            val pending = harness.controller.plan(player, Material.STONE)
            tasks.tick(1)
            harness.select(10, 64, 0, 10, 64, 0)
            tasks.tick()

            shouldThrow<java.util.concurrent.CancellationException> { pending.awaitBuilderPlanning() }
            harness.mutableChecks shouldBe 1
            harness.createdPlans shouldBe 0
        }
    }

    test("fill plans exact survival costs and stays free in creative without mutating the world") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("BuilderFillModesTest")
            val world = paper.addSimpleWorld("fill-modes")
            val player = paper.addPlayer("FillOwner")
            player.teleport(Location(world, 0.5, 64.0, 2.5))
            world.getBlockAt(1, 64, 0).type = Material.SHORT_GRASS
            world.getBlockAt(2, 64, 0).type = Material.STONE
            world.getBlockAt(3, 64, 0).type = Material.DEEPSLATE
            val harness = FillHarness(plugin, world, maximumChanges = 4)
            harness.select(0, 64, 0, 3, 64, 0)

            player.gameMode = GameMode.SURVIVAL
            val survival = harness.controller.plan(player, Material.STONE).awaitBuilderPlanning()
            survival.kind shouldBe BuilderPlanKind.FILL
            survival.changes.map { it.position.x } shouldContainExactly listOf(0, 1)
            survival.costs.map { it.materialKey to it.amount } shouldContainExactly listOf("minecraft:stone" to 2)

            player.gameMode = GameMode.CREATIVE
            val creative = harness.controller.plan(player, Material.STONE).awaitBuilderPlanning()
            creative.changes.map { it.position.x } shouldContainExactly listOf(0, 1)
            creative.costs shouldBe emptyList()
            harness.permissionChecks shouldBe 2
            harness.mutableChecks shouldBe 4
            harness.placementMaterials shouldContainExactly List(4) { Material.STONE }
            world.getBlockAt(0, 64, 0).type shouldBe Material.AIR
            world.getBlockAt(1, 64, 0).type shouldBe Material.SHORT_GRASS
            world.getBlockAt(2, 64, 0).type shouldBe Material.STONE
            world.getBlockAt(3, 64, 0).type shouldBe Material.DEEPSLATE
        }
    }

    test("fill charges construction items for wall torches and redstone wire") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("fill-items")
            val player = paper.addPlayer("WireBuilder")
            player.gameMode = GameMode.SURVIVAL
            val harness = FillHarness(paper.createSimplePlugin("FillItems"), world)
            harness.select(0, 64, 0, 1, 64, 0)
            listOf(Material.WALL_TORCH to "minecraft:torch", Material.REDSTONE_WALL_TORCH to "minecraft:redstone_torch",
                Material.REDSTONE_WIRE to "minecraft:redstone").forEach { (block, item) ->
                harness.controller.plan(player, block).awaitBuilderPlanning().costs.map { it.materialKey to it.amount } shouldBe listOf(item to 2)
            }
        }
    }

    test("fill rejects a Lands protection failure before producing a plan or changing blocks") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("BuilderFillProtectionTest")
            val world = paper.addSimpleWorld("fill-protection")
            val player = paper.addPlayer("ProtectedFillOwner")
            player.teleport(Location(world, 0.5, 64.0, 2.5))
            val harness = FillHarness(plugin, world, mutable = false)
            harness.select(0, 64, 0, 0, 64, 0)

            shouldThrow<FillFailure> { harness.controller.plan(player, Material.OAK_PLANKS).awaitBuilderPlanning() }.path shouldBe "errors.protection"
            harness.createdPlans shouldBe 0
            world.getBlockAt(0, 64, 0).type shouldBe Material.AIR
        }
    }

    test("fill enforces its exact change bound and leaves the world untouched") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("BuilderFillBoundTest")
            val world = paper.addSimpleWorld("fill-bound")
            val player = paper.addPlayer("BoundedFillOwner")
            player.teleport(Location(world, 0.5, 64.0, 2.5))
            val harness = FillHarness(plugin, world, maximumChanges = 1)
            harness.select(0, 64, 0, 1, 64, 0)

            shouldThrow<FillFailure> { harness.controller.plan(player, Material.GLASS).awaitBuilderPlanning() }.path shouldBe
                "errors.selection-too-large"
            harness.createdPlans shouldBe 0
            world.getBlockAt(0, 64, 0).type shouldBe Material.AIR
            world.getBlockAt(1, 64, 0).type shouldBe Material.AIR
        }
    }

    test("fill reports nothing to change when every target is equal or non-replaceable") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("BuilderFillEmptyTest")
            val world = paper.addSimpleWorld("fill-empty")
            val player = paper.addPlayer("EmptyFillOwner")
            player.teleport(Location(world, 0.5, 64.0, 2.5))
            world.getBlockAt(0, 64, 0).type = Material.STONE
            world.getBlockAt(1, 64, 0).type = Material.DEEPSLATE
            val harness = FillHarness(plugin, world)
            harness.select(0, 64, 0, 1, 64, 0)

            shouldThrow<FillFailure> { harness.controller.plan(player, Material.STONE).awaitBuilderPlanning() }.path shouldBe
                "errors.nothing-to-change"
            harness.mutableChecks shouldBe 0
            harness.createdPlans shouldBe 0
        }
    }
})

private class FillHarness(
    plugin: Plugin,
    private val world: World,
    maximumChanges: Int = 64,
    private val mutable: Boolean = true,
    private val tasks: BuilderPlanningTasks = ImmediateBuilderPlanningTasks,
) {
    private var selection: BuilderSelection? = null
    var permissionChecks = 0
    var mutableChecks = 0
    val placementMaterials = mutableListOf<Material>()
    var createdPlans = 0

    private val safety = BuilderBlockSafety(plugin, setOf("AIR", "SHORT_GRASS"))
    val controller = BuilderFillController(
        safety = safety,
        maximumChanges = maximumChanges,
        host = object : BuilderPlanningHost, BuilderPlanningTasks by tasks {
            override fun ensurePermission(player: Player, feature: BuilderFeature) {
                feature shouldBe BuilderFeature.FILL
                permissionChecks++
            }

            override fun requiredSelection(player: Player): BuilderSelection =
                selection ?: throw FillFailure("errors.selection-missing")

            override fun world(worldId: UUID): World =
                Bukkit.getWorld(worldId) ?: throw FillFailure("errors.world-not-allowed")

            override fun placementData(material: Material): BlockData = material.createBlockData()

            override fun ensureMutable(player: Player, block: Block, placing: Material?) {
                (tasks as? QueuedBuilderPlanningTasks)?.assertMain()
                mutableChecks++
                if (!mutable) throw FillFailure("errors.protection")
            }

            override fun ensurePlacement(player: Player, block: Block, material: Material) {
                placementMaterials += material
                ensureMutable(player, block)
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

            override fun fail(path: String): Nothing = throw FillFailure(path)
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

private class FillFailure(val path: String) : RuntimeException(path)
