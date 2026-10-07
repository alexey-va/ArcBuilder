package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.spyk
import io.papermc.paper.connection.PlayerGameConnection
import io.papermc.paper.dialog.DialogResponseView
import io.papermc.paper.event.player.PlayerCustomClickEvent
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
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

class BuilderCrownSelectionDialogTest : FunSpec({
    test("native crown settings can revise density and leaf material before preview") {
        BuilderLocalePolicy.configure(defaultLocaleTag = "en", followClientLocale = false)
        try {
            MockBukkitTestRuntime.open().use { paper ->
                val player = spyk(paper.addPlayer("CrownDialog"))
                val plugin = paper.createSimplePlugin("BuilderCrownDialogTest")
                val harness = CrownDialogHarness(plugin, player)
                harness.use {
                    val world = paper.addSimpleWorld("crown-dialog")
                    val selection = BuilderSelection(
                        BuilderBlockPos(world.uid, 0, 64, 0),
                        BuilderBlockPos(world.uid, 4, 64, 0),
                    )
                    val expectedPlanId = java.util.UUID.randomUUID()
                    var previewed: Pair<UUIDAndSelection, BuilderCrownSelectionOptions>? = null
                    harness.open(
                        selection,
                        expectedPlanId = expectedPlanId,
                    ) { captured, planId, options ->
                        previewed = UUIDAndSelection(planId, captured) to options
                        true
                    }

                    harness.screen().numberInputs.single().initial shouldBe 3f
                    harness.click("density_dense", thickness = 4f)
                    harness.screen().buttons
                        .single { it.id.value == "density_dense" }
                        .label.let(PlainTextComponentSerializer.plainText()::serialize)
                        .contains("Dense selected") shouldBe true
                    harness.screen().numberInputs.single().initial shouldBe 4f

                    harness.click("material", thickness = 5f)
                    harness.screen().id shouldBe "builder.material-picker.crown-leaf-title"
                    harness.screen().buttons.count { it.id.value.startsWith("material_") } shouldBe 2
                    harness.click("material_0")
                    harness.screen().id shouldBe "builder.crown-selection"
                    harness.screen().buttons
                        .single { it.id.value == "material" }
                        .label.let(PlainTextComponentSerializer.plainText()::serialize)
                        .contains("Birch Leaves") shouldBe true

                    harness.click("preview")

                    previewed shouldBe (UUIDAndSelection(expectedPlanId, selection) to BuilderCrownSelectionOptions(
                        thickness = 5,
                        density = BuilderCrownDensity.DENSE,
                        materialName = "birch_leaves",
                    ))
                    harness.dialog.isOpen(player.uniqueId) shouldBe false
                }
            }
        } finally {
            BuilderLocalePolicy.configure(defaultLocaleTag = "ru", followClientLocale = false)
        }
    }

    test("stale selector or selection closes settings without creating a preview") {
        BuilderLocalePolicy.configure(defaultLocaleTag = "en", followClientLocale = false)
        try {
            MockBukkitTestRuntime.open().use { paper ->
                val player = spyk(paper.addPlayer("CrownDialogStale"))
                val plugin = paper.createSimplePlugin("BuilderCrownDialogStaleTest")
                CrownDialogHarness(plugin, player).use { harness ->
                    var current = true
                    var previews = 0
                    val world = paper.addSimpleWorld("crown-dialog-stale")
                    val captured = BuilderSelection(
                        BuilderBlockPos(world.uid, 0, 64, 0),
                        BuilderBlockPos(world.uid, 0, 64, 0),
                    )
                    harness.open(captured, isCurrent = { current }) { _, _, _ -> previews++; true }
                    val oldKey = harness.key("preview")
                    current = false
                    harness.clickKey(oldKey, thickness = 4f)
                    previews shouldBe 0
                    harness.dialog.isOpen(player.uniqueId) shouldBe false
                }
            }
        } finally {
            BuilderLocalePolicy.configure(defaultLocaleTag = "ru", followClientLocale = false)
        }
    }
})

private data class UUIDAndSelection(val planId: java.util.UUID?, val selection: BuilderSelection)

private class CrownDialogHarness(private val plugin: JavaPlugin, private val player: Player) : AutoCloseable {
    data class Presentation(val screen: PaperDialogScreen, val registration: Any)

    val presentations = mutableListOf<Presentation>()
    private val runtime = crownDialogTestRuntime(plugin) { _, screen, registration ->
        presentations += Presentation(screen, registration)
    }
    private val messages = LocalizedMiniMessage(
        catalogs = mapOf("en" to CrownDialogCatalog()),
        defaultLocale = { "en" },
    )
    private val picker = BuilderMaterialPicker(plugin, messages, runtime)
    private var currentGuard: () -> Boolean = { true }
    private var previewHandler: (BuilderSelection, java.util.UUID?, BuilderCrownSelectionOptions) -> Boolean =
        { _, _, _ -> false }
    val dialog = BuilderCrownSelectionDialog(
        messages = messages,
        dialogs = runtime,
        materialPicker = picker,
        leafMaterials = listOf(Material.OAK_LEAVES, Material.BIRCH_LEAVES),
        isCurrent = { _, _, _ -> currentGuard() },
        onPreview = { _, captured, planId, options -> previewHandler(captured, planId, options) },
    )

    init {
        every { player.closeDialog() } just Runs
    }

    fun open(
        selection: BuilderSelection,
        expectedPlanId: java.util.UUID? = null,
        isCurrent: () -> Boolean = { true },
        onPreview: (BuilderSelection, java.util.UUID?, BuilderCrownSelectionOptions) -> Boolean,
    ) {
        currentGuard = isCurrent
        previewHandler = onPreview
        dialog.open(player, selection, expectedPlanId, BuilderCrownSelectionOptions())
    }

    fun screen(): PaperDialogScreen = presentations.last().screen

    fun click(action: String, thickness: Float? = null) = clickKey(key(action), thickness)

    fun key(action: String): String {
        val registration = presentations.last().registration
        val nonce = registration.javaClass.getMethod("getNonce").invoke(registration) as String
        return "${plugin.name.lowercase(Locale.ROOT)}:dialog/$nonce/$action"
    }

    fun clickKey(key: String, thickness: Float? = null) {
        val connection = mockk<PlayerGameConnection> { every { player } returns this@CrownDialogHarness.player }
        val response = thickness?.let { value ->
            mockk<DialogResponseView> { every { getFloat("thickness") } returns value }
        }
        runtime.onCustomClick(mockk<PlayerCustomClickEvent> {
            every { commonConnection } returns connection
            every { identifier } returns Key.key(key)
            every { dialogResponseView } returns response
        })
    }

    override fun close() {
        dialog.close()
        picker.close()
    }
}

private fun crownDialogTestRuntime(
    plugin: JavaPlugin,
    presenter: (Player, PaperDialogScreen, Any) -> Unit,
): PaperDialogRuntime {
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

private class CrownDialogCatalog : LocaleCatalog {
    private val messages = mapOf(
        "material-picker.crown-leaf-title" to "Foliage material",
        "material-picker.search-label" to "Search",
        "material-picker.find" to "Find",
        "material-picker.no-results" to "No leaves found",
        "material-picker.page" to "Page <current> / <total>",
        "material-picker.back" to "Back",
        "crown-selection.title" to "Set up crown",
        "crown-selection.body" to "Selection <x> × <y> × <z> · thickness <thickness>",
        "crown-selection.thickness-label" to "Thickness",
        "crown-selection.density-option" to "<density>",
        "crown-selection.density-selected" to "<density> selected",
        "crown-selection.density-help" to "Foliage density",
        "crown-selection.material-button" to "Leaves: <material>",
        "crown-selection.material-help" to "Choose leaf material",
        "crown-selection.preview" to "Preview",
        "crown-selection.preview-help" to "Review changes and cost",
        "crown-selection.cancel" to "Back",
        "crown.labels.density.airy" to "Airy",
        "crown.labels.density.natural" to "Natural",
        "crown.labels.density.dense" to "Dense",
    )

    override fun scalar(path: String): String? = messages[path]
    override fun lines(path: String): List<String>? = null
}
