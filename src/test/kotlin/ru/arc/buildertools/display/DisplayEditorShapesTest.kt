package ru.arc.buildertools.display

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.spyk
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.data.type.Slab
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import ru.arc.paper.testing.MockBukkitTestRuntime

class DisplayEditorShapesTest : FunSpec({
    test("typed slab variants match the vanilla bottom top and double model bounds") {
        MockBukkitTestRuntime.open().use {
            val slab = Material.OAK_SLAB.createBlockData() as Slab
            for ((type, low, high) in listOf(
                Triple(Slab.Type.BOTTOM, 0.0, .5), Triple(Slab.Type.TOP, .5, 1.0), Triple(Slab.Type.DOUBLE, 0.0, 1.0),
            )) {
                slab.type = type
                val shape = DisplayEditorShapes.block(slab)
                val centre = shape.centre()
                val pose = DisplayEditorPose(centre.x.toDouble(), centre.y.toDouble(), centre.z.toDouble())
                val corners = DisplayEditorGeometry.corners(pose, shape.mesh)
                corners.minOf { it.y } shouldBe low
                corners.maxOf { it.y } shouldBe high
                corners.minOf { it.x } shouldBe 0.0
                corners.maxOf { it.x } shouldBe 1.0
            }
        }
    }

    test("verified direct generated and handheld models are thin while unverified models keep fallback") {
        MockBukkitTestRuntime.open().use {
            for (material in listOf(Material.DIAMOND, Material.IRON_SWORD, Material.STICK, Material.OAK_DOOR)) {
                DisplayEditorShapes.item(ItemStack(material)).mesh shouldBe DisplayEditorMesh(1.0, 1.0, 1.0 / 16)
            }
            for (material in listOf(Material.STONE, Material.BOW, Material.FISHING_ROD, Material.COMPASS)) {
                DisplayEditorShapes.item(ItemStack(material)).mesh shouldBe DisplayEditorMesh(1.0, 1.0, 1.0)
            }
        }
    }

    @Suppress("DEPRECATION")
    test("explicit item models and either custom model data representation cannot inherit vanilla flat geometry") {
        MockBukkitTestRuntime.open().use {
            fun withPreservedMeta(configure: ItemMeta.() -> Unit): ItemStack {
                val stack = spyk(ItemStack(Material.DIAMOND))
                val meta = stack.itemMeta.apply(configure)
                // MockBukkit 4.116.3 ItemMetaMock.clone/equals omit itemModel and
                // customModelDataComponent. Preserve only those real metadata flags here.
                every { stack.hasItemMeta() } returns true
                every { stack.itemMeta } returns meta
                return stack
            }
            val explicitModel = withPreservedMeta { itemModel = NamespacedKey("custom", "trophy") }
            explicitModel.itemMeta.hasItemModel() shouldBe true
            val legacyData = ItemStack(Material.DIAMOND).apply {
                itemMeta = itemMeta.apply { setCustomModelData(7) }
            }
            legacyData.itemMeta.hasCustomModelData() shouldBe true
            val componentData = withPreservedMeta {
                setCustomModelDataComponent(customModelDataComponent.apply { floats = listOf(7f) })
            }
            componentData.itemMeta.hasCustomModelDataComponent() shouldBe true
            for ((label, stack) in listOf("item model" to explicitModel, "legacy CMD" to legacyData, "component CMD" to componentData)) {
                withClue(label) { DisplayEditorShapes.item(stack).mesh shouldBe DisplayEditorMesh(1.0, 1.0, 1.0) }
            }
        }
    }
})
