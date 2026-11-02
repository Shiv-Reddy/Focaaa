package com.example.focaaa.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun DemoConversationScreen(
    repository: ConversationRepository,
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    val messages by repository.messages.collectAsState(initial = emptyList())
    var draft by remember { mutableStateOf("") }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Delete all local demo data?") },
            text = { Text("This permanently deletes every demo message from this device. The synthetic examples will not be restored automatically.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteAll = false
                    scope.launch { repository.deleteAllLocalData() }
                }) { Text("Delete all") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") } }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = onBack) { Text("Back to pairing options") }
        Text(
            text = repository.conversation.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("DEMO - SYNTHETIC ONLY", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                Text(
                    "Synthetic messages are stored encrypted on this device. They are not sent to a partner; Focaaa does not yet provide end-to-end encryption.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        TextButton(onClick = { confirmDeleteAll = true }, modifier = Modifier.align(Alignment.End)) {
            Text("Delete all local demo data")
        }
        saveError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        if (messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No demo messages yet. Write one below to try the layout.", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    val localMessage = message.sender == MessageSender.LOCAL_USER
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (localMessage) Arrangement.End else Arrangement.Start
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth(0.88f),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (localMessage) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    if (localMessage) "You - demo" else "Demo partner",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(message.text, style = MaterialTheme.typography.bodyLarge)
                                TextButton(
                                    onClick = { scope.launch { repository.deleteMessage(message.id) } },
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Text("Delete demo message")
                                }
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it; saveError = null },
                modifier = Modifier.weight(1f),
                label = { Text("Add a demo message") },
                maxLines = 4
            )
            Button(
                onClick = {
                    val message = draft
                    saving = true
                    saveError = null
                    scope.launch {
                        try {
                            repository.sendDemoMessage(message)
                            draft = ""
                        } catch (_: Exception) {
                            saveError = "The demo message was not saved. Check local storage and try again."
                        } finally {
                            saving = false
                        }
                    }
                },
                enabled = draft.isNotBlank() && !saving
            ) {
                Text(if (saving) "Saving…" else "Add")
            }
        }
    }
}
