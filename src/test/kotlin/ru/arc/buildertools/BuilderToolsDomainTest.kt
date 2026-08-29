package ru.arc.buildertools

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.type.Candle
import org.bukkit.block.data.Waterlogged
import org.bukkit.block.data.type.Slab
import org.bukkit.block.data.type.TrapDoor
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginDescriptionFile
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import ru.arc.config.Config
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.playerstate.PaperPlayerStateEnvelope
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.util.UUID

class BuilderToolsDomainTest : FunSpec({
    val worldId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    val playerId = UUID.fromString("22222222-2222-2222-2222-222222222222")

    test("bundled config is disabled while a runtime override may opt in survival") {
        val temporaryDirectory = Files.createTempDirectory("arc-builder-tools-config-")
        val base = Config(temporaryDirectory, "modules/builder-tools.yml")
        BuilderToolsConfig(base).enabled shouldBe false

        val override = Config(temporaryDirectory, "modules/builder-tools-runtime.yml")
        override.setBoolean("enabled", true)
        override.setStringList("allowed-worlds", listOf("world"))
        val configured = BuilderToolsConfig(base, override).validated()
        configured.enabled shouldBe true
        configured.allowedWorlds shouldBe setOf("world")
        configured.shopMaxQuotedMaterials shouldBe 64
        configured.allowsWorld("WORLD") shouldBe true
        configured.allowsWorld("world_nether") shouldBe false

        override.setStringList("allowed-worlds", listOf("*"))
        val wildcard = BuilderToolsConfig(base, override).validated()
        wildcard.allowedWorlds shouldBe setOf("*")
        wildcard.previewPeriodTicks shouldBe 10L
        wildcard.previewRadius shouldBe 32.0
        wildcard.allowsWorld("world") shouldBe true
        wildcard.allowsWorld("rc_survival_nether") shouldBe true
        wildcard.allowsWorld("resource-end") shouldBe true

        override.setStringList("allowed-worlds", listOf("*", "world"))
        shouldThrow<IllegalArgumentException> { BuilderToolsConfig(base, override).validated() }
    }

    test("runtime override owns survival book contracts and their MySQL connection") {
        val temporaryDirectory = Files.createTempDirectory("arc-builder-books-config-")
        val base = Config(temporaryDirectory, "modules/builder-tools.yml")
        val override = Config(temporaryDirectory, "modules/builder-tools-runtime.yml")
        override.setBoolean("enabled", true)
        override.setStringList("allowed-worlds", listOf("*"))
        override.setBoolean("book-contracts.enabled", true)
        override.setString("book-contracts.construction-markup-percent", "22.50")
        override.setString("book-contracts.max-issue-price", "12345.67")
        override.setBoolean("book-contracts.mysql.enabled", true)
        override.setString("book-contracts.mysql.host", "db.internal")
        override.setInt("book-contracts.mysql.port", 3307)
        override.setString("book-contracts.mysql.database", "builder_contracts")
        override.setString("book-contracts.mysql.username", "builder")
        override.setString("book-contracts.mysql.password", "test-password")
        override.setString("book-contracts.mysql.ssl-mode", "DISABLED")
        override.setInt("book-contracts.mysql.pool.maximum-size", 3)

        val configured = BuilderToolsConfig(base, override).validated()
        val sql = configured.bookSqlConfig()

        configured.bookContractsEnabled shouldBe true
        configured.bookConstructionMarkupBasisPoints shouldBe 2_250
        configured.bookMaxIssuePriceMinor shouldBe 1_234_567
        sql.enabled shouldBe true
        sql.host shouldBe "db.internal"
        sql.port shouldBe 3307
        sql.database shouldBe "builder_contracts"
        sql.username shouldBe "builder"
        sql.password shouldBe "test-password"
        sql.maximumPoolSize shouldBe 3
    }

    test("clipboard capacity cannot exceed one operation capacity") {
        val temporaryDirectory = Files.createTempDirectory("arc-builder-clipboard-config-")
        val base = Config(temporaryDirectory, "modules/builder-tools.yml")
        val override = Config(temporaryDirectory, "modules/builder-tools-runtime.yml")
        override.setBoolean("enabled", true)
        override.setStringList("allowed-worlds", listOf("*"))
        base.setInt("limits.max-changes", 10)
        base.setInt("limits.max-clipboard-blocks", 11)

        shouldThrow<IllegalArgumentException> { BuilderToolsConfig(base, override).validated() }

        base.setInt("limits.max-clipboard-blocks", 10)
        BuilderToolsConfig(base, override).validated().maxClipboardBlocks shouldBe 10
    }

    test("bundled policy supports survival and creative without opening spectator modes") {
        BuilderGameModePolicy.allows(GameMode.SURVIVAL) shouldBe true
        BuilderGameModePolicy.allows(GameMode.CREATIVE) shouldBe true
        BuilderGameModePolicy.allows(GameMode.ADVENTURE) shouldBe false
        BuilderGameModePolicy.allows(GameMode.SPECTATOR) shouldBe false
        BuilderGameModePolicy.usesInventory(GameMode.SURVIVAL) shouldBe true
        BuilderGameModePolicy.usesInventory(GameMode.CREATIVE) shouldBe false
    }

    test("every builder plan kind requires a localized label") {
        val temporaryDirectory = Files.createTempDirectory("arc-builder-tools-locales-")
        val config = BuilderToolsConfig(Config(temporaryDirectory, "modules/builder-tools.yml"))
        val bundled = Config(temporaryDirectory, "modules/builder-tools.yml")
        val missing = bundled.keys("locales").flatMap { locale ->
            BuilderPlanKind.entries.mapNotNull { kind ->
                val path = "locales.$locale.kinds.${kind.name.lowercase()}"
                path.takeIf { bundled.stringOrNull(path).isNullOrBlank() }
            }
        }

        config.validated()
        missing shouldBe emptyList()
    }

    test("player-only command rejection is localized through the validated catalog") {
        val temporaryDirectory = Files.createTempDirectory("arc-builder-tools-command-locale-")
        val base = Config(temporaryDirectory, "modules/builder-tools.yml")
        val runtimeOverride = Config(temporaryDirectory, "modules/builder-tools-runtime.yml").apply {
            setBoolean("enabled", true)
            setStringList("allowed-worlds", listOf("*"))
        }
        val config = BuilderToolsConfig(base, runtimeOverride).validated()
        val messages = config.messages()
        val plain = PlainTextComponentSerializer.plainText()

        plain.serialize(messages.render("errors.player-only", "ru-RU")) shouldBe
            "🛠 Команда доступна только игрокам."
        plain.serialize(messages.render("errors.player-only", "en-US")) shouldBe
            "🛠 This command is available only to players."
    }

    test("pending plans bind their confirmation game mode atomically") {
        val now = 1_800_000_000_000L
        val plan = BuilderPlan(
            id = UUID.fromString("33333333-3333-3333-3333-333333333334"),
            playerId = playerId,
            kind = BuilderPlanKind.BUILD_BOOK,
            changes = listOf(
                BuilderBlockChange(
                    BuilderBlockPos(worldId, 4, 70, 8),
                    "minecraft:air",
                    "minecraft:stone",
                ),
            ),
            costs = emptyList(),
            rewards = emptyList(),
            createdAtMillis = now,
            expiresAtMillis = now + 30_000,
        ).validated()

        val pending = BuilderPendingPlan(plan, GameMode.SURVIVAL)
        pending.plan shouldBe plan
        pending.gameMode shouldBe GameMode.SURVIVAL
    }

    test("registered build-book plans require the complete authoritative identity") {
        val now = 1_800_000_000_000L
        val base = BuilderPlan(
            id = UUID.randomUUID(),
            playerId = playerId,
            kind = BuilderPlanKind.BUILD_BOOK,
            changes = listOf(
                BuilderBlockChange(
                    BuilderBlockPos(worldId, 4, 70, 8),
                    "minecraft:air",
                    "minecraft:stone",
                ),
            ),
            costs = emptyList(),
            rewards = emptyList(),
            createdAtMillis = now,
            expiresAtMillis = now + 30_000,
        )

        shouldThrow<IllegalArgumentException> {
            base.copy(bookInstanceId = UUID.randomUUID()).validated()
        }
        base.copy(
            bookBlueprintId = UUID.randomUUID(),
            bookInstanceId = UUID.randomUUID(),
            bookInstanceGeneration = 1,
            bookBuildingId = "player-0123456789abcdef0123456789abcdef-book.schem",
            bookSchematicSha256 = "a".repeat(64),
        ).validated().kind shouldBe BuilderPlanKind.BUILD_BOOK
    }

    test("builder item presentation explicitly disables vanilla italic styling") {
        MockBukkitTestRuntime.open().use {
            val item = ItemStack(Material.ECHO_SHARD)
            item.editMeta { meta ->
                BuilderItemPresentation.apply(
                    meta,
                    Component.text("Инструмент строителя"),
                    listOf(Component.text("Первая точка"), Component.empty()),
                )
            }
            val meta = checkNotNull(item.itemMeta)
            checkNotNull(meta.displayName()).decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
            checkNotNull(meta.lore()).all {
                it.decoration(TextDecoration.ITALIC) == TextDecoration.State.FALSE
            } shouldBe true
        }
    }

    test("preview loop refreshes continuously and is cancelled with its lifecycle") {
        val scheduler = TestTaskScheduler()
        val scope = LifecycleTaskScope(scheduler)
        var renders = 0
        BuilderPreviewLoop(scope, 10L) { renders++ }

        scheduler.tick(1)
        renders shouldBe 1
        scheduler.tick(9)
        renders shouldBe 1
        scheduler.tick(1)
        renders shouldBe 2
        scope.close()
        scheduler.tick(20)
        renders shouldBe 2
    }

    test("preview sessions own refresh failure throttling exact expiry and cleanup") {
        val scheduler = TestTaskScheduler()
        val scope = LifecycleTaskScope(scheduler)
        val player = mock<org.bukkit.entity.Player>()
        whenever(player.uniqueId).thenReturn(playerId)
        val plan = BuilderPlan(
            id = UUID.fromString("33333333-3333-3333-3333-333333333335"),
            playerId = playerId,
            kind = BuilderPlanKind.FILL,
            changes = listOf(
                BuilderBlockChange(
                    BuilderBlockPos(worldId, 1, 64, 1),
                    "minecraft:air",
                    "minecraft:stone",
                ),
            ),
            costs = listOf(BuilderItemAmount("AAAA", "minecraft:stone", 1)),
            rewards = emptyList(),
            createdAtMillis = 1L,
            expiresAtMillis = 1_000L,
        ).validated()
        val pending = BuilderPendingPlan(plan, GameMode.SURVIVAL)
        var failRendering = true
        var planRenders = 0
        var selectionRenders = 0
        var failures = 0
        val expired = mutableListOf<UUID>()
        val sessions = BuilderPreviewSessions(
            taskScope = scope,
            periodTicks = 2L,
            onlinePlayers = { listOf(player) },
            canRender = { true },
            renderSelection = { selectionRenders++ },
            renderPlan = { _, _ ->
                if (failRendering) error("preview unavailable")
                planRenders++
            },
            clearPlan = {},
            onExpired = expired::add,
            onRenderFailure = { _, _ -> failures++ },
            nowMillis = { 0L },
        )

        sessions.open(player, pending, expireAfterTicks = 10L, showImmediately = false)
        sessions[playerId] shouldBe pending
        scheduler.tick(1)
        failures shouldBe 1
        scheduler.tick(2)
        failures shouldBe 1

        failRendering = false
        scheduler.tick(2)
        planRenders shouldBe 1
        selectionRenders shouldBe 1
        failRendering = true
        scheduler.tick(2)
        failures shouldBe 2

        failRendering = false
        scheduler.tick(3)
        sessions[playerId] shouldBe null
        expired shouldBe listOf(playerId)
        sessions.close()
        val rendersAtClose = planRenders + selectionRenders
        scheduler.tick(20)
        planRenders + selectionRenders shouldBe rendersAtClose
        scope.close()
    }

    test("an older preview expiry cannot discard its replacement") {
        val scheduler = TestTaskScheduler()
        val scope = LifecycleTaskScope(scheduler)
        val player = mock<org.bukkit.entity.Player>()
        whenever(player.uniqueId).thenReturn(playerId)
        fun pending(id: String) = BuilderPendingPlan(
            BuilderPlan(
                id = UUID.fromString(id),
                playerId = playerId,
                kind = BuilderPlanKind.DECONSTRUCT,
                changes = emptyList(),
                costs = emptyList(),
                rewards = emptyList(),
                createdAtMillis = 0L,
                expiresAtMillis = 1_000L,
            ),
            GameMode.CREATIVE,
        )
        val first = pending("33333333-3333-3333-3333-333333333336")
        val replacement = pending("33333333-3333-3333-3333-333333333337")
        val expired = mutableListOf<UUID>()
        val sessions = BuilderPreviewSessions(
            taskScope = scope,
            periodTicks = 20L,
            onlinePlayers = { emptyList() },
            canRender = { true },
            renderSelection = {},
            renderPlan = { _, _ -> },
            clearPlan = {},
            onExpired = expired::add,
            onRenderFailure = { _, failure -> throw failure },
        )

        sessions.open(player, first, expireAfterTicks = 5L, showImmediately = false)
        scheduler.tick(2)
        sessions.open(player, replacement, expireAfterTicks = 10L, showImmediately = false)
        scheduler.tick(3)
        sessions[playerId] shouldBe replacement
        expired shouldBe emptyList()
        sessions.close()
        scheduler.tick(20)
        sessions[playerId] shouldBe null
        expired shouldBe emptyList()
        scope.close()
    }

    test("selection preview is clipped around the viewer and remains bounded") {
        val selection = BuilderSelection(
            BuilderBlockPos(worldId, -100, 60, -100),
            BuilderBlockPos(worldId, 100, 100, 100),
        )
        val points = BuilderSelectionPreviewGeometry.visibleOutline(
            selection = selection,
            viewerX = -100.0,
            viewerY = 60.0,
            viewerZ = -100.0,
            radius = 32.0,
            spacing = 0.75,
            maximumPoints = 128,
        )

        points.isNotEmpty() shouldBe true
        (points.size <= 128) shouldBe true
        points.all { point ->
            val dx = point.x + 100.0
            val dy = point.y - 60.0
            val dz = point.z + 100.0
            dx * dx + dy * dy + dz * dz <= 32.0 * 32.0
        } shouldBe true
    }

    test("plugin descriptor exposes only the unified builder command root") {
        val pluginDescriptor = checkNotNull(javaClass.classLoader.getResourceAsStream("plugin.yml"))
            .bufferedReader()
            .use { it.readText() }

        pluginDescriptor.contains("\n  builder:\n") shouldBe true
        pluginDescriptor.contains("\n  deconstruction:\n") shouldBe false
        pluginDescriptor.contains("\n  crown:\n") shouldBe false
        pluginDescriptor.contains("aliases: [buildtools]") shouldBe false
    }

    test("bundled builder safety is Lands-first and has no WorldGuard requirement") {
        val bundled = checkNotNull(javaClass.classLoader.getResourceAsStream("modules/builder-tools.yml"))
            .bufferedReader()
            .use { it.readText() }

        bundled.contains("require-lands: true") shouldBe false
        bundled.contains("require-worldguard") shouldBe false
        bundled.contains("allowed by WorldGuard") shouldBe false
        bundled.contains("разрешена WorldGuard") shouldBe false
    }

    test("bundled builder messages isolate the full-color builder mark and keep continuation indentation") {
        val tools = checkNotNull(javaClass.classLoader.getResourceAsStream("modules/builder-tools.yml"))
            .bufferedReader()
            .use { it.readText() }
        val books = checkNotNull(javaClass.classLoader.getResourceAsStream("modules/auto-build.yml"))
            .bufferedReader()
            .use { it.readText() }

        tools.lines().count { it.trim() == "prefix: \"<white>🛠</white> \"" } shouldBe 2
        books.contains("<white>🛠</white> ") shouldBe true
        tools.contains("<#8c8c8c>   <#d48763>/builder wand") shouldBe true
        books.contains("<#8c8c8c>   ПКМ по блоку") shouldBe true
        listOf(tools, books).forEach { bundled ->
            bundled.contains("#92bed8", ignoreCase = true) shouldBe false
            bundled.contains("#d48763", ignoreCase = true) shouldBe true
            bundled.contains("◇") shouldBe false
            bundled.contains("•") shouldBe false
        }
    }

    test("permission policy accepts canonical feature and build-book entry nodes") {
        fun permissions(vararg nodes: String): (String) -> Boolean = nodes.toSet()::contains

        BuilderPermissionPolicy.canUse(BuilderFeature.FILL, permissions("arcbuild.fill")) shouldBe true
        BuilderPermissionPolicy.canUse(BuilderFeature.REPLACE, permissions("arcbuild.replace")) shouldBe true
        BuilderPermissionPolicy.canUse(BuilderFeature.COPY, permissions("arcbuild.copy")) shouldBe true
        BuilderPermissionPolicy.canUse(BuilderFeature.FILL, permissions("arc.builder.tools.fill")) shouldBe false
        BuilderPermissionPolicy.canUse(BuilderFeature.PASTE, permissions("arc.buildertools.paste")) shouldBe false
        BuilderPermissionPolicy.canUse(BuilderFeature.CROWN, permissions("arc.crown")) shouldBe false
        BuilderPermissionPolicy.canUseAny(permissions("arcbuild.deconstruct")) shouldBe true
        BuilderPermissionPolicy.canUseAny(permissions("arcbuild.book.create")) shouldBe true
        BuilderPermissionPolicy.canUseAny(permissions("arcbuild.book.sell")) shouldBe true
        BuilderPermissionPolicy.canUseAny(permissions("arcbuild.book.use")) shouldBe true
        BuilderPermissionPolicy.canUseAny(permissions("arc.build.book.create")) shouldBe false
        BuilderPermissionPolicy.canUseAny(permissions()) shouldBe false
    }

    test("build-book create permission grants the declared sell and use children") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("BuilderPermissionContract")
            val description = checkNotNull(javaClass.classLoader.getResourceAsStream("plugin.yml"))
                .use(::PluginDescriptionFile)
            val required = setOf("arcbuild.book.create", "arcbuild.book.sell", "arcbuild.book.use")
            description.permissions
                .filter { it.name in required }
                .sortedBy { it.name == "arcbuild.book.create" }
                .forEach(paper.server.pluginManager::addPermission)
            val player = paper.addPlayer("BookSeller")

            player.addAttachment(plugin, "arcbuild.book.create", true)
            player.recalculatePermissions()

            player.hasPermission("arcbuild.book.sell") shouldBe true
            player.hasPermission("arcbuild.book.use") shouldBe true
        }
    }

    test("permission policy applies canonical selection and hourly tiers under absolute bounds") {
        fun permissions(vararg nodes: String): (String) -> Boolean = nodes.toSet()::contains

        BuilderPermissionPolicy.maximumAxis(permissions("arcbuild.selection.size.100"), 48) shouldBe 48
        BuilderPermissionPolicy.maximumAxis(permissions("arcbuild.selection.size.40"), 48) shouldBe 40
        BuilderPermissionPolicy.maximumAxis(permissions(), 48) shouldBe 20
        BuilderPermissionPolicy.hourlyChanges(permissions("arcbuild.hourly.150000"), 20_000) shouldBe 150_000
        BuilderPermissionPolicy.hourlyChanges(permissions("arcbuild.hourly.50000"), 20_000) shouldBe 50_000
        BuilderPermissionPolicy.hourlyChanges(permissions(), 20_000) shouldBe 20_000
    }

    test("builder tool exchange transforms one owned item without minting its base material") {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.server.addPlayer("ToolOwner")
            player.inventory.setItemInMainHand(ItemStack(Material.ECHO_SHARD, 3))
            val replacement = ItemStack(Material.ECHO_SHARD).also { item ->
                item.editMeta { meta -> meta.setCustomModelData(1) }
            }

            BuilderOwnedToolExchange.replaceOnePlainHeld(player, Material.ECHO_SHARD, replacement) shouldBe
                BuilderOwnedToolExchangeResult.REPLACED
            player.inventory.contents.filterNotNull().sumOf { item ->
                if (item.type == Material.ECHO_SHARD) item.amount else 0
            } shouldBe 3
            player.inventory.contents.filterNotNull().count(replacement::isSimilar) shouldBe 1
        }
    }

    test("builder tool exchange rejects custom inputs and a full split inventory without mutation") {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.server.addPlayer("ToolGuard")
            val customInput = ItemStack(Material.ECHO_SHARD).also { item ->
                item.editMeta { meta -> meta.setCustomModelData(9) }
            }
            player.inventory.setItemInMainHand(customInput)
            val replacement = ItemStack(Material.ECHO_SHARD).also { item ->
                item.editMeta { meta -> meta.setCustomModelData(1) }
            }
            BuilderOwnedToolExchange.replaceOnePlainHeld(player, Material.ECHO_SHARD, replacement) shouldBe
                BuilderOwnedToolExchangeResult.WRONG_ITEM
            player.inventory.itemInMainHand shouldBe customInput

            player.inventory.contents.indices.forEach { index -> player.inventory.setItem(index, ItemStack(Material.STONE)) }
            player.inventory.setItemInMainHand(ItemStack(Material.ECHO_SHARD, 2))
            BuilderOwnedToolExchange.replaceOnePlainHeld(player, Material.ECHO_SHARD, replacement) shouldBe
                BuilderOwnedToolExchangeResult.INVENTORY_FULL
            player.inventory.itemInMainHand.amount shouldBe 2
        }
    }

    test("inventory procurement computes only exact deficits and simulates delivery before construction") {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.server.addPlayer("ShopBuilder")
            player.inventory.addItem(ItemStack(Material.STONE, 20))
            val costs = BuilderItemCodec.aggregate(listOf(ItemStack(Material.STONE, 64), ItemStack(Material.OAK_PLANKS, 16)))

            BuilderInventory.missingCosts(player, costs).map { it.materialKey to it.amount } shouldBe listOf(
                "minecraft:stone" to 44,
                "minecraft:oak_planks" to 16,
            )
            BuilderInventory.canApply(player, costs, emptyList(), null, 0) shouldBe false
            BuilderInventory.canApplyAfterReceiving(
                player,
                BuilderInventory.missingCosts(player, costs),
                costs,
                emptyList(),
                null,
                0,
            ) shouldBe true
            costs.all { BuilderInventory.plainMaterial(it) != null } shouldBe true
        }
    }

    test("block safety admits ordinary structure blocks and rejects technical or unstable blocks") {
        MockBukkitTestRuntime.open().use { paper ->
            val safety = BuilderBlockSafety(mock<Plugin>(), setOf("AIR", "SHORT_GRASS"))
            safety.isSafeMaterial(Material.STONE) shouldBe true
            safety.isSafeMaterial(Material.OAK_STAIRS) shouldBe true
            safety.isSafeMaterial(Material.OAK_LEAVES) shouldBe true
            safety.isSafeMaterial(Material.OAK_DOOR) shouldBe true
            safety.isSafeMaterial(Material.OAK_TRAPDOOR) shouldBe true
            safety.isSafeMaterial(Material.WHITE_CARPET) shouldBe true
            safety.isSafeMaterial(Material.LADDER) shouldBe true
            safety.isSafeMaterial(Material.SCAFFOLDING) shouldBe true
            safety.isSafeMaterial(Material.CANDLE) shouldBe true
            safety.isSafeMaterial(Material.TORCH) shouldBe true
            safety.isSafeMaterial(Material.WALL_TORCH) shouldBe true
            safety.isSafeMaterial(Material.POINTED_DRIPSTONE) shouldBe true
            safety.isSafeMaterial(Material.SEA_PICKLE) shouldBe true
            safety.isSafeMaterial(Material.LILY_PAD) shouldBe true
            safety.isSafeMaterial(Material.TNT) shouldBe false
            safety.isSafeMaterial(Material.SAND) shouldBe true
            safety.isSafeMaterial(Material.BROWN_CONCRETE_POWDER) shouldBe true
            safety.isSafeMaterial(Material.BEDROCK) shouldBe false
            safety.isSafeMaterial(Material.REDSTONE_TORCH) shouldBe false
            safety.isSafeMaterial(Material.REDSTONE_WALL_TORCH) shouldBe false
            safety.isSafeMaterial(Material.PISTON) shouldBe false
            safety.isSafeMaterial(Material.HOPPER) shouldBe false
            safety.isSafeMaterial(Material.CHEST) shouldBe false

            val waterlogged = paper.server.createBlockData(Material.OAK_STAIRS) as Waterlogged
            waterlogged.isWaterlogged = true
            safety.isSafePlacement(waterlogged) shouldBe false

            val doubleSlab = paper.server.createBlockData(Material.OAK_SLAB) as Slab
            doubleSlab.type = Slab.Type.DOUBLE
            BuilderPlacementCost.itemOrNull(doubleSlab)?.amount shouldBe 2

            val lowerDoor = paper.server.createBlockData(Material.OAK_DOOR) as Bisected
            lowerDoor.half = Bisected.Half.BOTTOM
            BuilderPlacementCost.itemOrNull(lowerDoor)?.let { it.type to it.amount } shouldBe (Material.OAK_DOOR to 1)
            lowerDoor.half = Bisected.Half.TOP
            BuilderPlacementCost.itemOrNull(lowerDoor) shouldBe null

            val topTrapdoor = paper.server.createBlockData(Material.OAK_TRAPDOOR) as TrapDoor
            topTrapdoor.half = Bisected.Half.TOP
            BuilderPlacementCost.itemOrNull(topTrapdoor)?.amount shouldBe 1

            val candles = paper.server.createBlockData(Material.CANDLE) as Candle
            candles.candles = 4
            BuilderPlacementCost.itemOrNull(candles)?.amount shouldBe 4
        }
    }

    test("selection has overflow-safe inclusive dimensions and deterministic order") {
        val selection = BuilderSelection(
            BuilderBlockPos(worldId, 3, 7, -2),
            BuilderBlockPos(worldId, 1, 6, 1),
        ).validated(maxAxis = 8, maxScanVolume = 100)

        selection.sizeX shouldBe 3
        selection.sizeY shouldBe 2
        selection.sizeZ shouldBe 4
        selection.volume shouldBe 24L
        selection.positionsTopDown().first() shouldBe BuilderBlockPos(worldId, 1, 7, -2)
        selection.positionsBottomUp().first() shouldBe BuilderBlockPos(worldId, 1, 6, -2)
    }

    test("selection rejects axis and scan-volume abuse independently") {
        val selection = BuilderSelection(
            BuilderBlockPos(worldId, 0, 0, 0),
            BuilderBlockPos(worldId, 20, 20, 20),
        )
        shouldThrow<IllegalArgumentException> { selection.validated(maxAxis = 20, maxScanVolume = 100_000) }
        shouldThrow<IllegalArgumentException> { selection.validated(maxAxis = 32, maxScanVolume = 9_000) }
    }

    test("crown geometry is deterministic bounded and seed-sensitive") {
        val first = BuilderCrownGeometry.offsets(radius = 5, seed = 42L)
        val repeated = BuilderCrownGeometry.offsets(radius = 5, seed = 42L)
        val other = BuilderCrownGeometry.offsets(radius = 5, seed = 43L)

        first shouldBe repeated
        first shouldNotBe other
        first.isNotEmpty() shouldBe true
        first.all { (x, y, z) -> x in -5..5 && y in -5..5 && z in -5..5 } shouldBe true
        first.size shouldBe first.toSet().size
    }

    test("crown palette parser preserves exact bounded weights") {
        BuilderCrownPaletteParser.parse("oak_leaves90%,birch_leaves10%") shouldBe listOf(
            BuilderCrownPaletteEntry("oak_leaves", 90),
            BuilderCrownPaletteEntry("birch_leaves", 10),
        )
        BuilderCrownPaletteParser.parse("oak_leaves,birch_leaves") shouldBe listOf(
            BuilderCrownPaletteEntry("oak_leaves", 1),
            BuilderCrownPaletteEntry("birch_leaves", 1),
        )
        shouldThrow<IllegalArgumentException> { BuilderCrownPaletteParser.parse("oak_leaves80%,birch_leaves10%") }
        shouldThrow<IllegalArgumentException> { BuilderCrownPaletteParser.parse("oak_leaves,oak_leaves") }
        shouldThrow<IllegalArgumentException> { BuilderCrownPaletteParser.parse("oak_leaves50%,birch_leaves") }
    }

    test("crown palette assignment is deterministic and approximately weighted") {
        val settings = BuilderCrownSettings(
            palette = listOf(
                BuilderCrownPaletteEntry("oak_leaves", 80),
                BuilderCrownPaletteEntry("birch_leaves", 20),
            ),
        ).validated()
        val first = (0 until 2_000).map { index -> settings.materialAt(index, index % 17, index % 31, 99L) }
        val repeated = (0 until 2_000).map { index -> settings.materialAt(index, index % 17, index % 31, 99L) }
        first shouldBe repeated
        (first.count { it == "oak_leaves" } in 1_500..1_700) shouldBe true
    }

    test("crown settings produce distinct bounded shapes and monotonic density") {
        val wide = BuilderCrownGeometry.offsets(BuilderCrownSettings(shape = BuilderCrownShape.WIDE), 73L)
        val tall = BuilderCrownGeometry.offsets(BuilderCrownSettings(shape = BuilderCrownShape.TALL), 73L)
        val wideX = wide.maxOf { kotlin.math.abs(it.first) }
        val wideY = wide.maxOf { kotlin.math.abs(it.second) }
        val tallX = tall.maxOf { kotlin.math.abs(it.first) }
        val tallY = tall.maxOf { kotlin.math.abs(it.second) }
        (wideX > wideY) shouldBe true
        (tallY > tallX) shouldBe true

        val airy = BuilderCrownGeometry.offsets(BuilderCrownSettings(density = BuilderCrownDensity.AIRY), 73L)
        val natural = BuilderCrownGeometry.offsets(BuilderCrownSettings(density = BuilderCrownDensity.NATURAL), 73L)
        val dense = BuilderCrownGeometry.offsets(BuilderCrownSettings(density = BuilderCrownDensity.DENSE), 73L)
        (airy.size <= natural.size) shouldBe true
        (natural.size <= dense.size) shouldBe true
        dense.all { (x, y, z) -> x in -6..6 && y in -6..6 && z in -6..6 } shouldBe true
    }

    test("crown sessions keep a stable preview seed and advance only on reroll") {
        val sessions = BuilderCrownSessions()
        val center = BuilderBlockPos(worldId, 12, 80, -7)
        val settings = BuilderCrownSettings(shape = BuilderCrownShape.ROUND)
        sessions.update(playerId, settings)
        val first = sessions.seed(playerId, center, settings, reroll = false)
        sessions.seed(playerId, center, settings, reroll = false) shouldBe first
        sessions.seed(playerId, center, settings, reroll = true) shouldNotBe first
        sessions.clear(playerId)
        sessions.settings(playerId) shouldBe BuilderCrownSettings()
    }

    test("journal enforces plan identity phase and undo linkage") {
        val now = 1_800_000_000_000L
        val operationId = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val change = BuilderBlockChange(
            BuilderBlockPos(worldId, 4, 70, 8),
            "minecraft:stone",
            "minecraft:air",
        )
        val plan = BuilderPlan(
            id = operationId,
            playerId = playerId,
            kind = BuilderPlanKind.DECONSTRUCT,
            changes = listOf(change),
            costs = emptyList(),
            rewards = emptyList(),
            createdAtMillis = now,
            expiresAtMillis = now + 30_000,
        ).validated()
        val prepared = BuilderJournalRecord(
            operationId = operationId,
            playerId = playerId,
            playerName = "Builder_1",
            phase = BuilderJournalPhase.PREPARED,
            plan = plan,
            inventoryBefore = PaperPlayerStateEnvelope(payloadBase64 = "AAAA", sha256 = "a".repeat(64)),
            createdAtMillis = now,
            updatedAtMillis = now,
        ).validated()

        val applying = prepared.copy(phase = BuilderJournalPhase.APPLYING, updatedAtMillis = now + 1)
        val committed = applying.copy(
            phase = BuilderJournalPhase.COMMITTED,
            updatedAtMillis = now + 2,
            committedAtMillis = now + 2,
        )
        BuilderJournalTransitionRules.classify(applying, committed, committed) shouldBe
            BuilderJournalReconciliation.TARGET_COMMITTED
        BuilderJournalTransitionRules.classify(applying, committed, applying) shouldBe
            BuilderJournalReconciliation.PREDECESSOR_CONFIRMED
        BuilderJournalTransitionRules.classify(applying, committed, null) shouldBe
            BuilderJournalReconciliation.UNKNOWN

        shouldThrow<IllegalArgumentException> { plan.copy(kind = BuilderPlanKind.UNDO).validated() }
        shouldThrow<IllegalArgumentException> {
            BuilderJournalRecord(
                operationId = operationId,
                playerId = playerId,
                playerName = "Builder_1",
                phase = BuilderJournalPhase.COMMITTED,
                plan = plan,
                inventoryBefore = PaperPlayerStateEnvelope(payloadBase64 = "AAAA", sha256 = "a".repeat(64)),
                createdAtMillis = now,
                updatedAtMillis = now,
                committedAtMillis = null,
            ).validated()
        }
    }

    test("undo reverses ordinary materials without reissuing a consumed build book") {
        val now = 1_800_000_000_000L
        val change = BuilderBlockChange(
            BuilderBlockPos(worldId, 4, 70, 8),
            "minecraft:air",
            "minecraft:stone",
        )
        val book = BuilderItemAmount("AAAA", "minecraft:book", 1)
        val stone = BuilderItemAmount("BBBB", "minecraft:stone", 4)
        val displacedDirt = BuilderItemAmount("CCCC", "minecraft:dirt", 2)
        val ordinary = BuilderPlan(
            id = UUID.randomUUID(),
            playerId = playerId,
            kind = BuilderPlanKind.FILL,
            changes = listOf(change),
            costs = listOf(stone),
            rewards = emptyList(),
            createdAtMillis = now,
            expiresAtMillis = now + 30_000,
        )
        BuilderUndoRules.exchangeFor(ordinary) shouldBe BuilderUndoExchange(
            costs = emptyList(),
            rewards = listOf(stone),
        )

        val builtFromBook = ordinary.copy(
            kind = BuilderPlanKind.BUILD_BOOK,
            costs = listOf(book),
            rewards = listOf(displacedDirt),
            bookBlueprintId = UUID.randomUUID(),
            bookInstanceId = UUID.randomUUID(),
            bookInstanceGeneration = 1,
            bookBuildingId = "player-blueprint.schem",
            bookSchematicSha256 = "a".repeat(64),
        )
        BuilderUndoRules.exchangeFor(builtFromBook) shouldBe BuilderUndoExchange(
            costs = listOf(displacedDirt),
            rewards = emptyList(),
        )
    }

    test("recovery distinguishes never-applied and possibly-applied phases") {
        BuilderRecoveryRules.action(
            BuilderJournalPhase.PREPARED,
            "minecraft:stone",
            "minecraft:stone",
            "minecraft:air",
        ) shouldBe BuilderRecoveryAction.KEEP_BEFORE
        shouldThrow<IllegalArgumentException> {
            BuilderRecoveryRules.action(
                BuilderJournalPhase.PREPARED,
                "minecraft:air",
                "minecraft:stone",
                "minecraft:air",
            )
        }
        BuilderRecoveryRules.action(
            BuilderJournalPhase.APPLYING,
            "minecraft:air",
            "minecraft:stone",
            "minecraft:air",
        ) shouldBe BuilderRecoveryAction.RESTORE_BEFORE
        shouldThrow<IllegalStateException> {
            BuilderRecoveryRules.action(
                BuilderJournalPhase.APPLYING,
                "minecraft:dirt",
                "minecraft:stone",
                "minecraft:air",
            )
        }
    }

    test("plan rejects duplicate positions and non-vanilla block data") {
        val now = 1_800_000_000_000L
        val position = BuilderBlockPos(worldId, 1, 64, 1)
        val duplicate = listOf(
            BuilderBlockChange(position, "minecraft:stone", "minecraft:air"),
            BuilderBlockChange(position, "minecraft:dirt", "minecraft:air"),
        )
        shouldThrow<IllegalArgumentException> {
            BuilderPlan(UUID.randomUUID(), playerId, BuilderPlanKind.FILL, duplicate, emptyList(), emptyList(), createdAtMillis = now, expiresAtMillis = now + 1_000).validated()
        }
        shouldThrow<IllegalArgumentException> {
            BuilderBlockChange(position, "itemsadder:custom", "minecraft:air").validated()
        }
    }
})
