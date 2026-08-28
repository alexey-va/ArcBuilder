package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.data.type.Leaves
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import ru.arc.config.Config
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.text.LocaleCatalog
import ru.arc.text.LocalizedMiniMessage
import java.nio.file.Files
import java.util.UUID

class BuilderCrownControllerTest : FunSpec({
    test("bundled crown guidance states survival cost and creative exemption") {
        val config = Config(Files.createTempDirectory("arc-builder-crown-guidance-"), "modules/builder-tools.yml")
        val russian = (
            config.stringList("locales.ru.crown-brush.lore") +
                config.stringList("locales.ru.crown.help")
        ).joinToString("\n").lowercase()
        val english = (
            config.stringList("locales.en.crown-brush.lore") +
                config.stringList("locales.en.crown.help")
        ).joinToString("\n").lowercase()

        russian shouldContain "в выживании"
        russian shouldContain "в творческом"
        english shouldContain "in survival"
        english shouldContain "in creative"
    }

    test("brush keeps its legacy identity and explicit non-italic presentation") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("BuilderCrownBrushTest")
            val player = paper.addPlayer("CrownBrushOwner")
            val harness = CrownHarness(plugin)
            harness.controller.use { controller ->
                player.inventory.setItemInMainHand(ItemStack(Material.BRUSH))

                controller.handle(player, listOf("wand"))

                val brush = player.inventory.itemInMainHand
                controller.isBrush(brush) shouldBe true
                PlainTextComponentSerializer.plainText().serialize(checkNotNull(brush.itemMeta.displayName())) shouldBe "Кисть крон"
                checkNotNull(brush.itemMeta.displayName()).decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
                checkNotNull(brush.itemMeta.lore()).map { it.decoration(TextDecoration.ITALIC) }
                    .shouldContainExactly(TextDecoration.State.FALSE, TextDecoration.State.FALSE)
            }
        }
    }

    test("settings are owned by the controller and invalidate an existing crown preview") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("BuilderCrownSettingsTest")
            val player = paper.addPlayer("CrownSettingsOwner")
            val harness = CrownHarness(plugin)
            harness.controller.use { controller ->
                controller.handle(player, listOf("radius", "7"))

                controller.settings(player.uniqueId).radius shouldBe 7
                harness.discardedPreviews shouldBe 1
                PlainTextComponentSerializer.plainText().serialize(checkNotNull(player.nextComponentMessage())) shouldBe
                    "Настройка: Радиус = 7"

                controller.clearPlayer(player.uniqueId)
                controller.settings(player.uniqueId) shouldBe BuilderCrownSettings()
                controller.anchor(player.uniqueId) shouldBe null
            }
        }
    }

    test("crown status renders player-facing localized setting values") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("BuilderCrownLocalizedStatusTest")
            val player = paper.addPlayer("CrownStatusOwner")
            val harness = CrownHarness(plugin)
            harness.controller.use { controller ->
                controller.handle(player, listOf("status"))

                val rendered = checkNotNull(player.nextComponentMessage())
                PlainTextComponentSerializer.plainText().serialize(rendered) shouldBe
                    "Параметры: естественная / 5 / естественная / естественный"
                PlainTextComponentSerializer.plainText().serialize(checkNotNull(player.nextComponentMessage())) shouldBe
                    "Палитра: Дубовые листья × 1"
            }
        }
    }

    test("brush previews on the outer face and confirms only that exact anchor") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("BuilderCrownInteractionTest")
            val world = paper.addSimpleWorld("crown-interaction")
            val player = paper.addPlayer("CrownInteractionOwner")
            player.teleport(Location(world, 0.5, 64.0, 2.5))
            val clicked = world.getBlockAt(0, 64, 0).also { it.type = Material.STONE }
            val harness = CrownHarness(plugin)
            harness.controller.use { controller ->
                val brush = controller.styleBrush(ItemStack(Material.BRUSH), player)

                paper.callEvent(interact(player, Action.LEFT_CLICK_BLOCK, brush, clicked, BlockFace.UP))

                controller.anchor(player.uniqueId) shouldBe BuilderBlockPos(world.uid, 0, 65, 0)
                harness.preparedPlans.size shouldBe 1
                harness.preparedPlans.single().kind shouldBe BuilderPlanKind.CROWN

                paper.callEvent(interact(player, Action.RIGHT_CLICK_BLOCK, brush, clicked, BlockFace.NORTH))
                harness.failures shouldContainExactly listOf("crown.same-face")
                harness.confirmedPlans shouldBe 0

                paper.callEvent(interact(player, Action.RIGHT_CLICK_BLOCK, brush, clicked, BlockFace.UP))
                harness.confirmedPlans shouldBe 1
            }
        }
    }

    test("creative crown plans are free while survival plans charge every leaf") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("BuilderCrownCostsTest")
            val world = paper.addSimpleWorld("crown-costs")
            val player = paper.addPlayer("CrownCostsOwner")
            player.teleport(Location(world, 0.5, 64.0, 2.5))
            val clicked = world.getBlockAt(0, 64, 0).also { it.type = Material.STONE }
            val harness = CrownHarness(plugin)
            harness.controller.use { controller ->
                val brush = controller.styleBrush(ItemStack(Material.BRUSH), player)

                player.gameMode = GameMode.CREATIVE
                paper.callEvent(interact(player, Action.LEFT_CLICK_BLOCK, brush, clicked, BlockFace.UP))
                val creative = harness.preparedPlans.single()
                creative.costs shouldBe emptyList()

                harness.preparedPlans.clear()
                player.gameMode = GameMode.SURVIVAL
                paper.callEvent(interact(player, Action.LEFT_CLICK_BLOCK, brush, clicked, BlockFace.UP))
                val survival = harness.preparedPlans.single()
                survival.costs.sumOf { it.amount } shouldBe survival.changes.size

                controller.close()
                harness.preparedPlans.clear()
                paper.callEvent(interact(player, Action.LEFT_CLICK_BLOCK, brush, clicked, BlockFace.UP))
                harness.preparedPlans shouldBe emptyList()
                controller.anchor(player.uniqueId) shouldBe null
            }
        }
    }
})

private class CrownHarness(plugin: Plugin) {
    val preparedPlans = mutableListOf<BuilderPlan>()
    val failures = mutableListOf<String>()
    var confirmedPlans = 0
    var discardedPreviews = 0

    private val selections = BuilderSelectionController(
        previewRadius = 32.0,
        previewSpacing = 0.75,
        maximumOutlinePoints = 512,
    )
    private val safety = BuilderBlockSafety(plugin, setOf("AIR", "SHORT_GRASS"))

    val controller = BuilderCrownController(
        plugin = plugin,
        messages = crownMessages(),
        safety = safety,
        selections = selections,
        maximumChanges = BuilderPlan.ABSOLUTE_MAX_CHANGES,
        host = object : BuilderCrownHost {
            override fun operationLocked(playerId: UUID): Boolean = false

            override fun ensureAvailable(player: org.bukkit.entity.Player) = Unit

            override fun ensurePermission(player: org.bukkit.entity.Player) = Unit

            override fun ensureMutable(player: org.bukkit.entity.Player, block: org.bukkit.block.Block) = Unit

            override fun placementData(material: Material) = material.createBlockData().also { data ->
                if (data is Leaves) data.isPersistent = true
            }

            override fun materialLabel(player: org.bukkit.entity.Player, material: Material): Component =
                Component.text(if (material == Material.OAK_LEAVES) "Дубовые листья" else material.name)

            override fun setFirstPosition(player: org.bukkit.entity.Player, location: Location) {
                selections.set(
                    player.uniqueId,
                    BuilderBlockPos(player.world.uid, location.blockX, location.blockY, location.blockZ),
                    first = true,
                )
            }

            override fun createPlan(
                player: org.bukkit.entity.Player,
                changes: List<BuilderBlockChange>,
                costs: List<BuilderItemAmount>,
            ): BuilderPlan {
                val now = System.currentTimeMillis()
                return BuilderPlan(
                    id = UUID.randomUUID(),
                    playerId = player.uniqueId,
                    kind = BuilderPlanKind.CROWN,
                    changes = changes,
                    costs = costs,
                    rewards = emptyList(),
                    createdAtMillis = now,
                    expiresAtMillis = now + 30_000L,
                ).validated()
            }

            override fun preparePlan(player: org.bukkit.entity.Player, plan: BuilderPlan) {
                preparedPlans += plan
            }

            override fun confirmPlan(player: org.bukkit.entity.Player) {
                confirmedPlans++
            }

            override fun prepareUndo(player: org.bukkit.entity.Player) = Unit

            override fun cancelPlan(player: org.bukkit.entity.Player) = Unit

            override fun showPlanStatus(player: org.bukkit.entity.Player) = Unit

            override fun discardPendingCrown(playerId: UUID) {
                discardedPreviews++
            }

            override fun runEventAction(player: org.bukkit.entity.Player, action: () -> Unit) {
                try {
                    action()
                } catch (failure: CrownFailure) {
                    failures += failure.path
                }
            }

            override fun fail(path: String, values: Map<String, Component>): Nothing = throw CrownFailure(path)
        },
    )
}

private class CrownFailure(val path: String) : RuntimeException(path)

private fun interact(
    player: org.bukkit.entity.Player,
    action: Action,
    item: ItemStack,
    block: org.bukkit.block.Block,
    face: BlockFace,
): PlayerInteractEvent = PlayerInteractEvent(player, action, item, block, face, EquipmentSlot.HAND)

private fun crownMessages(): LocalizedMiniMessage = LocalizedMiniMessage(
    catalogs = mapOf(
        "ru" to object : LocaleCatalog {
            private val scalars = mapOf(
                "prefix" to "",
                "crown-brush.name" to "Кисть крон",
                "crown-brush.received" to "Кисть готова",
                "crown.settings-updated" to "Настройка: <setting> = <value>",
                "crown.palette-updated" to "Палитра обновлена",
                "crown.palette-row" to "Палитра: <material> × <weight>",
                "crown.labels.settings.shape" to "Форма",
                "crown.labels.settings.radius" to "Радиус",
                "crown.labels.settings.density" to "Густота",
                "crown.labels.settings.noise" to "Край",
                "crown.labels.shape.natural" to "естественная",
                "crown.labels.shape.round" to "округлая",
                "crown.labels.shape.wide" to "раскидистая",
                "crown.labels.shape.tall" to "вытянутая",
                "crown.labels.density.airy" to "воздушная",
                "crown.labels.density.natural" to "естественная",
                "crown.labels.density.dense" to "густая",
                "crown.labels.noise.smooth" to "мягкий",
                "crown.labels.noise.natural" to "естественный",
                "crown.labels.noise.wild" to "сильный",
            )
            private val lists = mapOf(
                "crown-brush.lore" to listOf("ЛКМ — превью", "ПКМ — подтвердить"),
                "crown.help" to listOf("Настройте крону"),
                "crown.status" to listOf("Параметры: <shape> / <radius> / <density> / <noise>"),
            )

            override fun scalar(path: String): String? = scalars[path]

            override fun lines(path: String): List<String>? = lists[path]
        },
    ),
    defaultLocale = { "ru" },
)
