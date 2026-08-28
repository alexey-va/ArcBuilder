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
            val survival = harness.controller.plan(player, Material.STONE)
            survival.kind shouldBe BuilderPlanKind.FILL
            survival.changes.map { it.position.x } shouldContainExactly listOf(0, 1)
            survival.costs.map { it.materialKey to it.amount } shouldContainExactly listOf("minecraft:stone" to 2)

            player.gameMode = GameMode.CREATIVE
            val creative = harness.controller.plan(player, Material.STONE)
            creative.changes.map { it.position.x } shouldContainExactly listOf(0, 1)
            creative.costs shouldBe emptyList()
            harness.permissionChecks shouldBe 2
            harness.mutableChecks shouldBe 4
            world.getBlockAt(0, 64, 0).type shouldBe Material.AIR
            world.getBlockAt(1, 64, 0).type shouldBe Material.SHORT_GRASS
            world.getBlockAt(2, 64, 0).type shouldBe Material.STONE
            world.getBlockAt(3, 64, 0).type shouldBe Material.DEEPSLATE
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

            shouldThrow<FillFailure> { harness.controller.plan(player, Material.OAK_PLANKS) }.path shouldBe "errors.protection"
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

            shouldThrow<FillFailure> { harness.controller.plan(player, Material.GLASS) }.path shouldBe
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

            shouldThrow<FillFailure> { harness.controller.plan(player, Material.STONE) }.path shouldBe
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
) {
    private var selection: BuilderSelection? = null
    var permissionChecks = 0
    var mutableChecks = 0
    var createdPlans = 0

    private val safety = BuilderBlockSafety(plugin, setOf("AIR", "SHORT_GRASS"))
    val controller = BuilderFillController(
        safety = safety,
        maximumChanges = maximumChanges,
        host = object : BuilderFillHost {
            override fun ensurePermission(player: Player) {
                permissionChecks++
            }

            override fun requiredSelection(player: Player): BuilderSelection =
                selection ?: throw FillFailure("errors.selection-missing")

            override fun world(worldId: UUID): World =
                Bukkit.getWorld(worldId) ?: throw FillFailure("errors.world-not-allowed")

            override fun placementData(material: Material): BlockData = material.createBlockData()

            override fun ensureMutable(player: Player, block: Block) {
                mutableChecks++
                if (!mutable) throw FillFailure("errors.protection")
            }

            override fun createPlan(
                player: Player,
                changes: List<BuilderBlockChange>,
                costs: List<BuilderItemAmount>,
            ): BuilderPlan {
                createdPlans++
                return BuilderPlan(
                    id = UUID.randomUUID(),
                    playerId = player.uniqueId,
                    kind = BuilderPlanKind.FILL,
                    changes = changes,
                    costs = costs,
                    rewards = emptyList(),
                    createdAtMillis = 1_800_000_000_000L,
                    expiresAtMillis = 1_800_000_030_000L,
                ).validated(maximumChanges)
            }

            override fun fail(path: String): Nothing = throw FillFailure(path)
        },
    )

    fun select(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int) {
        selection = BuilderSelection(
            BuilderBlockPos(world.uid, x1, y1, z1),
            BuilderBlockPos(world.uid, x2, y2, z2),
        ).validated(maxAxis = 100, maxScanVolume = 10_000L)
    }
}

private class FillFailure(val path: String) : RuntimeException(path)
