package com.example.focaaa.chat.relay

import com.example.focaaa.chat.crypto.CryptoGroupHandle
import com.example.focaaa.chat.crypto.CryptoEnvelope
import com.example.focaaa.chat.crypto.CryptoInboundResult
import com.example.focaaa.chat.crypto.OpenMlsCryptoProvider

/**
 * Adapts a staged ciphertext envelope to the MLS provider. The provider persists the accepted MLS
 * snapshot and inbound message/ack state in one Room transaction before this method returns.
 * RelaySyncCoordinator may acknowledge the server copy only after this succeeds.
 */
class OpenMlsRelayInboundProcessor(
    private val cryptoProvider: OpenMlsCryptoProvider
) : RelayInboundProcessor {
    override suspend fun processAndPersist(envelope: StoredRelayEnvelope) {
        require(envelope.isValidForProcessing())
        val wire = envelope.payloadCopy()
        try {
            val group = CryptoGroupHandle(envelope.conversationId)
            cryptoProvider.ensureReady(group)
            val inbound = cryptoProvider.processIncoming(
                group,
                CryptoEnvelope(
                    encoded = wire,
                    epoch = 0L,
                    relayEnvelopeId = envelope.envelopeId,
                    createdAtMillis = envelope.createdAtMillis
                )
            )
            when (inbound) {
                is CryptoInboundResult.ApplicationMessage -> inbound.message.plaintext.fill(0)
                is CryptoInboundResult.GroupStateChanged -> Unit
            }
        } finally {
            wire.fill(0)
        }
    }

    private fun StoredRelayEnvelope.isValidForProcessing(): Boolean =
        envelopeId.isNotBlank() &&
            conversationId.isNotBlank() &&
            payload.isNotEmpty() &&
            createdAtMillis > 0L &&
            expiresAtMillis > createdAtMillis
}
