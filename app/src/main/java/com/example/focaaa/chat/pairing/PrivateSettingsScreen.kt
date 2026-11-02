package com.example.focaaa.chat.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.focaaa.chat.normalizeContactName
import kotlinx.coroutines.launch
import com.example.focaaa.ui.theme.FocaaaPalette

private val Forest = FocaaaPalette.Primary
private val SoftGreen = FocaaaPalette.Pale

@Composable
fun PrivateSettingsScreen(
    privateEntryMinutes: Int,
    contactName: String?,
    onPrivateEntryMinutesChange: (Int) -> Unit,
    onSaveContactName: suspend (String) -> Unit,
    onChangePin: () -> Unit,
    onReviewPrivacyLimits: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var nameDraft by remember { mutableStateOf(contactName.orEmpty()) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var savingName by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(contactName) { nameDraft = contactName.orEmpty() }

    Column(
        modifier = modifier.fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Private settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Private entry timer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Starting the selected duration opens this area after your PIN.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                (1..10).chunked(5).forEach { values ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        values.forEach { value ->
                            val selected = value == privateEntryMinutes
                            androidx.compose.material3.Surface(
                                onClick = { onPrivateEntryMinutesChange(value) },
                                shape = CircleShape,
                                color = if (selected) Forest else SoftGreen,
                                modifier = Modifier.size(48.dp)
                            ) {
                                androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                                    Text("$value", color = if (selected) Color.White else Forest, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (contactName != null) {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Contact name", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "This name is saved on this phone only.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = nameDraft,
                        onValueChange = { nameDraft = it.take(80); nameError = null },
                        label = { Text("Name") },
                        singleLine = true,
                        isError = nameError != null,
                        supportingText = { Text(nameError ?: "Up to 40 characters") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            normalizeContactName(nameDraft)?.let { clean ->
                                scope.launch {
                                    savingName = true
                                    try {
                                        onSaveContactName(clean)
                                        nameDraft = clean
                                        nameError = null
                                    } catch (_: Exception) {
                                        nameError = "The name could not be saved. Try again."
                                    } finally {
                                        savingName = false
                                    }
                                }
                            } ?: run { nameError = "Enter a name from 1 to 40 characters." }
                        },
                        enabled = !savingName && normalizeContactName(nameDraft) != null,
                        colors = ButtonDefaults.buttonColors(containerColor = Forest),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (savingName) "Saving…" else "Save name") }
                }
            }
        }
        TextButton(onClick = onChangePin, modifier = Modifier.fillMaxWidth()) {
            Text("Change access PIN", color = Forest)
        }
        TextButton(onClick = onReviewPrivacyLimits, modifier = Modifier.fillMaxWidth()) {
            Text("Review privacy limits", color = Forest)
        }
    }
}
