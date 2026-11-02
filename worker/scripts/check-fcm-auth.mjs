import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { sendHint } from '../src/fcm.ts';

const file = process.argv[2];
if (!file) throw new Error('Pass the local service-account JSON path.');
const credentials = JSON.parse(readFileSync(resolve(file), 'utf8'));
if (credentials.type !== 'service_account' || credentials.project_id !== 'focaaa-dev' ||
    typeof credentials.client_email !== 'string' || typeof credentials.private_key !== 'string') {
  throw new Error('Unexpected service-account file.');
}

try {
  await sendHint({
    FCM_CLIENT_EMAIL: credentials.client_email,
    FCM_PRIVATE_KEY: credentials.private_key,
    FIREBASE_PROJECT_ID: 'focaaa-dev'
  }, 'invalid-focaaa-test-token', 'invalid-test-pair');
  throw new Error('FCM unexpectedly accepted a deliberately invalid device token.');
} catch (error) {
  if (error instanceof Error && error.message === 'FCM hint failed with HTTP 400.') {
    process.stdout.write('FCM authorization succeeded; the deliberately invalid device token was rejected.\n');
  } else {
    throw error;
  }
}
