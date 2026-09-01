package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.data.type.Bed
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class BuilderConstructionAtomicCompanionMockBukkitTest : FunSpec({
    test("an underwater bed placed atomically over water and seagrass does not stop on its head") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.server.addSimpleWorld("underwater-construction")
            val footBlock = world.getBlockAt(10, 46, 10)
            val headBlock = world.getBlockAt(10, 46, 9)
            footBlock.setBlockData(Material.WATER.createBlockData(), false)
            headBlock.setBlockData(Material.SEAGRASS.createBlockData(), false)
            val foot = (Material.BROWN_BED.createBlockData() as Bed).apply {
                facing = BlockFace.NORTH
                part = Bed.Part.FOOT
            }
            val head = (Material.BROWN_BED.createBlockData() as Bed).apply {
                facing = BlockFace.NORTH
                part = Bed.Part.HEAD
            }
            val footChange = BuilderBlockChange(
                BuilderBlockPos(world.uid, footBlock.x, footBlock.y, footBlock.z),
                footBlock.blockData.asString,
                foot.asString,
            )
            val headChange = BuilderBlockChange(
                BuilderBlockPos(world.uid, headBlock.x, headBlock.y, headBlock.z),
                headBlock.blockData.asString,
                head.asString,
            )
            val projectId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
            val playerId = UUID.fromString("11111111-2222-3333-4444-555555555555")
            val now = 1_800_000_000_000L
            val book = BuilderItemAmount("BBBB", "minecraft:book", 1)
            val plan = BuilderPlan(
                id = projectId,
                playerId = playerId,
                kind = BuilderPlanKind.BUILD_BOOK,
                changes = listOf(footChange, headChange),
                costs = listOf(book),
                rewards = emptyList(),
                createdAtMillis = now,
                expiresAtMillis = now + 60_000,
            )
            val project = BuilderConstructionProjectRecord(
                projectId = projectId,
                playerId = playerId,
                playerName = "Builder",
                projectTitle = "Подводный дом",
                plan = plan,
                steps = listOf(
                    BuilderConstructionStep(footChange, requiredMaterial = null, output = null),
                    BuilderConstructionStep(headChange, requiredMaterial = null, output = null),
                ),
                bookCost = book,
                state = BuilderConstructionProjectState.PREPARED,
                cursor = 0,
                createdAtMillis = now,
                updatedAtMillis = now,
            ).validated().activated(now + 1)
            val port = object : BuilderConstructionProjectPort {
                override fun currentBlockData(position: BuilderBlockPos): String =
                    world.getBlockAt(position.x, position.y, position.z).blockData.asString

                override fun canModify(
                    project: BuilderConstructionProjectRecord,
                    step: BuilderConstructionStep,
                ): Boolean = true

                override fun prepareInput(
                    playerId: UUID,
                    project: BuilderConstructionProjectRecord,
                    input: BuilderItemAmount,
                ): BuilderResourceMutation? = error("bed pair has no per-step input")

                override fun prepareOutput(
                    playerId: UUID,
                    project: BuilderConstructionProjectRecord,
                    output: BuilderItemAmount,
                ): BuilderResourceMutation? = error("bed pair has no output")

                override fun reconcileResource(
                    project: BuilderConstructionProjectRecord,
                    mutation: BuilderResourceMutation,
                ): BuilderResourceMutationResult = error("bed pair has no resource receipt")

                override fun rollbackResource(
                    project: BuilderConstructionProjectRecord,
                    mutation: BuilderResourceMutation,
                ): BuilderResourceMutationResult = error("bed pair has no resource receipt")

                override fun apply(project: BuilderConstructionProjectRecord, step: BuilderConstructionStep) {
                    footBlock.setBlockData(foot, false)
                    headBlock.setBlockData(head, false)
                }
            }

            val worldPrepared = BuilderConstructionProjectController.tick(project, now + 2, port)
            worldPrepared?.state shouldBe BuilderConstructionProjectState.WORLD_PREPARED
            val companionPending = BuilderConstructionProjectController.tick(checkNotNull(worldPrepared), now + 3, port)
            companionPending?.state shouldBe BuilderConstructionProjectState.ACTIVE
            companionPending?.cursor shouldBe 1
            headBlock.blockData.asString shouldBe head.asString

            val completed = BuilderConstructionProjectController.tick(checkNotNull(companionPending), now + 4, port)

            completed?.state shouldBe BuilderConstructionProjectState.COMPLETED
            completed?.cursor shouldBe 2
        }
    }
})
