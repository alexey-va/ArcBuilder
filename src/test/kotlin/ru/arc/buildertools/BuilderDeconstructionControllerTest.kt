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
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.plugin.Plugin
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class BuilderDeconstructionControllerTest : FunSpec({
    test("drop tool keeps ordinary enchantments but removes Silk Touch") {
        MockBukkitTestRuntime.open().use { paper ->
            val tool = ItemStack(Material.DIAMOND_PICKAXE).apply {
                addUnsafeEnchantment(Enchantment.SILK_TOUCH, 1)
                addUnsafeEnchantment(Enchantment.FORTUNE, 3)
            }

            val dropsTool = BuilderDeconstructionDrops.withoutSilkTouch(tool)

            dropsTool.containsEnchantment(Enchantment.SILK_TOUCH) shouldBe false
            dropsTool.getEnchantmentLevel(Enchantment.FORTUNE) shouldBe 3
            tool.containsEnchantment(Enchantment.SILK_TOUCH) shouldBe true
        }
    }

    test("survival pools suitable inventory tools and leaves one durability on each") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("BuilderDeconstructionRefundTest")
            val world = paper.addSimpleWorld("deconstruction-refund")
            val player = paper.addPlayer("DeconstructionOwner")
            player.teleport(Location(world, 0.5, 64.0, 2.5))
            world.getBlockAt(0, 64, 0).type = Material.STONE
            world.getBlockAt(1, 64, 0).type = Material.STONE
            val harness = DeconstructionHarness(
                plugin = plugin,
                drops = { _, tool ->
                    checkNotNull(tool).containsEnchantment(Enchantment.SILK_TOUCH) shouldBe false
                    listOf(ItemStack(Material.COBBLESTONE))
                },
            )
            harness.select(world, 0, 64, 0, 1, 64, 0)
            val held = ItemStack(Material.DIAMOND_PICKAXE).apply {
                editMeta { meta ->
                    meta.addEnchant(Enchantment.SILK_TOUCH, 1, true)
                    meta.addEnchant(Enchantment.FORTUNE, 3, true)
                    (meta as Damageable).damage = type.maxDurability.toInt() - 2
                }
            }
            val reserve = ItemStack(Material.IRON_PICKAXE).apply {
                editMeta { meta -> (meta as Damageable).damage = type.maxDurability.toInt() - 2 }
            }
            player.inventory.setItemInMainHand(held)
            player.inventory.setItem(10, reserve)

            val plan = harness.controller.plan(player)
            val pooled = BuilderPooledToolCodec.decode(checkNotNull(plan.toolFingerprintBase64))

            plan.kind shouldBe BuilderPlanKind.DECONSTRUCT
            plan.changes.size shouldBe 2
            plan.rewards.materialAmounts() shouldBe mapOf(Material.COBBLESTONE to 2)
            plan.toolDamage shouldBe 2
            pooled.uses.map { it.slot to it.damage } shouldBe listOf(0 to 1, 10 to 1)
            pooled.bypassUsed shouldBe false
            player.inventory.setItem(10, null)
            BuilderInventory.canApply(player, emptyList(), plan.rewards, plan.toolFingerprintBase64, plan.toolDamage) shouldBe false
            player.inventory.setItem(10, reserve)
            BuilderInventory.canApply(player, emptyList(), plan.rewards, plan.toolFingerprintBase64, plan.toolDamage) shouldBe true
            BuilderInventory.applyToolDamage(player, checkNotNull(plan.toolFingerprintBase64), plan.toolDamage)
            (player.inventory.getItem(0)?.itemMeta as Damageable).damage shouldBe
                Material.DIAMOND_PICKAXE.maxDurability.toInt() - 1
            (player.inventory.getItem(10)?.itemMeta as Damageable).damage shouldBe
                Material.IRON_PICKAXE.maxDurability.toInt() - 1
            harness.permissions shouldBe 1
            harness.mutableBlocks shouldBe 2
            world.getBlockAt(0, 64, 0).type shouldBe Material.STONE
            world.getBlockAt(1, 64, 0).type shouldBe Material.STONE
        }
    }

    test("survival discovers a suitable tool outside the held slot") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("BuilderDeconstructionInventoryToolTest")
            val world = paper.addSimpleWorld("deconstruction-inventory-tool")
            val player = paper.addPlayer("InventoryToolOwner")
            player.teleport(Location(world, 0.5, 64.0, 2.5))
            world.getBlockAt(0, 64, 0).type = Material.STONE
            val harness = DeconstructionHarness(plugin)
            harness.select(world, 0, 64, 0, 0, 64, 0)
            player.inventory.setItemInMainHand(ItemStack(Material.STICK))
            val inventoryTool = ItemStack(Material.IRON_PICKAXE).apply {
                editMeta { meta -> (meta as Damageable).damage = type.maxDurability.toInt() - 2 }
            }
            player.inventory.setItem(10, inventoryTool)

            val plan = harness.controller.plan(player)
            val pooled = BuilderPooledToolCodec.decode(checkNotNull(plan.toolFingerprintBase64))

            pooled.uses.map { it.slot to it.damage } shouldBe listOf(10 to 1)
            plan.toolDamage shouldBe 1
            BuilderInventory.applyToolDamage(player, checkNotNull(plan.toolFingerprintBase64), plan.toolDamage)
            player.inventory.itemInMainHand.type shouldBe Material.STICK
            (player.inventory.getItem(10)?.itemMeta as Damageable).damage shouldBe
                Material.IRON_PICKAXE.maxDurability.toInt() - 1
        }
    }

    test("explicit permission allows tool-free survival deconstruction") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("BuilderDeconstructionWithoutToolTest")
            val world = paper.addSimpleWorld("deconstruction-without-tool")
            val player = paper.addPlayer("ToolFreeOwner")
            player.teleport(Location(world, 0.5, 64.0, 2.5))
            world.getBlockAt(0, 64, 0).type = Material.STONE
            val harness = DeconstructionHarness(
                plugin = plugin,
                withoutToolPermission = true,
                drops = { _, tool ->
                    tool shouldBe null
                    emptyList()
                },
            )
            harness.select(world, 0, 64, 0, 0, 64, 0)

            val plan = harness.controller.plan(player)

            plan.changes.size shouldBe 1
            plan.rewards shouldBe emptyList()
            plan.toolFingerprintBase64 shouldBe null
            plan.toolDamage shouldBe 0
            BuilderDeconstructionToolPolicy.requiresBypass(player.gameMode, plan) shouldBe true
            world.getBlockAt(0, 64, 0).type shouldBe Material.STONE
        }
    }

    test("tool permission uses remaining pooled durability then falls back to tool-free drops") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("BuilderDeconstructionPartialToolTest")
            val world = paper.addSimpleWorld("deconstruction-partial-tool")
            val player = paper.addPlayer("PartialToolOwner")
            player.teleport(Location(world, 0.5, 64.0, 2.5))
            world.getBlockAt(0, 64, 0).type = Material.STONE
            world.getBlockAt(1, 64, 0).type = Material.STONE
            val harness = DeconstructionHarness(
                plugin = plugin,
                withoutToolPermission = true,
                drops = { _, tool -> listOf(ItemStack(if (tool == null) Material.FLINT else Material.COBBLESTONE)) },
            )
            harness.select(world, 0, 64, 0, 1, 64, 0)
            player.inventory.setItemInMainHand(ItemStack(Material.DIAMOND_PICKAXE).apply {
                editMeta { meta -> (meta as Damageable).damage = type.maxDurability.toInt() - 2 }
            })

            val plan = harness.controller.plan(player)
            val pooled = BuilderPooledToolCodec.decode(checkNotNull(plan.toolFingerprintBase64))

            plan.rewards.materialAmounts() shouldBe mapOf(Material.COBBLESTONE to 1, Material.FLINT to 1)
            plan.toolDamage shouldBe 1
            pooled.uses.map(BuilderPooledToolUse::damage) shouldBe listOf(1)
            pooled.bypassUsed shouldBe true
            BuilderDeconstructionToolPolicy.requiresBypass(player.gameMode, plan) shouldBe true
        }
    }

    test("survival fails when pooled tools cannot cover the selection without permission") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("BuilderDeconstructionExhaustedToolsTest")
            val world = paper.addSimpleWorld("deconstruction-exhausted-tools")
            val player = paper.addPlayer("ExhaustedToolOwner")
            player.teleport(Location(world, 0.5, 64.0, 2.5))
            world.getBlockAt(0, 64, 0).type = Material.STONE
            world.getBlockAt(1, 64, 0).type = Material.STONE
            val harness = DeconstructionHarness(plugin)
            harness.select(world, 0, 64, 0, 1, 64, 0)
            player.inventory.setItemInMainHand(ItemStack(Material.DIAMOND_PICKAXE).apply {
                editMeta { meta -> (meta as Damageable).damage = type.maxDurability.toInt() - 2 }
            })

            shouldThrow<DeconstructionFailure> { harness.controller.plan(player) }.path shouldBe "errors.tool"
        }
    }

    test("unbreakable pooled tools remain unchanged") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("BuilderDeconstructionUnbreakableToolTest")
            val world = paper.addSimpleWorld("deconstruction-unbreakable-tool")
            val player = paper.addPlayer("UnbreakableToolOwner")
            player.teleport(Location(world, 0.5, 64.0, 2.5))
            world.getBlockAt(0, 64, 0).type = Material.STONE
            val harness = DeconstructionHarness(plugin)
            harness.select(world, 0, 64, 0, 0, 64, 0)
            val originalDamage = Material.DIAMOND_PICKAXE.maxDurability.toInt() - 1
            player.inventory.setItemInMainHand(ItemStack(Material.DIAMOND_PICKAXE).apply {
                editMeta { meta ->
                    meta.isUnbreakable = true
                    (meta as Damageable).damage = originalDamage
                }
            })

            val plan = harness.controller.plan(player)
            BuilderInventory.applyToolDamage(player, checkNotNull(plan.toolFingerprintBase64), plan.toolDamage)

            (player.inventory.itemInMainHand.itemMeta as Damageable).damage shouldBe originalDamage
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

            player.inventory.setItemInMainHand(ItemStack(Material.DIAMOND_PICKAXE).apply {
                editMeta { meta -> (meta as Damageable).damage = type.maxDurability.toInt() - 3 }
            })
            shouldThrow<DeconstructionFailure> { harness.controller.plan(player) }.path shouldBe "errors.selection-too-large"
            world.getBlockAt(0, 64, 0).type shouldBe Material.STONE
            world.getBlockAt(1, 64, 0).type shouldBe Material.STONE
        }
    }
})

private class DeconstructionHarness(
    plugin: Plugin,
    maximumChanges: Int = 64,
    withoutToolPermission: Boolean = false,
    drops: (Block, ItemStack?) -> Collection<ItemStack> = { block, _ ->
        listOfNotNull(BuilderPlacementCost.itemOrNull(block.blockData))
    },
) {
    private lateinit var selection: BuilderSelection
    private val safety = BuilderBlockSafety(plugin, setOf("AIR", "SHORT_GRASS"))
    var permissions = 0
    var mutableBlocks = 0

    val controller = BuilderDeconstructionController(
        safety = safety,
        maximumChanges = maximumChanges,
        isPreferredTool = { _, tool -> tool.type.name.endsWith("_PICKAXE") },
        blockDrops = { block, tool, _ -> drops(block, tool) },
        host = object : BuilderDeconstructionHost {
            override fun ensurePermission(player: Player) {
                permissions++
            }

            override fun canDeconstructWithoutTool(player: Player): Boolean = withoutToolPermission

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
