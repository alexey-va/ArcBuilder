package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemStack
import ru.arc.config.ConfigManager
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.loadPlugin
import ru.ruscrafting.builder.paper.ArcBuilderPlugin
import java.util.UUID

class BuilderConstructionProjectsMenuMockBukkitTest : FunSpec({
    test("projects menu shows only the owners unfinished builds and separates left and right click actions") {
        ConfigManager.clear()
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<ArcBuilderPlugin>()
            BuilderToolsModule.shutdown()
            val world = paper.addSimpleWorld("projects-menu")
            val owner = paper.addPlayer("ProjectOwner").also {
                it.gameMode = GameMode.CREATIVE
                it.setLocale(java.util.Locale.forLanguageTag("ru-RU"))
            }
            val other = paper.addPlayer("OtherBuilder")
            val active = project(world.uid, owner.uniqueId, "Дом у реки", BuilderConstructionProjectState.WAITING_MATERIALS)
            val foreign = project(world.uid, other.uniqueId, "Чужой дом", BuilderConstructionProjectState.ACTIVE)
            val completed = project(world.uid, owner.uniqueId, "Готовый дом", BuilderConstructionProjectState.ACTIVE)
                .cancelled(1_800_000_000_100L)
            val teleports = mutableListOf<UUID>()
            val inspections = mutableListOf<UUID>()
            val scope = LifecycleTaskScope()
            try {
                BuilderConstructionProjectsMenuManager(
                    plugin = plugin,
                    messages = BuilderToolsConfig(ConfigManager.ofModule(plugin.dataPath, "builder-tools.yml")).messages(),
                    taskScope = scope,
                    projects = { listOf(foreign, completed, active) },
                    onTeleport = { _, record -> teleports += record.projectId },
                    onInspect = { _, record -> inspections += record.projectId },
                ).use { menu ->
                    menu.open(owner)
                    val inventory = owner.openInventory.topInventory
                    inventory.size shouldBe 54
                    val cards = (0 until inventory.size).mapNotNull(inventory::getItem)
                        .filter { it.type == Material.BOOK }
                    cards.size shouldBe 1
                    plain(checkNotNull(cards.single().itemMeta.displayName())) shouldContain "Дом у реки"
                    cards.single().itemMeta.lore().orEmpty().joinToString(" | ", transform = ::plain) shouldContain "не хватает"

                    click(paper, owner, 10, ClickType.LEFT).isCancelled.shouldBeTrue()
                    teleports shouldBe listOf(active.projectId)
                    inspections shouldBe emptyList()

                    menu.open(owner)
                    click(paper, owner, 10, ClickType.RIGHT).isCancelled.shouldBeTrue()
                    inspections shouldBe listOf(active.projectId)
                }
            } finally {
                scope.close()
                ConfigManager.clear()
            }
        }
    }
})

private fun project(
    worldId: UUID,
    ownerId: UUID,
    title: String,
    state: BuilderConstructionProjectState,
): BuilderConstructionProjectRecord {
    val now = 1_800_000_000_000L
    val id = UUID.randomUUID()
    val book = amount(Material.BOOK)
    val stone = amount(Material.STONE, 12)
    val change = BuilderBlockChange(BuilderBlockPos(worldId, 10, 64, 10), "minecraft:dirt", "minecraft:stone")
    val plan = BuilderPlan(
        id = id,
        playerId = ownerId,
        kind = BuilderPlanKind.BUILD_BOOK,
        changes = listOf(change),
        costs = listOf(book, stone),
        rewards = emptyList(),
        createdAtMillis = now,
        expiresAtMillis = now + 60_000,
    )
    return BuilderConstructionProjectRecord(
        projectId = id,
        playerId = ownerId,
        playerName = "Builder",
        projectTitle = title,
        plan = plan,
        steps = listOf(BuilderConstructionStep(change, stone, null)),
        bookCost = book,
        state = state,
        cursor = 0,
        createdAtMillis = now,
        updatedAtMillis = now,
    ).validated()
}

private fun amount(material: Material, amount: Int = 1): BuilderItemAmount = BuilderItemAmount(
    itemBase64 = BuilderItemCodec.encodePrototype(ItemStack(material)),
    materialKey = material.key.toString(),
    amount = amount,
)

private fun click(
    paper: MockBukkitTestRuntime,
    player: org.bukkit.entity.Player,
    rawSlot: Int,
    type: ClickType,
): InventoryClickEvent = paper.callEvent(
    InventoryClickEvent(
        player.openInventory,
        InventoryType.SlotType.CONTAINER,
        rawSlot,
        type,
        InventoryAction.PICKUP_ALL,
    ),
)

private fun plain(component: net.kyori.adventure.text.Component): String =
    PlainTextComponentSerializer.plainText().serialize(component)
