package ru.arc

import io.kotest.core.spec.style.DescribeSpec
import ru.arc.config.ConfigManager
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.loadPlugin
import ru.ruscrafting.builder.paper.ArcBuilderPlugin
import java.nio.file.Path

abstract class KotestTestBase(
    body: KotestTestBase.() -> Unit = {},
) : DescribeSpec() {
    private lateinit var paper: MockBukkitTestRuntime
    val server get() = paper.server
    lateinit var plugin: ArcBuilderPlugin
        private set
    lateinit var dataPath: Path
        private set

    init {
        beforeSpec {
            ConfigManager.clear()
            paper = MockBukkitTestRuntime.open()
            plugin = paper.loadPlugin<ArcBuilderPlugin>()
            dataPath = plugin.dataPath
        }
        afterSpec {
            paper.close()
            ConfigManager.clear()
        }
        body()
    }
}
