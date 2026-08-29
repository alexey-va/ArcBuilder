package ru.arc.autobuild

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.security.MessageDigest

class SystemBuildBookCatalogTest : FunSpec({
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    fun config(root: java.nio.file.Path, body: String): java.nio.file.Path =
        root.resolve("system-build-books.yml").also { Files.writeString(it, body.trimIndent()) }

    test("known reviewed system book resolves only while the schematic digest matches") {
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
                    materials-included: true
                """,
            ),
            root,
        )
        val data = BuildBookData(buildingId = "viking.schem", title = "viking.schem")

        val definition = checkNotNull(catalog.resolve(data))
        definition.title shouldBe "Стартовый дом"
        definition.materialsIncluded shouldBe true
        definition.title.contains(".schem") shouldBe false
        catalog.resolve(BuildBookData(buildingId = "unknown.schem", title = "unknown")) shouldBe null

        Files.writeString(root.resolve("viking.schem"), "changed")
        catalog.resolve(data) shouldBe null
    }

    test("catalog rejects digest mismatch traversal duplicate ids and disabled player entries") {
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
                        sha256: ${"0".repeat(64)}
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
        disabled.resolve(BuildBookData(buildingId = "viking.schem", title = "Viking")) shouldBe null
    }
})
