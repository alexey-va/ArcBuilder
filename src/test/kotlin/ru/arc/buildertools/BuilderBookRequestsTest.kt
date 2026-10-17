package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.GameMode
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import ru.arc.autobuild.BuildBookData
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import java.util.UUID
import java.util.concurrent.CompletableFuture

class BuilderBookRequestsTest : FunSpec({
    class Fixture {
        val scheduler = TestTaskScheduler()
        val scope = LifecycleTaskScope(scheduler)
        val requests = BuilderBookRequests(scope)
        val player = mockk<Player>()
        val inventory = mockk<PlayerInventory>()
        val item = mockk<ItemStack>()
        val otherItem = mockk<ItemStack>()
        val world = mockk<World>()
        var online = true
        var heldSlot = 0
        var currentItem: ItemStack = item
        var worldId = UUID.randomUUID()
        var mode = GameMode.SURVIVAL
        val accepted = mutableListOf<String>()
        var failed = 0
        init {
            every { player.uniqueId } returns UUID.randomUUID()
            every { player.inventory } returns inventory
            every { player.world } returns world
            every { world.uid } answers { worldId }
            every { player.gameMode } answers { mode }
            every { player.isOnline } answers { online }
            every { inventory.heldItemSlot } answers { heldSlot }
            every { inventory.getItem(0) } answers { currentItem }
            every { item.clone() } returns item
        }
        fun request(): CompletableFuture<String> = CompletableFuture<String>().also { future ->
            requests.run(player, BuilderLocatedBook(0, item, BuildBookData("civs/house", "House")),
                request = { future }, failed = { failed++ }, ready = { accepted += it })
        }
        fun settle(future: CompletableFuture<String>, value: String = "loaded") {
            future.complete(value)
            scheduler.executeImmediate()
        }
    }

    test("retrieval completion always waits for the main-thread handoff") {
        val f = Fixture()
        val pending = f.request()
        pending.complete("loaded")
        f.accepted shouldBe emptyList()
        f.scheduler.executeImmediate()
        f.accepted shouldBe listOf("loaded")
        f.requests.size shouldBe 0
        f.scope.close()
    }

    test("only the newest request can consume a late response") {
        val f = Fixture()
        val first = f.request()
        val second = f.request()
        f.requests.size shouldBe 1
        f.settle(first, "old")
        f.accepted shouldBe emptyList()
        f.settle(second, "new")
        f.accepted shouldBe listOf("new")
        f.requests.size shouldBe 0
        f.scope.close()
    }

    test("book stack slot world mode and disconnect changes reject the response") {
        val mutations: List<(Fixture) -> Unit> = listOf(
            { it.currentItem = it.otherItem }, { it.heldSlot = 1 },
            { it.worldId = UUID.randomUUID() }, { it.mode = GameMode.CREATIVE }, { it.online = false },
        )
        mutations.forEach { mutate ->
            val f = Fixture()
            val pending = f.request()
            mutate(f)
            f.settle(pending)
            f.accepted shouldBe emptyList()
            f.failed shouldBe 1
            f.requests.size shouldBe 0
            f.scope.close()
        }
    }

    test("independent grant refuses a replaced player session and reports shutdown exactly once") {
        io.mockk.mockkStatic(Bukkit::class)
        try {
            val f = Fixture()
            every { Bukkit.getPlayer(f.player.uniqueId) } returns f.player
            fun grant() = CompletableFuture<String>().also { future ->
                f.requests.run(f.player, null, request = { future }, latestOnly = false,
                    failed = { f.failed++ }, ready = { f.accepted += it })
            }
            val replaced = grant()
            every { Bukkit.getPlayer(f.player.uniqueId) } returns mockk<Player>()
            f.settle(replaced)
            f.failed shouldBe 1
            f.accepted shouldBe emptyList()
            val first = grant()
            val second = grant()
            f.requests.close()
            f.failed shouldBe 3
            f.settle(first)
            f.settle(second)
            f.failed shouldBe 3
            f.requests.size shouldBe 0
            f.scope.close()
        } finally { io.mockk.unmockkStatic(Bukkit::class) }
    }

    test("a new preview keeps accepted independent issuance tickets alive") {
        io.mockk.mockkStatic(Bukkit::class)
        try {
            val f = Fixture()
            every { Bukkit.getPlayer(f.player.uniqueId) } returns f.player
            val grant = CompletableFuture<String>()
            f.requests.run(f.player, null, request = { grant }, latestOnly = false,
                failed = { f.failed++ }, ready = { f.accepted += it })
            val preview = f.request()
            f.requests.size shouldBe 2
            f.settle(grant, "grant")
            f.settle(preview, "preview")
            f.accepted shouldBe listOf("grant", "preview")
            f.failed shouldBe 0
            f.requests.size shouldBe 0
            f.scope.close()
        } finally { io.mockk.unmockkStatic(Bukkit::class) }
    }

    test("capture context fence observes book and lifecycle changes before a future completes") {
        val f = Fixture()
        var current: (() -> Boolean)? = null
        val future = CompletableFuture<String>()
        f.requests.run(f.player, BuilderLocatedBook(0, f.item, BuildBookData("civs/house", "House")),
            request = { fence -> current = fence; future }, failed = { f.failed++ }, ready = { f.accepted += it })
        checkNotNull(current).invoke() shouldBe true
        f.heldSlot = 1
        checkNotNull(current).invoke() shouldBe false
        f.heldSlot = 0
        f.requests.invalidate(f.player.uniqueId)
        checkNotNull(current).invoke() shouldBe false
        f.settle(future)
        f.accepted shouldBe emptyList()
        f.scope.close()
    }

    test("cancel and lifecycle shutdown reject pending responses") {
        val f = Fixture()
        val cancelled = f.request()
        f.requests.invalidate(f.player.uniqueId) shouldBe true
        f.settle(cancelled)
        val shutdown = f.request()
        f.requests.close()
        f.scope.close()
        f.settle(shutdown)
        f.accepted shouldBe emptyList()
        f.failed shouldBe 0
        f.requests.size shouldBe 0
    }
})
