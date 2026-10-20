package ru.arc.hooks.zauction

import fr.maxlego08.zauctionhouse.api.event.events.AuctionExpireEvent
import fr.maxlego08.zauctionhouse.api.item.Item
import fr.maxlego08.zauctionhouse.api.item.ItemStatus
import fr.maxlego08.zauctionhouse.api.item.StorageType
import fr.maxlego08.zauctionhouse.api.item.items.AuctionItem
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Bukkit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.arc.TestBase
import ru.arc.autobuild.BuildBookData
import ru.arc.autobuild.BuildBookItems
import ru.arc.buildertools.BuilderBookAuctionToken
import ru.arc.buildertools.BuilderBookAuctionTokenCodec
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import java.util.UUID
import java.util.concurrent.CompletableFuture

class BuilderAuctionHookTest : TestBase() {
    private val token = BuilderBookAuctionToken(UUID.randomUUID(), UUID.randomUUID())

    @Test
    fun `expiry intent waits for exact durable deleted evidence and dispatches on main`() {
        val query = CompletableFuture<List<Item>>()
        val calls = mutableListOf<List<Int>>()
        withHook({ ids -> calls += ids; query }) { hook, tasks, closed ->
            hook.onExpired(AuctionExpireEvent(listOf(lot(11, token, ItemStatus.REMOVED)), StorageType.EXPIRED))
            assertTrue(calls.isEmpty())
            assertTrue(closed.isEmpty())

            tasks.tick(10)
            assertEquals(listOf(listOf(11)), calls)
            assertTrue(closed.isEmpty())
            val persisted = lot(11, token, ItemStatus.DELETED)
            CompletableFuture.runAsync { query.complete(listOf(persisted)) }.join()
            assertTrue(closed.isEmpty())

            tasks.tick(10)
            assertEquals(listOf(token), closed)
        }
    }

    @Test
    fun `listed expiry is not terminal storage closure`() {
        var calls = 0
        withHook({ calls += 1; CompletableFuture.completedFuture(emptyList()) }) { hook, tasks, closed ->
            hook.onExpired(AuctionExpireEvent(listOf(lot(11, token, ItemStatus.REMOVED)), StorageType.LISTED))
            retryWindow(tasks)
            assertEquals(0, calls)
            assertTrue(closed.isEmpty())
        }
    }

    @Test
    fun `wrong listing token or nonterminal status cannot close the lease`() {
        val results = listOf(
            lot(12, token, ItemStatus.DELETED),
            lot(11, token.copy(leaseId = UUID.randomUUID()), ItemStatus.DELETED),
            lot(11, token, ItemStatus.PURCHASED),
        )
        results.forEach { result ->
            var calls = 0
            withHook({ calls += 1; CompletableFuture.completedFuture(listOf(result)) }) { hook, tasks, closed ->
                hook.onExpired(AuctionExpireEvent(listOf(lot(11, token, ItemStatus.PURCHASED)), StorageType.PURCHASED))
                retryWindow(tasks)
                assertEquals(3, calls)
                assertTrue(closed.isEmpty())
            }
        }
    }

    @Test
    fun `empty durable read exhausts finite retries without assuming absence`() {
        var calls = 0
        withHook({ calls += 1; CompletableFuture.completedFuture(emptyList()) }) { hook, tasks, closed ->
            hook.onExpired(AuctionExpireEvent(listOf(lot(11, token, ItemStatus.REMOVED)), StorageType.EXPIRED))
            retryWindow(tasks)
            assertEquals(3, calls)
            assertTrue(closed.isEmpty())
        }
    }

    @Test
    fun `failed durable read exhausts finite retries without closing the lease`() {
        var calls = 0
        withHook({
            calls += 1
            CompletableFuture.failedFuture(IllegalStateException("fixture database unavailable"))
        }) { hook, tasks, closed ->
            hook.onExpired(AuctionExpireEvent(listOf(lot(11, token, ItemStatus.REMOVED)), StorageType.EXPIRED))
            retryWindow(tasks)
            assertEquals(3, calls)
            assertTrue(closed.isEmpty())
        }
    }

    @Test
    fun `bulk expiry retains every token while querying at most 32 listing ids`() {
        val tokens = (1..33).associateWith { BuilderBookAuctionToken(UUID.randomUUID(), UUID.randomUUID()) }
        val lots = tokens.mapValues { (id, token) -> lot(id, token, ItemStatus.DELETED) }
        val calls = mutableListOf<List<Int>>()
        withHook({ ids ->
            calls += ids
            CompletableFuture.completedFuture(ids.map(lots::getValue))
        }) { hook, tasks, closed ->
            hook.onExpired(AuctionExpireEvent(lots.values.toList(), StorageType.EXPIRED))
            retryWindow(tasks)
            assertEquals(listOf(32, 1), calls.map(List<Int>::size))
            assertEquals(tokens.values.toSet(), closed.toSet())
            assertEquals(33, closed.size)
        }
    }

    @Test
    fun `closing the hook drops an accepted read completion`() {
        val query = CompletableFuture<List<Item>>()
        withHook({ query }) { hook, tasks, closed ->
            hook.onExpired(AuctionExpireEvent(listOf(lot(11, token, ItemStatus.REMOVED)), StorageType.EXPIRED))
            tasks.tick(10)
            hook.close()
            query.complete(listOf(lot(11, token, ItemStatus.DELETED)))
            tasks.executeImmediate()
            assertTrue(closed.isEmpty())
        }
    }

    @Test
    fun `replacement handler does not receive an old read completion`() {
        val query = CompletableFuture<List<Item>>()
        withHook({ query }) { hook, tasks, closed ->
            hook.onExpired(AuctionExpireEvent(listOf(lot(11, token, ItemStatus.REMOVED)), StorageType.EXPIRED))
            tasks.tick(10)
            val replacement = mutableListOf<BuilderBookAuctionToken>()
            hook.setClosureHandler { replacement += it }
            query.complete(listOf(lot(11, token, ItemStatus.DELETED)))
            tasks.executeImmediate()
            assertTrue(closed.isEmpty())
            assertTrue(replacement.isEmpty())
        }
    }

    @Test
    fun `missing native manager is unavailable rather than an absent token`() {
        withHook({ CompletableFuture.completedFuture(emptyList()) }) { hook, _, _ ->
            assertThrows(IllegalStateException::class.java) { hook.contains(token) }
        }
    }

    private fun withHook(
        lookup: (List<Int>) -> CompletableFuture<List<Item>>,
        action: (BuilderAuctionHook, TestTaskScheduler, MutableList<BuilderBookAuctionToken>) -> Unit,
    ) {
        val tasks = TestTaskScheduler()
        Tasks.withScheduler(tasks) {
            BuilderAuctionHook(plugin, lookup).use { hook ->
                val closed = mutableListOf<BuilderBookAuctionToken>()
                hook.setClosureHandler {
                    assertTrue(Bukkit.isPrimaryThread())
                    closed += it
                }
                action(hook, tasks, closed)
            }
        }
    }

    private fun retryWindow(tasks: TestTaskScheduler) {
        tasks.tick(200)
        tasks.executeImmediate()
    }

    private fun lot(id: Int, token: BuilderBookAuctionToken, status: ItemStatus): AuctionItem {
        val data = BuildBookData(
            buildingId = "auction-test.schem",
            title = "Дом",
            playerCreated = true,
            creatorId = UUID.randomUUID(),
            creatorName = "BookSeller",
            blueprintId = UUID.randomUUID(),
            instanceId = token.instanceId,
            instanceGeneration = 1,
            issuePriceMinor = 0L,
            contentSha256 = "a".repeat(64),
            schematicSha256 = "b".repeat(64),
        ).validated()
        val stack = BuilderBookAuctionTokenCodec.attach(BuildBookItems.create(data), token)
        val item = mockk<AuctionItem>()
        every { item.id } returns id
        every { item.status } answers { assertTrue(Bukkit.isPrimaryThread()); status }
        every { item.itemStacks } answers { assertTrue(Bukkit.isPrimaryThread()); listOf(stack) }
        return item
    }
}
