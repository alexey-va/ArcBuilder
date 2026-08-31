package ru.arc.buildertools

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import java.nio.file.Files
import java.nio.file.Path

class BuilderToolsReloadPreflightTest : FunSpec({
    fun configRoot(): Path = Files.createTempDirectory("arc-builder-reload-preflight-").also { root ->
        Config(root, "modules/builder-tools.yml")
        Config(root, "modules/auto-build.yml")
        Config(root, "modules/system-build-books.yml")
    }

    test("valid disabled configuration can be preflighted without starting external services") {
        val root = configRoot()

        val candidate = BuilderToolsReloadPreflight.load(root)

        candidate.enabled shouldBe false
        candidate.constructionMaxContainerProbesPerTick shouldBe 512
    }

    test("malformed YAML is rejected before Config can degrade it into default values") {
        val root = configRoot()
        Files.writeString(
            root.resolve("modules/builder-tools.yml"),
            "enabled: true\nallowed-worlds: [world\n",
        )

        shouldThrowAny { BuilderToolsReloadPreflight.load(root) }
    }

    test("semantic limit failure reports the invalid candidate without accepting enabled defaults") {
        val root = configRoot()
        val base = Config(root, "modules/builder-tools.yml").apply {
            setBoolean("enabled", true)
            setStringList("allowed-worlds", listOf("*"))
            setInt("limits.blocks-per-tick", 0)
            saveStrict()
        }

        val failure = shouldThrowAny { BuilderToolsReloadPreflight.load(root) }

        failure.message.orEmpty() shouldContain "blocks-per-tick"
        base.integer("limits.blocks-per-tick") shouldBe 0
    }

    test("malformed auto-build YAML is rejected even while builder tools are disabled") {
        val root = configRoot()
        Files.writeString(root.resolve("modules/auto-build.yml"), "build-book: {editor: [}\n")

        shouldThrowAny { BuilderToolsReloadPreflight.load(root) }
    }

    test("invalid numeric scalar is rejected instead of silently using a default") {
        val root = configRoot()
        val path = root.resolve("modules/builder-tools.yml")
        Files.writeString(
            path,
            Files.readString(path).replace("blocks-per-tick: 16", "blocks-per-tick: nope"),
        )

        val failure = shouldThrowAny { BuilderToolsReloadPreflight.load(root) }

        failure.message.orEmpty() shouldContain "limits.blocks-per-tick"
    }

    test("reload preflight strictly validates cosmetic effect settings") {
        val root = configRoot()
        val path = root.resolve("modules/builder-tools.yml")
        Files.writeString(
            path,
            Files.readString(path).replace("volume: 0.55", "volume: loud"),
        )

        val failure = shouldThrowAny { BuilderToolsReloadPreflight.load(root) }

        failure.message.orEmpty() shouldContain "construction.effects.sounds.volume"
    }

    test("invalid boolean and partial duration syntax are rejected strictly") {
        val root = configRoot()
        val path = root.resolve("modules/builder-tools.yml")
        val original = Files.readString(path)
        Files.writeString(path, original.replace("enabled: false", "enabled: perhaps"))
        shouldThrowAny { BuilderToolsReloadPreflight.load(root) }.message.orEmpty() shouldContain "enabled"

        Files.writeString(path, original.replace("plan-ttl: 30s", "plan-ttl: eventually-30s"))
        shouldThrowAny { BuilderToolsReloadPreflight.load(root) }.message.orEmpty() shouldContain "timers.plan-ttl"
    }

    test("reload publication merge-forward restores new defaults and preserves operator keys idempotently") {
        val root = configRoot()
        val config = Config(root, "modules/builder-tools.yml").apply {
            removeKey("runtime.progress-every-batches")
            setString("operator-owned-note", "keep-me")
            saveStrict()
        }

        BuilderToolsConfig.mergeBundledDefaults(root) shouldBe true
        val merged = Config(root, "modules/builder-tools.yml")
        merged.integer("runtime.progress-every-batches") shouldBe 10
        merged.integer("preview.max-plan-displays") shouldBe 512
        merged.double("preview.block-display-scale") shouldBe 1.0
        merged.integer("construction.effects.interval-blocks") shouldBe 4
        merged.string("operator-owned-note") shouldBe "keep-me"
        BuilderToolsConfig.mergeBundledDefaults(root) shouldBe false
    }

    test("preflight validates a merge-forward candidate without mutating the live YAML") {
        val root = configRoot()
        val live = Config(root, "modules/builder-tools.yml").apply {
            removeKey("locales.ru.reload.success")
            removeKey("runtime.progress-every-batches")
            saveStrict()
        }
        live.stringOrNull("locales.ru.reload.success") shouldBe null
        live.stringOrNull("runtime.progress-every-batches") shouldBe null

        val candidate = BuilderToolsReloadPreflight.load(root)

        candidate.progressEveryBatches shouldBe 10
        candidate.messages().render("reload.success", "ru-RU").toString().isNotBlank() shouldBe true
        val unchanged = Config(root, "modules/builder-tools.yml")
        unchanged.stringOrNull("locales.ru.reload.success") shouldBe null
        unchanged.stringOrNull("runtime.progress-every-batches") shouldBe null
        Files.list(root).use { entries ->
            entries.noneMatch { it.fileName.toString().startsWith(".builder-reload-") } shouldBe true
        }
    }

    test("publication merge-forwards both files and keeps operator values") {
        val root = configRoot()
        ConfigManager.clear()
        Config(root, "modules/builder-tools.yml").apply {
            removeKey("runtime.progress-every-batches")
            setString("operator-owned-note", "keep-me")
            saveStrict()
        }
        Config(root, "modules/auto-build.yml").apply {
            removeKey("build-book.player-copy.max-offset")
            setString("operator-owned-note", "keep-auto")
            saveStrict()
        }

        BuilderToolsConfigPublication.publish(root)

        Config(root, "modules/builder-tools.yml").apply {
            integer("runtime.progress-every-batches") shouldBe 10
            string("operator-owned-note") shouldBe "keep-me"
        }
        Config(root, "modules/auto-build.yml").apply {
            integer("build-book.player-copy.max-offset") shouldBe 16
            string("operator-owned-note") shouldBe "keep-auto"
        }
        ConfigManager.clear()
    }
})
