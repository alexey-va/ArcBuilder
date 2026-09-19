package ru.arc.buildertools

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.net.URLClassLoader
import java.util.UUID

class ExternalArcBuilderTelemetryBridgeTest : StringSpec({
    "does not load the optional ARC API when ARC is absent" {
        MockBukkitTestRuntime.open().use { runtime ->
            runtime.server.pluginManager.isPluginEnabled("ARC") shouldBe false
            val targetName = "ru.arc.buildertools.ExternalArcBuilderTelemetryBridge"
            val source = ExternalArcBuilderTelemetryBridge::class.java.protectionDomain.codeSource.location
            var apiLoads = 0
            object : URLClassLoader(arrayOf(source), ExternalArcBuilderTelemetryBridge::class.java.classLoader) {
                override fun loadClass(name: String, resolve: Boolean): Class<*> {
                    if (name.startsWith("ru.arc.paper.api.")) {
                        apiLoads++
                        throw ClassNotFoundException(name)
                    }
                    if (name.startsWith(targetName)) {
                        return (findLoadedClass(name) ?: findClass(name)).also {
                            if (resolve) resolveClass(it)
                        }
                    }
                    return super.loadClass(name, resolve)
                }
            }.use { isolated ->
                val type = isolated.loadClass(targetName)
                val instance = type.getField("INSTANCE").get(null)
                type.getMethod("completed", UUID::class.java, String::class.java)
                    .invoke(instance, UUID.randomUUID(), "operation:missing:api")
                apiLoads shouldBe 0
            }
        }
    }
})
