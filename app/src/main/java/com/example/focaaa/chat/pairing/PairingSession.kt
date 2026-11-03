package com.example.focaaa.chat.pairing

/** Pairing progress persisted in the SQLCipher database so an invite can resume after process death. */
data class PairingSession(
    val role: PairingRole,
    val stage: PairingStage,
    val identityHandle: String,
    val inviteToken: String? = null,
    val expiresAtMillis: Long = 0L,
    val conversationId: String? = null,
    val keyPackage: ByteArray? = null,
    val welcome: ByteArray? = null,
    val ratchetTree: ByteArray? = null,
    val verificationCode: String? = null,
    val localControlQueued: Boolean = false,
    val relayActive: Boolean = false,
    val liveDraftConsent: Boolean = false
) {
    override fun toString(): String =
        "PairingSession(role=$role, stage=$stage, identityHandle=<redacted>, inviteToken=<redacted>, conversationId=$conversationId, verificationCode=<redacted>, relayActive=$relayActive)"
}

enum class PairingRole { INVITER, JOINER }

enum class PairingStage {
    IDENTITY_READY,
    INVITE_READY,
    JOIN_REQUESTED,
    GROUP_READY,
    VERIFIED_LOCAL,
    ACTIVE
}

/** SQLCipher-backed persistence boundary for one in-progress or active local pair. */
interface PairingSessionStore {
    suspend fun read(sessionId: String): PairingSession?
    suspend fun read(): PairingSession? = read(CURRENT_PAIRING_SESSION_ID)
    suspend fun write(sessionId: String, session: PairingSession)
    suspend fun write(session: PairingSession) = write(pairingSessionStorageKey(session), session)
    suspend fun clear(sessionId: String)
    suspend fun clear() = clear(CURRENT_PAIRING_SESSION_ID)
    fun observeActive(): kotlinx.coroutines.flow.Flow<List<PairingSession>>
}

internal const val CURRENT_PAIRING_SESSION_ID = "current"

internal fun pairingSessionStorageKey(session: PairingSession): String =
    if (session.stage == PairingStage.ACTIVE) {
        requireNotNull(session.conversationId?.takeIf(String::isNotBlank)) {
            "An active pairing must have a conversation ID."
        }
    } else {
        CURRENT_PAIRING_SESSION_ID
    }
