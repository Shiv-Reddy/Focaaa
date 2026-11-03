package com.example.focaaa.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NotificationPanelOpeningTest {
    @Test fun savedPanelOpensBeforeSlowRelayRefresh() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val relayMayFinish = CompletableDeferred<Unit>()
        val savedPosted = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        try {
            withTimeout(1_000) { openNotificationPanel(
                scope = scope,
                showSaved = { events.add("saved"); savedPosted.complete(Unit) },
                refreshFromRelay = { events.add("sync"); relayMayFinish.await() },
                showRefreshed = { events.add("updated") }
            ) }
            withTimeout(1_000) { savedPosted.await() }
            assertEquals("saved", events.first())
            assertFalse(relayMayFinish.isCompleted)
        } finally {
            scope.cancel()
        }
    }

    @Test fun relayFailureKeepsAlreadyPostedPanelAvailable() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val events = mutableListOf<String>()
        try {
            openNotificationPanel(
                scope = scope,
                showSaved = { events.add("saved") },
                refreshFromRelay = { error("network unavailable") },
                showRefreshed = { events.add("updated") }
            )
            assertEquals(listOf("saved"), events)
        } finally {
            scope.cancel()
        }
    }
}
