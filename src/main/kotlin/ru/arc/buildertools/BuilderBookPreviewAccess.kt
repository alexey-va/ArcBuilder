package ru.arc.buildertools

import java.util.UUID

internal const val BUILDER_PREVIEW_ADMIN_PERMISSION = "arcbuild.admin.preview"

internal enum class BuilderBookPreviewAccessLevel {
    NONE,
    OWNER,
    INSPECT,
}

internal object BuilderBookPreviewAccess {
    fun level(ownerId: UUID, viewerId: UUID, canInspectOthers: Boolean): BuilderBookPreviewAccessLevel = when {
        ownerId == viewerId -> BuilderBookPreviewAccessLevel.OWNER
        canInspectOthers -> BuilderBookPreviewAccessLevel.INSPECT
        else -> BuilderBookPreviewAccessLevel.NONE
    }
}
