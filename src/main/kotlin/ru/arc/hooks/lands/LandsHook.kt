package ru.arc.hooks.lands

import me.angeschossen.lands.api.LandsIntegration
import me.angeschossen.lands.api.flags.type.Flags
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/** Lands is authoritative only inside an actual Land; wilderness is buildable. */
class LandsHook(plugin: Plugin) {
    private val integration = LandsIntegration.of(plugin)

    fun canModify(player: Player, block: Block, placing: Material? = null): Boolean {
        val landWorld = integration.getWorld(block.world) ?: return true
        val location = block.location
        if (landWorld.getArea(location) == null) return true
        val landPlayer = integration.getLandPlayer(player.uniqueId) ?: return false
        val breakAllowed = block.type.isAir ||
            landWorld.hasRoleFlag(landPlayer, location, Flags.BLOCK_BREAK, block.type, false)
        val placeAllowed = placing == null || landWorld.hasRoleFlag(landPlayer, location, Flags.BLOCK_PLACE, placing, false)
        return breakAllowed && placeAllowed
    }
}
