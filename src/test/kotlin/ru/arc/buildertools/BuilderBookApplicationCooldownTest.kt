package ru.arc.buildertools

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.util.UUID

class BuilderBookApplicationCooldownTest : StringSpec({
    "ordinary player waits twelve hours from durable project application" {
        val playerId = UUID.fromString("11111111-2222-3333-4444-555555555555")
        val startedAt = 1_800_000_100_000L
        val records = listOf(project(playerId, applicationStartedAtMillis = startedAt))

        BuilderBookApplicationCooldown.remaining(
            playerId = playerId,
            records = records,
            nowMillis = startedAt + Duration.ofHours(5).toMillis(),
            cooldown = Duration.ofHours(12),
            bypass = false,
        ) shouldBe Duration.ofHours(7)

        BuilderBookApplicationCooldown.remaining(
            playerId = playerId,
            records = records,
            nowMillis = startedAt + Duration.ofHours(12).toMillis(),
            cooldown = Duration.ofHours(12),
            bypass = false,
        ) shouldBe Duration.ZERO
    }

    "operator bypass and another player's projects do not block application" {
        val playerId = UUID.randomUUID()
        val now = 1_800_000_100_000L
        val other = project(UUID.randomUUID(), applicationStartedAtMillis = now - 1_000)
        val own = project(playerId, applicationStartedAtMillis = now - 1_000)

        BuilderBookApplicationCooldown.remaining(
            playerId,
            listOf(other),
            now,
            Duration.ofHours(12),
            bypass = false,
        ) shouldBe Duration.ZERO
        BuilderBookApplicationCooldown.remaining(
            playerId,
            listOf(own),
            now,
            Duration.ofHours(12),
            bypass = true,
        ) shouldBe Duration.ZERO
    }
})

private fun project(
    playerId: UUID,
    applicationStartedAtMillis: Long,
): BuilderConstructionProjectRecord {
    val createdAt = applicationStartedAtMillis - 30_000
    val projectId = UUID.randomUUID()
    val position = BuilderBlockPos(UUID.randomUUID(), 1, 64, 1)
    val change = BuilderBlockChange(position, "minecraft:air", "minecraft:stone")
    val book = BuilderItemAmount("BOOK", "minecraft:book", 1)
    val plan = BuilderPlan(
        id = projectId,
        playerId = playerId,
        kind = BuilderPlanKind.BUILD_BOOK,
        changes = listOf(change),
        costs = listOf(book),
        rewards = emptyList(),
        createdAtMillis = createdAt,
        expiresAtMillis = createdAt + 60_000,
    )
    return BuilderConstructionProjectRecord(
        projectId = projectId,
        playerId = playerId,
        playerName = "Builder",
        plan = plan,
        steps = listOf(BuilderConstructionStep(change, requiredMaterial = null, output = null)),
        bookCost = book,
        state = BuilderConstructionProjectState.PREPARED,
        cursor = 0,
        createdAtMillis = createdAt,
        updatedAtMillis = applicationStartedAtMillis,
        applicationStartedAtMillis = applicationStartedAtMillis,
    ).validated()
}
