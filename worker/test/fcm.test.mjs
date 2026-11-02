import test from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync } from 'node:crypto';
import { sendHint } from '../src/fcm.ts';

test('FCM hints use scoped bearer tokens and exchange again after credential rotation', async () => {
  const { privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
  const pem = privateKey.export({ type: 'pkcs8', format: 'pem' });
  const originalFetch = globalThis.fetch;
  const exchanges = [];
  const hints = [];
  globalThis.fetch = async (url, options) => {
    if (String(url) === 'https://oauth2.googleapis.com/token') {
      const assertion = new URLSearchParams(options.body).get('assertion');
      const claims = JSON.parse(Buffer.from(assertion.split('.')[1], 'base64url').toString());
      exchanges.push(claims.iss);
      return Response.json({ access_token: `access-${exchanges.length}`, expires_in: 3600 });
    }
    assert.equal(String(url), 'https://fcm.googleapis.com/v1/projects/focaaa-dev/messages:send');
    hints.push({ auth: options.headers.authorization, body: JSON.parse(options.body) });
    return Response.json({ name: 'sent' });
  };
  const env = email => ({
    FIREBASE_PROJECT_ID: 'focaaa-dev',
    FCM_CLIENT_EMAIL: email,
    FCM_PRIVATE_KEY: pem
  });
  try {
    await sendHint(env('first@example.test'), 'device-one', 'pair-one');
    await sendHint(env('first@example.test'), 'device-two', 'pair-two');
    await sendHint(env('second@example.test'), 'device-three', 'pair-three');
    assert.deepEqual(exchanges, ['first@example.test', 'second@example.test']);
    assert.deepEqual(hints.map(hint => hint.auth), ['Bearer access-1', 'Bearer access-1', 'Bearer access-2']);
    assert.deepEqual(hints[2].body, {
      message: {
        token: 'device-three',
        data: { type: 'sync', conversationId: 'pair-three' },
        android: { priority: 'HIGH' }
      }
    });
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('FCM hint is optional when credentials are absent', async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = () => { throw new Error('No request expected'); };
  try {
    await sendHint({ FIREBASE_PROJECT_ID: 'focaaa-dev' }, 'device', 'pair');
  } finally {
    globalThis.fetch = originalFetch;
  }
});
