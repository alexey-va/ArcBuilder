package ru.arc.autobuild.gui

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import ru.arc.KotestTestBase
import ru.arc.autobuild.BuildBookSettings
import ru.arc.config.ConfigManager
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import java.nio.file.Files

class BuildBookEditorPresentationTest : KotestTestBase({
    val plain = PlainTextComponentSerializer.plainText()

    describe("build-book editor surface") {
        it("uses Escape instead of a close-only save control") {
            val config = ConfigManager.ofModule(dataPath, "auto-build.yml")

            BuildBookSettings.validate()
            config.stringOrNull("build-book.editor.close.name") shouldBe null
            config.stringListOrNull("build-book.editor.close.lore") shouldBe null

            config.componentList("build-book.editor.overview.lore") {
                tag("name", Component.text("Дом"))
                tag("rotation", Component.text(90))
                tag("offset_x", Component.text(2))
                tag("offset_y", Component.text(1))
                tag("offset_z", Component.text(-3))
            }.map(plain::serialize) shouldContainExactly listOf(
                "Поворот: 90°",
                "Сдвиг: 2 / 1 / -3",
                "",
                "Esc — закрыть",
            )
        }

        it("merge-forwards new editor text without replacing operator settings") {
            val path = ConfigManager.moduleYamlPath(dataPath, "auto-build.yml")
            val original = Files.readString(path)
            try {
                Files.writeString(
                    path,
                    """
                    build-book:
                      player-copy:
                        max-offset: 12
                        max-per-player: 17
                        custom-model-data: 0
                        default-name: Operator draft
                    operator-only:
                      keep: true
                    """.trimIndent() + "\n",
                )
                ConfigManager.clear()

                BuildBookSettings.validate()
                val merged = ConfigManager.ofModule(dataPath, "auto-build.yml")
                merged.int("build-book.player-copy.max-offset") shouldBe 12
                merged.string("build-book.player-copy.default-name") shouldBe "Operator draft"
                merged.boolean("operator-only.keep") shouldBe true
                merged.string("build-book.editor.title") shouldBe "<dark_gray>Настройка книги"
                merged.string("build-book.editor.preview-protection-denied.name") shouldBe
                    "<#ff9f0f><bold>Положение недоступно"

                val afterFirstValidation = Files.readString(path)
                BuildBookSettings.validate()
                Files.readString(path) shouldBe afterFirstValidation
            } finally {
                Files.writeString(path, original)
                ConfigManager.clear()
            }
        }

        it("explicitly disables italics on every final name and lore root") {
            val item = BuildBookEditorPresentation.item(
                material = Material.BOOK,
                name = Component.text("Настройка книги"),
                lore = listOf(Component.text("Положение"), Component.empty(), Component.text("Esc — вернуться")),
            )
            val meta = checkNotNull(item.itemMeta)

            checkNotNull(meta.displayName()).decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
            meta.lore().orEmpty().forEach { line ->
                line.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
            }
        }

        it("shows rejection on the clicked item and restores it after two seconds") {
            val scheduler = TestTaskScheduler()
            var refreshes = 0
            val controller = BuildBookEditorFeedbackController(
                refresh = { refreshes++ },
                taskScope = LifecycleTaskScope(scheduler),
            )
            val item = BuildBookEditorPresentation.item(
                Material.CLOCK,
                Component.text("Поворот"),
                listOf(Component.text("Текущее значение: 0°")),
            )
            val rejection = BuildBookEditorPresentation.state(
                Component.text("Положение недоступно"),
                listOf(Component.text("Выберите другое положение")),
            )

            controller.show(item, rejection)
            plain.serialize(checkNotNull(item.itemMeta.displayName())) shouldBe "Положение недоступно"
            refreshes shouldBe 1

            scheduler.tick(39)
            plain.serialize(checkNotNull(item.itemMeta.displayName())) shouldBe "Положение недоступно"
            scheduler.tick()
            plain.serialize(checkNotNull(item.itemMeta.displayName())) shouldBe "Поворот"
            refreshes shouldBe 2
        }

        it("keeps only the newest feedback during rapid clicks") {
            val scheduler = TestTaskScheduler()
            var refreshes = 0
            val controller = BuildBookEditorFeedbackController(
                refresh = { refreshes++ },
                taskScope = LifecycleTaskScope(scheduler),
            )
            val x = BuildBookEditorPresentation.item(Material.REDSTONE_TORCH, Component.text("Смещение X"), emptyList())
            val y = BuildBookEditorPresentation.item(Material.SCAFFOLDING, Component.text("Высота Y"), emptyList())
            val rejection = BuildBookEditorPresentation.state(Component.text("Недоступно"), emptyList())

            controller.show(x, rejection)
            controller.show(y, rejection)

            plain.serialize(checkNotNull(x.itemMeta.displayName())) shouldBe "Смещение X"
            plain.serialize(checkNotNull(y.itemMeta.displayName())) shouldBe "Недоступно"
            scheduler.pendingCount() shouldBe 1
            refreshes shouldBe 2

            scheduler.tick(40)
            plain.serialize(checkNotNull(x.itemMeta.displayName())) shouldBe "Смещение X"
            plain.serialize(checkNotNull(y.itemMeta.displayName())) shouldBe "Высота Y"
            refreshes shouldBe 3
        }

        it("cancels a stale restore when the editor closes") {
            val scheduler = TestTaskScheduler()
            val controller = BuildBookEditorFeedbackController(
                refresh = {},
                taskScope = LifecycleTaskScope(scheduler),
            )
            val item = BuildBookEditorPresentation.item(Material.REPEATER, Component.text("Сбросить"), emptyList())
            val rejection = BuildBookEditorPresentation.state(Component.text("Недоступно"), emptyList())
            val replacement = BuildBookEditorPresentation.state(Component.text("Новый экран"), emptyList())

            controller.show(item, rejection)
            controller.close()
            replacement.applyTo(item)
            scheduler.tick(40)

            plain.serialize(checkNotNull(item.itemMeta.displayName())) shouldBe "Новый экран"
            scheduler.pendingCount() shouldBe 0
        }

        it("keeps feedback and restored presentation explicitly nonitalic") {
            val scheduler = TestTaskScheduler()
            val controller = BuildBookEditorFeedbackController(
                refresh = {},
                taskScope = LifecycleTaskScope(scheduler),
            )
            val item = BuildBookEditorPresentation.item(
                Material.BOOK,
                Component.text("Обычное состояние"),
                listOf(Component.text("Обычная строка")),
            )
            val rejection = BuildBookEditorPresentation.state(
                Component.text("Ошибка"),
                listOf(Component.text("Причина")),
            )

            controller.show(item, rejection)
            checkNotNull(item.itemMeta.displayName()).decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
            item.itemMeta.lore().orEmpty().forEach { line ->
                line.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
            }
            scheduler.tick(40)
            checkNotNull(item.itemMeta.displayName()).decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
            item.itemMeta.lore().orEmpty().forEach { line ->
                line.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
            }
        }
    }
})
