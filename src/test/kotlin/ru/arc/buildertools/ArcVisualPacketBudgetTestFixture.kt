package ru.arc.buildertools

import org.bukkit.plugin.Plugin
import org.bukkit.plugin.ServicePriority
import ru.arc.paper.api.ArcVisualPacketBudget
import ru.arc.paper.api.VisualPacketAdmission

/** Supplies the shared ARC service required while MockBukkit constructs packet displays. */
internal fun installArcVisualPacketBudgetFixture(plugin: Plugin) {
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
