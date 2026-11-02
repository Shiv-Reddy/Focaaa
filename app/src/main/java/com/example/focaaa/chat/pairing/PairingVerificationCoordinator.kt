package com.example.focaaa.chat.pairing

import com.example.focaaa.chat.crypto.CryptoGroupHandle
import com.example.focaaa.chat.crypto.CryptoPeerIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

/**
 * Serializes pairing confirmation changes with their encrypted local persistence.
 * Callers must supply [confirmPeerCode] only after the crypto provider authenticated the sender.
 */
class PairingVerificationCoordinator private constructor(
    private val group: CryptoGroupHandle,
    private val store: PairingVerificationStore,
    private val gate: PairingVerificationGate,
    @Volatile private var persisted: Boolean
) {
    private val mutex = Mutex()
    /** Sending is allowed only when the trusted gate state was durably written. */
    val canSendApplicationMessages: Boolean
        get() = persisted && gate.canSendApplicationMessages

    suspend fun confirmLocalCode(enteredCode: String): Boolean = serialized {
        persistTransition { gate.confirmLocalCode(enteredCode) }
    }

    suspend fun confirmPeerCode(
        authenticatedSender: CryptoPeerIdentity,
        confirmedCode: String
    ): Boolean = serialized {
        persistTransition { gate.confirmPeerCode(authenticatedSender, confirmedCode) }
    }

    suspend fun revoke() {
        mutex.lock()
        try {
            persisted = false
            gate.revoke()
            persisted = persistSnapshot()
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun serialized(block: suspend () -> Boolean): Boolean {
        mutex.lock()
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun persistTransition(transition: () -> Boolean): Boolean {
        // Close sending before changing the in-memory gate. If storage fails, it stays closed.
        val wasPersisted = persisted
        persisted = false
        if (!transition()) {
            persisted = wasPersisted
            return false
        }
        persisted = persistSnapshot()
        // A confirmation can be saved before the other device confirms. Report whether this
        // transition was saved; the separate send gate still requires both confirmations.
        return persisted
    }

    private suspend fun persistSnapshot(): Boolean = try {
        store.write(group, gate.snapshot())
        true
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        false
    }

    companion object {
        /**
         * Restore only state bound to this group peer and code. A mismatched or unreadable record
         * starts with a closed gate; confirmation cannot enable sending unless its snapshot saves.
         */
        suspend fun open(
            group: CryptoGroupHandle,
            expectedPeer: CryptoPeerIdentity,
            expectedCode: PairingVerificationCode,
            store: PairingVerificationStore
        ): PairingVerificationCoordinator {
            val candidate = try {
                store.read(group)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
            val gate = candidate
                ?.takeIf {
                    it.expectedCode == expectedCode &&
                        it.expectedPeerIdentity.contentEquals(expectedPeer.encoded)
                }
                ?.let { runCatching { PairingVerificationGate(expectedPeer, expectedCode, it) }.getOrNull() }
                ?: PairingVerificationGate(expectedPeer, expectedCode)

            val compatibleSnapshot = candidate != null && gate.snapshot().let { current ->
                current.localConfirmed == candidate.localConfirmed &&
                    current.peerConfirmed == candidate.peerConfirmed &&
                    current.revoked == candidate.revoked &&
                    current.expectedCode == candidate.expectedCode &&
                    current.expectedPeerIdentity.contentEquals(candidate.expectedPeerIdentity)
            }

            val coordinator = PairingVerificationCoordinator(
                group = group,
                store = store,
                gate = gate,
                persisted = compatibleSnapshot
            )
            if (!compatibleSnapshot) coordinator.persisted = coordinator.persistSnapshot()
            return coordinator
        }
    }
}
