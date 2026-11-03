import test from 'node:test';
import assert from 'node:assert/strict';
import { database } from './d1.mjs';
import { createRelayHandler } from '../src/index.ts';
import { purgeExpired } from '../src/messages.ts';

const token = 'A'.repeat(32);
const idA = 'aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa';
const idB = 'bbbbbbbb-bbbb-4bbb-bbbb-bbbbbbbbbbbb';
const idC = 'cccccccc-cccc-4ccc-cccc-cccccccccccc';
const payload = Buffer.from('opaque-ciphertext').toString('base64');
const keyPackage = Buffer.from('opaque-key-package').toString('base64');
const welcome = Buffer.from('opaque-welcome').toString('base64');
const tree = Buffer.from('opaque-tree').toString('base64');

function setup() {
  const DB = database();
  const hints = [];
  const handler = createRelayHandler({
    authenticate: async request => request.headers.get('x-test-uid') ?? (() => { throw Error('unauthenticated'); })(),
    sendHint: async (_env, deviceToken, pairId) => hints.push({ deviceToken, pairId })
  });
  const env = { DB, FIREBASE_PROJECT_ID: 'focaaa-dev' };
  async function call(uid, name, data = {}) {
    const request = new Request(`https://relay.test/v1/${name}`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'x-test-uid': uid },
      body: JSON.stringify(data)
    });
    const response = await handler(request, env, { waitUntil() {} });
    const body = await response.json();
    return { status: response.status, ...body };
  }
  return { call, hints, DB };
}

async function pair(fixture, inviteToken = token, liveDraftConsent = false) {
  const { call } = fixture;
  assert.equal((await call('owner', 'registerDevice', { deviceId: idA, fcmToken: 'fcm-a' })).status, 200);
  assert.equal((await call('joiner', 'registerDevice', { deviceId: idB, fcmToken: 'fcm-b' })).status, 200);
  const invite = await call('owner', 'createPairingInvite', { deviceId: idA, token: inviteToken });
  assert.equal(invite.status, 200);
  const joined = await call('joiner', 'joinPairingInvite', { deviceId: idB, token: inviteToken,
    keyPackageBase64: keyPackage, liveDraftConsent });
  assert.equal(joined.status, 200);
  assert.equal(joined.result.status, 'pending-confirmation');
  const conversationId = joined.result.conversationId;
  const pending = await call('owner', 'fetchPairingRequest', { deviceId: idA, token: inviteToken });
  assert.equal(pending.result.joinerKeyPackageBase64, keyPackage);
  const published = await call('owner', 'publishPairingWelcome', { deviceId: idA, conversationId, welcomeBase64: welcome, ratchetTreeBase64: tree });
  assert.equal(published.status, 200);
  assert.equal((await call('joiner', 'fetchPairingWelcome', { deviceId: idB, conversationId })).result.welcomeBase64, welcome);
  assert.equal((await call('owner', 'confirmPairing', { deviceId: idA, conversationId })).result.active, false);
  assert.equal((await call('joiner', 'confirmPairing', { deviceId: idB, conversationId })).result.active, true);
  return conversationId;
}

test('join choice is stored on the pair and legacy joins leave live drafts off', async () => {
  const without = setup();
  const withConsent = setup();
  try {
    const oldPair = await pair(without);
    const optedPair = await pair(withConsent, token, true);
    assert.equal(without.DB.prepare('SELECT live_draft_consent FROM pairs WHERE pair_id = ?')
      .bind(oldPair).first().live_draft_consent, 0);
    assert.equal(withConsent.DB.prepare('SELECT live_draft_consent FROM pairs WHERE pair_id = ?')
      .bind(optedPair).first().live_draft_consent, 1);
  } finally { without.DB.close(); withConsent.DB.close(); }
});

test('single-use invite and both confirmations create one active pair', async () => {
  const f = setup();
  try {
    const conversationId = await pair(f);
    assert.equal((await f.call('owner', 'confirmPairing', { deviceId: idA, conversationId })).result.active, true);
    assert.equal((await f.call('other', 'registerDevice', { deviceId: idC, fcmToken: 'fcm-c' })).status, 200);
    const replay = await f.call('other', 'joinPairingInvite', { deviceId: idC, token, keyPackageBase64: keyPackage });
    assert.equal(replay.status, 412);
  } finally { f.DB.close(); }
});

test('ciphertext receipt makes send retry safe and only recipient can acknowledge', async () => {
  const f = setup();
  try {
    const conversationId = await pair(f);
    const data = { deviceId: idA, conversationId, envelopeId: 'message-1', payloadBase64: payload };
    assert.equal((await f.call('owner', 'sendEnvelope', data)).status, 200);
    assert.equal(f.hints.length, 1);
    assert.equal((await f.call('owner', 'sendEnvelope', data)).status, 200);
    assert.equal(f.hints.length, 1);
    assert.equal((await f.call('joiner', 'fetchPendingEnvelopes', { deviceId: idB, conversationId })).result.envelopes.length, 1);
    assert.equal((await f.call('owner', 'acknowledgeEnvelopes', { deviceId: idA, conversationId, envelopeIds: ['message-1'] })).status, 403);
    assert.equal((await f.call('joiner', 'acknowledgeEnvelopes', { deviceId: idB, conversationId, envelopeIds: ['message-1'] })).result.deleted, 1);
    assert.equal((await f.call('owner', 'sendEnvelope', data)).status, 200);
    assert.equal((await f.call('joiner', 'fetchPendingEnvelopes', { deviceId: idB, conversationId })).result.envelopes.length, 0);
    assert.equal((await f.call('owner', 'sendEnvelope', { ...data, payloadBase64: Buffer.from('different').toString('base64') })).status, 409);
  } finally { f.DB.close(); }
});

test('message status advances only after recipient delivery and explicit read', async () => {
  const f = setup();
  try {
    const conversationId = await pair(f);
    const ids = ['status-message-1'];
    const sender = { deviceId: idA, conversationId, envelopeIds: ids };
    const recipient = { deviceId: idB, conversationId, envelopeIds: ids };
    await f.call('owner', 'sendEnvelope', { deviceId: idA, conversationId, envelopeId: ids[0], payloadBase64: payload });
    assert.deepEqual((await f.call('owner', 'fetchMessageStatuses', sender)).result.statuses,
      [{ envelopeId: ids[0], level: 1 }]);
    assert.deepEqual((await f.call('joiner', 'markRead', recipient)).result.readEnvelopeIds, []);
    assert.equal((await f.call('joiner', 'acknowledgeEnvelopes', recipient)).result.deleted, 1);
    assert.deepEqual((await f.call('owner', 'fetchMessageStatuses', sender)).result.statuses,
      [{ envelopeId: ids[0], level: 2 }]);
    assert.deepEqual((await f.call('joiner', 'markRead', recipient)).result.readEnvelopeIds, ids);
    assert.deepEqual((await f.call('joiner', 'markRead', recipient)).result.readEnvelopeIds, ids);
    assert.deepEqual((await f.call('owner', 'fetchMessageStatuses', sender)).result.statuses,
      [{ envelopeId: ids[0], level: 3 }]);
    assert.equal(f.hints.length, 1, 'read receipts must not create a new push alert');
  } finally { f.DB.close(); }
});

test('only the recipient can mark read and only the sender can query a status', async () => {
  const f = setup();
  try {
    const conversationId = await pair(f);
    await f.call('owner', 'sendEnvelope', { deviceId: idA, conversationId, envelopeId: 'private-1', payloadBase64: payload });
    await f.call('joiner', 'acknowledgeEnvelopes', { deviceId: idB, conversationId, envelopeIds: ['private-1'] });
    assert.deepEqual((await f.call('owner', 'markRead', { deviceId: idA, conversationId, envelopeIds: ['private-1'] })).result.readEnvelopeIds, []);
    assert.deepEqual((await f.call('joiner', 'fetchMessageStatuses', { deviceId: idB, conversationId, envelopeIds: ['private-1'] })).result.statuses, []);
    assert.equal((await f.call('outsider', 'registerDevice', { deviceId: idC, fcmToken: 'fcm-c' })).status, 200);
    assert.equal((await f.call('outsider', 'fetchMessageStatuses', { deviceId: idC, conversationId, envelopeIds: ['private-1'] })).status, 403);
    assert.equal((await f.call('outsider', 'markRead', { deviceId: idC, conversationId, envelopeIds: ['private-1'] })).status, 403);
    assert.equal((await f.call('owner', 'fetchMessageStatuses', { deviceId: idA, conversationId, envelopeIds: [] })).status, 400);
    assert.equal((await f.call('owner', 'fetchMessageStatuses', { deviceId: idA, conversationId, envelopeIds: Array(101).fill('private-1') })).status, 400);
  } finally { f.DB.close(); }
});

test('revoke pair blocks send and purges pending ciphertext', async () => {
  const f = setup();
  try {
    const conversationId = await pair(f);
    await f.call('owner', 'sendEnvelope', { deviceId: idA, conversationId, envelopeId: 'message-2', payloadBase64: payload });
    assert.equal((await f.call('owner', 'revokePairing', { deviceId: idA, conversationId })).status, 200);
    const stoppedSend = await f.call('owner', 'sendEnvelope', { deviceId: idA, conversationId, envelopeId: 'message-3', payloadBase64: payload });
    assert.equal(stoppedSend.status, 410);
    assert.equal(stoppedSend.error.code, 'pair-revoked');
    const stoppedFetch = await f.call('joiner', 'fetchPendingEnvelopes', { deviceId: idB, conversationId });
    assert.equal(stoppedFetch.status, 410);
    assert.equal(stoppedFetch.error.code, 'pair-revoked');
  } finally { f.DB.close(); }
});

test('old revoked pair metadata is purged while active pairs remain', async () => {
  const f = setup();
  try {
    const conversationId = await pair(f);
    assert.equal((await f.call('owner', 'revokePairing', { deviceId: idA, conversationId })).status, 200);
    const now = Date.now();
    f.DB.prepare('UPDATE pairs SET updated_at_ms = ? WHERE pair_id = ?')
      .bind(now - 31 * 24 * 60 * 60_000, conversationId).run();
    await purgeExpired({ DB: f.DB }, now);
    assert.equal(f.DB.prepare('SELECT COUNT(*) AS n FROM pairs WHERE pair_id = ?').bind(conversationId).first().n, 0);
    const stale = await f.call('joiner', 'fetchPendingEnvelopes', { deviceId: idB, conversationId });
    assert.equal(stale.status, 410);
    assert.equal(stale.error.code, 'pair-revoked');
    const replacement = await pair(f, 'B'.repeat(32));
    assert.equal((await f.call('owner', 'sendEnvelope', { deviceId: idA, conversationId: replacement, envelopeId: 'fresh-1', payloadBase64: payload })).status, 200);
  } finally { f.DB.close(); }
});

test('one owner keeps two contacts isolated when one pair is revoked', async () => {
  const f = setup();
  try {
    const firstPair = await pair(f);
    const secondToken = 'B'.repeat(32);
    assert.equal((await f.call('other', 'registerDevice', { deviceId: idC, fcmToken: '' })).status, 200);
    assert.equal((await f.call('owner', 'createPairingInvite', { deviceId: idA, token: secondToken })).status, 200);
    const joined = await f.call('other', 'joinPairingInvite', {
      deviceId: idC, token: secondToken, keyPackageBase64: keyPackage
    });
    assert.equal(joined.status, 200);
    const secondPair = joined.result.conversationId;
    assert.notEqual(secondPair, firstPair);
    assert.equal((await f.call('owner', 'fetchPairingRequest', { deviceId: idA, token: secondToken })).status, 200);
    assert.equal((await f.call('owner', 'publishPairingWelcome', {
      deviceId: idA, conversationId: secondPair, welcomeBase64: welcome, ratchetTreeBase64: tree
    })).status, 200);
    assert.equal((await f.call('other', 'fetchPairingWelcome', { deviceId: idC, conversationId: secondPair })).status, 200);
    assert.equal((await f.call('owner', 'confirmPairing', { deviceId: idA, conversationId: secondPair })).result.active, false);
    assert.equal((await f.call('other', 'confirmPairing', { deviceId: idC, conversationId: secondPair })).result.active, true);

    const envelopeId = 'shared-id-across-pairs';
    assert.equal((await f.call('owner', 'sendEnvelope', {
      deviceId: idA, conversationId: firstPair, envelopeId, payloadBase64: payload
    })).status, 200);
    assert.equal((await f.call('owner', 'sendEnvelope', {
      deviceId: idA, conversationId: secondPair, envelopeId, payloadBase64: payload
    })).status, 200);
    assert.equal((await f.call('joiner', 'fetchPendingEnvelopes', { deviceId: idB, conversationId: firstPair })).result.envelopes.length, 1);
    assert.equal((await f.call('other', 'fetchPendingEnvelopes', { deviceId: idC, conversationId: secondPair })).result.envelopes.length, 1);
    assert.equal((await f.call('joiner', 'fetchPendingEnvelopes', { deviceId: idB, conversationId: secondPair })).status, 403);
    assert.equal((await f.call('other', 'fetchPendingEnvelopes', { deviceId: idC, conversationId: firstPair })).status, 403);

    assert.equal((await f.call('owner', 'revokePairing', { deviceId: idA, conversationId: firstPair })).status, 200);
    assert.notEqual((await f.call('joiner', 'fetchPendingEnvelopes', { deviceId: idB, conversationId: firstPair })).status, 200);
    assert.equal((await f.call('other', 'fetchPendingEnvelopes', { deviceId: idC, conversationId: secondPair })).result.envelopes.length, 1);
    assert.equal((await f.call('owner', 'sendEnvelope', {
      deviceId: idA, conversationId: secondPair, envelopeId: 'second-still-active', payloadBase64: payload
    })).status, 200);
  } finally { f.DB.close(); }
});

test('pairing registration remains available when FCM cannot provide a push token', async () => {
  const f = setup();
  try {
    assert.equal((await f.call('owner', 'registerDevice', { deviceId: idA, fcmToken: '' })).status, 200);
    assert.equal((await f.call('owner', 'createPairingInvite', { deviceId: idA, token })).status, 200);
  } finally { f.DB.close(); }
});

test('expired invite is rejected without creating a pair', async () => {
  const f = setup();
  try {
    await f.call('owner', 'registerDevice', { deviceId: idA, fcmToken: '' });
    await f.call('joiner', 'registerDevice', { deviceId: idB, fcmToken: '' });
    await f.call('owner', 'createPairingInvite', { deviceId: idA, token });
    f.DB.prepare('UPDATE invites SET expires_at_ms = ? WHERE owner_uid = ?').bind(Date.now() - 1, 'owner').run();
    assert.equal((await f.call('joiner', 'joinPairingInvite', { deviceId: idB, token, keyPackageBase64: keyPackage })).status, 412);
    assert.equal(f.DB.prepare('SELECT COUNT(*) AS n FROM pairs').bind().first().n, 0);
  } finally { f.DB.close(); }
});

test('expired consumed invite cannot be reused by its original joiner', async () => {
  const f = setup();
  try {
    await f.call('owner', 'registerDevice', { deviceId: idA, fcmToken: '' });
    await f.call('joiner', 'registerDevice', { deviceId: idB, fcmToken: '' });
    await f.call('owner', 'createPairingInvite', { deviceId: idA, token });
    assert.equal((await f.call('joiner', 'joinPairingInvite', { deviceId: idB, token, keyPackageBase64: keyPackage })).status, 200);
    f.DB.prepare('UPDATE invites SET expires_at_ms = ? WHERE owner_uid = ?').bind(Date.now() - 1, 'owner').run();
    assert.equal((await f.call('joiner', 'joinPairingInvite', { deviceId: idB, token, keyPackageBase64: keyPackage })).status, 412);
  } finally { f.DB.close(); }
});

test('device revocation disables its pairs and deletes pending ciphertext', async () => {
  const f = setup();
  try {
    const conversationId = await pair(f);
    await f.call('owner', 'sendEnvelope', { deviceId: idA, conversationId, envelopeId: 'message-4', payloadBase64: payload });
    assert.equal((await f.call('owner', 'revokeDevice', { deviceId: idA })).status, 200);
    assert.equal(f.DB.prepare('SELECT COUNT(*) AS n FROM envelopes').bind().first().n, 0);
    assert.equal((await f.call('owner', 'registerDevice', { deviceId: idA, fcmToken: 'new' })).status, 412);
    assert.notEqual((await f.call('joiner', 'fetchPendingEnvelopes', { deviceId: idB, conversationId })).status, 200);
  } finally { f.DB.close(); }
});

test('acknowledging a full queue stays within the free D1 per-request query limit', async () => {
  const f = setup();
  try {
    const conversationId = await pair(f);
    const ids = Array.from({ length: 100 }, (_, index) => `bulk-${index}`);
    for (const id of ids) {
      f.DB.prepare(`INSERT INTO envelope_receipts(pair_id,envelope_id,sender_uid,sender_device_id,payload_sha256,expires_at_ms,creation_nonce)
        VALUES(?,?,?,?,?,?,?)`).bind(conversationId, id, 'owner', idA, 'digest', Date.now() + 60_000, id).run();
      f.DB.prepare(`INSERT INTO envelopes(pair_id,envelope_id,sender_uid,sender_device_id,recipient_uid,payload_base64,created_at_ms,expires_at_ms)
        VALUES(?,?,?,?,?,?,?,?)`).bind(conversationId, id, 'owner', idA, 'joiner', payload, Date.now(), Date.now() + 60_000).run();
    }
    f.DB.limitQueries(50);
    const response = await f.call('joiner', 'acknowledgeEnvelopes', { deviceId: idB, conversationId, envelopeIds: ids });
    assert.equal(response.status, 200);
    assert.equal(response.result.deleted, 100);
  } finally { f.DB.close(); }
});
