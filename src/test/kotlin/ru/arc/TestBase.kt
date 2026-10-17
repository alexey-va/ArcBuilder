package ru.arc

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import ru.arc.config.ConfigManager
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.loadPlugin
import ru.ruscrafting.builder.paper.ArcBuilderPlugin
import java.nio.file.Path

abstract class TestBase {
    private lateinit var paper: MockBukkitTestRuntime
    protected val server get() = paper.server
    protected lateinit var plugin: ArcBuilderPlugin
    protected lateinit var dataPath: Path

    @BeforeEach
    fun setUpArcBuilder() {
        ConfigManager.clear()
        paper = MockBukkitTestRuntime.open()
        plugin = paper.loadPlugin<ArcBuilderPlugin>()
        dataPath = plugin.dataPath
    }

    @AfterEach
    fun tearDownArcBuilder() {
        paper.close()
        ConfigManager.clear()
    }
}
