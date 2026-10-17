package ru.arc.buildertools

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.autobuild.PlayerBuildBookDigestInspection
import ru.arc.autobuild.PlayerBuildBookStore
import ru.arc.autobuild.PlayerBuildBookTemplate
import ru.arc.autobuild.PreparedPlayerBuildBookTemplate
import ru.arc.config.Config
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BuilderDraftPreparationTest : FunSpec({
    test("blocked preparation returns with a book lease and accepts no duplicate or journal write") {
        MockBukkitTestRuntime.open().use { paper ->
            DraftPreparationHarness(paper).use { harness ->
                harness.create()

                harness.storage.requests.size shouldBe 1
                harness.locks.isBookLocked(harness.player.uniqueId) shouldBe true
                harness.lifecycle.hasPending(harness.player.uniqueId) shouldBe true
                harness.records().size shouldBe 0
                shouldThrow<BuilderUserFailure> { harness.create() }.path shouldBe "errors.busy"
                harness.storage.requests.size shouldBe 1

                harness.complete(0)
                harness.main.executeImmediate()
                val record = harness.records().single()
                harness.storage.persisted.size shouldBe 1
                record.phase shouldBe BuilderDraftPhase.READY
                record.sourceRotation shouldBe 270
                record.title shouldBe "My draft"
                record.contentSha256 shouldBe PlayerBuildBookStore.contentSha256(checkNotNull(harness.clipboard))
                harness.player.inventory.itemInMainHand.type shouldBe Material.BOOK
            }
        }
    }

    for (change in listOf("cancel", "quit", "close", "clipboard", "slot", "source", "permission", "mode")) {
        test("$change before draft preparation completes prevents persistence and releases the lease") {
            MockBukkitTestRuntime.open().use { paper ->
                DraftPreparationHarness(paper).use { harness ->
                    harness.create()
                    when (change) {
                        "cancel" -> harness.lifecycle.cancelPreparation(harness.player.uniqueId) shouldBe true
                        "quit" -> harness.lifecycle.onPlayerQuit(harness.player.uniqueId)
                        "close" -> harness.lifecycle.close()
                        "clipboard" -> harness.clipboard = harness.clipboard?.copy()
                        "slot" -> harness.player.inventory.heldItemSlot = 1
                        "source" -> harness.player.inventory.setItemInMainHand(ItemStack(Material.BOOK, 2))
                        "permission" -> harness.permissionAllowed = false
                        "mode" -> harness.player.gameMode = GameMode.CREATIVE
                    }
                    harness.complete(0)
                    harness.main.executeImmediate()

                    harness.storage.persisted.size shouldBe 0
                    harness.records().size shouldBe 0
                    harness.locks.isBookLocked(harness.player.uniqueId) shouldBe false
                    harness.lifecycle.hasPending(harness.player.uniqueId) shouldBe false
                }
            }
        }
    }

    test("a cancelled late preparation cannot release a newer request's book lease") {
        MockBukkitTestRuntime.open().use { paper ->
            DraftPreparationHarness(paper).use { harness ->
                harness.create()
                harness.lifecycle.cancelPreparation(harness.player.uniqueId) shouldBe true
                harness.create()
                harness.complete(0)
                harness.main.executeImmediate()

                harness.storage.persisted.size shouldBe 0
                harness.locks.isBookLocked(harness.player.uniqueId) shouldBe true
                harness.lifecycle.hasPending(harness.player.uniqueId) shouldBe true
                harness.complete(1)
                harness.main.executeImmediate()
                harness.records().size shouldBe 1
                harness.storage.persisted.size shouldBe 1
            }
        }
    }
})

private class DraftPreparationHarness(paper: MockBukkitTestRuntime) : AutoCloseable {
    private val mainThread = Thread.currentThread()
    private val root = Files.createTempDirectory("builder-draft-preparation-")
    private val config = BuilderToolsConfig(Config(root, "modules/builder-tools.yml"))
    private val plugin = paper.createSimplePlugin("DraftPreparation")
    val player = paper.addPlayer("DraftBuilder")
    val main = TestTaskScheduler()
    private val scope = LifecycleTaskScope(main)
    private val executor = Executors.newSingleThreadExecutor()
    val locks = BuilderOperationLocks(plugin)
    private val journal = BuilderDraftJournal(root, 100)
    val storage = DelayedDraftStorage(mainThread)
    var permissionAllowed = true
    var clipboard: BuilderClipboard? = BuilderClipboard(
        blocks = listOf(BuilderClipboardBlock(0, 0, 0, "minecraft:stone")),
        sizeX = 1,
        sizeY = 1,
        sizeZ = 1,
        createdAtMillis = System.currentTimeMillis(),
        expiresAtMillis = System.currentTimeMillis() + 60_000,
        sourceRotation = 270,
    )
    private val host = mockk<BuilderBookLifecycleHost>()
    val lifecycle: BuilderDraftLifecycle

    init {
        player.addAttachment(plugin, "arcbuild.book.create", true)
        player.gameMode = GameMode.SURVIVAL
        player.inventory.setItemInMainHand(ItemStack(Material.BOOK))
        every { host.ensureCopyPermission(any()) } answers {
            Thread.currentThread() shouldBe mainThread
            if (!permissionAllowed) throw BuilderUserFailure("errors.no-permission")
        }
        every { host.ensureOperationalContext(any()) } answers { Thread.currentThread() shouldBe mainThread }
        every { host.currentClipboard(player.uniqueId) } answers {
            Thread.currentThread() shouldBe mainThread
            clipboard
        }
        every { host.send(any(), any(), any()) } answers { Thread.currentThread() shouldBe mainThread }
        lifecycle = BuilderDraftLifecycle(config, config.messages(), scope, executor, locks, host, journal, storage)
        lifecycle.start()
        executor.submit {}.get(5, TimeUnit.SECONDS)
        main.executeImmediate()
        lifecycle.health().ready shouldBe true
    }

    fun create() = lifecycle.createDraft(player, listOf("My", "draft"))

    fun complete(index: Int) {
        val playerId = player.uniqueId
        executor.submit {
            val request = storage.requests[index]
            request.future.complete(
                PreparedPlayerBuildBookTemplate(
                    creatorId = playerId,
                    fileName = PlayerBuildBookStore.fileName(playerId, request.clipboard),
                    contentSha256 = PlayerBuildBookStore.contentSha256(request.clipboard),
                    blockCount = request.clipboard.blocks.size,
                    writeSchematic = { error("In-memory storage does not serialize") },
                ),
            )
        }.get(5, TimeUnit.SECONDS)
    }

    fun records(): List<BuilderDraftRecord> = executor.submit<List<BuilderDraftRecord>> { journal.loadAll() }.get(5, TimeUnit.SECONDS)

    override fun close() {
        lifecycle.close()
        scope.close()
        locks.close()
        executor.shutdown()
        check(executor.awaitTermination(5, TimeUnit.SECONDS))
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}

private class DelayedDraftStorage(private val mainThread: Thread) : BuilderDraftStorage {
    data class Request(val clipboard: BuilderClipboard, val future: CompletableFuture<PreparedPlayerBuildBookTemplate>)
    val requests = mutableListOf<Request>()
    val persisted = mutableListOf<PreparedPlayerBuildBookTemplate>()

    override fun prepareAsync(
        creatorId: UUID,
        source: BuilderClipboard,
        isCurrent: () -> Boolean,
    ): CompletableFuture<PreparedPlayerBuildBookTemplate> {
        Thread.currentThread() shouldBe mainThread
        isCurrent() shouldBe true
        // An accepted computation may finish after cancellation; the lifecycle must fence its result.
        val future = object : CompletableFuture<PreparedPlayerBuildBookTemplate>() {
            override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
        }
        requests += Request(source, future)
        return future
    }

    override fun persist(prepared: PreparedPlayerBuildBookTemplate): PlayerBuildBookTemplate {
        (Thread.currentThread() === mainThread) shouldBe false
        persisted += prepared
        return PlayerBuildBookTemplate(prepared.fileName, prepared.contentSha256, "a".repeat(64), prepared.blockCount)
    }

    override fun inspectSchematic(buildingId: String): PlayerBuildBookDigestInspection = error("Not part of preparation")
    override fun inspectContent(buildingId: String, expectedSha256: String): CompletableFuture<PlayerBuildBookDigestInspection> =
        error("Not part of preparation")
    override fun register(template: PlayerBuildBookTemplate) = Unit
}
