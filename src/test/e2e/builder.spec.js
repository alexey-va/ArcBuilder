import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
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

function itemCount(player, material) {
  return player.bot.inventory.items().filter(item => item.name === material).reduce((n, item) => n + item.count, 0);
}

test('survival selection previews without world mutation; confirm and undo update both clients', async ({ player, server, createPlayer, signal }) => {
  await selectReplacement(player, server, signal);
  const observer = await createPlayer({ username: 'BuildObserver' });
  await observer.teleport(2.5, 65, 1.5);
  await clientBlocks(observer, 'oak_planks', signal);
  player.chat('/builder replace oak_planks diamond_block');
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
