package com.example.focaaa.chat.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.focaaa.chat.ConversationContact
import com.example.focaaa.chat.relay.RelayServiceGeneration
import com.example.focaaa.ui.theme.FocaaaPalette

@Composable
fun ContactListScreen(
    contacts: List<ConversationContact>,
    onOpen: (String) -> Unit,
    onAddPerson: () -> Unit,
    onRename: (String) -> Unit,
    onUnpair: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf<ConversationContact?>(null) }
    Column(modifier.fillMaxSize().background(FocaaaPalette.Canvas).padding(horizontal = 22.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Your private space", style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold, color = FocaaaPalette.Ink)
        Text("One active partner at a time. Saved history stays read only.",
            color = FocaaaPalette.Muted, style = MaterialTheme.typography.bodyMedium)
        if (!hasActivePartner(contacts)) {
            Button(onClick = onAddPerson, modifier = Modifier.fillMaxWidth()) { Text("Add partner") }
        }
        if (contacts.isEmpty()) {
            Card(shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No partner yet", fontWeight = FontWeight.SemiBold)
                    Text("Add a partner to start an encrypted conversation.", color = FocaaaPalette.Muted)
                }
            }
        } else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(contacts, key = { it.conversationId }) { contact ->
                Card(onClick = { onOpen(contact.conversationId) }, shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(42.dp).background(FocaaaPalette.Pale, CircleShape),
                            contentAlignment = Alignment.Center) {
                            Text(contact.displayName.trim().take(1).uppercase(), color = FocaaaPalette.Primary,
                                fontWeight = FontWeight.Bold)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(contact.displayName, color = FocaaaPalette.Ink, fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (contact.isRestored) "Restored history · read only"
                                else if (RelayServiceGeneration.isCurrent(context, contact.conversationId)) "Private conversation"
                                else "Saved history · reconnect to message",
                                style = MaterialTheme.typography.bodySmall,
                                color = FocaaaPalette.Muted
                            )
                        }
                        TextButton(onClick = { selected = contact }) { Text("Manage") }
                    }
                }
            }
        }
    }
    selected?.let { contact ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(contact.displayName) },
            text = { Text(if (contact.isRestored) "This removes the restored history from this phone. It does not affect an active pair or the backup file." else "Unpairing stops relay delivery and deletes this phone’s local conversation and encryption state. The other person’s saved history is not deleted.") },
            confirmButton = { TextButton(onClick = { selected = null; onUnpair(contact.conversationId) }) { Text(if (contact.isRestored) "Remove history" else "Unpair") } },
            dismissButton = { TextButton(onClick = { selected = null; onRename(contact.conversationId) }) { Text("Rename") } }
        )
    }
}
