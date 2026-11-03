package com.example.focaaa.chat.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** JNI access to OpenMLS operations. All returned snapshots remain untrusted until authenticated by the encrypted state store. */
class NativeOpenMls {
    external fun createIdentityNative(identityCredential: ByteArray): ByteArray
    external fun createKeyPackageNative(snapshot: ByteArray): ByteArray
    external fun addMemberNative(snapshot: ByteArray, keyPackage: ByteArray): ByteArray
    external fun joinGroupNative(snapshot: ByteArray, welcome: ByteArray, ratchetTree: ByteArray): ByteArray
    external fun fingerprintNative(snapshot: ByteArray): ByteArray
    external fun liveDraftKeyNative(snapshot: ByteArray): ByteArray
    external fun encryptNative(snapshot: ByteArray, plaintext: ByteArray): ByteArray
    external fun rotateGroupStateNative(snapshot: ByteArray): ByteArray
    external fun removePeerNative(snapshot: ByteArray, peerCredential: ByteArray): ByteArray
    external fun processMessageNative(snapshot: ByteArray, message: ByteArray): ByteArray

    companion object {
        private const val MAX_NATIVE_RESULT_BYTES = 32 * 1024 * 1024
        private val MAGIC = "FOCAARES".toByteArray(Charsets.US_ASCII)

        init {
            System.loadLibrary("focaaa_openmls")
        }

        /** Decodes the bounded, versioned field container returned by the Rust JNI functions. */
        fun decodeResult(encoded: ByteArray, expectedFieldCount: Int): List<ByteArray> {
            require(encoded.size in 16..MAX_NATIVE_RESULT_BYTES) { "Native MLS result length is invalid." }
            val input = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)
            val magic = ByteArray(MAGIC.size)
            input.get(magic)
            require(magic.contentEquals(MAGIC)) { "Native MLS result header is invalid." }
            require(input.int == 1) { "Native MLS result version is unsupported." }
            val count = input.int
            require(count == expectedFieldCount && count in 1..16) { "Native MLS result field count is invalid." }
            val fields = ArrayList<ByteArray>(count)
            repeat(count) {
                require(input.remaining() >= Int.SIZE_BYTES) { "Native MLS result is truncated." }
                val size = input.int
                require(size >= 0 && size <= input.remaining()) { "Native MLS result field length is invalid." }
                val field = ByteArray(size)
                input.get(field)
                fields += field
            }
            require(!input.hasRemaining()) { "Native MLS result contains trailing bytes." }
            return fields
        }
    }
}
