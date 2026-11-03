package com.example.focaaa.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Show durable local chat immediately; a slow relay must not delay the tile panel. */
internal suspend fun openNotificationPanel(
    scope: CoroutineScope,
    showSaved: suspend () -> Unit,
    refreshFromRelay: suspend () -> Unit,
    showRefreshed: suspend () -> Unit
) {
    showSaved()
    scope.launch {
        try {
            refreshFromRelay()
            showRefreshed()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Saved messages and Reply remain available while the relay recovers.
        }
    }
}
