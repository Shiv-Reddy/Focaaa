package com.example.focaaa.chat.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Protects a random SQLCipher passphrase with an AES-GCM key held by Android Keystore. */
internal class DatabaseKeyManager(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun getOrCreatePassphrase(): ByteArray {
        val hasWrappedKey = preferences.contains(WRAPPED_KEY) && preferences.contains(IV)
        val databaseExists = appContext.getDatabasePath(DATABASE_NAME).exists()
        if (hasWrappedKey) return unwrap()

        if (databaseExists) {
            throw LocalDatabaseKeyException(
                "The local database key is missing. Focaaa kept the database untouched; clear app data only if you accept losing its local history."
            )
        }

        // If there is no database and no wrapped passphrase, the history is already absent.
        // Remove a leftover alias (for example after Android cleared app preferences) so a
        // clean local setup can create a fresh wrapping key.
        deleteStaleKeyIfPresent()

        val passphrase = ByteArray(PASSPHRASE_BYTES).also(java.security.SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrappingKey())
        val wrapped = cipher.doFinal(passphrase)
        val saved = preferences.edit()
            .putString(WRAPPED_KEY, Base64.encodeToString(wrapped, Base64.NO_WRAP))
            .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .commit()
        if (!saved) throw LocalDatabaseKeyException("Focaaa could not safely store the local database key.")
        return passphrase
    }

    private fun unwrap(): ByteArray {
        try {
            val wrapped = Base64.decode(preferences.getString(WRAPPED_KEY, null), Base64.NO_WRAP)
            val iv = Base64.decode(preferences.getString(IV, null), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateWrappingKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            return cipher.doFinal(wrapped)
        } catch (error: Exception) {
            throw LocalDatabaseKeyException(
                "Focaaa could not unlock the encrypted local database. The database was not deleted or replaced.",
                error
            )
        }
    }

    private fun getOrCreateWrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
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

    private fun deleteStaleKeyIfPresent() {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    companion object {
        const val DATABASE_NAME = "focaaa_messages.db"
        const val KEY_PREFERENCES = "focaaa_db_key.xml"
        private const val PREFERENCES = "focaaa_db_key"
        private const val KEY_ALIAS = "focaaa_room_wrapping_key_v1"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PASSPHRASE_BYTES = 32
        private const val GCM_TAG_BITS = 128
        private const val WRAPPED_KEY = "wrapped_passphrase"
        private const val IV = "passphrase_iv"
    }
}

class LocalDatabaseKeyException(message: String, cause: Throwable? = null) : Exception(message, cause)
