package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import ru.arc.config.Config
import java.nio.file.Files

class BuilderToolsReloadServiceTest : FunSpec({
    test("durable construction reload waits only for an in-flight write or completion") {
        builderConstructionReloadBlocked(writesInFlight = 0, completionsInFlight = 0) shouldBe false
        builderConstructionReloadBlocked(writesInFlight = 1, completionsInFlight = 0) shouldBe true
        builderConstructionReloadBlocked(writesInFlight = 0, completionsInFlight = 1) shouldBe true
    }

    fun config(label: String): BuilderToolsConfig = BuilderToolsConfig(
        Config(Files.createTempDirectory("arc-builder-reload-$label-"), "modules/builder-tools.yml"),
    )

    test("candidate validation failure leaves the active runtime completely untouched") {
        val previous = config("invalid-previous")
        val active = FakeReloadRuntime()
        var factoryCalls = 0
        val service = BuilderToolsReloadService(
            initialConfig = previous,
            initialRuntime = active,
            loadCandidate = { error("invalid limits.blocks-per-tick") },
            createRuntime = { factoryCalls += 1; FakeReloadRuntime() },
            reloadBlocker = FakeReloadRuntime::blocker,
            publishConfig = {},
        )

        val result = service.reload().shouldBeInstanceOf<BuilderToolsReloadResult.Rejected>()

        result.failure.message shouldBe "invalid limits.blocks-per-tick"
        service.runtime shouldBe active
        service.config shouldBe previous
        active.closeCalls shouldBe 0
        factoryCalls shouldBe 0
    }

    test("unsafe active generation rejects reload without closing or replacing it") {
        val previous = config("busy-previous")
        val candidate = config("busy-candidate")
        val active = FakeReloadRuntime(BuilderToolsReloadBlocker.ACTIVE_OPERATION)
        var factoryCalls = 0
        var candidateLoads = 0
        val service = BuilderToolsReloadService(
            initialConfig = previous,
            initialRuntime = active,
            loadCandidate = { candidateLoads += 1; candidate },
            createRuntime = { factoryCalls += 1; FakeReloadRuntime() },
            reloadBlocker = FakeReloadRuntime::blocker,
            publishConfig = {},
        )

        service.reload() shouldBe BuilderToolsReloadResult.Busy(BuilderToolsReloadBlocker.ACTIVE_OPERATION)
        service.runtime shouldBe active
        service.config shouldBe previous
        active.closeCalls shouldBe 0
        factoryCalls shouldBe 0
        candidateLoads shouldBe 0
    }

    test("successful reload closes the previous generation and publishes exactly one replacement") {
        val previous = config("success-previous")
        val candidate = config("success-candidate")
        val active = FakeReloadRuntime()
        val replacement = FakeReloadRuntime()
        val published = mutableListOf<BuilderToolsConfig>()
        val service = BuilderToolsReloadService(
            initialConfig = previous,
            initialRuntime = active,
            loadCandidate = { candidate },
            createRuntime = { configured -> replacement.also { configured shouldBe candidate } },
            reloadBlocker = FakeReloadRuntime::blocker,
            publishConfig = published::add,
        )

        service.reload() shouldBe BuilderToolsReloadResult.Applied(candidate)
        active.closeCalls shouldBe 1
        replacement.closeCalls shouldBe 0
        service.runtime shouldBe replacement
        service.config shouldBe candidate
        published shouldBe listOf(candidate)
    }

    test("candidate activation failure reconstructs the previous generation") {
        val previous = config("activation-previous")
        val candidate = config("activation-candidate")
        val active = FakeReloadRuntime()
        val rollback = FakeReloadRuntime()
        val service = BuilderToolsReloadService(
            initialConfig = previous,
            initialRuntime = active,
            loadCandidate = { candidate },
            createRuntime = { configured ->
                if (configured === candidate) error("candidate startup failed")
                rollback
            },
            reloadBlocker = FakeReloadRuntime::blocker,
            publishConfig = {},
        )

        val result = service.reload().shouldBeInstanceOf<BuilderToolsReloadResult.Rejected>()

        result.failure.message shouldBe "candidate startup failed"
        result.rollbackFailure shouldBe null
        active.closeCalls shouldBe 1
        service.runtime shouldBe rollback
        service.config shouldBe previous
    }

    test("publication failure closes the candidate before restoring the previous generation") {
        val previous = config("publish-previous")
        val candidate = config("publish-candidate")
        val active = FakeReloadRuntime()
        val replacement = FakeReloadRuntime()
        val rollback = FakeReloadRuntime()
        val service = BuilderToolsReloadService(
            initialConfig = previous,
            initialRuntime = active,
            loadCandidate = { candidate },
            createRuntime = { configured -> if (configured === candidate) replacement else rollback },
            reloadBlocker = FakeReloadRuntime::blocker,
            publishConfig = { error("publish failed") },
        )

        val result = service.reload().shouldBeInstanceOf<BuilderToolsReloadResult.Rejected>()

        result.failure.message shouldBe "publish failed"
        replacement.closeCalls shouldBe 1
        service.runtime shouldBe rollback
        service.config shouldBe previous
    }

    test("candidate and rollback activation failures are both surfaced and leave no pretend runtime") {
        val previous = config("double-failure-previous")
        val candidate = config("double-failure-candidate")
        val active = FakeReloadRuntime()
        val service = BuilderToolsReloadService(
            initialConfig = previous,
            initialRuntime = active,
            loadCandidate = { candidate },
            createRuntime = { configured ->
                if (configured === candidate) error("candidate failed") else error("rollback failed")
            },
            reloadBlocker = FakeReloadRuntime::blocker,
            publishConfig = {},
        )

        val result = service.reload().shouldBeInstanceOf<BuilderToolsReloadResult.Rejected>()

        result.failure.message shouldBe "candidate failed"
        result.rollbackFailure?.message shouldBe "rollback failed"
        service.runtime shouldBe null
        service.config shouldBe previous
        active.closeCalls shouldBe 1
    }

    test("publication rollback failure is surfaced even when runtime reconstruction succeeds") {
        val previous = config("publication-rollback-previous")
        val candidate = config("publication-rollback-candidate")
        val active = FakeReloadRuntime()
        val replacement = FakeReloadRuntime()
        val rollback = FakeReloadRuntime()
        val diskRollbackFailure = IllegalStateException("disk restore failed")
        val service = BuilderToolsReloadService(
            initialConfig = previous,
            initialRuntime = active,
            loadCandidate = { candidate },
            createRuntime = { configured -> if (configured === candidate) replacement else rollback },
            reloadBlocker = FakeReloadRuntime::blocker,
            publishConfig = {
                throw BuilderToolsConfigPublicationException(
                    cause = IllegalStateException("second file merge failed"),
                    rollbackFailure = diskRollbackFailure,
                )
            },
        )

        val result = service.reload().shouldBeInstanceOf<BuilderToolsReloadResult.Rejected>()

        result.rollbackFailure shouldBe diskRollbackFailure
        result.failure.message shouldBe "Builder-tools configuration publication failed"
        replacement.closeCalls shouldBe 1
        service.runtime shouldBe rollback
        service.config shouldBe previous
    }
})

private class FakeReloadRuntime(
    val blocker: BuilderToolsReloadBlocker? = null,
) : AutoCloseable {
    var closeCalls: Int = 0
        private set

    override fun close() {
        closeCalls += 1
    }
}
