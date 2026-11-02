# Saved history backup

Open the private area with the configured focus timer and PIN, then go to Settings → Saved history.

## Back up

Tap **Back up history**. Focaaa prepares an encrypted `.fcb` file and displays a new recovery key. Record the key separately before choosing where to save the file. If you copy it, clear it from the clipboard afterward. The app does not retain the key and cannot recover it. Anyone with both the file and key can read the history.

The file contains local contact names, message text, sender direction, and timestamps for active and previously restored conversations. It does not contain the PIN, installation credentials, pairing state, MLS identity, or encryption keys. The file is encrypted on the phone with AES-256-GCM before the Android document picker writes it. There is no automatic cloud upload by Focaaa; the chosen document provider controls where the file goes.

## Restore

Tap **Restore history**, enter the exact recovery key, and select the `.fcb` file. Restored contacts are labelled **Restored history · read only**. They cannot send messages or resume an old pairing. To message that person again, create a new invite and complete the comparison on both devices. Restoring the same file twice does not duplicate its messages. A wrong key, changed file, or unsupported version is rejected before the database import.

The format is versioned and limited to 100 conversations, 20,000 messages, and a 16 MiB file. If the history exceeds those limits, export stops with an error and leaves local data in place. Imported history remains on this phone until you remove it in the private contacts screen.

This is an optional recovery path for saved messages, not a transfer of an active private chat. Test an export and restore with disposable history before relying on it for important data.
