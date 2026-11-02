package com.example.focaaa.chat.pairing

import kotlinx.coroutines.withTimeoutOrNull

internal class ChatSyncDeadlineExceeded : Exception("Private chat refresh timed out; Focaaa will retry.")

/** Convert only our own deadline into a retryable error; external cancellation still propagates. */
internal suspend fun <T : Any> boundedChatSync(timeoutMillis: Long, block: suspend () -> T): T =
    withTimeoutOrNull(timeoutMillis) { block() } ?: throw ChatSyncDeadlineExceeded()
