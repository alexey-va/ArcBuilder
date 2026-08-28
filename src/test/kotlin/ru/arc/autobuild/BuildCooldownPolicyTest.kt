package ru.arc.autobuild

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BuildCooldownPolicyTest {
    @Test
    fun `zero disables cooldown for a simple build book`() {
        BuildCooldownPolicy.resolveSeconds("0", 3_600L) shouldBe 0L
        BuildCooldownPolicy.toTicks(0L) shouldBe 0L
    }

    @Test
    fun `valid per-book cooldown overrides the configured fallback`() {
        BuildCooldownPolicy.resolveSeconds("120", 3_600L) shouldBe 120L
        BuildCooldownPolicy.toTicks(120L) shouldBe 2_400L
    }

    @Test
    fun `missing or malformed book data keeps the configured limit`() {
        BuildCooldownPolicy.resolveSeconds(null, 1_800L) shouldBe 1_800L
        BuildCooldownPolicy.resolveSeconds("nope", 1_800L) shouldBe 1_800L
        BuildCooldownPolicy.resolveSeconds("-1", 1_800L) shouldBe 1_800L
        BuildCooldownPolicy.resolveSeconds("604801", 1_800L) shouldBe 1_800L
    }

    @Test
    fun `invalid configured fallback fails closed to the one hour default`() {
        BuildCooldownPolicy.resolveSeconds(null, -1L) shouldBe BuildCooldownPolicy.DEFAULT_SECONDS
        BuildCooldownPolicy.resolveSeconds(null, Long.MAX_VALUE) shouldBe BuildCooldownPolicy.DEFAULT_SECONDS
    }
}
