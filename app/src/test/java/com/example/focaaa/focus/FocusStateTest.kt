package com.example.focaaa.focus

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusStateTest {
    @Test
    fun configuredPrivateDurationMatchesOnlyThatTimer() {
        assertTrue(isPrivateDuration(5, 5))
        assertFalse(isPrivateDuration(3, 5))
        assertFalse(isPrivateDuration(6, 5))
    }

    @Test
    fun invalidPrivateDurationFallsBackToThreeMinutes() {
        assertEquals(3, normalizePrivateEntryDuration(0))
        assertEquals(3, normalizePrivateEntryDuration(11))
        assertEquals(1, normalizePrivateEntryDuration(1))
        assertEquals(10, normalizePrivateEntryDuration(10))
    }

    @Test
    fun onlyExactlyThreeMinutesOpensPrivateEntry() {
        assertFalse(isPrivateDuration(2))
        assertTrue(isPrivateDuration(3))
        assertFalse(isPrivateDuration(4))
        assertFalse(isPrivateDuration(25))
    }

    @Test
    fun timerSurvivesResumeAndStopsAtZero() {
        val start = 1_000_000L
        val running = FocusTimerState.ready(25).resumeAt(start)

        assertEquals(24 * 60_000L, running.remainingAt(start + 60_000L))
        val paused = running.pauseAt(start + 90_000L)
        assertEquals(23 * 60_000L + 30_000L, paused.remainingAt(start + 600_000L))
        val resumed = paused.resumeAt(start + 600_000L)
        assertEquals(0L, resumed.remainingAt(start + 25 * 60_000L + 600_000L))
        assertEquals(25 * 60_000L, resumed.reset().remainingAt(start))
    }

    @Test
    fun unfinishedTasksCarryForwardAndOldCompletedTasksLeaveToday() {
        val yesterday = LocalDate.of(2026, 10, 26)
        val today = yesterday.plusDays(1)
        val tasks = listOf(
            FocusTask("1", "Unfinished", null),
            FocusTask("2", "Done yesterday", yesterday),
            FocusTask("3", "Done today", today)
        )

        assertEquals(listOf("1", "3"), visibleTasks(tasks, today).map(FocusTask::id))
    }
}
