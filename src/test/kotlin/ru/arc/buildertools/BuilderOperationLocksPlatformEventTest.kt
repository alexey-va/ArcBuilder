package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.bukkit.Bukkit
import org.bukkit.ExplosionResult
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Container
import org.bukkit.block.DoubleChest
import org.bukkit.entity.Entity
import org.bukkit.entity.Item
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockFadeEvent
import org.bukkit.event.block.BlockFormEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockGrowEvent
import org.bukkit.event.block.BlockPhysicsEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockSpreadEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.inventory.InventoryPickupItemEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

/**
 * Platform coverage for the complete event boundary owned by
 * [BuilderOperationLocks]. Each scenario owns and closes one MockBukkit
 * runtime, and compares a locked target with an unrelated player/block.
 */
class BuilderOperationLocksPlatformEventTest : FunSpec({
    test("locked player inventory and movement-related events are cancelled while an outsider is unaffected") {
        MockBukkitTestRuntime.open().use { paper ->
            strictPlatformScenario {
                val fixture = LockEventFixture.create(paper, "BuilderLockPlayerEvents")
                fixture.locks.use { locks ->
                    fixture.lockPlayer()
                    val ownerView = checkNotNull(fixture.owner.openInventory(Bukkit.createInventory(null, 9)))
                    val outsiderView = checkNotNull(fixture.outsider.openInventory(Bukkit.createInventory(null, 9)))
                    val ownerItem = mockk<Item>(relaxed = true)
                    val outsiderItem = mockk<Item>(relaxed = true)

                    assertLockedAndFree(
                        paper,
                        { InventoryClickEvent(ownerView, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL) },
                        { InventoryClickEvent(outsiderView, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL) },
                    )
                    assertLockedAndFree(
                        paper,
                        { InventoryDragEvent(ownerView, ItemStack(Material.STONE), ItemStack(Material.AIR), true, mapOf(0 to ItemStack(Material.STONE))) },
                        { InventoryDragEvent(outsiderView, ItemStack(Material.STONE), ItemStack(Material.AIR), true, mapOf(0 to ItemStack(Material.STONE))) },
                    )
                    assertLockedAndFree(
                        paper,
                        { PlayerDropItemEvent(fixture.owner, ownerItem) },
                        { PlayerDropItemEvent(fixture.outsider, outsiderItem) },
                    )
                    assertLockedAndFree(
                        paper,
                        { EntityPickupItemEvent(fixture.owner, ownerItem, 0) },
                        { EntityPickupItemEvent(fixture.outsider, outsiderItem, 0) },
                    )
                    assertLockedAndFree(
                        paper,
                        { PlayerSwapHandItemsEvent(fixture.owner, ItemStack(Material.STONE), ItemStack(Material.DIRT)) },
                        { PlayerSwapHandItemsEvent(fixture.outsider, ItemStack(Material.STONE), ItemStack(Material.DIRT)) },
                    )
                    assertLockedAndFree(
                        paper,
                        { PlayerItemHeldEvent(fixture.owner, 0, 1) },
                        { PlayerItemHeldEvent(fixture.outsider, 0, 1) },
                    )
                    assertLockedAndFree(
                        paper,
                        { EntityDamageEvent(fixture.owner, EntityDamageEvent.DamageCause.FALL, 1.0) },
                        { EntityDamageEvent(fixture.outsider, EntityDamageEvent.DamageCause.FALL, 1.0) },
                    )
                }
            }
        }
    }

    test("locked blocks cancel break and place while unrelated blocks remain mutable") {
        MockBukkitTestRuntime.open().use { paper ->
            strictPlatformScenario {
                val fixture = LockEventFixture.create(paper, "BuilderLockDirectBlockEvents")
                fixture.locks.use { locks ->
                    fixture.lockBlock()
                    assertLockedAndFree(
                        paper,
                        { org.bukkit.event.block.BlockBreakEvent(fixture.locked, fixture.owner) },
                        { org.bukkit.event.block.BlockBreakEvent(fixture.free, fixture.outsider) },
                    )
                    assertLockedAndFree(
                        paper,
                        { placeEvent(fixture.locked, fixture.free, fixture.owner) },
                        { placeEvent(fixture.free, fixture.outsiderBlock, fixture.outsider) },
                    )
                }
            }
        }
    }

    test("resource lease locks both double-chest holders and every inventory route until its exact project releases it") {
        MockBukkitTestRuntime.open().use { paper ->
            strictPlatformScenario {
                val fixture = LockEventFixture.create(paper, "BuilderResourceLocks")
                fixture.locks.use { locks ->
                    val left = fixture.world.getBlockAt(3, 64, 0).also { it.type = Material.CHEST }
                    val right = fixture.world.getBlockAt(4, 64, 0).also { it.type = Material.CHEST }
                    val projectId = UUID.randomUUID()
                    val otherProjectId = UUID.randomUUID()
                    val leftHolder = mockk<Container>()
                    val rightHolder = mockk<Container>()
                    val holder = mockk<DoubleChest>()
                    io.mockk.every { leftHolder.block } returns left
                    io.mockk.every { rightHolder.block } returns right
                    io.mockk.every { holder.leftSide } returns leftHolder
                    io.mockk.every { holder.rightSide } returns rightHolder
                    val lockedInventory = Bukkit.createInventory(holder, 54)
                    val freeInventory = Bukkit.createInventory(null, 9)
                    val lockedView = checkNotNull(fixture.outsider.openInventory(lockedInventory))
                    val freeView = checkNotNull(fixture.owner.openInventory(freeInventory))
                    val item = mockk<Item>(relaxed = true)

                    locks.tryResourceLock(
                        projectId,
                        listOf(
                            BuilderBlockPos(fixture.world.uid, left.x, left.y, left.z),
                            BuilderBlockPos(fixture.world.uid, right.x, right.y, right.z),
                        ),
                    ) shouldBe true
                    locks.tryResourceLock(otherProjectId, listOf(BuilderBlockPos(fixture.world.uid, left.x, left.y, left.z))) shouldBe false

                    assertLockedAndFree(
                        paper,
                        { InventoryOpenEvent(lockedView) },
                        { InventoryOpenEvent(freeView) },
                    )
                    assertLockedAndFree(
                        paper,
                        { InventoryClickEvent(lockedView, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL) },
                        { InventoryClickEvent(freeView, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL) },
                    )
                    assertLockedAndFree(
                        paper,
                        { InventoryDragEvent(lockedView, ItemStack(Material.STONE), ItemStack(Material.AIR), true, mapOf(0 to ItemStack(Material.STONE))) },
                        { InventoryDragEvent(freeView, ItemStack(Material.STONE), ItemStack(Material.AIR), true, mapOf(0 to ItemStack(Material.STONE))) },
                    )
                    assertLockedAndFree(
                        paper,
                        { InventoryMoveItemEvent(lockedInventory, ItemStack(Material.STONE), freeInventory, true) },
                        { InventoryMoveItemEvent(freeInventory, ItemStack(Material.STONE), freeInventory, true) },
                    )
                    assertLockedAndFree(
                        paper,
                        { InventoryMoveItemEvent(freeInventory, ItemStack(Material.STONE), lockedInventory, true) },
                        { InventoryMoveItemEvent(freeInventory, ItemStack(Material.STONE), freeInventory, true) },
                    )
                    assertLockedAndFree(
                        paper,
                        { InventoryPickupItemEvent(lockedInventory, item) },
                        { InventoryPickupItemEvent(freeInventory, item) },
                    )
                    assertLockedAndFree(
                        paper,
                        { org.bukkit.event.block.BlockBreakEvent(left, fixture.outsider) },
                        { org.bukkit.event.block.BlockBreakEvent(fixture.free, fixture.outsider) },
                    )
                    assertLockedAndFree(
                        paper,
                        { org.bukkit.event.block.BlockBreakEvent(right, fixture.outsider) },
                        { org.bukkit.event.block.BlockBreakEvent(fixture.free, fixture.outsider) },
                    )

                    locks.unlockResources(otherProjectId)
                    paper.callEvent(InventoryClickEvent(lockedView, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL)).isCancelled shouldBe true
                    paper.callEvent(org.bukkit.event.block.BlockBreakEvent(right, fixture.outsider)).isCancelled shouldBe true

                    locks.unlockResources(projectId)
                    paper.callEvent(InventoryOpenEvent(lockedView)).isCancelled shouldBe false
                    paper.callEvent(InventoryClickEvent(lockedView, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL)).isCancelled shouldBe false
                    paper.callEvent(
                        InventoryDragEvent(
                            lockedView,
                            ItemStack(Material.STONE),
                            ItemStack(Material.AIR),
                            true,
                            mapOf(0 to ItemStack(Material.STONE)),
                        ),
                    ).isCancelled shouldBe false
                    paper.callEvent(InventoryMoveItemEvent(lockedInventory, ItemStack(Material.STONE), freeInventory, true)).isCancelled shouldBe false
                    paper.callEvent(InventoryMoveItemEvent(freeInventory, ItemStack(Material.STONE), lockedInventory, true)).isCancelled shouldBe false
                    paper.callEvent(InventoryPickupItemEvent(lockedInventory, item)).isCancelled shouldBe false
                    paper.callEvent(org.bukkit.event.block.BlockBreakEvent(left, fixture.outsider)).isCancelled shouldBe false
                    paper.callEvent(org.bukkit.event.block.BlockBreakEvent(right, fixture.outsider)).isCancelled shouldBe false
                }
            }
        }
    }

    test("locked environmental block events cancel either affected endpoint while unrelated events remain open") {
        MockBukkitTestRuntime.open().use { paper ->
            strictPlatformScenario {
                val fixture = LockEventFixture.create(paper, "BuilderLockEnvironmentEvents")
                fixture.locks.use { locks ->
                    fixture.lockBlock()
                    val data = Material.STONE.createBlockData()

                    assertLockedAndFree(
                        paper,
                        { BlockPhysicsEvent(fixture.locked, data) },
                        { BlockPhysicsEvent(fixture.free, data) },
                    )
                    assertLockedAndFree(
                        paper,
                        { BlockPhysicsEvent(fixture.free, data, fixture.locked) },
                        { BlockPhysicsEvent(fixture.free, data, fixture.outsiderBlock) },
                    )
                    assertLockedAndFree(
                        paper,
                        { BlockFromToEvent(fixture.locked, fixture.free) },
                        { BlockFromToEvent(fixture.free, fixture.outsiderBlock) },
                    )
                    assertLockedAndFree(
                        paper,
                        { BlockFromToEvent(fixture.free, fixture.locked) },
                        { BlockFromToEvent(fixture.free, fixture.outsiderBlock) },
                    )
                    assertLockedAndFree(
                        paper,
                        { BlockGrowEvent(fixture.locked, fixture.locked.state) },
                        { BlockGrowEvent(fixture.free, fixture.free.state) },
                    )
                    assertLockedAndFree(
                        paper,
                        { BlockSpreadEvent(fixture.locked, fixture.free, fixture.locked.state) },
                        { BlockSpreadEvent(fixture.free, fixture.outsiderBlock, fixture.free.state) },
                    )
                    assertLockedAndFree(
                        paper,
                        { BlockSpreadEvent(fixture.free, fixture.locked, fixture.free.state) },
                        { BlockSpreadEvent(fixture.free, fixture.outsiderBlock, fixture.free.state) },
                    )
                    assertLockedAndFree(
                        paper,
                        { BlockFadeEvent(fixture.locked, fixture.locked.state) },
                        { BlockFadeEvent(fixture.free, fixture.free.state) },
                    )
                    assertLockedAndFree(
                        paper,
                        { BlockBurnEvent(fixture.locked, fixture.free) },
                        { BlockBurnEvent(fixture.free, fixture.outsiderBlock) },
                    )
                    assertLockedAndFree(
                        paper,
                        { BlockFormEvent(fixture.locked, fixture.locked.state) },
                        { BlockFormEvent(fixture.free, fixture.free.state) },
                    )
                    assertLockedAndFree(
                        paper,
                        { EntityChangeBlockEvent(fixture.owner, fixture.locked, data) },
                        { EntityChangeBlockEvent(fixture.outsider, fixture.free, data) },
                    )
                }
            }
        }
    }

    test("locked explosion lists, explosion blocks, and piston movement are cancelled without affecting unrelated areas") {
        MockBukkitTestRuntime.open().use { paper ->
            strictPlatformScenario {
                val fixture = LockEventFixture.create(paper, "BuilderLockPhysicsEvents")
                fixture.locks.use { locks ->
                    fixture.lockBlock()
                    val entity = mockk<Entity>(relaxed = true)
                    assertLockedAndFree(
                        paper,
                        {
                            EntityExplodeEvent(
                                entity,
                                fixture.owner.location,
                                mutableListOf(fixture.locked),
                                1.0f,
                                ExplosionResult.DESTROY,
                            )
                        },
                        {
                            EntityExplodeEvent(
                                entity,
                                fixture.outsider.location,
                                mutableListOf(fixture.outsiderBlock),
                                1.0f,
                                ExplosionResult.DESTROY,
                            )
                        },
                    )
                    assertLockedAndFree(
                        paper,
                        {
                            BlockExplodeEvent(
                                fixture.locked,
                                fixture.locked.state,
                                listOf(fixture.free),
                                1.0f,
                                ExplosionResult.DESTROY,
                            )
                        },
                        {
                            BlockExplodeEvent(
                                fixture.free,
                                fixture.free.state,
                                listOf(fixture.outsiderBlock),
                                1.0f,
                                ExplosionResult.DESTROY,
                            )
                        },
                    )
                    assertLockedAndFree(
                        paper,
                        {
                            BlockExplodeEvent(
                                fixture.free,
                                fixture.free.state,
                                listOf(fixture.locked),
                                1.0f,
                                ExplosionResult.DESTROY,
                            )
                        },
                        {
                            BlockExplodeEvent(
                                fixture.free,
                                fixture.free.state,
                                listOf(fixture.outsiderBlock),
                                1.0f,
                                ExplosionResult.DESTROY,
                            )
                        },
                    )

                    val piston = fixture.world.getBlockAt(1, 64, 0)
                    val pistonFree = fixture.world.getBlockAt(7, 64, 0)
                    val movedIntoLocked = fixture.world.getBlockAt(1, 64, 0)
                    val movedFree = fixture.world.getBlockAt(7, 64, 0)
                    assertLockedAndFree(
                        paper,
                        { BlockPistonExtendEvent(piston, listOf(movedIntoLocked), BlockFace.EAST) },
                        { BlockPistonExtendEvent(pistonFree, listOf(movedFree), BlockFace.EAST) },
                    )
                    assertLockedAndFree(
                        paper,
                        { BlockPistonRetractEvent(piston, listOf(movedIntoLocked), BlockFace.EAST) },
                        { BlockPistonRetractEvent(pistonFree, listOf(movedFree), BlockFace.EAST) },
                    )
                }
            }
        }
    }
})

private fun <T> assertLockedAndFree(
    paper: MockBukkitTestRuntime,
    locked: () -> T,
    free: () -> T,
) where T : Event, T : Cancellable {
    paper.callEvent(locked()).isCancelled shouldBe true
    paper.callEvent(free()).isCancelled shouldBe false
}

private fun strictPlatformScenario(block: () -> Unit) {
    failOnUnsupportedMockBukkitOperation(block)
}

private fun placeEvent(block: Block, against: Block, player: org.bukkit.entity.Player): BlockPlaceEvent =
    BlockPlaceEvent(
        block,
        block.state,
        against,
        ItemStack(Material.STONE),
        player,
        true,
    )

private class LockEventFixture private constructor(
    val plugin: Plugin,
    val world: org.bukkit.World,
    val owner: org.bukkit.entity.Player,
    val outsider: org.bukkit.entity.Player,
    val locked: Block,
    val free: Block,
    val outsiderBlock: Block,
    val locks: BuilderOperationLocks,
) {
    fun lockPlayer() = locks.lockRecovery(owner.uniqueId)

    fun lockBlock() {
        check(locks.tryLock(plan(owner.uniqueId, world.uid)))
    }

    companion object {
        fun create(paper: MockBukkitTestRuntime, pluginName: String): LockEventFixture {
            val plugin = paper.createSimplePlugin(pluginName)
            val world = paper.addSimpleWorld("$pluginName-world")
            val owner = paper.addPlayer("${pluginName}Owner")
            val outsider = paper.addPlayer("${pluginName}Outsider")
            val locked = world.getBlockAt(2, 64, 0).also { it.type = Material.STONE }
            val free = world.getBlockAt(8, 64, 0).also { it.type = Material.STONE }
            val outsiderBlock = world.getBlockAt(9, 64, 0).also { it.type = Material.STONE }
            return LockEventFixture(
                plugin,
                world,
                owner,
                outsider,
                locked,
                free,
                outsiderBlock,
                BuilderOperationLocks(plugin),
            )
        }
    }
}

private fun plan(playerId: UUID, worldId: UUID): BuilderPlan {
    val now = System.currentTimeMillis()
    return BuilderPlan(
        id = UUID.randomUUID(),
        playerId = playerId,
        kind = BuilderPlanKind.FILL,
        changes = listOf(
            BuilderBlockChange(
                BuilderBlockPos(worldId, 2, 64, 0),
                Material.STONE.createBlockData().asString,
                Material.OAK_PLANKS.createBlockData().asString,
            ),
        ),
        costs = emptyList(),
        rewards = emptyList(),
        createdAtMillis = now,
        expiresAtMillis = now + 30_000L,
    ).validated()
}
