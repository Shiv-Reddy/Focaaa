package com.example.focaaa.chat.relay

import android.util.Base64
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Reconciles the server queue with SQLCipher before acknowledging delivery. The inbound processor
 * must atomically persist accepted MLS state, the idempotent local message, and committed queue
 * state before returning.
 */
class RelaySyncCoordinator(
    private val gateway: RelayGateway,
    private val queue: RelayQueueStore,
    private val inboundProcessor: RelayInboundProcessor,
    private val stateReadiness: RelayStateReadiness
) {
    suspend fun reconcile(deviceId: String, conversationId: String) {
        require(deviceId.length in 20..80)
        require(conversationId.isNotBlank() && conversationId.length <= 128)

        // Authenticate and recover the Keystore generation before any fetch, send, or ack call.
        stateReadiness.ensureReady(conversationId)

        val response = gateway.fetchPending(deviceId, conversationId).awaitTask()
        val fetched = parseEnvelopes(response.data, conversationId)
        try {
            for (envelope in fetched) queue.stageInbound(envelope)
        } finally {
            fetched.forEach { it.payload.fill(0) }
        }

        // Process persisted work even if the server already removed it after a prior successful ack.
        val inbound = queue.pendingInbound(conversationId)
        try {
            for (envelope in inbound.filter { it.state == RelayQueueState.STAGED }) {
                inboundProcessor.processAndPersist(envelope)
            }
            val committedIds = inbound.map { it.envelopeId }
            if (committedIds.isNotEmpty()) {
                gateway.acknowledge(deviceId, conversationId, committedIds).awaitTask()
                for (envelopeId in committedIds) {
                    queue.removeInboundAfterAcknowledgement(envelopeId)
                }
            }
        } finally {
            inbound.forEach { it.payload.fill(0) }
        }

        for (envelope in queue.pendingOutbound().filter { it.conversationId == conversationId }) {
            val payload = envelope.payloadCopy()
            try {
                gateway.sendEnvelope(
                    deviceId = deviceId,
                    conversationId = conversationId,
                    envelopeId = envelope.envelopeId,
                    encryptedEnvelope = payload
                ).awaitTask()
                queue.removeOutboundAfterAcceptance(envelope.envelopeId)
            } finally {
                payload.fill(0)
                envelope.payload.fill(0)
            }
        }
    }

    private fun parseEnvelopes(data: Any?, conversationId: String): List<StoredRelayEnvelope> {
        val root = data as? Map<*, *> ?: error("Relay response was not an object.")
        val values = root["envelopes"] as? List<*> ?: error("Relay response did not include envelopes.")
        return values.map { value ->
            val item = value as? Map<*, *> ?: error("Relay envelope was not an object.")
            val id = item["envelopeId"] as? String ?: error("Relay envelope ID was missing.")
            require(id.length in 1..128 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' })
            val encoded = item["payloadBase64"] as? String ?: error("Relay envelope payload was missing.")
            val payload = Base64.decode(encoded, Base64.DEFAULT)
            require(payload.isNotEmpty() && payload.size <= WorkerRelayGateway.MAX_ENVELOPE_BYTES)
            require(Base64.encodeToString(payload, Base64.NO_WRAP) == encoded) { "Relay envelope encoding was not canonical." }
            val createdAt = (item["createdAtMillis"] as? Number)?.toLong() ?: error("Relay creation time was missing.")
            val expiresAt = (item["expiresAtMillis"] as? Number)?.toLong() ?: error("Relay expiry was missing.")
            require(expiresAt > createdAt)
            StoredRelayEnvelope(
                envelopeId = id,
                conversationId = conversationId,
                payload = payload,
                createdAtMillis = createdAt,
                expiresAtMillis = expiresAt
            )
        }
    }
}

/** Must authenticate local MLS state and finish pending key retirement before network access. */
fun interface RelayStateReadiness {
    suspend fun ensureReady(conversationId: String)
}

/** Implemented by the selected MLS provider and the idempotent local message handoff. */
fun interface RelayInboundProcessor {
    /** Throws unless authenticated MLS state, the local message, and committed queue state share a durable transaction. */
    suspend fun processAndPersist(envelope: StoredRelayEnvelope)
}

private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (!continuation.isActive) return@addOnCompleteListener
        if (task.isSuccessful) {
            continuation.resume(task.result)
        } else {
            continuation.resumeWithException(task.exception ?: IllegalStateException("Firebase operation failed."))
        }
    }
}
