package com.example.focaaa.chat.crypto

import com.example.focaaa.chat.MessageSender
import com.example.focaaa.chat.pairing.PairingVerificationCode
import com.example.focaaa.chat.pairing.PairingVerificationSnapshot
import com.example.focaaa.chat.relay.StoredRelayEnvelope

/**
 * Protocol boundary for pairing and one-to-one message encryption.
 *
 * The demo repository does not implement this interface. Opaque protocol values and plaintext
 * returned from [processIncoming] must never be written to logs or sent to the relay as plaintext.
 */
interface CryptoProvider {
    suspend fun createIdentity(): CryptoIdentityHandle

    suspend fun createKeyPackage(identity: CryptoIdentityHandle): CryptoKeyPackage

    suspend fun createGroupAndWelcome(
        identity: CryptoIdentityHandle,
        peerKeyPackage: CryptoKeyPackage,
        conversationId: String
    ): CryptoGroupInvitation

    suspend fun joinGroup(
        identity: CryptoIdentityHandle,
        welcome: CryptoWelcome,
        conversationId: String
    ): CryptoGroupHandle

    /**
     * Return the shared code derived from the MLS group ID and both authenticated member
     * credentials. Implementations must use the encoding in
     * [com.example.focaaa.chat.pairing.PairingVerificationCodeDerivation] so both clients agree.
     */
    suspend fun verificationFingerprint(group: CryptoGroupHandle): CryptoFingerprint

    /**
     * Process an incoming MLS wire message. Implementations must apply valid commits and persist
     * the resulting epoch before returning. Application plaintext is returned only after message
     * authentication succeeds; malformed, replayed, or out-of-order messages fail.
     */
    suspend fun processIncoming(
        group: CryptoGroupHandle,
        envelope: CryptoEnvelope
    ): CryptoInboundResult

    suspend fun encrypt(
        group: CryptoGroupHandle,
        plaintext: ByteArray
    ): CryptoEnvelope

    /** Sends the fixed MLS-authenticated verification control message after local code confirmation. */
    suspend fun sendPairingConfirmation(group: CryptoGroupHandle, code: PairingVerificationCode): CryptoEnvelope

    suspend fun rotateGroupState(group: CryptoGroupHandle): CryptoGroupUpdate

    suspend fun removePeer(
        group: CryptoGroupHandle,
        peerIdentity: CryptoPeerIdentity
    ): CryptoGroupUpdate
}

/** Opaque reference to private identity material held only by the crypto provider. */
@JvmInline
value class CryptoIdentityHandle(val value: String)

/** An expiring public MLS key package; it must not include private key material. */
data class CryptoKeyPackage(
    val encoded: ByteArray,
    val expiresAtMillis: Long
)

/** Opaque MLS welcome and required public group state for a newly added peer. */
data class CryptoWelcome(
    val encoded: ByteArray,
    val groupContext: ByteArray,
    val initialCommit: ByteArray
)

/** Opaque local reference to persisted MLS group state. */
@JvmInline
value class CryptoGroupHandle(val value: String)

/** Stable public identity for an authenticated MLS group member, never a private key handle. */
data class CryptoPeerIdentity(val encoded: ByteArray)

/** New local group state and the one-time bundle to transfer to the joining device. */
data class CryptoGroupInvitation(
    val group: CryptoGroupHandle,
    val welcome: CryptoWelcome
)

/** Group comparison fingerprint: [displayCode] is human-comparable and [encoded] is the full digest. */
data class CryptoFingerprint(
    val displayCode: String,
    val encoded: ByteArray
)

/** Opaque encrypted MLS application message suitable for the relay envelope. */
data class CryptoEnvelope(
    val encoded: ByteArray,
    val epoch: Long,
    val relayEnvelopeId: String? = null,
    val createdAtMillis: Long = 0
)

/** Result of processing one inbound MLS message from the relay. */
sealed interface CryptoInboundResult {
    /** Authenticated application data, available only after the MLS message was accepted. */
    data class ApplicationMessage(val message: DecryptedCryptoMessage) : CryptoInboundResult

    /**
     * A valid commit or proposal changed local group state. Persist that state before returning;
     * forward [outboundMessage] to the relay when MLS requires this client to publish a response.
     */
    data class GroupStateChanged(
        val group: CryptoGroupHandle,
        val outboundMessage: CryptoEnvelope?
    ) : CryptoInboundResult
}

/** Plaintext and authenticated sender identity produced only after successful decryption. */
data class DecryptedCryptoMessage(
    val plaintext: ByteArray,
    val senderIdentity: CryptoPeerIdentity,
    val epoch: Long
)

/** Updated opaque group state and any protocol commit that must be delivered to the peer. */
data class CryptoGroupUpdate(
    val group: CryptoGroupHandle,
    val commit: CryptoEnvelope
)

/**
 * Encrypted persistence boundary for MLS private/group state.
 * Implementations must use the SQLCipher-protected local database, authenticate stored state, and
 * satisfy the selected MLS provider's atomic-write and irreversible secret-deletion requirements.
 * This interface alone does not prove those requirements are met.
 */
interface EncryptedCryptoStateStore {
    suspend fun read(stateId: String): ByteArray?
    suspend fun write(stateId: String, state: ByteArray)
    suspend fun delete(stateId: String)
}

/** An authenticated inbound plaintext waiting to be committed with its resulting MLS state. */
data class MlsInboundHandoff(
    val envelopeId: String,
    val conversationId: String,
    val conversationTitle: String,
    val messageId: String,
    val plaintext: String,
    val sender: MessageSender,
    val createdAtMillis: Long
) {
    override fun toString(): String = "MlsInboundHandoff(envelopeId=$envelopeId, conversationId=$conversationId, messageId=$messageId, plaintext=<redacted>)"
}

/** Local plaintext history row that must commit atomically with the MLS state and ciphertext outbox. */
data class MlsOutboundHandoff(
    val envelopeId: String,
    val conversationId: String,
    val conversationTitle: String,
    val plaintext: String,
    val createdAtMillis: Long
) {
    override fun toString(): String = "MlsOutboundHandoff(envelopeId=$envelopeId, conversationId=$conversationId, plaintext=<redacted>)"
}

/** Room commits the new encrypted MLS snapshot and its relay/message handoff in one SQL transaction. */
interface AtomicMlsStateStore : EncryptedCryptoStateStore {
    suspend fun writeAndEnqueueOutbound(
        stateId: String,
        state: ByteArray,
        envelope: StoredRelayEnvelope,
        localMessage: MlsOutboundHandoff? = null
    )

    suspend fun writeAndCommitInbound(stateId: String, state: ByteArray, handoff: MlsInboundHandoff)

    suspend fun writeAndCommitInboundState(
        stateId: String,
        state: ByteArray,
        envelopeId: String,
        conversationId: String,
        verificationSnapshot: PairingVerificationSnapshot? = null
    )
}
