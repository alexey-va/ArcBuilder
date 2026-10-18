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
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
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
    test("startup migration removes loaded chunk orphans across shared-budget ticks") {
        MockBukkitTestRuntime.open().use { paper ->
            val tasks = QueuedBuilderPlanningTasks()
            val plugin = paper.createSimplePlugin("SiteMigration")
            var removed = 0
            val tag = mockk<PersistentDataContainer> {
                every { has(any(), PersistentDataType.STRING) } returns true
            }
            val entities = Array<Entity>(300) {
                mockk(relaxed = true) {
                    every { isValid } returns true
                    every { persistentDataContainer } returns tag
                    every { remove() } answers { tasks.assertMain(); removed++ }
                }
            }
            val world = mockk<World>(relaxed = true)
            val chunk = mockk<Chunk> {
                every { this@mockk.world } returns world
                every { x } returns 0
                every { z } returns 0
                every { this@mockk.entities } answers { tasks.assertMain(); entities }
            }
            every { world.isChunkLoaded(0, 0) } returns true
            every { world.loadedChunks } answers { tasks.assertMain(); arrayOf(chunk) }
            mockkStatic(Bukkit::class)
            try {
                every { Bukkit.getWorlds() } returns listOf(world)
                BuilderConstructionSiteDisplayManager(
                    plugin = plugin,
                    settings = siteDisplaySettings(),
                    messages = mockk(relaxed = true),
                    projectLookup = { null },
                    onInspect = { _, _ -> },
                    displays = mockk(relaxed = true),
                    space = unblockedPlaqueSpace(),
                    planningTasks = tasks,
                ).use {
                    removed shouldBe 0
                    tasks.tick()
                    removed shouldBe 126
                    tasks.captureSteps shouldBe 128
                    repeat(3) { tasks.tick() }
                    removed shouldBe 300
                    io.mockk.verify(exactly = 0) { world.entities }
                }
            } finally {
                unmockkStatic(Bukkit::class)
            }
        }
    }

    test("packet site visuals never spawn native displays or load chunks and restore the loaded hitbox strips") {
        ConfigManager.clear()
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<ArcBuilderPlugin>()
            BuilderToolsModule.shutdown()
            try {
                val worldId = UUID.randomUUID()
                val original = pausedProject(worldId)
                val steps = CountingSiteSteps(original.steps)
                var project = original.copy(steps = steps).validated()
                val tasks = QueuedBuilderPlanningTasks()
                val config = BuilderToolsConfig(
                    ConfigManager.ofModule(plugin.dataPath, "builder-tools.yml"),
                ).validated()
                val spawned = mutableListOf<Entity>()
                val valid = IdentityHashMap<Entity, Boolean>()
                val world = displayWorld(worldId, spawned, valid)
                val packetVisuals = mutableListOf<PacketDisplay>()
                val displays = mockk<PaperPacketDisplays>(relaxed = true)
                every { displays.spawnBlock(any(), any()) } answers {
                    tasks.assertMain()
                    mockk<PacketBlockDisplay>(relaxed = true) { every { isValid } returns true }
                        .also { packetVisuals += it }
                }
                every { displays.spawnText(any(), any()) } answers {
                    tasks.assertMain()
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
                    every { Bukkit.getWorld(worldId) } answers { tasks.assertMain(); world }
                    BuilderConstructionSiteDisplayManager(
                        plugin = plugin,
                        settings = siteDisplaySettings(),
                        messages = config.messages(),
                        projectLookup = { id -> project.takeIf { it.projectId == id } },
                        onInspect = { _, _ -> },
                        displays = displays,
                        space = unblockedPlaqueSpace(),
                        planningTasks = tasks,
                    ).use { manager ->
                        steps.reads = 0
                        manager.upsert(project)
                        steps.reads shouldBe 0
                        packetVisuals.size shouldBe 0
                        tasks.runComputations()
                        tasks.workerRuns shouldBe 1
                        tasks.tick()
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

                        every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns true
                        paper.callEvent(ChunkLoadEvent(chunk, false))
                        manager.queueMockedControlCapture(tasks)
                        tasks.tick()
                        spawned.size shouldBe 4
                        spawned.forEach { interaction ->
                            (interaction is Interaction) shouldBe true
                            (interaction as Interaction).interactionWidth shouldBe 2.35f
                            (interaction.interactionHeight > 1.5f) shouldBe true
                        }
                        valid.replaceAll { _, _ -> false }
                        paper.callEvent(ChunkLoadEvent(chunk, false))
                        manager.queueMockedControlCapture(tasks)
                        tasks.tick()
                        spawned.size shouldBe 8
                        packetVisuals.size shouldBe 14
                        steps.reads = 0
                        manager.upsert(project.copy(updatedAtMillis = project.updatedAtMillis + 2))
                        spawned.size shouldBe 8
                        steps.reads shouldBe 0
                        project = project.copy(siteAnchor = BuilderBlockPos(worldId, 5, 64, 5))
                        manager.upsert(project)
                        project = project.copy(siteAnchor = BuilderBlockPos(worldId, 6, 64, 6))
                        manager.upsert(project)
                        spawned.size shouldBe 8
                        tasks.runComputations()
                        tasks.tick()
                        manager.queueMockedControlCapture(tasks)
                        tasks.tick()
                        spawned.size shouldBe 12
                        packetVisuals.size shouldBe 14
                        spawned.takeLast(4).map { it.location.x }.average() shouldBe 6.5
                        spawned.subList(4, 8).forEach { old -> io.mockk.verify(exactly = 1) { old.remove() } }
                        io.mockk.verify(exactly = 0) { world.getChunkAt(any<Int>(), any<Int>()) }

                        project = project.copy(siteAnchor = BuilderBlockPos(worldId, 7, 64, 7))
                        manager.upsert(project)
                        manager.remove(project.projectId)
                        tasks.runComputations()
                        tasks.tick()
                        spawned.size shouldBe 12
                        packetVisuals.size shouldBe 14
                        project = project.copy(siteAnchor = BuilderBlockPos(worldId, 8, 64, 8))
                        manager.upsert(project)
                        manager.close()
                        tasks.runComputations()
                        tasks.tick()
                        spawned.size shouldBe 12
                        packetVisuals.size shouldBe 14
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

/** The relaxed display mock has no Core tick; execute its real participant on native test steps. */
private fun BuilderConstructionSiteDisplayManager.queueMockedControlCapture(tasks: QueuedBuilderPlanningTasks) {
    val controls = javaClass.getDeclaredField("controls").apply { isAccessible = true }.get(this)
    val participant = controls.javaClass.getDeclaredField("participant").apply { isAccessible = true }.get(controls)
    val capture = participant.javaClass.getMethod("captureSteps", List::class.java).apply { isAccessible = true }
    val steps = capture.invoke(participant, emptyList<Any>()) as Iterator<*>
    tasks.capture({ true }) {
        if (steps.hasNext()) { steps.next(); false } else true
    }
}

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
    every { world.minHeight } returns -64
    every { world.maxHeight } returns 320

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
        var location = firstArg<org.bukkit.Location>().clone()
        var width = 0f
        var height = 0f
        tracked(mockk<Interaction>(relaxed = true)).also { entity ->
            every { entity.uniqueId } returns UUID.randomUUID()
            every { entity.world } returns world
            every { entity.location } answers { location.clone() }
            every { entity.teleport(any<org.bukkit.Location>()) } answers { location = firstArg<org.bukkit.Location>().clone(); true }
            every { entity.interactionWidth } answers { width }
            every { entity.interactionWidth = any() } answers { width = firstArg() }
            every { entity.interactionHeight } answers { height }
            every { entity.interactionHeight = any() } answers { height = firstArg() }
            thirdArg<Consumer<Interaction>>().accept(entity)
        }
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
