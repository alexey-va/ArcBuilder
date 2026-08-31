package ru.arc.autobuild

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.arc.TestBase
import ru.arc.buildertools.BuilderClipboard
import ru.arc.buildertools.BuilderClipboardBlock
import ru.arc.buildertools.BuilderInventory
import ru.arc.buildertools.BuilderItemCodec
import java.util.UUID

class BuildBookTest : TestBase() {
    @Test
    fun `cardinal rotation and local offsets share one transform`() {
        val transform = BuildBookTransform(rotation = 90, offsetX = 2, offsetY = -1, offsetZ = 3).validated()

        assertEquals(Triple(-3, -1, 2), transform.rotatedOffset(90))
        assertEquals(180, transform.rotate(90).rotation)
        assertEquals(0, transform.rotate(-90).rotation)
    }

    @Test
    fun `book placement is relative to the facing captured at copy time`() {
        assertEquals(0, BuildBookRelativeRotation.resolve(90, 90, 0))
        assertEquals(90, BuildBookRelativeRotation.resolve(180, 90, 0))
        assertEquals(180, BuildBookRelativeRotation.resolve(90, 90, 180))
        assertEquals(270, BuildBookRelativeRotation.resolve(0, 90, 0))
    }

    @Test
    fun `non-cardinal rotations are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            BuildBookTransform(rotation = 45).validated()
        }
    }

    @Test
    fun `offset editing is bounded by configuration`() {
        val maximum = BuildBookSettings.maxOffset
        val transform = BuildBookTransform(offsetX = maximum).offset(dx = 5)

        assertEquals(maximum, transform.offsetX)
    }

    @Test
    fun `book metadata round trips through PDC`() {
        val expected = BuildBookData(
            buildingId = "player-0123456789abcdef0123456789abcdef-0123456789abcdef0123.schem",
            title = "Дом у озера",
            transform = BuildBookTransform(270, 2, -1, 4),
            sourceRotation = 90,
            playerCreated = true,
            creatorId = UUID.randomUUID(),
            blockCount = 37,
            cooldownSeconds = 0,
        ).validated()

        val item = BuildBookItems.create(expected)

        assertEquals(expected, BuildBookCodec.read(item))
        assertTrue(item.itemMeta.lore().orEmpty().isNotEmpty())
    }

    @Test
    fun `draft and registered UUID metadata round trip without vanilla italics`() {
        val creator = UUID.randomUUID()
        val blueprint = UUID.randomUUID()
        val draft = BuildBookData(
            buildingId = "player-${creator.toString().replace("-", "")}-draft.schem",
            title = "Башня",
            playerCreated = true,
            creatorId = creator,
            creatorName = "Builder",
            blueprintId = blueprint,
            contentSha256 = "a".repeat(64),
            schematicSha256 = "b".repeat(64),
            blockCount = 42,
            cooldownSeconds = 0,
        ).validated()
        val registered = draft.copy(
            instanceId = UUID.randomUUID(),
            instanceGeneration = 1,
            issuePriceMinor = 12_345L,
            playerMaterials = listOf(
                BuildBookMaterialRequirement(Material.OAK_PLANKS, 32),
                BuildBookMaterialRequirement(Material.STONE, 64),
            ).let(BuildBookMaterialRequirements::normalize),
        ).validated()

        val draftItem = BuildBookItems.create(draft)
        val registeredItem = BuildBookItems.create(registered)

        assertEquals(draft, BuildBookCodec.read(draftItem))
        assertEquals(registered, BuildBookCodec.read(registeredItem))
        val preGenerationItem = registeredItem.clone().also { item ->
            item.editMeta { meta ->
                meta.persistentDataContainer.remove(checkNotNull(NamespacedKey.fromString("arc:build_book_instance_generation")))
            }
        }
        assertEquals(1, BuildBookCodec.read(preGenerationItem)?.instanceGeneration)
        assertTrue(checkNotNull(draftItem.itemMeta.displayName()).decoration(TextDecoration.ITALIC) == TextDecoration.State.FALSE)
        assertTrue(draftItem.itemMeta.lore().orEmpty().all { it.decoration(TextDecoration.ITALIC) == TextDecoration.State.FALSE })
        assertTrue(registeredItem.itemMeta.lore().orEmpty().all { it.decoration(TextDecoration.ITALIC) == TextDecoration.State.FALSE })
        val plainText = PlainTextComponentSerializer.plainText()
        val draftLore = draftItem.itemMeta.lore().orEmpty().map(plainText::serialize)
        val registeredLore = registeredItem.itemMeta.lore().orEmpty().map(plainText::serialize)
        assertTrue(draftLore.any { it.contains("Цена новой копии: после расчёта") })
        assertTrue(registeredLore.any { it.contains("Цена новой копии: 123.45 💰") })
        assertTrue(registeredLore.any { it.contains("При строительстве: монеты не списываются") })
        assertTrue(registeredLore.any { it.contains("Принести для строительства") })
        assertTrue(registeredLore.any { it.contains("32×") })
        assertTrue(registeredLore.any { it.contains("64×") })
        val coinGlyphs = registeredItem.itemMeta.lore().orEmpty()
            .flatMap { line -> line.descendants().filterIsInstance<TextComponent>().toList() }
            .filter { component -> component.content() == "💰" }
        assertEquals(1, coinGlyphs.size)
        assertTrue(coinGlyphs.all { component -> component.color() == NamedTextColor.WHITE })
        // MockBukkit 4.116.3 omits tooltipStyle when its ItemMeta copy constructor runs after editMeta.
        // Exercise the same production decorator directly so the assertion remains strict and is not skipped.
        val appearanceMeta = ItemStack(Material.BOOK).itemMeta
        BuildBookItems.applyAppearance(appearanceMeta, registered)
        assertEquals(NamespacedKey.fromString("lzblocks:tooltip/rare"), appearanceMeta.tooltipStyle)
    }

    @Test
    fun `system book presentation never exposes the schematic filename`() {
        val item = BuildBookItems.create(
            BuildBookData(
                buildingId = "viking.schem",
                title = "Стартовый дом",
            ).validated(),
        )
        val plain = PlainTextComponentSerializer.plainText()
        val visible = buildList {
            add(plain.serialize(checkNotNull(item.itemMeta.displayName())))
            addAll(item.itemMeta.lore().orEmpty().map(plain::serialize))
        }

        assertTrue(visible.none { ".schem" in it })
        assertTrue(visible.any { "Стартовый дом" in it })
    }

    @Test
    fun `player material requirements use a canonical bounded PDC representation`() {
        val encoded = BuildBookMaterialRequirements.encode(
            listOf(
                BuildBookMaterialRequirement(Material.STONE, 32),
                BuildBookMaterialRequirement(Material.OAK_PLANKS, 16),
                BuildBookMaterialRequirement(Material.STONE, 32),
            ),
        )

        assertEquals(
            listOf(
                BuildBookMaterialRequirement(Material.OAK_PLANKS, 16),
                BuildBookMaterialRequirement(Material.STONE, 64),
            ),
            BuildBookMaterialRequirements.decode(encoded),
        )
    }

    @Test
    fun `long player title is compacted without splitting Unicode code points`() {
        val title = "Очень длинное название уютного дома у озера 🏡"
        val compact = BuildBookItems.compactTitle(title, 28)

        assertTrue(compact.endsWith("…"))
        assertTrue(compact.codePointCount(0, compact.length) <= 29)
        assertFalse(compact.contains('\uFFFD'))
    }

    @Test
    fun `PDC book without current schema is rejected`() {
        val item = ItemStack(Material.BOOK)
        item.editMeta { meta ->
            meta.persistentDataContainer.set(
                checkNotNull(NamespacedKey.fromString("arc:build_book_id")),
                PersistentDataType.STRING,
                "house.schem",
            )
        }

        assertNull(BuildBookCodec.read(item))
    }

    @Test
    fun `material transaction requires and consumes the exact build book`() {
        val player = server.addPlayer("BookBuilder")
        val data = BuildBookData(
            buildingId = "house.schem",
            title = "Дом",
            playerCreated = true,
            creatorId = player.uniqueId,
            blockCount = 2,
            cooldownSeconds = 0,
        ).validated()
        val buildBook = BuildBookItems.create(data)
        val costs = BuilderItemCodec.aggregate(listOf(buildBook, ItemStack(Material.STONE, 2)))

        player.inventory.addItem(ItemStack(Material.BOOK), ItemStack(Material.STONE, 2))
        assertFalse(BuilderInventory.canApply(player, costs, emptyList(), null, 0))

        player.inventory.clear()
        player.inventory.addItem(buildBook, ItemStack(Material.STONE, 2))
        assertTrue(BuilderInventory.canApply(player, costs, emptyList(), null, 0))
        assertTrue(BuilderInventory.removeCosts(player.inventory, costs))
        assertTrue(player.inventory.storageContents.filterNotNull().none { BuildBookCodec.matches(it, data) })
        assertFalse(player.inventory.contains(Material.STONE))
    }

    @Test
    fun `content addressed player schematic names are stable and owner scoped`() {
        val owner = UUID.randomUUID()
        val now = System.currentTimeMillis()
        val first = clipboard(now, "minecraft:stone")
        val same = clipboard(now + 1, "minecraft:stone")
        val changed = clipboard(now, "minecraft:oak_planks")

        assertEquals(PlayerBuildBookStore.fileName(owner, first), PlayerBuildBookStore.fileName(owner, same))
        assertNotEquals(PlayerBuildBookStore.fileName(owner, first), PlayerBuildBookStore.fileName(owner, changed))
        assertNotEquals(PlayerBuildBookStore.fileName(owner, first), PlayerBuildBookStore.fileName(UUID.randomUUID(), first))
    }

    @Test
    fun `player schematic reuses a full content address and rejects an address mismatch`() {
        val owner = UUID.randomUUID()
        val source = clipboard(System.currentTimeMillis(), "minecraft:oak_planks")
        val prepared = PreparedPlayerBuildBookTemplate(
            creatorId = owner,
            fileName = PlayerBuildBookStore.fileName(owner, source),
            contentSha256 = PlayerBuildBookStore.contentSha256(source),
            blockCount = source.blocks.size,
            writeSchematic = { output -> output.write("stable-schematic".toByteArray()) },
        )

        val first = PlayerBuildBookStore.persist(prepared)
        val repeated = PlayerBuildBookStore.persist(
            prepared.copy(writeSchematic = { output -> output.write("same-content-new-metadata".toByteArray()) }),
        )

        assertEquals(first, repeated)
        assertTrue(first.buildingId.contains(prepared.contentSha256))
        assertEquals(first.schematicSha256, PlayerBuildBookStore.schematicSha256(first.buildingId))

        PlayerBuildBookStore.register(first)

        assertEquals(first.buildingId, BuildingManager.getBuilding(first.buildingId)?.fileName)

        val collision = prepared.copy(
            contentSha256 = "f".repeat(64),
            writeSchematic = { output -> output.write("different-schematic".toByteArray()) },
        )
        assertThrows(IllegalArgumentException::class.java) { PlayerBuildBookStore.persist(collision) }
    }

    private fun clipboard(now: Long, blockData: String) = BuilderClipboard(
        blocks = listOf(BuilderClipboardBlock(0, 0, 0, blockData)),
        sizeX = 2,
        sizeY = 1,
        sizeZ = 1,
        createdAtMillis = now,
        expiresAtMillis = now + 60_000,
    ).validated(100)
}

private fun Component.descendants(): Sequence<Component> = sequence {
    yield(this@descendants)
    children().forEach { child -> yieldAll(child.descendants()) }
}
