package ru.arc.autobuild

import io.mockk.every
import io.mockk.mockk
import ru.arc.paper.api.*
import java.util.concurrent.CompletableFuture

/** In-memory consumer fixture; native decoding and transforms are tested by arc-core. */
internal fun schematicTestBuilding(id: String, states: List<String> = listOf("minecraft:stone")): Building {
    val cells = states.mapIndexed { y, state ->
        ArcSchematicBlock(ArcSchematicPoint(0, y, 0), state, state == "minecraft:air", state == "minecraft:barrier")
    }
    val snapshot = schematicTestSnapshot(cells)
    val library = mockk<ArcSchematicLibrary>()
    every { library.transform(snapshot, any()) } returns CompletableFuture.completedFuture(snapshot)
    return Building(id, snapshot, library)
}

internal fun schematicTestSnapshot(cells: List<ArcSchematicBlock>): ArcSchematicSnapshot = object : ArcSchematicSnapshot {
    override val minimum = ArcSchematicPoint(cells.minOf { it.position.x }, cells.minOf { it.position.y }, cells.minOf { it.position.z })
    override val maximum = ArcSchematicPoint(cells.maxOf { it.position.x }, cells.maxOf { it.position.y }, cells.maxOf { it.position.z })
    override val origin = ArcSchematicPoint(0, 0, 0)
    override val sizeX = maximum.x - minimum.x + 1
    override val sizeY = maximum.y - minimum.y + 1
    override val sizeZ = maximum.z - minimum.z + 1
    override val volume = sizeX.toLong() * sizeY * sizeZ
    override val nonBarrierCount = cells.count { !it.isBarrier }
    override val fileSha256 = "a".repeat(64)
    override val contentSha256 = "b".repeat(64)
    override val hasBiomes = false
    override val entityCount = 0
    override fun blocks(): List<ArcSchematicBlock> = cells
    override fun blockAt(x: Int, y: Int, z: Int): ArcSchematicBlock = cells.single { it.position == ArcSchematicPoint(x, y, z) }
}
