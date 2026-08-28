package ru.arc.buildertools

import org.bukkit.GameMode
import org.bukkit.inventory.ItemStack
import ru.arc.autobuild.BuildBookData

internal object BuilderBookConstructionCosts {
    fun calculate(book: ItemStack, data: BuildBookData, gameMode: GameMode): List<BuilderItemAmount> {
        val exactBook = book.clone().also { it.amount = 1 }
        val playerMaterials = if (BuilderGameModePolicy.usesInventory(gameMode)) {
            data.playerMaterials.map { requirement -> ItemStack(requirement.material, requirement.amount) }
        } else {
            emptyList()
        }
        return BuilderItemCodec.aggregate(listOf(exactBook) + playerMaterials)
    }
}
