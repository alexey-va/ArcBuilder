package ru.arc.buildertools

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.bukkit.Color
import ru.arc.config.Config
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.hypot

class BuilderPlaqueAppearanceTest : FunSpec({
    test("staged appearance parsing and non-native validation do not need Bukkit registries") {
        val root = Files.createTempDirectory("builder-plaque-staged-")
        val config = Config(root, "modules/builder-tools.yml")
        BuilderPlaqueAppearance.read(config).glowMaterial shouldBe org.bukkit.Material.LIGHT_BLUE_STAINED_GLASS
        BuilderToolsConfig(config).validated(validateNativeMaterials = false).plaqueAppearance().trail.enabled shouldBe true
    }

    test("legacy ARGB remains exact and RGB plus opacity independently controls the background") {
        val config = Config(Files.createTempDirectory("builder-plaque-"), "modules/builder-tools.yml")
        for (argb in listOf("#B21C2328", "#E6101418")) {
            config.setString("construction.site.panel.background-color", argb)
            BuilderPlaqueAppearance.read(config).background.asARGB() shouldBe argb.substring(1).toLong(16).toInt()
        }
        config.setString("construction.site.panel.background-color", "#204060")
        config.setDouble("construction.site.panel.background-opacity", .5)
        BuilderPlaqueAppearance.read(config).background shouldBe Color.fromARGB(128, 32, 64, 96)
        config.setDouble("construction.site.panel.background-opacity", 0.0)
        BuilderPlaqueAppearance.read(config).background.alpha shouldBe 0
    }

    test("invalid particle data colors ranges and cadence cannot reach an emitter") {
        val bad = mapOf(
            "background-color" to "#XYZXYZ", "background-opacity" to "1.1", "glow.color" to "blue",
            "glow.material" to "AIR", "glow.thickness" to "0", "border-trail.particle" to "BLOCK",
            "border-trail.color" to "#FFFF", "border-trail.size" to "0", "border-trail.speed" to "NaN",
            "border-trail.tail-length" to "9", "border-trail.spacing" to "0", "border-trail.padding" to "-1",
            "border-trail.update-interval-ticks" to "6", "border-trail.view-range" to "65", "border-trail.max-viewers" to "33",
        )
        for ((key, value) in bad) {
            val config = Config(Files.createTempDirectory("builder-plaque-invalid-"), "modules/builder-tools.yml")
            config.setString("construction.site.panel.$key", value)
            shouldThrowAny { BuilderPlaqueAppearance.read(config) }
        }
    }

    test("the clockwise head wraps continuously and the tapered tail stays outside text at every corner") {
        val settings = BuilderPlaqueTrail(speed = 3.2, padding = .1, tailLength = 1.0)
        val first = builderPlaqueTrail(4.0, 2.0, 0, settings)
        val repeated = builderPlaqueTrail(4.0, 2.0, 80, settings)
        first.zip(repeated).forEach { (a, b) ->
            a.x shouldBe (b.x plusOrMinus 1e-8)
            a.y shouldBe (b.y plusOrMinus 1e-8)
        }
        for (tick in 0L..160L) {
            val points = builderPlaqueTrail(4.0, 2.0, tick, settings)
            points.zipWithNext().all { (a, b) -> a.strength > b.strength } shouldBe true
            points.forEach { point ->
                (abs(abs(point.x) - 2.1) < 1e-8 || abs(point.y + .1) < 1e-8 || abs(point.y - 2.1) < 1e-8) shouldBe true
            }
            val next = builderPlaqueTrail(4.0, 2.0, tick + 1, settings).first()
            (hypot(points.first().x - next.x, points.first().y - next.y) <= .16000001) shouldBe true
        }
    }

    test("tail density has a hard emission cap and a disabled trail has no births") {
        builderPlaqueTrail(20.0, 4.0, 1, BuilderPlaqueTrail(tailLength = 8.0, spacing = .05)).size shouldBe 32
        builderPlaqueTrail(20.0, 4.0, 1, BuilderPlaqueTrail(enabled = false)) shouldBe emptyList()
    }

    test("export a representative plaque using production text settings and particle geometry") {
        val config = BuilderToolsConfig(Config(Files.createTempDirectory("builder-plaque-preview-"), "modules/builder-tools.yml"))
        val messages = config.messages()
        val text = messages.render("book.preview-panel", "ru", mapOf("name" to messages.literal("Берёзовый коттедж")))
        val appearance = config.plaqueAppearance()
        val (width, height) = BuilderWorldPlaque.decorationBounds(text, config.constructionSitePanelLineWidth)
        // Verified against the pinned 1.21.11 font asset: 156px widest line, 3 native lines.
        width shouldBe (7.9 plusOrMinus 1e-8)
        height shouldBe (1.5 plusOrMinus 1e-8)
        BuilderWorldPlaque.visualHeight(text, config.constructionSitePanelLineWidth) shouldBe 3.0
        val cycleTicks = 2 * (width + height + appearance.trail.padding * 4) / appearance.trail.speed * 20
        val stopTick = kotlin.math.ceil(cycleTicks * 2).toInt()
        java.io.File("build/reports/builder-plaque/motion.json").apply {
            parentFile.mkdirs()
            writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
                "evidence" to "Production geometry and locale export; not a native client render",
                "text" to net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.builder().character('§').hexColors().build().serialize(text),
                "lineWidth" to config.constructionSitePanelLineWidth,
                "width" to width, "height" to height, "appearance" to appearance,
                "cycleTicks" to cycleTicks, "stopTick" to stopTick, "endTick" to stopTick + 40,
                "frames" to (0 until stopTick step appearance.trail.intervalTicks).map { tick ->
                    mapOf("tick" to tick, "points" to builderPlaqueTrail(width, height, tick.toLong(), appearance.trail))
                },
            )))
        }
    }
})
