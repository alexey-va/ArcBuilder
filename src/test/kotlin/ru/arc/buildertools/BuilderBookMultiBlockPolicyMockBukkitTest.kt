package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.type.Bed
import org.bukkit.block.data.type.Door
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class BuilderBookMultiBlockPolicyMockBukkitTest : FunSpec({
    val worldId = UUID.fromString("11111111-2222-3333-4444-555555555555")

    fun change(position: BuilderBlockPos, after: BlockData) = BuilderBookPlacementResult.Change(
        block = BuilderBlockChange(position, "minecraft:air", after.asString),
        placementItem = null,
        refund = null,
    )

    test("unsafe bed head rejects the otherwise safe foot as one atomic pair") {
        MockBukkitTestRuntime.open().use {
            val footPosition = BuilderBlockPos(worldId, 10, 64, 10)
            val headPosition = BuilderBlockPos(worldId, 11, 64, 10)
            val foot = (Material.RED_BED.createBlockData() as Bed).apply {
                facing = BlockFace.EAST
                part = Bed.Part.FOOT
            }
            val head = (Material.RED_BED.createBlockData() as Bed).apply {
                facing = BlockFace.EAST
                part = Bed.Part.HEAD
            }

            BuilderBookMultiBlockPolicy.rejectedPositions(
                listOf(
                    BuilderBookPlannedCell(footPosition, foot, change(footPosition, foot)),
                    BuilderBookPlannedCell(headPosition, head, BuilderBookPlacementResult.SkippedUnsafe),
                ),
            ).shouldContainExactlyInAnyOrder(footPosition, headPosition)
        }
    }

    test("safe complete bed remains in the plan") {
        MockBukkitTestRuntime.open().use {
            val footPosition = BuilderBlockPos(worldId, 20, 64, 20)
            val headPosition = BuilderBlockPos(worldId, 20, 64, 19)
            val foot = (Material.WHITE_BED.createBlockData() as Bed).apply {
                facing = BlockFace.NORTH
                part = Bed.Part.FOOT
            }
            val head = (Material.WHITE_BED.createBlockData() as Bed).apply {
                facing = BlockFace.NORTH
                part = Bed.Part.HEAD
            }

            BuilderBookMultiBlockPolicy.rejectedPositions(
                listOf(
                    BuilderBookPlannedCell(footPosition, foot, change(footPosition, foot)),
                    BuilderBookPlannedCell(headPosition, head, change(headPosition, head)),
                ),
            ) shouldBe emptySet()
        }
    }

    test("missing or unsafe door half rejects both coordinates") {
        MockBukkitTestRuntime.open().use {
            val bottomPosition = BuilderBlockPos(worldId, 4, 70, 4)
            val topPosition = bottomPosition.copy(y = 71)
            val bottom = (Material.OAK_DOOR.createBlockData() as Door).apply { half = Bisected.Half.BOTTOM }
            val top = (Material.OAK_DOOR.createBlockData() as Door).apply { half = Bisected.Half.TOP }

            BuilderBookMultiBlockPolicy.rejectedPositions(
                listOf(BuilderBookPlannedCell(bottomPosition, bottom, change(bottomPosition, bottom))),
            ).shouldContainExactlyInAnyOrder(bottomPosition, topPosition)

            BuilderBookMultiBlockPolicy.rejectedPositions(
                listOf(
                    BuilderBookPlannedCell(bottomPosition, bottom, BuilderBookPlacementResult.SkippedUnsafe),
                    BuilderBookPlannedCell(topPosition, top, change(topPosition, top)),
                ),
            ).shouldContainExactlyInAnyOrder(bottomPosition, topPosition)
        }
    }

    test("single-block bisected trapdoor is not mistaken for a missing double block") {
        MockBukkitTestRuntime.open().use {
            val position = BuilderBlockPos(worldId, 8, 70, 8)
            val trapdoor = Material.OAK_TRAPDOOR.createBlockData()

            BuilderBookMultiBlockPolicy.rejectedPositions(
                listOf(BuilderBookPlannedCell(position, trapdoor, change(position, trapdoor))),
            ) shouldBe emptySet()
        }
    }
})
