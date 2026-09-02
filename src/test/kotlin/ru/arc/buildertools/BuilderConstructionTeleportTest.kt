package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class BuilderConstructionTeleportTest : FunSpec({
    test("safe destination stays outside the construction volume on its persisted panel side") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("construction-teleport")
            for (x in 5..15) for (z in 5..15) world.getBlockAt(x, 63, z).type = Material.STONE
            val project = teleportProject(world.uid, BuilderConstructionSitePanelFace.MIN_Z)

            val destination = checkNotNull(BuilderConstructionTeleport.findSafeDestination(world, project))

            destination.blockZ shouldBe 8
            destination.blockY shouldBe 64
            (destination.blockX in 10..12) shouldBe true
        }
    }

    test("unsafe liquid column is rejected") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("construction-teleport-liquid")
            for (x in 10..12) {
                world.getBlockAt(x, 63, 8).type = Material.STONE
                world.getBlockAt(x, 64, 8).type = Material.WATER
            }
            val project = teleportProject(world.uid, BuilderConstructionSitePanelFace.MIN_Z)

            BuilderConstructionTeleport.findSafeDestination(world, project) shouldBe null
        }
    }
})

private fun teleportProject(worldId: UUID, face: BuilderConstructionSitePanelFace): BuilderConstructionProjectRecord {
    val now = 1_800_000_000_000L
    val id = UUID.randomUUID()
    val book = teleportAmount(Material.BOOK)
    val changes = listOf(10, 11, 12).map { x ->
        BuilderBlockChange(BuilderBlockPos(worldId, x, 64, 10), "minecraft:air", "minecraft:stone")
    }
    val plan = BuilderPlan(
        id = id,
        playerId = UUID.randomUUID(),
        kind = BuilderPlanKind.BUILD_BOOK,
        changes = changes,
        costs = listOf(book),
        rewards = emptyList(),
        createdAtMillis = now,
        expiresAtMillis = now + 60_000,
    )
    return BuilderConstructionProjectRecord(
        projectId = id,
        playerId = plan.playerId,
        playerName = "Builder",
        projectTitle = "Дом",
        sitePanelFace = face,
        plan = plan,
        steps = changes.map { BuilderConstructionStep(it, null, null) },
        bookCost = book,
        state = BuilderConstructionProjectState.ACTIVE,
        cursor = 0,
        createdAtMillis = now,
        updatedAtMillis = now,
    ).validated()
}

private fun teleportAmount(material: Material): BuilderItemAmount = BuilderItemAmount(
    itemBase64 = BuilderItemCodec.encodePrototype(ItemStack(material)),
    materialKey = material.key.toString(),
    amount = 1,
)
