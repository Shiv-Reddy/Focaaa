package com.example.focaaa.chat.backup

import com.example.focaaa.chat.MessageSender
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Read-only history; no MLS identity, relay record, PIN, or app credential belongs here. */
data class HistoryMessage(
    val sourceId: String,
    val sender: MessageSender,
    val createdAtMillis: Long,
    val text: String
)

data class HistoryConversation(
    val sourceId: String,
    val title: String,
    val messages: List<HistoryMessage>
)

data class HistorySnapshot(
    val backupId: String,
    val createdAtMillis: Long,
    val conversations: List<HistoryConversation>
)

/** A random recovery key is exported to the user, never kept in app storage. */
object HistoryBackupCodec {
    private val random = SecureRandom()
    private val magic = "FOCAAABK".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Byte = 1
    private const val NONCE_BYTES = 12
    private const val TAG_BYTES = 16
    private const val HEADER_BYTES = 8 + 1 + NONCE_BYTES
    const val MAX_FILE_BYTES = 16 * 1024 * 1024
    private const val MAX_CONVERSATIONS = 100
    private const val MAX_MESSAGES = 20_000
    private const val MAX_TEXT_CHARS = 4_000
    private const val MAX_TEXT_BYTES = 16_000
    private val idPattern = Regex("[A-Za-z0-9_-]{1,128}")
    private val backupIdPattern = Regex("[a-f0-9]{32}")
    private val recoveryKeyPattern = Regex("[A-Za-z0-9_-]{43}")

    fun generateRecoveryKey(): String = ByteArray(32).also(random::nextBytes).let { bytes ->
        try { Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) }
        finally { bytes.fill(0) }
    }

    fun encrypt(snapshot: HistorySnapshot, recoveryKey: String): ByteArray {
        validate(snapshot)
        val key = decodeRecoveryKey(recoveryKey)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val header = magic + byteArrayOf(VERSION) + nonce
        val plaintext = encode(snapshot)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(header)
            val ciphertext = cipher.doFinal(plaintext)
            check(header.size + ciphertext.size <= MAX_FILE_BYTES) { "The history backup is too large." }
            return header + ciphertext
        } finally {
            key.fill(0)
            plaintext.fill(0)
            nonce.fill(0)
        }
    }

    fun decrypt(file: ByteArray, recoveryKey: String): HistorySnapshot {
        require(file.size in (HEADER_BYTES + TAG_BYTES)..MAX_FILE_BYTES) { "The history backup has an invalid size." }
        require(file.copyOfRange(0, magic.size).contentEquals(magic)) { "This is not a Focaaa history backup." }
        require(file[magic.size] == VERSION) { "This history backup version is unsupported." }
        val key = decodeRecoveryKey(recoveryKey)
        val nonce = file.copyOfRange(magic.size + 1, HEADER_BYTES)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(file, 0, HEADER_BYTES)
            val plaintext = cipher.doFinal(file, HEADER_BYTES, file.size - HEADER_BYTES)
            try { return decode(plaintext).also(::validate) }
            finally { plaintext.fill(0) }
        } finally {
            key.fill(0)
            nonce.fill(0)
        }
    }

    fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            check(output.size() + count <= MAX_FILE_BYTES) { "The history backup is too large." }
            output.write(buffer, 0, count)
        }
        buffer.fill(0)
        return output.toByteArray()
    }

    private fun decodeRecoveryKey(value: String): ByteArray {
        require(recoveryKeyPattern.matches(value)) { "Enter the complete recovery key." }
        val bytes = Base64.getUrlDecoder().decode(value)
        require(bytes.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == value) {
            "The recovery key is invalid."
        }
        return bytes
    }

    internal fun validate(snapshot: HistorySnapshot) {
        require(backupIdPattern.matches(snapshot.backupId) && snapshot.createdAtMillis > 0) {
            "The history backup metadata is invalid."
        }
        require(snapshot.conversations.size <= MAX_CONVERSATIONS) { "There are too many conversations to back up." }
        val conversationIds = HashSet<String>()
        val messageIds = HashSet<String>()
        var messageCount = 0
        var encodedSize = 4 + 4 + 32 + 8 + 4
        snapshot.conversations.forEach { conversation ->
            require(idPattern.matches(conversation.sourceId) && conversationIds.add(conversation.sourceId)) {
                "A history conversation ID is invalid or repeated."
            }
            require(conversation.sourceId != "synthetic-demo" && conversation.title.length in 1..40 &&
                conversation.title.isNotBlank()) { "A history contact is invalid." }
            encodedSize += 4 + conversation.sourceId.toByteArray(Charsets.UTF_8).size
            encodedSize += 4 + conversation.title.toByteArray(Charsets.UTF_8).size + 4
            messageCount += conversation.messages.size
            require(messageCount <= MAX_MESSAGES) { "There are too many messages to back up." }
            conversation.messages.forEach { message ->
                require(idPattern.matches(message.sourceId) && messageIds.add(message.sourceId)) {
                    "A history message ID is invalid or repeated."
                }
                require(message.sender == MessageSender.LOCAL_USER || message.sender == MessageSender.REMOTE_PARTNER) {
                    "Demo messages cannot be backed up."
                }
                require(message.createdAtMillis > 0 && message.text.length in 1..MAX_TEXT_CHARS && message.text.isNotBlank()) {
                    "A history message is invalid."
                }
                val textBytes = message.text.toByteArray(Charsets.UTF_8).size
                require(textBytes <= MAX_TEXT_BYTES) { "A history message is too large." }
                encodedSize += 4 + message.sourceId.toByteArray(Charsets.UTF_8).size + 1 + 8 + 4 + textBytes
                require(encodedSize + HEADER_BYTES + TAG_BYTES <= MAX_FILE_BYTES) { "The history backup is too large." }
            }
        }
    }

    private fun encode(snapshot: HistorySnapshot): ByteArray {
        val output = ZeroingByteArrayOutputStream()
        try {
            DataOutputStream(output).use { data ->
                data.writeInt(1)
                writeString(data, snapshot.backupId)
                data.writeLong(snapshot.createdAtMillis)
                data.writeInt(snapshot.conversations.size)
                snapshot.conversations.forEach { conversation ->
                    writeString(data, conversation.sourceId)
                    writeString(data, conversation.title)
                    data.writeInt(conversation.messages.size)
                    conversation.messages.forEach { message ->
                        writeString(data, message.sourceId)
                        data.writeByte(if (message.sender == MessageSender.LOCAL_USER) 0 else 1)
                        data.writeLong(message.createdAtMillis)
                        writeString(data, message.text)
                    }
                }
            }
            return output.toByteArray()
        } finally { output.erase() }
    }

    private fun decode(plaintext: ByteArray): HistorySnapshot {
        val input = DataInputStream(ByteArrayInputStream(plaintext))
        require(input.readInt() == 1) { "The history data version is unsupported." }
        val backupId = readString(input, 32)
        val createdAtMillis = input.readLong()
        val count = input.readInt()
        require(count in 0..MAX_CONVERSATIONS) { "The history conversation count is invalid." }
        var messageCount = 0
        val conversations = List(count) {
            val sourceId = readString(input, 128)
            val title = readString(input, 160)
            val size = input.readInt()
            require(size >= 0 && messageCount + size <= MAX_MESSAGES) { "The history message count is invalid." }
            messageCount += size
            val messages = List(size) {
                val id = readString(input, 128)
                val sender = when (input.readUnsignedByte()) {
                    0 -> MessageSender.LOCAL_USER
                    1 -> MessageSender.REMOTE_PARTNER
                    else -> error("The history sender is invalid.")
                }
                val time = input.readLong()
                val text = readString(input, MAX_TEXT_BYTES)
                HistoryMessage(id, sender, time, text)
            }
            HistoryConversation(sourceId, title, messages)
        }
        require(input.available() == 0) { "The history backup contains unexpected data." }
        return HistorySnapshot(backupId, createdAtMillis, conversations)
    }

    private fun writeString(output: DataOutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        output.writeInt(bytes.size)
        output.write(bytes)
        bytes.fill(0)
    }

    private fun readString(input: DataInputStream, maximum: Int): String {
        val size = input.readInt()
        require(size in 1..maximum && size <= input.available()) { "A history field has an invalid size." }
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } finally { bytes.fill(0) }
    }

    private class ZeroingByteArrayOutputStream : ByteArrayOutputStream() {
        fun erase() { buf.fill(0); reset() }
    }
}
