package com.example.focaaa.chat.pairing

import com.example.focaaa.chat.crypto.CryptoFingerprint
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Derives a stable human-comparable value from authenticated MLS group identity data.
 * This detects mismatched identities during comparison; it is not a secret or an
 * authentication mechanism on its own.
 */
object PairingVerificationCodeDerivation {
    private val domain = "com.example.focaaa/pairing-verification/v1\u0000".toByteArray(Charsets.US_ASCII)
    private val codeModulus = BigInteger("1000000000000")
    private const val credentialFingerprintBytes = 32
    private const val maxGroupIdBytes = 65_535
    private const val displayCodeDigits = 12

    /**
     * [stableGroupId] must be the MLS group ID. Each member fingerprint must be SHA-256 of that
     * member's validated MLS Credential structure in canonical TLS encoding, excluding the
     * epoch-specific LeafNode signature and encryption/signature key material.
     */
    fun derive(
        stableGroupId: ByteArray,
        firstMemberCredentialFingerprint: ByteArray,
        secondMemberCredentialFingerprint: ByteArray
    ): CryptoFingerprint {
        require(stableGroupId.isNotEmpty() && stableGroupId.size <= maxGroupIdBytes) {
            "MLS group ID must contain between 1 and 65535 bytes."
        }
        require(firstMemberCredentialFingerprint.size == credentialFingerprintBytes)
        require(secondMemberCredentialFingerprint.size == credentialFingerprintBytes)
        require(!firstMemberCredentialFingerprint.contentEquals(secondMemberCredentialFingerprint)) {
            "A verified pairing requires two distinct member credentials."
        }

        val first = firstMemberCredentialFingerprint.copyOf()
        val second = secondMemberCredentialFingerprint.copyOf()
        val (lower, upper) = if (compareUnsigned(first, second) <= 0) first to second else second to first

        val input = ByteArrayOutputStream()
        DataOutputStream(input).use { output ->
            output.write(domain)
            output.writeInt(stableGroupId.size)
            output.write(stableGroupId)
            output.writeInt(lower.size)
            output.write(lower)
            output.writeInt(upper.size)
            output.write(upper)
        }

        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        val decimalValue = BigInteger(1, digest.copyOfRange(0, java.lang.Long.BYTES))
            .mod(codeModulus)
            .toString()
            .padStart(displayCodeDigits, '0')
        return CryptoFingerprint(displayCode = decimalValue, encoded = digest)
    }

    private fun compareUnsigned(left: ByteArray, right: ByteArray): Int {
        for (index in left.indices) {
            val difference = (left[index].toInt() and 0xff) - (right[index].toInt() and 0xff)
            if (difference != 0) return difference
        }
        return left.size.compareTo(right.size)
    }
}
