package com.example.focaaa.focus

import android.content.Context
import java.time.LocalDate
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Small, local-only state for the ordinary focus surface. It contains no private-chat data. */
class FocusStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("focaaa_focus", Context.MODE_PRIVATE)

    fun tasks(today: LocalDate = LocalDate.now()): List<FocusTask> {
        val saved = decodeTasks(preferences.getString(TASKS, null))
        val current = visibleTasks(saved, today)
        if (current.size != saved.size) saveTasks(current)
        return current
    }

    fun addTask(text: String, today: LocalDate = LocalDate.now()): List<FocusTask> {
        val clean = text.trim()
        require(clean.isNotEmpty() && clean.length <= 120) { "Keep each task to 120 characters." }
        val current = tasks(today)
        check(current.size < 50) { "Complete or remove a task before adding another." }
        return (current + FocusTask(UUID.randomUUID().toString(), clean)).also(::saveTasks)
    }

    fun setCompleted(id: String, completed: Boolean, today: LocalDate = LocalDate.now()): List<FocusTask> =
        tasks(today).map { task ->
            if (task.id == id) task.copy(completedOn = if (completed) today else null) else task
        }.also(::saveTasks)

    fun deleteTask(id: String, today: LocalDate = LocalDate.now()): List<FocusTask> =
        tasks(today).filterNot { it.id == id }.also(::saveTasks)

    fun timer(): FocusTimerState {
        val duration = preferences.getInt(TIMER_DURATION, 25)
        val remaining = preferences.getLong(TIMER_REMAINING, duration * 60_000L)
        val runningUntil = preferences.getLong(TIMER_RUNNING_UNTIL, 0L).takeIf { it > 0L }
        return runCatching { FocusTimerState(duration, remaining, runningUntil) }
            .getOrElse { FocusTimerState.ready(25) }
    }

    fun privateEntryMinutes(): Int = normalizePrivateEntryDuration(
        preferences.getInt(PRIVATE_ENTRY_MINUTES, DEFAULT_PRIVATE_ENTRY_MINUTES)
    )

    fun setPrivateEntryMinutes(minutes: Int) {
        require(minutes in 1..10) { "Choose a private-entry duration from 1 to 10 minutes." }
        check(preferences.edit().putInt(PRIVATE_ENTRY_MINUTES, minutes).commit()) {
            "Could not save the private-entry duration."
        }
    }

    fun resetTimerToDefault() = saveTimer(FocusTimerState.ready(25))

    fun saveTimer(timer: FocusTimerState) {
        check(preferences.edit()
            .putInt(TIMER_DURATION, timer.durationMinutes)
            .putLong(TIMER_REMAINING, timer.remainingMillis)
            .putLong(TIMER_RUNNING_UNTIL, timer.runningUntilMillis ?: 0L)
            .commit()) { "Could not save the focus timer." }
    }

    private fun saveTasks(tasks: List<FocusTask>) {
        val array = JSONArray()
        tasks.forEach { task ->
            array.put(JSONObject()
                .put("id", task.id)
                .put("text", task.text)
                .put("completedOn", task.completedOn?.toString()))
        }
        check(preferences.edit().putString(TASKS, array.toString()).commit()) {
            "Could not save your tasks."
        }
    }

    private fun decodeTasks(raw: String?): List<FocusTask> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until minOf(array.length(), 50)) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    val text = item.optString("text")
                    if (id.isBlank() || text.isBlank() || text.length > 120) continue
                    val date = item.optString("completedOn").takeIf { it.isNotBlank() && it != "null" }
                        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    add(FocusTask(id, text, date))
                }
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val TASKS = "tasks"
        const val TIMER_DURATION = "timer_duration"
        const val TIMER_REMAINING = "timer_remaining"
        const val TIMER_RUNNING_UNTIL = "timer_running_until"
        const val PRIVATE_ENTRY_MINUTES = "private_entry_minutes"
    }
}
