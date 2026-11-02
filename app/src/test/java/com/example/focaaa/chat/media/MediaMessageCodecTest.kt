package com.example.focaaa.chat.media

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaMessageCodecTest {
    private val descriptor = MediaDescriptor(
        MediaKind.PHOTO, "j", "123e4567-e89b-12d3-a456-426614174000",
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() }),
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(12) { it.toByte() }),
        "a".repeat(64), 1024, 1040
    )

    @Test fun descriptorRoundTripsAndNotificationUsesOnlyGenericLabel() {
        val wire = MediaMessageCodec.encode(descriptor)
        assertEquals(descriptor, MediaMessageCodec.decode(wire))
        assertEquals("Photo", MediaMessageCodec.preview(wire))
        assertEquals("Hello", MediaMessageCodec.preview("Hello"))
        assertNull(MediaMessageCodec.decode("Hello"))
    }

    @Test fun invalidTypeSizeKeyAndTruncationAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            MediaMessageCodec.encode(descriptor.copy(plaintextSize = MediaMessageCodec.MAX_PHOTO_BYTES + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MediaMessageCodec.encode(descriptor.copy(keyBase64 = "bad"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MediaMessageCodec.encode(descriptor.copy(kind = MediaKind.VIDEO))
        }
        assertEquals("Media unavailable", MediaMessageCodec.preview(MediaMessageCodec.PREFIX + "broken"))
    }
}
