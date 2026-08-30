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
import org.bukkit.block.data.type.Slab
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.plugin.Plugin
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class BuilderDeconstructionControllerTest : FunSpec({
    test("refund policy accepts only the exact Silk Touch construction item") {
        MockBukkitTestRuntime.open().use { paper ->
            val slab = paper.server.createBlockData(Material.STONE_SLAB) as Slab
            slab.type = Slab.Type.DOUBLE

            BuilderDeconstructionRefunds.exactConstructionItem(slab, listOf(ItemStack(Material.STONE_SLAB, 2)))
                ?.let { it.type to it.amount } shouldBe (Material.STONE_SLAB to 2)
            BuilderDeconstructionRefunds.exactConstructionItem(
                Material.DIAMOND_ORE.createBlockData(),
                listOf(ItemStack(Material.DIAMOND, 4)),
            ) shouldBe null
            BuilderDeconstructionRefunds.exactConstructionItem(
                Material.BUDDING_AMETHYST.createBlockData(),
                emptyList(),
            ) shouldBe null
            BuilderDeconstructionRefunds.exactConstructionItem(
                Material.STONE.createBlockData(),
                listOf(ItemStack(Material.STONE, 2)),
            ) shouldBe null
        }
    }

    test("survival skips reward ores and refunds other construction items without Fortune amplification") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("BuilderDeconstructionRefundTest")
            val world = paper.addSimpleWorld("deconstruction-refund")
            val player = paper.addPlayer("DeconstructionOwner")
            player.teleport(Location(world, 0.5, 64.0, 2.5))
            world.getBlockAt(0, 64, 0).type = Material.DIAMOND_ORE
            val slab = paper.server.createBlockData(Material.STONE_SLAB) as Slab
            slab.type = Slab.Type.DOUBLE
            world.getBlockAt(1, 64, 0).blockData = slab
            val harness = DeconstructionHarness(plugin)
            harness.select(world, 0, 64, 0, 1, 64, 0)
            val tool = ItemStack(Material.DIAMOND_PICKAXE).apply {
                editMeta { meta ->
                    meta.addEnchant(Enchantment.FORTUNE, 3, true)
                    (meta as Damageable).damage = 7
                }
            }
            player.inventory.setItemInMainHand(tool)

            val first = harness.controller.plan(player)
            val second = harness.controller.plan(player)

            first.kind shouldBe BuilderPlanKind.DECONSTRUCT
            first.changes.size shouldBe 1
            first.rewards.materialAmounts() shouldBe mapOf(Material.STONE_SLAB to 2)
            first.skippedUnsafeBlocks shouldBe 1
            second.rewards shouldBe first.rewards
            first.toolDamage shouldBe 1
            BuilderItemCodec.decodePrototype(checkNotNull(first.toolFingerprintBase64)).isSimilar(tool) shouldBe true
            harness.permissions shouldBe 2
            harness.mutableBlocks shouldBe 2
            world.getBlockAt(0, 64, 0).type shouldBe Material.DIAMOND_ORE
            (world.getBlockAt(1, 64, 0).blockData as Slab).type shouldBe Slab.Type.DOUBLE
        }
    }

    test("creative deconstruction needs no tool and never creates inventory value") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("BuilderDeconstructionCreativeTest")
            val world = paper.addSimpleWorld("deconstruction-creative")
            val player = paper.addPlayer("CreativeDeconstructionOwner")
            player.gameMode = GameMode.CREATIVE
            player.teleport(Location(world, 0.5, 64.0, 2.5))
            world.getBlockAt(0, 64, 0).type = Material.STONE
            val harness = DeconstructionHarness(plugin)
            harness.select(world, 0, 64, 0, 0, 64, 0)

            val plan = harness.controller.plan(player)

            plan.changes.size shouldBe 1
            plan.rewards shouldBe emptyList()
            plan.toolFingerprintBase64 shouldBe null
            plan.toolDamage shouldBe 0
            world.getBlockAt(0, 64, 0).type shouldBe Material.STONE
        }
    }

    test("planning fails closed on an unsuitable tool and on the exact change bound") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("BuilderDeconstructionBoundsTest")
            val world = paper.addSimpleWorld("deconstruction-bounds")
            val player = paper.addPlayer("BoundedDeconstructionOwner")
            player.teleport(Location(world, 0.5, 64.0, 2.5))
            world.getBlockAt(0, 64, 0).type = Material.STONE
            world.getBlockAt(1, 64, 0).type = Material.STONE
            val harness = DeconstructionHarness(plugin, maximumChanges = 1)
            harness.select(world, 0, 64, 0, 1, 64, 0)

            player.inventory.setItemInMainHand(ItemStack(Material.STICK))
            shouldThrow<DeconstructionFailure> { harness.controller.plan(player) }.path shouldBe "errors.tool"

            player.inventory.setItemInMainHand(ItemStack(Material.DIAMOND_PICKAXE))
            shouldThrow<DeconstructionFailure> { harness.controller.plan(player) }.path shouldBe "errors.selection-too-large"
            world.getBlockAt(0, 64, 0).type shouldBe Material.STONE
            world.getBlockAt(1, 64, 0).type shouldBe Material.STONE
        }
    }
})

private class DeconstructionHarness(
    plugin: Plugin,
    maximumChanges: Int = 64,
) {
    private lateinit var selection: BuilderSelection
    private val safety = BuilderBlockSafety(plugin, setOf("AIR", "SHORT_GRASS"))
    var permissions = 0
    var mutableBlocks = 0

    val controller = BuilderDeconstructionController(
        safety = safety,
        maximumChanges = maximumChanges,
        isPreferredTool = { _, tool -> tool.type == Material.DIAMOND_PICKAXE },
        constructionRefund = { block -> BuilderPlacementCost.itemOrNull(block.blockData) },
        host = object : BuilderDeconstructionHost {
            override fun ensurePermission(player: Player) {
                permissions++
            }

            override fun requiredSelection(player: Player): BuilderSelection = selection

            override fun world(worldId: UUID): World =
                Bukkit.getWorld(worldId) ?: throw DeconstructionFailure("errors.world-not-allowed")

            override fun ensureMutable(player: Player, block: Block) {
                mutableBlocks++
            }

            override fun createPlan(
                player: Player,
                changes: List<BuilderBlockChange>,
                rewards: List<BuilderItemAmount>,
                toolFingerprint: String?,
                toolDamage: Int,
                skippedUnsafeBlocks: Int,
            ): BuilderPlan {
                val now = 1_800_000_000_000L
                return BuilderPlan(
                    id = UUID.randomUUID(),
                    playerId = player.uniqueId,
                    kind = BuilderPlanKind.DECONSTRUCT,
                    changes = changes,
                    costs = emptyList(),
                    rewards = rewards,
                    toolFingerprintBase64 = toolFingerprint,
                    toolDamage = toolDamage,
                    skippedUnsafeBlocks = skippedUnsafeBlocks,
                    createdAtMillis = now,
                    expiresAtMillis = now + 30_000L,
                ).validated(maximumChanges)
            }

            override fun fail(path: String): Nothing = throw DeconstructionFailure(path)
        },
    )

    fun select(world: World, x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int) {
        selection = BuilderSelection(
            BuilderBlockPos(world.uid, x1, y1, z1),
            BuilderBlockPos(world.uid, x2, y2, z2),
        )
    }
}

private fun List<BuilderItemAmount>.materialAmounts(): Map<Material, Int> = associate { amount ->
    BuilderItemCodec.decodePrototype(amount.itemBase64).type to amount.amount
}

private class DeconstructionFailure(val path: String) : RuntimeException(path)
