package com.example.focaaa.chat.crypto

/** Debug-only entry point for manually exercising the native compatibility spike. */
class OpenMlsCompatibilityProbe {
    external fun runNative(): String

    /** Creates two local MLS members and checks welcome, snapshot restore, and bidirectional messages. */
    external fun runTwoMemberSnapshotProbe(): String

    /** Creates an unencrypted snapshot for compatibility-spike inspection only; never persist it as-is. */
    external fun createSnapshotNative(): ByteArray

    /** Restores all OpenMLS typed records and fails closed on malformed or inconsistent snapshots. */
    external fun restoreSnapshotNative(snapshot: ByteArray): String

    companion object {
        init {
            System.loadLibrary("focaaa_openmls")
        }
    }
}
