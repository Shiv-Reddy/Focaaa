package com.example.focaaa.focus

import java.time.LocalDate

data class FocusTask(
    val id: String,
    val text: String,
    val completedOn: LocalDate? = null
)

fun visibleTasks(tasks: List<FocusTask>, today: LocalDate): List<FocusTask> =
    tasks.filter { it.completedOn == null || it.completedOn == today }

const val DEFAULT_PRIVATE_ENTRY_MINUTES = 3

fun normalizePrivateEntryDuration(minutes: Int): Int =
    minutes.takeIf { it in 1..10 } ?: DEFAULT_PRIVATE_ENTRY_MINUTES

fun isPrivateDuration(minutes: Int, privateEntryMinutes: Int = DEFAULT_PRIVATE_ENTRY_MINUTES): Boolean =
    minutes == normalizePrivateEntryDuration(privateEntryMinutes)

data class FocusTimerState(
    val durationMinutes: Int,
    val remainingMillis: Long,
    val runningUntilMillis: Long? = null
) {
    init {
        require(durationMinutes in 1..180)
        require(remainingMillis in 0..durationMinutes * 60_000L)
    }

    fun remainingAt(nowMillis: Long): Long =
        (runningUntilMillis?.minus(nowMillis) ?: remainingMillis)
            .coerceIn(0L, durationMinutes * 60_000L)

    fun pauseAt(nowMillis: Long): FocusTimerState =
        copy(remainingMillis = remainingAt(nowMillis), runningUntilMillis = null)

    fun resumeAt(nowMillis: Long): FocusTimerState =
        if (remainingMillis == 0L) this else copy(runningUntilMillis = nowMillis + remainingMillis)

    fun reset(): FocusTimerState = ready(durationMinutes)

    companion object {
        fun ready(durationMinutes: Int): FocusTimerState =
            FocusTimerState(durationMinutes, durationMinutes * 60_000L)
    }
}
