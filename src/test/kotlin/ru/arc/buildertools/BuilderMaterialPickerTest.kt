package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import io.papermc.paper.connection.PlayerGameConnection
import io.papermc.paper.dialog.DialogResponseView
import io.papermc.paper.event.player.PlayerCustomClickEvent
import net.kyori.adventure.key.Key
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.text.LocaleCatalog
import ru.arc.text.LocalizedMiniMessage
import java.util.Locale
import kotlin.Function3
import org.opentest4j.TestAbortedException

class BuilderMaterialPickerTest : FunSpec({
    test("search refreshes replace the same history visit") {
        strictPickerRuntime { paper ->
            val player = spyk(paper.addPlayer("PickerRefresh"))
            every { player.closeDialog() } just Runs
            val plugin = paper.createSimplePlugin("BuilderPickerRefreshTest")
            PickerHarness(plugin, player).use { harness ->
                harness.picker.open(
                    player,
                    FILL_TITLE,
                    listOf(Material.STONE, Material.DIRT),
                    isCurrent = { true },
                    onSelected = {},
                )

                listOf("stone", "dirt", "stone").forEach { harness.click("find", it) }
                harness.presentations.size shouldBe 4

                harness.clickExit()

                harness.picker.isOpen(player.uniqueId) shouldBe false
                harness.presentations.size shouldBe 4
                verify(exactly = 1) { player.closeDialog() }
            }
        }
    }

    test("replace source returns from target Back with the submitted query") {
        strictPickerRuntime { paper ->
            val player = spyk(paper.addPlayer("PickerReplace"))
            every { player.closeDialog() } just Runs
            val plugin = paper.createSimplePlugin("BuilderPickerReplaceTest")
            PickerHarness(plugin, player).use { harness ->
                var selectedSource: Material? = null
                harness.picker.open(
                    player,
                    SOURCE_TITLE,
                    listOf(Material.OAK_LOG),
                    isCurrent = { true },
                    onSelected = { source ->
                        selectedSource = source
                        harness.picker.open(
                            player,
                            TARGET_TITLE,
                            listOf(Material.BIRCH_LOG),
                            isCurrent = { true },
                            onSelected = {},
                            beginFlow = false,
                        )
                    },
                    closeOnSelect = false,
                )

                harness.click("find", "oak")
                harness.click("material_0", "oak")
                selectedSource shouldBe Material.OAK_LOG
                harness.presentations.last().screen.id shouldBe SCREEN_PREFIX + TARGET_TITLE

                harness.clickExit()

                val restored = harness.presentations.last().screen
                restored.id shouldBe SCREEN_PREFIX + SOURCE_TITLE
                restored.inputs.single().initial shouldBe "oak"
                harness.picker.isOpen(player.uniqueId) shouldBe true
            }
        }
    }

    test("a previous screen action cannot dispatch after close and a new flow") {
        strictPickerRuntime { paper ->
            val player = spyk(paper.addPlayer("PickerStale"))
            every { player.closeDialog() } just Runs
            val plugin = paper.createSimplePlugin("BuilderPickerStaleTest")
            PickerHarness(plugin, player).use { harness ->
                var oldSelections = 0
                var newSelections = 0
                harness.picker.open(
                    player,
                    FILL_TITLE,
                    listOf(Material.OAK_LOG),
                    isCurrent = { true },
                    onSelected = { oldSelections++ },
                )
                val oldPresentation = harness.presentations.lastIndex
                val staleKey = harness.key(oldPresentation, "material_0")

                harness.picker.close(player.uniqueId)
                harness.picker.open(
                    player,
                    FILL_TITLE,
                    listOf(Material.DIRT),
                    isCurrent = { true },
                    onSelected = { newSelections++ },
                )
                harness.clickKey(staleKey)

                oldSelections shouldBe 0
                newSelections shouldBe 0
                harness.picker.isOpen(player.uniqueId) shouldBe true

                harness.click("material_0")
                newSelections shouldBe 1
            }
        }
    }

    test("explicit search keeps twelve choices per page and retains query on next") {
        strictPickerRuntime { paper ->
            val player = spyk(paper.addPlayer("PickerPagination"))
            every { player.closeDialog() } just Runs
            val plugin = paper.createSimplePlugin("BuilderPickerPaginationTest")
            PickerHarness(plugin, player).use { harness ->
                val candidates = listOf(
                    Material.STONE, Material.SMOOTH_STONE, Material.STONE_SLAB,
                    Material.SMOOTH_STONE_SLAB, Material.STONE_STAIRS, Material.STONE_BRICKS,
                    Material.MOSSY_STONE_BRICKS, Material.CRACKED_STONE_BRICKS,
                    Material.CHISELED_STONE_BRICKS, Material.STONE_BRICK_SLAB,
                    Material.STONE_BRICK_STAIRS, Material.STONE_BRICK_WALL,
                    Material.MOSSY_STONE_BRICK_SLAB,
                )
                candidates.size shouldBe 13

                harness.picker.open(
                    player,
                    FILL_TITLE,
                    candidates,
                    isCurrent = { true },
                    onSelected = {},
                )

                harness.click("find", "stone")
                val firstPage = harness.presentations.last().screen
                firstPage.inputs.single().initial shouldBe "stone"
                firstPage.materialChoiceCount() shouldBe 12
                firstPage.buttons.any { it.id.value == "next" } shouldBe true

                harness.click("next", "stone")

                val lastPage = harness.presentations.last().screen
                lastPage.inputs.single().initial shouldBe "stone"
                lastPage.materialChoiceCount() shouldBe 1
                lastPage.buttons.any { it.id.value == "previous" } shouldBe true
            }
        }
    }
})

private fun strictPickerRuntime(action: (MockBukkitTestRuntime) -> Unit) {
    try {
        MockBukkitTestRuntime.open().use(action)
    } catch (failure: TestAbortedException) {
        throw AssertionError("Picker behavior must not silently skip an unsupported platform call", failure)
    }
}

private class PickerHarness(private val plugin: JavaPlugin, private val player: Player) : AutoCloseable {
    data class Presentation(val screen: PaperDialogScreen, val registration: Any)

    val presentations = mutableListOf<Presentation>()
    private val runtime = pickerTestRuntime(plugin) { _, screen, registration ->
        presentations += Presentation(screen, registration)
    }
    val picker = BuilderMaterialPicker(plugin, pickerMessages(), runtime)

    fun click(action: String, submittedQuery: String? = null) = clickKey(
        key(presentations.lastIndex, action),
        submittedQuery,
    )

    fun clickExit() {
        val exit = requireNotNull(presentations.last().screen.exitButton)
        click(exit.id.value)
    }

    fun key(presentationIndex: Int, action: String): String {
        val registration = presentations[presentationIndex].registration
        val nonce = registration.javaClass.getMethod("getNonce").invoke(registration) as String
        return "${plugin.name.lowercase(Locale.ROOT)}:dialog/$nonce/$action"
    }

    fun clickKey(key: String, submittedQuery: String? = null) {
        val actor = player
        val connection = mockk<PlayerGameConnection> {
            every { this@mockk.player } returns actor
        }
        val response = submittedQuery?.let { query ->
            mockk<DialogResponseView> { every { getText("query") } returns query }
        }
        runtime.onCustomClick(mockk<PlayerCustomClickEvent> {
            every { commonConnection } returns connection
            every { identifier } returns Key.key(key)
            every { dialogResponseView } returns response
        })
    }

    override fun close() = picker.close()
}

private fun PaperDialogScreen.materialChoiceCount(): Int = buttons.count { it.id.value.startsWith("material_") }

private fun pickerTestRuntime(
    plugin: JavaPlugin,
    presenter: (Player, PaperDialogScreen, Any) -> Unit,
): PaperDialogRuntime {
    // The public runtime constructor talks to Paper's native dialog API; tests use its real
    // history/session logic while capturing the presentation at its existing internal seam.
    val callback: (Player, PaperDialogScreen, Any) -> Unit = { player, screen, registration ->
        presenter(player, screen, registration)
    }
    val constructor = PaperDialogRuntime::class.java.getDeclaredConstructor(
        org.bukkit.plugin.Plugin::class.java,
        Function3::class.java,
    )
    constructor.isAccessible = true
    return constructor.newInstance(plugin, callback) as PaperDialogRuntime
}

private fun pickerMessages() = LocalizedMiniMessage(
    catalogs = mapOf("en" to PickerCatalog()),
    defaultLocale = { "en" },
)

private class PickerCatalog : LocaleCatalog {
    private val messages = mapOf(
        "material-picker.fill-title" to "Fill material",
        "material-picker.replace-source-title" to "Replace source",
        "material-picker.replace-target-title" to "Replace target",
        "material-picker.search-label" to "Search",
        "material-picker.find" to "Find",
        "material-picker.no-results" to "No materials found",
        "material-picker.page" to "Page <current> / <total>",
        "material-picker.previous" to "Previous",
        "material-picker.next" to "Next",
        "material-picker.back" to "Back",
    )

    override fun scalar(path: String): String? = messages[path]
    override fun lines(path: String): List<String>? = null
}

private const val FILL_TITLE = "material-picker.fill-title"
private const val SOURCE_TITLE = "material-picker.replace-source-title"
private const val TARGET_TITLE = "material-picker.replace-target-title"
private const val SCREEN_PREFIX = "builder."
