import {
  base64Of, deviceIdOf, fail, idOf, INVITE_TOKEN, INVITE_TTL_MS, rateLimit,
  requireDevice, requireMemberDevice, requirePair, requiredString, sha256Hex,
  type Data, type Env
} from './common.ts';

interface InviteRow {
  token_hash: string;
  invite_id: string;
  owner_uid: string;
  owner_device_id: string;
  expires_at_ms: number;
  pair_id: string | null;
  consumed: number;
}

export async function registerDevice(env: Env, uid: string, data: Data, now: number) {
  const deviceId = deviceIdOf(data);
  const fcmToken = data.fcmToken;
  if (typeof fcmToken !== 'string' || fcmToken.length > 4096) {
    fail(400, 'invalid-argument', 'fcmToken is invalid.');
  }
  await rateLimit(env.DB, uid, 'registerDevice', 10, now);
  const result = await env.DB.prepare(`
    INSERT INTO devices(uid, device_id, fcm_token, revoked, updated_at_ms) VALUES(?, ?, ?, 0, ?)
    ON CONFLICT(uid, device_id) DO UPDATE SET fcm_token = excluded.fcm_token,
      updated_at_ms = excluded.updated_at_ms WHERE devices.revoked = 0
  `).bind(uid, deviceId, fcmToken, now).run();
  if (result.meta.changes !== 1) fail(412, 'failed-precondition', 'A revoked installation cannot be registered again.');
  return { registered: true };
}

export async function createPairingInvite(env: Env, uid: string, data: Data, now: number) {
  const deviceId = deviceIdOf(data);
  const token = requiredString(data, 'token', 32);
  if (!INVITE_TOKEN.test(token)) fail(400, 'invalid-argument', 'The invite token is invalid.');
  await requireDevice(env.DB, uid, deviceId);
  await rateLimit(env.DB, uid, 'createInvite', 5, now);
  const hash = await sha256Hex(token);
  const existing = await env.DB.prepare('SELECT * FROM invites WHERE token_hash = ?').bind(hash).first<InviteRow>();
  if (existing) {
    if (existing.owner_uid !== uid || existing.owner_device_id !== deviceId) fail(409, 'already-exists', 'The invite token is already in use.');
    if (existing.expires_at_ms <= now) fail(412, 'failed-precondition', 'This invite has expired. Create a fresh invite.');
    return { inviteId: existing.invite_id, expiresAtMillis: existing.expires_at_ms };
  }
  const inviteId = crypto.randomUUID();
  const expiresAtMillis = now + INVITE_TTL_MS;
  try {
    await env.DB.prepare(`INSERT INTO invites(token_hash, invite_id, owner_uid, owner_device_id, expires_at_ms)
      VALUES(?, ?, ?, ?, ?)`).bind(hash, inviteId, uid, deviceId, expiresAtMillis).run();
  } catch {
    fail(409, 'already-exists', 'The invite token is already in use.');
  }
  return { inviteId, expiresAtMillis };
}

export async function joinPairingInvite(env: Env, uid: string, data: Data, now: number) {
  const deviceId = deviceIdOf(data);
  const token = requiredString(data, 'token', 32);
  if (!INVITE_TOKEN.test(token)) fail(400, 'invalid-argument', 'The invite token is invalid.');
  const keyPackage = base64Of(data, 'keyPackageBase64', 16 * 1024);
  await requireDevice(env.DB, uid, deviceId);
  await rateLimit(env.DB, uid, 'joinInvite', 10, now);
  const hash = await sha256Hex(token);
  const invite = await env.DB.prepare('SELECT * FROM invites WHERE token_hash = ?').bind(hash).first<InviteRow>();
  if (!invite) fail(412, 'failed-precondition', 'This invite is expired or already used.');
  if (invite.expires_at_ms <= now) fail(412, 'failed-precondition', 'This invite is expired or already used.');
  if (invite.consumed !== 0) {
    const pair = invite.pair_id ? await env.DB.prepare('SELECT joiner_uid, joiner_device_id, joiner_key_package_base64, status FROM pairs WHERE pair_id = ?')
      .bind(invite.pair_id).first<{ joiner_uid: string; joiner_device_id: string; joiner_key_package_base64: string; status: string }>() : null;
    if (pair?.status === 'pending-confirmation' && pair.joiner_uid === uid &&
        pair.joiner_device_id === deviceId && pair.joiner_key_package_base64 === keyPackage) {
      return { conversationId: invite.pair_id, status: 'pending-confirmation' };
    }
    fail(412, 'failed-precondition', 'This invite is expired or already used.');
  }
  if (invite.owner_uid === uid) fail(400, 'invalid-argument', "An invite must be joined from the partner's device.");
  await requireDevice(env.DB, invite.owner_uid, invite.owner_device_id);
  const pairId = crypto.randomUUID();
  const results = await env.DB.batch([
    env.DB.prepare(`UPDATE invites SET consumed = 1, pair_id = ? WHERE token_hash = ? AND consumed = 0
      AND expires_at_ms > ? AND EXISTS (SELECT 1 FROM devices d WHERE d.uid = invites.owner_uid
        AND d.device_id = invites.owner_device_id AND d.revoked = 0)
      AND EXISTS (SELECT 1 FROM devices d WHERE d.uid = ? AND d.device_id = ? AND d.revoked = 0)`)
      .bind(pairId, hash, now, uid, deviceId),
    env.DB.prepare(`INSERT INTO pairs(pair_id, owner_uid, owner_device_id, joiner_uid, joiner_device_id,
      status, pairing_status, joiner_key_package_base64, welcome_expires_at_ms, created_at_ms, updated_at_ms)
      SELECT ?, owner_uid, owner_device_id, ?, ?, 'pending-confirmation', 'key-package-ready', ?, expires_at_ms, ?, ?
      FROM invites WHERE token_hash = ? AND pair_id = ? AND consumed = 1`)
      .bind(pairId, uid, deviceId, keyPackage, now, now, hash, pairId)
  ]);
  if (results[0].meta.changes !== 1 || results[1].meta.changes !== 1) {
    fail(412, 'failed-precondition', 'This invite is expired or already used.');
  }
  return { conversationId: pairId, status: 'pending-confirmation' };
}

export async function fetchPairingRequest(env: Env, uid: string, data: Data, now: number) {
  const deviceId = deviceIdOf(data);
  const token = requiredString(data, 'token', 32);
  if (!INVITE_TOKEN.test(token)) fail(400, 'invalid-argument', 'The invite token is invalid.');
  await requireDevice(env.DB, uid, deviceId);
  await rateLimit(env.DB, uid, 'fetchPairingRequest', 30, now);
  const invite = await env.DB.prepare('SELECT * FROM invites WHERE token_hash = ?').bind(await sha256Hex(token)).first<InviteRow>();
  if (!invite || invite.owner_uid !== uid || invite.owner_device_id !== deviceId) {
    fail(403, 'permission-denied', 'Only the inviting device can read the pairing request.');
  }
  if (invite.expires_at_ms <= now) fail(412, 'failed-precondition', 'The invite has expired.');
  if (invite.consumed === 0 || !invite.pair_id) return { status: 'waiting' };
  const pair = await requirePair(env.DB, uid, invite.pair_id);
  if (pair.owner_device_id !== deviceId || pair.pairing_status !== 'key-package-ready') {
    fail(412, 'failed-precondition', 'The pairing request is unavailable or already processed.');
  }
  return { conversationId: invite.pair_id, joinerKeyPackageBase64: pair.joiner_key_package_base64 };
}

export async function publishPairingWelcome(env: Env, uid: string, data: Data, now: number) {
  const deviceId = deviceIdOf(data);
  const pairId = idOf(data, 'conversationId');
  const welcome = base64Of(data, 'welcomeBase64', 64 * 1024);
  const tree = base64Of(data, 'ratchetTreeBase64', 64 * 1024);
  await requireDevice(env.DB, uid, deviceId);
  await rateLimit(env.DB, uid, 'publishPairingWelcome', 10, now);
  const pair = await requirePair(env.DB, uid, pairId);
  if (pair.owner_uid !== uid || pair.owner_device_id !== deviceId || pair.status !== 'pending-confirmation') {
    fail(403, 'permission-denied', 'Only the inviting device may publish this Welcome.');
  }
  if (!pair.welcome_expires_at_ms || pair.welcome_expires_at_ms <= now) {
    fail(412, 'failed-precondition', 'The pairing invite expired before its Welcome was published.');
  }
  if (pair.pairing_status === 'welcome-ready') {
    if (pair.welcome_base64 === welcome && pair.ratchet_tree_base64 === tree) return { published: true, alreadyPublished: true };
    fail(412, 'failed-precondition', 'A different Welcome was already published.');
  }
  if (pair.pairing_status !== 'key-package-ready') fail(412, 'failed-precondition', 'The pairing request was already processed.');
  const result = await env.DB.prepare(`UPDATE pairs SET pairing_status = 'welcome-ready', welcome_base64 = ?,
    ratchet_tree_base64 = ?, updated_at_ms = ? WHERE pair_id = ? AND owner_uid = ? AND owner_device_id = ?
    AND status = 'pending-confirmation' AND pairing_status = 'key-package-ready' AND welcome_expires_at_ms > ?`)
    .bind(welcome, tree, now, pairId, uid, deviceId, now).run();
  if (result.meta.changes !== 1) fail(412, 'failed-precondition', 'The pairing request was already processed.');
  return { published: true, alreadyPublished: false };
}

export async function fetchPairingWelcome(env: Env, uid: string, data: Data, now: number) {
  const deviceId = deviceIdOf(data);
  const pairId = idOf(data, 'conversationId');
  await requireDevice(env.DB, uid, deviceId);
  await rateLimit(env.DB, uid, 'fetchPairingWelcome', 30, now);
  const pair = await requirePair(env.DB, uid, pairId);
  if (pair.joiner_uid !== uid || pair.joiner_device_id !== deviceId) {
    fail(403, 'permission-denied', 'Only the joining device may fetch this Welcome.');
  }
  if (pair.pairing_status !== 'welcome-ready' || !pair.welcome_expires_at_ms || pair.welcome_expires_at_ms <= now) {
    fail(412, 'failed-precondition', 'The pairing Welcome is unavailable or expired.');
  }
  return { welcomeBase64: pair.welcome_base64, ratchetTreeBase64: pair.ratchet_tree_base64 };
}

export async function confirmPairing(env: Env, uid: string, data: Data, now: number) {
  const deviceId = deviceIdOf(data);
  const pairId = idOf(data, 'conversationId');
  await requireDevice(env.DB, uid, deviceId);
  await rateLimit(env.DB, uid, 'confirmPairing', 10, now);
  const pair = await requirePair(env.DB, uid, pairId);
  requireMemberDevice(pair, uid, deviceId);
  if (pair.status === 'revoked') fail(412, 'failed-precondition', 'This conversation was revoked.');
  if (pair.status === 'active' && pair.pairing_status === 'complete') return { confirmed: true, active: true };
  if (pair.pairing_status !== 'welcome-ready' || !pair.welcome_expires_at_ms || pair.welcome_expires_at_ms <= now) {
    fail(412, 'failed-precondition', 'The MLS Welcome must be delivered before pairing confirmation.');
  }
  const owner = pair.owner_uid === uid;
  const otherConfirmed = owner ? 'joiner_confirmed' : 'owner_confirmed';
  const selfConfirmed = owner ? 'owner_confirmed' : 'joiner_confirmed';
  const result = await env.DB.prepare(`UPDATE pairs SET ${selfConfirmed} = 1,
    status = CASE WHEN ${otherConfirmed} = 1 THEN 'active' ELSE 'pending-confirmation' END,
    pairing_status = CASE WHEN ${otherConfirmed} = 1 THEN 'complete' ELSE 'welcome-ready' END,
    joiner_key_package_base64 = CASE WHEN ${otherConfirmed} = 1 THEN NULL ELSE joiner_key_package_base64 END,
    welcome_base64 = CASE WHEN ${otherConfirmed} = 1 THEN NULL ELSE welcome_base64 END,
    ratchet_tree_base64 = CASE WHEN ${otherConfirmed} = 1 THEN NULL ELSE ratchet_tree_base64 END,
    welcome_expires_at_ms = CASE WHEN ${otherConfirmed} = 1 THEN NULL ELSE welcome_expires_at_ms END,
    updated_at_ms = ? WHERE pair_id = ? AND ${owner ? 'owner_uid' : 'joiner_uid'} = ?
    AND ${owner ? 'owner_device_id' : 'joiner_device_id'} = ? AND status = 'pending-confirmation'
    AND pairing_status = 'welcome-ready' AND welcome_expires_at_ms > ?`)
    .bind(now, pairId, uid, deviceId, now).run();
  if (result.meta.changes !== 1) {
    const latest = await requirePair(env.DB, uid, pairId);
    if (latest.status !== 'active') fail(412, 'failed-precondition', 'The MLS Welcome must be delivered before pairing confirmation.');
  }
  const active = (await requirePair(env.DB, uid, pairId)).status === 'active';
  return { confirmed: true, active };
}

export async function revokePairing(env: Env, uid: string, data: Data, now: number) {
  const deviceId = deviceIdOf(data);
  const pairId = idOf(data, 'conversationId');
  await requireDevice(env.DB, uid, deviceId);
  await rateLimit(env.DB, uid, 'revokePairing', 10, now);
  const pair = await requirePair(env.DB, uid, pairId);
  requireMemberDevice(pair, uid, deviceId);
  await env.DB.batch([
    env.DB.prepare(`UPDATE pairs SET status = 'revoked', pairing_status = 'revoked', owner_confirmed = 0,
      joiner_confirmed = 0, joiner_key_package_base64 = NULL, welcome_base64 = NULL,
      ratchet_tree_base64 = NULL, welcome_expires_at_ms = NULL, updated_at_ms = ? WHERE pair_id = ?`)
      .bind(now, pairId),
    env.DB.prepare('DELETE FROM envelopes WHERE pair_id = ?').bind(pairId),
    env.DB.prepare('DELETE FROM media_uploads WHERE pair_id = ?').bind(pairId)
  ]);
  return { revoked: true };
}
