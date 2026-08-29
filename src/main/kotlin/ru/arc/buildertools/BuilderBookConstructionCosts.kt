package ru.arc.buildertools

import org.bukkit.GameMode
import org.bukkit.inventory.ItemStack
import ru.arc.autobuild.BuildBookData

internal data class BuilderBookPlannedChange(
    val change: BuilderBlockChange,
    val placementItem: ItemStack?,
    val refund: ItemStack?,
)

internal data class BuilderBookConstructionDefinition(
    val steps: List<BuilderConstructionStep>,
    val bookCost: BuilderItemAmount,
    val costs: List<BuilderItemAmount>,
    val rewards: List<BuilderItemAmount>,
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
        val playerSuppliedTypes = if (!BuilderGameModePolicy.usesInventory(gameMode)) {
            emptySet()
        } else if (!data.playerCreated && !systemMaterialsIncluded) {
            null
        } else {
            data.playerMaterials.mapTo(mutableSetOf()) { requirement ->
                require(requirement.material.isItem) { "Build-book player material is not available as an item" }
                requirement.material
            }
        }
        val inputItems = mutableListOf<ItemStack>()
        val outputItems = mutableListOf<ItemStack>()
        val steps = placements.map { placement ->
            val inputItem = placement.placementItem?.takeIf { item ->
                playerSuppliedTypes == null || item.type in playerSuppliedTypes
            }
            inputItem?.let(inputItems::add)
            placement.refund?.let(outputItems::add)
            BuilderConstructionStep(
                change = placement.change,
                requiredMaterial = inputItem?.let { BuilderItemCodec.aggregate(listOf(it)).single() },
                output = placement.refund?.let { BuilderItemCodec.aggregate(listOf(it)).single() },
            ).validated()
        }
        return BuilderBookConstructionDefinition(
            steps = steps,
            bookCost = BuilderItemCodec.aggregate(listOf(exactBook)).single(),
            costs = BuilderItemCodec.aggregate(listOf(exactBook) + inputItems),
            rewards = BuilderItemCodec.aggregate(outputItems),
        )
    }
}
