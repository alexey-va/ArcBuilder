package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.block.Chest
import org.bukkit.loot.LootTable

class BuilderLootTableAccessTest : FunSpec({
    test("Paper boundary assigns, saves and reads back the exact configured loot table") {
        val table = mockk<LootTable>()
        val chest = mockk<Chest>()
        val block = mockk<Block>()
        every { table.key } returns NamespacedKey.minecraft("chests/spawn_bonus_chest")
        every { block.state } returns chest
        every { chest.setLootTable(table) } just runs
        every { chest.update(true, false) } returns true
        every { chest.lootTable } returns table

        PaperBuilderLootTableAccess.apply(block, table)

        PaperBuilderLootTableAccess.matches(block, table) shouldBe true
        verify(exactly = 1) { chest.setLootTable(table) }
        verify(exactly = 1) { chest.update(true, false) }
    }
})
