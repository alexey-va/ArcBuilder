package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class BuilderPacketSceneTest : FunSpec({
    val owner = UUID.randomUUID()
    val observer = UUID.randomUUID()

    fun display(id: Int, x: Double = 1.0) = BuilderPacketDisplay(
        entityId = id, uuid = UUID.randomUUID(), x = x, y = 64.0, z = 1.0,
        blockStateId = 1, scaleX = 1f, scaleY = 1f, scaleZ = 1f,
        translateX = 0f, translateY = 0f, translateZ = 0f, glowRgb = 0xffb142, viewRange = 1f,
    )

    test("unchanged previews send nothing and moving windows replace the queued target") {
        val connection = RecordingPreviewConnection()
        val audience = mapOf(owner to BuilderPacketViewer(connection, setOf(0L)))
        val first = display(10)
        val retained = display(11)
        val entering = display(12)
        BuilderPacketScene().use { scene ->
            scene.update(listOf(first, retained), audience)
            scene.update(listOf(first, retained), audience)
            scene.update(listOf(retained, entering), audience)
            connection.snapshots shouldBe listOf(listOf(10, 11), listOf(11, 12))
        }
        connection.snapshots.last() shouldBe emptyList()
        connection.forcedResets shouldBe listOf(emptySet(), setOf(10), setOf(11, 12))
    }

    test("new observers receive a complete scene and losing access removes it") {
        val connection = RecordingPreviewConnection()
        val admin = RecordingPreviewConnection()
        val displays = listOf(display(20), display(21))
        val own = owner to BuilderPacketViewer(connection, setOf(0L))
        BuilderPacketScene().use { scene ->
            scene.update(displays, mapOf(own))
            scene.update(displays, mapOf(own, observer to BuilderPacketViewer(admin, setOf(0L))))
            scene.update(displays, mapOf(own))
            connection.snapshots shouldBe listOf(listOf(20, 21))
            admin.snapshots shouldBe listOf(listOf(20, 21), emptyList())
        }
        connection.snapshots shouldBe listOf(listOf(20, 21), emptyList())
        admin.forcedResets shouldBe listOf(emptySet(), setOf(20, 21))
    }

    test("client chunk unload and reload replays retained displays without new entity ids") {
        val connection = RecordingPreviewConnection()
        val first = display(30)
        val nextChunk = display(31, x = 17.0)
        val all = mapOf(owner to BuilderPacketViewer(connection, setOf(0L, 1L)))
        BuilderPacketScene().use { scene ->
            scene.update(listOf(first, nextChunk), mapOf(owner to BuilderPacketViewer(connection, setOf(0L))))
            scene.update(listOf(first, nextChunk), all)
            scene.forgetChunk(owner, 0L)
            scene.update(listOf(first, nextChunk), all)
            connection.snapshots shouldBe listOf(listOf(30), listOf(30, 31), listOf(31), listOf(30, 31))
            connection.forcedResets shouldBe listOf(emptySet(), emptySet(), setOf(30), emptySet())
        }
        connection.snapshots.last() shouldBe emptyList()
        connection.forcedResets.last() shouldBe setOf(30, 31)
    }

    test("same player on a new connection receives the complete retained scene") {
        val old = RecordingPreviewConnection()
        val fresh = RecordingPreviewConnection()
        val displays = listOf(display(40))
        BuilderPacketScene().use { scene ->
            scene.update(displays, mapOf(owner to BuilderPacketViewer(old, setOf(0L))))
            scene.update(displays, mapOf(owner to BuilderPacketViewer(fresh, setOf(0L))))
            old.snapshots shouldBe listOf(listOf(40), emptyList())
            fresh.snapshots shouldBe listOf(listOf(40))
            old.forcedResets shouldBe listOf(emptySet(), setOf(40))
        }
        fresh.snapshots shouldBe listOf(listOf(40), emptyList())
        fresh.forcedResets shouldBe listOf(emptySet(), setOf(40))
    }

    test("respawn reset replays once and repeated cleanup has no residual ids") {
        val connection = RecordingPreviewConnection()
        val displays = listOf(display(50))
        val audience = mapOf(owner to BuilderPacketViewer(connection, setOf(0L)))
        val scene = BuilderPacketScene()
        scene.update(displays, audience)
        scene.removeViewer(owner)
        scene.removeViewer(owner)
        scene.update(displays, audience)
        scene.close()
        scene.close()
        connection.snapshots shouldBe listOf(listOf(50), emptyList(), listOf(50), emptyList())
        connection.forcedResets shouldBe listOf(emptySet(), setOf(50), emptySet(), setOf(50))
    }

    test("temporarily missing sent chunks force IDs out of the acknowledged baseline") {
        val connection = RecordingPreviewConnection()
        val block = display(55)
        val scene = BuilderPacketScene()
        scene.update(listOf(block), mapOf(owner to BuilderPacketViewer(connection, setOf(0L))))
        scene.update(listOf(block), mapOf(owner to BuilderPacketViewer(connection, emptySet())))
        scene.update(listOf(block), mapOf(owner to BuilderPacketViewer(connection, setOf(0L))))

        connection.snapshots shouldBe listOf(listOf(55), emptyList(), listOf(55))
        connection.forcedResets shouldBe listOf(emptySet(), setOf(55), emptySet())
    }

    test("negative coordinates use Minecraft signed chunk coordinates") {
        display(60, x = -.001).chunkKey() shouldBe 0xffffffffL
        display(61, x = -16.001).chunkKey() shouldBe 0xfffffffeL
    }
})

private class RecordingPreviewConnection : BuilderPreviewConnection {
    override val identity = Any()
    val snapshots = mutableListOf<List<Int>>()
    val forcedResets = mutableListOf<Set<Int>>()
    override fun submit(desired: List<BuilderPacketDisplay>, forceResetIds: Set<Int>) {
        snapshots += desired.map(BuilderPacketDisplay::entityId)
        forcedResets += forceResetIds.toSet()
    }
}
