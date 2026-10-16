package ru.arc.autobuild

import org.bukkit.Bukkit
import ru.arc.paper.api.ArcSchematicLibrary
import ru.arc.paper.api.ArcSchematicService
import ru.arc.paper.api.ArcSchematicContentPolicy
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/** Consumer handle only: it never reads a file or retains loaded schematics. */
internal class BuilderSchematics(private val root: Path) : AutoCloseable {
    private var library: ArcSchematicLibrary? = null
    private var closed = false

    @Synchronized
    fun library(): ArcSchematicLibrary {
        check(!closed) { "Builder schematic access is closed" }
        return library ?: checkNotNull(Bukkit.getServicesManager().load(ArcSchematicService::class.java)) {
            "Arc Core schematic service is unavailable"
        }.open("ArcBuilder", root).also { library = it }
    }

    fun load(id: String, expectedSha256: String?): CompletableFuture<Building> = try {
        val access = library()
        (if (expectedSha256 == null) access.load(id, ArcSchematicContentPolicy.IGNORE_ENTITIES_AND_BIOMES) else access.loadVerified(id, expectedSha256, ArcSchematicContentPolicy.IGNORE_ENTITIES_AND_BIOMES))
            .thenApply { Building(id, it, access) }
    } catch (failure: Throwable) {
        CompletableFuture.failedFuture(failure)
    }

    @Synchronized
    override fun close() {
        closed = true
        library?.close()
        library = null
    }
}
