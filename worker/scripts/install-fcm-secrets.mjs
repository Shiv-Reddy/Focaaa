import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const file = process.argv[2];
if (!file) {
  throw new Error('Pass the local service-account JSON path. Never paste the key into a command.');
}

const credentials = JSON.parse(readFileSync(resolve(file), 'utf8'));
if (credentials.type !== 'service_account' || credentials.project_id !== 'focaaa-dev' ||
    typeof credentials.client_email !== 'string' ||
    !credentials.client_email.endsWith('.iam.gserviceaccount.com') ||
    typeof credentials.private_key !== 'string' ||
    !credentials.private_key.startsWith('-----BEGIN PRIVATE KEY-----\n') ||
    !credentials.private_key.includes('\n-----END PRIVATE KEY-----')) {
  throw new Error('The file is not a service-account key for the focaaa-dev project.');
}

for (const [name, value] of [
  ['FCM_CLIENT_EMAIL', credentials.client_email],
  ['FCM_PRIVATE_KEY', credentials.private_key]
]) {
  const result = spawnSync(
    process.execPath,
    [fileURLToPath(new URL('../node_modules/wrangler/bin/wrangler.js', import.meta.url)), 'secret', 'put', name],
    { cwd: fileURLToPath(new URL('..', import.meta.url)), input: value, encoding: 'utf8', windowsHide: true }
  );
  if (result.error || result.status !== 0) {
    throw new Error(`Cloudflare secret ${name} was not installed (exit ${result.status ?? 'unknown'}).`);
  }
  process.stdout.write(`${name} installed.\n`);
}
