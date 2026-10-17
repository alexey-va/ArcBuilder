package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import ru.arc.autobuild.ConstructionSite
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.runtime.PaperNativeCleanupServiceProvider
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.concurrent.CompletableFuture

class BuilderVisualShutdownTest : FunSpec({
    test("renderer detaches immediately and waits for sliced native cleanup and actual packet drain") {
        MockBukkitTestRuntime.open().use { paper ->
            val host = paper.createSimplePlugin("CleanupHost")
            PaperNativeCleanupServiceProvider(host).use {
                val plugin = paper.createSimplePlugin("BuilderVisual")
                val physicalPackets = CompletableFuture<Void>()
                var packetCloses = 0
                val packets = object : BuilderPreviewPacketTransport {
                    override fun blockStateId(blockData: BlockData) = 1
                    override fun nextEntityId() = 1
                    override fun connection(player: Player): BuilderPreviewConnection? = null
                    override fun closeAsync(): CompletableFuture<Void> { packetCloses++; return physicalPackets.copy() }
                }
                LifecycleTaskScope(TestTaskScheduler()).use { tasks ->
                    val renderer = BuilderBlockDisplayRenderer(plugin, 32, 1f, false, 64.0, 20L,
                        mockk(relaxed = true), tasks, packets = packets)
                    var removed = 0
                    val player = mockk<Player>(relaxed = true)
                    every { player.hideBossBar(any()) } answers { check(Bukkit.isPrimaryThread()); removed++; Unit }
                    val site = mockk<ConstructionSite>()
                    every { site.player } returns player
                    val ids = List(300) { UUID.randomUUID() }
                    val bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS)
                    fun put(field: String, value: Any) {
                        BuilderBlockDisplayRenderer::class.java.getDeclaredField(field).apply { isAccessible = true }.set(renderer, value)
                    }
                    put("bookBossBars", ids.associateWith { bar }.toMutableMap())
                    put("bookSites", ids.associateWith { site }.toMutableMap())
                    val observer = renderer.closeAsync()
                    observer.cancel(false)
                    val actual = renderer.closeAsync()
                    removed shouldBe 0
                    actual.isDone shouldBe false
                    packetCloses shouldBe 1
                    paper.server.scheduler.performOneTick()
                    (removed in 0..128) shouldBe true
                    actual.isDone shouldBe false
                    repeat(300) { if (removed < 300) paper.server.scheduler.performOneTick() }
                    removed shouldBe 300
                    actual.isDone shouldBe false
                    physicalPackets.complete(null)
                    repeat(4) { paper.server.scheduler.performOneTick() }
                    actual.join()
                    renderer.closeAsync().isCompletedExceptionally shouldBe false
                    packetCloses shouldBe 1
                }
            }
        }
    }

    test("closing the cleanup host fails the actual renderer completion") {
        MockBukkitTestRuntime.open().use { paper ->
            val host = paper.createSimplePlugin("CleanupHostFailure")
            val cleanup = PaperNativeCleanupServiceProvider(host)
            val plugin = paper.createSimplePlugin("BuilderVisualFailure")
            val packets = object : BuilderPreviewPacketTransport {
                override fun blockStateId(blockData: BlockData) = 1
                override fun nextEntityId() = 1
                override fun connection(player: Player): BuilderPreviewConnection? = null
            }
            LifecycleTaskScope(TestTaskScheduler()).use { tasks ->
                val renderer = BuilderBlockDisplayRenderer(plugin, 32, 1f, false, 64.0, 20L,
                    mockk(relaxed = true), tasks, packets = packets)
                val actual = renderer.closeAsync()
                cleanup.close()
                actual.isCompletedExceptionally shouldBe true
            }
        }
    }
})
