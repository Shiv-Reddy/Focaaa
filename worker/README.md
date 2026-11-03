# Focaaa free relay

This Worker stores pairing records and opaque encrypted message envelopes in Cloudflare D1. It verifies Firebase anonymous Authentication and Firebase App Check on every private request. The Worker cannot read message plaintext or the Focaaa PIN. Its authenticated WebSocket forwards only a temporary boolean typing status between active pair members; it rejects draft text and keeps no typing history.

## Free-plan behavior

Use a Cloudflare Workers Free account and the free D1 database. No paid upgrade is performed by this project. Cloudflare currently limits Workers Free to 100,000 requests per day and D1 Free to 5 million rows read, 100,000 rows written per day, and 5 GB total storage. D1 Free also limits each invocation to 50 queries and each SQL statement to 100 bound parameters. If a free limit is reached, requests can fail until the daily reset or storage is cleared; messages already accepted in D1 remain queued until their seven-day expiry. Check the [Workers limits](https://developers.cloudflare.com/workers/platform/limits/), [D1 limits](https://developers.cloudflare.com/d1/platform/limits/), and [D1 pricing](https://developers.cloudflare.com/d1/platform/pricing/) before changing traffic or deployment settings.

## Set up

1. In `worker/`, run `npm install`, `npm test`, and `npm run typecheck`.
2. Sign in using `npx wrangler login`. Keep OAuth codes and credentials out of the repository and chat.
3. Create the free D1 database once with `npx wrangler d1 create focaaa-relay --location apac`; put its returned ID in `wrangler.jsonc`. This repository already contains the ID of the development database. Create a separate database and change the ID for another account.
4. Apply schema with `npm run db:remote`, then run `npm run deploy`. Record the exact `https://…workers.dev` URL printed by Wrangler.
5. In the Android project's ignored `local.properties`, set `focaaa.relayBaseUrl=https://…workers.dev` using that exact Worker URL. Build with `./gradlew :app:testDebugUnitTest :app:assembleDebug` and install the APK without clearing app data.
6. In Firebase Console for project `focaaa-dev`, enable anonymous Authentication and configure App Check for the Android app. Debug APKs use Firebase's debug App Check provider: register each installation's debug token in Firebase Console before pairing. Direct-download release APKs use a package-restricted reCAPTCHA Enterprise Android key with **Support applications distributed outside of the Google Play Store** enabled. Register that same key for the release Android app in Firebase App Check. Never publish or paste a debug token.

`/health` is public for connectivity checks. Every `/v1/*` endpoint requires a valid Firebase ID token and App Check token. A 401 response during a live test usually means one of those prerequisites is missing. The current emulator pairing records are not in D1, so existing phone contacts retain their local history but require fresh pairing for this relay.

## Optional background hints

Foreground and reopen sync read the durable D1 queue without Firebase Cloud Messaging. For prompt background hints, create a dedicated service account in `focaaa-dev` with the **Firebase Cloud Messaging API Admin** role, then download its JSON key into the ignored `work/` directory. With Wrangler authenticated, run `node scripts/install-fcm-secrets.mjs ../work/focaaa-fcm-sender.json` from `worker/`. This local script validates the project ID and streams only the email and private key into Cloudflare Worker secrets `FCM_CLIENT_EMAIL` and `FCM_PRIVATE_KEY`; it does not print their values. Rotate or revoke the key in Google Cloud/Firebase if compromised. Do not place the key in `wrangler.jsonc`, Android resources, source control, terminal commands, or chat. Hints carry only a conversation ID; messages are still retrieved from D1.

On 2026-11-01, a dedicated `focaaa-fcm-sender` account was given the Firebase Cloud Messaging API Admin role and its email/key were added as Cloudflare Worker secrets. `npx wrangler secret list` showed both names without values. `node --experimental-strip-types scripts/check-fcm-auth.mjs ../work/focaaa-fcm-sender.json` proved that Google accepted the credential and rejected a deliberately invalid device token; it sent nothing to a phone. The tested credential-rotation cache fix is deployed, but real push delivery still needs a physical-phone check. Keep the local JSON only in ignored private storage and rotate/revoke it if exposed.

## Checks and maintenance

- `npm test` covers pairing, message retry/receipt/ack, expiry, revocation, and authorization failure cases.
- `npm run typecheck` checks Worker TypeScript.
- `npm run dev` and `npm run db:local` allow local D1 checks; they do not prove the hosted identity flow.
- After deployment, check `/health`, then test a fresh two-phone pair, both send directions, background/reopen delivery, unpair, and re-pair on actual phones.
- Review usage in Cloudflare Dashboard → Workers & Pages and D1 Metrics. The daily cron removes expired invites, ciphertext, and receipts. Avoid deleting the D1 database while phones have active pairs.
