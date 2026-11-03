# Data storage and encryption

This describes the current Android release and Cloudflare relay. Focaaa has not had an independent security audit.

## On each phone

- Private conversation records, local contact names, pairing state, outgoing queue, and message delivery state live in the app-private Room database `focaaa_messages.db` (`/data/user/0/com.focaaa.app/databases/` for the release app). SQLCipher encrypts the database file. Message text is readable inside the app after the database is unlocked.
- A random database passphrase is wrapped with an AES-GCM key in Android Keystore. The wrapped passphrase is kept in private app preferences. MLS group snapshots receive an additional Keystore-backed encryption layer.
- Sent and downloaded attachments are AES-256-GCM ciphertext files below the app-private `files/private_media/` directory. The file key travels to the partner only in the encrypted conversation message. The original photo or video selected from another app stays wherever that app stores it; Focaaa does not encrypt that original.
- The normal focus timer and tasks are in private app preferences, not in the encrypted chat database. The six-digit PIN is a local access gate, stored as a salted password-derived hash. **The PIN does not encrypt the chat or authenticate the partner.**
- An optional `.fcb` history export is AES-GCM encrypted with a separate recovery key. The user chooses where to save the file. It excludes attachment files and active pairing keys; restoring it creates read-only history.
- In 1.7, live draft sharing is off by default on each chat opening. When explicitly enabled, every composer text change is an ordered edit with a timestamp and any removed text. Both participants store consented edits in the SQLCipher database and can view them for 24 hours. Expired rows are removed when chat next opens or during hourly cleanup while open. The recipient can view recent edits only inside the unlocked private chat. The app does not capture PIN, invite, or other input fields. Android input methods expose text changes, not a guaranteed physical-key log.

Android app backup is disabled. Uninstalling or clearing app data removes local history and keys unless an independent encrypted history export was saved. A history export cannot reconnect an active conversation.

## Online relay

Cloudflare Worker uses the D1 database named `focaaa-relay`. It holds anonymous account and device identifiers, pairing records, push tokens, routing and rate-limit records, message delivery/read metadata, MLS encrypted envelopes, and encrypted media chunks. It does **not** hold message plaintext, local contact names, media decryption keys, or the PIN. Firebase provides anonymous authentication, App Check, and content-free push hints; those services receive identifiers and operational metadata.

MLS encrypts text messages before they reach the Worker. Photo and video bytes are encrypted on the sending phone before upload; their decryption descriptors are carried inside MLS messages. The relay can still see traffic timing, envelope sizes, pair identifiers, and delivery/read events. Transport requests use HTTPS.

Version 1.7 derives a separate 256-bit key from the verified MLS group and encrypts live draft edits with AES-GCM. The authenticated WebSocket Durable Object routes opaque frames between the two registered pair devices. It sees pair/device identifiers, edit sequence and timing, and frame sizes; it does not receive the draft key or plaintext and does not persist a server-side edit history. It is active only while the unlocked chat is open. On reconnect, the sender replays its current session's encrypted edits; while the sender is offline, the partner cannot receive drafts. Version 1.6 clients use only the existing message path.

Unacknowledged encrypted envelopes, their receipts, and encrypted media expire after seven days. Acknowledged envelopes are removed from the queue. Revoked or expired pair metadata is removed after 30 days once its receipts are gone; active pair and device records remain while in use. A daily Worker job purges expired records. Each partner keeps their own local history until they delete it or unpair locally.

Security claims here describe the implemented storage and message path, not a guarantee against a compromised phone, an unlocked device, malicious operating system, or a flaw in the app or cryptographic integration.
