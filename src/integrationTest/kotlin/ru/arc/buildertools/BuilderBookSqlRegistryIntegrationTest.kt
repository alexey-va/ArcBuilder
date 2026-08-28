package ru.arc.buildertools

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import ru.arc.onetime.OneTimeUseClaim
import ru.arc.onetime.OneTimeUseClaimResult
import ru.arc.onetime.OneTimeUseCommitResult
import ru.arc.onetime.OneTimeUseReleaseResult
import ru.arc.sql.SqlConnectionConfig
import ru.arc.sql.SqlRuntime
import ru.arc.sql.SqlSslMode
import ru.arc.testing.containers.MySqlTestEndpoint
import ru.arc.testing.containers.MySqlTestService
import ru.arc.testing.containers.MySqlTestSettings
import java.util.UUID
import java.util.concurrent.TimeUnit

class BuilderBookSqlRegistryIntegrationTest : StringSpec({
    "domain reservation survives a one-time ledger failure and is recoverable" {
        val settings = MySqlTestSettings(image = "mysql:8.4.10", database = "arc_test")
        MySqlTestService.start(settings).use { mysql ->
            val mint = paidMint()
            openRegistry(mysql.endpoint).use { registry ->
                registry.initialize().await()
                val prepared = mint.preparedVersion()
                val started = prepared.withdrawalStarted()
                registry.prepareMint(prepared).await() shouldBe true
                registry.transitionMint(prepared, started).await() shouldBe started
                registry.transitionMint(started, mint).await() shouldBe mint
                registry.issuePaidMint(mint.transactionId, 10L).await().status shouldBe BuilderBookMintStatus.ISSUED
                registry.markDelivered(mint.instanceId, mint.transactionId, 11L).await() shouldBe true

                mysql.endpoint.copy(username = "root", password = settings.rootPassword).connect().use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute(
                            "CREATE TRIGGER arc_builder_fail_claim BEFORE INSERT ON arc_one_time_uses " +
                                "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'injected claim failure'",
                        )
                    }
                }

                val operationId = UUID.randomUUID()
                val request = bookUseRequest(mint, operationId)
                shouldThrowAny { registry.oneTimeUses.claim(request).await() }
                val reserved = checkNotNull(registry.loadInstance(mint.instanceId).await())
                reserved.status shouldBe BuilderBookInstanceStatus.RESERVED
                reserved.reservationOperationId shouldBe operationId

                mysql.endpoint.copy(username = "root", password = settings.rootPassword).connect().use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute("DROP TRIGGER arc_builder_fail_claim")
                    }
                }

                registry.oneTimeUses.claim(request).await() shouldBe
                    OneTimeUseClaimResult.Acquired(OneTimeUseClaim.acquired(request, newlyCreated = true))
                registry.oneTimeUses.release(OneTimeUseClaim.acquired(request, newlyCreated = false)).await() shouldBe
                    OneTimeUseReleaseResult.RELEASED
                registry.loadInstance(mint.instanceId).await()?.status shouldBe BuilderBookInstanceStatus.AVAILABLE
            }
        }
    }

    "MySQL migrations, delivery, contention, idempotency and open-mint uniqueness are durable" {
        val settings = MySqlTestSettings(image = "mysql:8.4.10", database = "arc_test")
        MySqlTestService.start(settings).use { mysql ->
            val endpoint = mysql.endpoint
            val mint = paidMint()
            val openMintPlayer = UUID.randomUUID()
            openRegistry(endpoint).use { registry ->
                registry.initialize().await()

                val prepared = mint.preparedVersion()
                registry.prepareMint(prepared).await() shouldBe true
                val started = prepared.withdrawalStarted()
                registry.transitionMint(prepared, started).await() shouldBe started
                registry.transitionMint(started, mint).await() shouldBe mint
                val issued = registry.issuePaidMint(mint.transactionId, 10L).await()
                issued.status shouldBe BuilderBookMintStatus.ISSUED

                val deliveries = registry.pendingDeliveries(mint.playerId).await()
                deliveries shouldHaveSize 1
                deliveries.single().instance.transactionId shouldBe mint.transactionId
                deliveries.single().placement shouldBe mint.placement
                registry.markDelivered(mint.instanceId, UUID.randomUUID(), 11L).await() shouldBe false
                registry.markDelivered(mint.instanceId, mint.transactionId, 11L).await() shouldBe true
                registry.markDelivered(mint.instanceId, mint.transactionId, 12L).await() shouldBe true

                val firstOperation = UUID.randomUUID()
                val secondOperation = UUID.randomUUID()
                val firstRequest = bookUseRequest(mint, firstOperation)
                val secondRequest = bookUseRequest(mint, secondOperation)
                val firstReservation = registry.oneTimeUses.claim(firstRequest)
                val secondReservation = registry.oneTimeUses.claim(secondRequest)
                val reservationOutcomes = listOf(firstReservation.await(), secondReservation.await())
                reservationOutcomes.count { it is OneTimeUseClaimResult.Acquired } shouldBe 1
                reservationOutcomes.count { it == OneTimeUseClaimResult.Busy } shouldBe 1
                val winner = if (reservationOutcomes[0] is OneTimeUseClaimResult.Acquired) {
                    OneTimeUseClaim.acquired(firstRequest, newlyCreated = false)
                } else {
                    OneTimeUseClaim.acquired(secondRequest, newlyCreated = false)
                }

                registry.oneTimeUses.commit(winner).await() shouldBe OneTimeUseCommitResult.COMMITTED
                registry.oneTimeUses.commit(winner).await() shouldBe OneTimeUseCommitResult.ALREADY_COMMITTED
                registry.loadInstance(mint.instanceId).await()?.status shouldBe BuilderBookInstanceStatus.CONSUMED

                val sourceMint = paidMint()
                val sourcePrepared = sourceMint.preparedVersion()
                val sourceStarted = sourcePrepared.withdrawalStarted()
                registry.prepareMint(sourcePrepared).await() shouldBe true
                registry.transitionMint(sourcePrepared, sourceStarted).await() shouldBe sourceStarted
                registry.transitionMint(sourceStarted, sourceMint).await() shouldBe sourceMint
                registry.issuePaidMint(sourceMint.transactionId, 40L).await().status shouldBe BuilderBookMintStatus.ISSUED
                registry.markDelivered(sourceMint.instanceId, sourceMint.transactionId, 41L).await() shouldBe true

                val copyPlayer = sourceMint.playerId
                val copyTransaction = UUID.randomUUID()
                val copyPaid = BuilderBookMint(
                    transactionId = copyTransaction,
                    kind = BuilderBookMintKind.COPY,
                    playerId = copyPlayer,
                    blueprint = sourceMint.blueprint,
                    instanceId = UUID.randomUUID(),
                    sourceInstanceId = sourceMint.instanceId,
                    sourceInstanceGeneration = BuilderBookInstance.INITIAL_GENERATION,
                    placement = BuilderBookPlacement(180, -2, 1, 3),
                    status = BuilderBookMintStatus.FUNDS_WITHDRAWN,
                    createdAtMillis = 42L,
                    updatedAtMillis = 44L,
                    balanceBeforeMinor = 1_000L,
                    balanceAfterMinor = 885L,
                    evidence = "integration_copy_delta",
                ).validated()
                val copyPrepared = copyPaid.preparedVersion()
                val copyStarted = copyPrepared.withdrawalStarted()
                val copySourceRequest = bookUseRequest(sourceMint, copyTransaction, copyPlayer)
                registry.oneTimeUses.claim(copySourceRequest).await() shouldBe
                    OneTimeUseClaimResult.Acquired(OneTimeUseClaim.acquired(copySourceRequest, newlyCreated = true))
                registry.prepareMint(copyPrepared).await() shouldBe true
                registry.transitionMint(copyPrepared, copyStarted).await() shouldBe copyStarted
                registry.transitionMint(copyStarted, copyPaid).await() shouldBe copyPaid
                registry.issuePaidMint(copyTransaction, 45L).await().status shouldBe BuilderBookMintStatus.ISSUED
                registry.loadInstance(sourceMint.instanceId).await()?.status shouldBe BuilderBookInstanceStatus.RESERVED
                registry.markDelivered(copyPaid.instanceId, copyTransaction, 46L).await() shouldBe true
                registry.oneTimeUses.release(OneTimeUseClaim.acquired(copySourceRequest, newlyCreated = false)).await() shouldBe
                    OneTimeUseReleaseResult.RELEASED
                registry.loadInstance(sourceMint.instanceId).await()?.status shouldBe BuilderBookInstanceStatus.AVAILABLE

                val auctionLease = UUID.randomUUID()
                registry.reserveForAuction(
                    sourceMint.instanceId,
                    BuilderBookInstance.INITIAL_GENERATION,
                    sourceMint.blueprint.blueprintId,
                    sourceMint.blueprint.buildingId,
                    sourceMint.blueprint.schematicSha256,
                    auctionLease,
                    copyPlayer,
                    "survival",
                    50L,
                ).await() shouldBe BuilderBookAuctionReservationResult.Reserved(sourceMint.blueprint)
                registry.loadInstance(sourceMint.instanceId).await()?.status shouldBe BuilderBookInstanceStatus.LISTED
                registry.listedForServer("survival").await().map { it.instanceId } shouldBe listOf(sourceMint.instanceId)
                registry.oneTimeUses.claim(bookUseRequest(sourceMint, UUID.randomUUID(), copyPlayer)).await() shouldBe
                    OneTimeUseClaimResult.Busy
                registry.releaseFromAuction(sourceMint.instanceId, UUID.randomUUID()).await() shouldBe false
                val auctionBuyer = UUID.randomUUID()
                registry.beginAuctionTransfer(
                    sourceMint.instanceId,
                    auctionLease,
                    auctionBuyer,
                    "survival",
                    52L,
                ).await() shouldBe BuilderBookAuctionTransferResult.Pending(2)
                registry.beginAuctionTransfer(
                    sourceMint.instanceId,
                    auctionLease,
                    auctionBuyer,
                    "survival",
                    53L,
                ).await() shouldBe BuilderBookAuctionTransferResult.Pending(2)
                val transferring = checkNotNull(registry.loadInstance(sourceMint.instanceId).await())
                transferring.status shouldBe BuilderBookInstanceStatus.TRANSFER_PENDING
                transferring.ownerId shouldBe auctionBuyer
                transferring.generation shouldBe 2
                registry.completeAuctionTransfer(sourceMint.instanceId, auctionLease, auctionBuyer, 2).await() shouldBe true
                registry.completeAuctionTransfer(sourceMint.instanceId, auctionLease, auctionBuyer, 2).await() shouldBe true
                registry.beginAuctionTransfer(
                    sourceMint.instanceId,
                    auctionLease,
                    auctionBuyer,
                    "survival",
                    54L,
                ).await() shouldBe BuilderBookAuctionTransferResult.Completed(2)
                val transferred = checkNotNull(registry.loadInstance(sourceMint.instanceId).await())
                transferred.status shouldBe BuilderBookInstanceStatus.AVAILABLE
                transferred.ownerId shouldBe auctionBuyer
                transferred.generation shouldBe 2
                registry.oneTimeUses.claim(
                    bookUseRequest(
                        sourceMint,
                        UUID.randomUUID(),
                        copyPlayer,
                        generation = BuilderBookInstance.INITIAL_GENERATION,
                    ),
                ).await() shouldBe OneTimeUseClaimResult.IdentityConflict
                val buyerOperation = UUID.randomUUID()
                val buyerRequest = bookUseRequest(sourceMint, buyerOperation, auctionBuyer, generation = 2)
                registry.oneTimeUses.claim(buyerRequest).await() shouldBe
                    OneTimeUseClaimResult.Acquired(OneTimeUseClaim.acquired(buyerRequest, newlyCreated = true))
                registry.oneTimeUses.release(OneTimeUseClaim.acquired(buyerRequest, newlyCreated = false)).await() shouldBe
                    OneTimeUseReleaseResult.RELEASED

                val firstMint = preparedMint(openMintPlayer)
                val secondMint = preparedMint(playerId = firstMint.playerId)
                val mintOutcomes = listOf(registry.prepareMint(firstMint), registry.prepareMint(secondMint)).map { it.await() }
                mintOutcomes.count { it } shouldBe 1
                mintOutcomes.count { !it } shouldBe 1
            }

            endpoint.connect().use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate("DELETE FROM arc_builder_books_schema_history WHERE version = 2")
                    statement.executeUpdate(
                        "UPDATE arc_builder_books_schema_history " +
                            "SET checksum = 'b1a6998b8a240a3c24e7dce340268dea19047cfadd83d8863d7d0218a415223a' " +
                            "WHERE version = 1",
                    )
                }
            }
            openRegistry(endpoint).use { retried ->
                retried.initialize().await()
                retried.loadInstance(mint.instanceId).await()?.status shouldBe BuilderBookInstanceStatus.CONSUMED
            }

            openRegistry(endpoint).use { reopened ->
                reopened.initialize().await()
                reopened.loadInstance(mint.instanceId).await()?.status shouldBe BuilderBookInstanceStatus.CONSUMED
                val recovered = reopened.openMints().await().filter { it.playerId == openMintPlayer }
                recovered shouldHaveSize 1
                recovered.single().status shouldBe BuilderBookMintStatus.PREPARED
            }
        }
    }

    "migration resumes after MySQL committed only the first version-one DDL statements" {
        val settings = MySqlTestSettings(image = "mysql:8.0.46", database = "arc_test")
        MySqlTestService.start(settings).use { mysql ->
            mysql.endpoint.copy(username = "root", password = settings.rootPassword).connect().use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("REVOKE ALL PRIVILEGES, GRANT OPTION FROM '${settings.username}'@'%'")
                    statement.execute(
                        "GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX " +
                            "ON `${settings.database}`.* TO '${settings.username}'@'%'",
                    )
                }
            }
            mysql.endpoint.connect().use { connection ->
                BuilderBookSqlRegistry.MIGRATIONS.single { it.version == 1 }.statements.take(2).forEach { sql ->
                    connection.createStatement().use { statement -> statement.execute(sql) }
                }
            }

            openRegistry(mysql.endpoint).use { registry ->
                registry.initialize().await()
            }

            mysql.endpoint.connect().use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT version FROM arc_builder_books_schema_history ORDER BY version",
                    ).use { rows ->
                        buildList {
                            while (rows.next()) add(rows.getInt("version"))
                        } shouldBe (1..BuilderBookSqlRegistry.CURRENT_SCHEMA_VERSION).toList()
                    }
                }
            }
        }
    }
})

private fun bookUseRequest(
    mint: BuilderBookMint,
    operationId: UUID,
    playerId: UUID = mint.playerId,
    generation: Int = BuilderBookInstance.INITIAL_GENERATION,
) = BuilderBookOneTimeUse.request(
    instanceId = mint.instanceId,
    expectedGeneration = generation,
    blueprintId = mint.blueprint.blueprintId,
    buildingId = mint.blueprint.buildingId,
    schematicSha256 = mint.blueprint.schematicSha256,
    operationId = operationId,
    playerId = playerId,
    serverName = "survival",
)

private fun openRegistry(endpoint: MySqlTestEndpoint): BuilderBookSqlRegistry = BuilderBookSqlRegistry(
    SqlRuntime.create(
        SqlConnectionConfig(
            host = endpoint.host,
            port = endpoint.port,
            database = endpoint.database,
            username = endpoint.username,
            password = endpoint.password,
            sslMode = SqlSslMode.DISABLED,
            minimumIdle = 0,
            maximumPoolSize = 4,
            connectionTimeoutMs = 5_000,
            socketTimeoutMs = 10_000,
            validationTimeoutMs = 2_000,
            maxLifetimeMs = 60_000,
            failFast = true,
        ),
        "builder-book-it-${UUID.randomUUID().toString().take(8)}",
    ),
)

private fun preparedMint(playerId: UUID = UUID.randomUUID()): BuilderBookMint {
    val blueprint = BuilderBookBlueprint(
        blueprintId = UUID.randomUUID(),
        creatorId = playerId,
        creatorName = "Builder",
        title = "MySQL fixture",
        buildingId = "player-${playerId.toString().replace("-", "")}-${UUID.randomUUID().toString().take(8)}.schem",
        contentSha256 = "a".repeat(64),
        schematicSha256 = "b".repeat(64),
        blockCount = 10,
        materialTypes = 2,
        materialItems = 10,
        materialCostMinor = 100L,
        constructionFeeMinor = 15L,
        issuePriceMinor = 115L,
        createdAtMillis = 1L,
    ).validated()
    return BuilderBookMint(
        transactionId = UUID.randomUUID(),
        kind = BuilderBookMintKind.CREATE,
        playerId = playerId,
        blueprint = blueprint,
        instanceId = UUID.randomUUID(),
        placement = BuilderBookPlacement(90, 1, 2, -3),
        createdAtMillis = 2L,
    ).validated()
}

private fun paidMint(): BuilderBookMint {
    val prepared = preparedMint()
    return prepared.copy(
        status = BuilderBookMintStatus.FUNDS_WITHDRAWN,
        updatedAtMillis = 4L,
        balanceBeforeMinor = 1_000L,
        balanceAfterMinor = 885L,
        evidence = "integration_exact_delta",
    ).validated()
}

private fun BuilderBookMint.preparedVersion(): BuilderBookMint = copy(
    status = BuilderBookMintStatus.PREPARED,
    updatedAtMillis = createdAtMillis,
    balanceBeforeMinor = null,
    balanceAfterMinor = null,
    evidence = null,
).validated()

private fun BuilderBookMint.withdrawalStarted(): BuilderBookMint = copy(
    status = BuilderBookMintStatus.WITHDRAWAL_STARTED,
    updatedAtMillis = createdAtMillis + 1,
    balanceBeforeMinor = 1_000L,
).validated()

private fun <T> java.util.concurrent.CompletableFuture<T>.await(): T = get(20, TimeUnit.SECONDS)
