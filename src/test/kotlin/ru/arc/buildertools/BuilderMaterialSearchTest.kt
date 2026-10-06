package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import java.util.Locale

class BuilderMaterialSearchTest : FunSpec({
    test("ranks exact, prefix, and contained English identifiers in that order") {
        BuilderMaterialArguments.search(
            listOf(Material.MOSSY_STONE_BRICKS, Material.STONE_BRICKS, Material.STONE),
            " StOnE ",
        ) shouldContainExactly listOf(Material.STONE, Material.STONE_BRICKS, Material.MOSSY_STONE_BRICKS)
    }

    test("accepts namespaced vanilla material identifiers") {
        BuilderMaterialArguments.search(
            listOf(Material.STONE_BRICKS, Material.STONE),
            "minecraft:stone",
        ) shouldContainExactly listOf(Material.STONE, Material.STONE_BRICKS)
    }

    test("matches Russian human names with case, spaces, and ё normalization") {
        val material = Material.BIRCH_LOG
        val russian = BuilderMaterialArguments.names(listOf(material)).first()
        val query = "  ${russian.replace('е', 'ё').uppercase(Locale.ROOT)}  "

        BuilderMaterialArguments.search(listOf(Material.OAK_LOG, material), query) shouldContainExactly listOf(material)
    }

    test("blank search prefers owned candidates and never adds inventory materials outside the allowlist") {
        val candidates = listOf(Material.STONE, Material.DIRT, Material.OAK_PLANKS)
        val result = BuilderMaterialArguments.search(candidates, "   ", setOf(Material.DIRT, Material.CHEST))

        result.first() shouldBe Material.DIRT
        result.toSet() shouldBe candidates.toSet()
        BuilderMaterialArguments.search(candidates, "chest", setOf(Material.CHEST)) shouldBe emptyList()
    }
})
