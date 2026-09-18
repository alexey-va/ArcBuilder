package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BuilderBookInteractionPolicyTest : FunSpec({
    test("air click never prepares or confirms a build book") {
        BuilderBookInteractionPolicy.decide(
            action = BuilderBookClick.AIR,
            exactBookPreviewOpen = false,
            preparedPlan = BuilderBookPreparedPlan.SAME_BOOK,
        ) shouldBe BuilderBookInteractionDecision.REQUIRE_PREVIEW
    }

    test("new block click disposes any prepared plan before opening one preview") {
        BuilderBookInteractionPolicy.decide(
            action = BuilderBookClick.BLOCK,
            exactBookPreviewOpen = false,
            preparedPlan = BuilderBookPreparedPlan.SAME_BOOK,
        ) shouldBe BuilderBookInteractionDecision.REPLACE_PLAN_WITH_PREVIEW
    }

    test("air click with another book cannot inherit a stale prepared plan") {
        BuilderBookInteractionPolicy.decide(
            action = BuilderBookClick.AIR,
            exactBookPreviewOpen = false,
            preparedPlan = BuilderBookPreparedPlan.OTHER_BOOK,
        ) shouldBe BuilderBookInteractionDecision.REQUIRE_PREVIEW
    }

    test("ordinary positioning flow requires the hologram path before preparing") {
        BuilderBookInteractionPolicy.decide(
            action = BuilderBookClick.AIR,
            exactBookPreviewOpen = true,
            preparedPlan = BuilderBookPreparedPlan.NONE,
        ) shouldBe BuilderBookInteractionDecision.REQUIRE_PREVIEW
        BuilderBookInteractionPolicy.decide(
            action = BuilderBookClick.AIR,
            exactBookPreviewOpen = false,
            preparedPlan = BuilderBookPreparedPlan.NONE,
        ) shouldBe BuilderBookInteractionDecision.REQUIRE_PREVIEW
    }
})
