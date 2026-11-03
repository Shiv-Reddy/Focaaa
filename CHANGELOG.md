# Changelog

## 2.0.0 — 4 November 2026

- Keep the focus timer and task list as the ordinary app surface and retain the private chat flow introduced in 1.6.
- Show a temporary typing status in the unlocked private chat without sending draft text or edit history.
- Keep the reader's position when a new message arrives while they are viewing older chat history.
- Reuse one network client for typing status and release screen-specific jobs when chat closes.
- Increase notification-panel timestamp text from 10sp to 11sp for readability.
- Simplify release staging to verify and publish only the current signed APK. Previous draft-enabled APKs stay off the download page.

The signed 2.0.0 upgrade is intended to preserve active pairs, messages, and focus tasks. A second-phone live typing check is still required before describing that behavior as verified on both devices.

## 1.9 — 3 November 2026

- Remove live draft and keystroke sharing, including its local edit history on upgrade.
- Restore the 1.6-style chat and add a temporary boolean typing indicator.
- Keep the direct APK App Check setup and existing message and media paths.
