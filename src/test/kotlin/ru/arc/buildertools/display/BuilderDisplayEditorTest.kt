package ru.arc.buildertools.display

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
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
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.RayTraceResult
import org.bukkit.util.Vector
import org.mockbukkit.mockbukkit.entity.EntityMock
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.Locale

/** Native entity/session orchestration. Exact Paper ray collision and client rendering are separate checks. */
class BuilderDisplayEditorTest : FunSpec({
    test("Shift magnet saves a bottom slab against the ceiling using its rendered top face") {
        displayFixture { f ->
            f.command("block", "oak_slab")
            f.rayHit = RayTraceResult(Vector(.5, 67.0, 3.5), BlockFace.DOWN)
            f.player.isSneaking = true
            f.editor.tick()
            f.command("confirm")
            val saved = f.displays().single() as BlockDisplay
            val snapshot = f.editor.capture(saved)!!
            snapshot.content.mesh().height shouldBe .5
            snapshot.pose.y shouldBe (66.744 plusOrMinus .00001)
            // The actual vanilla slab top is native-local y=.5, not the unit block top y=1.
            (saved.location.y + saved.transformation.translation.y + .5) shouldBe (66.994 plusOrMinus .00001)
            DisplayEditorGeometry.corners(snapshot.pose, snapshot.content.mesh()).maxOf { it.y } shouldBe (66.994 plusOrMinus .00001)
        }
    }

    test("Shift magnet saves a generated diamond against a wall using its native thin face") {
        displayFixture { f ->
            f.command("item", "diamond")
            f.rayHit = RayTraceResult(Vector(.5, f.player.eyeLocation.y, 4.0), BlockFace.NORTH)
            f.player.isSneaking = true
            f.editor.tick()
            f.command("confirm")
            val saved = f.displays().single() as ItemDisplay
            val snapshot = f.editor.capture(saved)!!
            snapshot.content.mesh().depth shouldBe (1.0 / 16)
            snapshot.pose.z shouldBe (3.96275 plusOrMinus .00001)
            saved.itemDisplayTransform shouldBe ItemDisplay.ItemDisplayTransform.NONE
            // ItemModelGenerator's max Z=8.5/16 is centred by the native NONE item render.
            (saved.location.z + saved.transformation.translation.z + 1.0 / 32) shouldBe (3.994 plusOrMinus .00001)
            DisplayEditorGeometry.corners(snapshot.pose, snapshot.content.mesh()).maxOf { it.z } shouldBe (3.994 plusOrMinus .00001)
        }
    }

    test("creation is private until commit and does not consume or mint inventory items") {
        displayFixture { f ->
            val inventory = f.player.inventory.contents.map { it?.clone() }
            f.command("item")
            f.displays().single().isPersistent shouldBe false
            f.displays().single().isVisibleByDefault shouldBe false
            f.command("confirm")
            val saved = f.displays().single() as ItemDisplay
            saved.isPersistent shouldBe true
            saved.isVisibleByDefault shouldBe true
            saved.itemStack.amount shouldBe 1
            f.player.inventory.contents.toList() shouldBe inventory
            f.command("duplicate")
            f.command("confirm")
            f.displays().filter { it.isPersistent } shouldHaveSize 2
            f.player.inventory.contents.toList() shouldBe inventory
            f.command("undo")
            f.displays().filter { it.isPersistent } shouldHaveSize 1
            f.player.inventory.contents.toList() shouldBe inventory
        }
    }

    test("cancel and quit discard a private drag and leave the persistent original unchanged") {
        displayFixture { f ->
            val saved = f.createBlock()
            val before = f.editor.capture(saved)
            f.rightClick()
            f.displays() shouldHaveSize 2
            f.player.setRotation(15f, 0f)
            f.editor.tick()
            f.editor.capture(saved) shouldBe before
            f.command("cancel")
            f.displays() shouldHaveSize 1
            f.editor.capture(saved) shouldBe before
            f.player.setRotation(0f, 0f)
            f.rightClick()
            f.editor.onQuit(PlayerQuitEvent(f.player, Component.empty()))
            f.displays() shouldHaveSize 1
            f.editor.capture(saved) shouldBe before
            f.editor.hasSession(f.player) shouldBe false
        }
    }

    test("text creates commits and edits literal content with retained validation input and undo") {
        displayFixture { f ->
            f.command("text", "Home\\nWorkshop")
            (f.displays().single() as TextDisplay).text() shouldBe Component.text("Home\nWorkshop")
            f.displays().single().isPersistent shouldBe false
            f.command("confirm")
            val saved = f.displays().single() as TextDisplay
            saved.isPersistent shouldBe true
            val before = f.editor.capture(saved)!!
            f.command("menu")
            f.click("edit_text")
            val invalid = "x".repeat(121)
            f.clickText("save_text", invalid)
            f.screen().inputs.single().initial shouldBe invalid
            f.screen().body.size shouldBe 2
            saved.text() shouldBe Component.text("Home\nWorkshop")
            f.clickText("save_text", "<red>Literal</red>")
            saved.text() shouldBe Component.text("<red>Literal</red>")
            BuilderDisplayEditor.samePose(before.pose, f.editor.capture(saved)!!.pose) shouldBe true
            f.command("undo")
            saved.text() shouldBe Component.text("Home\nWorkshop")
            BuilderDisplayEditor.samePose(before.pose, f.editor.capture(saved)!!.pose) shouldBe true
        }
    }

    test("permission revocation discards a draft without touching the saved object") {
        displayFixture { f ->
            val saved = f.createBlock()
            val before = f.editor.capture(saved)
            f.rightClick()
            f.player.setRotation(10f, 0f)
            f.editor.tick()
            f.permission.setPermission(BuilderDisplayEditor.PERMISSION, false)
            f.editor.tick()
            f.displays() shouldHaveSize 1
            f.editor.capture(saved) shouldBe before
            f.editor.hasSession(f.player) shouldBe false
        }
    }

    test("final creation checks every touched cell beyond the permitted anchor") {
        displayFixture { f ->
            f.buildAccess = { it.y != 66 }
            f.command("block")
            f.displays() shouldHaveSize 1
            f.command("confirm")
            f.displays() shouldHaveSize 0
        }
    }

    test("final movement rechecks both original source and destination") {
        displayFixture { f ->
            val saved = f.createBlock()
            val before = f.editor.capture(saved)!!
            f.rightClick()
            f.player.setRotation(25f, 0f)
            f.editor.tick()
            // The source footprint includes y=66 although its anchor is y=65.
            f.buildAccess = { !(it.y == 66 && it.x == 0 && it.z == 3) }
            f.command("confirm")
            f.displays() shouldHaveSize 1
            f.editor.capture(saved) shouldBe before
        }
    }

    test("stale source and stale delete dialog cannot overwrite external changes") {
        displayFixture { f ->
            val saved = f.createBlock()
            f.rightClick()
            saved.block = Material.DIAMOND_BLOCK.createBlockData()
            f.command("confirm")
            saved.block.material shouldBe Material.DIAMOND_BLOCK
            f.displays() shouldHaveSize 1
            f.command("delete")
            val staleDelete = f.actionKey("confirm_delete")
            saved.block = Material.GOLD_BLOCK.createBlockData()
            f.clickKey(staleDelete)
            saved.isValid shouldBe true
            saved.block.material shouldBe Material.GOLD_BLOCK
        }
    }

    test("delete restore remaps older undo records onto the new native entity UUID") {
        displayFixture { f ->
            val original = f.createBlock()
            f.command("delete")
            f.click("confirm_delete")
            f.displays() shouldHaveSize 0
            f.command("undo")
            f.displays() shouldHaveSize 1
            (f.displays().single().uniqueId != original.uniqueId) shouldBe true
            f.command("undo")
            f.displays() shouldHaveSize 0
        }
    }

    test("foreign displays are never adopted even with admin permission") {
        displayFixture { f ->
            f.permission.setPermission(BuilderDisplayEditor.ADMIN, true)
            val foreign = f.world.spawn(Location(f.world, .5, f.player.eyeLocation.y, 3.5), BlockDisplay::class.java)
            foreign.block = Material.GOLD_BLOCK.createBlockData()
            f.command("tool")
            f.rightClick()
            f.displays() shouldHaveSize 1
            f.editor.capture(foreign) shouldBe null
            f.command("delete")
            foreign.isValid shouldBe true
            foreign.block.material shouldBe Material.GOLD_BLOCK
        }
    }

    test("offhand use is cancelled and stale dialog actions lose authority after exit") {
        displayFixture { f ->
            f.command("tool")
            val event = PlayerInteractEvent(f.player, Action.RIGHT_CLICK_AIR, ItemStack(Material.WATER_BUCKET), null, BlockFace.SELF, EquipmentSlot.OFF_HAND)
            f.editor.onInteract(event)
            event.isCancelled shouldBe true
            f.command("menu")
            val createKey = f.actionKey("block")
            f.command("exit")
            f.clickKey(createKey)
            f.displays() shouldHaveSize 0
            f.editor.isEditing(f.player) shouldBe false
        }
    }

    test("shutdown removes private drafts but retains permanent creations") {
        displayFixture { f ->
            val saved = f.createBlock()
            f.command("duplicate")
            f.displays() shouldHaveSize 2
            f.editor.close()
            f.displays().map { it.uniqueId } shouldBe listOf(saved.uniqueId)
        }
    }
})

private fun displayFixture(run: (DisplayFixture) -> Unit) {
    MockBukkitTestRuntime.open().use { paper -> DisplayFixture(paper).use(run) }
}

private class DisplayFixture(paper: MockBukkitTestRuntime) : AutoCloseable {
    val plugin = paper.createSimplePlugin("DisplayEditorTest")
    val world = paper.addSimpleWorld("display-editor")
    val player = paper.addPlayer("Decorator")
    val permission = player.addAttachment(plugin, BuilderDisplayEditor.PERMISSION, true)
    var buildAccess: (Block) -> Boolean = { true }
    var rayHit: RayTraceResult? = null
    private val screens = mutableListOf<Pair<PaperDialogScreen, Any>>()
    private val dialogs = spyk(testDialogs(plugin) { screen, registration -> screens += screen to registration })
    val editor: BuilderDisplayEditor

    init {
        for (x in -2..2) for (z in -2..2) world.loadChunk(x, z)
        player.teleport(Location(world, .5, 64.0, .5, 0f, 0f))
        player.inventory.setItemInMainHand(ItemStack(Material.STONE, 64))
        // Native dialog rendering/dismissal is absent from MockBukkit; keep real nonce/action dispatch.
        every { dialogs.close(any<Player>()) } just Runs
        editor = BuilderDisplayEditor(plugin, { _, key, _ -> Component.text(key) }, { _, block -> buildAccess(block) }, dialogs,
            traceBlocks = { _, _, _, _ -> rayHit },
            spawnDisplay = { nativeWorld, at, type, configure ->
                val display = spyk(nativeWorld.createEntity(at, type))
                // Exact upstream mock gaps: EntityMock visibility and DisplayMock billboard.
                // Keep the production pre-spawn initializer and native registry/lifecycle unchanged.
                var visible = true
                var billboard = Display.Billboard.FIXED
                every { display.isVisibleByDefault = any() } answers { visible = firstArg() }
                every { display.isVisibleByDefault } answers { visible }
                every { display.billboard = any() } answers { billboard = firstArg() }
                every { display.billboard } answers { billboard }
                configure(display)
                paper.server.registerEntity(display as EntityMock)
                display
            })
    }

    fun command(vararg args: String) { editor.command(player, args.toList()) }
    fun displays(): List<Display> = world.entities.filterIsInstance<Display>().filter { it.isValid }
    fun createBlock(): BlockDisplay {
        command("block")
        command("confirm")
        return displays().single() as BlockDisplay
    }
    fun rightClick() {
        editor.tick()
        editor.onInteract(PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, player.inventory.itemInMainHand, null, BlockFace.SELF, EquipmentSlot.HAND))
    }
    fun actionKey(action: String): String {
        val nonce = screens.last().second.javaClass.getMethod("getNonce").invoke(screens.last().second) as String
        return "${plugin.name.lowercase(Locale.ROOT)}:dialog/$nonce/$action"
    }
    fun click(action: String) = clickKey(actionKey(action))
    fun clickText(action: String, value: String) = clickKey(actionKey(action), value)
    fun screen(): PaperDialogScreen = screens.last().first
    fun clickKey(key: String, text: String? = null) {
        val connection = mockk<PlayerGameConnection> { every { player } returns this@DisplayFixture.player }
        val response = text?.let { value -> mockk<DialogResponseView> { every { getText("display_text") } returns value } }
        dialogs.onCustomClick(mockk<PlayerCustomClickEvent> {
            every { commonConnection } returns connection
            every { identifier } returns Key.key(key)
            every { dialogResponseView } returns response
        })
    }
    override fun close() { editor.close() }
}

private fun testDialogs(plugin: JavaPlugin, presenter: (PaperDialogScreen, Any) -> Unit): PaperDialogRuntime {
    val constructor = PaperDialogRuntime::class.java.getDeclaredConstructor(org.bukkit.plugin.Plugin::class.java, Function3::class.java)
    constructor.isAccessible = true
    val callback: (Player, PaperDialogScreen, Any) -> Unit = { _, screen, registration -> presenter(screen, registration) }
    return constructor.newInstance(plugin, callback) as PaperDialogRuntime
}
