package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Container
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class BuilderConstructionResourcesTest : FunSpec({
    fun project(
        playerId: UUID,
        worldId: UUID,
        input: BuilderItemAmount,
        output: BuilderItemAmount? = null,
    ): BuilderConstructionProjectRecord {
        val projectId = UUID.randomUUID()
        val now = 1_800_000_000_000L
        val book = BuilderItemCodec.aggregate(listOf(ItemStack(Material.BOOK))).single()
        val change = BuilderBlockChange(
            BuilderBlockPos(worldId, 0, 64, 0),
            "minecraft:dirt",
            "minecraft:stone",
        )
        val step = BuilderConstructionStep(change, input, output)
        return BuilderConstructionProjectRecord(
            projectId = projectId,
            playerId = playerId,
            playerName = "Builder",
            plan = BuilderPlan(
                id = projectId,
                playerId = playerId,
                kind = BuilderPlanKind.BUILD_BOOK,
                changes = listOf(change),
                costs = listOf(book, input),
                rewards = listOfNotNull(output),
                createdAtMillis = now,
                expiresAtMillis = now + 60_000,
            ),
            steps = listOf(step),
            bookCost = book,
            state = BuilderConstructionProjectState.PREPARED,
            cursor = 0,
            createdAtMillis = now,
            updatedAtMillis = now,
        ).validated()
    }

    fun Container.amount(material: Material): Int = inventory.contents
        .filterNotNull()
        .filter { it.type == material }
        .sumOf(ItemStack::getAmount)

    test("foreign claimed chest is never used even when it is next to wilderness construction") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("construction-foreign-container")
            world.getChunkAt(0, 0).load()
            val ownerId = UUID.randomUUID()
            val stone = BuilderItemCodec.aggregate(listOf(ItemStack(Material.STONE, 2))).single()
            val project = project(ownerId, world.uid, stone)
            world.getBlockAt(1, 64, 0).type = Material.BARREL
            world.getBlockAt(2, 64, 0).type = Material.CHEST
            val own = world.getBlockAt(1, 64, 0).state as Container
            val foreign = world.getBlockAt(2, 64, 0).state as Container
            own.inventory.addItem(ItemStack(Material.STONE, 1))
            foreign.inventory.addItem(ItemStack(Material.STONE, 64))
            val resources = BuilderConstructionResources(
                containerRadius = 4,
                onlineRange = 48.0,
                worldProvider = { world.takeIf { it.uid == world.uid } },
                onlinePlayerProvider = { null },
                canOpenContainer = { _, block -> block.x != 2 },
            )

            resources.removeInput(ownerId, project, stone) shouldBe false
            own.amount(Material.STONE) shouldBe 1
            foreign.amount(Material.STONE) shouldBe 64

            own.inventory.addItem(ItemStack(Material.STONE, 1))
            resources.removeInput(ownerId, project, stone) shouldBe true
            own.amount(Material.STONE) shouldBe 0
            foreign.amount(Material.STONE) shouldBe 64
        }
    }

    test("wilderness chest is usable and access is checked again before mutation") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("construction-wilderness-container")
            world.getChunkAt(0, 0).load()
            val ownerId = UUID.randomUUID()
            val stone = BuilderItemCodec.aggregate(listOf(ItemStack(Material.STONE))).single()
            val project = project(ownerId, world.uid, stone)
            world.getBlockAt(1, 64, 0).type = Material.BARREL
            val container = world.getBlockAt(1, 64, 0).state as Container
            container.inventory.addItem(ItemStack(Material.STONE))
            var accessChecks = 0
            val resources = BuilderConstructionResources(
                containerRadius = 4,
                onlineRange = 48.0,
                worldProvider = { world },
                onlinePlayerProvider = { null },
                canOpenContainer = { _, _ -> (++accessChecks) <= 1 },
            )

            resources.removeInput(ownerId, project, stone) shouldBe false
            container.amount(Material.STONE) shouldBe 1
            accessChecks shouldBe 2
        }
    }

    test("nearby player inventory supplies input first while container receives output first") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("construction-resource-order")
            world.getChunkAt(0, 0).load()
            val player = paper.server.addPlayer("Builder")
            player.teleport(Location(world, 0.5, 64.0, 2.5))
            val stone = BuilderItemCodec.aggregate(listOf(ItemStack(Material.STONE))).single()
            val dirt = BuilderItemCodec.aggregate(listOf(ItemStack(Material.DIRT))).single()
            val project = project(player.uniqueId, world.uid, stone, dirt)
            world.getBlockAt(1, 64, 0).type = Material.BARREL
            val container = world.getBlockAt(1, 64, 0).state as Container
            container.inventory.addItem(ItemStack(Material.STONE))
            player.inventory.addItem(ItemStack(Material.STONE))
            val resources = BuilderConstructionResources(
                containerRadius = 4,
                onlineRange = 48.0,
                worldProvider = { world },
                onlinePlayerProvider = { player },
                canOpenContainer = { _, _ -> true },
            )

            resources.removeInput(player.uniqueId, project, stone) shouldBe true
            player.inventory.all(Material.STONE).values.sumOf(ItemStack::getAmount) shouldBe 0
            container.amount(Material.STONE) shouldBe 1

            resources.storeOutput(player.uniqueId, project, dirt) shouldBe true
            container.amount(Material.DIRT) shouldBe 1
            player.inventory.all(Material.DIRT).values.sumOf(ItemStack::getAmount) shouldBe 0
        }
    }
})
