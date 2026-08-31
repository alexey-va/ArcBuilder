package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID

class BuilderConstructionPlayerLeasesTest : FunSpec({
    test("same project and player acquire idempotently without a second lock") {
        val locks = mockk<BuilderOperationLocks>(relaxed = true)
        val projectId = UUID.randomUUID()
        val playerId = UUID.randomUUID()
        every { locks.tryBookLock(playerId) } returns true
        every { locks.unlockBook(playerId) } returns Unit

        BuilderConstructionPlayerLeases(locks).use { leases ->
            leases.acquire(projectId, playerId) shouldBe true
            leases.acquire(projectId, playerId) shouldBe true

            verify(exactly = 1) { locks.tryBookLock(playerId) }

            leases.release(projectId)
            verify(exactly = 1) { locks.unlockBook(playerId) }
        }
    }

    test("one construction lease rejects a second project and a conflicting project identity") {
        val locks = mockk<BuilderOperationLocks>(relaxed = true)
        val firstProjectId = UUID.randomUUID()
        val secondProjectId = UUID.randomUUID()
        val playerId = UUID.randomUUID()
        val anotherPlayerId = UUID.randomUUID()
        every { locks.tryBookLock(playerId) } returns true
        every { locks.unlockBook(playerId) } returns Unit

        BuilderConstructionPlayerLeases(locks).use { leases ->
            leases.acquire(firstProjectId, playerId) shouldBe true
            leases.acquire(secondProjectId, playerId) shouldBe false
            leases.acquire(firstProjectId, anotherPlayerId) shouldBe false

            verify(exactly = 1) { locks.tryBookLock(playerId) }
            verify(exactly = 0) { locks.tryBookLock(anotherPlayerId) }
        }
    }

    test("ordinary operation rejection does not retain a construction lease") {
        val locks = mockk<BuilderOperationLocks>(relaxed = true)
        val projectId = UUID.randomUUID()
        val playerId = UUID.randomUUID()
        every { locks.tryBookLock(playerId) } returns false

        BuilderConstructionPlayerLeases(locks).use { leases ->
            leases.acquire(projectId, playerId) shouldBe false
            leases.release(projectId)

            verify(exactly = 1) { locks.tryBookLock(playerId) }
            verify(exactly = 0) { locks.unlockBook(playerId) }
        }
    }

    test("releasing another project cannot unlock the current construction player lease") {
        val locks = mockk<BuilderOperationLocks>(relaxed = true)
        val projectId = UUID.randomUUID()
        val otherProjectId = UUID.randomUUID()
        val playerId = UUID.randomUUID()
        every { locks.tryBookLock(playerId) } returns true
        every { locks.unlockBook(playerId) } returns Unit

        BuilderConstructionPlayerLeases(locks).use { leases ->
            leases.acquire(projectId, playerId) shouldBe true
            leases.release(otherProjectId)

            verify(exactly = 0) { locks.unlockBook(playerId) }
            verify(exactly = 0) { locks.unlockResources(projectId) }
            verify(exactly = 0) { locks.unlockResources(otherProjectId) }
            leases.acquire(otherProjectId, playerId) shouldBe false

            leases.release(projectId)
            verify(exactly = 1) { locks.unlockBook(playerId) }
            verify(exactly = 1) { locks.unlockResources(projectId) }
        }
    }

    test("releaseAll releases every remaining player exactly once") {
        val locks = mockk<BuilderOperationLocks>(relaxed = true)
        val firstProjectId = UUID.randomUUID()
        val secondProjectId = UUID.randomUUID()
        val firstPlayerId = UUID.randomUUID()
        val secondPlayerId = UUID.randomUUID()
        every { locks.tryBookLock(firstPlayerId) } returns true
        every { locks.tryBookLock(secondPlayerId) } returns true
        every { locks.unlockBook(firstPlayerId) } returns Unit
        every { locks.unlockBook(secondPlayerId) } returns Unit
        val leases = BuilderConstructionPlayerLeases(locks)

        leases.acquire(firstProjectId, firstPlayerId) shouldBe true
        leases.acquire(secondProjectId, secondPlayerId) shouldBe true
        leases.release(firstProjectId)
        leases.release(firstProjectId)
        leases.releaseAll()
        leases.releaseAll()
        leases.close()

        verify(exactly = 1) { locks.unlockBook(firstPlayerId) }
        verify(exactly = 1) { locks.unlockBook(secondPlayerId) }
    }

    test("close releases held leases once and rejects future acquisition") {
        val locks = mockk<BuilderOperationLocks>(relaxed = true)
        val projectId = UUID.randomUUID()
        val playerId = UUID.randomUUID()
        every { locks.tryBookLock(playerId) } returns true
        every { locks.unlockBook(playerId) } returns Unit
        val leases = BuilderConstructionPlayerLeases(locks)

        leases.acquire(projectId, playerId) shouldBe true
        leases.close()
        leases.close()

        verify(exactly = 1) { locks.unlockBook(playerId) }
        leases.acquire(UUID.randomUUID(), UUID.randomUUID()) shouldBe false
    }
})
