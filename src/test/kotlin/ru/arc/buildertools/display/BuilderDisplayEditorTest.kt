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
import io.mockk.verify
import io.papermc.paper.connection.PlayerGameConnection
import io.papermc.paper.dialog.DialogResponseView
import io.papermc.paper.event.player.PlayerCustomClickEvent
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
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
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.RayTraceResult
import org.bukkit.util.Vector
import org.joml.Vector3d
import org.joml.Matrix4f
import org.mockbukkit.mockbukkit.entity.EntityMock
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.Locale
import java.util.concurrent.CompletableFuture

/** Native entity/session orchestration. Exact Paper ray collision and client rendering are separate checks. */
class BuilderDisplayEditorTest : FunSpec({
    for (mode in DisplayEditorMode.entries) test("model grab returns to free ${mode.name.lowercase()} after a committed axis gesture") {
        displayFixture { f ->
            val saved = f.createBlock()
            f.command(mode.name.lowercase())
            val tip = f.tip(saved, DisplayEditorAxis.X)
            f.lookAt(tip)
            f.rightClick()
            if (mode == DisplayEditorMode.ROTATE) f.player.setRotation(f.player.yaw + 4f, f.player.pitch + 7f)
            else f.lookAt(Vector3d(tip).add(.3, 0.0, 0.0))
            f.editor.tick()
            f.frames.last().parts.first().height shouldBe .055f
            f.rightClick()

            val before = f.editor.capture(saved)!!
            f.lookAt(before.pose.position())
            f.rightClick()
            val yaw = f.player.yaw
            val pitch = f.player.pitch
            f.player.setRotation(yaw + 12f, pitch - 10f)
            f.editor.tick()
            f.assertFreeAxes()
            when (mode) {
                DisplayEditorMode.MOVE -> {
                    val moved = f.previewPose()
                    (kotlin.math.abs(moved.x - before.pose.x) > .1) shouldBe true
                    (moved.y > before.pose.y + .1) shouldBe true
                }
                DisplayEditorMode.ROTATE -> {
                    // Observe the real draft's render frame, avoiding MockBukkit's lossy SVD approximation.
                    val expected = before.pose.withMatrix(Matrix4f()
                        .rotateY(Math.toRadians(-DisplayEditorGeometry.yawDelta(yaw, f.player.yaw)).toFloat())
                        .rotateX(Math.toRadians((f.player.pitch - pitch).toDouble()).toFloat())
                        .mul(before.pose.matrix()))
                    f.frames.last() shouldBe DisplayEditorAxesGeometry.frame(expected, before.content.mesh(), DisplayEditorAxis.FREE)
                    (f.frames.last() != DisplayEditorAxesGeometry.frame(before.pose, before.content.mesh(), DisplayEditorAxis.FREE)) shouldBe true
                }
                DisplayEditorMode.SCALE -> {
                    val initial = DisplayEditorGeometry.scale(before.pose)
                    val resized = DisplayEditorGeometry.scale(f.previewPose())
                    val factor = kotlin.math.exp(.25)
                    (resized.x / initial.x).toDouble() shouldBe (factor plusOrMinus .00001)
                    (resized.y / initial.y).toDouble() shouldBe (factor plusOrMinus .00001)
                    (resized.z / initial.z).toDouble() shouldBe (factor plusOrMinus .00001)
                }
            }
            f.editor.capture(saved) shouldBe before
        }
    }

    test("F frees an active handle and rebases the new mode without moving the current draft") {
        displayFixture { f ->
            val saved = f.createBlock()
            val tip = f.tip(saved, DisplayEditorAxis.X)
            f.lookAt(tip)
            f.rightClick()
            f.lookAt(Vector3d(tip).add(.4, 0.0, 0.0))
            f.editor.tick()
            val moved = f.previewPose()
            f.swap()
            f.editor.tick()
            BuilderDisplayEditor.samePose(f.previewPose(), moved) shouldBe true
            f.assertFreeAxes()
            val yaw = f.player.yaw
            val pitch = f.player.pitch
            f.player.setRotation(yaw + 10f, pitch - 5f)
            f.editor.tick()
            val expected = moved.withMatrix(Matrix4f().rotateY(Math.toRadians(-10.0).toFloat()).rotateX(Math.toRadians(-5.0).toFloat()).mul(moved.matrix()))
            f.frames.last() shouldBe DisplayEditorAxesGeometry.frame(expected, f.editor.capture(saved)!!.content.mesh(), DisplayEditorAxis.FREE)
        }
    }

    test("menu axis applies to one body gesture and cancel exit and creation clear unused constraints") {
        displayFixture { f ->
            val saved = f.createBlock()
            val before = f.editor.capture(saved)!!
            f.command("axis")
            f.click("axis_y")
            f.command("tool")
            f.lookAt(before.pose.position())
            f.rightClick()
            f.player.setRotation(12f, -10f)
            f.editor.tick()
            f.previewPose().x shouldBe (before.pose.x plusOrMinus .00001)
            (f.previewPose().y > before.pose.y + .1) shouldBe true
            f.previewPose().z shouldBe (before.pose.z plusOrMinus .00001)
            f.command("cancel")
            f.lookAt(before.pose.position())
            f.rightClick()
            f.player.setRotation(12f, -10f)
            f.editor.tick()
            f.assertFreeAxes()
            (kotlin.math.abs(f.previewPose().x - before.pose.x) > .1) shouldBe true
            (f.previewPose().y > before.pose.y + .1) shouldBe true
            f.command("cancel")

            for (abandon in listOf("cancel", "exit")) {
                f.command("axis", "x")
                f.command(abandon)
                f.command("tool")
                f.lookAt(before.pose.position())
                f.rightClick()
                f.editor.tick()
                f.assertFreeAxes()
                f.command("cancel")
            }
            f.command("axis", "x")
            f.command("block")
            f.editor.tick()
            f.assertFreeAxes()
        }
    }

    test("hovered Display handle starts a constrained move and active right click commits instead of repicking") {
        displayFixture { f ->
            val saved = f.createBlock()
            val before = f.editor.capture(saved)!!
            val tip = f.tip(saved, DisplayEditorAxis.X)
            f.lookAt(tip)
            f.editor.tick()
            f.frames.last().parts.filter { it.hovered }.map { it.axis } shouldBe listOf(0, 0)
            f.rightClick()
            f.displays() shouldHaveSize 2
            f.lookAt(Vector3d(tip).add(.6, 0.0, 0.0))
            f.editor.tick()
            val moved = f.previewPose()
            moved.x shouldBe (before.pose.x + .6 plusOrMinus .00001)
            moved.y shouldBe (before.pose.y plusOrMinus .00001)
            moved.z shouldBe (before.pose.z plusOrMinus .00001)
            f.editor.capture(saved) shouldBe before
            f.rightClick()
            f.displays() shouldHaveSize 1
            BuilderDisplayEditor.samePose(f.editor.capture(saved)!!.pose, moved) shouldBe true
            f.command("undo")
            BuilderDisplayEditor.samePose(f.editor.capture(saved)!!.pose, before.pose) shouldBe true
        }
    }

    test("captured move keeps its axis when Shift sees an unrelated surface") {
        displayFixture { f ->
            val saved = f.createBlock()
            val before = f.editor.capture(saved)!!
            val tip = f.tip(saved, DisplayEditorAxis.X)
            f.lookAt(tip)
            f.rightClick()
            f.player.isSneaking = true
            f.rayHit = RayTraceResult(Vector(3.0, 68.0, 4.0), BlockFace.DOWN)
            f.lookAt(Vector3d(tip).add(.4, 0.0, 0.0))
            f.editor.tick()
            val moved = f.previewPose()
            moved.x shouldBe (before.pose.x + .4 plusOrMinus .00001)
            moved.y shouldBe (before.pose.y plusOrMinus .00001)
            moved.z shouldBe (before.pose.z plusOrMinus .00001)
            f.command("cancel")
            f.editor.capture(saved) shouldBe before
        }
    }

    test("captured scale grows outward shrinks inward and preserves the other world dimensions") {
        displayFixture { f ->
            val saved = f.createBlock()
            val before = f.editor.capture(saved)!!
            f.command("scale")
            val tip = f.tip(saved, DisplayEditorAxis.X)
            f.lookAt(tip)
            f.rightClick()
            f.lookAt(Vector3d(tip).add(.6, 0.0, 0.0))
            f.editor.tick()
            val larger = f.previewPose()
            (DisplayEditorGeometry.scale(larger).x > 1f) shouldBe true
            DisplayEditorGeometry.scale(larger).y shouldBe 1f
            DisplayEditorGeometry.scale(larger).z shouldBe 1f
            f.lookAt(Vector3d(tip).add(-.3, 0.0, 0.0))
            f.editor.tick()
            val smaller = f.previewPose()
            (DisplayEditorGeometry.scale(smaller).x < 1f) shouldBe true
            smaller.position() shouldBe before.pose.position()
            f.command("cancel")
            f.editor.capture(saved) shouldBe before
        }
    }

    test("a valid axis drag pauses then resumes without a jump and retains wheel changes") {
        displayFixture { f ->
            val saved = f.createBlock()
            f.command("scale")
            val tip = f.tip(saved, DisplayEditorAxis.X)
            f.lookAt(tip)
            f.rightClick()
            f.lookAt(Vector3d(tip).add(.5, 0.0, 0.0))
            f.editor.tick()
            val enlarged = f.previewPose()
            f.player.setRotation(-90f, 0f) // Exactly along positive world X.
            f.editor.tick()
            BuilderDisplayEditor.samePose(f.previewPose(), enlarged) shouldBe true
            f.editor.onWheel(PlayerItemHeldEvent(f.player, 0, 1))
            f.editor.tick()
            val wheelAdjusted = f.previewPose()
            (DisplayEditorGeometry.scale(wheelAdjusted).x > DisplayEditorGeometry.scale(enlarged).x) shouldBe true
            f.lookAt(Vector3d(tip).add(1.0, 0.0, 0.0))
            f.editor.tick()
            BuilderDisplayEditor.samePose(f.previewPose(), wheelAdjusted) shouldBe true
            f.lookAt(Vector3d(tip).add(1.2, 0.0, 0.0))
            f.editor.tick()
            (DisplayEditorGeometry.scale(f.previewPose()).x > DisplayEditorGeometry.scale(wheelAdjusted).x) shouldBe true
        }
    }

    test("handle clicks recheck current aim source and access and never target the idle triad") {
        displayFixture { f ->
            f.command("tool")
            f.editor.tick()
            f.rightClick()
            f.displays() shouldHaveSize 0
            val saved = f.createBlock()
            val before = f.editor.capture(saved)!!
            val tip = f.tip(saved, DisplayEditorAxis.X)
            f.lookAt(tip)
            f.editor.tick()
            f.frames.last().parts.any { it.hovered } shouldBe true
            f.rayHit = RayTraceResult(f.player.eyeLocation.toVector().add(f.player.eyeLocation.direction), BlockFace.NORTH)
            f.rightClick()
            f.frames.last().parts.any { it.hovered } shouldBe false
            f.displays() shouldHaveSize 1
            f.rayHit = null
            f.buildAccess = { it.y != 66 }
            f.rightClick()
            f.displays() shouldHaveSize 1
            f.buildAccess = { true }
            saved.teleport(saved.location.add(0.0, 3.0, 0.0))
            f.rightClick()
            f.displays() shouldHaveSize 1
            (f.editor.capture(saved)!!.pose.y > before.pose.y + 2) shouldBe true
        }
    }

    test("Shift F cancels the private gesture and opens free axes while F still cycles mode") {
        displayFixture { f ->
            val saved = f.createBlock()
            val before = f.editor.capture(saved)
            f.command("axis", "x")
            f.rightClick()
            f.player.setRotation(15f, 0f)
            f.editor.tick()
            f.displays() shouldHaveSize 2
            f.player.isSneaking = true
            f.swap().isCancelled shouldBe true
            f.screen().id shouldBe "builder.display-editor.axes"
            f.screen().buttons.single { it.id.value == "axis_free" }.label shouldBe Component.text("display-editor.axis-selected-free").decoration(TextDecoration.ITALIC, false)
            f.screen().numberInputs.map { it.initial } shouldBe listOf(100f, 100f, 100f)
            f.displays() shouldHaveSize 1
            f.editor.capture(saved) shouldBe before
            f.player.isSneaking = false
            f.swap().isCancelled shouldBe true
            f.command("menu")
            f.screen().buttons.single { it.id.value == "rotate" }.label shouldBe Component.text("display-editor.mode-selected-rotate").decoration(TextDecoration.ITALIC, false)
        }
    }

    test("axis menu without selection keeps axis choice and offers no size mutation controls") {
        displayFixture { f ->
            f.command("tool")
            f.player.isSneaking = true
            f.swap()
            f.screen().numberInputs shouldHaveSize 0
            f.screen().buttons.map { it.id.value } shouldBe listOf("axis_free", "axis_x", "axis_y", "axis_z")
            f.click("axis_z")
            f.screen().buttons.single { it.id.value == "axis_z" }.label shouldBe Component.text("display-editor.axis-selected-z").decoration(TextDecoration.ITALIC, false)
            f.command("exit")
            f.swap().isCancelled shouldBe false
            f.displays() shouldHaveSize 0
        }
    }

    test("local size sliders retain unsaved input across axis choices and apply with undo") {
        displayFixture { f ->
            val saved = f.createBlock()
            val before = f.editor.capture(saved)!!
            f.command("axis")
            f.clickScale("axis_y", 125f, 75f, 200f)
            f.screen().numberInputs.map { it.initial } shouldBe listOf(125f, 75f, 200f)
            f.editor.capture(saved) shouldBe before
            f.clickScale("apply_scale", 125f, 75f, 200f)
            val resized = f.editor.capture(saved)!!
            DisplayEditorGeometry.scale(resized.pose) shouldBe DisplayEditorScale(1.25f, .75f, 2f)
            resized.pose.position() shouldBe before.pose.position()
            resized.content shouldBe before.content
            f.command("undo")
            BuilderDisplayEditor.samePose(f.editor.capture(saved)!!.pose, before.pose) shouldBe true
        }
    }

    test("menu resets rotation and size independently and each remains undoable") {
        displayFixture { f ->
            val saved = f.createBlock()
            f.command("axis")
            f.clickScale("apply_scale", 200f, 75f, 150f)
            // Entity yaw is a separate native rotation; the capture combines it with the display matrix.
            saved.setRotation(35f, 0f)
            val rotated = f.editor.capture(saved)!!
            f.command("axis")
            f.click("reset_rotation")
            val resetRotation = f.editor.capture(saved)!!
            BuilderDisplayEditor.samePose(resetRotation.pose, DisplayEditorGeometry.withoutRotation(rotated.pose)) shouldBe true
            resetRotation.content shouldBe rotated.content
            saved.location.yaw shouldBe 0f
            f.click("reset_scale")
            val resetScale = f.editor.capture(saved)!!
            DisplayEditorGeometry.scale(resetScale.pose) shouldBe DisplayEditorScale(1f, 1f, 1f)
            resetScale.pose.position() shouldBe rotated.pose.position()
            f.command("undo")
            BuilderDisplayEditor.samePose(f.editor.capture(saved)!!.pose, resetRotation.pose) shouldBe true
        }
    }

    test("scale mutation refuses source changes access loss revoked permission and stale callbacks") {
        displayFixture { f ->
            val saved = f.createBlock()
            f.command("axis")
            saved.block = Material.GOLD_BLOCK.createBlockData()
            val changed = f.editor.capture(saved)!!
            f.clickScale("apply_scale", 150f, 150f, 150f)
            f.editor.capture(saved) shouldBe changed
            f.buildAccess = { it.y != 66 }
            f.clickScale("apply_scale", 150f, 150f, 150f)
            f.editor.capture(saved) shouldBe changed
            f.buildAccess = { true }
            val stale = f.actionKey("reset_rotation")
            f.permission.setPermission(BuilderDisplayEditor.PERMISSION, false)
            f.clickKey(stale)
            f.editor.capture(saved) shouldBe changed
            f.command("exit")
            f.permission.setPermission(BuilderDisplayEditor.PERMISSION, true)
            f.clickKey(stale)
            f.editor.capture(saved) shouldBe changed
        }
    }

    test("scale application protects expanded destination cells and validates every submitted number") {
        displayFixture { f ->
            val saved = f.createBlock()
            val before = f.editor.capture(saved)!!
            f.command("axis")
            for (value in listOf(null, Float.NaN, 0f, 4f, 401f, 100.5f)) {
                f.clickScale("apply_scale", value, 100f, 100f)
                f.editor.capture(saved) shouldBe before
            }
            // x=1 is outside the original [0,1) footprint but inside the requested wider object.
            f.buildAccess = { it.x != 1 }
            f.clickScale("apply_scale", 300f, 100f, 100f)
            f.editor.capture(saved) shouldBe before
        }
    }

    test("input claim and private axes are released on exit quit permission loss and shutdown") {
        displayFixture { f ->
            f.command("tool")
            f.player.getMetadata(BuilderDisplayEditor.INPUT_METADATA).single().let {
                it.owningPlugin shouldBe f.plugin
                it.asBoolean() shouldBe true
            }
            f.editor.tick()
            verify { f.axes.update(f.player, match { it.parts.size == 6 }) }
            f.command("axis")
            f.player.hasMetadata(BuilderDisplayEditor.INPUT_METADATA) shouldBe true
            f.command("exit")
            f.player.hasMetadata(BuilderDisplayEditor.INPUT_METADATA) shouldBe false
            verify { f.axes.clear(f.player.uniqueId) }
            // Cleanup must also remove this plugin's claim when no session remains.
            f.player.setMetadata(BuilderDisplayEditor.INPUT_METADATA, FixedMetadataValue(f.plugin, true))
            f.command("exit")
            f.player.hasMetadata(BuilderDisplayEditor.INPUT_METADATA) shouldBe false
            f.command("tool")
            f.editor.onQuit(PlayerQuitEvent(f.player, Component.empty()))
            f.player.hasMetadata(BuilderDisplayEditor.INPUT_METADATA) shouldBe false
            f.command("tool")
            f.permission.setPermission(BuilderDisplayEditor.PERMISSION, false)
            f.editor.tick()
            f.player.hasMetadata(BuilderDisplayEditor.INPUT_METADATA) shouldBe false
            f.permission.setPermission(BuilderDisplayEditor.PERMISSION, true)
            f.command("tool")
            f.editor.closeAsync().isDone shouldBe true
            f.player.hasMetadata(BuilderDisplayEditor.INPUT_METADATA) shouldBe false
            verify { f.axes.closeAsync() }
        }
    }

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
    val frames = mutableListOf<DisplayEditorAxesFrame>()
    private val screens = mutableListOf<Pair<PaperDialogScreen, Any>>()
    private val dialogs = spyk(testDialogs(plugin) { screen, registration -> screens += screen to registration })
    val axes = mockk<DisplayEditorAxes>(relaxed = true) {
        every { closeAsync() } returns CompletableFuture.completedFuture(null)
        every { update(any(), any()) } answers { frames += secondArg<DisplayEditorAxesFrame>() }
    }
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
            }, axes = axes)
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
    fun lookAt(point: Vector3d) {
        val at = player.eyeLocation
        at.direction = Vector(point.x - at.x, point.y - at.y, point.z - at.z)
        player.setRotation(at.yaw, at.pitch)
    }
    fun assertFreeAxes() {
        frames.last().parts.filterIndexed { index, _ -> index % 2 == 0 }.forEach { rod ->
            listOf(rod.width, rod.height, rod.depth).filterIndexed { index, _ -> index != rod.axis }
                .all { it == .025f } shouldBe true
        }
    }
    fun tip(display: Display, axis: DisplayEditorAxis): Vector3d {
        val state = editor.capture(display)!!
        val part = DisplayEditorAxesGeometry.frame(state.pose, state.content.mesh(), DisplayEditorAxis.FREE).parts[(axis.ordinal - 1) * 2 + 1]
        return state.pose.position().add(part.x + part.width / 2.0, part.y + part.height / 2.0, part.z + part.depth / 2.0)
    }
    fun previewPose(): DisplayEditorPose {
        val preview = displays().single { !it.isPersistent }
        val native = preview.transformation
        val matrix = org.joml.Matrix4f().translate(native.translation).rotate(native.leftRotation).scale(native.scale).rotate(native.rightRotation).translate(.5f, .5f, .5f)
        val centre = matrix.getTranslation(org.joml.Vector3f())
        matrix.setTranslation(0f, 0f, 0f)
        return DisplayEditorPose(preview.location.x + centre.x, preview.location.y + centre.y, preview.location.z + centre.z).withMatrix(matrix)
    }
    fun swap() = PlayerSwapHandItemsEvent(player, player.inventory.itemInMainHand, player.inventory.itemInOffHand).also(editor::onSwap)
    fun actionKey(action: String): String {
        val nonce = screens.last().second.javaClass.getMethod("getNonce").invoke(screens.last().second) as String
        return "${plugin.name.lowercase(Locale.ROOT)}:dialog/$nonce/$action"
    }
    fun click(action: String) = clickKey(actionKey(action))
    fun clickText(action: String, value: String) = clickKey(actionKey(action), value)
    fun clickScale(action: String, x: Float?, y: Float?, z: Float?) = clickKey(actionKey(action), numbers = mapOf("scale_x" to x, "scale_y" to y, "scale_z" to z))
    fun screen(): PaperDialogScreen = screens.last().first
    fun clickKey(key: String, text: String? = null, numbers: Map<String, Float?>? = null) {
        val connection = mockk<PlayerGameConnection> { every { player } returns this@DisplayFixture.player }
        val response = if (text == null && numbers == null) null else mockk<DialogResponseView> {
            every { getText(any()) } returns text
            every { getFloat(any()) } answers { numbers?.get(firstArg<String>()) }
        }
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
