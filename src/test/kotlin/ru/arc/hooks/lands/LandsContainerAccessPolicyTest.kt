package ru.arc.hooks.lands

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class LandsContainerAccessPolicyTest : FunSpec({
    test("wilderness containers stay available without a Lands role") {
        LandsContainerAccessPolicy.canUseContainer(
            claimed = false,
            interactContainerAllowed = false,
        ) shouldBe true
    }

    test("containers inside a Land follow the exact container interaction flag") {
        LandsContainerAccessPolicy.canUseContainer(
            claimed = true,
            interactContainerAllowed = true,
        ) shouldBe true
        LandsContainerAccessPolicy.canUseContainer(
            claimed = true,
            interactContainerAllowed = false,
        ) shouldBe false
    }
})
