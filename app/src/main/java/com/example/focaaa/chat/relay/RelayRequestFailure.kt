package com.example.focaaa.chat.relay

/** A definitive relay response: this conversation cannot deliver more messages. */
internal class PairEndedException : IllegalStateException("This private connection has ended.")

internal fun relayRequestFailure(status: Int, code: String?, message: String?): IllegalStateException =
    if (status == 410 && code == "pair-revoked") PairEndedException()
    else IllegalStateException(message?.takeIf(String::isNotBlank) ?: "The private relay returned HTTP $status.")
