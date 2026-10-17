package ru.arc.autobuild

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.ints.shouldBeGreaterThan
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest

class SystemBuildBookCatalogTest : FunSpec({
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    fun config(root: java.nio.file.Path, body: String): java.nio.file.Path =
        root.resolve("system-build-books.yml").also { Files.writeString(it, body.trimIndent()) }

    test("full building library loads only metadata beyond the generic YAML size limit") {
        val root = Files.createTempDirectory("arc-builder-large-system-books-")
        val schematicsRoot = root.resolve("schematics-not-present")
        val entryCount = 16_384
        fun buildingId(index: Int) = "minecolonies/style_${index % 36}/building_$index.schem"
        val body = buildString {
            appendLine("books:")
            repeat(entryCount) { index ->
                appendLine("  - building-id: ${buildingId(index)}")
                appendLine("    title: Дом поселения $index")
                appendLine("    sha256: ${"a".repeat(64)}")
                appendLine("    player-enabled: true")
                appendLine("    starter-enabled: false")
                appendLine("    materials-included: true")
            }
        }
        body.codePointCount(0, body.length) shouldBeGreaterThan 3_000_000

        val catalog = SystemBuildBookCatalog.load(config(root, body), schematicsRoot)

        catalog.enabledBuildingIds.size shouldBe entryCount
        catalog.starterBuildingIds shouldBe emptyList()
        catalog.resolve(BuildBookData(buildingId(0), "First"))?.title shouldBe "Дом поселения 0"
        catalog.resolve(BuildBookData(buildingId(entryCount - 1), "Last"))?.title shouldBe "Дом поселения ${entryCount - 1}"
        Files.exists(schematicsRoot) shouldBe false
    }

    test("catalogue rejects excessive metadata entries before validating individual fields") {
        val root = Files.createTempDirectory("arc-builder-too-many-system-books-")
        val body = "books:\n" + "  - {}\n".repeat(65_537)

        shouldThrow<IllegalArgumentException> {
            SystemBuildBookCatalog.load(config(root, body), root.resolve("schematics-not-present"))
        }.message shouldBe "System build-book catalog size is invalid"
    }

    test("catalogue rejects files larger than 64 MiB before parsing") {
        val root = Files.createTempDirectory("arc-builder-oversize-system-books-")
        val path = root.resolve("system-build-books.yml")
        RandomAccessFile(path.toFile(), "rw").use { it.setLength(64L * 1024 * 1024 + 1) }

        shouldThrow<IllegalArgumentException> {
            SystemBuildBookCatalog.load(path, root.resolve("schematics-not-present"))
        }.message shouldBe "System build-book catalog exceeds 64 MiB"
    }

    test("catalogue resolves metadata without reading or hashing schematic files") {
        val root = Files.createTempDirectory("arc-builder-system-books-")
        val bytes = "reviewed schematic".toByteArray()
        Files.write(root.resolve("viking.schem"), bytes)
        val catalog = SystemBuildBookCatalog.load(
            config(
                root,
                """
                books:
                  - building-id: viking.schem
                    title: Стартовый дом
                    sha256: ${sha256(bytes)}
                    player-enabled: true
                    starter-enabled: true
                    materials-included: true
                    container-loot-table: minecraft:chests/spawn_bonus_chest
                """,
            ),
            root,
        )
        val data = BuildBookData(buildingId = "viking.schem", title = "viking.schem")

        catalog.enabledBuildingIds shouldBe listOf("viking.schem")
        catalog.starterBuildingIds shouldBe listOf("viking.schem")
        val definition = checkNotNull(catalog.resolve(data))
        definition.title shouldBe "Стартовый дом"
        definition.materialsIncluded shouldBe true
        definition.containerLootTableKey shouldBe "minecraft:chests/spawn_bonus_chest"
        definition.title.contains(".schem") shouldBe false
        catalog.resolve(BuildBookData(buildingId = "unknown.schem", title = "unknown")) shouldBe null

        val defaultConfig = config(root, Files.readString(root.resolve("system-build-books.yml"))
            .replace("    materials-included: true\n", ""))
        checkNotNull(SystemBuildBookCatalog.load(defaultConfig, root).resolve(data)).materialsIncluded shouldBe true

        Files.writeString(root.resolve("viking.schem"), "changed")
        catalog.resolve(data) shouldBe definition
        Files.delete(root.resolve("viking.schem"))
        catalog.resolve(data) shouldBe definition
        catalog.resolve(data.copy(buildingId = "viking")) shouldBe definition
    }

    test("moved schemas resolve old book ids but expose only canonical ids in starter and suggestion pools") {
        val root = Files.createTempDirectory("arc-builder-book-aliases-")
        val body = """
            books:
              - building-id: players/viking
                aliases: [viking.schem]
                title: Viking
                player-enabled: true
                starter-enabled: true
        """
        val catalog = SystemBuildBookCatalog.load(config(root, body), root.resolve("absent"))
        val canonical = catalog.resolve(BuildBookData("players/viking", "Viking"))
        listOf("viking", "viking.schem", "viking.schematic", "players/viking.schem").forEach { id ->
            catalog.resolve(BuildBookData(id, "Old book")) shouldBe canonical
        }
        canonical?.buildingId shouldBe "players/viking"
        catalog.enabledBuildingIds shouldBe listOf("players/viking")
        catalog.starterBuildingIds shouldBe listOf("players/viking")
        catalog.resolve(BuildBookData("viking", "Player book", playerCreated = true)) shouldBe null
        for (invalid in listOf("../outside", "players/viking.schem", "viking, viking.schem")) {
            shouldThrow<IllegalArgumentException> {
                SystemBuildBookCatalog.load(config(root, body.replace("viking.schem]", "$invalid]")), root)
            }
        }
        shouldThrow<IllegalArgumentException> {
            SystemBuildBookCatalog.load(config(root, body.replace("[viking.schem]", "viking.schem")), root)
        }
        shouldThrow<IllegalArgumentException> {
            SystemBuildBookCatalog.load(config(root, body.trimIndent() + "\n" + """
              - building-id: atelier/viking
                aliases: [viking]
                title: Other
                player-enabled: true
            """.trimIndent().prependIndent("  ")), root)
        }
    }

    test("catalog rejects traversal duplicate ids and disabled player entries") {
        val root = Files.createTempDirectory("arc-builder-system-books-invalid-")
        val bytes = "schematic".toByteArray()
        Files.write(root.resolve("viking.schem"), bytes)
        val digest = sha256(bytes)

        shouldThrow<IllegalArgumentException> {
            SystemBuildBookCatalog.load(
                config(
                    root,
                    """
                    books:
                      - building-id: viking.schem
                        title: Viking
                        sha256: $digest
                        player-enabled: true
                        container-loot-table: true
                    """,
                ),
                root,
            )
        }
        shouldThrow<IllegalArgumentException> {
            SystemBuildBookCatalog.load(
                config(
                    root,
                    """
                    books:
                      - building-id: viking.schem
                        title: Viking
                        sha256: $digest
                        player-enabled: true
                        container-loot-table: Invalid Loot Table
                    """,
                ),
                root,
            )
        }
        shouldThrow<IllegalArgumentException> {
            SystemBuildBookCatalog.load(
                config(
                    root,
                    """
                    books:
                      - building-id: ../outside.schem
                        title: Outside
                        sha256: $digest
                        player-enabled: true
                    """,
                ),
                root,
            )
        }
        shouldThrow<IllegalArgumentException> {
            SystemBuildBookCatalog.load(
                config(
                    root,
                    """
                    books:
                      - building-id: viking.schem
                        title: First
                        sha256: $digest
                        player-enabled: true
                      - building-id: viking.schem
                        title: Second
                        sha256: $digest
                        player-enabled: true
                    """,
                ),
                root,
            )
        }

        val nested = SystemBuildBookCatalog.load(config(root, """
            books:
              - building-id: civs/castle
                title: Castle
                sha256: $digest
                player-enabled: true
        """), root)
        nested.resolve(BuildBookData("civs/castle.schem", "old alias"))?.buildingId shouldBe "civs/castle"

        val disabled = SystemBuildBookCatalog.load(
            config(
                root,
                """
                books:
                  - building-id: viking.schem
                    title: Viking
                    sha256: $digest
                    player-enabled: false
                """,
            ),
            root,
        )
        disabled.enabledBuildingIds shouldBe emptyList()
        disabled.starterBuildingIds shouldBe emptyList()
        disabled.resolve(BuildBookData(buildingId = "viking.schem", title = "Viking")) shouldBe null
    }
})
