package com.example.focaaa.chat.media

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PreparedMedia(val descriptor: MediaDescriptor, val ciphertext: ByteArray)

/** Only ciphertext is retained in app-private files; plaintext is returned for a private viewer. */
class EncryptedMediaStore(private val context: Context) {
    suspend fun prepare(uri: Uri, kind: MediaKind, conversationId: String): PreparedMedia = withContext(Dispatchers.IO) {
        val mime = context.contentResolver.getType(uri)?.lowercase()
            ?: throw IllegalArgumentException("Could not identify this file type.")
        val code = MediaMessageCodec.mimeCode(kind, mime)
        val max = if (kind == MediaKind.PHOTO) MediaMessageCodec.MAX_PHOTO_BYTES
            else MediaMessageCodec.MAX_VIDEO_BYTES
        val plaintext = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (out.size() + count > max) throw IllegalArgumentException(
                    if (kind == MediaKind.PHOTO) "Photo must be 2 MB or smaller."
                    else "Video must be 10 MB or smaller."
                )
                out.write(buffer, 0, count)
            }
            buffer.fill(0)
            out.toByteArray()
        } ?: throw IllegalArgumentException("Could not open the selected file.")
        require(plaintext.isNotEmpty()) { "The selected file is empty." }
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            val encrypted = cipher.doFinal(plaintext)
            val descriptor = MediaDescriptor(kind, code, UUID.randomUUID().toString(),
                Base64.getUrlEncoder().withoutPadding().encodeToString(key),
                Base64.getUrlEncoder().withoutPadding().encodeToString(nonce),
                digest(encrypted), plaintext.size, encrypted.size)
            saveCiphertext(conversationId, descriptor, encrypted)
            PreparedMedia(descriptor, encrypted)
        } finally {
            plaintext.fill(0)
            key.fill(0)
            nonce.fill(0)
        }
    }

    suspend fun saveCiphertext(conversationId: String, descriptor: MediaDescriptor, ciphertext: ByteArray) = withContext(Dispatchers.IO) {
        MediaMessageCodec.validate(descriptor)
        require(ciphertext.size == descriptor.ciphertextSize && digest(ciphertext) == descriptor.ciphertextSha256) {
            "Encrypted media failed its integrity check."
        }
        val destination = file(conversationId, descriptor.mediaId)
        val temp = File(destination.parentFile, "${descriptor.mediaId}.tmp")
        try {
            temp.outputStream().use { it.write(ciphertext) }
            check(temp.renameTo(destination)) { "Could not save private media." }
        } finally { temp.delete() }
    }

    suspend fun readCiphertext(conversationId: String, descriptor: MediaDescriptor): ByteArray? = withContext(Dispatchers.IO) {
        val stored = file(conversationId, descriptor.mediaId)
        if (!stored.isFile) return@withContext null
        require(stored.length() == descriptor.ciphertextSize.toLong()) { "Saved media is damaged." }
        val bytes = stored.readBytes()
        require(digest(bytes) == descriptor.ciphertextSha256) { "Saved media is damaged." }
        bytes
    }

    suspend fun decrypt(descriptor: MediaDescriptor, ciphertext: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        MediaMessageCodec.validate(descriptor)
        require(ciphertext.size == descriptor.ciphertextSize && digest(ciphertext) == descriptor.ciphertextSha256) {
            "Encrypted media failed its integrity check."
        }
        val key = Base64.getUrlDecoder().decode(descriptor.keyBase64)
        val nonce = Base64.getUrlDecoder().decode(descriptor.nonceBase64)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            val plaintext = cipher.doFinal(ciphertext)
            require(plaintext.size == descriptor.plaintextSize) { "Private media size does not match." }
            plaintext
        } finally { key.fill(0); nonce.fill(0) }
    }

    suspend fun delete(conversationId: String, mediaId: String) = withContext(Dispatchers.IO) {
        file(conversationId, mediaId).delete()
    }

    suspend fun deleteConversation(conversationId: String) = withContext(Dispatchers.IO) {
        directory(conversationId).deleteRecursively()
    }

    private fun directory(conversationId: String): File {
        require(Regex("[A-Za-z0-9_-]{1,128}").matches(conversationId))
        return File(context.filesDir, "private_media/$conversationId").also { check(it.isDirectory || it.mkdirs()) }
    }

    private fun file(conversationId: String, mediaId: String): File {
        require(runCatching { UUID.fromString(mediaId).toString() == mediaId }.getOrDefault(false))
        return File(directory(conversationId), "$mediaId.bin")
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
