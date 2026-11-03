package com.example.focaaa.chat.live

/** Applies authenticated edits strictly once and in sequence. */
class RemoteDraftSession(val sessionId: String) {
    var text: String = ""
        private set
    var nextSequence: Long = 1
        private set

    fun apply(edit: DraftEdit): Boolean {
        if (edit.sequence < nextSequence) return false
        require(edit.sequence == nextSequence) { "A live draft edit is missing." }
        text = edit.applyTo(text)
        nextSequence++
        return true
    }
}
