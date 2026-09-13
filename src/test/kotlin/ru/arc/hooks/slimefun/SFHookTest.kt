package ru.arc.hooks.slimefun

import com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.block.Block

class SFHookTest : FunSpec({
    test("detects blocks from Slimefun storage instead of tile entity metadata") {
        val location = mockk<Location>()
        val block = mockk<Block> {
            every { this@mockk.location } returns location
        }
        mockkStatic(StorageCacheUtils::class)
        try {
            every { StorageCacheUtils.hasSlimefunBlock(location) } returns true

            SFHook().isSlimefunBlock(block) shouldBe true

            verify(exactly = 1) { StorageCacheUtils.hasSlimefunBlock(location) }
        } finally {
            unmockkStatic(StorageCacheUtils::class)
        }
    }
})
