package ru.arc.autobuild

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.nio.file.Files

class BuildingManagerTest : FunSpec({
    test("system schematic invalidation allows a new runtime generation to replace cached data") {
        val buildingId = "cache-generation-regression.schem"
        val previous = Building(buildingId)
        val replacement = Building(buildingId)
        val root = Files.createTempDirectory("builder-cache-generation")

        mockkObject(BuilderStoragePaths)
        every { BuilderStoragePaths.schematicsRoot(any()) } returns root
        BuildingManager.addBuilding(previous)
        try {
            BuildingManager.getBuilding(buildingId) shouldBe previous

            BuildingManager.invalidateBuildings(listOf(buildingId))
            // A removed file must not remain available from the previous cache.
            BuildingManager.getBuilding(buildingId) shouldBe null
            BuildingManager.addBuilding(replacement)

            BuildingManager.getBuilding(buildingId) shouldBe replacement
        } finally {
            BuildingManager.invalidateBuildings(listOf(buildingId))
            unmockkObject(BuilderStoragePaths)
            Files.deleteIfExists(root)
        }
    }
})
