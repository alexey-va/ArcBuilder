package ru.arc.hooks

import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.buildertools.BuilderBookAuctionPort
import ru.arc.buildertools.BuilderMaterialPresentation
import ru.arc.hooks.economyshop.EconomyShopGuiPurchaseService
import ru.arc.hooks.economyshop.ShopPurchaseService
import ru.arc.hooks.lands.LandsHook
import ru.arc.hooks.slimefun.SFHook
import ru.arc.hooks.zauction.BuilderAuctionHook

/** Optional integrations owned by ArcBuilder; deliberately no WorldGuard. */
internal object HookRegistry {
    @JvmField var landsHook: LandsHook? = null
    @JvmField var sfHook: SFHook? = null
    @JvmField var auctionHook: BuilderBookAuctionPort? = null
    @JvmField var shopPurchaseService: ShopPurchaseService? = null

    fun start(plugin: JavaPlugin) {
        val manager = Bukkit.getPluginManager()
        landsHook = if (manager.isPluginEnabled("Lands")) LandsHook(plugin) else null
        sfHook = if (manager.isPluginEnabled("Slimefun")) SFHook() else null
        shopPurchaseService = if (
            manager.isPluginEnabled("EconomyShopGUI-Premium") || manager.isPluginEnabled("EconomyShopGUI")
        ) {
            EconomyShopGuiPurchaseService { stack ->
                stack?.type?.let(BuilderMaterialPresentation::readableName).orEmpty()
            }
        } else null
        auctionHook = if (
            manager.isPluginEnabled("zAuctionHouse") || manager.isPluginEnabled("zAuctionHouseV3")
        ) {
            BuilderAuctionHook(plugin)
        } else null
    }

    fun close() {
        auctionHook?.setDeliveryHandler(null)
        (auctionHook as? AutoCloseable)?.close()
        auctionHook = null
        shopPurchaseService = null
        landsHook = null
        sfHook = null
    }
}
