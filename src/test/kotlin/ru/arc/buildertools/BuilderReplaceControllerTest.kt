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

            val survival = harness.controller.plan(player, Material.OAK_STAIRS, Material.SPRUCE_STAIRS)

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
            val creative = harness.controller.plan(player, Material.OAK_STAIRS, Material.SPRUCE_STAIRS)
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
                harness.controller.plan(player, Material.CHEST, Material.STONE)
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
                harness.controller.plan(player, Material.OAK_DOOR, Material.STONE)
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
                harness.controller.plan(player, Material.STONE, Material.OAK_DOOR)
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
                harness.controller.plan(player, Material.STONE, Material.DEEPSLATE)
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
) {
    private var selection: BuilderSelection? = null
    var mutableChecks = 0
    var createdPlans = 0

    val safety = BuilderBlockSafety(plugin, emptySet())

    val controller = BuilderReplaceController(
        safety = safety,
        maximumChanges = maximumChanges,
        host = object : BuilderReplaceHost {
            override fun ensurePermission(player: Player) = Unit

            override fun requiredSelection(player: Player): BuilderSelection =
                selection ?: throw ReplaceFailure("errors.selection-missing")

            override fun world(worldId: UUID): World =
                Bukkit.getWorld(worldId) ?: throw ReplaceFailure("errors.world-not-allowed")

            override fun placementData(material: Material): BlockData = material.createBlockData()

            override fun ensurePlacement(player: Player, block: Block, material: Material) {
                mutableChecks++
            }

            override fun createPlan(
                player: Player,
                changes: List<BuilderBlockChange>,
                costs: List<BuilderItemAmount>,
                rewards: List<BuilderItemAmount>,
                skippedUnsafeBlocks: Int,
            ): BuilderPlan {
                createdPlans++
                return BuilderPlan(
                    id = UUID.randomUUID(),
                    playerId = player.uniqueId,
                    kind = BuilderPlanKind.REPLACE,
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
        selection = BuilderSelection(
            BuilderBlockPos(world.uid, x1, y1, z1),
            BuilderBlockPos(world.uid, x2, y2, z2),
        ).validated(maxAxis = 100, maxScanVolume = 10_000L)
    }
}

private class ReplaceFailure(val path: String) : RuntimeException(path)
