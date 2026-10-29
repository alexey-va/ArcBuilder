package ru.arc.buildertools

import org.bukkit.plugin.Plugin
import org.bukkit.plugin.ServicePriority
import ru.arc.paper.api.ArcDisplayPassengerService
import ru.arc.paper.api.ArcVisualPacketBudget
import ru.arc.paper.api.VisualPacketAdmission
import ru.arc.paper.display.PaperDisplayPassengerServiceProvider
import ru.arc.paper.runtime.PaperNativeCleanupServiceProvider

/** Supplies the shared ARC services required while MockBukkit constructs packet displays. */
internal fun installArcVisualPacketBudgetFixture(plugin: Plugin) {
    if (plugin.server.servicesManager.load(ru.arc.paper.api.ArcNativeCleanupService::class.java) == null) {
        PaperNativeCleanupServiceProvider(plugin)
    }
    if (plugin.server.servicesManager.load(ArcDisplayPassengerService::class.java) == null) {
        PaperDisplayPassengerServiceProvider(plugin)
    }
    plugin.server.servicesManager.register(
        ArcVisualPacketBudget::class.java,
        AllowAllVisualPacketBudget,
        plugin,
        ServicePriority.Normal,
    )
}

private object AllowAllVisualPacketBudget : ArcVisualPacketBudget {
    override fun acquire(
        source: String,
        connection: Any,
        bytes: Int,
        packets: Int,
        cleanup: Boolean,
        writable: Boolean,
    ): VisualPacketAdmission = VisualPacketAdmission.ALLOWED

    override fun recordSent(source: String, bytes: Int, packets: Int, cleanup: Boolean) = Unit
}
