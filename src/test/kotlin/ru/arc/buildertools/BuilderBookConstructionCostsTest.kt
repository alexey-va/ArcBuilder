package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.autobuild.BuildBookData
import ru.arc.autobuild.BuildBookMaterialRequirement
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class BuilderBookConstructionCostsTest : FunSpec({
    test("registered book charges only player-supplied materials for actual changed steps") {
        MockBukkitTestRuntime.open().use {
            val worldId = UUID.randomUUID()
            val book = ItemStack(Material.BOOK)
            val data = BuildBookData(
                buildingId = "player-book.schem",
                title = "Дом",
                playerCreated = true,
                creatorId = UUID.randomUUID(),
                creatorName = "Builder",
                blueprintId = UUID.randomUUID(),
                instanceId = UUID.randomUUID(),
                instanceGeneration = 1,
                issuePriceMinor = 100,
                contentSha256 = "a".repeat(64),
                schematicSha256 = "b".repeat(64),
                playerMaterials = listOf(
                    BuildBookMaterialRequirement(Material.OAK_SLAB, 200),
                    BuildBookMaterialRequirement(Material.STONE, 500),
                ),
            )
            val placements = listOf(
                BuilderBookPlannedChange(
                    BuilderBlockChange(
                        BuilderBlockPos(worldId, 0, 64, 0),
                        "minecraft:air",
                        "minecraft:stone",
                    ),
                    placementItem = ItemStack(Material.STONE),
                    refund = null,
                ),
                BuilderBookPlannedChange(
                    BuilderBlockChange(
                        BuilderBlockPos(worldId, 0, 65, 0),
                        "minecraft:dirt",
                        "minecraft:oak_slab[type=double,waterlogged=false]",
                    ),
                    placementItem = ItemStack(Material.OAK_SLAB, 2),
                    refund = ItemStack(Material.DIRT),
                ),
            )

            val definition = BuilderBookConstructionCosts.calculate(book, data, GameMode.SURVIVAL, placements)

            definition.steps.map { it.requiredMaterial?.materialKey to it.requiredMaterial?.amount } shouldContainExactly
                listOf("minecraft:stone" to 1, "minecraft:oak_slab" to 2)
            definition.costs.map { it.materialKey to it.amount } shouldContainExactly listOf(
                "minecraft:book" to 1,
                "minecraft:stone" to 1,
                "minecraft:oak_slab" to 2,
            )
            definition.rewards.map { it.materialKey to it.amount } shouldBe listOf("minecraft:dirt" to 1)
        }
    }

    test("starter system book with included materials consumes only its physical book") {
        MockBukkitTestRuntime.open().use {
            val worldId = UUID.randomUUID()
            val book = ItemStack(Material.BOOK)
            val data = BuildBookData(
                buildingId = "viking.schem", title = "Дом викинга",
                playerMaterials = listOf(BuildBookMaterialRequirement(Material.CHEST, 1)),
            )
            val placements = listOf(
                BuilderBookPlannedChange(
                    BuilderBlockChange(
                        BuilderBlockPos(worldId, 0, 64, 0),
                        "minecraft:air",
                        "minecraft:chest[facing=north,type=single,waterlogged=false]",
                    ),
                    placementItem = ItemStack(Material.CHEST),
                    refund = null,
                    lootTableKey = "minecraft:chests/spawn_bonus_chest",
                ),
            )

            val survival = BuilderBookConstructionCosts.calculate(
                book,
                data,
                GameMode.SURVIVAL,
                placements,
                systemMaterialsIncluded = true,
            )
            val creative = BuilderBookConstructionCosts.calculate(
                book,
                data,
                GameMode.CREATIVE,
                placements,
                systemMaterialsIncluded = true,
            )

            survival.costs.map { it.materialKey to it.amount } shouldBe listOf("minecraft:book" to 1)
            survival.steps.single().requiredMaterial shouldBe null
            survival.steps.single().lootTableKey shouldBe "minecraft:chests/spawn_bonus_chest"
            creative.costs.map { it.materialKey to it.amount } shouldBe listOf("minecraft:book" to 1)
            creative.steps.single().requiredMaterial shouldBe null
        }
    }
})
