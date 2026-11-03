package com.example.focaaa.chat.live

import android.content.Context
import android.util.Base64
import com.example.focaaa.R
import com.example.focaaa.chat.crypto.CryptoGroupHandle
import com.example.focaaa.chat.crypto.OpenMlsCryptoProvider
import com.example.focaaa.chat.data.LiveDraftEditEntity
import com.example.focaaa.chat.data.RoomConversationRepository
import com.example.focaaa.chat.relay.RelayDeviceRegistration
import com.example.focaaa.chat.relay.RelayEndpoint
import com.google.android.gms.tasks.Tasks
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.auth.FirebaseAuth
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

data class PartnerLiveDraft(val text: String = "", val sharing: Boolean = false, val online: Boolean = false)

/** Active only while the unlocked private chat is resumed. Ordinary chat keeps working if this fails. */
class LiveDraftCoordinator(
    context: Context,
    private val repository: RoomConversationRepository,
    private val conversationId: String
) {
    private val appContext = context.applicationContext
    private val provider = OpenMlsCryptoProvider(repository.encryptedCryptoStateStore, repository.pairingVerificationStore)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val engine = LiveDraftEngine()
    private val consentLock = Any()
    private var remoteSession: RemoteDraftSession? = null
    @Volatile private var key: ByteArray? = null
    private var epoch: Long = -1
    @Volatile private var socket: WebSocket? = null
    @Volatile private var active = false
    @Volatile private var generation = 0
    private var retryJob: Job? = null
    private var retentionJob: Job? = null
    private var retryDelayMillis = 2_000L
    private val _sharing = MutableStateFlow(false)
    val sharing: StateFlow<Boolean> = _sharing
    private val _available = MutableStateFlow(false)
    val available: StateFlow<Boolean> = _available
    private val _partner = MutableStateFlow(PartnerLiveDraft())
    val partner: StateFlow<PartnerLiveDraft> = _partner

    init { scope.launch {
        for (command in commands) {
            try { process(command) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { pauseSharing() }
        }
    } }

    suspend fun start() = withContext(Dispatchers.IO) {
        if (active) return@withContext
        active = true
        try {
            repository.purgeExpiredLiveDraftEdits()
            val exported = provider.exportLiveDraftKey(CryptoGroupHandle(conversationId))
            if (!active) { exported.first.fill(0); return@withContext }
            key = exported.first
            epoch = exported.second
            _available.value = true
            retentionJob?.cancel()
            retentionJob = scope.launch {
                while (active) {
                    delay(60 * 60_000L)
                    if (active) repository.purgeExpiredLiveDraftEdits()
                }
            }
            connect()
        } catch (cancelled: CancellationException) {
            stop()
            throw cancelled
        } catch (_: Exception) {
            _available.value = false
            _sharing.value = false
        }
    }

    fun startSharing(currentComposerText: String) {
        if (!active || !_available.value || _sharing.value) return
        synchronized(consentLock) { _sharing.value = true }
        commands.trySend(Command.Start(currentComposerText))
    }

    fun textChanged(after: String) {
        if (active && _sharing.value) commands.trySend(Command.Text(after))
    }

    fun pauseSharing() {
        if (!_sharing.value) return
        synchronized(consentLock) { _sharing.value = false }
        commands.trySend(Command.Pause)
    }

    fun stop() {
        if (!active) return
        synchronized(consentLock) {
            _sharing.value = false
            active = false
        }
        generation++
        retryJob?.cancel()
        retentionJob?.cancel()
        commands.trySend(Command.Pause)
        socket?.close(1000, "Private chat closed")
        socket = null
        key?.fill(0)
        key = null
        epoch = -1
        _available.value = false
        _partner.value = PartnerLiveDraft()
    }

    fun dispose() { stop(); commands.close() }

    private suspend fun connect() = withContext(Dispatchers.IO) {
        if (!active) return@withContext
        try {
            val user = FirebaseAuth.getInstance().currentUser
                ?: error("Private relay sign-in is unavailable.")
            val idToken = Tasks.await(user.getIdToken(false), 20, TimeUnit.SECONDS).token
                ?: error("Private relay sign-in is unavailable.")
            val appCheck = Tasks.await(FirebaseAppCheck.getInstance().getAppCheckToken(false),
                20, TimeUnit.SECONDS).token
            if (!active) return@withContext
            val base = RelayEndpoint.normalize(appContext.getString(R.string.relay_base_url))
            val deviceId = RelayDeviceRegistration.deviceId(appContext)
            val request = Request.Builder()
                .url("$base/v1/liveDraft?deviceId=$deviceId&conversationId=$conversationId")
                .header("Authorization", "Bearer $idToken")
                .header("X-Firebase-AppCheck", appCheck)
                .build()
            val currentGeneration = ++generation
            socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (active && currentGeneration == generation) socket = webSocket
                    else webSocket.close(1000, "Private chat closed")
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (active && currentGeneration == generation) commands.trySend(Command.Inbound(text))
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (active && currentGeneration == generation) commands.trySend(
                        if (response?.code == 410 || response?.code == 412) Command.Denied else Command.Disconnected)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (active && currentGeneration == generation) commands.trySend(Command.Disconnected)
                }
            })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { scheduleReconnect() }
    }

    private fun scheduleReconnect() {
        if (!active || retryJob?.isActive == true) return
        retryJob = scope.launch {
            delay(retryDelayMillis)
            retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(30_000)
            retryJob = null
            if (active) connect()
        }
    }

    private suspend fun process(command: Command) {
        when (command) {
            is Command.Start -> if (active && _sharing.value) beginSession(command.text)
            is Command.Text -> if (active && _sharing.value) recordText(command.text)
            Command.Pause -> {
                val sessionId = engine.sessionId
                if (sessionId != null) {
                    sendControl("stop", sessionId, engine.nextSequence - 1)
                    engine.stop()
                }
            }
            is Command.Inbound -> if (active) receive(command.json)
            Command.Disconnected -> if (active) {
                socket = null
                _partner.value = PartnerLiveDraft()
                remoteSession = null
                scheduleReconnect()
            }
            Command.Denied -> stop()
        }
    }

    private suspend fun beginSession(text: String) {
        val sessionId = UUID.randomUUID().toString().replace("-", "")
        val initial = engine.start(sessionId, text, System.currentTimeMillis())
        sendControl("start", sessionId, 0)
        if (initial != null) saveAndSend(sessionId, initial)
    }

    private suspend fun recordText(after: String) {
        if (engine.nextSequence > MAX_SESSION_EDITS) {
            val previous = engine.sessionId
            if (previous != null) sendControl("stop", previous, engine.nextSequence - 1)
            beginSession(after)
            return
        }
        val edit = engine.textChanged(after, System.currentTimeMillis()) ?: return
        val sessionId = engine.sessionId ?: return
        saveAndSend(sessionId, edit)
    }

    private suspend fun saveAndSend(sessionId: String, edit: DraftEdit) {
        val secret = key ?: return
        try {
            val ciphertext = DraftFrameCrypto.encrypt(secret, conversationId, sessionId, epoch, edit)
            if (!active || !_sharing.value) return
            repository.saveLiveDraftEdit(LiveDraftEditEntity(conversationId, sessionId, "LOCAL",
                edit.sequence, epoch, edit.atMillis, edit.start, edit.removed, edit.inserted, ciphertext))
            sendEdit(sessionId, edit.sequence, epoch, ciphertext)
        } catch (_: Exception) { pauseSharing() }
    }

    private fun sendEdit(sessionId: String, sequence: Long, editEpoch: Long, ciphertext: ByteArray) {
        synchronized(consentLock) {
            if (active && _sharing.value) socket?.send(JSONObject().put("type", "edit").put("sessionId", sessionId)
                .put("sequence", sequence).put("epoch", editEpoch)
                .put("payloadBase64", Base64.encodeToString(ciphertext, Base64.NO_WRAP)).toString())
        }
    }

    private fun sendControl(type: String, sessionId: String, sequence: Long) {
        socket?.send(JSONObject().put("type", type).put("sessionId", sessionId)
            .put("sequence", sequence).toString())
    }

    private suspend fun replaySession() {
        if (!_sharing.value) return
        val sessionId = engine.sessionId ?: return
        sendControl("start", sessionId, 0)
        repository.localLiveDraftSessionEdits(conversationId, sessionId).forEach { row ->
            sendEdit(sessionId, row.sequence, row.epoch, row.ciphertext)
            delay(50)
        }
    }

    private suspend fun receive(raw: String) {
        try {
            val value = JSONObject(raw)
            when (value.getString("type")) {
                "ready", "peer-online" -> {
                    retryDelayMillis = 2_000
                    val peerOnline = value.optBoolean("peerOnline", true)
                    if (peerOnline) replaySession()
                    _partner.value = _partner.value.copy(online = peerOnline)
                }
                "peer-offline" -> {
                    remoteSession = null
                    _partner.value = PartnerLiveDraft()
                }
                "start" -> {
                    val sessionId = value.getString("sessionId")
                    if (value.getLong("sequence") != 0L) return
                    if (remoteSession?.sessionId != sessionId) remoteSession = RemoteDraftSession(sessionId)
                    _partner.value = PartnerLiveDraft(remoteSession?.text.orEmpty(), sharing = true, online = true)
                }
                "edit" -> acceptEdit(value)
                "ack" -> if (value.getString("sessionId") == engine.sessionId) {
                    repository.acknowledgeLiveDraftEdits(conversationId, engine.sessionId!!,
                        value.getLong("sequence"))
                }
                "resume" -> if (value.getString("sessionId") == engine.sessionId) replaySession()
                "stop" -> if (value.getString("sessionId") == remoteSession?.sessionId) {
                    remoteSession = null
                    _partner.value = PartnerLiveDraft(online = true)
                }
            }
        } catch (_: Exception) { /* Ignore malformed or unauthenticated frames. */ }
    }

    private suspend fun acceptEdit(value: JSONObject) {
        val sessionId = value.getString("sessionId")
        val sequence = value.getLong("sequence")
        val frameEpoch = value.getLong("epoch")
        if (sessionId.length !in 8..80 || sequence !in 1..MAX_SESSION_EDITS || frameEpoch != epoch) return
        val session = remoteSession?.takeIf { it.sessionId == sessionId }
            ?: if (sequence == 1L) RemoteDraftSession(sessionId).also { remoteSession = it } else return
        if (sequence < session.nextSequence) {
            sendControl("ack", sessionId, sequence)
            return
        }
        if (sequence > session.nextSequence) {
            sendControl("resume", sessionId, session.nextSequence)
            return
        }
        val ciphertext = Base64.decode(value.getString("payloadBase64"), Base64.NO_WRAP)
        val edit = DraftFrameCrypto.decrypt(key ?: return, conversationId, sessionId, frameEpoch,
            sequence, ciphertext)
        val nextText = edit.applyTo(session.text)
        repository.saveLiveDraftEdit(LiveDraftEditEntity(conversationId, sessionId, "REMOTE",
            sequence, frameEpoch, edit.atMillis, edit.start, edit.removed, edit.inserted, ciphertext,
            acknowledged = true))
        session.apply(edit)
        _partner.value = PartnerLiveDraft(nextText, sharing = true, online = true)
        sendControl("ack", sessionId, sequence)
    }

    private sealed interface Command {
        data class Start(val text: String) : Command
        data class Text(val text: String) : Command
        data class Inbound(val json: String) : Command
        data object Pause : Command
        data object Disconnected : Command
        data object Denied : Command
    }

    private companion object {
        const val MAX_SESSION_EDITS = 1_000L
        val client = OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build()
    }
}
