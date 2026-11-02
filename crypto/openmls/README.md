# OpenMLS Android compatibility probe

This native library and its Kotlin provider adapter are still behind the Phase 4 storage gate. The app does not expose pairing or production messaging, and these sources do not enable an E2EE claim.

The original probe checks that OpenMLS can create and validate a KeyPackage and reconstruct a one-member group through its storage-provider interface. The snapshot spike exports OpenMLS `MemoryStorage`'s complete typed key/value map and restores it through a fresh provider instance. Kotlin stores each snapshot in one SQLCipher Room row under a fresh AES-GCM Android Keystore key, verifies the new row, retires old aliases, and records interrupted deletions for recovery. The source-level provider routes MLS state changes and inbound/outbound relay handoffs through Room transactions. This remains an unverified adapter, not a selected production MLS provider: Android build/restart checks and device/OEM checks of key deletion and copied DB/WAL behavior are still required.

## Build

1. In Android Studio's SDK Manager, install an Android NDK version. Install Rust 1.91 or newer, `cargo-ndk`, and the Android targets:

   ```powershell
   cargo install cargo-ndk
   rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
   ```

2. From the project root, build the native libraries for all supported Android ABIs:

   ```powershell
   .\gradlew.bat :app:buildOpenMlsCompatibilityProbe
   ```

3. Build and install the Android app:

   ```powershell
   .\gradlew.bat :app:assembleDebug
   ```

The Gradle task places generated `.so` files under `app/build/generated/openmls/jniLibs` and checks for `cargo-ndk`, the NDK, and all three Rust targets. The directory is included as a main source set for native libraries.

## Run the probe manually

With the debug app running under the Android Studio debugger, evaluate:

```kotlin
com.example.focaaa.chat.crypto.OpenMlsCompatibilityProbe().runNative()
```

Expected result:

```text
openmls=0.9.2; key_package_roundtrip=ok; group_reload=ok; storage=memory_only
```

An Android build and this result are required before treating the compatibility spike as passed. The in-memory provider is only a library API smoke check; the production state adapter still must integrate with authenticated SQLCipher storage and prove OpenMLS's retired-secret deletion requirements.

The two-member MLS flow can also be executed on the development host from `crypto/openmls`:

```powershell
cargo run --locked --offline --example two_member_snapshot_probe
```

This runs synthetic identities and fixed probe plaintext entirely in memory. It does not connect to Firebase or the app UI.

To exercise a two-member welcome and application-message round trip across serialized provider maps, evaluate:

```kotlin
com.example.focaaa.chat.crypto.OpenMlsCompatibilityProbe().runTwoMemberSnapshotProbe()
```

Expected result:

```text
openmls=0.9.2; two_member_welcome=ok; snapshot_restore=ok; signing_key_restore=ok; inviter_to_invitee=ok; invitee_to_inviter=ok; storage=memory_only
```

This probe creates two local test identities in one process. It validates MLS library flow plus the current snapshot encoding only; it does not prove device-to-device identity verification, SQLCipher/Keystore persistence, Android process restart, crash-safe state-to-relay atomicity, or production deletion semantics.

## Manual Room snapshot restart probe

After the Android build contains the new JNI methods, run this in Android Studio's debugger on the device from an evaluator that supports Kotlin coroutines:

```kotlin
kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
    com.example.focaaa.chat.data.RoomConversationRepository
        .open(applicationContext)
        .openMlsSnapshotStoreProbe
        .createOrRestore()
}
```

On first run, it creates a synthetic one-member group, encrypts the serialized provider map under a fresh Keystore key, writes it to the Room `crypto_state` row, reads/decrypts it, and restores the group. Force-stop and relaunch the app, evaluate the same expression again, and expect `snapshot=restored` with the same `group_id` and epoch. Then evaluate:

```kotlin
kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
    com.example.focaaa.chat.data.RoomConversationRepository
        .open(applicationContext)
        .openMlsSnapshotStoreProbe
        .rotateSnapshotKeyForProbe()
}
```

Expect `key_rotation=ok`, `previous_key_retired=true`, and `old_copy_readable=false`. This captures the old encrypted row before rotation, retires its Keystore alias, and verifies that the captured bytes no longer decrypt. To remove the current snapshot and key, evaluate `runBlocking(Dispatchers.IO) { ...openMlsSnapshotStoreProbe.deleteSnapshot() }`.

The snapshot JNI methods are diagnostics and return raw snapshot bytes to the caller; they must never be stored outside the encrypted store or logged. A successful check proves one-group process-restart reconstruction and retirement of a prior generation key for an in-memory copy of its row. It does not prove physical or OEM-level deletion, unreadability of actual captured DB/WAL/journal files, failure recovery, or production message safety.
