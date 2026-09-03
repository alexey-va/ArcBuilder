package ru.arc.buildertools

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class BuilderBookHoldHintTrackerTest : StringSpec({
    "draft hold hint appears once until the draft or preview state changes" {
        val tracker = BuilderBookHoldHintTracker()
        val player = UUID.randomUUID()

        tracker.shouldShow(player, draftIdentity = "draft-a", previewOpen = false) shouldBe true
        tracker.shouldShow(player, draftIdentity = "draft-a", previewOpen = false) shouldBe false
        tracker.shouldShow(player, draftIdentity = "draft-a", previewOpen = true) shouldBe false
        tracker.shouldShow(player, draftIdentity = "draft-a", previewOpen = false) shouldBe true
        tracker.shouldShow(player, draftIdentity = null, previewOpen = false) shouldBe false
        tracker.shouldShow(player, draftIdentity = "draft-b", previewOpen = false) shouldBe true
    }
})
