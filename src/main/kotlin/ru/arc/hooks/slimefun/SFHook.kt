package ru.arc.hooks.slimefun

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun
import org.bukkit.block.Block

class SFHook {
    fun isSlimefunBlock(block: Block): Boolean =
        Slimefun.getBlockDataService().getBlockData(block).isPresent
}
