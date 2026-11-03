package com.example.focaaa.chat.typing

import android.content.Context
import com.example.focaaa.R
import com.example.focaaa.chat.relay.RelayDeviceRegistration
import com.example.focaaa.chat.relay.RelayEndpoint
import com.google.android.gms.tasks.Tasks
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.auth.FirebaseAuth
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/** Ephemeral status only: composer text never enters this class or crosses the socket. */
class TypingCoordinator(context: Context, private val conversationId: String) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = sharedClient
    private val _partnerTyping = MutableStateFlow(false)
    val partnerTyping: StateFlow<Boolean> = _partnerTyping
    @Volatile private var active = false
    @Volatile private var composing = false
    @Volatile private var socket: WebSocket? = null
    @Volatile private var generation = 0
    private var reconnect: Job? = null
    private var idle: Job? = null
    private var peerExpiry: Job? = null
    private var retryMillis = 1_000L
    @Volatile private var lastActiveSentAt = 0L

    fun start() {
        if (active) return
        active = true
        scope.launch { connect() }
    }

    fun edited(hasText: Boolean) {
        if (!active) return
        val wasComposing = composing
        composing = hasText
        if (wasComposing != hasText || hasText) send(hasText)
        idle?.cancel()
        if (hasText) idle = scope.launch {
            delay(2_500)
            if (active && composing) { composing = false; send(false) }
        }
    }

    fun sent() { composing = false; idle?.cancel(); send(false) }

    fun stop() {
        if (!active) return
        sent()
        active = false
        generation++
        reconnect?.cancel()
        peerExpiry?.cancel()
        _partnerTyping.value = false
        socket?.close(1000, "Chat closed")
        socket = null
    }

    /** Release this screen's jobs; the shared HTTP client is reused by later chats. */
    fun dispose() {
        stop()
        scope.cancel()
    }

    private suspend fun connect() {
        if (!active) return
        try {
            val user = FirebaseAuth.getInstance().currentUser ?: return retry()
            val idToken = Tasks.await(user.getIdToken(false), 20, TimeUnit.SECONDS).token ?: return retry()
            val appCheck = Tasks.await(FirebaseAppCheck.getInstance().getAppCheckToken(false),
                20, TimeUnit.SECONDS).token
            if (!active) return
            val base = RelayEndpoint.normalize(appContext.getString(R.string.relay_base_url))
            val deviceId = RelayDeviceRegistration.deviceId(appContext)
            val request = Request.Builder()
                .url("$base/v1/typing?deviceId=$deviceId&conversationId=$conversationId")
                .header("Authorization", "Bearer $idToken")
                .header("X-Firebase-AppCheck", appCheck)
                .build()
            val current = ++generation
            socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!active || current != generation) { webSocket.close(1000, "Chat closed"); return }
                    socket = webSocket
                    retryMillis = 1_000L
                    lastActiveSentAt = 0L
                    if (composing) send(true)
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (!active || current != generation) return
                    runCatching {
                        val frame = JSONObject(text)
                        if (frame.optString("type") != "typing") return@runCatching
                        val value = frame.optBoolean("active", false)
                        _partnerTyping.value = value
                        peerExpiry?.cancel()
                        if (value) peerExpiry = scope.launch {
                            delay(4_000)
                            _partnerTyping.value = false
                        }
                    }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    disconnected(current)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    disconnected(current)
                }
            })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { retry() }
    }

    private fun disconnected(current: Int) {
        if (!active || current != generation) return
        socket = null
        _partnerTyping.value = false
        retry()
    }

    private fun retry() {
        if (!active || reconnect?.isActive == true) return
        reconnect = scope.launch {
            delay(retryMillis)
            retryMillis = (retryMillis * 2).coerceAtMost(30_000L)
            reconnect = null
            connect()
        }
    }

    private fun send(value: Boolean) {
        if (!active) return
        val now = System.currentTimeMillis()
        if (value && now - lastActiveSentAt < 1_500L) return
        if (value) lastActiveSentAt = now else lastActiveSentAt = 0L
        socket?.send("{\"type\":\"typing\",\"active\":$value}")
    }

    private companion object {
        val sharedClient = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    }
}
