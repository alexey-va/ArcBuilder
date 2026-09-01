package ru.arc.buildertools

import com.google.gson.GsonBuilder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.GameMode
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

    fun denseContainerPositions(): List<Triple<Int, Int, Int>> = buildList {
        for (y in 60..62) {
            for (z in -4..4) {
                add(Triple(-4, y, z))
            }
        }
    }

    test("foreign claimed chest is never used even when it is next to wilderness construction") {
        withResourcePaper { paper ->
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
        withResourcePaper { paper ->
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

    test("container discovery advances incrementally without exceeding the configured scan budget") {
        withResourcePaper { paper ->
            val world = paper.addSimpleWorld("construction-bounded-container-scan")
            world.getChunkAt(-1, -1).load()
            val ownerId = UUID.randomUUID()
            val stone = BuilderItemCodec.aggregate(listOf(ItemStack(Material.STONE))).single()
            val project = project(ownerId, world.uid, stone)
            world.getBlockAt(-4, 60, -3).type = Material.BARREL
            val container = world.getBlockAt(-4, 60, -3).state as Container
            container.inventory.addItem(ItemStack(Material.STONE))
            var blockLookups = 0
            val resources = BuilderConstructionResources(
                containerRadius = 4,
                onlineRange = 48.0,
                worldProvider = { world },
                onlinePlayerProvider = { null },
                canOpenContainer = { _, _ -> true },
                maxContainerProbesPerCall = 1,
                blockProvider = { sourceWorld, x, y, z ->
                    blockLookups += 1
                    sourceWorld.getBlockAt(x, y, z)
                },
            )

            resources.removeInput(ownerId, project, stone) shouldBe false
            blockLookups shouldBe 1
            container.amount(Material.STONE) shouldBe 1

            resources.removeInput(ownerId, project, stone) shouldBe true
            container.amount(Material.STONE) shouldBe 0
        }
    }

    test("dense container scans retain only the configured cache budget") {
        withResourcePaper { paper ->
            val world = paper.addSimpleWorld("construction-capped-container-cache")
            world.getChunkAt(-1, -1).load()
            world.getChunkAt(-1, 0).load()
            val ownerId = UUID.randomUUID()
            val stone = BuilderItemCodec.aggregate(listOf(ItemStack(Material.STONE))).single()
            val dirt = BuilderItemCodec.aggregate(listOf(ItemStack(Material.DIRT))).single()
            val project = project(ownerId, world.uid, stone)
            val densePositions = denseContainerPositions().take(20)
            densePositions.forEach { (x, y, z) -> world.getBlockAt(x, y, z).type = Material.BARREL }
            var blockLookups = 0
            val resources = BuilderConstructionResources(
                containerRadius = 4,
                onlineRange = 48.0,
                worldProvider = { world },
                onlinePlayerProvider = { null },
                canOpenContainer = { _, _ -> true },
                maxContainerProbesPerCall = 1,
                maxCachedContainersPerProject = 4,
                maxResolvedContainersPerCall = 4,
                blockProvider = { sourceWorld, x, y, z ->
                    blockLookups += 1
                    sourceWorld.getBlockAt(x, y, z)
                },
            )

            repeat(densePositions.size) { resources.removeInput(ownerId, project, stone) shouldBe false }
            blockLookups = 0

            resources.storeOutput(ownerId, project, dirt) shouldBe true

            (blockLookups <= 13) shouldBe true
        }
    }

    test("dense container scans resolve only the configured cached-source slice per call") {
        withResourcePaper { paper ->
            val world = paper.addSimpleWorld("construction-capped-container-resolution")
            world.getChunkAt(-1, -1).load()
            world.getChunkAt(-1, 0).load()
            val ownerId = UUID.randomUUID()
            val stone = BuilderItemCodec.aggregate(listOf(ItemStack(Material.STONE))).single()
            val dirt = BuilderItemCodec.aggregate(listOf(ItemStack(Material.DIRT))).single()
            val project = project(ownerId, world.uid, stone)
            val densePositions = denseContainerPositions().take(20)
            densePositions.forEach { (x, y, z) -> world.getBlockAt(x, y, z).type = Material.BARREL }
            var blockLookups = 0
            val resources = BuilderConstructionResources(
                containerRadius = 4,
                onlineRange = 48.0,
                worldProvider = { world },
                onlinePlayerProvider = { null },
                canOpenContainer = { _, _ -> true },
                maxContainerProbesPerCall = 1,
                maxCachedContainersPerProject = 20,
                maxResolvedContainersPerCall = 2,
                blockProvider = { sourceWorld, x, y, z ->
                    blockLookups += 1
                    sourceWorld.getBlockAt(x, y, z)
                },
            )

            repeat(densePositions.size) { resources.removeInput(ownerId, project, stone) shouldBe false }
            blockLookups = 0

            resources.storeOutput(ownerId, project, dirt) shouldBe true

            (blockLookups <= 9) shouldBe true
            densePositions.sumOf { (x, y, z) ->
                (world.getBlockAt(x, y, z).state as Container).amount(Material.DIRT)
            } shouldBe 1
        }
    }

    test("nearby player inventory supplies input first while container receives output first") {
        withResourcePaper { paper ->
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

    test("creative owner receives recovered blocks in an empty inventory when no container exists") {
        withResourcePaper { paper ->
            val world = paper.addSimpleWorld("construction-creative-output")
            world.getChunkAt(0, 0).load()
            val player = paper.server.addPlayer("CreativeOutputOwner").also {
                it.gameMode = GameMode.CREATIVE
                it.teleport(Location(world, 0.5, 64.0, 1.5))
            }
            val stone = BuilderItemCodec.aggregate(listOf(ItemStack(Material.STONE))).single()
            val andesite = BuilderItemCodec.aggregate(listOf(ItemStack(Material.ANDESITE))).single()
            val project = project(player.uniqueId, world.uid, stone, andesite)
            val resources = BuilderConstructionResources(
                containerRadius = 4,
                onlineRange = 48.0,
                worldProvider = { world },
                onlinePlayerProvider = { player },
                canOpenContainer = { _, _ -> true },
            )

            resources.storeOutput(player.uniqueId, project, andesite) shouldBe true

            player.inventory.all(Material.ANDESITE).values.sumOf(ItemStack::getAmount) shouldBe 1
        }
    }

    test("durable player debit receipt is write-ahead and replaying it never charges twice") {
        withResourcePaper { paper ->
            val world = paper.addSimpleWorld("construction-idempotent-player-debit")
            world.getChunkAt(0, 0).load()
            val player = paper.server.addPlayer("ReceiptOwner")
            player.teleport(Location(world, 0.5, 64.0, 1.5))
            player.inventory.addItem(ItemStack(Material.STONE))
            val stone = BuilderItemCodec.aggregate(listOf(ItemStack(Material.STONE))).single()
            val project = project(player.uniqueId, world.uid, stone)
            val resources = BuilderConstructionResources(
                containerRadius = 4,
                onlineRange = 48.0,
                worldProvider = { world },
                onlinePlayerProvider = { player },
                canOpenContainer = { _, _ -> true },
            )

            val receipt = checkNotNull(resources.prepareInput(player.uniqueId, project, stone))
            player.inventory.all(Material.STONE).values.sumOf(ItemStack::getAmount) shouldBe 1

            resources.reconcile(project, receipt) shouldBe BuilderResourceMutationResult.APPLIED
            player.inventory.all(Material.STONE).values.sumOf(ItemStack::getAmount) shouldBe 0
            resources.reconcile(project, receipt) shouldBe BuilderResourceMutationResult.APPLIED
            player.inventory.all(Material.STONE).values.sumOf(ItemStack::getAmount) shouldBe 0
        }
    }

    test("durable output receipt inserts once and recognizes its exact after snapshot on replay") {
        withResourcePaper { paper ->
            val world = paper.addSimpleWorld("construction-idempotent-output")
            world.getChunkAt(0, 0).load()
            val ownerId = UUID.randomUUID()
            val stone = BuilderItemCodec.aggregate(listOf(ItemStack(Material.STONE))).single()
            val dirt = BuilderItemCodec.aggregate(listOf(ItemStack(Material.DIRT))).single()
            val project = project(ownerId, world.uid, stone, dirt)
            world.getBlockAt(1, 64, 0).type = Material.BARREL
            val container = world.getBlockAt(1, 64, 0).state as Container
            val resources = BuilderConstructionResources(
                containerRadius = 4,
                onlineRange = 48.0,
                worldProvider = { world },
                onlinePlayerProvider = { null },
                canOpenContainer = { _, _ -> true },
            )

            val receipt = checkNotNull(resources.prepareOutput(ownerId, project, dirt))
            container.amount(Material.DIRT) shouldBe 0
            resources.reconcile(project, receipt) shouldBe BuilderResourceMutationResult.APPLIED
            container.amount(Material.DIRT) shouldBe 1
            resources.reconcile(project, receipt) shouldBe BuilderResourceMutationResult.APPLIED
            container.amount(Material.DIRT) shouldBe 1
        }
    }

    test("stale receipt never overwrites inventory changes made before its first application") {
        withResourcePaper { paper ->
            val world = paper.addSimpleWorld("construction-stale-receipt")
            world.getChunkAt(0, 0).load()
            val player = paper.server.addPlayer("ReceiptDrift")
            player.teleport(Location(world, 0.5, 64.0, 1.5))
            player.inventory.addItem(ItemStack(Material.STONE))
            val stone = BuilderItemCodec.aggregate(listOf(ItemStack(Material.STONE))).single()
            val project = project(player.uniqueId, world.uid, stone)
            val resources = BuilderConstructionResources(
                containerRadius = 4,
                onlineRange = 48.0,
                worldProvider = { world },
                onlinePlayerProvider = { player },
                canOpenContainer = { _, _ -> true },
            )
            val receipt = checkNotNull(resources.prepareInput(player.uniqueId, project, stone))
            player.inventory.clear()
            player.inventory.addItem(ItemStack(Material.DIRT, 3))

            resources.reconcile(project, receipt) shouldBe BuilderResourceMutationResult.STALE
            player.inventory.all(Material.DIRT).values.sumOf(ItemStack::getAmount) shouldBe 3
            player.inventory.all(Material.STONE).values.sumOf(ItemStack::getAmount) shouldBe 0
        }
    }

    test("access revocation after planning conflicts without touching the protected container") {
        withResourcePaper { paper ->
            val world = paper.addSimpleWorld("construction-receipt-access-revoked")
            world.getChunkAt(0, 0).load()
            val ownerId = UUID.randomUUID()
            val stone = BuilderItemCodec.aggregate(listOf(ItemStack(Material.STONE))).single()
            val project = project(ownerId, world.uid, stone)
            world.getBlockAt(1, 64, 0).type = Material.BARREL
            val container = world.getBlockAt(1, 64, 0).state as Container
            container.inventory.addItem(ItemStack(Material.STONE))
            var allowed = true
            val resources = BuilderConstructionResources(
                containerRadius = 4,
                onlineRange = 48.0,
                worldProvider = { world },
                onlinePlayerProvider = { null },
                canOpenContainer = { _, _ -> allowed },
            )

            val receipt = checkNotNull(resources.prepareInput(ownerId, project, stone))
            allowed = false

            resources.reconcile(project, receipt) shouldBe BuilderResourceMutationResult.CONFLICT
            container.amount(Material.STONE) shouldBe 1
        }
    }

    test("JSON crash replay applies a pending player receipt once in a fresh resource service") {
        withResourcePaper { paper ->
            val world = paper.addSimpleWorld("construction-receipt-json-replay")
            world.getChunkAt(0, 0).load()
            val player = paper.server.addPlayer("ReceiptReplay")
            player.teleport(Location(world, 0.5, 64.0, 1.5))
            player.inventory.addItem(ItemStack(Material.STONE))
            val stone = BuilderItemCodec.aggregate(listOf(ItemStack(Material.STONE))).single()
            val project = project(player.uniqueId, world.uid, stone).activated(1_800_000_000_001L)
            val resources = BuilderConstructionResources(
                containerRadius = 4,
                onlineRange = 48.0,
                worldProvider = { world },
                onlinePlayerProvider = { player },
                canOpenContainer = { _, _ -> true },
            )

            val receipt = checkNotNull(resources.prepareInput(player.uniqueId, project, stone))
            val pending = project.inputPrepared(receipt, 1_800_000_000_002L)
            val reloaded = GsonBuilder().create()
                .fromJson(GsonBuilder().create().toJson(pending), BuilderConstructionProjectRecord::class.java)
                .validated()

            resources.reconcile(project, receipt) shouldBe BuilderResourceMutationResult.APPLIED
            val restartedResources = BuilderConstructionResources(
                containerRadius = 4,
                onlineRange = 48.0,
                worldProvider = { world },
                onlinePlayerProvider = { player },
                canOpenContainer = { _, _ -> true },
            )
            restartedResources.reconcile(reloaded, checkNotNull(reloaded.pendingResourceMutation)) shouldBe
                BuilderResourceMutationResult.APPLIED
            player.inventory.all(Material.STONE).values.sumOf(ItemStack::getAmount) shouldBe 0
        }
    }

})

private fun <T> withResourcePaper(block: (MockBukkitTestRuntime) -> T): T =
    failOnUnsupportedMockBukkitOperation { MockBukkitTestRuntime.open().use(block) }
