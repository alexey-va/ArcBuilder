import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { expect, test, waitUntil } from '@drownek/plugwright';

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

async function assertBridge(server, player, signal, placed, remaining) {
  const cells = Array.from({ length: 6 }, (_, x) => ({ x, material: x < placed ? 'stone' : 'air' }));
  await waitUntil(() => cells.every(({ x, material }) => player.bot.blockAt(position(player, x, 65, 0))?.name === material) &&
    stoneCount(player) === remaining, {
    signal, timeout: 10000, message: `Expected ${placed} bridge blocks and exactly ${remaining} stone items`,
  });
  const marker = `assist-world-${randomUUID()}`;
  const checks = cells.map(({ x, material }) => `if block ${x} 65 0 ${material}`).join(' ');
  server.execute(`minecraft:execute ${checks} run tellraw ${player.username} {"text":"${marker}"}`);
  await expect(player).toHaveReceivedMessage(marker);
  assert.equal(stoneCount(player), remaining);
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

test('placement assistant gives an ordinary survival player one native placement per fresh air use', async ({ player, server, signal }) => {
  assert.equal(player.bot.version, '1.21.11');
  await commands(server, player, [
    'minecraft:fill -2 64 -2 7 70 6 air',
    'minecraft:fill -2 64 2 7 64 6 stone',
    'minecraft:fill 0 64 0 1 64 0 stone',
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

  // The continuation cells have no floor or directly aimed support block.
  await player.bot.lookAt(position(player, 2.5, 65.5, 0.5), false);
  // Wait for the server tick to publish the same ready target shown to the player.
  await player.bot.waitForTicks(3);
  useAir(player, ++sequence);
  await assertBridge(server, player, signal, 3, 5);

  await player.bot.lookAt(position(player, 3.5, 65.5, 0.5), false);
  await player.bot.waitForTicks(20);
  await assertBridge(server, player, signal, 3, 5);
  useAir(player, ++sequence);
  await assertBridge(server, player, signal, 4, 4);

  await player.bot.lookAt(position(player, 4.5, 65.5, 0.5), false);
  await player.bot.waitForTicks(20);
  await assertBridge(server, player, signal, 4, 4);
  player.chat('/builder assist off');
  await expect(player).toHaveReceivedMessage(/placement (?:helper|assistant).*disabled/i);
  useAir(player, ++sequence);
  await player.bot.waitForTicks(20);
  await assertBridge(server, player, signal, 4, 4);
});
