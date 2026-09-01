package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemStack
import ru.arc.buildertools.BuilderConstructionProjectState.ACTIVE
import ru.arc.buildertools.BuilderConstructionProjectState.PAUSED
import ru.arc.config.ConfigManager
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.loadPlugin
import ru.ruscrafting.builder.paper.ArcBuilderPlugin
import java.util.UUID
import java.util.Locale

class BuilderConstructionMenuMockBukkitTest : FunSpec({
    test("owner opens a complete 27-slot control menu and pauses and resumes through its action slot") {
        withMenuFixture { fixture ->
            val owner = fixture.paper.addPlayer("MenuOwner").also {
                it.gameMode = GameMode.CREATIVE
                it.setLocale(Locale.forLanguageTag("ru-RU"))
            }
            var project = fixture.project(owner.uniqueId)
            val requests = mutableListOf<Boolean>()
            fixture.openManager(
                projectLookup = { project },
                canControl = { player, record -> player.uniqueId == record.playerId },
                requestPaused = { _, _, pause ->
                    requests += pause
                    project = if (pause) project.paused(project.updatedAtMillis + 1) else project.resumed(project.updatedAtMillis + 1)
                    true
                },
            ).use { menu ->
                menu.open(owner, project)
                val inventory = owner.openInventory.topInventory

                inventory.size shouldBe 27
                inventory.getItem(0)?.type shouldBe Material.GRAY_STAINED_GLASS_PANE
                inventory.getItem(10)?.type shouldBe Material.BOOK
                inventory.getItem(12)?.type shouldBe Material.CLOCK
                inventory.getItem(14)?.type shouldBe Material.CHEST
                inventory.getItem(16)?.type shouldBe Material.REDSTONE_TORCH
                plain(inventory.getItem(10)!!.itemMeta.displayName()!!) shouldContain "Подводный дом"
                inventory.getItem(10)!!.itemMeta.displayName()!!.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
                inventory.getItem(14)!!.itemMeta.lore().orEmpty().joinToString(" | ", transform = ::plain) shouldContain "×12"

                fixture.click(owner, 16).isCancelled.shouldBeTrue()
                requests shouldContain true
                inventory.getItem(16)?.type shouldBe Material.LIME_DYE
                plain(inventory.getItem(16)!!.itemMeta.displayName()!!) shouldContain "Продолжить"

                fixture.click(owner, 16).isCancelled.shouldBeTrue()
                requests shouldBe listOf(true, false)
                inventory.getItem(16)?.type shouldBe Material.REDSTONE_TORCH
            }
        }
    }

    test("another player receives a read-only menu and every inventory click remains isolated") {
        withMenuFixture { fixture ->
            val owner = fixture.paper.addPlayer("ProjectOwner")
            val visitor = fixture.paper.addPlayer("MenuVisitor").also {
                it.setLocale(Locale.forLanguageTag("ru-RU"))
            }
            val project = fixture.project(owner.uniqueId)
            var calls = 0
            fixture.openManager(
                projectLookup = { project },
                canControl = { player, record -> player.uniqueId == record.playerId },
                requestPaused = { _, _, _ -> calls += 1; true },
            ).use { menu ->
                menu.open(visitor, project)
                val inventory = visitor.openInventory.topInventory

                inventory.getItem(16)?.type shouldBe Material.GRAY_DYE
                plain(inventory.getItem(16)!!.itemMeta.displayName()!!) shouldContain "Только просмотр"
                fixture.click(visitor, 16).isCancelled.shouldBeTrue()
                fixture.click(visitor, inventory.size).isCancelled.shouldBeTrue()
                calls shouldBe 0
            }
        }
    }

    test("reopening the same project keeps refresh active, shows pause, and closes a terminal project") {
        withMenuFixture { fixture ->
            val owner = fixture.paper.addPlayer("RefreshOwner").also {
                it.setLocale(Locale.forLanguageTag("ru-RU"))
            }
            var project: BuilderConstructionProjectRecord? = fixture.project(owner.uniqueId)
            fixture.openManager(
                projectLookup = { project },
                canControl = { _, _ -> true },
                requestPaused = { _, _, _ -> true },
            ).use { menu ->
                menu.open(owner, checkNotNull(project))
                menu.open(owner, checkNotNull(project))
                val original = owner.openInventory.topInventory

                project = checkNotNull(project).paused(checkNotNull(project).updatedAtMillis + 1)
                fixture.paper.performTicks(10)

                owner.openInventory.topInventory shouldBe original
                owner.openInventory.topInventory.getItem(16)?.type shouldBe Material.LIME_DYE
                checkNotNull(project).state shouldBe PAUSED

                project = checkNotNull(project).cancelled(checkNotNull(project).updatedAtMillis + 1)
                fixture.paper.performTicks(10)

                owner.openInventory.type shouldBe InventoryType.CRAFTING
            }
        }
    }
})

private class ConstructionMenuFixture(
    val paper: MockBukkitTestRuntime,
    val plugin: ArcBuilderPlugin,
) : AutoCloseable {
    private val config = BuilderToolsConfig(ConfigManager.ofModule(plugin.dataPath, "builder-tools.yml")).validated()
    private val world = paper.addSimpleWorld("construction-menu")
    private val taskScope = LifecycleTaskScope()

    fun project(ownerId: UUID): BuilderConstructionProjectRecord {
        val now = 1_800_000_000_000L
        val projectId = UUID.randomUUID()
        val book = amount(Material.BOOK)
        val stone = amount(Material.STONE, 12)
        val change = BuilderBlockChange(
            BuilderBlockPos(world.uid, 10, 64, 10),
            "minecraft:dirt",
            "minecraft:stone",
        )
        val plan = BuilderPlan(
            id = projectId,
            playerId = ownerId,
            kind = BuilderPlanKind.BUILD_BOOK,
            changes = listOf(change),
            costs = listOf(book, stone),
            rewards = emptyList(),
            createdAtMillis = now,
            expiresAtMillis = now + 60_000,
        )
        return BuilderConstructionProjectRecord(
            projectId = projectId,
            playerId = ownerId,
            playerName = paper.server.getPlayer(ownerId)?.name ?: "Builder",
            projectTitle = "Подводный дом",
            plan = plan,
            steps = listOf(BuilderConstructionStep(change, stone, null)),
            bookCost = book,
            state = ACTIVE,
            cursor = 0,
            createdAtMillis = now,
            updatedAtMillis = now,
        ).validated()
    }

    fun openManager(
        projectLookup: (UUID) -> BuilderConstructionProjectRecord?,
        canControl: (org.bukkit.entity.Player, BuilderConstructionProjectRecord) -> Boolean,
        requestPaused: (org.bukkit.entity.Player, UUID, Boolean) -> Boolean,
    ): BuilderConstructionMenuManager = BuilderConstructionMenuManager(
        plugin = plugin,
        settings = config.constructionMenuSettings(),
        messages = config.messages(),
        taskScope = taskScope,
        projectLookup = projectLookup,
        canControl = canControl,
        requestPaused = requestPaused,
    )

    fun click(player: org.bukkit.entity.Player, rawSlot: Int): InventoryClickEvent = paper.callEvent(
        InventoryClickEvent(
            player.openInventory,
            InventoryType.SlotType.CONTAINER,
            rawSlot,
            ClickType.LEFT,
            InventoryAction.PICKUP_ALL,
        ),
    )

    private fun amount(material: Material, amount: Int = 1): BuilderItemAmount = BuilderItemAmount(
        itemBase64 = BuilderItemCodec.encodePrototype(ItemStack(material)),
        materialKey = material.key.toString(),
        amount = amount,
    )

    override fun close() {
        taskScope.close()
    }
}

private inline fun withMenuFixture(block: (ConstructionMenuFixture) -> Unit) {
    ConfigManager.clear()
    MockBukkitTestRuntime.open().use { paper ->
        val plugin = paper.loadPlugin<ArcBuilderPlugin>()
        BuilderToolsModule.shutdown()
        try {
            ConstructionMenuFixture(paper, plugin).use(block)
        } finally {
            ConfigManager.clear()
        }
    }
}

private fun plain(component: net.kyori.adventure.text.Component): String =
    PlainTextComponentSerializer.plainText().serialize(component)
