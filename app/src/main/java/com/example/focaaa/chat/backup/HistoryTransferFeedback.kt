package com.example.focaaa.chat.backup

/** System-picker callbacks may run after the Activity has returned to the public focus home. */
internal enum class HistoryTransferOutcome {
    SAVED, SAVE_FAILED, IMPORTED, IMPORT_FAILED, INTERRUPTED
}

internal fun publicHistoryTransferToast(outcome: HistoryTransferOutcome): String = when (outcome) {
    HistoryTransferOutcome.SAVED -> "File saved."
    HistoryTransferOutcome.SAVE_FAILED -> "Could not save file."
    HistoryTransferOutcome.IMPORTED -> "File imported."
    HistoryTransferOutcome.IMPORT_FAILED -> "Could not import file."
    HistoryTransferOutcome.INTERRUPTED -> "File operation interrupted."
}
