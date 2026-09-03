package ru.arc.buildertools

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class BuilderBookPreviewAccessTest : StringSpec({
    "owners control their preview while permitted admins can only inspect it" {
        val owner = UUID.randomUUID()
        val admin = UUID.randomUUID()
        val stranger = UUID.randomUUID()

        BuilderBookPreviewAccess.level(owner, owner, canInspectOthers = false) shouldBe
            BuilderBookPreviewAccessLevel.OWNER
        BuilderBookPreviewAccess.level(owner, admin, canInspectOthers = true) shouldBe
            BuilderBookPreviewAccessLevel.INSPECT
        BuilderBookPreviewAccess.level(owner, stranger, canInspectOthers = false) shouldBe
            BuilderBookPreviewAccessLevel.NONE
    }
})
