package com.example.focaaa.chat.pairing

import com.example.focaaa.chat.relay.PairEndedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Returns control after the encrypted local queue is durable; relay delivery is retryable. */
internal class QueuedChatDelivery(private val scope: CoroutineScope) {
    private val pending = Channel<suspend () -> Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (deliver in pending) {
                for (attempt in 0..2) {
                    try {
                        withTimeout(15_000) { deliver() }
                        break
                    } catch (_: TimeoutCancellationException) {
                        // The durable queue is retried here, by visible sync, and on app reopen.
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: PairEndedException) {
                        break
                    } catch (_: Exception) {
                        // No message text or relay credentials are logged on failed delivery.
                    }
                    if (attempt < 2) delay(5_000)
                }
            }
        }
    }

    suspend fun enqueue(save: suspend () -> Unit, deliver: suspend () -> Unit) {
        save()
        check(pending.trySend(deliver).isSuccess) { "Private delivery is unavailable; the message remains queued." }
    }
}
