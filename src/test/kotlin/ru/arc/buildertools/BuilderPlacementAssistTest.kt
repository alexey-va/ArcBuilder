package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.WorldBorder
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.util.BoundingBox
import org.bukkit.util.RayTraceResult
import org.bukkit.util.Vector
import org.bukkit.util.VoxelShape
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.text.LocalizedMiniMessage
import java.util.UUID
import kotlin.math.abs

class BuilderPlacementAssistTest : FunSpec({
    test("two committed native blocks expose one offer and ticks never place") {
        AssistFixture().use { f ->
            f.helper.command(f.player, listOf("on"))
            f.place(f.position(0))
            f.helper.tick()
            f.packetAdmit shouldBe null
            f.scheduler.tick()
            f.place(f.position(1))
            f.scheduler.tick()
            (f.packetAdmit != null) shouldBe true
            repeat(25) { f.helper.tick() }
            f.calls shouldBe 0
            f.offer()?.lock?.next shouldBe f.position(2)
            f.offer() shouldBe null
            verify { f.renderer.placementAssist(f.player, f.position(2), false) }
        }
    }

    test("late cancellation cannot establish a line or admit input") {
        AssistFixture().use { f ->
            f.helper.command(f.player, listOf("on"))
            f.place(f.position(0))
            f.place(f.position(1)).isCancelled = true
            f.scheduler.tick()
            f.helper.tick()
            f.packetAdmit shouldBe null
            f.calls shouldBe 0
        }
    }

    test("fresh use dispatches once while off slot change stale connection and context invalidate it") {
        for (exit in listOf("none", "off", "slot", "connection", "context", "closed")) {
            AssistFixture().use { f ->
                f.seed()
                val input = f.input(checkNotNull(f.offer()))
                when (exit) {
                    "off" -> f.helper.command(f.player, listOf("off"))
                    "slot" -> f.helper.onSlot(PlayerItemHeldEvent(f.player, 0, 1))
                    "connection" -> f.channel = Any()
                    "context" -> f.allowed = false
                    "closed" -> f.helper.close()
                }
                f.helper.accept(input)
                f.calls shouldBe if (exit == "none") 1 else 0
                f.helper.isEnabled(f.player) shouldBe (exit != "off")
            }
        }
    }

    test("looking substantially away walls unloaded chunks and protection cannot offer placement") {
        for (reason in listOf("aim", "wall", "chunk", "protection")) {
            AssistFixture().use { f ->
                f.seed()
                f.offer()
                when (reason) {
                    "aim" -> f.eye.direction = Vector(-1, 0, 0)
                    "wall" -> every { f.world.rayTraceBlocks(any<Location>(), any(), any(), any(), any()) } returns
                        RayTraceResult(f.eye.toVector().add(f.eye.direction.multiply(.5)))
                    "chunk" -> every { f.world.isChunkLoaded(any<Int>(), any<Int>()) } returns false
                    "protection" -> f.buildAllowed = false
                }
                f.helper.tick()
                f.offer() shouldBe null
                f.calls shouldBe 0
                if (reason == "protection") verify { f.renderer.placementAssist(f.player, f.position(2), true) }
            }
        }
    }

    test("line snapping works off the exact cell while crouching and incidental placement preserve direction") {
        AssistFixture().use { f ->
            f.seed()
            val original = checkNotNull(f.offer()).lock
            every { f.player.isSneaking } returns true
            f.aim(Vector(3.5, 63.9, 1.8))
            BuilderPlacementAssistGeometry.aimDistance(f.eye.toVector(), f.eye.direction, f.position(2), 4.5) shouldBe null
            f.place(f.position(0, 64, -1))
            f.scheduler.tick()
            f.helper.tick()
            val offer = checkNotNull(f.offer())
            (offer.lock === original) shouldBe true
            offer.target.position shouldBe f.position(2)
            f.onNative = f::nativeSuccess
            f.helper.accept(f.input(offer))
            f.calls shouldBe 1
            f.material(f.position(2)) shouldBe Material.STONE
        }
    }

    test("a visible next cell still needs an unobstructed path to its actual support") {
        AssistFixture().use { f ->
            f.seed()
            f.offer()
            f.eye.z = -1.5
            f.aim(BuilderPlacementAssistGeometry.center(f.position(2)))
            val obstruction = f.position(0, 65, -1)
            f.set(obstruction, Material.STONE)
            f.helper.tick()
            f.offer() shouldBe null
            f.set(obstruction, Material.AIR)
            f.helper.tick()
            f.offer()?.target?.position shouldBe f.position(2)
            f.calls shouldBe 0
        }
    }

    test("native refusal never advances the constraint or reoffers before its visible cooldown") {
        AssistFixture().use { f ->
            f.seed()
            f.helper.accept(f.input(checkNotNull(f.offer())))
            f.offer() shouldBe null
            verify { f.renderer.placementAssist(f.player, f.position(2), true) }
            verify { f.player.sendActionBar(Component.text("assist.refused")) }
            repeat(20) { f.helper.tick() }
            f.offer()?.lock?.last shouldBe f.position(1)
            f.onNative = f::nativeSuccess
            f.helper.tick()
            f.helper.accept(f.input(checkNotNull(f.offer())))
            f.aim(BuilderPlacementAssistGeometry.center(f.position(3)))
            f.helper.tick()
            f.offer()?.lock?.last shouldBe f.position(2)
        }
    }

    test("full cube scope excludes slab shapes and genuinely customized item stacks") {
        AssistFixture().use { f ->
            f.helper.command(f.player, listOf("on"))
            val slab = f.block(f.position(0))
            every { slab.collisionShape.boundingBoxes } returns listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, .5, 1.0))
            f.place(f.position(0)); f.place(f.position(1))
            f.scheduler.tick()
            f.helper.tick()
            f.packetAdmit shouldBe null
        }
        AssistFixture().use { f ->
            f.player.inventory.setItemInMainHand(ItemStack(Material.STONE, 32).apply {
                editMeta { it.displayName(Component.text("Custom stone")) }
            })
            f.helper.command(f.player, listOf("on"))
            f.place(f.position(0)); f.place(f.position(1))
            f.scheduler.tick()
            f.helper.tick()
            f.packetAdmit shouldBe null
        }
    }

    test("consuming the last held item completes placement without a false refusal") {
        AssistFixture().use { f ->
            f.seed()
            f.player.inventory.setItemInMainHand(ItemStack(Material.STONE, 1))
            f.onNative = f::nativeSuccess
            f.helper.accept(f.input(checkNotNull(f.offer())))
            f.calls shouldBe 1
            f.helper.tick()
            f.offer() shouldBe null
            verify(exactly = 0) { f.player.sendActionBar(Component.text("assist.refused")) }
        }
    }

    test("plane mode selects adjacent supported cells on a floor and both wall axes") {
        for (normal in listOf(BlockFace.UP, BlockFace.EAST, BlockFace.SOUTH)) {
            AssistFixture().use { f ->
                val anchor = f.position(1, 64, 1)
                val target = if (normal == BlockFace.UP) anchor.relative(BlockFace.EAST) else anchor.relative(BlockFace.UP)
                if (normal == BlockFace.EAST) f.eye.x = -1.0
                if (normal == BlockFace.SOUTH) f.eye.z = -1.0
                f.helper.command(f.player, listOf("plane"))
                f.place(anchor, anchor.relative(normal.oppositeFace))
                f.scheduler.tick()
                f.aim(BuilderPlacementAssistGeometry.center(target))
                f.helper.tick()
                val offer = checkNotNull(f.offer())
                offer.lock.planeNormal shouldBe normal
                offer.target.position shouldBe target
                offer.target.support shouldBe anchor
                f.onNative = f::nativeSuccess
                f.helper.accept(f.input(offer))
                f.material(target) shouldBe Material.STONE
                f.player.inventory.itemInMainHand.amount shouldBe 31
            }
        }
    }

    test("plane stops on occupied projection and never uses floating unsafe or interactive support") {
        for (reason in listOf("occupied", "floating", "unsafe", "interactive")) {
            AssistFixture().use { f ->
                val anchor = f.position(0)
                f.helper.command(f.player, listOf("plane"))
                f.place(anchor, anchor.relative(BlockFace.DOWN))
                f.scheduler.tick()
                val target = when (reason) {
                    "occupied" -> anchor
                    "floating" -> f.position(3, 64, 2)
                    else -> f.position(1)
                }
                if (reason == "unsafe") f.safeSupport = { false }
                if (reason == "interactive") f.set(anchor, Material.CHEST)
                f.aim(BuilderPlacementAssistGeometry.center(target))
                f.helper.tick()
                f.offer() shouldBe null
                f.calls shouldBe 0
                if (reason == "occupied") verify { f.renderer.placementAssist(f.player, anchor, true) }
            }
        }
    }

    test("mode and preference persist while reset explicitly chooses a new anchor") {
        AssistFixture().use { f ->
            f.seed()
            f.helper.command(f.player, listOf("plane"))
            f.helper.mode(f.player) shouldBe BuilderPlacementAssistMode.PLANE
            f.offer() shouldBe null
            f.helper.command(f.player, listOf("off"))
            f.helper.command(f.player, listOf("on"))
            f.helper.mode(f.player) shouldBe BuilderPlacementAssistMode.PLANE
            f.helper.command(f.player, listOf("reset"))
            f.helper.isEnabled(f.player) shouldBe true
            val anchor = f.position(0)
            f.place(anchor, anchor.relative(BlockFace.DOWN))
            f.scheduler.tick()
            f.aim(BuilderPlacementAssistGeometry.center(f.position(0, 64, 1)))
            f.helper.tick()
            f.offer()?.lock?.planeNormal shouldBe BlockFace.UP
        }
    }

    test("background use-on redirects one native block and its air fallback cannot spend again that tick") {
        AssistFixture().use { f ->
            f.seedBackground()
            val hit = f.hit()
            f.offer(hit.copy(x = 100)) shouldBe null // an unrelated ordinary interaction stays vanilla
            val offer = checkNotNull(f.offer(hit))
            val input = f.input(offer, click = true)
            f.offer() shouldBe null // USE_ITEM immediately following USE_ITEM_ON cannot claim the offer
            f.onNative = { support, face ->
                f.offer() shouldBe null
                f.nativeSuccess(support, face)
            }
            f.receive(input)
            f.scheduler.executeImmediate()
            f.helper.accept(input) // duplicate/reentrant input remains fenced until the next runtime tick
            f.calls shouldBe 1
            f.player.inventory.itemInMainHand.amount shouldBe 31
            f.material(f.position(2)) shouldBe Material.STONE
            f.material(f.position(3, 64, 1)) shouldBe Material.AIR
            f.offer() shouldBe null
            verify(exactly = 2) { f.player.updateInventory() }
            verify(exactly = 4) { f.player.sendBlockChange(any<Location>(), any<BlockData>()) }
        }
    }

    test("refused redirected use-on leaves both positions and inventory unchanged and corrects prediction") {
        AssistFixture().use { f ->
            f.seedBackground()
            f.helper.accept(f.input(checkNotNull(f.offer(f.hit())), click = true))
            f.calls shouldBe 1
            f.material(f.position(2)) shouldBe Material.AIR
            f.material(f.position(3, 64, 1)) shouldBe Material.AIR
            f.player.inventory.itemInMainHand.amount shouldBe 32
            verify(exactly = 1) { f.player.updateInventory() }
            verify(exactly = 2) { f.player.sendBlockChange(any<Location>(), any<BlockData>()) }
        }
    }

    test("off close and rejected scheduler drain a captured prediction without entering native placement") {
        for (exit in listOf("off", "closed", "scheduler")) {
            AssistFixture().use { f ->
                f.seedBackground()
                val input = f.input(checkNotNull(f.offer(f.hit())), click = true)
                if (exit == "scheduler") f.tasks.close()
                f.receive(input)
                if (exit == "off") f.helper.command(f.player, listOf("off")) else f.helper.close()
                f.scheduler.executeImmediate()
                f.calls shouldBe 0
                verify(exactly = 1) { f.player.updateInventory() }
                verify(exactly = 2) { f.player.sendBlockChange(any<Location>(), any<BlockData>()) }
            }
        }
    }
})

private class AssistFixture : AutoCloseable {
    private val paper = MockBukkitTestRuntime.open()
    private val plugin = paper.createSimplePlugin("PlacementAssistTest")
    private val backing = paper.addPlayer("Builder")
    val player = mockk<Player>(relaxed = true)
    val world = mockk<World>()
    private val border = mockk<WorldBorder>()
    val renderer = mockk<BuilderDisplayRenderer>(relaxed = true)
    val scheduler = TestTaskScheduler()
    val tasks = LifecycleTaskScope(scheduler)
    private val worldId = UUID.randomUUID()
    val eye = Location(world, .5, 66.62, .5).setDirection(Vector(2.0, -2.12, 0.0))
    private val blocks = mutableMapOf<BuilderBlockPos, Block>()
    private val materials = mutableMapOf<BuilderBlockPos, Material>()
    var allowed = true
    var buildAllowed = true
    var safeSupport: (Block) -> Boolean = { true }
    var channel: Any = Any()
    var calls = 0
    var onNative: (BuilderBlockPos, BlockFace) -> Unit = { _, _ -> }
    var packetAdmit: ((UUID, BuilderPlacementAssistHit?) -> BuilderPlacementAssistOffer?)? = null
    private lateinit var packetReceive: (BuilderPlacementAssistInput) -> Unit
    val helper: BuilderPlacementAssist

    init {
        mockkStatic(Bukkit::class)
        every { Bukkit.getPlayer(backing.uniqueId) } returns player
        every { player.uniqueId } returns backing.uniqueId
        every { player.world } returns world
        every { player.isOnline } returns true
        every { player.isDead } returns false
        every { player.isSneaking } returns false
        every { player.gameMode } returns GameMode.SURVIVAL
        every { player.inventory } returns backing.inventory
        every { player.openInventory } returns backing.openInventory
        every { player.persistentDataContainer } returns backing.persistentDataContainer
        every { player.eyeLocation } answers { eye.clone() }
        every { player.getAttribute(any()) } returns null
        every { player.boundingBox } returns BoundingBox(.2, 65.0, .2, .8, 66.8, .8)
        every { world.uid } returns worldId
        every { world.minHeight } returns -64
        every { world.maxHeight } returns 320
        every { world.worldBorder } returns border
        every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns true
        every { border.isInside(any<Location>()) } returns true
        every { world.rayTraceBlocks(any<Location>(), any(), any(), any(), any()) } answers {
            trace(firstArg(), secondArg(), thirdArg())
        }
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers { block(position(firstArg(), secondArg(), thirdArg())) }
        backing.inventory.setItemInMainHand(ItemStack(Material.STONE, 32))
        val messages = mockk<LocalizedMiniMessage>(relaxed = true)
        every { messages.render(any(), any(), any()) } answers { Component.text(firstArg<String>()) }
        helper = BuilderPlacementAssist(plugin, renderer, messages, tasks,
            isContextAllowed = { allowed }, canBuild = { _, _, _ -> buildAllowed }, canUseSupport = { safeSupport(it) },
            sendMessage = { _, _ -> }, nativePlacement = { _, support, face, _ -> calls++; onNative(support, face) },
            openPackets = { admit, receive -> packetAdmit = admit; packetReceive = receive; AutoCloseable {} }, connection = { channel },
        )
    }

    fun position(x: Int, y: Int = 64, z: Int = 0) = BuilderBlockPos(worldId, x, y, z)
    fun material(position: BuilderBlockPos) = materials[position] ?: Material.AIR
    fun set(position: BuilderBlockPos, material: Material) { block(position); materials[position] = material }
    fun block(position: BuilderBlockPos): Block = blocks.getOrPut(position) {
        mockk<Block>().also { block ->
            every { block.x } returns position.x
            every { block.y } returns position.y
            every { block.z } returns position.z
            every { block.world } returns world
            every { block.location } returns Location(world, position.x.toDouble(), position.y.toDouble(), position.z.toDouble())
            every { block.type } answers { material(position) }
            every { block.blockData } returns mockk<BlockData>(relaxed = true)
            val shape = mockk<VoxelShape>()
            every { shape.boundingBoxes } answers {
                if (material(position).isAir) emptyList() else listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0))
            }
            every { block.collisionShape } returns shape
        }
    }
    fun place(position: BuilderBlockPos, against: BuilderBlockPos = position.relative(BlockFace.WEST)): BlockPlaceEvent {
        set(position, Material.STONE)
        val event = BlockPlaceEvent(block(position), mockk(), block(against), player.inventory.itemInMainHand.clone(), player, true, EquipmentSlot.HAND)
        helper.onPlace(event)
        return event
    }
    fun nativeSuccess(support: BuilderBlockPos, face: BlockFace) {
        place(support.relative(face), support)
        val remaining = player.inventory.itemInMainHand.amount - 1
        player.inventory.setItemInMainHand(if (remaining > 0) ItemStack(Material.STONE, remaining) else ItemStack(Material.AIR))
    }
    fun seed() {
        helper.command(player, listOf("line"))
        place(position(0)); place(position(1))
        scheduler.tick()
        helper.tick()
    }
    fun seedBackground() {
        seed()
        set(position(3, 63, 1), Material.STONE)
        aim(Vector(3.5, 63.9, 1.8))
        helper.tick()
    }
    fun aim(point: Vector) { eye.direction = point.clone().subtract(eye.toVector()) }
    fun offer(hit: BuilderPlacementAssistHit? = null) = packetAdmit?.invoke(player.uniqueId, hit)
    fun receive(input: BuilderPlacementAssistInput) = packetReceive(input)
    private fun vanillaTrace() = checkNotNull(trace(eye, eye.direction, 4.5))
    fun hit() = vanillaTrace().let { BuilderPlacementAssistHit(it.hitBlock!!.x, it.hitBlock!!.y, it.hitBlock!!.z, it.hitBlockFace!!) }
    fun input(offer: BuilderPlacementAssistOffer, click: Boolean = false): BuilderPlacementAssistInput {
        val clickData = if (click) vanillaTrace().let { trace ->
            val hit = hit()
            BuilderPlacementAssistClick(hit, (trace.hitPosition.x - hit.x).toFloat(),
                (trace.hitPosition.y - hit.y).toFloat(), (trace.hitPosition.z - hit.z).toFloat())
        } else null
        return BuilderPlacementAssistInput(player.uniqueId, channel, 7, eye.yaw, eye.pitch, offer, clickData)
    }
    private fun trace(eye: Location, direction: Vector, range: Double): RayTraceResult? {
        val unit = direction.clone().normalize()
        val first = blocks.keys.mapNotNull { position ->
            if (material(position).isAir) null else BuilderPlacementAssistGeometry.aimDistance(eye.toVector(), unit, position, range)?.let { position to it }
        }.minByOrNull { it.second } ?: return null
        val point = eye.toVector().add(unit.multiply(first.second))
        val face = when {
            abs(point.x - first.first.x) < 1e-5 -> BlockFace.WEST
            abs(point.x - first.first.x - 1.0) < 1e-5 -> BlockFace.EAST
            abs(point.y - first.first.y) < 1e-5 -> BlockFace.DOWN
            abs(point.y - first.first.y - 1.0) < 1e-5 -> BlockFace.UP
            abs(point.z - first.first.z) < 1e-5 -> BlockFace.NORTH
            else -> BlockFace.SOUTH
        }
        return RayTraceResult(point, block(first.first), face)
    }
    override fun close() {
        helper.close()
        tasks.close()
        unmockkStatic(Bukkit::class)
        paper.close()
    }
}
