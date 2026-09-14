package ru.arc.buildertools

import com.jeff_media.customblockdata.CustomBlockData
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataType
import ru.arc.paper.testing.MockBukkitTestRuntime

class BuilderTravelAnchorSafetyTest : FunSpec({
    test("ARC travel anchors are unsafe for every builder operation") {
        MockBukkitTestRuntime.open().use { paper ->
            val builder = paper.createSimplePlugin("ArcBuilderSafetyTest")
            val arc = paper.createSimplePlugin("ARC")
            val anchor = paper.addSimpleWorld("travel-anchor-safety").getBlockAt(0, 64, 0)
            anchor.type = Material.LODESTONE
            CustomBlockData(anchor, arc).set(
                NamespacedKey("arc", "travel_anchor"),
                PersistentDataType.BYTE,
                1.toByte(),
            )

            val safety = BuilderBlockSafety(builder, setOf("AIR", "SHORT_GRASS"))

            safety.isSafeExisting(anchor) shouldBe false
            safety.isReplaceable(anchor) shouldBe false
        }
    }
})
