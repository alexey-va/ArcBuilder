package ru.arc.buildertools

/** Separates coincident faces without changing the placement anchor or actual blocks. */
internal object BuilderPreviewSurface {
    private const val FACE_SEPARATION = .002f

    fun transform(
        configured: BuilderDisplayBlockTransform,
        matchesWorld: Boolean,
        occupied: Boolean,
    ): BuilderDisplayBlockTransform? {
        if (matchesWorld) return null
        // Keep replacement faces outside an existing full cube; shrinking would hide them.
        val scale = if (occupied && configured.scale > 1f - FACE_SEPARATION) {
            1f + FACE_SEPARATION
        } else {
            minOf(configured.scale, 1f - FACE_SEPARATION)
        }
        return BuilderDisplayBlockTransform((1f - scale) / 2f, scale)
    }
}
