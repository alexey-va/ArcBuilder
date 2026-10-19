package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class BuilderToolsReloadServiceTest : FunSpec({
    test("appearance reload preserves a busy runtime and mixed domain changes leave it entirely untouched") {
        val active = FakeReloadRuntime(blocker = BuilderToolsReloadBlocker.PENDING_PREVIEW)
        val events = mutableListOf<String>()
        var candidate = "appearance"
        val service = BuilderToolsReloadService(
            initialConfig = "previous", initialRuntime = active,
            loadCandidate = { candidate }, createRuntime = { error("must retain existing runtime") },
            reloadBlocker = FakeReloadRuntime::blocker,
            publishConfig = { events += "publish-$it"; {} },
            canRefreshInPlace = { _, next -> next == "appearance" },
            refreshInPlace = { runtime, next -> runtime shouldBe active; events += "refresh-$next" },
        )
        service.reload() shouldBe BuilderToolsReloadResult.Applied("appearance", inPlace = true)
        active.closeCalls shouldBe 0
        service.runtime shouldBe active
        events shouldBe listOf("publish-appearance", "refresh-appearance")
        candidate = "appearance-and-domain"
        service.reload() shouldBe BuilderToolsReloadResult.Busy(BuilderToolsReloadBlocker.PENDING_PREVIEW)
        service.config shouldBe "appearance"
        service.runtime shouldBe active
        events shouldBe listOf("publish-appearance", "refresh-appearance")
    }

    test("failed in-place refresh restores files and old visual settings without closing player state") {
        val active = FakeReloadRuntime(blocker = BuilderToolsReloadBlocker.VOLATILE_PLAYER_STATE)
        val events = mutableListOf<String>()
        val service = BuilderToolsReloadService(
            initialConfig = "previous", initialRuntime = active,
            loadCandidate = { "candidate" }, createRuntime = { error("must not recreate") },
            reloadBlocker = FakeReloadRuntime::blocker,
            publishConfig = { { events += "restore-files"; Unit } },
            canRefreshInPlace = { _, _ -> true },
            refreshInPlace = { _, next -> events += next; if (next == "candidate") error("visual failure") },
        )
        service.reload().shouldBeInstanceOf<BuilderToolsReloadResult.Rejected>().rollbackFailure shouldBe null
        events shouldBe listOf("candidate", "restore-files", "previous")
        service.config shouldBe "previous"
        service.runtime shouldBe active
        active.closeCalls shouldBe 0
    }

    test("durable construction reload refuses only in-flight work") {
        builderConstructionReloadBlocked(0, 0) shouldBe false
        builderConstructionReloadBlocked(1, 0) shouldBe true
        builderConstructionReloadBlocked(0, 1) shouldBe true
    }

    test("invalid candidate leaves the active runtime untouched") {
        val active = FakeReloadRuntime()
        val service = reloadService(active, load = { error("invalid limits") })

        service.reload().shouldBeInstanceOf<BuilderToolsReloadResult.Rejected>().failure.message shouldBe "invalid limits"
        service.runtime shouldBe active
        service.config shouldBe "previous"
        active.closeCalls shouldBe 0
    }

    test("busy runtime refuses reload before loading files") {
        val active = FakeReloadRuntime(blocker = BuilderToolsReloadBlocker.ACTIVE_OPERATION)
        val service = reloadService(active, load = { error("must not load") })

        service.reload() shouldBe BuilderToolsReloadResult.Busy(BuilderToolsReloadBlocker.ACTIVE_OPERATION)
        active.closeCalls shouldBe 0
    }

    test("maintenance validates closes publishes and activates sequentially on the caller") {
        val caller = Thread.currentThread()
        val events = mutableListOf<String>()
        fun step(event: String) { Thread.currentThread() shouldBe caller; events += event }
        val active = FakeReloadRuntime(onClose = { step("close") })
        val replacement = FakeReloadRuntime()
        val service = reloadService(
            active,
            load = { step("load"); "candidate" },
            create = { step("activate"); replacement },
            publish = { step("publish"); {} },
            validate = { step("validate") },
        )

        service.reload() shouldBe BuilderToolsReloadResult.Applied("candidate")
        events shouldBe listOf("load", "validate", "close", "publish", "activate")
        service.runtime shouldBe replacement
        service.config shouldBe "candidate"
    }

    test("failed old close prevents publication and reopening the journals") {
        val failure = IllegalStateException("storage close failed")
        val service = reloadService(
            FakeReloadRuntime(onClose = { throw failure }),
            create = { error("must not activate") },
            publish = { error("must not publish") },
        )

        service.reload().shouldBeInstanceOf<BuilderToolsReloadResult.Rejected>().rollbackFailure shouldBe failure
        service.runtime shouldBe null
    }

    test("candidate activation failure restores files before the previous runtime") {
        val events = mutableListOf<String>()
        val restored = FakeReloadRuntime()
        val service = reloadService(
            create = { configured ->
                events += "activate-$configured"
                if (configured == "candidate") error("candidate failed")
                restored
            },
            publish = { events += "publish"; { events += "restore-files"; Unit } },
        )

        val result = service.reload().shouldBeInstanceOf<BuilderToolsReloadResult.Rejected>()
        result.failure.message shouldBe "candidate failed"
        result.rollbackFailure shouldBe null
        events shouldBe listOf("publish", "activate-candidate", "restore-files", "activate-previous")
        service.runtime shouldBe restored
        service.config shouldBe "previous"
    }

    test("publication failure restores the previous runtime without activating the candidate") {
        val activated = mutableListOf<String>()
        val restored = FakeReloadRuntime()
        val service = reloadService(
            create = { activated += it; restored },
            publish = { error("publish failed") },
        )

        service.reload().shouldBeInstanceOf<BuilderToolsReloadResult.Rejected>().failure.message shouldBe "publish failed"
        activated shouldBe listOf("previous")
        service.runtime shouldBe restored
    }

    test("failed candidate cleanup restores files but never reopens its journals") {
        val cleanup = IllegalStateException("SQL close failed")
        val events = mutableListOf<String>()
        val service = reloadService(
            create = { configured ->
                events += configured
                throw BuilderToolsRuntimeActivationException(IllegalStateException("activation failed"), cleanup)
            },
            publish = { { events += "restore-files"; Unit } },
        )

        service.reload().shouldBeInstanceOf<BuilderToolsReloadResult.Rejected>().rollbackFailure shouldBe cleanup
        events shouldBe listOf("candidate", "restore-files")
        service.runtime shouldBe null
    }

    test("failed file rollback does not reconstruct a runtime") {
        val failure = IllegalStateException("restore failed")
        val service = reloadService(
            create = { error("must not activate") },
            publish = { throw BuilderToolsConfigPublicationException(IllegalStateException("merge failed"), failure) },
        )

        service.reload().shouldBeInstanceOf<BuilderToolsReloadResult.Rejected>().rollbackFailure shouldBe failure
        service.runtime shouldBe null
    }

    test("failed previous runtime reconstruction reports failed closed") {
        val service = reloadService(create = { error("$it failed") })

        val result = service.reload().shouldBeInstanceOf<BuilderToolsReloadResult.Rejected>()
        result.failure.message shouldBe "candidate failed"
        result.rollbackFailure?.message shouldBe "previous failed"
        service.runtime shouldBe null
    }
})

private fun reloadService(
    active: FakeReloadRuntime = FakeReloadRuntime(),
    load: () -> String = { "candidate" },
    create: (String) -> FakeReloadRuntime = { FakeReloadRuntime() },
    publish: (String) -> (() -> Unit) = { {} },
    validate: (String) -> Unit = {},
) = BuilderToolsReloadService(
    initialConfig = "previous",
    initialRuntime = active,
    loadCandidate = load,
    createRuntime = create,
    reloadBlocker = FakeReloadRuntime::blocker,
    publishConfig = publish,
    validateCandidate = validate,
)

private class FakeReloadRuntime(
    val blocker: BuilderToolsReloadBlocker? = null,
    val onClose: () -> Unit = {},
) : AutoCloseable {
    var closeCalls = 0
        private set
    override fun close() { closeCalls++; onClose() }
}
