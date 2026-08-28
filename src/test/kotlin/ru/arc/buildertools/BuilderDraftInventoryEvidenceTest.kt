package ru.arc.buildertools

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.arc.TestBase
import ru.arc.autobuild.BuildBookData
import ru.arc.autobuild.BuildBookItems
import java.util.UUID

class BuilderDraftInventoryEvidenceTest : TestBase() {
    @Test
    fun `recovery evidence reads the current inventory and cursor state`() {
        val player = server.addPlayer("DraftOwner")
        val record = readyRecord(player.uniqueId, player.name)
        val matching = draftItem(record)

        player.setItemOnCursor(matching)
        BuilderDraftInventoryEvidence.capture(player, record).also { evidence ->
            assertEquals(1, evidence.matchingItems)
            assertEquals(0, evidence.conflictingItems)
        }

        player.setItemOnCursor(ItemStack(Material.AIR))
        player.inventory.addItem(matching)
        player.inventory.addItem(
            BuildBookItems.create(
                BuildBookData(
                    buildingId = record.buildingId,
                    title = "Подменённый черновик",
                    playerCreated = true,
                    creatorId = record.playerId,
                    creatorName = record.playerName,
                    blueprintId = record.blueprintId,
                    contentSha256 = record.contentSha256,
                    schematicSha256 = record.schematicSha256,
                    blockCount = record.blockCount,
                    cooldownSeconds = 0,
                ).validated(),
            ),
        )

        BuilderDraftInventoryEvidence.capture(player, record).also { evidence ->
            assertEquals(1, evidence.matchingItems)
            assertEquals(1, evidence.conflictingItems)
        }
    }

    private fun readyRecord(playerId: UUID, playerName: String): BuilderDraftRecord {
        val now = 1_800_000_000_000L
        return BuilderDraftRecord(
            operationId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
            playerId = playerId,
            playerName = playerName,
            title = "Дом у озера",
            buildingId = "player-${playerId.toString().replace("-", "")}-${"a".repeat(64)}.schem",
            blueprintId = UUID.fromString("99999999-8888-7777-6666-555555555555"),
            contentSha256 = "a".repeat(64),
            schematicSha256 = "b".repeat(64),
            blockCount = 42,
            phase = BuilderDraftPhase.READY,
            createdAtMillis = now,
            updatedAtMillis = now + 1,
        ).validated()
    }

    private fun draftItem(record: BuilderDraftRecord): ItemStack = BuildBookItems.create(
        BuildBookData(
            buildingId = record.buildingId,
            title = record.title,
            playerCreated = true,
            creatorId = record.playerId,
            creatorName = record.playerName,
            blueprintId = record.blueprintId,
            contentSha256 = record.contentSha256,
            schematicSha256 = record.schematicSha256,
            blockCount = record.blockCount,
            cooldownSeconds = 0,
        ).validated(),
    )
}
