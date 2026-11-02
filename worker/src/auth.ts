import { createRemoteJWKSet, importX509, jwtVerify, type JWTPayload } from 'jose';
import { fail, type Env } from './common.ts';

const firebaseCertUrl = 'https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com';
const appCheckKeys = createRemoteJWKSet(new URL('https://firebaseappcheck.googleapis.com/v1/jwks'));
let certificateCache: { expiresAt: number; values: Record<string, string> } | null = null;
const importedKeys = new Map<string, Promise<CryptoKey>>();

async function firebaseKey(kid: string): Promise<CryptoKey> {
  if (!certificateCache || certificateCache.expiresAt <= Date.now()) {
    const response = await fetch(firebaseCertUrl);
    if (!response.ok) throw new Error('Firebase certificate lookup failed.');
    const values = await response.json() as Record<string, string>;
    const maxAge = Number(response.headers.get('cache-control')?.match(/max-age=(\d+)/)?.[1] ?? 300);
    certificateCache = { values, expiresAt: Date.now() + Math.min(maxAge, 21_600) * 1000 };
    importedKeys.clear();
  }
  const certificate = certificateCache.values[kid];
  if (!certificate) throw new Error('Firebase signing key was not found.');
  let key = importedKeys.get(kid);
  if (!key) {
    key = importX509(certificate, 'RS256');
    importedKeys.set(kid, key);
  }
  return key;
}

export function validAnonymousClaims(payload: JWTPayload, projectId: string, nowSeconds = Math.floor(Date.now() / 1000)): string {
  const firebase = payload.firebase as { sign_in_provider?: unknown } | undefined;
  if (payload.aud !== projectId || payload.iss !== `https://securetoken.google.com/${projectId}` ||
      typeof payload.sub !== 'string' || !payload.sub || firebase?.sign_in_provider !== 'anonymous' ||
      typeof payload.iat !== 'number' || payload.iat > nowSeconds ||
      typeof payload.exp !== 'number' || payload.exp <= nowSeconds ||
      typeof payload.auth_time !== 'number' || payload.auth_time > nowSeconds) {
    fail(401, 'unauthenticated', 'A valid anonymous Firebase session is required.');
  }
  return payload.sub;
}

export function validAppCheckClaims(payload: JWTPayload, env: Env, nowSeconds = Math.floor(Date.now() / 1000)): void {
  const audience = payload.aud;
  const expected = `projects/${env.FIREBASE_PROJECT_NUMBER}`;
  const allowedAppIds = [env.FIREBASE_ANDROID_APP_ID];
  if (env.FIREBASE_RELEASE_ANDROID_APP_ID) allowedAppIds.push(env.FIREBASE_RELEASE_ANDROID_APP_ID);
  if (payload.iss !== `https://firebaseappcheck.googleapis.com/${env.FIREBASE_PROJECT_NUMBER}` ||
      !(Array.isArray(audience) ? audience.includes(expected) : audience === expected) ||
      typeof payload.sub !== 'string' || !allowedAppIds.includes(payload.sub) ||
      typeof payload.exp !== 'number' || payload.exp <= nowSeconds) {
    fail(401, 'unauthenticated', 'A valid Focaaa App Check token is required.');
  }
}

export async function authenticate(request: Request, env: Env): Promise<string> {
  const authorization = request.headers.get('authorization');
  const appCheck = request.headers.get('x-firebase-appcheck');
  if (!authorization?.startsWith('Bearer ') || !appCheck) {
    fail(401, 'unauthenticated', 'Firebase sign-in and App Check are required.');
  }
  try {
    const idToken = authorization.slice('Bearer '.length);
    const id = await jwtVerify(idToken, header => {
      if (header.alg !== 'RS256' || typeof header.kid !== 'string') throw new Error('Invalid Firebase token header.');
      return firebaseKey(header.kid);
    }, { algorithms: ['RS256'], audience: env.FIREBASE_PROJECT_ID,
      issuer: `https://securetoken.google.com/${env.FIREBASE_PROJECT_ID}` });
    const uid = validAnonymousClaims(id.payload, env.FIREBASE_PROJECT_ID);
    const app = await jwtVerify(appCheck, appCheckKeys, {
      algorithms: ['RS256'], typ: 'JWT',
      audience: `projects/${env.FIREBASE_PROJECT_NUMBER}`,
      issuer: `https://firebaseappcheck.googleapis.com/${env.FIREBASE_PROJECT_NUMBER}`
    });
    validAppCheckClaims(app.payload, env);
    return uid;
  } catch {
    fail(401, 'unauthenticated', 'Firebase sign-in or App Check could not be verified.');
  }
}
