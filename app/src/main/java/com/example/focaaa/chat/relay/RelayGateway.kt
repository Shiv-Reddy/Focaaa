package com.example.focaaa.chat.relay

import com.example.focaaa.R
import android.content.pm.ApplicationInfo
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.auth.FirebaseAuth
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

data class RelayResult(val data: Any?)

interface RelayGateway {
    fun ensureAnonymousSession(): Task<Unit>
    fun registerDevice(deviceId: String, fcmToken: String): Task<RelayResult>
    fun createPairingInvite(deviceId: String, token: String): Task<RelayResult>
    fun joinPairingInvite(deviceId: String, token: String, keyPackage: ByteArray): Task<RelayResult>
    fun fetchPairingRequest(deviceId: String, inviteToken: String): Task<RelayResult>
    fun publishPairingWelcome(deviceId: String, conversationId: String, welcome: ByteArray, ratchetTree: ByteArray): Task<RelayResult>
    fun fetchPairingWelcome(deviceId: String, conversationId: String): Task<RelayResult>
    fun confirmPairing(deviceId: String, conversationId: String): Task<RelayResult>
    fun revokePairing(deviceId: String, conversationId: String): Task<RelayResult>
    fun sendEnvelope(deviceId: String, conversationId: String, envelopeId: String, encryptedEnvelope: ByteArray): Task<RelayResult>
    fun fetchPending(deviceId: String, conversationId: String): Task<RelayResult>
    fun acknowledge(deviceId: String, conversationId: String, envelopeIds: List<String>): Task<RelayResult>
    fun markRead(deviceId: String, conversationId: String, envelopeIds: List<String>): Task<RelayResult>
    fun fetchMessageStatuses(deviceId: String, conversationId: String, envelopeIds: List<String>): Task<RelayResult>
    fun revokeDevice(deviceId: String): Task<RelayResult>
    fun beginMediaUpload(deviceId: String, conversationId: String, mediaId: String, ciphertextSize: Int, ciphertextSha256: String): Task<RelayResult>
    fun uploadMediaChunk(deviceId: String, conversationId: String, mediaId: String, chunkIndex: Int, payload: ByteArray, payloadSha256: String): Task<RelayResult>
    fun completeMediaUpload(deviceId: String, conversationId: String, mediaId: String): Task<RelayResult>
    fun fetchMediaManifest(deviceId: String, conversationId: String, mediaId: String): Task<RelayResult>
    fun fetchMediaChunk(deviceId: String, conversationId: String, mediaId: String, chunkIndex: Int): Task<RelayResult>
    fun cancelMediaUpload(deviceId: String, conversationId: String, mediaId: String): Task<RelayResult>
}

internal object RelayEndpoint {
    fun normalize(configured: String): String {
        val value = configured.trim()
        check(value.isNotEmpty()) { "The private relay is not set up yet. Connect the free backend first." }
        val uri = URI(value)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.query == null && uri.fragment == null &&
            (uri.path.isNullOrEmpty() || uri.path == "/") && (uri.port == -1 || uri.port == 443)) {
            "The private relay address must be a secure HTTPS address."
        }
        return "https://${uri.host}${if (uri.port == 443) ":443" else ""}"
    }
}

/** Firebase provides identity/App Check; the encrypted relay is a Cloudflare Worker. */
class WorkerRelayGateway(
    private val configuredUrl: String = FirebaseApp.getInstance().applicationContext.getString(R.string.relay_base_url)
) : RelayGateway {
    override fun ensureAnonymousSession(): Task<Unit> {
        try { RelayEndpoint.normalize(configuredUrl) }
        catch (error: Exception) { return TaskCompletionSource<Unit>().apply { setException(error) }.task }
        val auth = FirebaseAuth.getInstance()
        val currentUser = auth.currentUser
        if (currentUser == null) return signInAnonymously(auth)
        if (!currentUser.isAnonymous) return TaskCompletionSource<Unit>().apply {
            setException(IllegalStateException("The relay requires an anonymous Firebase session."))
        }.task
        val result = TaskCompletionSource<Unit>()
        currentUser.getIdToken(true)
            .addOnSuccessListener { result.setResult(Unit) }
            .addOnFailureListener { error ->
                if (!error.hasInvalidRefreshToken()) result.setException(error)
                else {
                    // Old emulator identities cannot authenticate with the live project.
                    auth.signOut()
                    signInAnonymously(auth)
                        .addOnSuccessListener { result.setResult(Unit) }
                        .addOnFailureListener(result::setException)
                }
            }
        return result.task
    }

    private fun signInAnonymously(auth: FirebaseAuth): Task<Unit> {
        val result = TaskCompletionSource<Unit>()
        auth.signInAnonymously()
            .addOnSuccessListener { account ->
                if (account.user?.isAnonymous == true) result.setResult(Unit)
                else result.setException(IllegalStateException("Firebase did not create an anonymous session."))
            }
            .addOnFailureListener(result::setException)
        return result.task
    }

    override fun registerDevice(deviceId: String, fcmToken: String) = call("registerDevice", mapOf("deviceId" to deviceId, "fcmToken" to fcmToken))
    override fun createPairingInvite(deviceId: String, token: String) = call("createPairingInvite", mapOf("deviceId" to deviceId, "token" to token))
    override fun joinPairingInvite(deviceId: String, token: String, keyPackage: ByteArray) = call("joinPairingInvite", mapOf(
        "deviceId" to deviceId, "token" to token, "keyPackageBase64" to encode(keyPackage, MAX_KEY_PACKAGE_BYTES)))
    override fun fetchPairingRequest(deviceId: String, inviteToken: String) = call("fetchPairingRequest", mapOf("deviceId" to deviceId, "token" to inviteToken))
    override fun publishPairingWelcome(deviceId: String, conversationId: String, welcome: ByteArray, ratchetTree: ByteArray) =
        call("publishPairingWelcome", mapOf("deviceId" to deviceId, "conversationId" to conversationId,
            "welcomeBase64" to encode(welcome, MAX_WELCOME_BYTES), "ratchetTreeBase64" to encode(ratchetTree, MAX_RATCHET_TREE_BYTES)))
    override fun fetchPairingWelcome(deviceId: String, conversationId: String) =
        call("fetchPairingWelcome", mapOf("deviceId" to deviceId, "conversationId" to conversationId))
    override fun confirmPairing(deviceId: String, conversationId: String) =
        call("confirmPairing", mapOf("deviceId" to deviceId, "conversationId" to conversationId))
    override fun revokePairing(deviceId: String, conversationId: String) =
        call("revokePairing", mapOf("deviceId" to deviceId, "conversationId" to conversationId))
    override fun sendEnvelope(deviceId: String, conversationId: String, envelopeId: String, encryptedEnvelope: ByteArray) =
        call("sendEnvelope", mapOf("deviceId" to deviceId, "conversationId" to conversationId,
            "envelopeId" to envelopeId, "payloadBase64" to encode(encryptedEnvelope, MAX_ENVELOPE_BYTES)))
    override fun fetchPending(deviceId: String, conversationId: String) =
        call("fetchPendingEnvelopes", mapOf("deviceId" to deviceId, "conversationId" to conversationId))
    override fun acknowledge(deviceId: String, conversationId: String, envelopeIds: List<String>): Task<RelayResult> {
        require(envelopeIds.size in 1..100)
        return call("acknowledgeEnvelopes", mapOf("deviceId" to deviceId,
            "conversationId" to conversationId, "envelopeIds" to envelopeIds))
    }
    override fun markRead(deviceId: String, conversationId: String, envelopeIds: List<String>): Task<RelayResult> {
        require(envelopeIds.size in 1..100)
        return call("markRead", mapOf("deviceId" to deviceId,
            "conversationId" to conversationId, "envelopeIds" to envelopeIds))
    }
    override fun fetchMessageStatuses(deviceId: String, conversationId: String, envelopeIds: List<String>): Task<RelayResult> {
        require(envelopeIds.size in 1..100)
        return call("fetchMessageStatuses", mapOf("deviceId" to deviceId,
            "conversationId" to conversationId, "envelopeIds" to envelopeIds))
    }
    override fun revokeDevice(deviceId: String) = call("revokeDevice", mapOf("deviceId" to deviceId))
    override fun beginMediaUpload(deviceId: String, conversationId: String, mediaId: String, ciphertextSize: Int, ciphertextSha256: String) =
        call("beginMediaUpload", mapOf("deviceId" to deviceId, "conversationId" to conversationId,
            "mediaId" to mediaId, "ciphertextSize" to ciphertextSize, "ciphertextSha256" to ciphertextSha256))
    override fun uploadMediaChunk(deviceId: String, conversationId: String, mediaId: String, chunkIndex: Int, payload: ByteArray, payloadSha256: String) =
        call("uploadMediaChunk", mapOf("deviceId" to deviceId, "conversationId" to conversationId,
            "mediaId" to mediaId, "chunkIndex" to chunkIndex, "payloadBase64" to encode(payload, 128 * 1024),
            "payloadSha256" to payloadSha256))
    override fun completeMediaUpload(deviceId: String, conversationId: String, mediaId: String) =
        call("completeMediaUpload", mapOf("deviceId" to deviceId, "conversationId" to conversationId, "mediaId" to mediaId))
    override fun fetchMediaManifest(deviceId: String, conversationId: String, mediaId: String) =
        call("fetchMediaManifest", mapOf("deviceId" to deviceId, "conversationId" to conversationId, "mediaId" to mediaId))
    override fun fetchMediaChunk(deviceId: String, conversationId: String, mediaId: String, chunkIndex: Int) =
        call("fetchMediaChunk", mapOf("deviceId" to deviceId, "conversationId" to conversationId,
            "mediaId" to mediaId, "chunkIndex" to chunkIndex))
    override fun cancelMediaUpload(deviceId: String, conversationId: String, mediaId: String) =
        call("cancelMediaUpload", mapOf("deviceId" to deviceId, "conversationId" to conversationId, "mediaId" to mediaId))

    private fun encode(bytes: ByteArray, maximum: Int): String {
        require(bytes.isNotEmpty() && bytes.size <= maximum)
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    private fun call(name: String, arguments: Map<String, Any>): Task<RelayResult> {
        val result = TaskCompletionSource<RelayResult>()
        val baseUrl = try { RelayEndpoint.normalize(configuredUrl) }
        catch (error: Exception) { result.setException(error); return result.task }
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null || !user.isAnonymous) {
            result.setException(IllegalStateException("Sign in is required for the private relay."))
            return result.task
        }
        // The SDK refreshes expired tokens; forcing a network refresh on every five-second poll is wasteful.
        user.getIdToken(false)
            .addOnSuccessListener { identity ->
                val idToken = identity.token
                if (idToken.isNullOrBlank()) {
                    result.setException(IllegalStateException("Firebase did not provide a sign-in token."))
                    return@addOnSuccessListener
                }
                FirebaseAppCheck.getInstance().getAppCheckToken(false)
                    .addOnSuccessListener { appCheck -> networkExecutor.execute {
                        try { result.setResult(post(baseUrl, name, arguments, idToken, appCheck.token)) }
                        catch (error: Exception) { result.setException(error) }
                    } }
                    .addOnFailureListener(result::setException)
            }
            .addOnFailureListener(result::setException)
        return result.task
    }

    private fun post(baseUrl: String, name: String, arguments: Map<String, Any>, idToken: String, appCheckToken: String): RelayResult {
        val connection = URL("$baseUrl/v1/$name").openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Authorization", "Bearer $idToken")
            connection.setRequestProperty("X-Firebase-AppCheck", appCheckToken)
            connection.outputStream.use { it.write(JSONObject(arguments).toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (FirebaseApp.getInstance().applicationContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                Log.d("FocaaaRelay", "$name HTTP $status")
            }
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val response = JSONObject(body)
            if (status !in 200..299) {
                val error = response.optJSONObject("error")
                throw relayRequestFailure(status, error?.optString("code"), error?.optString("message"))
            }
            if (!response.has("result")) throw IllegalStateException("The private relay returned an invalid response.")
            return RelayResult(jsonValue(response.get("result")))
        } finally { connection.disconnect() }
    }

    private fun jsonValue(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> value.keys().asSequence().associateWith { key -> jsonValue(value.get(key)) }
        is JSONArray -> (0 until value.length()).map { index -> jsonValue(value.get(index)) }
        else -> value
    }

    companion object {
        private val networkExecutor = Executors.newFixedThreadPool(2)
        const val MAX_ENVELOPE_BYTES = 48 * 1024
        const val MAX_KEY_PACKAGE_BYTES = 16 * 1024
        const val MAX_WELCOME_BYTES = 64 * 1024
        const val MAX_RATCHET_TREE_BYTES = 64 * 1024
    }
}

private fun Throwable.hasInvalidRefreshToken(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current.message?.contains("INVALID_REFRESH_TOKEN", ignoreCase = true) == true) return true
        current = current.cause
    }
    return false
}
