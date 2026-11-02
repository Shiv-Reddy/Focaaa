package com.example.focaaa

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** App-local casual access gate. This PIN does not authenticate a partner or encrypt messages. */
internal class LocalPinStore(context: Context) {
    private val preferences = context.getSharedPreferences("focaaa_access", Context.MODE_PRIVATE)

    fun hasPin(): Boolean = preferences.contains(KEY_HASH) && preferences.contains(KEY_SALT)

    fun savePin(pin: String) {
        require(pin.matches(Regex("\\d{6}")))
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val hash = derive(pin, salt)
        preferences.edit()
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .putInt(KEY_FAILURES, 0)
            .remove(KEY_LOCKED_UNTIL)
            .apply()
    }

    fun remainingLockSeconds(now: Long = System.currentTimeMillis()): Int {
        val remainingMs = preferences.getLong(KEY_LOCKED_UNTIL, 0L) - now
        return if (remainingMs <= 0L) 0 else ((remainingMs + 999L) / 1000L).toInt()
    }

    fun verify(pin: String): PinVerification {
        val remaining = remainingLockSeconds()
        if (remaining > 0) return PinVerification.Locked(remaining)

        val salt = preferences.getString(KEY_SALT, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
            ?: return PinVerification.Invalid
        val expected = preferences.getString(KEY_HASH, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
            ?: return PinVerification.Invalid
        val supplied = derive(pin, salt)

        if (MessageDigest.isEqual(expected, supplied)) {
            preferences.edit().putInt(KEY_FAILURES, 0).remove(KEY_LOCKED_UNTIL).apply()
            return PinVerification.Success
        }

        val failures = preferences.getInt(KEY_FAILURES, 0) + 1
        return if (failures >= MAX_FAILURES) {
            preferences.edit()
                .putInt(KEY_FAILURES, 0)
                .putLong(KEY_LOCKED_UNTIL, System.currentTimeMillis() + LOCKOUT_MS)
                .apply()
            PinVerification.Locked(LOCKOUT_SECONDS)
        } else {
            preferences.edit().putInt(KEY_FAILURES, failures).apply()
            PinVerification.Invalid
        }
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, HASH_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private companion object {
        const val KEY_SALT = "pin_salt"
        const val KEY_HASH = "pin_hash"
        const val KEY_FAILURES = "pin_failures"
        const val KEY_LOCKED_UNTIL = "pin_locked_until"
        const val SALT_BYTES = 16
        const val HASH_BITS = 256
        const val PBKDF2_ITERATIONS = 120_000
        const val MAX_FAILURES = 5
        const val LOCKOUT_SECONDS = 30
        const val LOCKOUT_MS = LOCKOUT_SECONDS * 1_000L
    }
}

internal sealed interface PinVerification {
    data object Success : PinVerification
    data object Invalid : PinVerification
    data class Locked(val seconds: Int) : PinVerification
}
