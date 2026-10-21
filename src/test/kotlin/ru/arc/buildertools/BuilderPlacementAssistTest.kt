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
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.WorldBorder
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
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

class BuilderPlacementAssistTest : FunSpec({
    test("two committed native blocks expose one offer and ticks never place") {
        AssistFixture().use { f ->
            f.helper.command(f.player, listOf("on"))
            f.place(0)
            f.helper.tick()
            f.packetAdmit shouldBe null
            f.scheduler.tick()
            f.place(1)
            f.scheduler.tick()
            (f.packetAdmit != null) shouldBe true
            repeat(25) { f.helper.tick() }
            f.calls shouldBe 0
            f.offer()?.line?.next shouldBe f.position(2)
            f.offer() shouldBe null
            verify { f.renderer.placementAssist(f.player, f.position(2), false) }
        }
    }

    test("late cancellation cannot establish a line or admit input") {
        AssistFixture().use { f ->
            f.helper.command(f.player, listOf("on"))
            f.place(0)
            val second = f.place(1)
            second.isCancelled = true
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
                val offer = checkNotNull(f.offer())
                val input = f.input(offer)
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

    test("no aim no wall bypass no unloaded chunk trace and no permission offer") {
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

    test("native refusal never advances direction and native success advances only after return") {
        AssistFixture().use { f ->
            f.seed()
            val first = checkNotNull(f.offer())
            f.helper.accept(f.input(first))
            f.offer() shouldBe null
            verify { f.renderer.placementAssist(f.player, f.position(2), true) }
            verify { f.player.sendActionBar(Component.text("assist.refused")) }
            repeat(20) { f.helper.tick() }
            f.offer()?.line?.last shouldBe f.position(1)
            f.onNative = {
                f.place(2)
            }
            f.helper.tick()
            f.helper.accept(f.input(checkNotNull(f.offer())))
            f.eye.direction = f.position(3).let { Vector(it.x + .5, it.y + .5, it.z + .5).subtract(f.eye.toVector()) }
            f.helper.tick()
            f.offer()?.line?.last shouldBe f.position(2)
        }
    }

    test("ordinary slab collision shape and customized items keep vanilla placement") {
        AssistFixture().use { f ->
            f.helper.command(f.player, listOf("on"))
            val block = f.block(0)
            every { block.collisionShape.boundingBoxes } returns listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, .5, 1.0))
            f.place(0)
            f.place(1)
            f.scheduler.tick()
            f.helper.tick()
            f.packetAdmit shouldBe null
        }
        AssistFixture().use { f ->
            val custom = ItemStack(Material.STONE, 32).apply { editMeta { it.displayName(Component.text("Custom stone")) } }
            f.player.inventory.setItemInMainHand(custom)
            f.helper.command(f.player, listOf("on"))
            f.place(0); f.place(1)
            f.scheduler.tick()
            f.helper.tick()
            f.packetAdmit shouldBe null
        }
    }

    test("consuming the last held item completes native placement without a false refusal") {
        AssistFixture().use { f ->
            f.seed()
            f.player.inventory.setItemInMainHand(ItemStack(Material.STONE, 1))
            f.onNative = {
                f.place(2)
                f.player.inventory.setItemInMainHand(ItemStack(Material.AIR))
            }
            f.helper.accept(f.input(checkNotNull(f.offer())))
            f.calls shouldBe 1
            f.helper.tick()
            f.offer() shouldBe null
            verify(exactly = 0) { f.player.sendActionBar(Component.text("assist.refused")) }
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
    private val tasks = LifecycleTaskScope(scheduler)
    private val worldId = UUID.randomUUID()
    val eye = Location(world, .5, 66.62, .5).setDirection(Vector(2.0, -2.12, 0.0))
    private val blocks = mutableMapOf<Int, Block>()
    var allowed = true
    var buildAllowed = true
    var channel: Any = Any()
    var calls = 0
    var onNative: () -> Unit = {}
    var packetAdmit: ((UUID) -> BuilderPlacementAssistOffer?)? = null
    val helper: BuilderPlacementAssist

    init {
        mockkStatic(Bukkit::class)
        every { Bukkit.getPlayer(backing.uniqueId) } returns player
        every { player.uniqueId } returns backing.uniqueId
        every { player.world } returns world
        every { player.isOnline } returns true
        every { player.isDead } returns false
        every { player.isSneaking } returns false
        every { player.inventory } returns backing.inventory
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
        every { world.rayTraceBlocks(any<Location>(), any(), any(), any(), any()) } returns null
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers { block(firstArg()) }
        backing.inventory.setItemInMainHand(ItemStack(Material.STONE, 32))
        val messages = mockk<LocalizedMiniMessage>(relaxed = true)
        every { messages.render(any(), any(), any()) } answers { Component.text(firstArg<String>()) }
        helper = BuilderPlacementAssist(plugin, renderer, messages, tasks,
            isContextAllowed = { allowed }, canBuild = { _, _, _ -> buildAllowed }, sendMessage = { _, _ -> },
            nativePlacement = { _, _, _, _ -> calls++; onNative() },
            openPackets = { admit, _ -> packetAdmit = admit; AutoCloseable {} }, connection = { channel },
        )
    }

    fun position(x: Int) = BuilderBlockPos(worldId, x, 64, 0)
    fun block(x: Int): Block = blocks.getOrPut(x) {
        mockk<Block>().also { block ->
            every { block.x } returns x
            every { block.y } returns 64
            every { block.z } returns 0
            every { block.world } returns world
            every { block.type } returns Material.AIR
            val shape = mockk<VoxelShape>()
            every { shape.boundingBoxes } returns listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0))
            every { block.collisionShape } returns shape
        }
    }
    fun place(x: Int): BlockPlaceEvent {
        val block = block(x)
        every { block.type } returns Material.STONE
        val event = BlockPlaceEvent(block, mockk(), block(x - 1), player.inventory.itemInMainHand.clone(), player, true, EquipmentSlot.HAND)
        helper.onPlace(event)
        return event
    }
    fun seed() {
        helper.command(player, listOf("on"))
        place(0); place(1)
        scheduler.tick()
        helper.tick()
    }
    fun offer() = packetAdmit?.invoke(player.uniqueId)
    fun input(offer: BuilderPlacementAssistOffer) = BuilderPlacementAssistInput(player.uniqueId, channel, 7, eye.yaw, eye.pitch, offer)
    override fun close() {
        helper.close()
        tasks.close()
        unmockkStatic(Bukkit::class)
        paper.close()
    }
}
