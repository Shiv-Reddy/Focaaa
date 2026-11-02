package com.example.focaaa.chat.crypto

/**
 * Debug-only proof seam: stores OpenMLS's complete typed MemoryStorage map as one SQLCipher row
 * and asks a fresh native provider call to reconstruct the group. This is not the production
 * provider and does not satisfy retired-secret cryptographic erasure.
 */
class OpenMlsSnapshotStoreProbe(
    private val stateStore: KeystoreRotatingCryptoStateStore,
    private val nativeProbe: OpenMlsCompatibilityProbe = OpenMlsCompatibilityProbe()
) {
    suspend fun createOrRestore(): String {
        val stored = stateStore.read(STATE_ID)
        if (stored != null) {
            return nativeProbe.restoreSnapshotNative(stored) +
                "; room_sqlcipher=read_ok; keystore_aes_gcm=ok"
        }

        val created = nativeProbe.createSnapshotNative()
        stateStore.write(STATE_ID, created)
        val persisted = stateStore.read(STATE_ID)
            ?: error("OpenMLS snapshot was not readable after its SQLCipher write.")
        return nativeProbe.restoreSnapshotNative(persisted) +
            "; room_sqlcipher=write_read_ok; keystore_aes_gcm=ok"
    }

    /** Re-encrypts the current unchanged snapshot under a fresh Keystore key for manual gate checks. */
    suspend fun rotateSnapshotKeyForProbe(): String {
        val current = stateStore.read(STATE_ID)
            ?: error("Create the synthetic MLS snapshot before rotating its key.")
        val oldAlias = stateStore.activeAliasForProbe(STATE_ID)
            ?: error("MLS snapshot has no active Keystore key generation.")
        val oldEncryptedCopy = stateStore.encryptedCopyForProbe(STATE_ID)
            ?: error("MLS snapshot row disappeared before key rotation.")
        check(stateStore.canDecryptCopyForProbe(STATE_ID, oldEncryptedCopy)) {
            "Current MLS snapshot could not be opened before rotation."
        }
        stateStore.write(STATE_ID, current)
        val rotated = stateStore.read(STATE_ID)
            ?: error("MLS snapshot disappeared after key rotation.")
        val newAlias = stateStore.activeAliasForProbe(STATE_ID)
            ?: error("Rotated MLS snapshot has no active Keystore key generation.")
        check(newAlias != oldAlias && !stateStore.aliasExistsForProbe(oldAlias)) {
            "The previous MLS snapshot key was not retired."
        }
        check(stateStore.aliasCountForProbe(STATE_ID) == 1) {
            "Focaaa retained an unexpected MLS snapshot key generation."
        }
        check(!stateStore.canDecryptCopyForProbe(STATE_ID, oldEncryptedCopy)) {
            "A copy encrypted by the retired MLS snapshot key is still readable."
        }
        return nativeProbe.restoreSnapshotNative(rotated) +
            "; key_rotation=ok; previous_key_retired=true; old_copy_readable=false"
    }

    suspend fun deleteSnapshot() {
        stateStore.delete(STATE_ID)
        check(stateStore.aliasCountForProbe(STATE_ID) == 0) {
            "Focaaa retained a Keystore key for the deleted MLS snapshot."
        }
    }

    private companion object {
        const val STATE_ID = "openmls-storage-spike-v1"
    }
}
