package com.example.focaaa.chat.pairing

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.Base64

/** Random, public identifier for one short-lived pairing attempt; it is not an authentication key. */
@JvmInline
value class PairingInviteId(val value: String) {
    init {
        require(value.length == INVITE_ID_TEXT_LENGTH && value.all { it in BASE64_URL_CHARS }) {
            "Invite IDs must be unpadded base64url encodings of 16 bytes."
        }
        require(Base64.getUrlDecoder().decode(value).size == 16)
        require(Base64.getUrlEncoder().withoutPadding().encodeToString(Base64.getUrlDecoder().decode(value)) == value) {
            "Invite IDs must use canonical base64url encoding."
        }
    }

    fun toBytes(): ByteArray = Base64.getUrlDecoder().decode(value)

    companion object {
        private const val INVITE_ID_TEXT_LENGTH = 22
        private const val BASE64_URL_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        fun generate(random: SecureRandom = SecureRandom()): PairingInviteId {
            val bytes = ByteArray(16).also(random::nextBytes)
            return PairingInviteId(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
        }

        internal fun fromBytes(bytes: ByteArray): PairingInviteId {
            require(bytes.size == 16)
            return PairingInviteId(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
        }

    }
}

enum class PairingQrKind(internal val wireValue: Int) {
    INVITE(1),
    JOIN_RESPONSE(2),
    WELCOME(3);

    companion object {
        internal fun fromWireValue(value: Int): PairingQrKind? = entries.firstOrNull { it.wireValue == value }
    }
}

/** Opaque public MLS KeyPackage or Welcome bytes carried by a QR handshake step. */
class PairingQrPayload(
    val kind: PairingQrKind,
    val inviteId: PairingInviteId,
    val issuedAtMillis: Long,
    val expiresAtMillis: Long,
    content: ByteArray
) {
    private val contentBytes = content.copyOf()

    init {
        require(contentBytes.isNotEmpty() && contentBytes.size <= PairingQrCodec.MAX_CONTENT_BYTES)
        require(issuedAtMillis > 0L && expiresAtMillis > issuedAtMillis)
    }

    fun contentCopy(): ByteArray = contentBytes.copyOf()
}

sealed interface PairingQrDecodeResult {
    data class Accepted(val payload: PairingQrPayload) : PairingQrDecodeResult

    data class Rejected(val reason: Reason) : PairingQrDecodeResult

    enum class Reason {
        TOO_LARGE,
        BAD_PREFIX,
        BAD_ENCODING,
        UNSUPPORTED_VERSION,
        UNKNOWN_KIND,
        BAD_LENGTH,
        EMPTY_CONTENT,
        EXPIRED,
        INVALID_LIFETIME,
        ISSUED_IN_FUTURE
    }
}

/**
 * Versioned, bounded QR envelope for the three-step offline handshake. MLS validates the contents;
 * this codec only checks framing and local time limits. The initiator must also verify expiry and
 * one-time-use against its persisted invite record when it accepts each response.
 */
object PairingQrCodec {
    const val MAX_CONTENT_BYTES = 2_048
    const val MAX_TEXT_CHARS = 4_096
    const val INVITE_TTL_MILLIS = 5 * 60 * 1_000L
    const val ALLOWED_FUTURE_SKEW_MILLIS = 30 * 1_000L

    private const val PREFIX = "FOCAAA-PAIR:"
    private val MAGIC = byteArrayOf('F'.code.toByte(), 'C'.code.toByte(), 'A'.code.toByte(), 'A'.code.toByte())
    private const val VERSION = 1
    private const val HEADER_BYTES = 4 + 1 + 1 + 16 + 8 + 8 + 2

    fun encode(payload: PairingQrPayload, nowMillis: Long): String? {
        if (validateTimes(payload, nowMillis) != null) return null
        val content = payload.contentCopy()
        if (content.isEmpty() || content.size > MAX_CONTENT_BYTES) return null

        val bytes = ByteArrayOutputStream(HEADER_BYTES + content.size)
        DataOutputStream(bytes).use { output ->
            output.write(MAGIC)
            output.writeByte(VERSION)
            output.writeByte(payload.kind.wireValue)
            output.write(payload.inviteId.toBytes())
            output.writeLong(payload.issuedAtMillis)
            output.writeLong(payload.expiresAtMillis)
            output.writeShort(content.size)
            output.write(content)
        }
        val encoded = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray())
        return encoded.takeIf { it.length <= MAX_TEXT_CHARS }
    }

    fun decode(text: String, nowMillis: Long): PairingQrDecodeResult {
        if (text.length > MAX_TEXT_CHARS) return rejected(PairingQrDecodeResult.Reason.TOO_LARGE)
        if (!text.startsWith(PREFIX)) return rejected(PairingQrDecodeResult.Reason.BAD_PREFIX)
        val encodedBody = text.substring(PREFIX.length)
        if (encodedBody.isEmpty()) return rejected(PairingQrDecodeResult.Reason.BAD_ENCODING)

        val bytes = try {
            Base64.getUrlDecoder().decode(encodedBody)
        } catch (_: IllegalArgumentException) {
            return rejected(PairingQrDecodeResult.Reason.BAD_ENCODING)
        }
        if (Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) != encodedBody) {
            return rejected(PairingQrDecodeResult.Reason.BAD_ENCODING)
        }
        if (bytes.size < HEADER_BYTES) return rejected(PairingQrDecodeResult.Reason.BAD_LENGTH)
        if (bytes.size > HEADER_BYTES + MAX_CONTENT_BYTES) return rejected(PairingQrDecodeResult.Reason.TOO_LARGE)

        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(MAGIC.size).also { input.get(it) }
        if (!magic.contentEquals(MAGIC)) return rejected(PairingQrDecodeResult.Reason.BAD_PREFIX)
        if ((input.get().toInt() and 0xff) != VERSION) {
            return rejected(PairingQrDecodeResult.Reason.UNSUPPORTED_VERSION)
        }
        val kind = PairingQrKind.fromWireValue(input.get().toInt() and 0xff)
            ?: return rejected(PairingQrDecodeResult.Reason.UNKNOWN_KIND)
        val inviteIdBytes = ByteArray(16).also { input.get(it) }
        val issuedAtMillis = input.long
        val expiresAtMillis = input.long
        val contentLength = input.short.toInt() and 0xffff
        if (contentLength == 0) return rejected(PairingQrDecodeResult.Reason.EMPTY_CONTENT)
        if (contentLength > MAX_CONTENT_BYTES || input.remaining() != contentLength) {
            return rejected(PairingQrDecodeResult.Reason.BAD_LENGTH)
        }
        val content = ByteArray(contentLength).also { input.get(it) }

        val payload = try {
            PairingQrPayload(
                kind = kind,
                inviteId = PairingInviteId.fromBytes(inviteIdBytes),
                issuedAtMillis = issuedAtMillis,
                expiresAtMillis = expiresAtMillis,
                content = content
            )
        } catch (_: IllegalArgumentException) {
            return rejected(PairingQrDecodeResult.Reason.INVALID_LIFETIME)
        }
        val invalidTime = validateTimes(payload, nowMillis)
            ?: return PairingQrDecodeResult.Accepted(payload)
        return rejected(invalidTime)
    }

    private fun validateTimes(payload: PairingQrPayload, nowMillis: Long): PairingQrDecodeResult.Reason? {
        if (payload.issuedAtMillis > nowMillis + ALLOWED_FUTURE_SKEW_MILLIS) {
            return PairingQrDecodeResult.Reason.ISSUED_IN_FUTURE
        }
        if (payload.expiresAtMillis <= nowMillis) return PairingQrDecodeResult.Reason.EXPIRED
        val lifetime = payload.expiresAtMillis - payload.issuedAtMillis
        if (lifetime <= 0L || lifetime > INVITE_TTL_MILLIS) {
            return PairingQrDecodeResult.Reason.INVALID_LIFETIME
        }
        return null
    }

    private fun rejected(reason: PairingQrDecodeResult.Reason) = PairingQrDecodeResult.Rejected(reason)
}

/** Atomic persistence boundary for replay protection; use a unique `(inviteId, kind)` row key. */
interface PairingQrReplayStore {
    /** Return false if this invite step was previously consumed or has expired. */
    suspend fun consumeOnce(
        inviteId: PairingInviteId,
        kind: PairingQrKind,
        expiresAtMillis: Long,
        nowMillis: Long
    ): Boolean
}
