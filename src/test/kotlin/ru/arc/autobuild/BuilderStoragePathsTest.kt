package ru.arc.autobuild

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class BuilderStoragePathsTest : FunSpec({
    test("configured shared schematic root may be an explicit symlink") {
        val serverRoot = Files.createTempDirectory("arc-builder-storage-")
        val dataRoot = Files.createDirectories(serverRoot.resolve("plugins/ArcBuilder"))
        val sharedRoot = Files.createDirectories(serverRoot.resolve("shared-schematics"))
        val arcRoot = Files.createDirectories(serverRoot.resolve("plugins/ARC"))
        Files.createSymbolicLink(arcRoot.resolve("schematics"), sharedRoot)

        BuilderStoragePaths.validateSchematicsRoot(dataRoot, "../ARC/schematics") shouldBe sharedRoot.toRealPath()
    }

    test("configured schematic root cannot lexically leave the plugins directory") {
        val serverRoot = Files.createTempDirectory("arc-builder-storage-")
        val dataRoot = Files.createDirectories(serverRoot.resolve("plugins/ArcBuilder"))
        Files.createDirectories(serverRoot.resolve("outside"))

        shouldThrow<IllegalArgumentException> {
            BuilderStoragePaths.validateSchematicsRoot(dataRoot, "../../outside")
        }
    }

    test("configured schematic root cannot escape through a symlinked ancestor") {
        val serverRoot = Files.createTempDirectory("arc-builder-storage-")
        val dataRoot = Files.createDirectories(serverRoot.resolve("plugins/ArcBuilder"))
        val outside = Files.createDirectories(serverRoot.resolve("outside"))
        val linkedParent = dataRoot.resolve("linked")
        Files.createSymbolicLink(linkedParent, outside)
        Files.createDirectories(outside.resolve("schematics"))

        shouldThrow<IllegalArgumentException> {
            BuilderStoragePaths.validateSchematicsRoot(dataRoot, "linked/schematics")
        }
    }
})
