package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.Config
import java.nio.file.Files

class BuilderToolsScanVolumeConfigTest : FunSpec({
    test("builder scan volume defaults to one million blocks") {
        val root = Files.createTempDirectory("arc-builder-scan-volume-")

        BuilderToolsConfig(Config(root, "modules/builder-tools.yml")).maxScanVolume shouldBe 1_000_000L
    }
})
