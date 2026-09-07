import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { once } from 'node:events';
import { expect, test, waitUntil } from '@drownek/plugwright';

test('builder enforces its permission boundary on the real Paper command route', async ({ player }) => {
  player.chat('/builder help');
  await expect(player).toHaveReceivedMessage(/access to this tool/i);
});

async function commands(server, player, commands) {
  const marker = `fixture-${randomUUID()}`;
  for (const command of commands) server.execute(command);
  server.execute(`minecraft:tellraw ${player.username} {"text":"${marker}"}`);
  await expect(player).toHaveReceivedMessage(marker);
}

async function assertWorld(server, player, material) {
  const marker = `world-${randomUUID()}`;
  server.execute(`minecraft:execute if block 0 64 0 ${material} if block 1 64 0 ${material} run tellraw ${player.username} {"text":"${marker}"}`);
  await expect(player).toHaveReceivedMessage(marker);
}

async function clientBlocks(player, material, signal) {
  await waitUntil(() => [0, 1].every(x => player.bot.blockAt(player.bot.entity.position.clone().set(x, 64, 0))?.name === material), {
    signal, timeout: 10000, message: `${player.username} did not receive ${material} blocks`,
  });
}

async function selectReplacement(player, server, signal, materials = 2) {
  await commands(server, player, [
    'minecraft:fill -3 64 -3 4 64 4 stone',
    'minecraft:fill -3 65 -3 4 69 4 air',
    'minecraft:fill 0 64 0 1 64 0 oak_planks',
  ]);
  await player.teleport(0.5, 65, 1.5);
  await clientBlocks(player, 'oak_planks', signal);
  await player.makeOp();
  player.chat(`/lp user ${player.username} permission set arcbuild.replace true`);
  await expect(player).toHaveReceivedMessage('Set arcbuild.replace to true');
  await player.deOp();
  await player.giveItem('echo_shard', 1);
  await player.giveItem('diamond_block', materials);
  await player.bot.equip(player.bot.inventory.items().find(item => item.name === 'echo_shard'), 'hand');
  player.chat('/builder wand');
  await expect(player).toHaveReceivedMessage('Your echo shard is now the builder selector.');

  const first = player.bot.blockAt(player.bot.entity.position.clone().set(0, 64, 0));
  const second = player.bot.blockAt(player.bot.entity.position.clone().set(1, 64, 0));
  await player.bot.lookAt(first.position.offset(0.5, 0.5, 0.5), true);
  // Send the real left-click/start-dig packet; the selector cancels mining.
  player.bot._client.write('block_dig', { status: 0, location: first.position, face: 1, sequence: 0 });
  await expect(player).toHaveReceivedMessage('Position 1 selected.');
  await player.bot.activateBlock(second);
  await expect(player).toHaveReceivedMessage('Position 2 selected.');
}

let completionId = 0;
async function completions(player, text) {
  const transactionId = ++completionId;
  const response = once(player.bot._client, 'tab_complete', { signal: AbortSignal.timeout(10000) });
  // Mineflayer's helper omits the transactionId required by the 1.21.11 protocol.
  player.bot._client.write('tab_complete', { transactionId, text });
  const [packet] = await response.catch(cause => { throw new Error(`No completion response for ${text}`, { cause }); });
  assert.equal(packet.transactionId, transactionId);
  return packet.matches;
}

function itemCount(player, material) {
  return player.bot.inventory.items().filter(item => item.name === material).reduce((n, item) => n + item.count, 0);
}

test('survival selection previews without world mutation; confirm and undo update both clients', async ({ player, server, createPlayer, signal }) => {
  await selectReplacement(player, server, signal);
  const observer = await createPlayer({ username: 'BuildObserver' });
  await observer.teleport(2.5, 65, 1.5);
  await clientBlocks(observer, 'oak_planks', signal);
  for (const [query, expected] of [
    ['/builder replace дуб', 'дубовыеДоски'],
    ['/builder replace oak_planks АЛМ', 'алмазныйБлок'],
    ['/builder replace oak_planks diamond_b', 'diamond_block'],
  ]) {
    const matches = await completions(player, query);
    assert.ok(matches.some(match => match.match === expected), `${query}: ${JSON.stringify(matches)}`);
  }
  const oakMatches = await completions(player, '/builder replace oak_planks oak_');
  assert.ok(oakMatches.length > 0);
  assert.ok(!oakMatches.some(match => match.match === 'oak_door'));
  player.chat('/builder replace дубовыеДоски алмазныйБлок');
  await expect(player).toHaveReceivedMessage('[▶ Build]');
  await assertWorld(server, observer, 'oak_planks');
  await clientBlocks(player, 'oak_planks', signal);
  player.chat('/builder confirm');
  await expect(player).toHaveReceivedMessage('completed: 2 blocks.');
  await assertWorld(server, observer, 'diamond_block');
  await clientBlocks(player, 'diamond_block', signal);
  await clientBlocks(observer, 'diamond_block', signal);
  assert.equal(player.bot.inventory.items().filter(item => item.name === 'diamond_block').reduce((n, item) => n + item.count, 0), 0);

  const since = player.messageBuffer.length;
  player.chat('/builder undo');
  await expect(player).toHaveReceivedMessage('[▶ Build]', { since });
  player.chat('/builder confirm');
  await expect(player).toHaveReceivedMessage('completed: 2 blocks.', { since });
  await assertWorld(server, observer, 'oak_planks');
  await clientBlocks(player, 'oak_planks', signal);
  await clientBlocks(observer, 'oak_planks', signal);
  await waitUntil(() => player.bot.inventory.items().filter(item => item.name === 'diamond_block').reduce((n, item) => n + item.count, 0) === 2, { signal });
});

test('cancelled replacement rejects a stale confirmation without consuming or returning materials', async ({ player, server, signal }) => {
  await selectReplacement(player, server, signal);
  player.chat('/builder replace oak_planks diamond_block');
  await expect(player).toHaveReceivedMessage('[▶ Build]');
  player.chat('/builder cancel');
  await expect(player).toHaveReceivedMessage('Preview cancelled.');
  player.chat('/builder confirm');
  await expect(player).toHaveReceivedMessage('The plan or clipboard expired.');
  player.chat('/builder undo');
  await expect(player).toHaveReceivedMessage('There is no completed operation available to undo.');
  await assertWorld(server, player, 'oak_planks');
  await clientBlocks(player, 'oak_planks', signal);
  assert.equal(itemCount(player, 'diamond_block'), 2);
  assert.equal(itemCount(player, 'oak_planks'), 0);
});

test('insufficient materials leave the whole replacement untouched; retry commits only once', async ({ player, server, signal }) => {
  await selectReplacement(player, server, signal, 1);
  player.chat('/builder replace oak_planks diamond_block');
  await expect(player).toHaveReceivedMessage('Block replacement 2 blocks');
  player.chat('/builder confirm');
  await expect(player).toHaveReceivedMessage('Required items or free inventory space are missing.');
  await assertWorld(server, player, 'oak_planks');
  await clientBlocks(player, 'oak_planks', signal);
  assert.equal(itemCount(player, 'diamond_block'), 1);
  assert.equal(itemCount(player, 'oak_planks'), 0);

  await player.giveItem('diamond_block', 1);
  await waitUntil(() => itemCount(player, 'diamond_block') === 2, { signal });
  player.chat('/builder confirm');
  await expect(player).toHaveReceivedMessage('completed: 2 blocks.');
  await waitUntil(() => itemCount(player, 'diamond_block') === 0 && itemCount(player, 'oak_planks') === 2, { signal });
  player.chat('/builder confirm');
  await expect(player).toHaveReceivedMessage('The plan or clipboard expired.');
  await assertWorld(server, player, 'diamond_block');
  await clientBlocks(player, 'diamond_block', signal);
  assert.equal(itemCount(player, 'diamond_block'), 0);
  assert.equal(itemCount(player, 'oak_planks'), 2);
});

test('committed replacement updates neighbour redstone physics and undo turns it off; ores stay blocked', async ({ player, server, signal }) => {
  await selectReplacement(player, server, signal);
  await player.giveItem('redstone_block', 2);
  await commands(server, player, ['minecraft:setblock 0 64 -1 redstone_lamp[lit=false]']);
  player.chat('/builder replace oak_planks diamond_ore');
  await expect(player).toHaveReceivedMessage('That material is not supported.');
  await assertWorld(server, player, 'oak_planks');
  player.chat('/builder replace oak_planks redstone_block');
  await expect(player).toHaveReceivedMessage('[▶ Build]');
  player.chat('/builder confirm');
  await expect(player).toHaveReceivedMessage('completed: 2 blocks.');
  await assertWorld(server, player, 'redstone_block');
  await waitUntil(() => player.bot.blockAt(player.bot.entity.position.clone().set(0, 64, -1))?.getProperties().lit === true, { signal });
  const lit = `lit-${randomUUID()}`;
  server.execute(`minecraft:execute if block 0 64 -1 redstone_lamp[lit=true] run tellraw ${player.username} {"text":"${lit}"}`);
  await expect(player).toHaveReceivedMessage(lit);
  assert.equal(itemCount(player, 'redstone_block'), 0);
  assert.equal(itemCount(player, 'oak_planks'), 2);
  const since = player.messageBuffer.length;
  player.chat('/builder undo');
  await expect(player).toHaveReceivedMessage('[▶ Build]', { since });
  player.chat('/builder confirm');
  await expect(player).toHaveReceivedMessage('completed: 2 blocks.', { since });
  await assertWorld(server, player, 'oak_planks');
  await waitUntil(() => player.bot.blockAt(player.bot.entity.position.clone().set(0, 64, -1))?.getProperties().lit === false, { signal });
  assert.equal(itemCount(player, 'redstone_block'), 2);
  assert.equal(itemCount(player, 'oak_planks'), 0);
});
