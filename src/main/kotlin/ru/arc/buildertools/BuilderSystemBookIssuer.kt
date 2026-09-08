package ru.arc.buildertools

import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import ru.arc.autobuild.BuildBookData
import ru.arc.autobuild.BuildBookItems
import ru.arc.autobuild.Building
import ru.arc.autobuild.SystemBuildBookDefinition

/** Console-only issuance of reviewed catalogue books through the native item codec. */
internal object BuilderSystemBookIssuer {
    fun issue(
        sender: CommandSender,
        args: List<String>,
        resolve: (BuildBookData) -> SystemBuildBookDefinition?,
        maxScanVolume: Long,
    ): Boolean {
        if (sender !is ConsoleCommandSender) {
            sender.sendMessage("This operation is available only from the server console.")
            return true
        }
        if (args.size != 2) {
            sender.sendMessage("Usage: builder systembook <online-player> <catalogue-file.schem>")
            return true
        }
        try {
            val player = requireNotNull(Bukkit.getPlayerExact(args[0])) { "Player must be online on this server" }
            val definition = requireNotNull(resolve(BuildBookData(args[1], "System book").validated())) {
                "Catalogue entry is disabled, missing, or its schematic digest changed"
            }
            val building = Building(definition.buildingId)
            require(building.volume <= maxScanVolume) { "Schematic exceeds the configured scan volume" }
            val slot = player.inventory.firstEmpty()
            require(slot >= 0) { "Player inventory has no empty slot" }
            val item = BuildBookItems.create(data(definition))
            player.inventory.setItem(slot, item)
            sender.sendMessage("SYSTEM_BOOK_ISSUED player=${player.name} schematic=${definition.buildingId} slot=$slot sha256=${definition.schematicSha256}")
        } catch (failure: IllegalArgumentException) {
            sender.sendMessage("SYSTEM_BOOK_REFUSED ${failure.message}")
        }
        return true
    }

    internal fun data(definition: SystemBuildBookDefinition) = BuildBookData(
        buildingId = definition.buildingId,
        title = definition.title,
        systemMaterialsIncluded = definition.materialsIncluded,
    )
}
