package ru.arc.buildertools

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.type.Leaves
import org.bukkit.block.data.type.Slab
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.time.Duration
import java.util.UUID

class BuilderClipboardControllerTest : FunSpec({
    test("copy owns a bounded persistent-leaf snapshot and expires it exactly") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("BuilderClipboardCopyTest")
            val world = paper.addSimpleWorld("clipboard-copy")
            val player = paper.addPlayer("ClipboardCopyOwner")
            player.teleport(Location(world, 0.5, 64.0, 2.5))
            world.getBlockAt(0, 64, 0).type = Material.STONE
            world.getBlockAt(1, 64, 0).type = Material.OAK_LEAVES
            var now = 1_000L
            val harness = ClipboardHarness(plugin, nowMillis = { now })
            harness.select(player, world, 0, 64, 0, 1, 64, 0)

            harness.controller.use { controller ->
                val copied = controller.copy(player)

                copied.blocks.size shouldBe 2
                copied.blocks.map { it.dx } shouldBe listOf(0, 1)
                val leaves = Bukkit.createBlockData(copied.blocks.single { it.dx == 1 }.blockData) as Leaves
                leaves.isPersistent shouldBe true
                harness.copyPermissions shouldBe 1
                harness.readableBlocks shouldBe 2
                harness.protectedBlocks shouldBe 2
                controller.current(player.uniqueId) shouldBe copied

                now = copied.expiresAtMillis
                controller.current(player.uniqueId) shouldBe null
                controller.hasState(player.uniqueId) shouldBe false
            }
        }
    }

    test("paste charges exact survival materials and stays free in creative") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("BuilderClipboardPasteTest")
            val world = paper.addSimpleWorld("clipboard-paste")
            val player = paper.addPlayer("ClipboardPasteOwner")
            player.teleport(Location(world, 0.5, 64.0, 2.5))
            world.getBlockAt(0, 64, 0).type = Material.STONE
            val slab = paper.server.createBlockData(Material.OAK_SLAB) as Slab
            slab.type = Slab.Type.DOUBLE
            world.getBlockAt(1, 64, 0).blockData = slab
            val harness = ClipboardHarness(plugin)
            harness.select(player, world, 0, 64, 0, 1, 64, 0)

            harness.controller.use { controller ->
                controller.copy(player)
                player.teleport(Location(world, 10.5, 64.0, 12.5))

                player.gameMode = GameMode.SURVIVAL
                val survival = controller.planPaste(player)
                survival.kind shouldBe BuilderPlanKind.PASTE
                survival.changes.size shouldBe 2
                survival.changes.single { it.position.x == 11 }.afterBlockData shouldBe slab.asString
                survival.costs.sumOf { it.amount } shouldBe 3

                player.gameMode = GameMode.CREATIVE
                val creative = controller.planPaste(player)
                creative.changes.size shouldBe 2
                creative.costs shouldBe emptyList()
                harness.pastePermissions shouldBe 2
                harness.mutableBlocks shouldBe 4

                controller.close()
                controller.current(player.uniqueId) shouldBe null
                controller.hasState(player.uniqueId) shouldBe false
            }
        }
    }

    test("occupied paste target is skipped without mutation or unsafe-block spam") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("BuilderClipboardUnsafeTest")
            val world = paper.addSimpleWorld("clipboard-unsafe")
            val player = paper.addPlayer("ClipboardUnsafeOwner")
            player.teleport(Location(world, 0.5, 64.0, 2.5))
            world.getBlockAt(0, 64, 0).type = Material.STONE
            val harness = ClipboardHarness(plugin)
            harness.select(player, world, 0, 64, 0, 0, 64, 0)

            harness.controller.use { controller ->
                controller.copy(player)
                player.teleport(Location(world, 10.5, 64.0, 12.5))
                world.getBlockAt(10, 64, 10).type = Material.DEEPSLATE

                shouldThrow<ClipboardFailure> { controller.planPaste(player) }.path shouldBe "errors.nothing-to-change"
                harness.mutableBlocks shouldBe 0
                world.getBlockAt(10, 64, 10).type shouldBe Material.DEEPSLATE
            }
        }
    }

    test("oversized replacement copy keeps the last valid clipboard") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("BuilderClipboardLimitTest")
            val world = paper.addSimpleWorld("clipboard-limit")
            val player = paper.addPlayer("ClipboardLimitOwner")
            player.teleport(Location(world, 0.5, 64.0, 2.5))
            world.getBlockAt(0, 64, 0).type = Material.STONE
            world.getBlockAt(1, 64, 0).type = Material.DEEPSLATE
            val harness = ClipboardHarness(plugin, maximumBlocks = 1)
            harness.select(player, world, 0, 64, 0, 0, 64, 0)

            harness.controller.use { controller ->
                val original = controller.copy(player)
                harness.select(player, world, 0, 64, 0, 1, 64, 0)

                shouldThrow<ClipboardFailure> { controller.copy(player) }.path shouldBe "errors.selection-too-large"
                controller.current(player.uniqueId) shouldBe original

                controller.clear(player.uniqueId)
                controller.hasState(player.uniqueId) shouldBe false
            }
        }
    }
})

private class ClipboardHarness(
    plugin: Plugin,
    maximumBlocks: Int = 64,
    nowMillis: () -> Long = { 1_800_000_000_000L },
) {
    private val selections = BuilderSelectionController(
        previewRadius = 32.0,
        previewSpacing = 0.75,
        maximumOutlinePoints = 512,
    )
    private val safety = BuilderBlockSafety(plugin, setOf("AIR", "SHORT_GRASS"))
    var copyPermissions = 0
    var pastePermissions = 0
    var readableBlocks = 0
    var protectedBlocks = 0
    var mutableBlocks = 0

    val controller = BuilderClipboardController(
        safety = safety,
        selections = selections,
        maximumBlocks = maximumBlocks,
        clipboardTtl = Duration.ofMinutes(1),
        nowMillis = nowMillis,
        host = object : BuilderClipboardHost {
            override fun ensureCopyPermission(player: Player) {
                copyPermissions++
            }

            override fun ensurePastePermission(player: Player) {
                pastePermissions++
            }

            override fun requiredSelection(player: Player): BuilderSelection =
                selections.selection(player.uniqueId, player.world.uid)
                    ?.validated(maxAxis = 100, maxScanVolume = 10_000L)
                    ?: throw ClipboardFailure("errors.selection-missing")

            override fun world(worldId: UUID): World =
                Bukkit.getWorld(worldId) ?: throw ClipboardFailure("errors.world-not-allowed")

            override fun ensureInRangeAndLoaded(player: Player, block: Block) {
                readableBlocks++
            }

            override fun ensureProtected(player: Player, block: Block) {
                protectedBlocks++
            }

            override fun ensureMutable(player: Player, block: Block) {
                mutableBlocks++
            }

            override fun createPastePlan(
                player: Player,
                changes: List<BuilderBlockChange>,
                costs: List<BuilderItemAmount>,
                skippedUnsafeBlocks: Int,
            ): BuilderPlan {
                val now = nowMillis()
                return BuilderPlan(
                    id = UUID.randomUUID(),
                    playerId = player.uniqueId,
                    kind = BuilderPlanKind.PASTE,
                    changes = changes,
                    costs = costs,
                    rewards = emptyList(),
                    skippedUnsafeBlocks = skippedUnsafeBlocks,
                    createdAtMillis = now,
                    expiresAtMillis = now + 30_000L,
                ).validated()
            }

            override fun fail(path: String): Nothing = throw ClipboardFailure(path)
        },
    )

    fun select(player: Player, world: World, x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int) {
        selections.set(player.uniqueId, BuilderBlockPos(world.uid, x1, y1, z1), first = true)
        selections.set(player.uniqueId, BuilderBlockPos(world.uid, x2, y2, z2), first = false)
    }

    fun anchor(player: Player, world: World, x: Int, y: Int, z: Int) {
        selections.clear(player.uniqueId)
        selections.set(player.uniqueId, BuilderBlockPos(world.uid, x, y, z), first = true)
    }
}

private class ClipboardFailure(val path: String) : RuntimeException(path)
