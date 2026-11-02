# Focaaa

An Android focus timer and task list with an optional, PIN-protected private conversation for two consenting partners.

[Download the current early-access APK](https://focaaa-download.focaaa-free-relay.workers.dev/) · [Privacy notice](https://focaaa-download.focaaa-free-relay.workers.dev/privacy.html)

## What it does

- Focus sessions and a simple daily task list open as the ordinary app.
- Two people pair with an invite and verify a matching comparison code.
- Private text, photos, and short MP4 videos use the encrypted conversation. This release allows one active partner.
- A user-added Quick Settings tile can open a notification-panel conversation with inline Reply. Generic focus reminders contain no sender or message text.
- Conversation history stays encrypted on the phone. Optional encrypted history backups restore as read-only records; media files and active pairing keys are not included.

Photos are limited to 2 MiB and MP4 videos to 10 MiB. Encrypted relay media copies expire after seven days. The app supports Android 8.0 (API 26) and newer.

## Current release

Version **1.4** (version code 5) is an early-access direct APK. The signed app and download page share a teal and mist visual theme. The download page provides the APK checksum. The Cloudflare relay is designed for its free tier; no paid backend plan is required for this release.

The Android unit suite, release lint, signed APK build, signature and package checks passed. The public APK was downloaded and matched its local SHA-256. Earlier signed builds passed two-phone pairing, messaging, media, and notification checks. **The exact 1.4 visual update still needs a final check on both phones.** The release signing key and password also need separate offline backups. This project has not received an independent security audit.

## Project layout

| Path | Purpose |
| --- | --- |
| [`app/`](app/) | Kotlin, Jetpack Compose, local encrypted storage, pairing and chat |
| [`crypto/openmls/`](crypto/openmls/) | Rust/OpenMLS Android integration |
| [`worker/`](worker/) | Cloudflare Worker and D1 ciphertext relay |
| [`release-site/`](release-site/) | Static direct-download and privacy pages |
| [`scripts/`](scripts/) | Local release build, staging and key-backup checks |
| [`docs/history-backup.md`](docs/history-backup.md) | Backup and restore behavior |

Firebase supplies anonymous authentication, App Check and content-free FCM delivery hints. Cloudflare Workers and D1 handle pairing records and encrypted delivery. The relay does not receive message plaintext or media keys. See the [privacy notice](https://focaaa-download.focaaa-free-relay.workers.dev/privacy.html) for retention and platform limits.

## Build locally

1. Install Android Studio with Android SDK 37, an Android NDK, JDK 17 or newer, Rust, `cargo-ndk`, and these Rust targets:

   ```powershell
   cargo install cargo-ndk
   rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
   ```

2. Register your own debug and release Android apps in Firebase. Put their downloaded `google-services.json` files at `app/google-services.json` and `app/src/release/google-services.json`. Copy `app/app_check.example.xml` to `app/src/release/res/values/app_check.xml` and replace the placeholder with your own package-restricted reCAPTCHA Enterprise site key. These local files are ignored by Git.
3. Put your Android SDK path and relay endpoint in the ignored `local.properties` file. The development app ID is `com.example.focaaa`; the signed release ID is `com.focaaa.app`.
4. Run the checks and build:

   ```powershell
   .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintRelease
   ```

For the relay, run `npm ci`, `npm test`, and `npm run typecheck` in `worker/`. Use your own Cloudflare account, D1 database, Firebase project configuration, and Worker secrets before deploying. Never commit service-account JSON, App Check debug tokens, signing keys, or local release credentials.

## Safety and support

Focaaa reduces casual exposure but cannot hide its installation or activity from Android or someone controlling the phone. The tile is added by the user, and both partners must consent to pairing. Read [SECURITY.md](SECURITY.md) before reporting a security issue. For support or privacy requests, contact [xlr8beast2h@gmail.com](mailto:xlr8beast2h@gmail.com) without including a PIN, recovery key, invite code, or private message.

Third-party license notices are in [docs/THIRD_PARTY_NOTICES.md](docs/THIRD_PARTY_NOTICES.md). No license for reuse of this repository's original code is granted at this time.
