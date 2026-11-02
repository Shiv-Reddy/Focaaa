package com.example.focaaa.focus

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import com.example.focaaa.ui.theme.FocaaaPalette

private val Forest = FocaaaPalette.Primary
private val Ink = FocaaaPalette.Ink
private val Muted = FocaaaPalette.Muted
private val Canvas = FocaaaPalette.Canvas
private val Pale = FocaaaPalette.Pale

@Composable
fun FocusHomeScreen(
    store: FocusStore,
    privateEntryMinutes: Int,
    onPrivateEntry: () -> Unit,
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var timer by remember(store) { mutableStateOf(store.timer()) }
    var tasks by remember(store) { mutableStateOf(store.tasks()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var customMinutes by remember { mutableStateOf("") }
    var taskText by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var today by remember { mutableStateOf(LocalDate.now()) }

    LaunchedEffect(store) {
        while (true) {
            now = System.currentTimeMillis()
            val currentDay = LocalDate.now()
            if (currentDay != today) {
                today = currentDay
                tasks = store.tasks(currentDay)
            }
            delay(500)
        }
    }

    fun setTimer(next: FocusTimerState) {
        store.saveTimer(next)
        timer = next
    }

    val remaining = timer.remainingAt(now)
    val totalSeconds = (remaining + 999L) / 1_000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val isRunning = timer.runningUntilMillis != null && remaining > 0L
    val completedTasks = tasks.count { it.completedOn == today }
    val dateLabel = remember(today) {
        today.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()))
    }

    Column(
        modifier = modifier.fillMaxSize().background(Canvas).verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("focaaa.", color = Forest, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(dateLabel, color = Muted, fontSize = 13.sp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Your focus, today", color = Ink, fontSize = 30.sp, lineHeight = 35.sp, fontWeight = FontWeight.Bold)
            Text(
                if (tasks.isEmpty()) "Start with one task or a quiet timer."
                else "$completedTasks of ${tasks.size} tasks complete",
                color = Muted, fontSize = 15.sp
            )
        }

        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Focus session", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text("${timer.durationMinutes} min", color = Muted, fontSize = 13.sp)
                }
                Box(
                    modifier = Modifier.size(174.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val stroke = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round)
                        drawArc(Pale, -90f, 360f, useCenter = false, style = stroke)
                        val fraction = (remaining.toFloat() / (timer.durationMinutes * 60_000f))
                            .coerceIn(0f, 1f)
                        drawArc(Forest, -90f, 360f * fraction, useCenter = false, style = stroke)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("%02d:%02d".format(minutes, seconds), color = Ink, fontSize = 42.sp, fontWeight = FontWeight.Bold)
                        Text(if (isRunning) "In focus" else if (remaining == 0L) "Session complete" else "Ready to begin", color = Muted, fontSize = 12.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    listOf(25, 45).forEach { duration ->
                        val selected = timer.durationMinutes == duration
                        Box(
                            modifier = Modifier.background(if (selected) Forest else Pale, RoundedCornerShape(12.dp))
                                .clickable { setTimer(FocusTimerState.ready(duration)); message = "" }
                                .padding(horizontal = 16.dp, vertical = 9.dp)
                        ) {
                            Text("$duration min", color = if (selected) Color.White else Forest, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = customMinutes,
                        onValueChange = { value -> if (value.length <= 3 && value.all(Char::isDigit)) customMinutes = value },
                        label = { Text("Custom minutes") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        val duration = customMinutes.toIntOrNull()
                        if (duration == null || duration !in 1..180) {
                            message = "Choose 1 to 180 minutes."
                        } else {
                            setTimer(FocusTimerState.ready(duration))
                            message = ""
                        }
                    }) { Text("Set", color = Forest) }
                }
                Button(
                    onClick = {
                        if (isRunning) {
                            setTimer(timer.pauseAt(now))
                        } else if (isPrivateDuration(timer.durationMinutes, privateEntryMinutes)) {
                            onPrivateEntry()
                        } else {
                            val resumable = if (remaining == 0L) timer.reset() else timer.pauseAt(now)
                            setTimer(resumable.resumeAt(now))
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Forest),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text(if (isRunning) "Pause focus" else if (remaining == 0L) "Start again" else "Start focus") }
                if (remaining < timer.durationMinutes * 60_000L || isRunning) {
                    TextButton(onClick = { setTimer(timer.reset()) }) { Text("Reset timer", color = Muted) }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Today’s tasks", color = Ink, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("$completedTasks/${tasks.size} done", color = Muted, fontSize = 13.sp)
        }
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (tasks.isEmpty()) Text("What would you like to finish today?", color = Muted, modifier = Modifier.padding(10.dp))
                tasks.forEachIndexed { index, task ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Checkbox(
                            checked = task.completedOn != null,
                            onCheckedChange = { checked -> tasks = store.setCompleted(task.id, checked, today) }
                        )
                        Text(
                            task.text,
                            color = if (task.completedOn != null) Muted else Ink,
                            textDecoration = if (task.completedOn != null) TextDecoration.LineThrough else null,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { tasks = store.deleteTask(task.id, today) },
                            modifier = Modifier.semantics { contentDescription = "Remove task: ${task.text}" }
                        ) { Text("×", color = Muted, fontSize = 22.sp) }
                    }
                    if (index != tasks.lastIndex) HorizontalDivider(color = FocaaaPalette.Line)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = taskText,
                        onValueChange = { if (it.length <= 120) taskText = it },
                        label = { Text("Add a task") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        if (isPrivateSettingsShortcut(taskText)) {
                            taskText = ""
                            message = ""
                            onOpenSettings()
                        } else {
                            runCatching { tasks = store.addTask(taskText, today) }
                                .onSuccess { taskText = ""; message = "" }
                                .onFailure { message = it.message ?: "Could not add task." }
                        }
                    }) { Text("Add", color = Forest) }
                }
            }
        }
        if (message.isNotBlank()) Text(message, color = Forest, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
    }
}

internal fun isPrivateSettingsShortcut(value: String): Boolean = value.trim().equals("settings", ignoreCase = true)
