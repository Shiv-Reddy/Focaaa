import {
  base64Of, deviceIdOf, DOCUMENT_ID, ENVELOPE_TTL_MS, fail, idOf, MAX_BATCH, PAIR_METADATA_TTL_MS,
  rateLimit, requireDevice, requireMemberDevice, requirePair, sha256Hex,
  type Data, type Env
} from './common.ts';

interface ReceiptRow {
  sender_uid: string;
  sender_device_id: string;
  payload_sha256: string;
  expires_at_ms: number;
}

export async function sendEnvelope(
  env: Env, uid: string, data: Data, now: number,
  sendHint: (env: Env, token: string, pairId: string) => Promise<void>
) {
  const deviceId = deviceIdOf(data);
  const pairId = idOf(data, 'conversationId');
  const envelopeId = idOf(data, 'envelopeId');
  const payload = base64Of(data, 'payloadBase64', 48 * 1024);
  await rateLimit(env.DB, uid, 'sendEnvelope', 60, now);
  const pair = await requirePair(env.DB, uid, pairId);
  if (pair.status === 'revoked') fail(410, 'pair-revoked', 'This private connection has ended.');
  if (pair.status !== 'active') fail(403, 'permission-denied', 'This conversation is not active for this member.');
  requireMemberDevice(pair, uid, deviceId);
  await requireDevice(env.DB, uid, deviceId);
  const recipientUid = pair.owner_uid === uid ? pair.joiner_uid : pair.owner_uid;
  const recipientDeviceId = pair.owner_uid === uid ? pair.joiner_device_id : pair.owner_device_id;
  const recipient = await env.DB.prepare('SELECT fcm_token FROM devices WHERE uid = ? AND device_id = ? AND revoked = 0')
    .bind(recipientUid, recipientDeviceId).first<{ fcm_token: string | null }>();
  if (!recipient) fail(412, 'failed-precondition', 'Conversation recipient device is revoked or unavailable.');
  const digest = await sha256Hex(Uint8Array.from(atob(payload), character => character.charCodeAt(0)));
  const receipt = await env.DB.prepare('SELECT * FROM envelope_receipts WHERE pair_id = ? AND envelope_id = ?')
    .bind(pairId, envelopeId).first<ReceiptRow>();
  if (receipt) return validateReceipt(receipt, uid, deviceId, digest, now, envelopeId);

  const expiresAtMillis = now + ENVELOPE_TTL_MS;
  const nonce = crypto.randomUUID();
  const results = await env.DB.batch([
    env.DB.prepare(`INSERT INTO envelope_receipts(pair_id, envelope_id, sender_uid, sender_device_id,
      payload_sha256, expires_at_ms, creation_nonce)
      SELECT p.pair_id, ?, ?, ?, ?, ?, ? FROM pairs p
      JOIN devices sender ON sender.uid = ? AND sender.device_id = ? AND sender.revoked = 0
      JOIN devices recipient ON recipient.uid = ? AND recipient.device_id = ? AND recipient.revoked = 0
      WHERE p.pair_id = ? AND p.status = 'active' AND
      ((p.owner_uid = ? AND p.owner_device_id = ?) OR (p.joiner_uid = ? AND p.joiner_device_id = ?))
      ON CONFLICT(pair_id, envelope_id) DO NOTHING`)
      .bind(envelopeId, uid, deviceId, digest, expiresAtMillis, nonce, uid, deviceId,
        recipientUid, recipientDeviceId, pairId, uid, deviceId, uid, deviceId),
    env.DB.prepare(`INSERT INTO envelopes(pair_id, envelope_id, sender_uid, sender_device_id,
      recipient_uid, payload_base64, created_at_ms, expires_at_ms)
      SELECT pair_id, envelope_id, sender_uid, sender_device_id, ?, ?, ?, expires_at_ms
      FROM envelope_receipts WHERE pair_id = ? AND envelope_id = ? AND creation_nonce = ?`)
      .bind(recipientUid, payload, now, pairId, envelopeId, nonce)
  ]);
  if (results[0].meta.changes !== 1 || results[1].meta.changes !== 1) {
    const latest = await env.DB.prepare('SELECT * FROM envelope_receipts WHERE pair_id = ? AND envelope_id = ?')
      .bind(pairId, envelopeId).first<ReceiptRow>();
    if (latest) return validateReceipt(latest, uid, deviceId, digest, now, envelopeId);
    fail(403, 'permission-denied', 'This conversation is not active for this member.');
  }
  if (recipient.fcm_token) {
    try { await sendHint(env, recipient.fcm_token, pairId); }
    catch { /* A hint is optional; the durable queue remains available. */ }
  }
  return { accepted: true, envelopeId, expiresAtMillis };
}

function validateReceipt(receipt: ReceiptRow, uid: string, deviceId: string, digest: string, now: number, envelopeId: string) {
  if (receipt.sender_uid !== uid || receipt.sender_device_id !== deviceId || receipt.payload_sha256 !== digest) {
    fail(409, 'already-exists', 'This envelope ID is already in use.');
  }
  if (receipt.expires_at_ms <= now) fail(412, 'failed-precondition', 'This envelope has expired; create a new envelope ID.');
  return { accepted: true, envelopeId, expiresAtMillis: receipt.expires_at_ms };
}

export async function fetchPendingEnvelopes(env: Env, uid: string, data: Data, now: number) {
  const deviceId = deviceIdOf(data);
  const pairId = idOf(data, 'conversationId');
  await requireDevice(env.DB, uid, deviceId);
  await rateLimit(env.DB, uid, 'fetchEnvelopes', 120, now);
  const pair = await requirePair(env.DB, uid, pairId, true);
  requireMemberDevice(pair, uid, deviceId);
  const result = await env.DB.prepare(`SELECT envelope_id, payload_base64, created_at_ms, expires_at_ms FROM envelopes
    WHERE pair_id = ? AND recipient_uid = ? AND expires_at_ms > ? ORDER BY expires_at_ms LIMIT ?`)
    .bind(pairId, uid, now, MAX_BATCH).all<{
      envelope_id: string; payload_base64: string; created_at_ms: number; expires_at_ms: number
    }>();
  return { envelopes: result.results.map(row => ({
    envelopeId: row.envelope_id, payloadBase64: row.payload_base64,
    createdAtMillis: row.created_at_ms, expiresAtMillis: row.expires_at_ms
  })) };
}

export async function acknowledgeEnvelopes(env: Env, uid: string, data: Data, now: number) {
  const deviceId = deviceIdOf(data);
  const pairId = idOf(data, 'conversationId');
  const ids = envelopeIdsOf(data);
  await rateLimit(env.DB, uid, 'ackEnvelopes', 120, now);
  const pair = await requirePair(env.DB, uid, pairId, true);
  requireMemberDevice(pair, uid, deviceId);
  await requireDevice(env.DB, uid, deviceId);
  const encodedIds = JSON.stringify(ids);
  const foreign = await env.DB.prepare(`SELECT COUNT(*) AS count FROM envelopes WHERE pair_id = ?
    AND envelope_id IN (SELECT value FROM json_each(?)) AND recipient_uid <> ?`)
    .bind(pairId, encodedIds, uid).first<{ count: number }>();
  if ((foreign?.count ?? 0) > 0) {
    fail(403, 'permission-denied', 'Only the intended recipient can acknowledge an envelope.');
  }
  const result = await env.DB.batch([
    env.DB.prepare(`UPDATE envelope_receipts SET delivered_at_ms = COALESCE(delivered_at_ms, ?)
      WHERE pair_id = ? AND envelope_id IN (SELECT value FROM json_each(?))
      AND sender_uid <> ? AND sender_device_id <> ? AND expires_at_ms > ?
      AND EXISTS (SELECT 1 FROM envelopes e WHERE e.pair_id = envelope_receipts.pair_id
        AND e.envelope_id = envelope_receipts.envelope_id AND e.recipient_uid = ?)
      AND EXISTS (SELECT 1 FROM pairs p WHERE p.pair_id = envelope_receipts.pair_id AND p.status = 'active')`)
      .bind(now, pairId, encodedIds, uid, deviceId, now, uid),
    env.DB.prepare(`DELETE FROM envelopes
    WHERE pair_id = ? AND envelope_id IN (SELECT value FROM json_each(?)) AND recipient_uid = ? AND EXISTS
    (SELECT 1 FROM pairs p WHERE p.pair_id = ? AND p.status = 'active') AND EXISTS
    (SELECT 1 FROM devices d WHERE d.uid = ? AND d.device_id = ? AND d.revoked = 0)`)
      .bind(pairId, encodedIds, uid, pairId, uid, deviceId)
  ]);
  return { deleted: result[1].meta.changes };
}

export async function markRead(env: Env, uid: string, data: Data, now: number) {
  const deviceId = deviceIdOf(data);
  const pairId = idOf(data, 'conversationId');
  const ids = envelopeIdsOf(data);
  await rateLimit(env.DB, uid, 'markRead', 120, now);
  const pair = await requirePair(env.DB, uid, pairId, true);
  requireMemberDevice(pair, uid, deviceId);
  await requireDevice(env.DB, uid, deviceId);
  const encodedIds = JSON.stringify(ids);
  await env.DB.prepare(`UPDATE envelope_receipts SET read_at_ms = COALESCE(read_at_ms, ?)
    WHERE pair_id = ? AND envelope_id IN (SELECT value FROM json_each(?))
    AND sender_uid <> ? AND sender_device_id <> ?
    AND delivered_at_ms IS NOT NULL AND expires_at_ms > ?
    AND EXISTS (SELECT 1 FROM pairs p WHERE p.pair_id = envelope_receipts.pair_id AND p.status = 'active')`)
    .bind(now, pairId, encodedIds, uid, deviceId, now).run();
  const result = await env.DB.prepare(`SELECT envelope_id FROM envelope_receipts
    WHERE pair_id = ? AND envelope_id IN (SELECT value FROM json_each(?))
    AND sender_uid <> ? AND sender_device_id <> ? AND read_at_ms IS NOT NULL
    AND expires_at_ms > ? ORDER BY envelope_id`)
    .bind(pairId, encodedIds, uid, deviceId, now).all<{ envelope_id: string }>();
  return { readEnvelopeIds: result.results.map(row => row.envelope_id) };
}

export async function fetchMessageStatuses(env: Env, uid: string, data: Data, now: number) {
  const deviceId = deviceIdOf(data);
  const pairId = idOf(data, 'conversationId');
  const ids = envelopeIdsOf(data);
  await rateLimit(env.DB, uid, 'fetchMessageStatuses', 120, now);
  const pair = await requirePair(env.DB, uid, pairId, true);
  requireMemberDevice(pair, uid, deviceId);
  await requireDevice(env.DB, uid, deviceId);
  const result = await env.DB.prepare(`SELECT envelope_id AS envelopeId,
      CASE WHEN read_at_ms IS NOT NULL THEN 3 WHEN delivered_at_ms IS NOT NULL THEN 2 ELSE 1 END AS level
    FROM envelope_receipts WHERE pair_id = ? AND sender_uid = ? AND sender_device_id = ?
    AND envelope_id IN (SELECT value FROM json_each(?)) AND expires_at_ms > ?
    ORDER BY envelope_id`)
    .bind(pairId, uid, deviceId, JSON.stringify(ids), now)
    .all<{ envelopeId: string; level: number }>();
  return { statuses: result.results };
}

function envelopeIdsOf(data: Data): string[] {
  const ids = data.envelopeIds;
  if (!Array.isArray(ids) || ids.length < 1 || ids.length > MAX_BATCH ||
    ids.some(id => typeof id !== 'string' || !DOCUMENT_ID.test(id))) {
    fail(400, 'invalid-argument', 'envelopeIds is invalid.');
  }
  return ids as string[];
}

export async function revokeDevice(env: Env, uid: string, data: Data, now: number) {
  const deviceId = deviceIdOf(data);
  await rateLimit(env.DB, uid, 'revokeDevice', 5, now);
  const device = await env.DB.prepare('SELECT revoked FROM devices WHERE uid = ? AND device_id = ?')
    .bind(uid, deviceId).first<{ revoked: number }>();
  if (!device) fail(403, 'permission-denied', 'This app installation is not registered to this account.');
  await env.DB.batch([
    env.DB.prepare('UPDATE devices SET revoked = 1, fcm_token = NULL, updated_at_ms = ? WHERE uid = ? AND device_id = ?')
      .bind(now, uid, deviceId),
    env.DB.prepare(`UPDATE pairs SET status = 'revoked', pairing_status = 'revoked',
      joiner_key_package_base64 = NULL, welcome_base64 = NULL, ratchet_tree_base64 = NULL,
      welcome_expires_at_ms = NULL, updated_at_ms = ?
      WHERE (owner_uid = ? AND owner_device_id = ?) OR (joiner_uid = ? AND joiner_device_id = ?)`)
      .bind(now, uid, deviceId, uid, deviceId),
    env.DB.prepare(`DELETE FROM envelopes WHERE pair_id IN (
      SELECT pair_id FROM pairs WHERE (owner_uid = ? AND owner_device_id = ?) OR
      (joiner_uid = ? AND joiner_device_id = ?))`).bind(uid, deviceId, uid, deviceId),
    env.DB.prepare(`DELETE FROM media_uploads WHERE pair_id IN (
      SELECT pair_id FROM pairs WHERE (owner_uid = ? AND owner_device_id = ?) OR
      (joiner_uid = ? AND joiner_device_id = ?))`).bind(uid, deviceId, uid, deviceId)
  ]);
  return { revoked: true };
}

export async function purgeExpired(env: Env, now: number): Promise<void> {
  await env.DB.batch([
    env.DB.prepare('DELETE FROM media_uploads WHERE expires_at_ms <= ?').bind(now),
    env.DB.prepare('DELETE FROM envelopes WHERE expires_at_ms <= ?').bind(now),
    env.DB.prepare('DELETE FROM envelope_receipts WHERE expires_at_ms <= ?').bind(now),
    env.DB.prepare('DELETE FROM invites WHERE expires_at_ms <= ?').bind(now),
    env.DB.prepare('DELETE FROM rate_limits WHERE expires_at_ms <= ?').bind(now),
    env.DB.prepare(`UPDATE pairs SET status = 'expired', pairing_status = 'expired',
      joiner_key_package_base64 = NULL, welcome_base64 = NULL, ratchet_tree_base64 = NULL,
      welcome_expires_at_ms = NULL, updated_at_ms = ? WHERE status = 'pending-confirmation'
      AND welcome_expires_at_ms <= ?`).bind(now, now),
    env.DB.prepare(`DELETE FROM pairs WHERE status IN ('revoked', 'expired') AND updated_at_ms <= ?
      AND NOT EXISTS (SELECT 1 FROM envelope_receipts r WHERE r.pair_id = pairs.pair_id)`)
      .bind(now - PAIR_METADATA_TTL_MS)
  ]);
}
