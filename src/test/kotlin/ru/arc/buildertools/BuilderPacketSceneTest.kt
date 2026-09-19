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

    test("unchanged previews send nothing and moving windows only send their delta") {
        val connection = RecordingPreviewConnection()
        val audience = mapOf(owner to BuilderPacketViewer(connection, setOf(0L)))
        val first = display(10)
        val retained = display(11)
        val entering = display(12)
        BuilderPacketScene().use { scene ->
            scene.update(listOf(first, retained), audience)
            scene.update(listOf(first, retained), audience)
            scene.update(listOf(retained, entering), audience)
            connection.batches shouldBe listOf(
                PreviewBatch(emptyList(), listOf(10, 11)),
                PreviewBatch(listOf(10), listOf(12)),
            )
        }
        connection.batches.last() shouldBe PreviewBatch(listOf(11, 12), emptyList())
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
            connection.batches.size shouldBe 1
            admin.batches shouldBe listOf(
                PreviewBatch(emptyList(), listOf(20, 21)),
                PreviewBatch(listOf(20, 21), emptyList()),
            )
        }
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
            connection.batches shouldBe listOf(
                PreviewBatch(emptyList(), listOf(30)),
                PreviewBatch(emptyList(), listOf(31)),
                PreviewBatch(listOf(30), emptyList()),
                PreviewBatch(emptyList(), listOf(30)),
            )
        }
    }

    test("same player on a new connection receives the complete retained scene") {
        val old = RecordingPreviewConnection()
        val fresh = RecordingPreviewConnection()
        val displays = listOf(display(40))
        BuilderPacketScene().use { scene ->
            scene.update(displays, mapOf(owner to BuilderPacketViewer(old, setOf(0L))))
            scene.update(displays, mapOf(owner to BuilderPacketViewer(fresh, setOf(0L))))
            old.batches.last() shouldBe PreviewBatch(listOf(40), emptyList())
            fresh.batches.single() shouldBe PreviewBatch(emptyList(), listOf(40))
        }
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
        connection.batches shouldBe listOf(
            PreviewBatch(emptyList(), listOf(50)), PreviewBatch(listOf(50), emptyList()),
            PreviewBatch(emptyList(), listOf(50)), PreviewBatch(listOf(50), emptyList()),
        )
    }

    test("negative coordinates use Minecraft signed chunk coordinates") {
        display(60, x = -.001).chunkKey() shouldBe 0xffffffffL
        display(61, x = -16.001).chunkKey() shouldBe 0xfffffffeL
    }
})

private data class PreviewBatch(val removed: List<Int>, val added: List<Int>)

private class RecordingPreviewConnection : BuilderPreviewConnection {
    override val identity = Any()
    val batches = mutableListOf<PreviewBatch>()
    override fun send(removed: List<Int>, added: List<BuilderPacketDisplay>) {
        batches += PreviewBatch(removed.toList(), added.map(BuilderPacketDisplay::entityId))
    }
}
