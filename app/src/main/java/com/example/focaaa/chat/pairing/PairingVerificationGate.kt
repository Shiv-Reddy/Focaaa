package com.example.focaaa.chat.pairing

import com.example.focaaa.chat.crypto.CryptoPeerIdentity
import com.example.focaaa.chat.crypto.CryptoGroupHandle

/** Twelve-digit numeric code derived by the crypto provider from the authenticated two-member group. */
@JvmInline
value class PairingVerificationCode(val value: String) {
    init {
        require(value.length == CODE_DIGITS && value.all { it in '0'..'9' }) {
            "Verification codes must contain exactly 12 ASCII digits."
        }
    }

    companion object {
        private const val CODE_DIGITS = 12
    }
}

/** Snapshot to persist with the authenticated, encrypted conversation record. */
data class PairingVerificationSnapshot(
    val expectedPeerIdentity: ByteArray,
    val expectedCode: PairingVerificationCode,
    val localConfirmed: Boolean,
    val peerConfirmed: Boolean,
    val revoked: Boolean
)

/** Persistence boundary for the confirmation state associated with one local MLS group handle. */
interface PairingVerificationStore {
    suspend fun read(group: CryptoGroupHandle): PairingVerificationSnapshot?
    suspend fun write(group: CryptoGroupHandle, snapshot: PairingVerificationSnapshot)
    suspend fun delete(group: CryptoGroupHandle)
}

/**
 * Keeps application messaging locked until both members explicitly confirm the same code.
 * Construct this only after the MLS provider has authenticated the expected peer. Persist
 * [snapshot] with the encrypted conversation state and pass it back on restore. Calls should be
 * serialized by the pairing coordinator.
 */
class PairingVerificationGate(
    expectedPeer: CryptoPeerIdentity,
    private val expectedCode: PairingVerificationCode,
    restored: PairingVerificationSnapshot? = null
) {
    private val expectedPeerBytes = expectedPeer.encoded.copyOf().also {
        require(it.isNotEmpty()) { "Expected peer identity must not be empty." }
    }

    init {
        if (restored != null) {
            require(restored.expectedCode == expectedCode)
            require(restored.expectedPeerIdentity.contentEquals(expectedPeerBytes))
        }
    }

    private var localConfirmed = restored?.localConfirmed ?: false
    private var peerConfirmed = restored?.peerConfirmed ?: false
    private var revoked = restored?.revoked ?: false

    val canSendApplicationMessages: Boolean
        get() = !revoked && localConfirmed && peerConfirmed

    fun snapshot(): PairingVerificationSnapshot = PairingVerificationSnapshot(
        expectedPeerIdentity = expectedPeerBytes.copyOf(),
        expectedCode = expectedCode,
        localConfirmed = localConfirmed,
        peerConfirmed = peerConfirmed,
        revoked = revoked
    )

    /** Marks only this device's user's explicit comparison of the displayed code. */
    fun confirmLocalCode(enteredCode: String): Boolean {
        if (revoked || enteredCode != expectedCode.value) return false
        localConfirmed = true
        return true
    }

    /**
     * Records the partner's confirmation only when its code matches and its identity came from
     * the MLS-authenticated sender of the verification-control message.
     */
    fun confirmPeerCode(
        authenticatedSender: CryptoPeerIdentity,
        confirmedCode: String
    ): Boolean {
        if (revoked || confirmedCode != expectedCode.value) return false
        if (!authenticatedSender.encoded.contentEquals(expectedPeerBytes)) return false
        peerConfirmed = true
        return true
    }

    /** Revocation is terminal for this gate instance and always closes the send path. */
    fun revoke() {
        revoked = true
        localConfirmed = false
        peerConfirmed = false
    }
}
