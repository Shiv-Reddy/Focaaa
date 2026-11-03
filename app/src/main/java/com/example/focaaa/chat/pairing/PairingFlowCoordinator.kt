package com.example.focaaa.chat.pairing

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.example.focaaa.chat.crypto.CryptoGroupHandle
import com.example.focaaa.chat.crypto.CryptoIdentityHandle
import com.example.focaaa.chat.crypto.CryptoKeyPackage
import com.example.focaaa.chat.crypto.CryptoWelcome
import com.example.focaaa.chat.crypto.OpenMlsCryptoProvider
import com.example.focaaa.chat.data.RoomConversationRepository
import com.example.focaaa.chat.VisibleChatTracker
import com.example.focaaa.chat.media.EncryptedMediaStore
import com.example.focaaa.chat.media.MediaDescriptor
import com.example.focaaa.chat.media.MediaKind
import com.example.focaaa.chat.media.MediaMessageCodec
import com.example.focaaa.chat.relay.WorkerRelayGateway
import com.example.focaaa.chat.relay.RelayResult
import com.example.focaaa.chat.relay.OpenMlsRelayInboundProcessor
import com.example.focaaa.chat.relay.RelayDeviceRegistration
import com.example.focaaa.chat.relay.RelayGateway
import com.example.focaaa.chat.relay.RelaySyncCoordinator
import com.example.focaaa.chat.relay.PairEndedException
import com.example.focaaa.chat.relay.RelayStateReadiness
import com.example.focaaa.chat.relay.RelayServiceGeneration
import com.google.android.gms.tasks.Task
import java.security.SecureRandom
import java.security.MessageDigest
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class PendingPairingRemoteRevocationFailed(cause: Throwable) : IllegalStateException(
    "The relay could not cancel this pending pairing. Review the local removal option below.", cause
)

/** Coordinates consent-based invite, MLS Welcome, comparison, and relay activation. */
class PairingFlowCoordinator(
    context: Context,
    private val repository: RoomConversationRepository,
    private val gateway: RelayGateway = WorkerRelayGateway(),
    private var sessionId: String = CURRENT_PAIRING_SESSION_ID
) {
    private val appContext = context.applicationContext
    private val sessionStore = repository.pairingSessionStore
    private val mediaStore = EncryptedMediaStore(appContext)
    private val cryptoProvider by lazy {
        OpenMlsCryptoProvider(
            repository.encryptedCryptoStateStore,
            repository.pairingVerificationStore
        )
    }
    private val mutex = serialPairingOperations

    suspend fun currentSession(): PairingSession? = readSession()

    suspend fun createInvite(): PairingSession = mutex.withLock {
        check(sessionStore.read(CURRENT_PAIRING_SESSION_ID) == null) { "Finish or clear the current pairing before creating another invite." }
        requirePartnerSlot()
        ensureRegistered()
        val identity = cryptoProvider.createIdentity()
        val session = PairingSession(
            role = PairingRole.INVITER,
            stage = PairingStage.IDENTITY_READY,
            identityHandle = identity.value,
            inviteToken = generateInviteToken()
        )
        writeSession(session)
        advanceLocked(session)
    }

    suspend fun joinInvite(token: String, liveDraftConsent: Boolean): PairingSession = mutex.withLock {
        require(liveDraftConsent) { "Choose live draft sharing before joining this invite." }
        require(token.length == 32 && token.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "Enter the invite code exactly as the other person shared it."
        }
        check(sessionStore.read(CURRENT_PAIRING_SESSION_ID) == null) { "Finish or clear the current pairing before joining another invite." }
        requirePartnerSlot()
        ensureRegistered()
        val identity = cryptoProvider.createIdentity()
        val session = PairingSession(
            role = PairingRole.JOINER,
            stage = PairingStage.IDENTITY_READY,
            identityHandle = identity.value,
            inviteToken = token,
            liveDraftConsent = true
        )
        writeSession(session)
        advanceLocked(session)
    }

    /** Existing pairs can explicitly enable sharing; the joiner can withdraw it at any time. */
    suspend fun setLiveDraftConsent(conversationId: String, enabled: Boolean): PairingSession = mutex.withLock {
        val session = sessionStore.read(conversationId)
            ?: error("This private connection is unavailable.")
        check(session.role == PairingRole.JOINER && session.stage == PairingStage.ACTIVE) {
            "Only the invite participant can choose live draft sharing."
        }
        gateway.setLiveDraftConsent(RelayDeviceRegistration.deviceId(appContext), conversationId, enabled).awaitTask()
        val updated = session.copy(liveDraftConsent = enabled)
        sessionStore.write(conversationId, updated)
        updated
    }

    /** Poll for the counterpart's next setup action, or retry an interrupted local handoff. */
    suspend fun advance(): PairingSession? = mutex.withLock {
        val session = readSession() ?: return@withLock null
        if (session.stage == PairingStage.IDENTITY_READY) ensureRegistered()
        advanceLocked(session)
    }

    suspend fun confirmCode(enteredCode: String): PairingSession = mutex.withLock {
        var session = requireCurrentSession()
        check(session.stage == PairingStage.GROUP_READY || session.stage == PairingStage.VERIFIED_LOCAL || session.stage == PairingStage.ACTIVE) {
            "The MLS pairing has not finished yet."
        }
        val conversationId = requireNotNull(session.conversationId)
        val group = CryptoGroupHandle(conversationId)
        val code = PairingVerificationCode(requireNotNull(session.verificationCode))
        val peer = cryptoProvider.peerIdentity(group)
        val confirmation = PairingVerificationCoordinator.open(
            group = group,
            expectedPeer = peer,
            expectedCode = code,
            store = repository.pairingVerificationStore
        )
        check(confirmation.confirmLocalCode(enteredCode)) { "The code does not match. Compare both screens and try again." }
        session = session.copy(stage = PairingStage.VERIFIED_LOCAL)
        writeSession(session)
        session = activateRelayIfReady(session)
        if (session.relayActive) sync(conversationId)
        session
    }

    /** Reconcile queues and refresh the mutually-confirmed state after the user taps Check again. */
    suspend fun refresh(): PairingSession? = mutex.withLock {
        var session = readSession() ?: return@withLock null
        if (session.stage == PairingStage.IDENTITY_READY) ensureRegistered()
        session = advanceLocked(session)
        if (session.stage == PairingStage.VERIFIED_LOCAL || session.stage == PairingStage.ACTIVE) {
            session = activateRelayIfReady(session)
            if (session.relayActive) {
                sync(session.conversationId!!)
                val verification = repository.pairingVerificationStore.read(CryptoGroupHandle(session.conversationId))
                if (verification?.localConfirmed == true && verification.peerConfirmed && !verification.revoked) {
                    session = session.copy(stage = PairingStage.ACTIVE)
                    writeSession(session)
                }
            }
        }
        session
    }

    /** Returns true after a durable local queue commit, not after relay acceptance. */
    suspend fun sendMessage(text: String): Boolean {
        var queuedConversationId: String? = null
        queuedChatDelivery.enqueue(
            save = {
                mutex.withLock {
                    val session = requireCurrentSession()
                    val conversationId = requireNotNull(session.conversationId)
                    check(RelayServiceGeneration.isCurrent(appContext, conversationId)) {
                        "This saved chat used the local test service. Create a new invite to reconnect; its history stays on this phone."
                    }
                    check(session.stage == PairingStage.ACTIVE && session.relayActive) {
                        "Both partners must finish pairing verification before sending messages."
                    }
                    val clean = text.trim()
                    require(clean.isNotEmpty()) { "Enter a message first." }
                    require(!clean.startsWith(MediaMessageCodec.PREFIX) ||
                        runCatching { MediaMessageCodec.decode(clean) }.getOrNull() != null) {
                        "Invalid private media message."
                    }
                    val bytes = clean.toByteArray(Charsets.UTF_8)
                    try {
                        cryptoProvider.encrypt(CryptoGroupHandle(conversationId), bytes)
                        queuedConversationId = conversationId
                    } finally {
                        bytes.fill(0)
                    }
                }
            },
            deliver = { syncActiveConversation(requireNotNull(queuedConversationId)) }
        )
        return true
    }

    /** Upload authenticated ciphertext first; only a completed upload gets an MLS message. */
    suspend fun sendMedia(uri: Uri, kind: MediaKind): Boolean {
        val conversationId = mutex.withLock {
            val session = requireCurrentSession()
            val id = requireNotNull(session.conversationId)
            check(session.stage == PairingStage.ACTIVE && session.relayActive &&
                RelayServiceGeneration.isCurrent(appContext, id)) { "Open an active private conversation first." }
            id
        }
        val prepared = mediaStore.prepare(uri, kind, conversationId)
        val descriptor = prepared.descriptor
        val deviceId = RelayDeviceRegistration.deviceId(appContext)
        var completed = false
        try {
            val begun = gateway.beginMediaUpload(deviceId, conversationId, descriptor.mediaId,
                descriptor.ciphertextSize, descriptor.ciphertextSha256).awaitTask().data.objectData()
            check((begun["chunkCount"] as? Number)?.toInt() == descriptor.chunkCount) {
                "The media relay returned an invalid chunk plan."
            }
            for (index in 0 until descriptor.chunkCount) {
                val start = index * MediaMessageCodec.CHUNK_BYTES
                val end = minOf(start + MediaMessageCodec.CHUNK_BYTES, prepared.ciphertext.size)
                val chunk = prepared.ciphertext.copyOfRange(start, end)
                try {
                    gateway.uploadMediaChunk(deviceId, conversationId, descriptor.mediaId,
                        index, chunk, sha256(chunk)).awaitTask()
                } finally { chunk.fill(0) }
            }
            gateway.completeMediaUpload(deviceId, conversationId, descriptor.mediaId).awaitTask()
            completed = true
            return sendMessage(MediaMessageCodec.encode(descriptor))
        } catch (error: Exception) {
            if (!completed) {
                runCatching { gateway.cancelMediaUpload(deviceId, conversationId, descriptor.mediaId).awaitTask() }
                mediaStore.delete(conversationId, descriptor.mediaId)
            }
            throw error
        } finally { prepared.ciphertext.fill(0) }
    }

    /** Keep encrypted downloads on the device; verify both digest and GCM before showing media. */
    suspend fun openMedia(conversationId: String, descriptor: MediaDescriptor): ByteArray =
        serialMediaOperations.withLock { openMediaUnlocked(conversationId, descriptor) }

    private suspend fun openMediaUnlocked(conversationId: String, descriptor: MediaDescriptor): ByteArray {
        mediaStore.readCiphertext(conversationId, descriptor)?.let { ciphertext ->
            try { return mediaStore.decrypt(descriptor, ciphertext) }
            finally { ciphertext.fill(0) }
        }
        val session = requireCurrentSession()
        check(session.stage == PairingStage.ACTIVE && session.relayActive &&
            session.conversationId == conversationId &&
            RelayServiceGeneration.isCurrent(appContext, conversationId)) {
            "This media is no longer on this phone or available from the relay."
        }
        val deviceId = RelayDeviceRegistration.deviceId(appContext)
        val manifest = gateway.fetchMediaManifest(deviceId, conversationId, descriptor.mediaId)
            .awaitTask().data.objectData()
        check((manifest["ciphertextSize"] as? Number)?.toInt() == descriptor.ciphertextSize &&
            manifest["ciphertextSha256"] == descriptor.ciphertextSha256 &&
            (manifest["chunkCount"] as? Number)?.toInt() == descriptor.chunkCount) {
            "The media relay returned different ciphertext metadata."
        }
        val output = ByteArrayOutputStream(descriptor.ciphertextSize)
        for (index in 0 until descriptor.chunkCount) {
            val item = gateway.fetchMediaChunk(deviceId, conversationId, descriptor.mediaId, index)
                .awaitTask().data.objectData()
            check((item["chunkIndex"] as? Number)?.toInt() == index) { "Media chunk order is invalid." }
            val chunk = Base64.decode(item.string("payloadBase64"), Base64.DEFAULT)
            try {
                check(chunk.size in 1..MediaMessageCodec.CHUNK_BYTES &&
                    sha256(chunk) == item.string("payloadSha256") &&
                    output.size() + chunk.size <= descriptor.ciphertextSize) { "Media chunk is damaged." }
                output.write(chunk)
            } finally { chunk.fill(0) }
        }
        val ciphertext = output.toByteArray()
        try {
            val plaintext = mediaStore.decrypt(descriptor, ciphertext)
            mediaStore.saveCiphertext(conversationId, descriptor, ciphertext)
            return plaintext
        } finally { ciphertext.fill(0) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    /** Reconcile an open, verified conversation without repeating relay membership confirmation. */
    suspend fun syncActiveConversation(conversationId: String) = boundedChatSync(CHAT_SYNC_TIMEOUT_MILLIS) { mutex.withLock {
        val session = requireCurrentSession()
        check(RelayServiceGeneration.isCurrent(appContext, conversationId)) {
            "This saved chat used the local test service. Create a new invite to reconnect."
        }
        check(session.stage == PairingStage.ACTIVE && session.relayActive && session.conversationId == conversationId) {
            "This private conversation is not active."
        }
        sync(conversationId)
    } }

    /** Called only while the full conversation is resumed, after durable inbound reconciliation. */
    suspend fun reportVisibleReads(conversationId: String) = boundedChatSync(CHAT_SYNC_TIMEOUT_MILLIS) { mutex.withLock {
        if (VisibleChatTracker.shouldAlert(conversationId)) return@withLock
        requireActiveContact(conversationId)
        val ids = repository.unreportedInboundIds(conversationId)
        if (ids.isEmpty()) return@withLock
        val result = gateway.markRead(RelayDeviceRegistration.deviceId(appContext), conversationId, ids)
            .awaitTask().data.objectData()
        val confirmed = result["readEnvelopeIds"] as? List<*>
            ?: error("The relay returned an invalid read response.")
        for (value in confirmed) {
            val id = value as? String ?: error("The relay returned an invalid read ID.")
            require(id in ids) { "The relay returned a read ID that was not requested." }
            repository.markInboundReadReported(conversationId, id)
        }
    } }

    /** Revokes one active relay membership before removing this phone's local conversation state. */
    suspend fun unpair(conversationId: String) = mutex.withLock {
        requireActiveContact(conversationId)
        gateway.revokePairing(RelayDeviceRegistration.deviceId(appContext), conversationId).awaitTask()
        removeLocalContact(conversationId)
    }

    /** User-confirmed recovery if remote revocation fails; never presented as a successful unpair. */
    suspend fun removeLocalPairing(conversationId: String) = mutex.withLock {
        requireActiveContact(conversationId)
        removeLocalContact(conversationId)
    }

    private suspend fun requireActiveContact(conversationId: String) {
        require(conversationId.isNotBlank() && conversationId.length <= 128)
        val session = sessionStore.read(conversationId)
            ?: error("This contact is no longer paired on this phone.")
        check(session.stage == PairingStage.ACTIVE && session.relayActive && session.conversationId == conversationId) {
            "Only an active contact can be unpaired here."
        }
    }

    private suspend fun requirePartnerSlot() {
        check(!hasActivePartner(repository.observeActiveContacts().first())) {
            "This release supports one active partner. Unpair the current partner before adding another."
        }
    }

    private suspend fun removeLocalContact(conversationId: String) {
        repository.encryptedCryptoStateStore.delete(conversationId)
        repository.deletePrivateConversation(conversationId)
        mediaStore.deleteConversation(conversationId)
        RelayServiceGeneration.forget(appContext, conversationId)
    }

    /** Revokes an unfinished relay pair before clearing its local state. An unused invite expires on its own. */
    suspend fun clearPendingPairing() = mutex.withLock {
        val session = readSession() ?: return@withLock
        check(session.stage != PairingStage.ACTIVE) {
            "An active conversation must be unlinked through its own flow."
        }
        session.conversationId?.let { conversationId ->
            try {
                gateway.revokePairing(
                    RelayDeviceRegistration.deviceId(appContext), conversationId
                ).awaitTask()
            } catch (error: Exception) {
                throw PendingPairingRemoteRevocationFailed(error)
            }
        }
        clearPendingLocalState(session)
    }

    /** Used only after a failed remote revocation and an explicit local-only warning in the UI. */
    suspend fun clearPendingPairingLocally() = mutex.withLock {
        val session = readSession() ?: return@withLock
        check(session.stage != PairingStage.ACTIVE) {
            "An active conversation must be unlinked through its own flow."
        }
        clearPendingLocalState(session)
    }

    private suspend fun clearPendingLocalState(session: PairingSession) {
        if (session.identityHandle != "retired") {
            cryptoProvider.discardIdentity(CryptoIdentityHandle(session.identityHandle))
        }
        session.conversationId?.let { conversationId ->
            repository.pairingVerificationStore.delete(CryptoGroupHandle(conversationId))
            repository.encryptedCryptoStateStore.delete(conversationId)
        }
        sessionStore.clear(sessionId)
    }

    private suspend fun advanceLocked(initial: PairingSession): PairingSession {
        var session = initial
        check(session.expiresAtMillis == 0L || session.expiresAtMillis > System.currentTimeMillis() || session.stage >= PairingStage.GROUP_READY) {
            "This invite expired. Clear it and create or enter a fresh invite."
        }

        if (session.role == PairingRole.INVITER) {
            if (session.stage == PairingStage.IDENTITY_READY) {
                val token = requireNotNull(session.inviteToken)
                val result = gateway.createPairingInvite(
                    RelayDeviceRegistration.deviceId(appContext), token
                ).awaitTask().data.objectData()
                val expiresAt = result.long("expiresAtMillis")
                session = session.copy(stage = PairingStage.INVITE_READY, expiresAtMillis = expiresAt)
                writeSession(session)
                return session
            }
            if (session.stage == PairingStage.INVITE_READY) {
                val response = gateway.fetchPairingRequest(
                    RelayDeviceRegistration.deviceId(appContext),
                    requireNotNull(session.inviteToken)
                ).awaitTask().data.objectData()
                if (response["status"] == "waiting") return session
                val conversationId = response.string("conversationId")
                val keyPackage = response.bytes("joinerKeyPackageBase64", WorkerRelayGateway.MAX_KEY_PACKAGE_BYTES)
                val invitation = cryptoProvider.createGroupAndWelcome(
                    CryptoIdentityHandle(session.identityHandle),
                    CryptoKeyPackage(keyPackage, session.expiresAtMillis),
                    conversationId
                )
                val fingerprint = cryptoProvider.verificationFingerprint(invitation.group)
                val peer = cryptoProvider.peerIdentity(invitation.group)
                repository.pairingVerificationStore.write(
                    invitation.group,
                    PairingVerificationSnapshot(peer.encoded, PairingVerificationCode(fingerprint.displayCode), false, false, false)
                )
                session = session.copy(
                    stage = PairingStage.GROUP_READY,
                    identityHandle = "retired",
                    inviteToken = null,
                    conversationId = conversationId,
                    welcome = invitation.welcome.encoded.copyOf(),
                    ratchetTree = invitation.welcome.groupContext.copyOf(),
                    verificationCode = fingerprint.displayCode
                )
                writeSession(session)
                cryptoProvider.discardIdentity(CryptoIdentityHandle(initial.identityHandle))
                session = session.copy(identityHandle = "retired")
                writeSession(session)
                session = publishPendingWelcome(session)
            }
        } else {
            if (session.stage == PairingStage.IDENTITY_READY) {
                val keyPackage = session.keyPackage?.copyOf() ?: cryptoProvider.createKeyPackage(
                    CryptoIdentityHandle(session.identityHandle)
                ).encoded
                session = session.copy(keyPackage = keyPackage)
                writeSession(session)
                val response = gateway.joinPairingInvite(
                    RelayDeviceRegistration.deviceId(appContext),
                    requireNotNull(session.inviteToken),
                    keyPackage,
                    session.liveDraftConsent
                ).awaitTask().data.objectData()
                session = session.copy(
                    stage = PairingStage.JOIN_REQUESTED,
                    inviteToken = null,
                    keyPackage = null,
                    conversationId = response.string("conversationId"),
                    expiresAtMillis = System.currentTimeMillis() + INVITE_TTL_MILLIS
                )
                writeSession(session)
                return session
            }
            if (session.stage == PairingStage.JOIN_REQUESTED) {
                val conversationId = requireNotNull(session.conversationId)
                val response = gateway.fetchPairingWelcome(
                    RelayDeviceRegistration.deviceId(appContext), conversationId
                ).awaitTask().data.objectData()
                val welcomeBytes = response.bytes("welcomeBase64", WorkerRelayGateway.MAX_WELCOME_BYTES)
                val tree = response.bytes("ratchetTreeBase64", WorkerRelayGateway.MAX_RATCHET_TREE_BYTES)
                val group = cryptoProvider.joinGroup(
                    CryptoIdentityHandle(session.identityHandle),
                    CryptoWelcome(welcomeBytes, tree, ByteArray(0)),
                    conversationId
                )
                val fingerprint = cryptoProvider.verificationFingerprint(group)
                val peer = cryptoProvider.peerIdentity(group)
                repository.pairingVerificationStore.write(
                    group,
                    PairingVerificationSnapshot(peer.encoded, PairingVerificationCode(fingerprint.displayCode), false, false, false)
                )
                session = session.copy(
                    stage = PairingStage.GROUP_READY,
                    identityHandle = "retired",
                    welcome = null,
                    ratchetTree = null,
                    verificationCode = fingerprint.displayCode
                )
                writeSession(session)
                cryptoProvider.discardIdentity(CryptoIdentityHandle(initial.identityHandle))
                session = session.copy(identityHandle = "retired")
                writeSession(session)
            }
        }
        if (session.role == PairingRole.INVITER && session.stage == PairingStage.GROUP_READY) {
            if (session.identityHandle != "retired") {
                cryptoProvider.discardIdentity(CryptoIdentityHandle(session.identityHandle))
                session = session.copy(identityHandle = "retired")
                writeSession(session)
            }
            session = publishPendingWelcome(session)
        } else if (session.role == PairingRole.JOINER && session.stage == PairingStage.GROUP_READY && session.identityHandle != "retired") {
            cryptoProvider.discardIdentity(CryptoIdentityHandle(session.identityHandle))
            session = session.copy(identityHandle = "retired")
            writeSession(session)
        }
        return session
    }

    private suspend fun publishPendingWelcome(session: PairingSession): PairingSession {
        val welcome = session.welcome ?: return session
        val tree = session.ratchetTree ?: error("The MLS Welcome is missing its ratchet tree.")
        gateway.publishPairingWelcome(
            RelayDeviceRegistration.deviceId(appContext),
            requireNotNull(session.conversationId),
            welcome,
            tree
        ).awaitTask()
        val updated = session.copy(welcome = null, ratchetTree = null)
        writeSession(updated)
        return updated
    }

    private suspend fun activateRelayIfReady(initial: PairingSession): PairingSession {
        var session = initial
        val conversationId = requireNotNull(session.conversationId)
        val result = gateway.confirmPairing(
            RelayDeviceRegistration.deviceId(appContext), conversationId
        ).awaitTask().data.objectData()
        val relayActive = result.boolean("active")
        if (relayActive) RelayServiceGeneration.markCurrent(appContext, conversationId)
        if (relayActive != session.relayActive) {
            session = session.copy(relayActive = relayActive)
            writeSession(session)
        }
        if (relayActive && !session.localControlQueued) {
            cryptoProvider.sendPairingConfirmation(
                CryptoGroupHandle(conversationId),
                PairingVerificationCode(requireNotNull(session.verificationCode))
            )
            session = session.copy(localControlQueued = true)
            writeSession(session)
        }
        return session
    }

    private suspend fun sync(conversationId: String) {
        val provider = cryptoProvider
        RelaySyncCoordinator(
            gateway = gateway,
            queue = repository.relayQueueStore,
            inboundProcessor = OpenMlsRelayInboundProcessor(provider),
            stateReadiness = RelayStateReadiness { id -> provider.ensureReady(CryptoGroupHandle(id)) }
        ).reconcile(RelayDeviceRegistration.deviceId(appContext), conversationId)
        // A status refresh is optional; an accepted ciphertext must never be reported as unsent
        // just because the receipt query is temporarily unavailable.
        try {
            val ids = repository.outgoingStatusCandidateIds(conversationId)
            if (ids.isNotEmpty()) {
                val result = gateway.fetchMessageStatuses(
                    RelayDeviceRegistration.deviceId(appContext), conversationId, ids
                ).awaitTask().data.objectData()
                val statuses = result["statuses"] as? List<*>
                    ?: error("The relay returned an invalid message-status response.")
                for (value in statuses) {
                    val item = value as? Map<*, *> ?: error("The relay returned an invalid message status.")
                    val id = item["envelopeId"] as? String ?: error("The relay omitted a message-status ID.")
                    val level = (item["level"] as? Number)?.toInt()
                        ?: error("The relay omitted a message-status level.")
                    require(id in ids && level in 1..3)
                    repository.advanceOutgoingStatus(conversationId, id, level)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (ended: PairEndedException) {
            throw ended
        } catch (_: Exception) {
            // Delivery remains retryable; the next sync will refresh these optional receipts.
        }
    }

    private suspend fun ensureRegistered() {
        RelayDeviceRegistration.register(appContext, gateway).awaitTask()
    }

    private fun generateInviteToken(): String {
        val random = ByteArray(24)
        SecureRandom().nextBytes(random)
        return try {
            Base64.encodeToString(random, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        } finally {
            random.fill(0)
        }
    }

    private suspend fun readSession(): PairingSession? = sessionStore.read(sessionId)

    private suspend fun writeSession(session: PairingSession) {
        sessionStore.write(session)
        if (session.stage == PairingStage.ACTIVE && session.conversationId != null) {
            sessionId = session.conversationId
        }
    }

    private suspend fun requireCurrentSession(): PairingSession =
        readSession() ?: error("There is no active pairing to continue.")

    private fun Any?.objectData(): Map<*, *> = this as? Map<*, *> ?: error("Firebase returned an invalid pairing response.")

    private fun Map<*, *>.string(key: String): String =
        this[key] as? String ?: error("Firebase pairing response is missing $key.")

    private fun Map<*, *>.long(key: String): Long =
        (this[key] as? Number)?.toLong() ?: error("Firebase pairing response is missing $key.")

    private fun Map<*, *>.boolean(key: String): Boolean =
        this[key] as? Boolean ?: error("Firebase pairing response is missing $key.")

    private fun Map<*, *>.bytes(key: String, maxBytes: Int): ByteArray {
        val encoded = string(key)
        val decoded = Base64.decode(encoded, Base64.DEFAULT)
        require(decoded.isNotEmpty() && decoded.size <= maxBytes)
        require(Base64.encodeToString(decoded, Base64.NO_WRAP) == encoded) {
            "Firebase returned malformed MLS setup bytes."
        }
        return decoded
    }

    private suspend fun Task<RelayResult>.awaitTask(): RelayResult = suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task ->
            if (!continuation.isActive) return@addOnCompleteListener
            if (task.isSuccessful) continuation.resume(task.result)
            else continuation.resumeWithException(task.exception ?: IllegalStateException("Firebase operation failed."))
        }
    }

    private companion object {
        val serialPairingOperations = Mutex()
        val serialMediaOperations = Mutex()
        val deliveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val queuedChatDelivery = QueuedChatDelivery(deliveryScope)
        const val CHAT_SYNC_TIMEOUT_MILLIS = 15_000L
        const val INVITE_TTL_MILLIS = 5 * 60 * 1000L
    }
}
