package ru.arc.buildertools

import com.sk89q.worldedit.IncompleteRegionException
import com.sk89q.worldedit.LocalSession
import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.command.tool.SelectionWand
import com.sk89q.worldedit.extension.platform.permission.ActorSelectorLimits
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.regions.selector.CuboidRegionSelector
import org.bukkit.entity.Player
import com.sk89q.worldedit.entity.Player as WorldEditPlayer

/** Optional, owning-thread adapter. WorldEdit remains the sole owner of its selection. */
internal class BuilderWorldEditSelection {
    private val worldEdit = WorldEdit.getInstance()

    private fun session(player: Player, actor: WorldEditPlayer, initialize: Boolean = false): LocalSession? {
        val item = player.inventory.itemInMainHand
        if (item.type.isAir) return null
        val type = BukkitAdapter.asItemType(item.type) ?: return null
        // Only a first click with the configured wand may enter WorldEdit's native
        // session-loading path. Per-tick previews never create or load sessions.
        val session = worldEdit.sessionManager.getIfPresent(actor) ?: if (initialize &&
            type.id == worldEdit.configuration.wandItem && actor.hasPermission("worldedit.selection.pos")
        ) worldEdit.sessionManager.get(actor) else return null
        if (!session.isToolControlEnabled) return null
        val tool = session.getTool(type) as? SelectionWand ?: return null
        return session.takeIf { tool.canUse(actor) }
    }

    private fun actor(player: Player): WorldEditPlayer =
        worldEdit.platformManager.createProxyActor<WorldEditPlayer>(BukkitAdapter.adapt(player))

    /** Null means no active wand; empty points mean a non-cuboid or another world's selection. */
    fun points(player: Player, initializeSession: Boolean = false): BuilderSelectionPoints? {
        if (player.inventory.itemInMainHand.type.isAir) return null
        val actor = actor(player)
        val session = session(player, actor, initializeSession) ?: return null
        val empty = BuilderSelectionPoints(null, null)
        // getRegionSelector(world) clears a selection from another world. Only clicks may do that.
        if (session.selectionWorld != actor.world) return empty
        val selector = session.getRegionSelector(actor.world) as? CuboidRegionSelector ?: return empty
        fun position(point: BlockVector3) = BuilderBlockPos(player.world.uid, point.x(), point.y(), point.z())
        if (selector.isDefined) {
            val region = selector.region
            return BuilderSelectionPoints(position(region.pos1), position(region.pos2))
        }
        val first = try { position(selector.primaryPosition) } catch (_: IncompleteRegionException) { null }
        return BuilderSelectionPoints(first, null)
    }

    fun select(player: Player, position: BuilderBlockPos, first: Boolean) {
        if (position.worldId != player.world.uid) return
        val actor = actor(player)
        val session = session(player, actor) ?: return
        val selector = session.getRegionSelector(actor.world)
        val point = BlockVector3.at(position.x, position.y, position.z)
        val limits = ActorSelectorLimits.forActor(actor)
        if (first) {
            if (selector.selectPrimary(point, limits)) selector.explainPrimarySelection(actor, session, point)
        } else {
            if (selector.selectSecondary(point, limits)) selector.explainSecondarySelection(actor, session, point)
        }
    }
}
