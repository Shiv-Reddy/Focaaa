package com.example.focaaa.chat.pairing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueuedChatDeliveryTest {
    @Test fun sendFinishesAfterDurableQueueWithoutWaitingForRelay() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val relayStarted = CompletableDeferred<Unit>()
        val relayFinish = CompletableDeferred<Unit>()
        var saved = false
        try {
            withTimeout(1_000) {
                QueuedChatDelivery(scope).enqueue(
                    save = { saved = true },
                    deliver = { relayStarted.complete(Unit); relayFinish.await() }
                )
            }
            assertTrue(saved)
            withTimeout(1_000) { relayStarted.await() }
            assertFalse(relayFinish.isCompleted)
        } finally {
            scope.cancel()
        }
    }

    @Test fun failedLocalSaveDoesNotAttemptRelayDelivery() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var deliveryAttempted = false
        try {
            try {
                QueuedChatDelivery(scope).enqueue(
                    save = { error("local save failed") },
                    deliver = { deliveryAttempted = true }
                )
                error("Expected local save failure")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message == "local save failed")
            }
            assertFalse(deliveryAttempted)
        } finally {
            scope.cancel()
        }
    }

    @Test fun rapidSendsCoalesceRelayRefreshesWithoutDroppingLocalSaves() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sender = QueuedChatDelivery(scope)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val latestDelivered = CompletableDeferred<Unit>()
        val saved = mutableListOf<Int>()
        val delivered = mutableListOf<Int>()
        try {
            sender.enqueue(
                save = { saved.add(1) },
                deliver = { firstStarted.complete(Unit); releaseFirst.await(); delivered.add(1) }
            )
            withTimeout(1_000) { firstStarted.await() }
            sender.enqueue(save = { saved.add(2) }, deliver = { delivered.add(2) })
            sender.enqueue(save = { saved.add(3) }, deliver = { delivered.add(3); latestDelivered.complete(Unit) })
            releaseFirst.complete(Unit)
            withTimeout(1_000) { latestDelivered.await() }
            delay(50)
            assertEquals(listOf(1, 2, 3), saved)
            assertEquals(listOf(1, 3), delivered)
        } finally {
            scope.cancel()
        }
    }
}
