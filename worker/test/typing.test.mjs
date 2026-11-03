import test from 'node:test';
import assert from 'node:assert/strict';
import { database } from './d1.mjs';
import { authorizeTyping, validateTypingFrame, LiveDraftRoom } from '../src/typing.ts';
import { createRelayHandler } from '../src/index.ts';

const ownerDevice = 'aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa';
const joinerDevice = 'bbbbbbbb-bbbb-4bbb-bbbb-bbbbbbbbbbbb';
const pairId = 'cccccccc-cccc-4ccc-cccc-cccccccccccc';

function fixture() {
  const DB = database();
  const now = Date.now();
  DB.prepare('INSERT INTO devices(uid,device_id,updated_at_ms) VALUES(?,?,?)').bind('owner', ownerDevice, now).run();
  DB.prepare('INSERT INTO devices(uid,device_id,updated_at_ms) VALUES(?,?,?)').bind('joiner', joinerDevice, now).run();
  DB.prepare(`INSERT INTO pairs(pair_id,owner_uid,owner_device_id,joiner_uid,joiner_device_id,
    status,pairing_status,created_at_ms,updated_at_ms) VALUES(?,?,?,?,?,'active','complete',?,?)`)
    .bind(pairId, 'owner', ownerDevice, 'joiner', joinerDevice, now, now).run();
  return DB;
}

test('typing route requires registered active pair members', async () => {
  const DB = fixture();
  try {
    assert.equal(await authorizeTyping({ DB }, 'owner', ownerDevice, pairId), 'owner');
    assert.equal(await authorizeTyping({ DB }, 'joiner', joinerDevice, pairId), 'joiner');
    await assert.rejects(authorizeTyping({ DB }, 'owner', joinerDevice, pairId));
    DB.prepare("UPDATE pairs SET status = 'revoked' WHERE pair_id = ?").bind(pairId).run();
    await assert.rejects(authorizeTyping({ DB }, 'joiner', joinerDevice, pairId));
  } finally { DB.close(); }
});

test('only content-free typing frames pass validation', () => {
  assert.deepEqual(validateTypingFrame('{"type":"typing","active":true}'), { type: 'typing', active: true });
  for (const bad of [
    '{"type":"typing","active":true,"text":"secret"}',
    '{"type":"typing","active":"true"}',
    '{"type":"edit","active":true}',
    '{"type":"typing","active":true,"removed":"secret"}'
  ]) assert.throws(() => validateTypingFrame(bad));
});

test('both roles receive only the other member typing status', async () => {
  const DB = fixture();
  const socket = (uid, deviceId, role) => ({
    attachment: { uid, deviceId, pairId, role, second: 0, count: 0, protocol: 'typing-v1' }, sent: [], closed: false,
    deserializeAttachment() { return this.attachment; },
    serializeAttachment(value) { this.attachment = value; },
    send(value) { this.sent.push(JSON.parse(value)); },
    close() { this.closed = true; }
  });
  const owner = socket('owner', ownerDevice, 'owner');
  const joiner = socket('joiner', joinerDevice, 'joiner');
  const room = new LiveDraftRoom({ getWebSockets: () => [owner, joiner] }, { DB });
  try {
    await room.webSocketMessage(owner, '{"type":"typing","active":true}');
    await room.webSocketMessage(joiner, '{"type":"typing","active":false}');
    assert.deepEqual(owner.sent, [{ type: 'typing', active: false }]);
    assert.deepEqual(joiner.sent, [{ type: 'typing', active: true }]);
    await room.webSocketMessage(joiner, '{"type":"typing","active":true,"text":"secret"}');
    assert.equal(joiner.closed, true);
    assert.equal(owner.sent.length, 1);
  } finally { DB.close(); }
});

test('old live-draft endpoint is no longer available', async () => {
  const DB = fixture();
  try {
    const handler = createRelayHandler({ authenticate: async () => 'owner', sendHint: async () => {} });
    const response = await handler(new Request('https://relay.test/v1/liveDraft', { headers: { Upgrade: 'websocket' } }),
      { DB, FIREBASE_PROJECT_ID: 'test' }, {});
    assert.equal(response.status, 404);
  } finally { DB.close(); }
});
