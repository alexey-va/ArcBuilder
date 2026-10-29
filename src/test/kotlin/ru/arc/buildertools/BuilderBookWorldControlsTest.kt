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
    "the complete owner overlay remains within ordinary reach in every facing direction" {
        val layout = BuilderBookWorldControls.layout({ net.kyori.adventure.text.Component.text(it) }, "quote")
        val rects = layout.buttons.map { it.rect } + layout.labels.map { it.rect }
        layout.labels.map { it.key } shouldBe listOf("title")
        (rects.maxOf { it.center.x + it.width / 2 } - rects.minOf { it.center.x - it.width / 2 } < 2.7) shouldBe true
        (rects.maxOf { it.center.y + it.height / 2 } - rects.minOf { it.center.y - it.height / 2 } < 1.6) shouldBe true
        val eye = ru.arc.paper.actionpanel.ActionPanelPoint(1.0, 65.62, -3.05)
        val frames = listOf(0f, 90f, 180f, 270f).map { yaw ->
            val anchor = ru.arc.paper.actionpanel.ActionPanelGeometry.followingAnchor(eye, yaw, rects, 2.4, 0.0, 0.0)
            ru.arc.paper.actionpanel.ActionPanelGeometry.allReachable(eye, anchor, rects, 4.0) shouldBe true
            val corners = rects.flatMap { rect -> listOf(-1, 1).flatMap { sx -> listOf(-1, 1).map { sy ->
                ru.arc.paper.actionpanel.ActionPanelGeometry.localToWorld(anchor, ru.arc.paper.actionpanel.ActionPanelPoint(
                    rect.center.x + sx * rect.width / 2, rect.center.y + sy * rect.height / 2, 0.0))
            } } }
            mapOf("yaw" to yaw, "anchor" to anchor, "corners" to corners)
        }
        java.io.File("build/reports/builder-book-controls/owner-overlay.json").apply {
            parentFile.mkdirs()
            writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
                "eye" to eye, "distance" to 2.4, "reach" to 4.0, "frames" to frames)))
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
                if (state in listOf("preparing", "cooldown", "error")) state else "hint", state == "preparing", state != "cooldown")
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
            val directions = listOf(BuilderBookWorldAction.AWAY, BuilderBookWorldAction.LEFT,
                BuilderBookWorldAction.RIGHT, BuilderBookWorldAction.TOWARD, BuilderBookWorldAction.UP, BuilderBookWorldAction.DOWN)
                .map(byAction::getValue)
            directions.map { plain.serialize(it.text) }.distinct() shouldBe listOf(">")
            directions.map { it.style.rotationDegrees } shouldBe listOf(90f, 180f, 0f, -90f, 90f, -90f)
            directions.map { it.style.scale }.distinct() shouldBe listOf(.9f)
            layout.labels.map { it.key } shouldBe if (state in listOf("preparing", "cooldown", "error"))
                listOf("title", "status") else listOf("title")
            if (state == "continue") plain.serialize(byAction.getValue(BuilderBookWorldAction.PRIMARY).text) shouldBe
                if (locale == "ru") "Начать стройку" else "Start building"
            layout.buttons.forEachIndexed { index, a -> layout.buttons.drop(index + 1).forEach { b ->
                val separated = kotlin.math.abs(a.rect.center.x - b.rect.center.x) >= (a.rect.width + b.rect.width) / 2 ||
                    kotlin.math.abs(a.rect.center.y - b.rect.center.y) >= (a.rect.height + b.rect.height) / 2
                separated shouldBe true
            } }
            val anchor = ru.arc.paper.actionpanel.ActionPanelGeometry.followingAnchor(
                ru.arc.paper.actionpanel.ActionPanelPoint(0.0, 0.0, 0.0), 180f,
                layout.buttons.map { it.rect } + layout.labels.map { it.rect },
                2.4, 0.0, 0.0)
            mapOf("locale" to locale, "state" to state, "anchorY" to anchor.point.y,
                "distance" to 2.4,
                "viewerZ" to if (state == "near-ghost") -3.05 else -7.0, "buttons" to layout.buttons.map { mapOf(
                "action" to it.action.name, "text" to plain.serialize(it.text), "rect" to it.rect,
                "component" to GsonComponentSerializer.gson().serialize(it.text),
                "scale" to it.style.scale, "lineWidth" to it.style.lineWidth, "baselineInset" to it.style.baselineInset,
                "rotationDegrees" to it.style.rotationDegrees, "enabled" to it.enabled,
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
