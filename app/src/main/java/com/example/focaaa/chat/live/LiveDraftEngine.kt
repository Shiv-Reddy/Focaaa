package com.example.focaaa.chat.live

/** Per-visible-chat consent gate; it holds no PIN or other app input. */
class LiveDraftEngine {
    var sessionId: String? = null
        private set
    var currentText: String = ""
        private set
    var nextSequence: Long = 1
        private set

    fun start(newSessionId: String, currentComposerText: String, atMillis: Long): DraftEdit? {
        require(newSessionId.isNotBlank() && currentComposerText.length <= DraftEdit.MAX_DRAFT_CHARS)
        sessionId = newSessionId
        currentText = ""
        nextSequence = 1
        return textChanged(currentComposerText, atMillis)
    }

    fun textChanged(after: String, atMillis: Long): DraftEdit? {
        if (sessionId == null || after == currentText) return null
        val edit = DraftEdit.between(currentText, after, nextSequence, atMillis)
        currentText = after
        nextSequence++
        return edit
    }

    fun stop() {
        sessionId = null
        currentText = ""
        nextSequence = 1
    }
}
