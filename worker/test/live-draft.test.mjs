import test from 'node:test';
import assert from 'node:assert/strict';
import { database } from './d1.mjs';
import { authorizeLiveDraft, validateLiveDraftFrame, LiveDraftRoom } from '../src/live-draft.ts';
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

test('only the active registered pair devices can join the live draft socket', async () => {
  const DB = fixture();
  try {
    assert.equal(await authorizeLiveDraft({ DB }, 'owner', ownerDevice, pairId), 'owner');
    assert.equal(await authorizeLiveDraft({ DB }, 'joiner', joinerDevice, pairId), 'joiner');
    await assert.rejects(authorizeLiveDraft({ DB }, 'outsider', ownerDevice, pairId));
    await assert.rejects(authorizeLiveDraft({ DB }, 'owner', joinerDevice, pairId));
    DB.prepare('UPDATE devices SET revoked = 1 WHERE uid = ?').bind('owner').run();
    await assert.rejects(authorizeLiveDraft({ DB }, 'owner', ownerDevice, pairId));
    DB.prepare("UPDATE pairs SET status = 'revoked' WHERE pair_id = ?").bind(pairId).run();
    await assert.rejects(authorizeLiveDraft({ DB }, 'joiner', joinerDevice, pairId));
  } finally { DB.close(); }
});

test('draft frames reject plaintext, invalid sequence, and oversized ciphertext', () => {
  const good = { type: 'edit', sessionId: 'session_123', sequence: 1, epoch: 2,
    payloadBase64: Buffer.from('opaque ciphertext').toString('base64') };
  assert.deepEqual(validateLiveDraftFrame(JSON.stringify(good)), good);
  assert.throws(() => validateLiveDraftFrame(JSON.stringify({ ...good, text: 'hidden draft' })));
  assert.throws(() => validateLiveDraftFrame(JSON.stringify({ ...good, sequence: -1 })));
  assert.throws(() => validateLiveDraftFrame(JSON.stringify({ ...good, sequence: 0 })));
  assert.throws(() => validateLiveDraftFrame(JSON.stringify({ ...good, payloadBase64: Buffer.alloc(30000).toString('base64') })));
  assert.throws(() => validateLiveDraftFrame(JSON.stringify({ ...good, type: 'unknown' })));
  assert.deepEqual(validateLiveDraftFrame(JSON.stringify({ type: 'start', sessionId: 'session_123', sequence: 0 })),
    { type: 'start', sessionId: 'session_123', sequence: 0 });
});

test('the room forwards opaque edits only to the other active pair member', async () => {
  const DB = fixture();
  const makeSocket = (uid, deviceId, role) => ({
    attachment: { uid, deviceId, pairId, role, second: 0, count: 0 }, sent: [], closed: false,
    deserializeAttachment() { return this.attachment; },
    serializeAttachment(value) { this.attachment = value; },
    send(value) { this.sent.push(JSON.parse(value)); },
    close() { this.closed = true; }
  });
  const owner = makeSocket('owner', ownerDevice, 'owner');
  const joiner = makeSocket('joiner', joinerDevice, 'joiner');
  const room = new LiveDraftRoom({ getWebSockets: () => [owner, joiner] }, { DB });
  const frame = { type: 'edit', sessionId: 'session_123', sequence: 1, epoch: 2,
    payloadBase64: Buffer.from('opaque ciphertext').toString('base64') };
  try {
    await room.webSocketMessage(owner, JSON.stringify(frame));
    assert.deepEqual(joiner.sent, [frame]);
    assert.deepEqual(owner.sent, []);
    DB.prepare("UPDATE pairs SET status = 'revoked' WHERE pair_id = ?").bind(pairId).run();
    await room.webSocketMessage(owner, JSON.stringify(frame));
    assert.equal(owner.closed, true);
    assert.deepEqual(joiner.sent, [frame]);
  } finally { DB.close(); }
});

test('the upgrade route checks identity before passing verified membership to the room', async () => {
  const DB = fixture();
  const forwarded = [];
  const env = { DB, FIREBASE_PROJECT_ID: 'focaaa-dev',
    LIVE_DRAFT: { idFromName: value => value, get: () => ({ fetch: request => {
      forwarded.push(request);
      return new Response('routed');
    } }) } };
  const handler = createRelayHandler({ authenticate: async request => request.headers.get('x-test-uid'), sendHint: async () => {} });
  const request = (uid, deviceId) => new Request(
    `https://relay.test/v1/liveDraft?deviceId=${deviceId}&conversationId=${pairId}`,
    { headers: { upgrade: 'websocket', 'x-test-uid': uid } });
  try {
    assert.equal((await handler(request('owner', ownerDevice), env, {})).status, 200);
    assert.equal(forwarded.length, 1);
    assert.equal(forwarded[0].headers.get('x-verified-role'), 'owner');
    assert.equal(forwarded[0].headers.get('authorization'), null);
    assert.equal((await handler(request('outsider', ownerDevice), env, {})).status, 403);
    assert.equal(forwarded.length, 1);
  } finally { DB.close(); }
});

test('revocation closes both live sockets before another draft can appear', async () => {
  const closed = [];
  const sockets = [{ close: () => closed.push('owner') }, { close: () => closed.push('joiner') }];
  const room = new LiveDraftRoom({ getWebSockets: () => sockets }, {});
  const response = await room.fetch(new Request('https://live-draft.internal/revoke', { method: 'POST' }));
  assert.equal(response.status, 200);
  assert.deepEqual(closed, ['owner', 'joiner']);
});

test('closing a replaced socket does not erase the partner draft', () => {
  const sent = [];
  const socket = role => ({
    deserializeAttachment: () => ({ role }),
    send: frame => sent.push(JSON.parse(frame))
  });
  const oldOwner = socket('owner');
  const newOwner = socket('owner');
  const joiner = socket('joiner');
  let connected = [oldOwner, newOwner, joiner];
  const room = new LiveDraftRoom({ getWebSockets: () => connected }, {});
  room.webSocketClose(oldOwner);
  assert.deepEqual(sent, []);
  connected = [joiner];
  room.webSocketClose(newOwner);
  assert.deepEqual(sent, [{ type: 'peer-offline' }]);
});
