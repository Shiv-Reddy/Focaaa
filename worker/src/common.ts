export interface Env {
  DB: D1Database;
  LIVE_DRAFT?: DurableObjectNamespace;
  FIREBASE_PROJECT_ID: string;
  FIREBASE_PROJECT_NUMBER: string;
  FIREBASE_ANDROID_APP_ID: string;
  FIREBASE_RELEASE_ANDROID_APP_ID?: string;
  FCM_CLIENT_EMAIL?: string;
  FCM_PRIVATE_KEY?: string;
}

export type Data = Record<string, unknown>;
export const INVITE_TTL_MS = 5 * 60_000;
export const ENVELOPE_TTL_MS = 7 * 24 * 60 * 60_000;
export const PAIR_METADATA_TTL_MS = 30 * 24 * 60 * 60_000;
export const MAX_BATCH = 100;
export const DEVICE_ID = /^[A-Za-z0-9_-]{20,80}$/;
export const DOCUMENT_ID = /^[A-Za-z0-9_-]{1,128}$/;
export const INVITE_TOKEN = /^[A-Za-z0-9_-]{32}$/;

export class RelayError extends Error {
  status: number;
  code: string;
  constructor(status: number, code: string, message: string) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

export function fail(status: number, code: string, message: string): never {
  throw new RelayError(status, code, message);
}

export function requiredString(data: Data, name: string, maxLength: number): string {
  const value = data[name];
  if (typeof value !== 'string' || value.length === 0 || value.length > maxLength) {
    fail(400, 'invalid-argument', `${name} is invalid.`);
  }
  return value as string;
}

export function deviceIdOf(data: Data): string {
  const id = requiredString(data, 'deviceId', 80);
  if (!DEVICE_ID.test(id)) fail(400, 'invalid-argument', 'deviceId is invalid.');
  return id;
}

export function idOf(data: Data, name: string): string {
  const id = requiredString(data, name, 128);
  if (!DOCUMENT_ID.test(id)) fail(400, 'invalid-argument', `${name} is invalid.`);
  return id;
}

export function base64Of(data: Data, name: string, maxBytes: number): string {
  const value = requiredString(data, name, 4 * Math.ceil(maxBytes / 3));
  try {
    const decoded = atob(value);
    if (decoded.length === 0 || decoded.length > maxBytes || btoa(decoded) !== value) {
      fail(400, 'invalid-argument', `${name} encoding or size is invalid.`);
    }
    return value;
  } catch (error) {
    if (error instanceof RelayError) throw error;
    return fail(400, 'invalid-argument', `${name} encoding or size is invalid.`);
  }
}

export async function sha256Hex(value: string | Uint8Array): Promise<string> {
  const bytes = typeof value === 'string' ? new TextEncoder().encode(value) : value;
  const digest = await crypto.subtle.digest('SHA-256', Uint8Array.from(bytes).buffer);
  return Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('');
}

export async function rateLimit(db: D1Database, uid: string, action: string, maximum: number, now: number): Promise<void> {
  const minute = Math.floor(now / 60_000);
  const result = await db.prepare(`
    INSERT INTO rate_limits(uid, action, minute, count, expires_at_ms) VALUES(?, ?, ?, 1, ?)
    ON CONFLICT(uid, action, minute) DO UPDATE SET count = count + 1 WHERE count < ?
  `).bind(uid, action, minute, (minute + 2) * 60_000, maximum).run();
  if (result.meta.changes !== 1) fail(429, 'resource-exhausted', 'Too many requests. Try again shortly.');
}

export async function requireDevice(db: D1Database, uid: string, deviceId: string): Promise<void> {
  const device = await db.prepare('SELECT revoked FROM devices WHERE uid = ? AND device_id = ?')
    .bind(uid, deviceId).first<{ revoked: number }>();
  if (!device || device.revoked !== 0) fail(403, 'permission-denied', 'This app installation is not registered.');
}

export interface PairRow {
  pair_id: string;
  owner_uid: string;
  owner_device_id: string;
  joiner_uid: string;
  joiner_device_id: string;
  status: string;
  pairing_status: string;
  joiner_key_package_base64: string | null;
  welcome_base64: string | null;
  ratchet_tree_base64: string | null;
  welcome_expires_at_ms: number | null;
  owner_confirmed: number;
  joiner_confirmed: number;
}

export async function requirePair(db: D1Database, uid: string, pairId: string, active = false): Promise<PairRow> {
  const pair = await db.prepare('SELECT * FROM pairs WHERE pair_id = ?').bind(pairId).first<PairRow>();
  if (!pair) fail(410, 'pair-revoked', 'This private connection has ended.');
  if (pair.owner_uid !== uid && pair.joiner_uid !== uid) {
    fail(403, 'permission-denied', 'You are not a member of this conversation.');
  }
  if (active && pair.status === 'revoked') fail(410, 'pair-revoked', 'This private connection has ended.');
  if (active && pair.status !== 'active') fail(412, 'failed-precondition', 'Both partners must confirm pairing first.');
  return pair;
}

export function requireMemberDevice(pair: PairRow, uid: string, deviceId: string): void {
  if ((pair.owner_uid === uid ? pair.owner_device_id : pair.joiner_device_id) !== deviceId) {
    fail(403, 'permission-denied', 'This device is not an active pair member.');
  }
}
