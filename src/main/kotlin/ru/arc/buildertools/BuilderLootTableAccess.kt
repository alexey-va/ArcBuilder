package ru.arc.buildertools

import org.bukkit.block.Block
import org.bukkit.loot.Lootable
import org.bukkit.loot.LootTable

/** Narrow Paper boundary because MockBukkit does not implement chest loot-table methods. */
internal interface BuilderLootTableAccess {
    fun matches(block: Block, table: LootTable): Boolean

    fun apply(block: Block, table: LootTable)
}

internal object PaperBuilderLootTableAccess : BuilderLootTableAccess {
    override fun matches(block: Block, table: LootTable): Boolean =
        (block.state as? Lootable)?.lootTable?.key == table.key

    override fun apply(block: Block, table: LootTable) {
        val state = block.state
        check(state is Lootable) { "Builder construction loot target is not lootable" }
        state.setLootTable(table)
        check(state.update(true, false)) { "Builder construction loot table was not saved" }
    }
}
