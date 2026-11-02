import test from 'node:test';
import assert from 'node:assert/strict';
import { authenticate, validAnonymousClaims, validAppCheckClaims } from '../src/auth.ts';

const project = 'focaaa-dev';
const env = {
  FIREBASE_PROJECT_ID: project,
  FIREBASE_PROJECT_NUMBER: '396039922086',
  FIREBASE_ANDROID_APP_ID: '1:396039922086:android:a357329ceb7312bc5aaf67'
};
const now = 2_000_000_000;
const identity = {
  aud: project, iss: `https://securetoken.google.com/${project}`,
  sub: 'anonymous-uid', iat: now - 10, exp: now + 100, auth_time: now - 10,
  firebase: { sign_in_provider: 'anonymous' }
};
const app = {
  iss: `https://firebaseappcheck.googleapis.com/${env.FIREBASE_PROJECT_NUMBER}`,
  aud: [`projects/${env.FIREBASE_PROJECT_NUMBER}`], sub: env.FIREBASE_ANDROID_APP_ID,
  exp: now + 100
};

test('anonymous Firebase claims must match project and provider', () => {
  assert.equal(validAnonymousClaims(identity, project, now), 'anonymous-uid');
  assert.throws(() => validAnonymousClaims({ ...identity, aud: 'other-project' }, project, now));
  assert.throws(() => validAnonymousClaims({ ...identity, firebase: { sign_in_provider: 'password' } }, project, now));
  assert.throws(() => validAnonymousClaims({ ...identity, exp: now - 1 }, project, now));
});

test('App Check claims must match project and Android app', () => {
  assert.doesNotThrow(() => validAppCheckClaims(app, env, now));
  assert.throws(() => validAppCheckClaims({ ...app, sub: 'other-app' }, env, now));
  assert.throws(() => validAppCheckClaims({ ...app, aud: ['projects/1'] }, env, now));
  assert.throws(() => validAppCheckClaims({ ...app, exp: now - 1 }, env, now));
});

test('a configured release Android app is accepted without trusting other app IDs', () => {
  const releaseId = '1:396039922086:android:release-example';
  const withRelease = { ...env, FIREBASE_RELEASE_ANDROID_APP_ID: releaseId };
  assert.doesNotThrow(() => validAppCheckClaims(app, withRelease, now));
  assert.doesNotThrow(() => validAppCheckClaims({ ...app, sub: releaseId }, withRelease, now));
  assert.throws(() => validAppCheckClaims({ ...app, sub: '1:396039922086:android:third-app' }, withRelease, now));
  assert.throws(() => validAppCheckClaims({ ...app, sub: releaseId, aud: ['projects/1'] }, withRelease, now));
  assert.throws(() => validAppCheckClaims({ ...app, sub: releaseId, exp: now - 1 }, withRelease, now));
});

test('private endpoints reject missing credentials before any database access', async () => {
  const request = new Request('https://relay.test/v1/registerDevice', { method: 'POST' });
  await assert.rejects(authenticate(request, env), error => error.status === 401);
});

test('private endpoints reject malformed signed-token input', async () => {
  const request = new Request('https://relay.test/v1/registerDevice', {
    method: 'POST',
    headers: { authorization: 'Bearer invalid.jwt.token', 'x-firebase-appcheck': 'invalid.jwt.token' }
  });
  await assert.rejects(authenticate(request, env), error => error.status === 401);
});
