import { importPKCS8, SignJWT } from 'jose';
import type { Env } from './common.ts';

let accessToken: { value: string; expiresAt: number; credentialFingerprint: string } | null = null;

async function fingerprint(email: string, privateKey: string): Promise<string> {
  const encoded = new TextEncoder().encode(`${email}\u0000${privateKey}`);
  try {
    const digest = await crypto.subtle.digest('SHA-256', encoded);
    return Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('');
  } finally {
    encoded.fill(0);
  }
}

async function googleAccessToken(env: Env): Promise<string | null> {
  if (!env.FCM_CLIENT_EMAIL || !env.FCM_PRIVATE_KEY) return null;
  const credentialFingerprint = await fingerprint(env.FCM_CLIENT_EMAIL, env.FCM_PRIVATE_KEY);
  if (accessToken?.credentialFingerprint === credentialFingerprint && accessToken.expiresAt > Date.now() + 60_000) {
    return accessToken.value;
  }
  const now = Math.floor(Date.now() / 1000);
  const key = await importPKCS8(env.FCM_PRIVATE_KEY.replace(/\\n/g, '\n'), 'RS256');
  const assertion = await new SignJWT({ scope: 'https://www.googleapis.com/auth/firebase.messaging' })
    .setProtectedHeader({ alg: 'RS256', typ: 'JWT' })
    .setIssuer(env.FCM_CLIENT_EMAIL)
    .setAudience('https://oauth2.googleapis.com/token')
    .setIssuedAt(now)
    .setExpirationTime(now + 3600)
    .sign(key);
  const response = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion })
  });
  if (!response.ok) throw new Error('FCM token exchange failed.');
  const result = await response.json() as { access_token?: string; expires_in?: number };
  if (!result.access_token) throw new Error('FCM token exchange returned no token.');
  accessToken = {
    value: result.access_token,
    expiresAt: Date.now() + Math.min(result.expires_in ?? 3600, 3600) * 1000,
    credentialFingerprint
  };
  return accessToken.value;
}

export async function sendHint(env: Env, deviceToken: string, pairId: string): Promise<void> {
  const token = await googleAccessToken(env);
  if (!token) return;
  const response = await fetch(`https://fcm.googleapis.com/v1/projects/${env.FIREBASE_PROJECT_ID}/messages:send`, {
    method: 'POST',
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
    body: JSON.stringify({ message: {
      token: deviceToken, data: { type: 'sync', conversationId: pairId },
      android: { priority: 'HIGH' }
    } })
  });
  if (!response.ok) throw new Error(`FCM hint failed with HTTP ${response.status}.`);
}
