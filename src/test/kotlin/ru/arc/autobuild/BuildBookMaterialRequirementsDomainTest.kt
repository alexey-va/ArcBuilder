package ru.arc.autobuild

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material

class BuildBookMaterialRequirementsDomainTest : StringSpec({
    "material codec works without a bootstrapped Bukkit server" {
        val encoded = BuildBookMaterialRequirements.encode(
            listOf(
                BuildBookMaterialRequirement(Material.STONE, 64),
                BuildBookMaterialRequirement(Material.OAK_PLANKS, 16),
            ),
        )

        BuildBookMaterialRequirements.decode(encoded) shouldBe
            listOf(
                BuildBookMaterialRequirement(Material.OAK_PLANKS, 16),
                BuildBookMaterialRequirement(Material.STONE, 64),
            )
    }

    "material codec rejects air without consulting Bukkit registries" {
        shouldThrow<IllegalArgumentException> {
            BuildBookMaterialRequirements.encode(listOf(BuildBookMaterialRequirement(Material.AIR, 1)))
        }
    }
})
