package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.data.type.Bed
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class BuilderPlanningCaptureTest : FunSpec({
    test("native prototype capture encodes each exact metadata identity once and matches the previous exchange") {
        MockBukkitTestRuntime.open().use {
            var encodes = 0
            val cache = BuilderItemCodec.PrototypeCache { item -> encodes++; BuilderItemCodec.encodePrototype(item) }
            val named = ItemStack(Material.STONE, 2).apply { editMeta { it.displayName(Component.text("Exact")) } }
            val other = named.clone().apply { editMeta { it.displayName(Component.text("Other")) } }
            val inputs = listOf(named, named.clone().also { it.amount = 7 }, other, named.clone())
            val captured = inputs.map(cache::capture)
            encodes shouldBe 2
            val expected = BuilderItemCodec.aggregate(inputs)
            CompletableFuture.supplyAsync { BuilderItemCodec.aggregateAmounts(captured) }.get(5, TimeUnit.SECONDS) shouldBe expected
            BuilderItemCodec.decodePrototype(captured.first().itemBase64).isSimilar(named) shouldBe true
        }
    }

    test("detached multiblock ordering and rejection exactly match native pair policy") {
        MockBukkitTestRuntime.open().use {
            val footPos = BuilderBlockPos(UUID.randomUUID(), 0, 64, 0)
            val headPos = footPos.copy(x = 1)
            val foot = (Material.RED_BED.createBlockData() as Bed).apply { part = Bed.Part.FOOT; facing = BlockFace.EAST }
            val head = (Material.RED_BED.createBlockData() as Bed).apply { part = Bed.Part.HEAD; facing = BlockFace.EAST }
            val native = listOf(
                BuilderBookPlannedCell(headPos, head, BuilderBookPlacementResult.Change(BuilderBlockChange(headPos, "minecraft:air", head.asString), null, null)),
                BuilderBookPlannedCell(footPos, foot, BuilderBookPlacementResult.Change(BuilderBlockChange(footPos, "minecraft:air", foot.asString), null, null)),
            )
            listOf(native, native.drop(1), native.mapIndexed { index, cell -> if (index == 0) cell.copy(placement = BuilderBookPlacementResult.SkippedUnsafe) else cell }).forEach { cells ->
                val captured = cells.map { cell -> BuilderBookMultiBlockPolicy.capture(cell,
                    (cell.placement as? BuilderBookPlacementResult.Change)?.let { BuilderBookCapturedChange(it.block, null, null) }) }
                BuilderBookMultiBlockPolicy.rejectedCaptured(captured) shouldBe BuilderBookMultiBlockPolicy.rejectedPositions(cells)
                BuilderBookMultiBlockPolicy.primaryFirstCaptured(captured).map { it.position } shouldBe
                    BuilderBookMultiBlockPolicy.primaryFirst(cells).map { it.position }
            }
        }
    }

    test("journal construction performs no filesystem work before the worker initializes it") {
        val root = Files.createTempDirectory("builder-journal-lazy-")
        try {
            val general = BuilderJournalStore(root, 10)
            val construction = BuilderConstructionProjectStore(root, 10)
            val drafts = BuilderDraftJournal(root, 10)
            Files.list(root).use { it.count() } shouldBe 0L
            CompletableFuture.runAsync {
                general.loadAll() shouldBe emptyList()
                construction.loadAll() shouldBe emptyList()
                drafts.loadAll() shouldBe emptyList()
            }.get(5, TimeUnit.SECONDS)
            Files.isDirectory(root.resolve("data/builder-tools-journal")) shouldBe true
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
})
