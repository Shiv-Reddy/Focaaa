package com.example.focaaa.chat.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Encrypts each complete MLS snapshot with a fresh non-exportable Android Keystore key.
 * This protects superseded SQLCipher row/WAL copies after the previous alias is retired.
 * State transitions can use the Room-backed atomic commit methods to include relay handoffs.
 */
class KeystoreRotatingCryptoStateStore(
    context: Context,
    private val databaseStore: EncryptedCryptoStateStore
) : EncryptedCryptoStateStore {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override suspend fun read(stateId: String): ByteArray? = withContext(Dispatchers.IO) {
        serial.withLock {
            validateStateId(stateId)
            check(!recoverPendingDeletion(stateId)) {
                "Focaaa completed an interrupted MLS-state deletion; a fresh group must be created explicitly."
            }

            val blob = databaseStore.read(stateId)
            if (blob == null) {
                deleteAliases(stateId)
                return@withLock null
            }

            val envelope = decodeEnvelope(stateId, blob)
            val plaintext = decrypt(stateId, envelope)
            // If a previous write committed but crashed before retirement, complete cleanup before
            // making the restored state available to the caller.
            deleteAliases(stateId, keep = envelope.alias)
            plaintext
        }
    }

    /** Recover alias retirement and authenticate current state before relay traffic is permitted. */
    suspend fun ensureReady(stateId: String) {
        val restored = read(stateId)
            ?: error("No persisted MLS state exists for this conversation.")
        restored.fill(0)
    }

    override suspend fun write(stateId: String, state: ByteArray) = writeSnapshot(stateId, state) { id, encrypted ->
        databaseStore.write(id, encrypted)
    }

    suspend fun writeAndEnqueueOutbound(
        stateId: String,
        state: ByteArray,
        envelope: com.example.focaaa.chat.relay.StoredRelayEnvelope,
        localMessage: MlsOutboundHandoff? = null
    ) = writeSnapshot(stateId, state) { id, encrypted ->
        val atomicStore = databaseStore as? AtomicMlsStateStore
            ?: error("The encrypted MLS store does not support atomic relay commits.")
        atomicStore.writeAndEnqueueOutbound(id, encrypted, envelope, localMessage)
    }

    suspend fun writeAndCommitInbound(
        stateId: String,
        state: ByteArray,
        handoff: MlsInboundHandoff
    ) = writeSnapshot(stateId, state) { id, encrypted ->
        val atomicStore = databaseStore as? AtomicMlsStateStore
            ?: error("The encrypted MLS store does not support atomic inbound commits.")
        atomicStore.writeAndCommitInbound(id, encrypted, handoff)
    }

    suspend fun writeAndCommitInboundState(
        stateId: String,
        state: ByteArray,
        envelopeId: String,
        conversationId: String,
        verificationSnapshot: com.example.focaaa.chat.pairing.PairingVerificationSnapshot? = null
    ) = writeSnapshot(stateId, state) { id, encrypted ->
        val atomicStore = databaseStore as? AtomicMlsStateStore
            ?: error("The encrypted MLS store does not support atomic inbound commits.")
        atomicStore.writeAndCommitInboundState(id, encrypted, envelopeId, conversationId, verificationSnapshot)
    }

    private suspend fun writeSnapshot(
        stateId: String,
        state: ByteArray,
        persist: suspend (String, ByteArray) -> Unit
    ) = withContext(Dispatchers.IO) {
        serial.withLock {
            validateStateId(stateId)
            require(state.isNotEmpty() && state.size <= MAX_STATE_BYTES) { "MLS state snapshot is invalid." }
            check(!recoverPendingDeletion(stateId)) {
                "MLS state deletion was being recovered. Create a fresh group in a separate operation."
            }

            val oldBlob = databaseStore.read(stateId)
            if (oldBlob != null) {
                // Refuse to replace state that cannot be authenticated and opened with its current key.
                val oldState = decrypt(stateId, decodeEnvelope(stateId, oldBlob))
                oldState.fill(0)
            } else {
                // Remove keys left by an interrupted first write before creating a new generation.
                deleteAliases(stateId)
            }

            val generation = UUID.randomUUID().toString().replace("-", "")
            val alias = aliasFor(stateId, generation)
            val key = createKey(alias)
            var databaseWriteAttempted = false
            try {
                val envelope = encrypt(stateId, generation, alias, key, state)
                // Keep this key if the write call throws: the database may have committed before
                // reporting an I/O failure, and deleting a referenced key would corrupt the row.
                databaseWriteAttempted = true
                persist(stateId, envelope)

                val committedBlob = databaseStore.read(stateId)
                    ?: error("Encrypted MLS state was not readable after its database commit.")
                val committed = decodeEnvelope(stateId, committedBlob)
                check(committed.alias == alias) { "A different MLS key generation was committed." }
                val verified = decrypt(stateId, committed)
                val valid = MessageDigest.isEqual(verified, state)
                verified.fill(0)
                check(valid) { "Encrypted MLS state did not match the committed snapshot." }

                // Success is returned only after all superseded aliases are gone.
                deleteAliases(stateId, keep = alias)
            } catch (error: Exception) {
                // If the write may have committed, retain the key until a later read can inspect
                // the row and retry cleanup of stale aliases.
                val committedAlias = runCatching {
                    databaseStore.read(stateId)?.let { decodeEnvelope(stateId, it).alias }
                }.getOrNull()
                if (!databaseWriteAttempted || (committedAlias != null && committedAlias != alias)) {
                    runCatching { deleteAlias(alias) }
                }
                throw error
            }
        }
    }

    override suspend fun delete(stateId: String) = withContext(Dispatchers.IO) {
        serial.withLock {
            validateStateId(stateId)
            check(preferences.edit().putBoolean(deleteMarker(stateId), true).commit()) {
                "Focaaa could not safely record pending MLS-state deletion."
            }
            // Keep the marker until both the current row and every generation key are removed.
            databaseStore.delete(stateId)
            deleteAliases(stateId)
            check(preferences.edit().remove(deleteMarker(stateId)).commit()) {
                "MLS state is deleted, but Focaaa could not clear its recovery marker."
            }
        }
    }

    internal suspend fun activeAliasForProbe(stateId: String): String? = withContext(Dispatchers.IO) {
        serial.withLock {
            validateStateId(stateId)
            databaseStore.read(stateId)?.let { decodeEnvelope(stateId, it).alias }
        }
    }

    internal suspend fun encryptedCopyForProbe(stateId: String): ByteArray? = withContext(Dispatchers.IO) {
        serial.withLock {
            validateStateId(stateId)
            databaseStore.read(stateId)?.copyOf()
        }
    }

    internal suspend fun canDecryptCopyForProbe(stateId: String, encryptedCopy: ByteArray): Boolean =
        withContext(Dispatchers.IO) {
            serial.withLock {
                validateStateId(stateId)
                try {
                    val plaintext = decrypt(stateId, decodeEnvelope(stateId, encryptedCopy))
                    plaintext.fill(0)
                    true
                } catch (_: Exception) {
                    false
                }
            }
        }

    internal suspend fun aliasExistsForProbe(alias: String): Boolean = withContext(Dispatchers.IO) {
        serial.withLock { keyStore().containsAlias(alias) }
    }

    internal suspend fun aliasCountForProbe(stateId: String): Int = withContext(Dispatchers.IO) {
        serial.withLock {
            validateStateId(stateId)
            aliases(stateId).size
        }
    }

    private suspend fun recoverPendingDeletion(stateId: String): Boolean {
        if (!preferences.getBoolean(deleteMarker(stateId), false)) return false
        databaseStore.delete(stateId)
        deleteAliases(stateId)
        check(preferences.edit().remove(deleteMarker(stateId)).commit()) {
            "Focaaa could not finish pending MLS-state deletion."
        }
        return true
    }

    private fun encrypt(
        stateId: String,
        generation: String,
        alias: String,
        key: SecretKey,
        plaintext: ByteArray
    ): ByteArray {
        val generationBytes = generation.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
            .put(MAGIC).put(FORMAT_VERSION).put(generationBytes).array()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad(stateId, header))
        val ciphertext = cipher.doFinal(plaintext)
        require(cipher.iv.size == GCM_IV_BYTES) { "Unexpected Android Keystore IV length." }
        return ByteBuffer.allocate(header.size + cipher.iv.size + ciphertext.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(header)
            .put(cipher.iv)
            .put(ciphertext)
            .array()
            .also { check(aliasFor(stateId, generation) == alias) }
    }

    private fun decrypt(stateId: String, envelope: Envelope): ByteArray {
        val key = keyFor(envelope.alias)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(GCM_TAG_BITS, envelope.iv)
        )
        cipher.updateAAD(aad(stateId, envelope.header))
        return cipher.doFinal(envelope.ciphertext)
    }

    private fun decodeEnvelope(stateId: String, bytes: ByteArray): Envelope {
        val minimumEnvelopeBytes = HEADER_BYTES + GCM_IV_BYTES + GCM_TAG_BITS / 8
        require(bytes.size >= minimumEnvelopeBytes && bytes.size <= MAX_ENVELOPE_BYTES) {
            "Encrypted MLS state envelope has an invalid length."
        }
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(MAGIC.size).also { input.get(it) }
        require(magic.contentEquals(MAGIC)) { "Encrypted MLS state envelope has an invalid header." }
        val version = input.get()
        require(version == FORMAT_VERSION) { "Encrypted MLS state envelope version is unsupported." }
        val generationBytes = ByteArray(GENERATION_BYTES).also { input.get(it) }
        val generation = generationBytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val header = bytes.copyOfRange(0, HEADER_BYTES)
        val iv = ByteArray(GCM_IV_BYTES).also { input.get(it) }
        val ciphertext = ByteArray(input.remaining()).also { input.get(it) }
        return Envelope(aliasFor(stateId, generation), header, iv, ciphertext)
    }

    private fun aad(stateId: String, header: ByteArray): ByteArray =
        stateDigest(stateId) + header

    private fun stateDigest(stateId: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(stateId.toByteArray(Charsets.UTF_8))

    private fun statePrefix(stateId: String): String =
        "$ALIAS_PREFIX${stateDigest(stateId).joinToString("") { "%02x".format(it.toInt() and 0xff) }}_"

    private fun aliasFor(stateId: String, generation: String): String = statePrefix(stateId) + generation

    private fun validateStateId(stateId: String) {
        require(stateId.isNotBlank() && stateId.length <= 128) { "MLS state identifier is invalid." }
    }

    private fun deleteMarker(stateId: String): String =
        DELETE_MARKER_PREFIX + stateDigest(stateId).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun aliases(stateId: String): List<String> {
        val prefix = statePrefix(stateId)
        return keyStore().aliases().toList().filter { it.startsWith(prefix) }
    }

    private fun deleteAliases(stateId: String, keep: String? = null) {
        aliases(stateId).filter { it != keep }.forEach(::deleteAlias)
    }

    private fun deleteAlias(alias: String) {
        val store = keyStore()
        if (store.containsAlias(alias)) store.deleteEntry(alias)
        check(!store.containsAlias(alias)) { "Focaaa could not retire an old MLS snapshot key." }
    }

    private fun createKey(alias: String): SecretKey {
        val store = keyStore()
        check(!store.containsAlias(alias)) { "MLS key generation collision." }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun keyFor(alias: String): SecretKey {
        val store = keyStore()
        check(store.containsAlias(alias)) { "The Android Keystore key for MLS state is missing." }
        return store.getKey(alias, null) as? SecretKey
            ?: error("The Android Keystore MLS-state key is unavailable.")
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private data class Envelope(
        val alias: String,
        val header: ByteArray,
        val iv: ByteArray,
        val ciphertext: ByteArray
    )

    private companion object {
        val serial = Mutex()
        val MAGIC = "FOCAKEY1".toByteArray(Charsets.US_ASCII)
        const val FORMAT_VERSION: Byte = 1
        const val HEADER_BYTES = 8 + 1 + 16
        const val GENERATION_BYTES = 16
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val MAX_STATE_BYTES = 16 * 1024 * 1024
        const val MAX_ENVELOPE_BYTES = MAX_STATE_BYTES + HEADER_BYTES + GCM_IV_BYTES + GCM_TAG_BITS / 8
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val PREFERENCES = "focaaa_mls_key_retirement"
        const val DELETE_MARKER_PREFIX = "delete_"
        const val ALIAS_PREFIX = "focaaa_mls_state_"
    }
}
