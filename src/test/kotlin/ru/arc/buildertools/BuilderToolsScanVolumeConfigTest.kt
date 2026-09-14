package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import ru.arc.config.Config
import java.nio.file.Files
import java.util.UUID

class BuilderToolsScanVolumeConfigTest : FunSpec({
    test("builder scan volume defaults to one million blocks") {
        val root = Files.createTempDirectory("arc-builder-scan-volume-")

        val config = BuilderToolsConfig(Config(root, "modules/builder-tools.yml"))

        config.maxScanVolume shouldBe 1_000_000L
        config.maxConstructionChanges shouldBe 100_000
        config.maxChangesFor(BuilderPlanKind.BUILD_BOOK) shouldBe 100_000
        config.maxChangesFor(BuilderPlanKind.FILL) shouldBe 4_096
        BuilderPlanKind.BUILD_BOOK.usesHourlyLimit() shouldBe false
        BuilderPlanKind.FILL.usesHourlyLimit() shouldBe true
    }

    test("Schvec-sized gradual plan exceeds immediate limit but fits construction limit") {
        val root = Files.createTempDirectory("arc-builder-construction-limit-")
        val config = BuilderToolsConfig(Config(root, "modules/builder-tools.yml"))
        val worldId = UUID.randomUUID()
        val playerId = UUID.randomUUID()
        val changes = List(19_658) { index ->
            BuilderBlockChange(
                position = BuilderBlockPos(worldId, index % 100, index / 10_000, index / 100),
                beforeBlockData = "minecraft:air",
                afterBlockData = "minecraft:stone",
            )
        }
        val plan = BuilderPlan(
            id = UUID.randomUUID(),
            playerId = playerId,
            kind = BuilderPlanKind.BUILD_BOOK,
            changes = changes,
            costs = emptyList(),
            rewards = emptyList(),
            createdAtMillis = 1,
            expiresAtMillis = 2,
        )

        shouldThrow<IllegalArgumentException> { plan.validated(config.maxChanges) }
        plan.validated(config.maxChangesFor(BuilderPlanKind.BUILD_BOOK)) shouldBe plan
    }
})
