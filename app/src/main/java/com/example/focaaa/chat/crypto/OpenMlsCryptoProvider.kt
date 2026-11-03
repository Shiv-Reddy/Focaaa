package com.example.focaaa.chat.crypto

import com.example.focaaa.chat.MessageSender
import com.example.focaaa.chat.pairing.PairingVerificationCode
import com.example.focaaa.chat.pairing.PairingVerificationCodeDerivation
import com.example.focaaa.chat.pairing.PairingVerificationSnapshot
import com.example.focaaa.chat.pairing.PairingVerificationStore
import com.example.focaaa.chat.crypto.MlsOutboundHandoff
import com.example.focaaa.chat.relay.RelayQueueState
import com.example.focaaa.chat.relay.StoredRelayEnvelope
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/**
 * OpenMLS 0.9.2 adapter over the native provider-map snapshot and Room atomic handoff seams.
 * This remains opt-in scaffolding until Android/JNI, key-retirement, and retired-secret deletion
 * checks pass on supported devices.
 */
class OpenMlsCryptoProvider(
    private val stateStore: KeystoreRotatingCryptoStateStore,
    private val verificationStore: PairingVerificationStore,
    private val native: NativeOpenMls = NativeOpenMls()
) : CryptoProvider {
    suspend fun ensureReady(group: CryptoGroupHandle) = stateStore.ensureReady(group.value)

    suspend fun discardIdentity(identity: CryptoIdentityHandle) = stateStore.delete(identity.value)

    override suspend fun createIdentity(): CryptoIdentityHandle {
        val identity = ByteArray(IDENTITY_BYTES).also(random::nextBytes)
        val handle = CryptoIdentityHandle(UUID.randomUUID().toString())
        try {
            val result = NativeOpenMls.decodeResult(native.createIdentityNative(identity), 1)
            try {
                stateStore.write(handle.value, result[0])
                return handle
            } finally {
                result.forEach { it.fill(0) }
            }
        } finally {
            identity.fill(0)
        }
    }

    override suspend fun createKeyPackage(identity: CryptoIdentityHandle): CryptoKeyPackage =
        withSnapshot(identity.value) { snapshot ->
            val result = NativeOpenMls.decodeResult(native.createKeyPackageNative(snapshot), 2)
            try {
                stateStore.write(identity.value, result[0])
                CryptoKeyPackage(
                    encoded = result[1].copyOf(),
                    expiresAtMillis = System.currentTimeMillis() + KEY_PACKAGE_TTL_MILLIS
                )
            } finally {
                result.forEach { it.fill(0) }
            }
        }

    override suspend fun createGroupAndWelcome(
        identity: CryptoIdentityHandle,
        peerKeyPackage: CryptoKeyPackage,
        conversationId: String
    ): CryptoGroupInvitation {
        requireConversationId(conversationId)
        check(peerKeyPackage.expiresAtMillis > System.currentTimeMillis()) {
            "The partner's KeyPackage has expired. Ask them to create a fresh invite."
        }
        val result = withSnapshot(identity.value) { snapshot ->
            NativeOpenMls.decodeResult(native.addMemberNative(snapshot, peerKeyPackage.encoded), 6)
        }
        try {
            val epoch = result[5].asLong()
            check(epoch >= 1L) { "MLS group add did not advance the group epoch." }
            // The Welcome installs the group at the new epoch, so the inviter's initial commit
            // must not also be replayed to the joining device as a relay message.
            stateStore.write(conversationId, result[0])
            return CryptoGroupInvitation(
                group = CryptoGroupHandle(conversationId),
                welcome = CryptoWelcome(
                    encoded = result[1].copyOf(),
                    groupContext = result[2].copyOf(),
                    initialCommit = result[3].copyOf()
                )
            )
        } finally {
                result.forEach { it.fill(0) }
        }
    }

    override suspend fun joinGroup(
        identity: CryptoIdentityHandle,
        welcome: CryptoWelcome,
        conversationId: String
    ): CryptoGroupHandle {
        requireConversationId(conversationId)
        val result = withSnapshot(identity.value) { snapshot ->
            NativeOpenMls.decodeResult(
                native.joinGroupNative(snapshot, welcome.encoded, welcome.groupContext),
                3
            )
        }
        try {
            check(result[2].asLong() > 0L) { "The MLS Welcome did not establish a joined epoch." }
            stateStore.write(conversationId, result[0])
            return CryptoGroupHandle(conversationId)
        } finally {
            result.forEach { it.fill(0) }
        }
    }

    override suspend fun verificationFingerprint(group: CryptoGroupHandle): CryptoFingerprint =
        withSnapshot(group.value) { snapshot ->
            val fields = NativeOpenMls.decodeResult(native.fingerprintNative(snapshot), 4)
            val first = MessageDigest.getInstance("SHA-256").digest(fields[1])
            val second = MessageDigest.getInstance("SHA-256").digest(fields[2])
            try {
                PairingVerificationCodeDerivation.derive(fields[0], first, second)
            } finally {
                fields.forEach { it.fill(0) }
                first.fill(0)
                second.fill(0)
            }
        }

    /** Returns the other MLS member's canonical credential only after validating the pair has two members. */
    suspend fun peerIdentity(group: CryptoGroupHandle): CryptoPeerIdentity =
        withSnapshot(group.value) { snapshot ->
            val fields = NativeOpenMls.decodeResult(native.fingerprintNative(snapshot), 4)
            try {
                val peer = when {
                    fields[1].contentEquals(fields[3]) -> fields[2]
                    fields[2].contentEquals(fields[3]) -> fields[1]
                    else -> error("The local MLS credential is not a member of this group.")
                }
                CryptoPeerIdentity(peer.copyOf())
            } finally {
                fields.forEach { it.fill(0) }
            }
        }

    override suspend fun processIncoming(
        group: CryptoGroupHandle,
        envelope: CryptoEnvelope
    ): CryptoInboundResult {
        require(envelope.relayEnvelopeId != null && envelope.relayEnvelopeId.isNotBlank()) {
            "Inbound MLS processing requires the stable relay envelope ID."
        }
        check(envelope.createdAtMillis > 0L) { "Inbound MLS processing requires the relay creation time." }
        val conversationId = group.value
        val result = withSnapshot(conversationId) { snapshot ->
            check(envelope.encoded.size <= MAX_ENVELOPE_BYTES) { "MLS envelope is too large." }
            NativeOpenMls.decodeResult(
                native.processMessageNative(snapshot, envelope.encoded),
                2
            )
        }
        return try {
            val outcome = NativeOpenMls.decodeResult(result[1], 4)
            try {
                val senderCredential = outcome[2]
                val envelopeId = requireNotNull(envelope.relayEnvelopeId)
                when (outcome[0].singleOrNull()?.toInt()) {
                    OUTCOME_APPLICATION -> {
                        ensureAuthenticatedPeer(group, senderCredential)
                        val sender = CryptoPeerIdentity(senderCredential.copyOf())
                        val text = decodeUtf8(outcome[1])
                        val verification = verificationStore.read(group)
                            ?: error("Pair verification must be prepared before accepting application messages.")
                        if (text.startsWith(PAIRING_CONFIRMATION_PREFIX)) {
                            val enteredCode = text.removePrefix(PAIRING_CONFIRMATION_PREFIX)
                            check(!verification.revoked && verification.localConfirmed) {
                                "Confirm the displayed code on this device before accepting partner confirmation."
                            }
                            check(verification.expectedPeerIdentity.contentEquals(sender.encoded)) {
                                "The pairing confirmation came from an unexpected MLS identity."
                            }
                            check(enteredCode == verification.expectedCode.value) {
                                "The partner confirmed a different pairing code."
                            }
                            check(verificationFingerprint(group).displayCode == enteredCode) {
                                "The pairing code no longer matches the current MLS group."
                            }
                            stateStore.writeAndCommitInboundState(
                                stateId = conversationId,
                                state = result[0],
                                envelopeId = envelopeId,
                                conversationId = conversationId,
                                verificationSnapshot = verification.copy(
                                    expectedPeerIdentity = verification.expectedPeerIdentity.copyOf(),
                                    peerConfirmed = true
                                )
                            )
                            CryptoInboundResult.GroupStateChanged(group, outboundMessage = null)
                        } else {
                            check(
                                verification.localConfirmed &&
                                    verification.peerConfirmed &&
                                    !verification.revoked
                            ) {
                                "Both partners must confirm the current pairing code before messages can be received."
                            }
                            check(verificationFingerprint(group).displayCode == verification.expectedCode.value) {
                                "The pairing code no longer matches the current MLS group."
                            }
                            stateStore.writeAndCommitInbound(
                                stateId = conversationId,
                                state = result[0],
                                handoff = MlsInboundHandoff(
                                    envelopeId = envelopeId,
                                    conversationId = conversationId,
                                    conversationTitle = DEFAULT_PARTNER_TITLE,
                                    messageId = envelopeId,
                                    plaintext = text,
                                    sender = MessageSender.REMOTE_PARTNER,
                                    createdAtMillis = envelope.createdAtMillis
                                )
                            )
                            CryptoInboundResult.ApplicationMessage(
                                DecryptedCryptoMessage(
                                    plaintext = outcome[1].copyOf(),
                                    senderIdentity = sender,
                                    epoch = outcome[3].asLong()
                                )
                            )
                        }
                    }
                    OUTCOME_STATE_CHANGED -> {
                        ensureAuthenticatedPeer(group, senderCredential)
                        stateStore.writeAndCommitInboundState(
                            stateId = conversationId,
                            state = result[0],
                            envelopeId = envelopeId,
                            conversationId = conversationId
                        )
                        CryptoInboundResult.GroupStateChanged(group, outboundMessage = null)
                    }
                    OUTCOME_OWN_ECHO -> {
                        stateStore.writeAndCommitInboundState(
                            stateId = conversationId,
                            state = result[0],
                            envelopeId = envelopeId,
                            conversationId = conversationId
                        )
                        CryptoInboundResult.GroupStateChanged(group, outboundMessage = null)
                    }
                    else -> error("OpenMLS returned an unsupported inbound message type.")
                }
            } finally {
                outcome.forEach { it.fill(0) }
            }
        } finally {
            result.forEach { it.fill(0) }
        }
    }

    override suspend fun encrypt(
        group: CryptoGroupHandle,
        plaintext: ByteArray
    ): CryptoEnvelope {
        check(canSend(group)) { "Pair verification is incomplete or revoked; message sending stays locked." }
        require(plaintext.isNotEmpty() && plaintext.size <= MAX_ENVELOPE_BYTES)
        return encryptAndQueue(group, plaintext, saveLocalMessage = true)
    }

    override suspend fun sendPairingConfirmation(
        group: CryptoGroupHandle,
        code: PairingVerificationCode
    ): CryptoEnvelope {
        val verification = verificationStore.read(group)
            ?: error("Pair verification has not been initialized for this group.")
        check(verification.localConfirmed && !verification.revoked) {
            "Confirm the displayed code on this device before sending the pairing confirmation."
        }
        check(code == verification.expectedCode && verificationFingerprint(group).displayCode == code.value) {
            "The pairing code does not match the current MLS group."
        }
        ensureExpectedPeerIsCurrentMember(group, verification.expectedPeerIdentity)
        val control = "$PAIRING_CONFIRMATION_PREFIX${code.value}".toByteArray(Charsets.US_ASCII)
        return try {
            encryptAndQueue(group, control, saveLocalMessage = false)
        } finally {
            control.fill(0)
        }
    }

    private suspend fun encryptAndQueue(
        group: CryptoGroupHandle,
        plaintext: ByteArray,
        saveLocalMessage: Boolean
    ): CryptoEnvelope {
        val result = withSnapshot(group.value) { snapshot ->
            NativeOpenMls.decodeResult(native.encryptNative(snapshot, plaintext), 3)
        }
        try {
            val envelopeId = UUID.randomUUID().toString()
            val encoded = result[1].copyOf()
            val outbound = newOutboundEnvelope(group.value, encoded, envelopeId)
            val localMessage = if (saveLocalMessage) {
                val text = decodeUtf8(plaintext)
                require(text.isNotBlank()) { "A chat message cannot be empty." }
                MlsOutboundHandoff(
                    envelopeId = envelopeId,
                    conversationId = group.value,
                    conversationTitle = DEFAULT_PARTNER_TITLE,
                    plaintext = text,
                    createdAtMillis = outbound.createdAtMillis
                )
            } else null
            stateStore.writeAndEnqueueOutbound(
                stateId = group.value,
                state = result[0],
                envelope = outbound,
                localMessage = localMessage
            )
            return CryptoEnvelope(
                encoded = encoded,
                epoch = result[2].asLong(),
                relayEnvelopeId = envelopeId,
                createdAtMillis = outbound.createdAtMillis
            )
        } finally {
            result.forEach { it.fill(0) }
        }
    }

    override suspend fun rotateGroupState(group: CryptoGroupHandle): CryptoGroupUpdate {
        val result = withSnapshot(group.value) { snapshot ->
            NativeOpenMls.decodeResult(native.rotateGroupStateNative(snapshot), 3)
        }
        try {
            val commit = result[1].copyOf()
            verificationStore.delete(group)
            stateStore.writeAndEnqueueOutbound(
                stateId = group.value,
                state = result[0],
                envelope = newOutboundEnvelope(group.value, commit)
            )
            return CryptoGroupUpdate(group, CryptoEnvelope(commit, result[2].asLong()))
        } finally {
            result.forEach { it.fill(0) }
        }
    }

    override suspend fun removePeer(
        group: CryptoGroupHandle,
        peerIdentity: CryptoPeerIdentity
    ): CryptoGroupUpdate {
        require(peerIdentity.encoded.isNotEmpty())
        val result = withSnapshot(group.value) { snapshot ->
            NativeOpenMls.decodeResult(
                native.removePeerNative(snapshot, peerIdentity.encoded),
                3
            )
        }
        try {
            val commit = result[1].copyOf()
            verificationStore.delete(group)
            stateStore.writeAndEnqueueOutbound(
                stateId = group.value,
                state = result[0],
                envelope = newOutboundEnvelope(group.value, commit)
            )
            return CryptoGroupUpdate(group, CryptoEnvelope(commit, result[2].asLong()))
        } finally {
            result.forEach { it.fill(0) }
        }
    }

    private suspend fun canSend(group: CryptoGroupHandle): Boolean {
        val verification = runCatching { verificationStore.read(group) }.getOrNull() ?: return false
        if (!verification.localConfirmed || !verification.peerConfirmed || verification.revoked) return false
        val fields = runCatching {
            withSnapshot(group.value) { snapshot ->
                NativeOpenMls.decodeResult(native.fingerprintNative(snapshot), 4)
            }
        }.getOrNull() ?: return false
        try {
            val ownCredential = fields[3]
            val peerCredential = when {
                fields[1].contentEquals(ownCredential) -> fields[2]
                fields[2].contentEquals(ownCredential) -> fields[1]
                else -> return false
            }
            if (!peerCredential.contentEquals(verification.expectedPeerIdentity)) return false
            val first = MessageDigest.getInstance("SHA-256").digest(fields[1])
            val second = MessageDigest.getInstance("SHA-256").digest(fields[2])
            return try {
                PairingVerificationCodeDerivation.derive(fields[0], first, second).displayCode ==
                    verification.expectedCode.value
            } finally {
                first.fill(0)
                second.fill(0)
            }
        } finally {
            fields.forEach { it.fill(0) }
        }
    }

    private suspend fun ensureExpectedPeerIsCurrentMember(group: CryptoGroupHandle, expectedPeer: ByteArray) {
        val fields = withSnapshot(group.value) { snapshot ->
            NativeOpenMls.decodeResult(native.fingerprintNative(snapshot), 4)
        }
        try {
            val isCurrentPeer = (fields[1].contentEquals(fields[3]) && fields[2].contentEquals(expectedPeer)) ||
                (fields[2].contentEquals(fields[3]) && fields[1].contentEquals(expectedPeer))
            check(isCurrentPeer) { "The expected partner identity is not a member of this MLS group." }
        } finally {
            fields.forEach { it.fill(0) }
        }
    }

    private suspend fun ensureAuthenticatedPeer(group: CryptoGroupHandle, credential: ByteArray) {
        val fields = withSnapshot(group.value) { snapshot ->
            NativeOpenMls.decodeResult(native.fingerprintNative(snapshot), 4)
        }
        try {
            check(credential.contentEquals(fields[1]) || credential.contentEquals(fields[2])) {
                "The inbound MLS sender is not a member of this conversation."
            }
            check(!credential.contentEquals(fields[3])) { "An inbound application message cannot be attributed to this device." }
            val expected = runCatching { verificationStore.read(group) }.getOrNull()
            if (expected != null && expected.expectedPeerIdentity.isNotEmpty()) {
                check(credential.contentEquals(expected.expectedPeerIdentity)) {
                    "The authenticated MLS sender does not match the verified partner identity."
                }
            }
        } finally {
            fields.forEach { it.fill(0) }
        }
    }

    private suspend fun newOutboundEnvelope(
        conversationId: String,
        payload: ByteArray,
        envelopeId: String = UUID.randomUUID().toString()
    ): StoredRelayEnvelope {
        val now = System.currentTimeMillis()
        return StoredRelayEnvelope(
            envelopeId = envelopeId,
            conversationId = conversationId,
            payload = payload.copyOf(),
            createdAtMillis = now,
            expiresAtMillis = now + RELAY_TTL_MILLIS,
            state = RelayQueueState.QUEUED
        )
    }

    private suspend fun <T> withSnapshot(stateId: String, operation: suspend (ByteArray) -> T): T {
        val snapshot = stateStore.read(stateId)
            ?: error("No persisted MLS state exists for this identity or conversation.")
        return try {
            operation(snapshot)
        } finally {
            snapshot.fill(0)
        }
    }

    private fun requireConversationId(value: String) {
        require(value.length in 1..128 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "Conversation ID is invalid."
        }
    }

    private fun decodeUtf8(value: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(value))
        .toString()

    private fun ByteArray.asLong(): Long {
        require(size == java.lang.Long.BYTES)
        return ByteBuffer.wrap(this).order(ByteOrder.BIG_ENDIAN).long
    }

    private companion object {
        val random = SecureRandom()
        const val IDENTITY_BYTES = 32
        const val MAX_ENVELOPE_BYTES = 48 * 1024
        const val KEY_PACKAGE_TTL_MILLIS = 5 * 60 * 1000L
        const val RELAY_TTL_MILLIS = 7 * 24 * 60 * 60 * 1000L
        const val DEFAULT_PARTNER_TITLE = "Partner"
        const val OUTCOME_APPLICATION = 1
        const val OUTCOME_STATE_CHANGED = 2
        const val OUTCOME_OWN_ECHO = 3
        const val PAIRING_CONFIRMATION_PREFIX = "FOCAAA-VERIFY-1:"
    }
}
