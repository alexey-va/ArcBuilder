package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.arc.autobuild.BuildBookData
import java.util.UUID
import java.util.concurrent.CompletableFuture

class BuilderBookStatusVerifierTest : FunSpec({
    val playerId = UUID.fromString("10000000-0000-0000-0000-000000000001")
    val blueprintId = UUID.fromString("20000000-0000-0000-0000-000000000002")
    val instanceId = UUID.fromString("30000000-0000-0000-0000-000000000003")
    val identity = BuilderBookPresentedIdentity(blueprintId, instanceId, 4)

    fun blueprint(id: UUID = blueprintId) = BuilderBookBlueprint(
        blueprintId = id,
        creatorId = playerId,
        creatorName = "Builder",
        title = "Дом",
        buildingId = "builder-home.schem",
        contentSha256 = "a".repeat(64),
        schematicSha256 = "b".repeat(64),
        blockCount = 10,
        materialTypes = 1,
        materialItems = 10,
        materialCostMinor = 1_000L,
        constructionFeeMinor = 150L,
        issuePriceMinor = 1_150L,
        createdAtMillis = 1L,
    )

    fun instance(
        ownerId: UUID = playerId,
        generation: Int = identity.generation,
        blueprint: UUID = blueprintId,
        status: BuilderBookInstanceStatus = BuilderBookInstanceStatus.AVAILABLE,
    ) = BuilderBookInstance(
        instanceId = instanceId,
        blueprintId = blueprint,
        transactionId = UUID.fromString("40000000-0000-0000-0000-000000000004"),
        mintedBy = playerId,
        deliveryPlayerId = playerId,
        ownerId = ownerId,
        generation = generation,
        status = status,
        createdAtMillis = 1L,
    )

    fun physicalBook(deliveryPending: Boolean = false) = BuildBookData(
        buildingId = "builder-home.schem",
        title = "Дом",
        playerCreated = true,
        creatorId = playerId,
        creatorName = "Builder",
        blueprintId = blueprintId,
        instanceId = instanceId,
        instanceGeneration = identity.generation,
        issuePriceMinor = 1_150L,
        contentSha256 = "a".repeat(64),
        schematicSha256 = "b".repeat(64),
        deliveryPending = deliveryPending,
        blockCount = 10,
    )

    test("presented identity accepts only a locally active registered book") {
        BuilderBookPresentedIdentity.from(physicalBook()) shouldBe identity
        BuilderBookPresentedIdentity.from(physicalBook(deliveryPending = true)) shouldBe null
        BuilderBookPresentedIdentity.from(physicalBook().copy(instanceId = null, instanceGeneration = null)) shouldBe null
        BuilderBookPresentedIdentity.from(null) shouldBe null
    }

    test("status lookup accepts only the exact available authoritative owner and generation") {
        val loaded = CompletableFuture<BuilderBookInstance?>()
        val authoritativeBlueprint = blueprint()
        val outcomes = mutableListOf<BuilderBookStatusVerification>()
        val verifier = BuilderBookStatusVerifier(
            loadInstance = { loaded },
            loadBlueprint = { CompletableFuture.completedFuture(authoritativeBlueprint) },
            runSync = { it() },
        )

        verifier.verify(playerId, identity, { identity }, outcomes::add) shouldBe
            BuilderBookStatusLookupStart.STARTED
        loaded.complete(instance())

        outcomes.shouldContainExactly(BuilderBookStatusVerification.Active(authoritativeBlueprint))
    }

    test("status lookup preserves higher-priority quote and auction guidance") {
        BuilderBookStatusLookupPolicy.shouldVerify(false, identity, false) shouldBe true
        BuilderBookStatusLookupPolicy.shouldVerify(true, identity, false) shouldBe false
        BuilderBookStatusLookupPolicy.shouldVerify(false, identity, true) shouldBe false
        BuilderBookStatusLookupPolicy.shouldVerify(false, null, false) shouldBe false
    }

    test("status lookup rejects missing mismatched stale and non-available rows") {
        val authoritative = listOf(
            null,
            instance(ownerId = UUID.fromString("50000000-0000-0000-0000-000000000005")),
            instance(generation = identity.generation + 1),
            instance(blueprint = UUID.fromString("60000000-0000-0000-0000-000000000006")),
            instance(status = BuilderBookInstanceStatus.REVOKED),
        )

        authoritative.forEach { row ->
            val outcomes = mutableListOf<BuilderBookStatusVerification>()
            BuilderBookStatusVerifier(
                loadInstance = { CompletableFuture.completedFuture(row) },
                loadBlueprint = { CompletableFuture.completedFuture(blueprint()) },
                runSync = { it() },
            ).verify(playerId, identity, { identity }, outcomes::add) shouldBe BuilderBookStatusLookupStart.STARTED

            outcomes.shouldContainExactly(BuilderBookStatusVerification.Stale)
        }
    }

    test("status lookup rejects a missing or mismatched authoritative blueprint") {
        listOf(
            null,
            blueprint(UUID.fromString("70000000-0000-0000-0000-000000000007")),
        ).forEach { authoritativeBlueprint ->
            val outcomes = mutableListOf<BuilderBookStatusVerification>()
            BuilderBookStatusVerifier(
                loadInstance = { CompletableFuture.completedFuture(instance()) },
                loadBlueprint = { CompletableFuture.completedFuture(authoritativeBlueprint) },
                runSync = { it() },
            ).verify(playerId, identity, { identity }, outcomes::add)

            outcomes.shouldContainExactly(BuilderBookStatusVerification.Stale)
        }
    }

    test("status lookup coalesces spam and notices a changed held book") {
        val loaded = CompletableFuture<BuilderBookInstance?>()
        var loads = 0
        var current: BuilderBookPresentedIdentity? = identity
        val outcomes = mutableListOf<BuilderBookStatusVerification>()
        val verifier = BuilderBookStatusVerifier(
            loadInstance = {
                loads++
                loaded
            },
            loadBlueprint = { CompletableFuture.completedFuture(blueprint()) },
            runSync = { it() },
        )

        verifier.verify(playerId, identity, { current }, outcomes::add) shouldBe BuilderBookStatusLookupStart.STARTED
        verifier.verify(playerId, identity, { current }, outcomes::add) shouldBe
            BuilderBookStatusLookupStart.ALREADY_PENDING
        loads shouldBe 1

        current = identity.copy(generation = identity.generation + 1)
        loaded.complete(instance())
        outcomes.shouldContainExactly(BuilderBookStatusVerification.SourceChanged)
    }

    test("status lookup reports instance or blueprint failure without calling the item stale") {
        listOf(false, true).forEach { failBlueprint ->
            val outcomes = mutableListOf<BuilderBookStatusVerification>()
            val failed = CompletableFuture<BuilderBookInstance?>()
            val failedBlueprint = CompletableFuture<BuilderBookBlueprint?>()
            val verifier = BuilderBookStatusVerifier(
                loadInstance = {
                    if (failBlueprint) CompletableFuture.completedFuture(instance()) else failed
                },
                loadBlueprint = {
                    if (failBlueprint) failedBlueprint else CompletableFuture.completedFuture(blueprint())
                },
                runSync = { it() },
            )

            verifier.verify(playerId, identity, { identity }, outcomes::add) shouldBe BuilderBookStatusLookupStart.STARTED
            if (failBlueprint) {
                failedBlueprint.completeExceptionally(IllegalStateException("blueprint unavailable"))
            } else {
                failed.completeExceptionally(IllegalStateException("instance unavailable"))
            }

            outcomes.shouldContainExactly(BuilderBookStatusVerification.RegistryUnavailable)
        }
    }

    test("closed status lookup ignores late database completion") {
        val loaded = CompletableFuture<BuilderBookInstance?>()
        val outcomes = mutableListOf<BuilderBookStatusVerification>()
        val verifier = BuilderBookStatusVerifier(
            loadInstance = { loaded },
            loadBlueprint = { CompletableFuture.completedFuture(blueprint()) },
            runSync = { it() },
        )

        verifier.verify(playerId, identity, { identity }, outcomes::add) shouldBe BuilderBookStatusLookupStart.STARTED
        verifier.close()
        loaded.complete(instance())

        outcomes shouldBe emptyList()
        verifier.verify(playerId, identity, { identity }, outcomes::add) shouldBe BuilderBookStatusLookupStart.CLOSED
    }
})
