package ru.arc.buildertools

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.ARC
import ru.arc.config.ConfigManager
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.loadPlugin
import ru.ruscrafting.builder.paper.ArcBuilderPlugin

class ArcBuilderBootstrapMockBukkitTest : FunSpec({
    test("startup installs the complete configuration and command binding before returning") {
        ConfigManager.clear()
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<ArcBuilderPlugin>()

            plugin.isEnabled shouldBe true
            ARC.plugin shouldBe plugin
            BuilderToolsConfiguration.current().dataRoot shouldBe plugin.dataPath
            checkNotNull(plugin.getCommand("builder")).executor shouldBe BuilderToolsModule
        }
    }

    test("disable clears bindings without a deferred configuration callback") {
        ConfigManager.clear()
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<ArcBuilderPlugin>()
            paper.server.pluginManager.disablePlugin(plugin)

            plugin.isEnabled shouldBe false
            ARC.plugin shouldBe null
            shouldThrow<IllegalStateException> { BuilderToolsConfiguration.current() }
            paper.performTicks(3)
            ARC.plugin shouldBe null
        }
    }
})
