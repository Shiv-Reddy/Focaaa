import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { database } from './d1.mjs';
import { createRelayHandler } from '../src/index.ts';
import { purgeExpired } from '../src/messages.ts';

const idA = 'aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa';
const idB = 'bbbbbbbb-bbbb-4bbb-bbbb-bbbbbbbbbbbb';
const idC = 'cccccccc-cccc-4ccc-cccc-cccccccccccc';
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const bytes = Buffer.from('opaque-authenticated-ciphertext');
const payloadBase64 = bytes.toString('base64');
const ciphertextSha256 = hash(bytes);

function setup() {
  const DB = database();
  const handler = createRelayHandler({
    authenticate: async request => request.headers.get('x-test-uid') ?? 'unknown',
    sendHint: async () => {}
  });
  const env = { DB, FIREBASE_PROJECT_ID: 'focaaa-dev' };
  async function call(uid, name, data = {}) {
    const response = await handler(new Request(`https://relay.test/v1/${name}`, {
      method: 'POST', headers: { 'x-test-uid': uid }, body: JSON.stringify(data)
    }), env, { waitUntil() {} });
    return { status: response.status, ...await response.json() };
  }
  async function pair() {
    await call('owner', 'registerDevice', { deviceId: idA, fcmToken: 'a' });
    await call('joiner', 'registerDevice', { deviceId: idB, fcmToken: 'b' });
    const token = 'A'.repeat(32);
    await call('owner', 'createPairingInvite', { deviceId: idA, token });
    const joined = await call('joiner', 'joinPairingInvite', { deviceId: idB, token,
      keyPackageBase64: Buffer.from('key-package').toString('base64') });
    const conversationId = joined.result.conversationId;
    await call('owner', 'publishPairingWelcome', { deviceId: idA, conversationId,
      welcomeBase64: Buffer.from('welcome').toString('base64'),
      ratchetTreeBase64: Buffer.from('tree').toString('base64') });
    await call('owner', 'confirmPairing', { deviceId: idA, conversationId });
    await call('joiner', 'confirmPairing', { deviceId: idB, conversationId });
    return conversationId;
  }
  return { DB, env, call, pair };
}

test('only active sender uploads ciphertext; only paired recipient fetches it', async () => {
  const f = setup();
  try {
    const conversationId = await f.pair();
    await f.call('other', 'registerDevice', { deviceId: idC, fcmToken: 'c' });
    const common = { conversationId, mediaId: 'media-1' };
    const begin = { ...common, deviceId: idA, ciphertextSize: bytes.length, ciphertextSha256 };
    assert.equal((await f.call('owner', 'beginMediaUpload', begin)).result.chunkCount, 1);
    assert.equal((await f.call('owner', 'beginMediaUpload', begin)).status, 200);
    assert.equal((await f.call('joiner', 'beginMediaUpload', { ...begin, deviceId: idB })).status, 409);
    assert.equal((await f.call('other', 'fetchMediaManifest', { ...common, deviceId: idC })).status, 403);
    assert.equal((await f.call('joiner', 'uploadMediaChunk', { ...common, deviceId: idB,
      chunkIndex: 0, payloadBase64, payloadSha256: ciphertextSha256 })).status, 403);
    assert.equal((await f.call('owner', 'completeMediaUpload', { ...common, deviceId: idA })).status, 412);
    const chunk = { ...common, deviceId: idA, chunkIndex: 0, payloadBase64, payloadSha256: ciphertextSha256 };
    assert.equal((await f.call('owner', 'uploadMediaChunk', chunk)).status, 200);
    assert.equal((await f.call('owner', 'uploadMediaChunk', chunk)).status, 200);
    assert.equal((await f.call('owner', 'uploadMediaChunk', { ...chunk,
      payloadBase64: Buffer.alloc(bytes.length, 5).toString('base64'),
      payloadSha256: hash(Buffer.alloc(bytes.length, 5)) })).status, 409);
    assert.equal((await f.call('owner', 'completeMediaUpload', { ...common, deviceId: idA })).status, 200);
    assert.equal((await f.call('owner', 'fetchMediaManifest', { ...common, deviceId: idA })).status, 404);
    const manifest = await f.call('joiner', 'fetchMediaManifest', { ...common, deviceId: idB });
    assert.equal(manifest.result.ciphertextSha256, ciphertextSha256);
    const fetched = await f.call('joiner', 'fetchMediaChunk', { ...common, deviceId: idB, chunkIndex: 0 });
    assert.equal(fetched.result.payloadBase64, payloadBase64);
    await f.call('owner', 'revokePairing', { conversationId, deviceId: idA });
    assert.equal((await f.call('joiner', 'fetchMediaChunk', { ...common, deviceId: idB, chunkIndex: 0 })).status, 410);
    assert.equal((await f.DB.prepare('SELECT COUNT(*) AS n FROM media_uploads').bind().first()).n, 0);
  } finally { f.DB.close(); }
});

test('media limits, corrupt chunk, cancellation, and expiry are enforced', async () => {
  const f = setup();
  try {
    const conversationId = await f.pair();
    const common = { conversationId, deviceId: idA, mediaId: 'media-2' };
    assert.equal((await f.call('owner', 'beginMediaUpload', { ...common,
      ciphertextSize: 10 * 1024 * 1024 + 17, ciphertextSha256 })).status, 400);
    assert.equal((await f.call('owner', 'beginMediaUpload', { ...common,
      ciphertextSize: bytes.length, ciphertextSha256 })).status, 200);
    assert.equal((await f.call('owner', 'uploadMediaChunk', { ...common, chunkIndex: 0,
      payloadBase64, payloadSha256: '0'.repeat(64) })).status, 400);
    assert.equal((await f.call('owner', 'cancelMediaUpload', common)).result.cancelled, true);
    assert.equal((await f.call('owner', 'completeMediaUpload', common)).status, 404);
    await f.call('owner', 'beginMediaUpload', { ...common, ciphertextSize: bytes.length, ciphertextSha256 });
    const stale = Date.now() - 1;
    f.DB.prepare('UPDATE media_uploads SET expires_at_ms = ? WHERE media_id = ?').bind(stale, common.mediaId).run();
    await purgeExpired(f.env, Date.now());
    assert.equal((await f.DB.prepare('SELECT COUNT(*) AS n FROM media_uploads').bind().first()).n, 0);
  } finally { f.DB.close(); }
});

test('free storage quota is reserved at begin and released by cancellation', async () => {
  const f = setup();
  try {
    const conversationId = await f.pair();
    const large = 10 * 1024 * 1024 + 16;
    for (let index = 0; index < 6; index++) {
      assert.equal((await f.call('owner', 'beginMediaUpload', {
        conversationId, deviceId: idA, mediaId: `quota-${index}`,
        ciphertextSize: large, ciphertextSha256: 'b'.repeat(64)
      })).status, 200);
    }
    assert.equal((await f.call('owner', 'beginMediaUpload', {
      conversationId, deviceId: idA, mediaId: 'quota-extra',
      ciphertextSize: large, ciphertextSha256: 'b'.repeat(64)
    })).status, 429);
    assert.equal((await f.call('owner', 'cancelMediaUpload', {
      conversationId, deviceId: idA, mediaId: 'quota-0'
    })).result.cancelled, true);
    assert.equal((await f.call('owner', 'beginMediaUpload', {
      conversationId, deviceId: idA, mediaId: 'quota-extra',
      ciphertextSize: large, ciphertextSha256: 'b'.repeat(64)
    })).status, 200);
  } finally { f.DB.close(); }
});
