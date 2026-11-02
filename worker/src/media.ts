import {
  base64Of, deviceIdOf, fail, idOf, rateLimit, requireDevice, requireMemberDevice,
  requirePair, sha256Hex, type Data, type Env
} from './common.ts';

const CHUNK_BYTES = 128 * 1024;
const MAX_CIPHERTEXT_BYTES = 10 * 1024 * 1024 + 16;
const PAIR_QUOTA_BYTES = 64 * 1024 * 1024;
const GLOBAL_QUOTA_BYTES = 240 * 1024 * 1024;
const MEDIA_TTL_MS = 7 * 24 * 60 * 60_000;
const HEX_SHA256 = /^[0-9a-f]{64}$/;

interface Upload {
  sender_uid: string;
  sender_device_id: string;
  ciphertext_size: number;
  ciphertext_sha256: string;
  chunk_count: number;
  complete: number;
  expires_at_ms: number;
}

function integerOf(data: Data, name: string, minimum: number, maximum: number): number {
  const value = data[name];
  if (typeof value !== 'number' || !Number.isInteger(value) || value < minimum || value > maximum) {
    fail(400, 'invalid-argument', `${name} is invalid.`);
  }
  return value as number;
}

function hashOf(data: Data, name: string): string {
  const value = data[name];
  if (typeof value !== 'string' || !HEX_SHA256.test(value)) fail(400, 'invalid-argument', `${name} is invalid.`);
  return value as string;
}

async function activeMember(env: Env, uid: string, data: Data) {
  const deviceId = deviceIdOf(data);
  const pairId = idOf(data, 'conversationId');
  const pair = await requirePair(env.DB, uid, pairId, true);
  requireMemberDevice(pair, uid, deviceId);
  await requireDevice(env.DB, uid, deviceId);
  return { deviceId, pairId, pair };
}

async function uploadOf(env: Env, pairId: string, mediaId: string): Promise<Upload> {
  const upload = await env.DB.prepare('SELECT * FROM media_uploads WHERE pair_id = ? AND media_id = ?')
    .bind(pairId, mediaId).first<Upload>();
  if (!upload) fail(404, 'not-found', 'Media is unavailable.');
  return upload;
}

export async function beginMediaUpload(env: Env, uid: string, data: Data, now: number) {
  const { deviceId, pairId } = await activeMember(env, uid, data);
  const mediaId = idOf(data, 'mediaId');
  const size = integerOf(data, 'ciphertextSize', 17, MAX_CIPHERTEXT_BYTES);
  const hash = hashOf(data, 'ciphertextSha256');
  const chunkCount = Math.ceil(size / CHUNK_BYTES);
  await rateLimit(env.DB, uid, 'beginMediaUpload', 20, now);
  const existing = await env.DB.prepare('SELECT * FROM media_uploads WHERE pair_id = ? AND media_id = ?')
    .bind(pairId, mediaId).first<Upload>();
  if (existing) {
    if (existing.sender_uid !== uid || existing.sender_device_id !== deviceId ||
      existing.ciphertext_size !== size || existing.ciphertext_sha256 !== hash ||
      existing.expires_at_ms <= now) fail(409, 'already-exists', 'This media ID is already in use.');
    return { mediaId, chunkCount, expiresAtMillis: existing.expires_at_ms, complete: existing.complete === 1 };
  }
  const expiresAt = now + MEDIA_TTL_MS;
  const inserted = await env.DB.prepare(`INSERT INTO media_uploads
    (pair_id, media_id, sender_uid, sender_device_id, ciphertext_size, ciphertext_sha256,
     chunk_count, created_at_ms, expires_at_ms)
    SELECT pair_id, ?, ?, ?, ?, ?, ?, ?, ? FROM pairs
    WHERE pair_id = ? AND status = 'active'
      AND COALESCE((SELECT SUM(ciphertext_size) FROM media_uploads
        WHERE pair_id = ? AND expires_at_ms > ?), 0) + ? <= ?
      AND COALESCE((SELECT SUM(ciphertext_size) FROM media_uploads
        WHERE expires_at_ms > ?), 0) + ? <= ?
    ON CONFLICT(pair_id, media_id) DO NOTHING`)
    .bind(mediaId, uid, deviceId, size, hash, chunkCount, now, expiresAt, pairId,
      pairId, now, size, PAIR_QUOTA_BYTES, now, size, GLOBAL_QUOTA_BYTES).run();
  if (inserted.meta.changes !== 1) {
    const latest = await env.DB.prepare('SELECT * FROM media_uploads WHERE pair_id = ? AND media_id = ?')
      .bind(pairId, mediaId).first<Upload>();
    if (latest) fail(409, 'already-exists', 'This media ID is already in use.');
    fail(429, 'resource-exhausted', 'Free media storage is full. Try again after older encrypted copies expire.');
  }
  return { mediaId, chunkCount, expiresAtMillis: expiresAt, complete: false };
}

export async function uploadMediaChunk(env: Env, uid: string, data: Data, now: number) {
  const { deviceId, pairId } = await activeMember(env, uid, data);
  const mediaId = idOf(data, 'mediaId');
  const index = integerOf(data, 'chunkIndex', 0, 80);
  const payload = base64Of(data, 'payloadBase64', CHUNK_BYTES);
  const bytes = Uint8Array.from(atob(payload), character => character.charCodeAt(0));
  const hash = hashOf(data, 'payloadSha256');
  if (await sha256Hex(bytes) !== hash) fail(400, 'invalid-argument', 'Media chunk hash does not match.');
  await rateLimit(env.DB, uid, 'uploadMediaChunk', 180, now);
  const upload = await uploadOf(env, pairId, mediaId);
  if (upload.sender_uid !== uid || upload.sender_device_id !== deviceId || upload.expires_at_ms <= now) {
    fail(403, 'permission-denied', 'Only the active sender can upload this media.');
  }
  if (index >= upload.chunk_count ||
      bytes.length !== (index === upload.chunk_count - 1
        ? upload.ciphertext_size - index * CHUNK_BYTES : CHUNK_BYTES)) {
    fail(400, 'invalid-argument', 'Media chunk size or index is invalid.');
  }
  const existing = await env.DB.prepare(`SELECT payload_sha256, payload_size FROM media_chunks
    WHERE pair_id = ? AND media_id = ? AND chunk_index = ?`)
    .bind(pairId, mediaId, index).first<{ payload_sha256: string; payload_size: number }>();
  if (existing) {
    if (existing.payload_sha256 !== hash || existing.payload_size !== bytes.length) {
      fail(409, 'already-exists', 'This media chunk already contains different ciphertext.');
    }
    return { accepted: true, chunkIndex: index };
  }
  if (upload.complete) fail(412, 'failed-precondition', 'This media upload is complete.');
  const inserted = await env.DB.prepare(`INSERT INTO media_chunks
    (pair_id, media_id, chunk_index, payload_base64, payload_sha256, payload_size)
    SELECT pair_id, media_id, ?, ?, ?, ? FROM media_uploads
    WHERE pair_id = ? AND media_id = ? AND complete = 0 AND expires_at_ms > ?
    ON CONFLICT(pair_id, media_id, chunk_index) DO NOTHING`)
    .bind(index, payload, hash, bytes.length, pairId, mediaId, now).run();
  if (inserted.meta.changes !== 1) fail(409, 'already-exists', 'This media chunk is already in use.');
  return { accepted: true, chunkIndex: index };
}

export async function completeMediaUpload(env: Env, uid: string, data: Data, now: number) {
  const { deviceId, pairId } = await activeMember(env, uid, data);
  const mediaId = idOf(data, 'mediaId');
  await rateLimit(env.DB, uid, 'completeMediaUpload', 30, now);
  const upload = await uploadOf(env, pairId, mediaId);
  if (upload.sender_uid !== uid || upload.sender_device_id !== deviceId || upload.expires_at_ms <= now) {
    fail(403, 'permission-denied', 'Only the active sender can complete this media.');
  }
  if (upload.complete === 1) return { complete: true, expiresAtMillis: upload.expires_at_ms };
  const result = await env.DB.prepare(`UPDATE media_uploads SET complete = 1
    WHERE pair_id = ? AND media_id = ? AND complete = 0 AND expires_at_ms > ?
    AND chunk_count = (SELECT COUNT(*) FROM media_chunks WHERE pair_id = ? AND media_id = ?)
    AND ciphertext_size = (SELECT COALESCE(SUM(payload_size), 0) FROM media_chunks
      WHERE pair_id = ? AND media_id = ?)`)
    .bind(pairId, mediaId, now, pairId, mediaId, pairId, mediaId).run();
  if (result.meta.changes !== 1) fail(412, 'failed-precondition', 'Media upload is incomplete.');
  return { complete: true, expiresAtMillis: upload.expires_at_ms };
}

export async function fetchMediaManifest(env: Env, uid: string, data: Data, now: number) {
  const { pairId, pair } = await activeMember(env, uid, data);
  const mediaId = idOf(data, 'mediaId');
  await rateLimit(env.DB, uid, 'fetchMediaManifest', 60, now);
  const upload = await uploadOf(env, pairId, mediaId);
  const recipientUid = pair.owner_uid === upload.sender_uid ? pair.joiner_uid : pair.owner_uid;
  if (uid !== recipientUid || upload.complete !== 1 || upload.expires_at_ms <= now) {
    fail(404, 'not-found', 'Media is unavailable.');
  }
  return { mediaId, ciphertextSize: upload.ciphertext_size,
    ciphertextSha256: upload.ciphertext_sha256, chunkCount: upload.chunk_count,
    expiresAtMillis: upload.expires_at_ms };
}

export async function fetchMediaChunk(env: Env, uid: string, data: Data, now: number) {
  const { pairId, pair } = await activeMember(env, uid, data);
  const mediaId = idOf(data, 'mediaId');
  const index = integerOf(data, 'chunkIndex', 0, 80);
  await rateLimit(env.DB, uid, 'fetchMediaChunk', 180, now);
  const upload = await uploadOf(env, pairId, mediaId);
  const recipientUid = pair.owner_uid === upload.sender_uid ? pair.joiner_uid : pair.owner_uid;
  if (uid !== recipientUid || upload.complete !== 1 || upload.expires_at_ms <= now || index >= upload.chunk_count) {
    fail(404, 'not-found', 'Media is unavailable.');
  }
  const row = await env.DB.prepare(`SELECT payload_base64, payload_sha256 FROM media_chunks
    WHERE pair_id = ? AND media_id = ? AND chunk_index = ?`)
    .bind(pairId, mediaId, index).first<{ payload_base64: string; payload_sha256: string }>();
  if (!row) fail(404, 'not-found', 'Media is unavailable.');
  return { chunkIndex: index, payloadBase64: row.payload_base64, payloadSha256: row.payload_sha256 };
}

export async function cancelMediaUpload(env: Env, uid: string, data: Data, now: number) {
  const { deviceId, pairId } = await activeMember(env, uid, data);
  const mediaId = idOf(data, 'mediaId');
  const result = await env.DB.prepare(`DELETE FROM media_uploads WHERE pair_id = ? AND media_id = ?
    AND sender_uid = ? AND sender_device_id = ? AND complete = 0`)
    .bind(pairId, mediaId, uid, deviceId).run();
  return { cancelled: result.meta.changes === 1 };
}
