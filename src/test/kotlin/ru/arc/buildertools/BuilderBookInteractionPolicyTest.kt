package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BuilderBookInteractionPolicyTest : FunSpec({
    test("repeated air click on the same prepared plan only repeats confirmation guidance") {
        BuilderBookInteractionPolicy.decide(
            action = BuilderBookClick.AIR,
            exactBookPreviewOpen = false,
            preparedPlan = BuilderBookPreparedPlan.SAME_BOOK,
        ) shouldBe BuilderBookInteractionDecision.RESHOW_PREPARED_PLAN
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
        ) shouldBe BuilderBookInteractionDecision.DISCARD_PLAN_AND_REQUIRE_PREVIEW
    }

    test("ordinary positioning flow requires one exact preview before preparing") {
        BuilderBookInteractionPolicy.decide(
            action = BuilderBookClick.AIR,
            exactBookPreviewOpen = true,
            preparedPlan = BuilderBookPreparedPlan.NONE,
        ) shouldBe BuilderBookInteractionDecision.PREPARE_PLAN
        BuilderBookInteractionPolicy.decide(
            action = BuilderBookClick.AIR,
            exactBookPreviewOpen = false,
            preparedPlan = BuilderBookPreparedPlan.NONE,
        ) shouldBe BuilderBookInteractionDecision.REQUIRE_PREVIEW
    }
})
