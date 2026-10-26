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

    test("match relevance precedes recent choices and recent choices only break equal matches") {
        BuilderMaterialArguments.search(
            listOf(Material.STONE, Material.DIRT, Material.STONE_BRICKS, Material.MOSSY_STONE_BRICKS),
            "stone",
            recentMaterials = listOf(Material.MOSSY_STONE_BRICKS, Material.DIRT, Material.STONE),
        ) shouldContainExactly listOf(Material.STONE, Material.STONE_BRICKS, Material.MOSSY_STONE_BRICKS)
        BuilderMaterialArguments.search(
            listOf(Material.STONE_BRICKS, Material.STONE_STAIRS),
            "stone",
            recentMaterials = listOf(Material.STONE_STAIRS),
        ).first() shouldBe Material.STONE_STAIRS
    }

    test("air leads blank results but never bypasses the query or supplied candidates") {
        val candidates = listOf(Material.DIRT, Material.STONE, Material.AIR)
        BuilderMaterialArguments.search(candidates, "  ", setOf(Material.DIRT), listOf(Material.STONE))
            .first() shouldBe Material.AIR
        listOf("воздух", " ВоЗдУх ", "AIR", "minecraft:air").forEach { query ->
            BuilderMaterialArguments.search(candidates, query) shouldContainExactly listOf(Material.AIR)
        }
        BuilderMaterialArguments.search(candidates, "stone") shouldContainExactly listOf(Material.STONE)
        BuilderMaterialArguments.search(listOf(Material.STONE), "воздух") shouldBe emptyList()
    }

    test("English ids phrases enum names and fragments share case insensitive relevance") {
        val candidates = listOf(Material.MOSSY_STONE_BRICKS, Material.STONE_BRICK_STAIRS, Material.STONE_BRICKS)
        listOf("STONE_BRICKS", "minecraft:stone_bricks", " StOnE Bri ", "sto bri", "one_brick").forEach { query ->
            BuilderMaterialArguments.search(candidates, query).first() shouldBe Material.STONE_BRICKS
        }
        BuilderMaterialArguments.search(listOf(Material.MOSSY_STONE_BRICKS, Material.BRICKS), "bricks")
            .first() shouldBe Material.BRICKS
    }

    test("bounded Russian and English typos follow literal matches deterministically") {
        val candidates = listOf(Material.STONE_BRICKS, Material.STONE, Material.DIRT)
        listOf("stnoe", "stome", "камеьн").forEach { query ->
            BuilderMaterialArguments.search(candidates, query).first() shouldBe Material.STONE
        }
        BuilderMaterialArguments.search(listOf(Material.DIRT), "dri") shouldBe emptyList()
        BuilderMaterialArguments.search(candidates, "completely unrelated") shouldBe emptyList()
        BuilderMaterialArguments.search(listOf(Material.STONE, Material.STONE_STAIRS), "stone stairs")
            .first() shouldBe Material.STONE_STAIRS
    }
})
