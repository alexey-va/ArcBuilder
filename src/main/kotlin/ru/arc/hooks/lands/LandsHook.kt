package ru.arc.hooks.lands

import me.angeschossen.lands.api.LandsIntegration
import me.angeschossen.lands.api.flags.type.Flags
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.UUID

internal object LandsContainerAccessPolicy {
    fun canUseContainer(claimed: Boolean, interactContainerAllowed: Boolean): Boolean =
        !claimed || interactContainerAllowed
}

/** Lands is authoritative only inside an actual Land; wilderness is buildable. */
class LandsHook(plugin: Plugin) {
    private val integration = LandsIntegration.of(plugin)

    fun canModify(player: Player, block: Block, placing: Material? = null): Boolean {
        return canModify(player.uniqueId, block, placing)
    }

    /** Uses Lands' persisted player identity so construction may continue while the owner is offline. */
    fun canModify(playerId: UUID, block: Block, placing: Material? = null): Boolean {
        val landWorld = integration.getWorld(block.world) ?: return true
        val location = block.location
        if (landWorld.getArea(location) == null) return true
        val landPlayer = integration.getLandPlayer(playerId) ?: return false
        val breakAllowed = block.type.isAir ||
            landWorld.hasRoleFlag(landPlayer, location, Flags.BLOCK_BREAK, block.type, false)
        val placeAllowed = placing == null || landWorld.hasRoleFlag(landPlayer, location, Flags.BLOCK_PLACE, placing, false)
        return breakAllowed && placeAllowed
    }

    /** Wilderness is open; claimed containers follow Lands' exact container-interaction role flag. */
    fun canOpenContainer(playerId: UUID, block: Block): Boolean {
        val landWorld = integration.getWorld(block.world) ?: return true
        val location = block.location
        val claimed = landWorld.getArea(location) != null
        val allowed = claimed && landWorld.hasRoleFlag(playerId, location, Flags.INTERACT_CONTAINER)
        return LandsContainerAccessPolicy.canUseContainer(claimed, allowed)
    }
}
