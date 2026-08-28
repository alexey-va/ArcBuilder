package ru.arc.buildertools

import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

internal enum class BuilderOwnedToolExchangeResult {
    REPLACED,
    WRONG_ITEM,
    INVENTORY_FULL,
}

/**
 * Converts exactly one plain vanilla item already owned by the player into a
 * tagged builder tool. This boundary never creates the valuable base material.
 * Must be called on the Paper primary thread.
 */
internal object BuilderOwnedToolExchange {
    fun replaceOnePlainHeld(
        player: Player,
        material: Material,
        replacement: ItemStack,
    ): BuilderOwnedToolExchangeResult {
        require(replacement.type == material && replacement.amount == 1) {
            "Builder tool replacement must be one matching base item"
        }
        val held = player.inventory.itemInMainHand
        if (!isPlain(held, material)) return BuilderOwnedToolExchangeResult.WRONG_ITEM
        if (held.amount > 1 && player.inventory.firstEmpty() == -1) {
            return BuilderOwnedToolExchangeResult.INVENTORY_FULL
        }

        if (held.amount == 1) {
            player.inventory.setItemInMainHand(replacement)
        } else {
            player.inventory.setItemInMainHand(held.clone().also { it.amount = held.amount - 1 })
            check(player.inventory.addItem(replacement).isEmpty()) {
                "Owned builder tool did not fit after successful preflight"
            }
        }
        return BuilderOwnedToolExchangeResult.REPLACED
    }

    private fun isPlain(item: ItemStack, material: Material): Boolean {
        if (item.type != material || item.amount <= 0) return false
        val prototype = item.clone().also { it.amount = 1 }
        return prototype.isSimilar(ItemStack(material))
    }
}
