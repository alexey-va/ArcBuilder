package ru.arc

import ru.ruscrafting.builder.paper.ArcBuilderPlugin

/**
 * Small migration bridge for the extracted builder domain. It intentionally
 * exposes only the plugin identity and backend id; no ARC module registry is
 * retained in the standalone plugin.
 */
object ARC {
    @Volatile
    private var active: ArcBuilderPlugin? = null

    val instance: ArcBuilderPlugin
        get() = checkNotNull(active) { "ArcBuilder is not enabled" }

    val plugin: ArcBuilderPlugin?
        get() = active

    @Volatile
    var serverName: String? = null
        private set

    fun install(plugin: ArcBuilderPlugin, serverId: String) {
        check(active == null || active === plugin) { "ArcBuilder plugin identity is already installed" }
        active = plugin
        serverName = serverId
    }

    fun clear(plugin: ArcBuilderPlugin) {
        if (active === plugin) {
            active = null
            serverName = null
        }
    }
}
