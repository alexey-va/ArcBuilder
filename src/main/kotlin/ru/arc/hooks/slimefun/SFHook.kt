package ru.arc.hooks.slimefun

import com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils
import org.bukkit.block.Block

class SFHook {
    fun isSlimefunBlock(block: Block): Boolean =
        StorageCacheUtils.hasSlimefunBlock(block.location)
}
