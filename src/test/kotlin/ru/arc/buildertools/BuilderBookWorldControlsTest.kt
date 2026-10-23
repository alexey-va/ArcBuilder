package ru.arc.buildertools

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import ru.arc.config.Config
import java.nio.file.Files
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

class BuilderBookWorldControlsTest : StringSpec({
    "near ghost controls preserve angular size and keep every corner and sight line outside the schematic" {
        val worldId = UUID.randomUUID()
        val geometry = BuilderBookPlaqueGeometry.create(BuilderBlockPos(worldId, 0, 64, 0), BuilderConstructionSitePanelFace.MIN_Z,
            listOf(BuilderBlockPos(worldId, -4, 64, -3), BuilderBlockPos(worldId, 5, 68, -3)), 2.25, .4, 4.5, 1.5)
        val placement = checkNotNull(geometry.controls(1.0, -4.0))
        placement.distance shouldBe .85
        val layout = BuilderBookWorldControls.layout({ net.kyori.adventure.text.Component.text(it) }, "quote", distanceScale = placement.scale)
        val rects = layout.buttons.map { it.rect } + layout.labels.map { it.rect }
        val eye = ru.arc.paper.actionpanel.ActionPanelPoint(1.0, 65.62, -4.0)
        val anchor = ru.arc.paper.actionpanel.ActionPanelGeometry.followingAnchor(eye, 180f, rects, placement.distance, 0.0, 0.0)
        val corners = rects.flatMap { rect -> listOf(-1, 1).flatMap { sx -> listOf(-1, 1).map { sy ->
            ru.arc.paper.actionpanel.ActionPanelGeometry.localToWorld(anchor, ru.arc.paper.actionpanel.ActionPanelPoint(
                rect.center.x + sx * rect.width / 2, rect.center.y + sy * rect.height / 2, 0.0))
        } } }
        corners.forEach { corner ->
            (corner.z <= -3.149) shouldBe true
            (maxOf(eye.z, corner.z) < -3.0) shouldBe true
        }
        (kotlin.math.abs(layout.buttons.first().rect.width / placement.distance - .76 / 2.4) < 1e-9) shouldBe true
        geometry.controls(1.0, -3.05) shouldBe null
        geometry.controls(1.0, -2.5) shouldBe null
        val corner = checkNotNull(geometry.controls(-5.01, -4.0))
        var controlFace: BuilderConstructionSitePanelFace? = corner.face
        val cornerTrace = listOf(-5.01, -4.99, -5.01, -4.98, -5.02, -4.8, -4.7).map { x ->
            val current = checkNotNull(geometry.controls(x, -4.0, controlFace))
            if (x <= -4.8) current.face shouldBe corner.face
            controlFace = current.face
            mapOf("viewerX" to x, "viewerZ" to -4.0, "placement" to current)
        }
        controlFace shouldBe BuilderConstructionSitePanelFace.MIN_Z
        geometry.controls(1.0, -3.8) shouldBe null
        checkNotNull(geometry.controls(1.0, -3.8, BuilderConstructionSitePanelFace.MIN_Z)).face shouldBe BuilderConstructionSitePanelFace.MIN_Z
        geometry.controls(1.0, -3.74, BuilderConstructionSitePanelFace.MIN_Z) shouldBe null
        checkNotNull(geometry.controls(1.0, -7.0)).scale shouldBe 1.0
        java.io.File("build/reports/builder-book-controls/near-ghost.json").apply {
            parentFile.mkdirs()
            writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
                "geometry" to geometry, "eye" to eye, "placement" to placement, "anchor" to anchor, "corners" to corners,
                "fallbackViewerZ" to listOf(-3.05, -2.5), "cornerTrace" to cornerTrace)))
        }
    }

    "six directions form a horizontal cross and a separate height pair with stable action geometry" {
        val messages = BuilderToolsConfig(Config(Files.createTempDirectory("builder-book-cross-"), "modules/builder-tools.yml")).messages()
        val plain = PlainTextComponentSerializer.plainText()
        val samples = listOf("ru", "en").flatMap { locale -> listOf("quote", "activate", "continue", "preparing", "cooldown", "error", "near-ghost").map { state ->
            val layout = BuilderBookWorldControls.layout({ key -> messages.render("book.world-controls.$key", locale,
                mapOf("name" to messages.literal("Берёзовый дом"), "price" to messages.literal("50,000,000.00"),
                    "hours" to messages.literal(2), "minutes" to messages.literal(15))) },
                when(state) { "cooldown" -> "continue"; "error" -> "activate"; "near-ghost" -> "quote"; else -> state },
                if (state in listOf("preparing", "cooldown", "error")) state else "hint", state == "preparing", state != "cooldown",
                if (state == "near-ghost") .85 / 2.4 else 1.0)
            val byAction = layout.buttons.associateBy { it.action }
            val away = byAction.getValue(BuilderBookWorldAction.AWAY).rect.center
            val toward = byAction.getValue(BuilderBookWorldAction.TOWARD).rect.center
            val left = byAction.getValue(BuilderBookWorldAction.LEFT).rect.center
            val right = byAction.getValue(BuilderBookWorldAction.RIGHT).rect.center
            away.x shouldBe toward.x
            left.y shouldBe right.y
            (away.y > left.y && toward.y < left.y && left.x < away.x && right.x > away.x) shouldBe true
            (byAction.getValue(BuilderBookWorldAction.UP).rect.center.x > right.x) shouldBe true
            layout.buttons.map { it.action }.toSet() shouldBe BuilderBookWorldAction.entries.toSet()
            layout.buttons.forEachIndexed { index, a -> layout.buttons.drop(index + 1).forEach { b ->
                val separated = kotlin.math.abs(a.rect.center.x - b.rect.center.x) >= (a.rect.width + b.rect.width) / 2 ||
                    kotlin.math.abs(a.rect.center.y - b.rect.center.y) >= (a.rect.height + b.rect.height) / 2
                separated shouldBe true
            } }
            val anchor = ru.arc.paper.actionpanel.ActionPanelGeometry.followingAnchor(
                ru.arc.paper.actionpanel.ActionPanelPoint(0.0, 0.0, 0.0), 180f,
                layout.buttons.map { it.rect } + layout.labels.map { it.rect },
                if (state == "near-ghost") .85 else 2.4, 0.0, 0.0)
            mapOf("locale" to locale, "state" to state, "anchorY" to anchor.point.y,
                "distance" to if (state == "near-ghost") .85 else 2.4,
                "viewerZ" to if (state == "near-ghost") -4.0 else -7.0, "buttons" to layout.buttons.map { mapOf(
                "action" to it.action.name, "text" to plain.serialize(it.text), "rect" to it.rect,
                "component" to GsonComponentSerializer.gson().serialize(it.text),
                "scale" to it.style.scale, "lineWidth" to it.style.lineWidth, "baselineInset" to it.style.baselineInset, "enabled" to it.enabled,
            ) }, "labels" to layout.labels.map { mapOf("key" to it.key, "text" to plain.serialize(it.text),
                "component" to GsonComponentSerializer.gson().serialize(it.text),
                "rect" to it.rect, "scale" to it.style.scale, "lineWidth" to it.style.lineWidth, "baselineInset" to it.style.baselineInset) })
        } }
        java.io.File("build/reports/builder-book-controls/layout.json").apply {
            parentFile.mkdirs()
            writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(samples))
        }
    }

    "nearest plaque follows the edge tangent and crosses corners without rescanning or corner flicker" {
        val worldId = UUID.randomUUID()
        val origin = BuilderBlockPos(worldId, 0, 64, 0)
        val occupied = listOf(BuilderBlockPos(worldId, -4, 66, -3), BuilderBlockPos(worldId, 5, 66, 4),
            BuilderBlockPos(worldId, -20, 64, -20), BuilderBlockPos(worldId, 20, 75, 20))
        val geometry = BuilderBookPlaqueGeometry.create(origin, BuilderConstructionSitePanelFace.MIN_X, occupied,
            2.25, .4, 4.5, 1.5)
        listOf(geometry.minX, geometry.minZ, geometry.maxX, geometry.maxZ) shouldBe listOf(-4.0, -3.0, 6.0, 5.0)
        geometry.nearest(-8.0, -1.0).placement.z shouldBe -1.0
        geometry.nearest(-8.0, 3.0).placement.z shouldBe 3.0
        val face = geometry.nearest(-7.0, -6.0).face
        listOf(-.03, .03, -.02, .02).forEach { jitter -> geometry.nearest(-7.0 + jitter, -6.0 - jitter, face).face shouldBe face }
        var previous: BuilderConstructionSitePanelFace? = null
        val frames = (0..120).map { tick ->
            val angle = tick * Math.PI * 2 / 120
            val x = 1 + cos(angle) * 9
            val z = 1 + sin(angle) * 8
            val next = geometry.nearest(x, z, previous)
            previous = next.face
            val p = next.placement
            (p.x < geometry.minX || p.x > geometry.maxX || p.z < geometry.minZ || p.z > geometry.maxZ) shouldBe true
            mapOf("tick" to tick, "viewerX" to x, "viewerZ" to z, "face" to next.face.name, "placement" to p)
        }
        frames.map { it["face"] }.toSet().size shouldBe 4
        java.io.File("build/reports/builder-book-controls/trajectory.json").apply {
            parentFile.mkdirs()
            writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(mapOf("geometry" to geometry, "frames" to frames)))
        }
    }
})
