import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { expect, test, waitUntil } from '@drownek/plugwright';
import chatMessage from 'prismarine-chat';

function position(player, x, y, z) {
  return player.bot.entity.position.clone().set(x, y, z);
}

function stoneCount(player) {
  return player.bot.inventory.items().filter(item => item.name === 'stone').reduce((n, item) => n + item.count, 0);
}

async function commands(server, player, entries) {
  const marker = `assist-fixture-${randomUUID()}`;
  for (const command of entries) server.execute(command);
  server.execute(`minecraft:tellraw ${player.username} {"text":"${marker}"}`);
  await expect(player).toHaveReceivedMessage(marker);
}

async function assertCells(server, player, signal, cells, remaining) {
  await waitUntil(() => cells.every(({ x, y, z, material }) => player.bot.blockAt(position(player, x, y, z))?.name === material) &&
    stoneCount(player) === remaining, {
    signal, timeout: 10000, message: `Expected the exact construction cells and ${remaining} stone items`,
  });
  const marker = `assist-world-${randomUUID()}`;
  const checks = cells.map(({ x, y, z, material }) => `if block ${x} ${y} ${z} ${material}`).join(' ');
  server.execute(`minecraft:execute ${checks} run tellraw ${player.username} {"text":"${marker}"}`);
  await expect(player).toHaveReceivedMessage(marker);
  assert.equal(stoneCount(player), remaining);
}

async function assertBridge(server, player, signal, placed, remaining) {
  const cells = Array.from({ length: 6 }, (_, x) => ({ x, y: 65, z: 0, material: x < placed ? 'stone' : 'air' }));
  await assertCells(server, player, signal, cells, remaining);
}

function useAir(player, sequence) {
  const { yaw, pitch } = player.bot.entity;
  // Exact 1.21.11 use_item schema; Mineflayer stores look angles in radians.
  player.bot._client.write('use_item', {
    hand: 0,
    sequence,
    rotation: { x: (Math.PI - yaw) * 180 / Math.PI, y: -pitch * 180 / Math.PI },
  });
}

async function aimAtReadyTarget(player, target, signal, readyPrefix = 'Continue row') {
  const ChatMessage = chatMessage(player.bot.registry);
  let actionBar = '';
  const receive = packet => { actionBar = ChatMessage.fromNotch(packet.text).toString(); };
  // Paper uses the dedicated action_bar packet, which Mineflayer's actionBar event does not handle.
  player.bot._client.on('action_bar', receive);
  try {
    await player.bot.lookAt(target, false);
    await waitUntil(() => actionBar.startsWith(readyPrefix), {
      signal, timeout: 10000, message: 'Server did not publish the placement assistant ready action bar',
    });
  } finally {
    player.bot._client.removeListener('action_bar', receive);
  }
}

test('locked row assists imprecise background and air clicks while crouching without duplicate placement', async ({ player, server, signal }) => {
  assert.equal(player.bot.version, '1.21.11');
  await commands(server, player, [
    'minecraft:fill -2 64 -2 7 70 6 air',
    'minecraft:fill -2 64 2 7 64 6 stone',
    'minecraft:fill 0 64 0 1 64 0 stone',
    'minecraft:setblock 3 65 -1 stone',
    `minecraft:clear ${player.username}`,
  ]);
  await player.setGameMode('survival');
  await player.teleport(2.5, 65, 3.5);
  await waitUntil(() => player.bot.entity.position.distanceTo(position(player, 2.5, 65, 3.5)) < 0.1 &&
    player.bot.blockAt(position(player, 0, 64, 0))?.name === 'stone', { signal });

  // No operator status or builder permission is granted anywhere in this test.
  player.chat('/builder help');
  await expect(player).toHaveReceivedMessage(/access to this tool/i);
  player.chat('/builder assist on');
  await expect(player).toHaveReceivedMessage(/placement (?:helper|assistant).*enabled/i);
  await player.giveItem('stone', 8);
  await player.bot.equip(player.bot.inventory.items().find(item => item.name === 'stone'), 'hand');
  await assertBridge(server, player, signal, 0, 8);

  let sequence = 0;
  for (const x of [0, 1]) {
    const support = position(player, x, 64, 0);
    await player.bot.lookAt(support.offset(0.5, 1, 0.5), false);
    // Real ordinary use-item-on packets earn the line; fixture writes do not.
    player.bot._client.write('block_place', {
      hand: 0, location: support, direction: 1,
      cursorX: 0.5, cursorY: 1, cursorZ: 0.5,
      insideBlock: false, worldBorderHit: false, sequence: ++sequence,
    });
    await assertBridge(server, player, signal, x + 1, 7 - x);
    // Successful-placement tracking rechecks cancellation and final state on the next tick.
    await player.bot.waitForTicks(3);
  }

  // The ordinary ray hits a background block beside the locked row. Vanilla would
  // place at x=3 and leave a gap at x=2; the assistant must keep the earned direction.
  player.bot.setControlState('sneak', true);
  await aimAtReadyTarget(player, position(player, 3.5, 65.5, 0.5), signal);
  player.bot._client.write('block_place', {
    hand: 0, location: position(player, 3, 65, -1), direction: 3,
    cursorX: 0.5, cursorY: 0.5, cursorZ: 1,
    insideBlock: false, worldBorderHit: false, sequence: ++sequence,
  });
  // A vanilla use-on fallback in the same packet burst must not place a second block.
  useAir(player, ++sequence);
  await assertBridge(server, player, signal, 3, 5);

  player.bot.setControlState('sneak', false);
  // This ray passes above the actual next-cell cube. It deliberately failed the
  // old exact-cell targeting even though the locked row is plainly in front of us.
  await aimAtReadyTarget(player, position(player, 3.5, 67, 0.5), signal);
  await player.bot.waitForTicks(20);
  await assertBridge(server, player, signal, 3, 5);
  useAir(player, ++sequence);
  await assertBridge(server, player, signal, 4, 4);

  await aimAtReadyTarget(player, position(player, 4.5, 65.5, 0.5), signal);
  await player.bot.waitForTicks(20);
  await assertBridge(server, player, signal, 4, 4);
  player.chat('/builder assist off');
  await expect(player).toHaveReceivedMessage(/placement (?:helper|assistant).*disabled/i);
  useAir(player, ++sequence);
  await player.bot.waitForTicks(20);
  await assertBridge(server, player, signal, 4, 4);
});

test('locked floor plane supports a two-dimensional patch without aiming at support faces', async ({ player, server, signal }) => {
  await commands(server, player, [
    'minecraft:fill -2 64 -2 7 70 6 air',
    'minecraft:fill -2 64 2 7 64 6 stone',
    'minecraft:setblock 0 64 0 stone',
    `minecraft:clear ${player.username}`,
  ]);
  await player.setGameMode('survival');
  await player.teleport(2.5, 65, 3.5);
  await waitUntil(() => player.bot.entity.position.distanceTo(position(player, 2.5, 65, 3.5)) < 0.1 &&
    player.bot.blockAt(position(player, 0, 64, 0))?.name === 'stone', { signal });
  player.chat('/builder assist plane');
  await expect(player).toHaveReceivedMessage(/placement (?:helper|assistant).*enabled/i);
  await player.giveItem('stone', 8);
  await player.bot.equip(player.bot.inventory.items().find(item => item.name === 'stone'), 'hand');

  let sequence = 0;
  await player.bot.lookAt(position(player, 0.5, 65, 0.5), false);
  player.bot._client.write('block_place', {
    hand: 0, location: position(player, 0, 64, 0), direction: 1,
    cursorX: 0.5, cursorY: 1, cursorZ: 0.5,
    insideBlock: false, worldBorderHit: false, sequence: ++sequence,
  });
  const patch = [[0, 0], [1, 0], [1, 1], [0, 1]];
  const cells = placed => patch.map(([x, z], index) => ({ x, y: 65, z, material: index < placed ? 'stone' : 'air' }));
  await assertCells(server, player, signal, cells(1), 7);
  for (let index = 1; index < patch.length; index++) {
    const [x, z] = patch[index];
    await aimAtReadyTarget(player, position(player, x + 0.5, 65.5, z + 0.5), signal, 'Plane ');
    useAir(player, ++sequence);
    await assertCells(server, player, signal, cells(index + 1), 7 - index);
  }
  await player.bot.waitForTicks(20);
  await assertCells(server, player, signal, cells(4), 4);
  player.chat('/builder assist off');
  await expect(player).toHaveReceivedMessage(/placement (?:helper|assistant).*disabled/i);
});
