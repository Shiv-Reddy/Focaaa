package com.example.focaaa.chat.media

import java.util.Base64
import java.util.UUID

enum class MediaKind { PHOTO, VIDEO }

data class MediaDescriptor(
    val kind: MediaKind,
    val mimeCode: String,
    val mediaId: String,
    val keyBase64: String,
    val nonceBase64: String,
    val ciphertextSha256: String,
    val plaintextSize: Int,
    val ciphertextSize: Int
) {
    val mimeType: String get() = MediaMessageCodec.mimeFor(mimeCode)
    val chunkCount: Int get() = (ciphertextSize + MediaMessageCodec.CHUNK_BYTES - 1) / MediaMessageCodec.CHUNK_BYTES
}

/** This short descriptor is sent only inside the existing MLS encrypted message. */
object MediaMessageCodec {
    const val PREFIX = "FOCAAA_MEDIA_V1|"
    const val CHUNK_BYTES = 128 * 1024
    const val MAX_PHOTO_BYTES = 2 * 1024 * 1024
    const val MAX_VIDEO_BYTES = 10 * 1024 * 1024
    private val sha256 = Regex("[0-9a-f]{64}")
    private val urlBase64 = Base64.getUrlDecoder()

    fun mimeCode(kind: MediaKind, mime: String): String = when (kind to mime.lowercase()) {
        MediaKind.PHOTO to "image/jpeg" -> "j"
        MediaKind.PHOTO to "image/png" -> "p"
        MediaKind.PHOTO to "image/webp" -> "w"
        MediaKind.PHOTO to "image/heic" -> "h"
        MediaKind.VIDEO to "video/mp4" -> "m"
        else -> throw IllegalArgumentException("Choose a JPEG, PNG, WebP, HEIC, or MP4 file.")
    }

    fun mimeFor(code: String): String = when (code) {
        "j" -> "image/jpeg"
        "p" -> "image/png"
        "w" -> "image/webp"
        "h" -> "image/heic"
        "m" -> "video/mp4"
        else -> throw IllegalArgumentException("Unsupported private media type.")
    }

    fun encode(value: MediaDescriptor): String {
        validate(value)
        return listOf("FOCAAA_MEDIA_V1", if (value.kind == MediaKind.PHOTO) "p" else "v",
            value.mimeCode, value.mediaId, value.keyBase64, value.nonceBase64,
            value.ciphertextSha256, value.plaintextSize.toString(), value.ciphertextSize.toString()).joinToString("|")
    }

    fun decode(text: String): MediaDescriptor? {
        if (!text.startsWith(PREFIX)) return null
        val parts = text.split('|')
        require(parts.size == 9) { "This private media message is invalid." }
        val kind = when (parts[1]) {
            "p" -> MediaKind.PHOTO
            "v" -> MediaKind.VIDEO
            else -> throw IllegalArgumentException("This private media message is invalid.")
        }
        val value = MediaDescriptor(kind, parts[2], parts[3], parts[4], parts[5], parts[6],
            parts[7].toIntOrNull() ?: -1, parts[8].toIntOrNull() ?: -1)
        validate(value)
        return value
    }

    fun preview(text: String): String = try {
        when (decode(text)?.kind) {
            MediaKind.PHOTO -> "Photo"
            MediaKind.VIDEO -> "Video"
            null -> text
        }
    } catch (_: IllegalArgumentException) {
        if (text.startsWith(PREFIX)) "Media unavailable" else text
    }

    fun validate(value: MediaDescriptor) {
        require(runCatching { UUID.fromString(value.mediaId).toString() == value.mediaId }.getOrDefault(false))
        require(sha256.matches(value.ciphertextSha256))
        val key = runCatching { urlBase64.decode(value.keyBase64) }.getOrNull()
        val nonce = runCatching { urlBase64.decode(value.nonceBase64) }.getOrNull()
        require(key?.size == 32 && nonce?.size == 12 &&
            Base64.getUrlEncoder().withoutPadding().encodeToString(key) == value.keyBase64 &&
            Base64.getUrlEncoder().withoutPadding().encodeToString(nonce) == value.nonceBase64)
        val max = if (value.kind == MediaKind.PHOTO) MAX_PHOTO_BYTES else MAX_VIDEO_BYTES
        require(value.plaintextSize in 1..max && value.ciphertextSize == value.plaintextSize + 16)
        require(if (value.kind == MediaKind.PHOTO) value.mimeCode in setOf("j", "p", "w", "h")
            else value.mimeCode == "m")
    }
}
