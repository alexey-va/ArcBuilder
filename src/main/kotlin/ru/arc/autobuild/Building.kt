package ru.arc.autobuild

import ru.arc.paper.api.ArcSchematicLibrary
import ru.arc.paper.api.ArcSchematicSnapshot
import ru.arc.paper.api.ArcSchematicTransform
import java.util.concurrent.CompletableFuture

/** One request's immutable value. Loading, transforms and cache lifetime belong to Arc Core. */
class Building(
    val fileName: String,
    val snapshot: ArcSchematicSnapshot,
    private val library: ArcSchematicLibrary,
) {
    val volume: Long get() = snapshot.volume
    val blockCount: Int get() = snapshot.nonBarrierCount
    val schematicSha256: String get() = snapshot.fileSha256
    val contentSha256: String get() = snapshot.contentSha256

    fun transform(rotation: Int, mirrored: Boolean): CompletableFuture<ArcSchematicSnapshot> =
        library.transform(snapshot, ArcSchematicTransform(BuildBookTransform.normalizeRotation(rotation) / 90, mirrored))
}
