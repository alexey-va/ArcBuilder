package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import ru.arc.autobuild.ConstructionSite
import ru.arc.autobuild.PlayerBuildBookDigestInspection
import ru.arc.config.ConfigManager
import ru.arc.observability.RuntimeHealthState
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.loadPlugin
import ru.ruscrafting.builder.paper.ArcBuilderPlugin
import java.util.Locale

class BuilderCommandBoundaryMockBukkitTest : FunSpec({
    test("console sender receives player-only behavior without throwing") {
        withCommandFixture { fixture ->
            fixture.execute(Bukkit.getConsoleSender(), "fill", "stone") shouldBe true
            fixture.runtime.runtimeHealthContribution().activeLeases shouldBe 0
        }
    }

    test("player without any builder permission is denied before touching state") {
        withCommandFixture { fixture ->
            val player = fixture.player("NoAccess")
            fixture.assertRejected(player, "нет доступа") {
                fixture.execute(player, "fill", "stone")
            }
        }
    }

    test("umbrella permission reaches feature validation while another feature permission does not") {
        withCommandFixture { fixture ->
            val umbrella = fixture.player("Umbrella")
            fixture.grant(umbrella, "arcbuild.use")
            fixture.assertRejected(umbrella, "область ещё не выделена") {
                fixture.execute(umbrella, "fill", "stone")
            }

            val replaceOnly = fixture.player("ReplaceOnly")
            fixture.grant(replaceOnly, "arcbuild.replace")
            fixture.assertRejected(replaceOnly, "нет доступа") {
                fixture.execute(replaceOnly, "fill", "stone")
            }
        }
    }

    test("spectator player is rejected before feature planning") {
        withCommandFixture { fixture ->
            val player = fixture.player("Spectator")
            fixture.grant(player, "arcbuild.use")
            player.gameMode = GameMode.SPECTATOR
            fixture.assertRejected(player, "режим недоступен") {
                fixture.execute(player, "fill", "stone")
            }
        }
    }

    test("disallowed world is rejected before feature planning") {
        withCommandFixture { fixture ->
            val player = fixture.player("WrongWorld")
            fixture.grant(player, "arcbuild.use")
            player.teleport(fixture.blocked.spawnLocation)
            fixture.assertRejected(player, "этом мире") {
                fixture.execute(player, "fill", "stone")
            }
        }
    }

    test("malformed fill material is rejected without mutation") {
        withCommandFixture { fixture ->
            val player = fixture.player("BadFill")
            fixture.grant(player, "arcbuild.use")
            fixture.assertRejected(player, "материал нельзя") {
                fixture.execute(player, "fill", "not_a_real_block")
            }
        }
    }

    test("malformed replace arguments are rejected without mutation") {
        withCommandFixture { fixture ->
            val player = fixture.player("BadReplace")
            fixture.grant(player, "arcbuild.use")
            fixture.assertRejected(player, "материал нельзя") {
                fixture.execute(player, "replace", "stone", "dirt", "not-confirm")
            }
        }
    }

    test("malformed disconnect arguments are rejected even with a valid selection") {
        withCommandFixture { fixture ->
            val player = fixture.player("BadDisconnect")
            fixture.grant(player, "arcbuild.use", "arcbuild.disconnect")
            fixture.selectSmallArea(player)
            fixture.drainPlayerMessage(player)

            fixture.assertRejected(player, "материал нельзя") {
                fixture.execute(player, "disconnect", "not-confirm")
            }
        }
    }

    test("malformed paste mode is rejected without mutation") {
        withCommandFixture { fixture ->
            val player = fixture.player("BadPaste")
            fixture.grant(player, "arcbuild.use")
            fixture.assertRejected(player, "материал нельзя") {
                fixture.execute(player, "paste", "diagonal")
            }
        }
    }

    test("malformed confirm argument shows help without creating a lease") {
        withCommandFixture { fixture ->
            val player = fixture.player("BadConfirm")
            fixture.grant(player, "arcbuild.use")
            fixture.assertRejected(player, "Инструменты строителя") {
                fixture.execute(player, "confirm", "not-buy")
            }
        }
    }

    test("unauthorized tab completion does not leak feature commands") {
        withCommandFixture { fixture ->
            val noAccess = fixture.player("NoSuggestions")
            fixture.tab(noAccess, "") shouldBe emptyList()

            val fillOnly = fixture.player("FillSuggestions")
            fixture.grant(fillOnly, "arcbuild.fill")
            val suggestions = fixture.tab(fillOnly, "")
            suggestions shouldContain "fill"
            suggestions shouldNotContain "replace"
            suggestions shouldNotContain "disconnect"
            suggestions shouldNotContain "copy"
            suggestions shouldNotContain "paste"
            suggestions shouldNotContain "deconstruct"
            suggestions shouldNotContain "crown"
        }
    }

    test("reload safety detects volatile selection state instead of silently discarding it") {
        withCommandFixture { fixture ->
            val player = fixture.player("ReloadSelection")
            fixture.grant(player, "arcbuild.use")
            fixture.selectSmallArea(player)

            fixture.runtime.reloadBlocker() shouldBe BuilderToolsReloadBlocker.VOLATILE_PLAYER_STATE
        }
    }
})

private class CommandFixture(
    val paper: MockBukkitTestRuntime,
    val plugin: ArcBuilderPlugin,
    val runtime: BuilderToolsRuntime,
    private val command: Command,
    val allowed: World,
    val blocked: World,
    private val messages: CommandMessageRecorder,
) : AutoCloseable {
    private val plain = PlainTextComponentSerializer.plainText()

    fun player(name: String): Player = paper.addPlayer(name).also {
        it.isOp = false
        it.setLocale(Locale.forLanguageTag("ru-RU"))
        it.gameMode = GameMode.SURVIVAL
        it.teleport(allowed.spawnLocation)
    }

    fun grant(player: Player, vararg permissions: String) {
        permissions.forEach { permission -> player.addAttachment(plugin, permission, true) }
        player.recalculatePermissions()
    }

    fun execute(sender: CommandSender, vararg args: String): Boolean =
        runtime.onCommand(sender, command, "builder", args)

    fun tab(player: Player, vararg args: String): List<String> =
        runtime.onTabComplete(player, command, "builder", args)

    fun assertRejected(player: Player, expectedMessage: String, action: () -> Unit) {
        val beforeInventory = player.inventory.contents.map { it?.clone() }
        val beforeCursor = player.itemOnCursor.clone()
        val beforeBlock = player.world.getBlockAt(0, 64, 0).blockData.asString
        messages.clear()

        action()

        messages.forPlayer(player)
            .joinToString("\n") { plain.serialize(it) }
            .lowercase() shouldContain expectedMessage.lowercase()
        player.inventory.contents.toList() shouldBe beforeInventory
        player.itemOnCursor shouldBe beforeCursor
        player.world.getBlockAt(0, 64, 0).blockData.asString shouldBe beforeBlock
        runtime.runtimeHealthContribution().activeLeases shouldBe 0
    }

    fun drainPlayerMessage(player: Player) {
        check(messages.forPlayer(player).isNotEmpty())
        messages.clear()
    }

    @Suppress("DEPRECATION")
    fun selectSmallArea(player: Player) {
        player.inventory.setItemInMainHand(org.bukkit.inventory.ItemStack(org.bukkit.Material.ECHO_SHARD))
        execute(player, "wand") shouldBe true
        val wand = player.inventory.itemInMainHand
        val first = PlayerInteractEvent(
            player,
            Action.LEFT_CLICK_BLOCK,
            wand,
            allowed.getBlockAt(0, 64, 0),
            BlockFace.UP,
            EquipmentSlot.HAND,
        )
        paper.callEvent(first)
        first.isCancelled shouldBe true
        val second = PlayerInteractEvent(
            player,
            Action.RIGHT_CLICK_BLOCK,
            wand,
            allowed.getBlockAt(1, 64, 0),
            BlockFace.UP,
            EquipmentSlot.HAND,
        )
        paper.callEvent(second)
        second.isCancelled shouldBe true
    }

    override fun close() {
        runtime.close()
        paper.server.pluginManager.disablePlugin(plugin)
        ConfigManager.clear()
    }
}

private class CommandMessageRecorder {
    private val recorded = mutableListOf<RecordedCommandMessage>()

    fun record(player: Player, message: Component) {
        recorded += RecordedCommandMessage(player.uniqueId, message)
    }

    fun forPlayer(player: Player): List<Component> = recorded
        .filter { it.playerId == player.uniqueId }
        .map(RecordedCommandMessage::message)

    fun clear() = recorded.clear()
}

private data class RecordedCommandMessage(
    val playerId: java.util.UUID,
    val message: Component,
)

private class NoopBuilderDisplayRenderer : BuilderDisplayRenderer {
    override fun selection(player: Player, points: BuilderSelectionPoints, selection: BuilderSelection?) = Unit
    override fun clearSelection(playerId: java.util.UUID) = Unit
    override fun plan(player: Player, plan: BuilderPlan) = Unit
    override fun clearPlan(playerId: java.util.UUID) = Unit
    override fun clearPlayer(playerId: java.util.UUID) = Unit
    override fun open(site: ConstructionSite) = Unit
    override fun refresh(site: ConstructionSite) = Unit
    override fun close(playerId: java.util.UUID) = Unit
    override fun close() = Unit
}

private object UnusedCommandDraftStorage : BuilderDraftStorage {
    override fun prepare(creatorId: java.util.UUID, source: BuilderClipboard): Nothing =
        error("Draft persistence is outside the command-boundary fixture")

    override fun persist(prepared: ru.arc.autobuild.PreparedPlayerBuildBookTemplate): Nothing =
        error("Draft persistence is outside the command-boundary fixture")

    override fun inspectSchematic(buildingId: String): PlayerBuildBookDigestInspection =
        PlayerBuildBookDigestInspection.Missing

    override fun inspectContent(buildingId: String): PlayerBuildBookDigestInspection =
        PlayerBuildBookDigestInspection.Missing

    override fun register(template: ru.arc.autobuild.PlayerBuildBookTemplate) = Unit
}

private fun withCommandFixture(block: (CommandFixture) -> Unit) {
    failOnUnsupportedMockBukkitOperation {
        ConfigManager.clear()
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<ArcBuilderPlugin>()
            try {
                val config = ConfigManager.ofModule(plugin.dataPath, "builder-tools.yml").apply {
                    setBoolean("enabled", true)
                    setStringList("allowed-worlds", listOf("allowed"))
                    setBoolean("safety.require-coreprotect", false)
                    setBoolean("shop.enabled", false)
                    saveStrict()
                }
                BuilderToolsModule.shutdown()
                val messages = CommandMessageRecorder()
                val runtime = BuilderToolsRuntime(
                    plugin = plugin,
                    config = BuilderToolsConfig(config).validated(),
                    displayRenderer = NoopBuilderDisplayRenderer(),
                    blockDataRotation = BuilderBlockDataRotation { data, _ -> data },
                    physicsUpdater = RecordingBuilderPhysicsUpdater(),
                    draftStorage = UnusedCommandDraftStorage,
                    bookSchematicVerifier = BuilderBookSchematicVerifier { true },
                    systemBuildBookResolver = { null },
                    sendPlayerMessage = messages::record,
                )
                val command = checkNotNull(plugin.getCommand("builder"))
                command.setExecutor(runtime)
                command.tabCompleter = runtime
                paper.server.pluginManager.registerEvents(runtime, plugin)
                val allowed = paper.addSimpleWorld("allowed")
                val blocked = paper.addSimpleWorld("blocked")
                allowed.loadChunk(0, 0)
                blocked.loadChunk(0, 0)
                val deadline = System.nanoTime() + 5_000_000_000L
                while (runtime.runtimeHealthContribution().state != RuntimeHealthState.UP && System.nanoTime() < deadline) {
                    paper.performTicks(1)
                    Thread.yield()
                }
                runtime.runtimeHealthContribution().state shouldBe RuntimeHealthState.UP
                CommandFixture(paper, plugin, runtime, command, allowed, blocked, messages).use(block)
            } finally {
                ConfigManager.clear()
            }
        }
    }
}
