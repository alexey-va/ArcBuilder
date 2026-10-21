import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { expect, test, waitUntil } from '@drownek/plugwright';

test('selector moves its complete native frame without destroying or respawning its parts', async ({ player, server, signal }) => {
  const marker = `cursor-fixture-${randomUUID()}`;
  for (const command of [
    'minecraft:fill -3 64 -3 5 64 4 stone',
    'minecraft:fill -3 65 -3 5 69 4 air',
    `minecraft:clear ${player.username}`,
    `minecraft:tellraw ${player.username} {"text":"${marker}"}`,
  ]) server.execute(command);
  await expect(player).toHaveReceivedMessage(marker);
  await player.teleport(0.5, 65, 2.5);
  await waitUntil(() => player.bot.entity.position.distanceTo(player.bot.entity.position.clone().set(0.5, 65, 2.5)) < 0.1 &&
    player.bot.blockAt(player.bot.entity.position.clone().set(0, 64, 0))?.name === 'stone', { signal });
  await player.makeOp();
  player.chat(`/lp user ${player.username} permission set arcbuild.use true`);
  await expect(player).toHaveReceivedMessage('Set arcbuild.use to true');
  await player.deOp();
  await player.giveItem('echo_shard', 1);
  await player.bot.equip(player.bot.inventory.items().find(item => item.name === 'echo_shard'), 'hand');

  const blockDisplayType = player.bot.registry.entitiesByName.block_display.id;
  const whiteState = player.bot.registry.blocksByName.white_stained_glass.defaultState;
  assert.ok(Number.isInteger(blockDisplayType) && Number.isInteger(whiteState));
  const client = player.bot._client;
  const started = performance.now();
  const live = new Map();
  const whiteIds = new Set();
  const spawns = [];
  const destroys = [];
  const events = [];
  const steps = [];
  const point = (x, y, z) => player.bot.entity.position.clone().set(x, y, z);
  const spawn = packet => {
    if (packet.type !== blockDisplayType) return;
    live.set(packet.entityId, { x: packet.x, y: packet.y, z: packet.z });
    spawns.push(packet.entityId);
    events.push({ ms: performance.now() - started, event: 'spawn', id: packet.entityId });
  };
  const metadata = packet => {
    if (packet.metadata.some(entry => entry.key === 23 && entry.value === whiteState)) whiteIds.add(packet.entityId);
  };
  const teleport = packet => {
    if (!live.has(packet.entityId)) return;
    live.set(packet.entityId, { x: packet.x, y: packet.y, z: packet.z });
    events.push({ ms: performance.now() - started, event: 'update', id: packet.entityId });
  };
  const destroy = packet => {
    for (const id of packet.entityIds) {
      if (!live.delete(id)) continue;
      destroys.push(id);
      events.push({ ms: performance.now() - started, event: 'destroy', id });
    }
  };
  const snapshot = label => steps.push({ label, ms: performance.now() - started,
    parts: [...whiteIds].sort((a, b) => a - b).map(id => ({ id, ...live.get(id) })) });
  client.on('spawn_entity', spawn);
  client.on('entity_metadata', metadata);
  client.on('entity_teleport', teleport);
  client.on('entity_destroy', destroy);
  try {
    await player.bot.lookAt(point(0.5, 64.5, 0.5), false);
    player.chat('/builder wand');
    await expect(player).toHaveReceivedMessage('Your echo shard is now the builder selector.');
    await waitUntil(() => whiteIds.size === 12 && [...whiteIds].every(id => live.has(id)), { signal, timeout: 10000 });
    const ids = [...whiteIds].sort((a, b) => a - b);
    const initial = new Map(ids.map(id => [id, live.get(id)]));
    snapshot('cell-0');
    for (const x of [1, 2, 1, 0]) {
      await player.bot.lookAt(point(x + 0.5, 64.5, 0.5), false);
      await waitUntil(() => ids.every(id => {
        const actual = live.get(id);
        const first = initial.get(id);
        return actual && Math.abs(actual.x - first.x - x) < 1e-5 &&
          Math.abs(actual.y - first.y) < 1e-5 && Math.abs(actual.z - first.z) < 1e-5;
      }), { signal, timeout: 10000, message: `The same twelve native frame parts did not reach cell ${x}` });
      snapshot(`cell-${x}`);
      assert.deepEqual([...whiteIds].sort((a, b) => a - b), ids);
      assert.deepEqual(spawns.slice().sort((a, b) => a - b), ids);
      assert.deepEqual(destroys, []);
    }
  } finally {
    client.removeListener('spawn_entity', spawn);
    client.removeListener('entity_metadata', metadata);
    client.removeListener('entity_teleport', teleport);
    client.removeListener('entity_destroy', destroy);
    console.log(`ARC_BUILDER_CURSOR_TRACE ${JSON.stringify({ scope: 'real Paper packets; not native rendered frames', steps, events })}`);
  }
});
