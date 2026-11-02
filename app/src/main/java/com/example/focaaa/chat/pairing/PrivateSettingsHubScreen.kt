package com.example.focaaa.chat.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.example.focaaa.ui.theme.FocaaaPalette

private val Forest = FocaaaPalette.Primary
private val Ink = FocaaaPalette.Ink
private val Muted = FocaaaPalette.Muted
private val Canvas = FocaaaPalette.Canvas
private val Rule = FocaaaPalette.Line

@Composable
fun PrivateSettingsHubScreen(
    privateEntryMinutes: Int,
    hasActivePartner: Boolean,
    onPrivateEntryMinutesChange: (Int) -> Unit,
    onContacts: () -> Unit,
    onCreateInvite: () -> Unit,
    onJoinInvite: () -> Unit,
    onAddTile: () -> Unit,
    onChangePin: () -> Unit,
    onReviewPrivacy: () -> Unit,
    onBackupHistory: () -> Unit,
    onRestoreHistory: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showAddPersonChoices by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxSize().background(Canvas).verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 22.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("focaaa.", color = Forest, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Done", color = Forest) }
        }
        Spacer(Modifier.height(17.dp))
        Text("Private settings", color = Ink, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)
        Text("Manage your conversation and access.", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 5.dp))
        Spacer(Modifier.height(28.dp))

        SectionTitle("Partner", "Conversation and saved history")
        Spacer(Modifier.height(12.dp))
        SettingsLink("Conversation and name", "View your partner or saved history", onContacts)
        HorizontalDivider(color = Rule)
        if (!hasActivePartner) {
            SettingsLink("Add partner", "Create or join an invite") { showAddPersonChoices = true }
        }
        Spacer(Modifier.height(28.dp))

        Text("Private entry", color = Ink, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
        Text("The focus timer that opens your private space.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
        Surface(shape = RoundedCornerShape(18.dp), color = FocaaaPalette.Deep, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(start = 22.dp, top = 20.dp, end = 22.dp, bottom = 16.dp)) {
                Text("ENTRY TIMER", color = Color.White.copy(alpha = 0.72f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("%02d".format(privateEntryMinutes), color = Color.White, fontSize = 66.sp, lineHeight = 72.sp, fontWeight = FontWeight.Light)
                    Text("MINUTES", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, modifier = Modifier.padding(start = 10.dp, bottom = 13.dp))
                }
                Slider(
                    value = privateEntryMinutes.toFloat(),
                    onValueChange = { onPrivateEntryMinutesChange(it.roundToInt().coerceIn(1, 10)) },
                    valueRange = 1f..10f,
                    steps = 8,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color(0xFFBDE5DA),
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                        activeTickColor = Forest,
                        inactiveTickColor = Color.White.copy(alpha = 0.45f)
                    )
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("1 min", color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp)
                    Text("10 min", color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(28.dp))

        SectionTitle("Tools", "Access from your phone.")
        Spacer(Modifier.height(12.dp))
        SettingsLink("Quick Settings tile", "Add a shortcut to the phone panel", onAddTile)
        HorizontalDivider(color = Rule)
        SettingsLink("Change PIN", "Update your private area PIN", onChangePin)
        Spacer(Modifier.height(28.dp))

        SectionTitle("Saved history", "Keep an encrypted copy of past conversations.")
        Spacer(Modifier.height(12.dp))
        SettingsLink("Back up history", "Save encrypted messages; media files are excluded", onBackupHistory)
        HorizontalDivider(color = Rule)
        SettingsLink("Restore history", "Import a saved file as read-only contacts", onRestoreHistory)
        Spacer(Modifier.height(28.dp))

        SectionTitle("Privacy & how it works", "Understand what stays on this phone.")
        Spacer(Modifier.height(12.dp))
        SettingsLink("Read the privacy guide", "Pairing, storage, and limits", onReviewPrivacy)
        Spacer(Modifier.height(28.dp))
        Text(
            "Your private area closes when you leave Focaaa.",
            color = Muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 18.dp)
        )
    }

    if (showAddPersonChoices && !hasActivePartner) {
        AlertDialog(
            onDismissRequest = { showAddPersonChoices = false },
            title = { Text("Add a partner", color = Ink) },
            text = { Text("This release supports one active partner at a time.", color = Muted) },
            confirmButton = {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Button(
                        onClick = { showAddPersonChoices = false; onCreateInvite() },
                        colors = ButtonDefaults.buttonColors(containerColor = Forest),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Create invite") }
                    OutlinedButton(
                        onClick = { showAddPersonChoices = false; onJoinInvite() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Join invite", color = Forest) }
                }
            },
            dismissButton = { TextButton(onClick = { showAddPersonChoices = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Text(title, color = Ink, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
    Text(subtitle, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun SettingsLink(title: String, detail: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 17.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(detail, color = Muted, fontSize = 12.sp)
        }
        Text("›", color = Forest, fontSize = 28.sp, modifier = Modifier.padding(start = 14.dp))
    }
}
