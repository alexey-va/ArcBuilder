package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.bukkit.Color
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.TextDisplay
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.inventory.ItemStack
import ru.arc.paper.display.PacketDisplay
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.config.ConfigManager
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.loadPlugin
import ru.ruscrafting.builder.paper.ArcBuilderPlugin
import java.util.IdentityHashMap
import java.util.UUID
import java.util.function.Consumer

class BuilderConstructionSiteDisplayManagerMockBukkitTest : FunSpec({
    test("packet site visuals never spawn native displays or load chunks and restore only the loaded hitbox") {
        ConfigManager.clear()
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<ArcBuilderPlugin>()
            BuilderToolsModule.shutdown()
            try {
                val worldId = UUID.randomUUID()
                val original = pausedProject(worldId)
                val steps = CountingSiteSteps(original.steps)
                val project = original.copy(steps = steps).validated()
                val config = BuilderToolsConfig(
                    ConfigManager.ofModule(plugin.dataPath, "builder-tools.yml"),
                ).validated()
                val spawned = mutableListOf<Entity>()
                val valid = IdentityHashMap<Entity, Boolean>()
                val world = displayWorld(worldId, spawned, valid)
                val packetVisuals = mutableListOf<PacketDisplay>()
                val displays = mockk<PaperPacketDisplays>(relaxed = true)
                every { displays.spawnBlock(any(), any()) } answers {
                    mockk<PacketBlockDisplay>(relaxed = true) { every { isValid } returns true }
                        .also { packetVisuals += it }
                }
                every { displays.spawnText(any(), any()) } answers {
                    mockk<PacketTextDisplay>(relaxed = true) { every { isValid } returns true }
                        .also { packetVisuals += it }
                }
                val chunk = mockk<Chunk> {
                    every { entities } answers { spawned.filter { valid[it] == true }.toTypedArray() }
                    every { this@mockk.world } returns world
                    every { x } returns 0
                    every { z } returns 0
                }

                mockkStatic(Bukkit::class)
                try {
                    every { Bukkit.getWorld(worldId) } returns world
                    BuilderConstructionSiteDisplayManager(
                        plugin = plugin,
                        settings = siteDisplaySettings(),
                        messages = config.messages(),
                        projectLookup = { id -> project.takeIf { it.projectId == id } },
                        onInspect = { _, _ -> },
                        displays = displays,
                    ).use { manager ->
                        manager.upsert(project)
                        spawned.size shouldBe 0
                        packetVisuals.size shouldBe 14
                        packetVisuals.filterIsInstance<PacketTextDisplay>().forEach { display ->
                            io.mockk.verify(exactly = 1) { display.displayWidth = 3f }
                            io.mockk.verify(exactly = 1) { display.displayHeight = 1.5f }
                        }
                        steps.reads = 0
                        manager.upsert(project.copy(updatedAtMillis = project.updatedAtMillis + 1))
                        packetVisuals.size shouldBe 14
                        steps.reads shouldBe 0

                        every { world.isChunkLoaded(0, 0) } returns true
                        paper.callEvent(ChunkLoadEvent(chunk, false))
                        spawned.size shouldBe 1
                        spawned.single().also { interaction ->
                            (interaction is Interaction) shouldBe true
                            io.mockk.verify(exactly = 1) { (interaction as Interaction).interactionWidth = 3f }
                        }
                        valid.replaceAll { _, _ -> false }
                        paper.callEvent(ChunkLoadEvent(chunk, false))
                        spawned.size shouldBe 2
                        packetVisuals.size shouldBe 14
                        io.mockk.verify(exactly = 0) { world.getChunkAt(any<Int>(), any<Int>()) }

                    }
                } finally {
                    unmockkStatic(Bukkit::class)
                }
            } finally {
                ConfigManager.clear()
            }
        }
    }
})

private fun siteDisplaySettings() = BuilderConstructionSiteDisplaySettings(
    enabled = true,
    outlineEnabled = true,
    outlineMaterial = Material.ORANGE_STAINED_GLASS,
    outlineThickness = 0.035f,
    glowColor = Color.fromRGB(0xFFB142),
    panelEnabled = true,
    panelFace = BuilderConstructionSitePanelFace.MAX_Z,
    panelHeightOffset = 2.25,
    panelFrontOffset = 0.4,
    panelInteractionWidth = 3f,
    panelInteractionHeight = 1.5f,
    panelLineWidth = 180,
    panelBackgroundColor = Color.fromARGB(0xB21C2328.toInt()),
    viewRange = 64.0,
    defaultLocale = "ru",
)

private fun displayWorld(
    worldId: UUID,
    spawned: MutableList<Entity>,
    valid: IdentityHashMap<Entity, Boolean>,
): World {
    val world = mockk<World>(relaxed = true)
    every { world.uid } returns worldId

    fun <T : Entity> tracked(entity: T): T {
        valid[entity] = true
        every { entity.isValid } answers { valid[entity] == true }
        every { entity.remove() } answers { valid[entity] = false }
        spawned += entity
        return entity
    }

    every {
        world.spawn(any(), BlockDisplay::class.java, any<Consumer<BlockDisplay>>())
    } answers {
        tracked(mockk<BlockDisplay>(relaxed = true)).also { thirdArg<Consumer<BlockDisplay>>().accept(it) }
    }
    every {
        world.spawn(any(), TextDisplay::class.java, any<Consumer<TextDisplay>>())
    } answers {
        tracked(mockk<TextDisplay>(relaxed = true)).also { thirdArg<Consumer<TextDisplay>>().accept(it) }
    }
    every {
        world.spawn(any(), Interaction::class.java, any<Consumer<Interaction>>())
    } answers {
        tracked(mockk<Interaction>(relaxed = true)).also { thirdArg<Consumer<Interaction>>().accept(it) }
    }
    return world
}

private fun pausedProject(worldId: UUID): BuilderConstructionProjectRecord {
    val now = 1_800_000_000_000L
    val projectId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    val playerId = UUID.fromString("11111111-2222-3333-4444-555555555555")
    val book = BuilderItemAmount(
        BuilderItemCodec.encodePrototype(ItemStack(Material.BOOK)),
        Material.BOOK.key.toString(),
        1,
    )
    val change = BuilderBlockChange(
        BuilderBlockPos(worldId, 4, 64, 4),
        "minecraft:air",
        "minecraft:stone",
    )
    val plan = BuilderPlan(
        id = projectId,
        playerId = playerId,
        kind = BuilderPlanKind.BUILD_BOOK,
        changes = listOf(change),
        costs = listOf(book),
        rewards = emptyList(),
        createdAtMillis = now,
        expiresAtMillis = now + 60_000,
    )
    return BuilderConstructionProjectRecord(
        projectId = projectId,
        playerId = playerId,
        playerName = "Builder",
        projectTitle = "Переживший рестарт дом",
        plan = plan,
        steps = listOf(BuilderConstructionStep(change, requiredMaterial = null, output = null)),
        bookCost = book,
        state = BuilderConstructionProjectState.PAUSED,
        cursor = 0,
        createdAtMillis = now,
        updatedAtMillis = now + 1,
    ).validated()
}

private class CountingSiteSteps(private val values: List<BuilderConstructionStep>) : AbstractList<BuilderConstructionStep>() {
    var reads = 0
    override val size: Int get() = values.size
    override fun get(index: Int): BuilderConstructionStep { reads++; return values[index] }
}
