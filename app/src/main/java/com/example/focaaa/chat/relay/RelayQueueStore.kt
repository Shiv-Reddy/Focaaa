package com.example.focaaa.chat.relay

/** Ciphertext-only local handoff journal, stored in Focaaa's SQLCipher database. */
interface RelayQueueStore {
    suspend fun stageInbound(envelope: StoredRelayEnvelope)
    suspend fun pendingInbound(conversationId: String): List<StoredRelayEnvelope>
    suspend fun removeInboundAfterAcknowledgement(envelopeId: String)
    suspend fun enqueueOutbound(envelope: StoredRelayEnvelope)
    suspend fun pendingOutbound(): List<StoredRelayEnvelope>
    suspend fun removeOutboundAfterAcceptance(envelopeId: String)
}

data class StoredRelayEnvelope(
    val envelopeId: String,
    val conversationId: String,
    val payload: ByteArray,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
    val state: RelayQueueState = RelayQueueState.STAGED
) {
    fun payloadCopy(): ByteArray = payload.copyOf()
}

enum class RelayQueueState {
    STAGED,
    COMMITTED,
    QUEUED
}
