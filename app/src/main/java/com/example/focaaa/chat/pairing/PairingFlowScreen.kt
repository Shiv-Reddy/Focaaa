package com.example.focaaa.chat.pairing

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun PairingFlowScreen(
    mode: PairingRole,
    coordinator: PairingFlowCoordinator,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onOpenSecureChat: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<PairingSession?>(null) }
    var inviteText by remember { mutableStateOf("") }
    var confirmationText by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var remoteCancelFailed by remember { mutableStateOf(false) }
    var showLocalRemovalWarning by remember { mutableStateOf(false) }
    var liveDraftConsentChecked by remember { mutableStateOf(false) }

    LaunchedEffect(coordinator) {
        val saved = coordinator.currentSession()
        session = saved
        if (saved != null) {
            try {
                session = when {
                    saved.stage == PairingStage.IDENTITY_READY ||
                        saved.stage == PairingStage.JOIN_REQUESTED ||
                        (saved.stage == PairingStage.GROUP_READY && saved.role == PairingRole.INVITER && saved.welcome != null) ->
                        coordinator.advance()
                    saved.stage == PairingStage.VERIFIED_LOCAL || saved.stage == PairingStage.ACTIVE ->
                        coordinator.refresh()
                    else -> saved
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                errorText = error.message?.take(180) ?: "Pairing could not resume. Check the connection and try again."
            }
        }
    }

    fun run(action: suspend () -> PairingSession?) {
        if (busy) return
        scope.launch {
            busy = true
            errorText = null
            remoteCancelFailed = false
            try {
                session = action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                remoteCancelFailed = error is PendingPairingRemoteRevocationFailed
                errorText = error.message?.take(180) ?: "Pairing could not continue. Check the connection and try again."
                session = coordinator.currentSession()
            } finally {
                busy = false
            }
        }
    }

    val current = session
    val isExistingSession = current != null
    val screenTitle = when {
        current?.role == PairingRole.INVITER -> "Create an invite"
        current?.role == PairingRole.JOINER -> "Join an invite"
        mode == PairingRole.INVITER -> "Create an invite"
        else -> "Join an invite"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text("Focaaa", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        Text(screenTitle, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        if (isExistingSession && current?.stage != PairingStage.ACTIVE) {
            TextButton(
                onClick = { run { coordinator.clearPendingPairing(); null } },
                enabled = !busy
            ) { Text("Cancel this pairing") }
        }

        when {
            current == null && mode == PairingRole.INVITER -> {
                Text("Create a one-time invite that expires in five minutes. Share the code directly with your partner.")
                Button(
                    onClick = { run { coordinator.createInvite() } },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Create invite") }
            }
            current == null && mode == PairingRole.JOINER -> {
                Text("Enter the one-time invite code your partner shared with you. It expires after five minutes and can be used once.")
                OutlinedTextField(
                    value = inviteText,
                    onValueChange = { inviteText = it.filter { character -> character.isLetterOrDigit() || character == '-' || character == '_' }.take(32) },
                    label = { Text("Invite code") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Checkbox(checked = liveDraftConsentChecked,
                        onCheckedChange = { liveDraftConsentChecked = it }, enabled = !busy)
                    Text("I agree to share my live typing, edits and deleted text with the invite creator.",
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://focaaa-download.focaaa-free-relay.workers.dev/privacy.html"))) }) {
                        Text("Privacy policy")
                    }
                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://focaaa-download.focaaa-free-relay.workers.dev/terms.html"))) }) {
                        Text("Terms")
                    }
                }
                Button(
                    onClick = { run { coordinator.joinInvite(inviteText.trim(), liveDraftConsentChecked) } },
                    enabled = !busy && inviteText.length == 32 && liveDraftConsentChecked,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Join invite") }
            }
            current?.stage == PairingStage.INVITE_READY -> {
                Text("Share this one-time code with your partner. The invite expires five minutes after it was created.")
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(current.inviteToken.orEmpty(), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(onClick = { copyInvite(context, current.inviteToken.orEmpty()) }) { Text("Copy") }
                            TextButton(onClick = { shareInvite(context, current.inviteToken.orEmpty()) }) { Text("Share") }
                        }
                    }
                }
                Text("Keep this screen available. Focaaa will check when your partner joins.")
                Button(onClick = { run { coordinator.advance() } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Check for partner")
                }
            }
            current?.stage == PairingStage.IDENTITY_READY -> {
                Text(if (current.role == PairingRole.INVITER) "Preparing a short-lived invite…" else "Joining the invite…")
                Button(onClick = { run { coordinator.advance() } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Continue")
                }
            }
            current?.stage == PairingStage.JOIN_REQUESTED -> {
                Text("Your join request is waiting for the inviter to prepare the secure group.")
                Button(onClick = { run { coordinator.advance() } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Check for secure setup")
                }
            }
            current != null && current.stage == PairingStage.GROUP_READY && current.role == PairingRole.INVITER && current.welcome != null -> {
                Text("The group is ready. Focaaa is delivering the one-time encrypted setup to your partner.")
                Button(onClick = { run { coordinator.advance() } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Continue secure setup")
                }
            }
            current?.stage == PairingStage.GROUP_READY || current?.stage == PairingStage.VERIFIED_LOCAL || current?.stage == PairingStage.ACTIVE -> {
                Text("Compare this 12-digit code with your partner in person or over a trusted call. Continue only when every digit matches on both phones.")
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = current.verificationCode.orEmpty().chunked(3).joinToString(" "),
                        modifier = Modifier.padding(20.dp),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                when (current.stage) {
                    PairingStage.GROUP_READY -> {
                        OutlinedTextField(
                            value = confirmationText,
                            onValueChange = { confirmationText = it.filter(Char::isDigit).take(12) },
                            label = { Text("Enter the code you compared") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = { run { coordinator.confirmCode(confirmationText) } },
                            enabled = !busy && confirmationText.length == 12,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Confirm matching code") }
                    }
                    PairingStage.VERIFIED_LOCAL -> {
                        Text(if (current.relayActive) "Both devices confirmed the comparison. Completing the encrypted confirmation handshake…" else "Your confirmation is saved. Waiting for your partner to confirm the same code.")
                        Button(onClick = { run { coordinator.refresh() } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text("Check pairing status")
                        }
                    }
                    PairingStage.ACTIVE -> {
                        Text("Both devices confirmed the same comparison code. Open the private conversation with your Focaaa PIN.")
                        Button(
                            onClick = { current.conversationId?.let(onOpenSecureChat) },
                            enabled = !busy && current.conversationId != null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Open private chat")
                        }
                        Button(onClick = { run { coordinator.refresh() } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text("Refresh secure status")
                        }
                    }
                    else -> Unit
                }
            }
        }

        if (errorText != null) {
            Text(errorText!!, color = MaterialTheme.colorScheme.error)
        }
        if (remoteCancelFailed && isExistingSession) {
            TextButton(onClick = { showLocalRemovalWarning = true }, enabled = !busy) {
                Text("Remove unfinished pairing from this phone")
            }
        }
        if (busy) CircularProgressIndicator()
        TextButton(onClick = onBack, enabled = !busy) { Text("Back") }
    }

    if (showLocalRemovalWarning) {
        AlertDialog(
            onDismissRequest = { showLocalRemovalWarning = false },
            title = { Text("Remove unfinished pairing?") },
            text = { Text("This deletes the unfinished pairing and its encryption keys from this phone. If the relay still has a record, this action cannot revoke it or remove anything from the other phone. Your existing contacts and chats stay on this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    showLocalRemovalWarning = false
                    run { coordinator.clearPendingPairingLocally(); null }
                }) { Text("Remove from this phone") }
            },
            dismissButton = {
                TextButton(onClick = { showLocalRemovalWarning = false }) { Text("Keep pairing") }
            }
        )
    }
}

private fun copyInvite(context: Context, token: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Focaaa invite", token))
}

private fun shareInvite(context: Context, token: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "Focaaa invite code: $token")
    }
    context.startActivity(Intent.createChooser(send, "Share Focaaa invite"))
}
