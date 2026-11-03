package com.example.focaaa.chat.live

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-GCM using a key exported from the verified MLS epoch, never sent to the relay. */
object DraftFrameCrypto {
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val MAX_FRAME_BYTES = 24_000
    private val random = SecureRandom()

    fun encrypt(key: ByteArray, conversationId: String, sessionId: String, epoch: Long, edit: DraftEdit): ByteArray {
        require(key.size == 32 && epoch >= 0)
        val plaintext = ByteArrayOutputStream().use { stream ->
            DataOutputStream(stream).use { output ->
                output.writeByte(1)
                output.writeLong(edit.sequence)
                output.writeLong(edit.atMillis)
                output.writeInt(edit.start)
                output.writeUTF(edit.removed)
                output.writeUTF(edit.inserted)
            }
            stream.toByteArray()
        }
        require(plaintext.size <= MAX_FRAME_BYTES - NONCE_BYTES - TAG_BITS / 8)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad(conversationId, sessionId, epoch, edit.sequence))
            nonce + cipher.doFinal(plaintext)
        } finally { plaintext.fill(0) }
    }

    fun decrypt(key: ByteArray, conversationId: String, sessionId: String, epoch: Long,
                sequence: Long, frame: ByteArray): DraftEdit {
        require(key.size == 32 && epoch >= 0 && sequence > 0)
        require(frame.size in NONCE_BYTES + TAG_BITS / 8 + 1..MAX_FRAME_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, frame.copyOfRange(0, NONCE_BYTES)))
        cipher.updateAAD(aad(conversationId, sessionId, epoch, sequence))
        val plaintext = cipher.doFinal(frame, NONCE_BYTES, frame.size - NONCE_BYTES)
        return try {
            DataInputStream(ByteArrayInputStream(plaintext)).use { input ->
                require(input.readUnsignedByte() == 1) { "Live draft format is unsupported." }
                val actualSequence = input.readLong()
                val atMillis = input.readLong()
                val start = input.readInt()
                val removed = input.readUTF()
                val inserted = input.readUTF()
                require(actualSequence == sequence && atMillis > 0 && start >= 0 &&
                    removed.length <= DraftEdit.MAX_DRAFT_CHARS &&
                    inserted.length <= DraftEdit.MAX_DRAFT_CHARS && input.available() == 0)
                DraftEdit(actualSequence, atMillis, start, removed, inserted)
            }
        } finally { plaintext.fill(0) }
    }

    private fun aad(conversationId: String, sessionId: String, epoch: Long, sequence: Long): ByteArray {
        require(conversationId.isNotBlank() && sessionId.isNotBlank())
        return "focaaa-live-draft-v1\u0000$conversationId\u0000$sessionId\u0000$epoch\u0000$sequence"
            .toByteArray(Charsets.UTF_8)
    }
}
