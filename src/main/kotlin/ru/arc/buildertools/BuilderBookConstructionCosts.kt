package ru.arc.buildertools

import org.bukkit.GameMode
import org.bukkit.inventory.ItemStack
import ru.arc.autobuild.BuildBookData

internal data class BuilderBookPlannedChange(
    val change: BuilderBlockChange,
    val placementItem: ItemStack?,
    val refund: ItemStack?,
    val lootTableKey: String? = null,
    val systemFurniture: Boolean = false,
)

internal data class BuilderBookConstructionDefinition(
    val steps: List<BuilderConstructionStep>,
    val bookCost: BuilderItemAmount,
    val costs: List<BuilderItemAmount>,
    val rewards: List<BuilderItemAmount>,
)

/** Only immutable values cross from native capture to the calculation worker. */
internal data class BuilderBookCapturedChange(
    val change: BuilderBlockChange,
    val placementItem: BuilderItemAmount?,
    val refund: BuilderItemAmount?,
    val lootTableKey: String? = null,
    val systemFurniture: Boolean = false,
)

internal object BuilderBookConstructionCosts {
    /** Full blueprint estimate retained for contract and presentation tests. */
    fun calculate(book: ItemStack, data: BuildBookData, gameMode: GameMode): List<BuilderItemAmount> {
        val exactBook = book.clone().also { it.amount = 1 }
        val playerMaterials = if (BuilderGameModePolicy.usesInventory(gameMode)) {
            data.playerMaterials.map { requirement ->
                require(requirement.material.isItem) { "Build-book player material is not available as an item" }
                ItemStack(requirement.material, requirement.amount)
            }
        } else {
            emptyList()
        }
        return BuilderItemCodec.aggregate(listOf(exactBook) + playerMaterials)
    }

    fun calculate(
        book: ItemStack,
        data: BuildBookData,
        gameMode: GameMode,
        placements: List<BuilderBookPlannedChange>,
        systemMaterialsIncluded: Boolean = false,
    ): BuilderBookConstructionDefinition {
        val exactBook = book.clone().also { it.amount = 1 }
        val cache = BuilderItemCodec.PrototypeCache()
        return assemble(
            cache.capture(exactBook),
            playerSuppliedTypes(data, gameMode, systemMaterialsIncluded),
            placements.map { placement ->
                BuilderBookCapturedChange(
                    placement.change, placement.placementItem?.let(cache::capture), placement.refund?.let(cache::capture),
                    placement.lootTableKey, placement.systemFurniture,
                )
            },
        )
    }

    fun playerSuppliedTypes(data: BuildBookData, gameMode: GameMode, systemMaterialsIncluded: Boolean): Set<String>? =
        if (!BuilderGameModePolicy.usesInventory(gameMode)) {
            emptySet()
        } else if (!data.playerCreated) {
            if (systemMaterialsIncluded) emptySet() else null
        } else {
            data.playerMaterials.mapTo(mutableSetOf()) { requirement ->
                require(requirement.material.isItem) { "Build-book player material is not available as an item" }
                requirement.material.key.toString()
            }
        }

    /** Pure cost/step calculation; no ItemStack, world, inventory or registry access. */
    fun assemble(
        bookCost: BuilderItemAmount,
        playerSuppliedTypes: Set<String>?,
        placements: List<BuilderBookCapturedChange>,
    ): BuilderBookConstructionDefinition {
        val inputItems = mutableListOf<BuilderItemAmount>()
        val outputItems = mutableListOf<BuilderItemAmount>()
        val steps = placements.map { placement ->
            val inputItem = placement.placementItem?.takeIf { item ->
                playerSuppliedTypes == null || item.materialKey in playerSuppliedTypes
            }
            inputItem?.let(inputItems::add)
            placement.refund?.let(outputItems::add)
            BuilderConstructionStep(
                change = placement.change,
                requiredMaterial = inputItem,
                output = placement.refund,
                lootTableKey = placement.lootTableKey,
                systemFurniture = placement.systemFurniture,
            ).validated()
        }
        return BuilderBookConstructionDefinition(
            steps = steps,
            bookCost = bookCost,
            costs = BuilderItemCodec.aggregateAmounts(listOf(bookCost) + inputItems),
            rewards = BuilderItemCodec.aggregateAmounts(outputItems),
        )
    }
}
