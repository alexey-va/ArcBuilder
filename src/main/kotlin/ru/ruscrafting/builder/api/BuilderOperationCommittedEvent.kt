package ru.ruscrafting.builder.api

import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.util.Collections
import java.util.UUID

data class BuilderPlacedBlock(
    val x: Int,
    val y: Int,
    val z: Int,
    val material: String,
)

class BuilderOperationCommittedEvent(
    val operationId: String,
    val playerId: UUID,
    val worldId: UUID,
    placements: List<BuilderPlacedBlock>,
) : Event(false) {
    val placements: List<BuilderPlacedBlock> = Collections.unmodifiableList(placements.toList())

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        @JvmStatic
        val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}
