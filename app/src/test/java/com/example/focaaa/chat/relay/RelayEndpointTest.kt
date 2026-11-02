package com.example.focaaa.chat.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RelayEndpointTest {
    @Test fun requiresConfiguredHttpsEndpoint() {
        assertThrows(IllegalStateException::class.java) { RelayEndpoint.normalize("") }
        assertThrows(IllegalArgumentException::class.java) { RelayEndpoint.normalize("http://relay.example") }
        assertThrows(IllegalArgumentException::class.java) { RelayEndpoint.normalize("https://relay.example/path") }
        assertEquals("https://relay.example", RelayEndpoint.normalize(" https://relay.example/ "))
    }

    @Test fun missingEndpointFailsBeforeFirebaseSignIn() {
        val task = WorkerRelayGateway("").ensureAnonymousSession()
        assertEquals(true, task.isComplete)
        assertEquals("The private relay is not set up yet. Connect the free backend first.",
            task.exception?.message)
    }
}
