# Focaaa

An Android focus timer and task list with an optional, PIN-protected private conversation for two consenting partners.

[Download the current early-access APK](https://focaaa-download.focaaa-free-relay.workers.dev/) · [Privacy notice](https://focaaa-download.focaaa-free-relay.workers.dev/privacy.html) · [Terms](https://focaaa-download.focaaa-free-relay.workers.dev/terms.html)

## What it does

- Focus sessions and a simple daily task list open as the ordinary app.
- Two people pair with an invite and verify a matching comparison code.
- Private text, photos, and short MP4 videos use the encrypted conversation. This release allows one active partner.
- A user-added Quick Settings tile can open a notification-panel conversation with inline Reply. Generic focus reminders contain no sender or message text.
- Conversation history stays encrypted on the phone. Optional encrypted history backups restore as read-only records; media files and active pairing keys are not included.
- In 1.8.2, the invite participant checks a clearly labeled box before joining. The invite creator can see that participant's unsent and deleted message edits while their private chat is open; the participant cannot see the creator's drafts. The participant can pause or turn sharing off. Edits remain viewable locally for 24 hours.

Photos are limited to 2 MiB and MP4 videos to 10 MiB. Encrypted relay media copies expire after seven days. The app supports Android 8.0 (API 26) and newer.

## Current release

Version **1.8.2** (version code 11) is the current early-access APK. It replaces the release App Check key with one configured for direct APK distribution; the previous key only supported Google Play installs and rejected attestation on the published APK. Exact signed **1.8.1**, **1.8**, **1.7**, and **1.6** binaries remain available as archives, but their embedded App Check key is no longer accepted for online pairing or chat. Update to 1.8.2 without clearing app data. The Cloudflare relay is designed for its free tier; no paid backend plan is required for this release.

The Android unit suite, release lint, Rust tests, and backend tests passed during development. **The one-way live draft exchange still needs a two-phone check before it can be called verified.** Earlier signed builds passed two-phone pairing, messaging, media, and notification checks. The release signing key and password still need separate offline backups. This project has not received an independent security audit.

## Project layout

| Path | Purpose |
| --- | --- |
| [`app/`](app/) | Kotlin, Jetpack Compose, local encrypted storage, pairing and chat |
| [`crypto/openmls/`](crypto/openmls/) | Rust/OpenMLS Android integration |
| [`worker/`](worker/) | Cloudflare Worker, D1 ciphertext relay, and pair-scoped live-draft WebSocket |
| [`release-site/`](release-site/) | Static direct-download, privacy, and terms pages |
| [`scripts/`](scripts/) | Local release build, staging and key-backup checks |
| [`docs/history-backup.md`](docs/history-backup.md) | Backup and restore behavior |
| [`docs/data-storage-and-encryption.md`](docs/data-storage-and-encryption.md) | Local database, relay storage, encryption boundaries, and retention |

Firebase supplies anonymous authentication, App Check and content-free FCM delivery hints. Cloudflare Workers and D1 handle pairing records and encrypted delivery. The relay does not receive message plaintext or media keys. See [data storage and encryption](docs/data-storage-and-encryption.md) and the [privacy notice](https://focaaa-download.focaaa-free-relay.workers.dev/privacy.html) for boundaries and retention.

## Build locally

1. Install Android Studio with Android SDK 37, an Android NDK, JDK 17 or newer, Rust, `cargo-ndk`, and these Rust targets:

   ```powershell
   cargo install cargo-ndk
   rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
   ```

2. Register your own debug and release Android apps in Firebase. Put their downloaded `google-services.json` files at `app/google-services.json` and `app/src/release/google-services.json`. Copy `app/app_check.example.xml` to `app/src/release/res/values/app_check.xml` and replace the placeholder with your own package-restricted reCAPTCHA Enterprise Android site key. Enable **Support applications distributed outside of the Google Play Store** when creating that key for a direct APK, and register the same key for the release app in Firebase App Check. These local files are ignored by Git.
3. Put your Android SDK path and relay endpoint in the ignored `local.properties` file. The development app ID is `com.example.focaaa`; the signed release ID is `com.focaaa.app`.
4. Run the checks and build:

   ```powershell
   .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintRelease
   ```

For the relay, run `npm ci`, `npm test`, and `npm run typecheck` in `worker/`. Use your own Cloudflare account, D1 database, Firebase project configuration, and Worker secrets before deploying. Never commit service-account JSON, App Check debug tokens, signing keys, or local release credentials.

## Safety and support

Focaaa reduces casual exposure but cannot hide its installation or activity from Android or someone controlling the phone. The tile is added by the user, and both partners must consent to pairing. Read [SECURITY.md](SECURITY.md) before reporting a security issue. For support or privacy requests, contact [xlr8beast2h@gmail.com](mailto:xlr8beast2h@gmail.com) without including a PIN, recovery key, invite code, or private message.

Third-party license notices are in [docs/THIRD_PARTY_NOTICES.md](docs/THIRD_PARTY_NOTICES.md). No license for reuse of this repository's original code is granted at this time.
