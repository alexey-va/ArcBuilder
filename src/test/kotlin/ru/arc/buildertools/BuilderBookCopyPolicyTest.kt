package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.autobuild.BuildBookData
import java.util.UUID

class BuilderBookCopyPolicyTest : FunSpec({
    val author = UUID.randomUUID()
    val active = BuildBookData(
        buildingId = "player-book.schem",
        title = "Дом",
        playerCreated = true,
        creatorId = author,
        creatorName = "Builder",
        blueprintId = UUID.randomUUID(),
        instanceId = UUID.randomUUID(),
        instanceGeneration = 1,
        issuePriceMinor = 12_345,
        contentSha256 = "a".repeat(64),
        schematicSha256 = "b".repeat(64),
    )

    test("only the active book author sees and may request copying") {
        BuilderBookCopyPolicy.canRequest(author, active) shouldBe true
        BuilderBookCopyPolicy.canRequest(UUID.randomUUID(), active) shouldBe false
        BuilderBookCopyPolicy.canRequest(author, active.copy(instanceId = null, instanceGeneration = null)) shouldBe false
        BuilderBookCopyPolicy.canRequest(author, BuildBookData("viking.schem", "Стартовый дом")) shouldBe false
    }

    test("confirmation revalidates both physical book and authoritative blueprint authors") {
        BuilderBookCopyPolicy.canConfirm(author, active.creatorId, author) shouldBe true
        BuilderBookCopyPolicy.canConfirm(author, UUID.randomUUID(), author) shouldBe false
        BuilderBookCopyPolicy.canConfirm(author, author, UUID.randomUUID()) shouldBe false
    }
})
