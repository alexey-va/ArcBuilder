package ru.arc.autobuild

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.world.block.BaseBlock
import com.sk89q.worldedit.world.block.BlockState
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.bukkit.block.data.BlockData
import ru.arc.buildertools.BuilderClipboard
import ru.arc.buildertools.BuilderClipboardBlock
import ru.arc.buildertools.QueuedBuilderPlanningTasks
import ru.arc.buildertools.awaitBuilderPlanning
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.concurrent.CancellationException

class PlayerBuildBookPreparationTest : FunSpec({
    test("player schematic capture spans ticks and WorldEdit assembly runs on the worker") {
        MockBukkitTestRuntime.open().use {
            val tasks = QueuedBuilderPlanningTasks()
            val owner = UUID.randomUUID()
            val source = preparationClipboard(300)
            val mainThread = Thread.currentThread()
            var captured = 0
            var assembled = 0
            val state = mockk<BlockState>()
            val block = mockk<BaseBlock>()
            every { state.toBaseBlock() } answers {
                (Thread.currentThread() === mainThread) shouldBe false
                assembled++
                block
            }
            mockkStatic(BukkitAdapter::class)
            try {
                every { BukkitAdapter.adapt(any<BlockData>()) } answers {
                    tasks.assertMain()
                    captured++
                    state
                }
                val pending = PlayerBuildBookStore.prepareAsync(owner, source, tasks) { true }
                pending.isDone shouldBe false
                captured shouldBe 0
                tasks.runComputations()
                tasks.tick(128)
                captured shouldBe 128
                assembled shouldBe 0
                pending.isDone shouldBe false
                tasks.tick(128)
                captured shouldBe 256
                pending.isDone shouldBe false
                val prepared = tasks.finish(pending)

                captured shouldBe 300
                assembled shouldBe 300
                tasks.workerRuns shouldBe 2
                prepared.blockCount shouldBe source.blocks.size
                prepared.contentSha256 shouldBe PlayerBuildBookStore.contentSha256(source)
                prepared.fileName shouldBe PlayerBuildBookStore.fileName(owner, source)
                prepared.fileName.contains("-o-3_2_7-") shouldBe true
            } finally {
                unmockkStatic(BukkitAdapter::class)
            }
        }
    }

    test("changed draft context stops native capture before clipboard assembly") {
        MockBukkitTestRuntime.open().use {
            val tasks = QueuedBuilderPlanningTasks()
            var current = true
            var captured = 0
            val state = mockk<BlockState>()
            mockkStatic(BukkitAdapter::class)
            try {
                every { BukkitAdapter.adapt(any<BlockData>()) } answers {
                    tasks.assertMain()
                    captured++
                    state
                }
                val pending = PlayerBuildBookStore.prepareAsync(UUID.randomUUID(), preparationClipboard(300), tasks) { current }
                tasks.runComputations()
                tasks.tick(1)
                captured shouldBe 1
                current = false
                tasks.tick()
                tasks.runComputations()

                shouldThrow<CancellationException> { pending.awaitBuilderPlanning() }
                captured shouldBe 1
                tasks.workerRuns shouldBe 1
            } finally {
                unmockkStatic(BukkitAdapter::class)
            }
        }
    }
})

private fun preparationClipboard(count: Int): BuilderClipboard {
    val now = System.currentTimeMillis()
    return BuilderClipboard(
        blocks = (0 until count).reversed().map { index ->
            BuilderClipboardBlock(index % 256, index / 256, 0, "minecraft:stone")
        },
        sizeX = 256,
        sizeY = 2,
        sizeZ = 1,
        createdAtMillis = now,
        expiresAtMillis = now + 60_000,
        originDx = -3,
        originDy = 2,
        originDz = 7,
        sourceRotation = 270,
    )
}
