package com.example.focaaa

import android.os.Bundle
import android.view.WindowManager
import android.os.Build
import android.app.StatusBarManager
import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.firstOrNull
import com.example.focaaa.ui.theme.FocaaaTheme
import com.example.focaaa.chat.ConversationRepository
import com.example.focaaa.chat.ConversationContact
import com.example.focaaa.chat.ChatSessionNotification
import com.example.focaaa.chat.DemoConversationScreen
import com.example.focaaa.chat.data.RoomConversationRepository
import com.example.focaaa.chat.backup.HistoryBackupCodec
import com.example.focaaa.chat.backup.HistoryTransferOutcome
import com.example.focaaa.chat.backup.publicHistoryTransferToast
import com.example.focaaa.chat.pairing.PairingFlowCoordinator
import com.example.focaaa.chat.pairing.PairingFlowScreen
import com.example.focaaa.chat.pairing.PairingRole
import com.example.focaaa.chat.pairing.PairingStage
import com.example.focaaa.chat.pairing.canContinuePinGateAttempt
import com.example.focaaa.chat.pairing.PrivateSettingsScreen
import com.example.focaaa.chat.pairing.PrivateSettingsHubScreen
import com.example.focaaa.chat.pairing.ContactListScreen
import com.example.focaaa.chat.pairing.hasActivePartner
import com.example.focaaa.chat.pairing.SecureConversationScreen
import com.example.focaaa.chat.relay.RelayDeviceRegistration
import com.example.focaaa.focus.FocusHomeScreen
import com.example.focaaa.focus.FocusStore

private enum class SetupScreen {
    FocusHome,
    PrivacyLimits,
    PinSetup,
    PinUnlock,
    Pairing,
    Contacts,
    SettingsHub,
    PrivateSettings,
    CreateInvite,
    JoinInvite,
    SecureChat
}

class MainActivity : FragmentActivity() {
    private var backgroundCount by mutableIntStateOf(0)
    private var closePrivateOnStop: (() -> Unit)? = null

    override fun onStop() {
        backgroundCount++
        closePrivateOnStop?.invoke()
        super.onStop()
    }

    override fun onDestroy() {
        closePrivateOnStop = null
        super.onDestroy()
    }
    override fun onResume() {
        super.onResume()
        RelayDeviceRegistration.refreshRegistrationIfNeeded(this)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val repository = RoomConversationRepository.open(applicationContext)
                    repository.pairingSessionStore.read()?.let { session ->
                        if (session.stage != PairingStage.ACTIVE) PairingFlowCoordinator(applicationContext, repository).refresh()
                    }
                    repository.pairingSessionStore.observeActive().firstOrNull()?.forEach { session ->
                        session.conversationId?.let { id -> PairingFlowCoordinator(applicationContext, repository, sessionId = id).refresh() }
                    }
                }
            } catch (_: Exception) {
                // Foreground reconciliation retries on the next resume; never log pairing or relay data.
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                com.example.focaaa.chat.relay.RelaySyncHintService.syncHints.collect { conversationId ->
                    try {
                        withContext(Dispatchers.IO) {
                            val repository = RoomConversationRepository.open(applicationContext)
                            val session = repository.pairingSessionStore.read(conversationId)
                            if (session?.stage == PairingStage.VERIFIED_LOCAL || session?.stage == PairingStage.ACTIVE) {
                                PairingFlowCoordinator(applicationContext, repository, sessionId = conversationId).refresh()
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // The next sync hint, explicit refresh, or foreground resume will retry.
                    }
                }
            }
        }
        val preferences = getSharedPreferences("focaaa_setup", MODE_PRIVATE)
        val pinStore = LocalPinStore(this)
        val focusStore = FocusStore(this)
        focusStore.resetTimerToDefault()
        val canUseDeviceAuthentication = BiometricManager.from(this)
            .canAuthenticate(deviceAuthenticators()) == BiometricManager.BIOMETRIC_SUCCESS

        setContent {
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                val message = if (granted) {
                    "Focus reminders are available. The Focaaa tile can also open your private notification panel."
                } else {
                    "Enable Focaaa notifications in Android Settings to receive focus reminders."
                }
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
            FocaaaTheme(darkTheme = false) {
                var roomRepository by remember { mutableStateOf<RoomConversationRepository?>(null) }
                var pairingFlow by remember { mutableStateOf<PairingFlowCoordinator?>(null) }
                var secureConversationId by remember { mutableStateOf<String?>(null) }
                var contacts by remember { mutableStateOf<List<ConversationContact>>(emptyList()) }
                var protectedDestination by remember { mutableStateOf(SetupScreen.Contacts) }
                var privateSettingsReturnScreen by remember { mutableStateOf(SetupScreen.Contacts) }
                var privateContactName by remember { mutableStateOf<String?>(null) }
                var conversationStorageError by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) {
                    try {
                        val repository = withContext(Dispatchers.IO) {
                            RoomConversationRepository.open(applicationContext)
                        }
                        roomRepository = repository
                        pairingFlow = PairingFlowCoordinator(applicationContext, repository)
                    } catch (error: Exception) {
                        conversationStorageError = error.message ?: "Encrypted local storage is unavailable."
                    }
                }
                LaunchedEffect(roomRepository) {
                    roomRepository?.observeContacts()?.collect { contacts = it }
                }
                LaunchedEffect(secureConversationId, roomRepository) {
                    val conversationId = secureConversationId
                    val repository = roomRepository
                    if (conversationId == null || repository == null) {
                        privateContactName = null
                    } else {
                        repository.observeConversationTitle(conversationId).collect { name ->
                            privateContactName = name
                        }
                    }
                }
                var currentScreen by remember {
                    mutableStateOf(
                        when {
                            !intent.getBooleanExtra(EXTRA_REQUIRE_HISTORY_AUTH, false) -> SetupScreen.FocusHome
                            !preferences.getBoolean("privacy_limits_acknowledged", false) -> SetupScreen.PrivacyLimits
                            !pinStore.hasPin() -> SetupScreen.PinSetup
                            else -> SetupScreen.PinUnlock
                        }
                    )
                }
                var isChangingPin by remember { mutableStateOf(false) }
                var pinChangeReturnScreen by remember { mutableStateOf(SetupScreen.Pairing) }
                var reviewingPrivacy by remember { mutableStateOf(false) }
                var privacyReviewReturnScreen by remember { mutableStateOf(SetupScreen.Pairing) }
                var privateEntryMinutes by remember { mutableIntStateOf(focusStore.privateEntryMinutes()) }
                var localRemovalCandidateId by remember { mutableStateOf<String?>(null) }
                var backupKey by remember { mutableStateOf<String?>(null) }
                var backupFile by remember { mutableStateOf<ByteArray?>(null) }
                var backupKeyRecorded by remember { mutableStateOf(false) }
                var restoreKey by remember { mutableStateOf("") }
                var pendingRestoreKey by remember { mutableStateOf<String?>(null) }
                var restorePrompt by remember { mutableStateOf(false) }
                var backupError by remember { mutableStateOf<String?>(null) }
                var backupBusy by remember { mutableStateOf(false) }
                var historyTransferResult by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(pendingRestoreKey) {
                    if (pendingRestoreKey != null) {
                        delay(10 * 60_000L)
                        pendingRestoreKey = null
                    }
                }
                val saveHistoryFile = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/octet-stream")
                ) { uri ->
                    val file = backupFile
                    backupFile = null
                    backupKey = null
                    if (uri != null && file != null) lifecycleScope.launch {
                        val saved = withContext(Dispatchers.IO) {
                            runCatching { contentResolver.openOutputStream(uri, "wt")?.use { it.write(file) } ?: error("Cannot write backup file.") }
                        }
                        file.fill(0)
                        val outcome = if (saved.isSuccess) HistoryTransferOutcome.SAVED else HistoryTransferOutcome.SAVE_FAILED
                        historyTransferResult = if (saved.isSuccess) "Encrypted history backup saved." else "Could not save the encrypted history backup."
                        Toast.makeText(this@MainActivity, publicHistoryTransferToast(outcome), Toast.LENGTH_LONG).show()
                    } else {
                        file?.fill(0)
                        if (uri != null) {
                            historyTransferResult = "Backup was interrupted. Please prepare a new backup."
                            Toast.makeText(this@MainActivity, publicHistoryTransferToast(HistoryTransferOutcome.INTERRUPTED), Toast.LENGTH_LONG).show()
                        }
                    }
                }
                val openHistoryFile = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri ->
                    val key = pendingRestoreKey.orEmpty()
                    pendingRestoreKey = null
                    if (uri != null && key.isNotBlank()) lifecycleScope.launch {
                        val restored = withContext(Dispatchers.IO) {
                            runCatching {
                                val repository = roomRepository ?: RoomConversationRepository.open(applicationContext)
                                val file = contentResolver.openInputStream(uri)?.use(HistoryBackupCodec::readBounded)
                                    ?: error("Cannot read backup file.")
                                try {
                                    val snapshot = HistoryBackupCodec.decrypt(file, key)
                                    repository.importHistorySnapshot(snapshot)
                                    snapshot.conversations.size
                                } finally { file.fill(0) }
                            }
                        }
                        val outcome = if (restored.isSuccess) HistoryTransferOutcome.IMPORTED else HistoryTransferOutcome.IMPORT_FAILED
                        historyTransferResult = restored.fold(
                            { "$it saved conversation(s) restored as read-only history." },
                            { "Could not restore. Check the backup file and recovery key." }
                        )
                        Toast.makeText(this@MainActivity, publicHistoryTransferToast(outcome), Toast.LENGTH_LONG).show()
                    } else if (uri != null) {
                        historyTransferResult = "Restore was interrupted. Please enter the recovery key again."
                        Toast.makeText(this@MainActivity, publicHistoryTransferToast(HistoryTransferOutcome.INTERRUPTED), Toast.LENGTH_LONG).show()
                    }
                }

                val onGatePassed: () -> Unit = onGatePassed@{
                    val expectedScreen = currentScreen
                    if (expectedScreen != SetupScreen.PinUnlock && expectedScreen != SetupScreen.PinSetup) return@onGatePassed
                    val expectedBackgroundCount = backgroundCount
                    lifecycleScope.launch {
                        try {
                            val session = withContext(Dispatchers.IO) {
                                val repository = roomRepository ?: RoomConversationRepository.open(applicationContext)
                                repository to repository.pairingSessionStore.read()
                            }
                            if (!canContinuePinGateAttempt(
                                    expectedBackgroundCount,
                                    backgroundCount,
                                    currentScreen == expectedScreen
                                )
                            ) return@launch
                            if (roomRepository == null) {
                                roomRepository = session.first
                                pairingFlow = PairingFlowCoordinator(applicationContext, session.first)
                            }
                            val saved = session.second
                            secureConversationId = null
                            currentScreen = when {
                                protectedDestination == SetupScreen.SettingsHub -> SetupScreen.SettingsHub
                                saved?.role == PairingRole.INVITER -> SetupScreen.CreateInvite
                                saved?.role == PairingRole.JOINER -> SetupScreen.JoinInvite
                                else -> protectedDestination
                            }
                        } catch (error: Exception) {
                            if (!canContinuePinGateAttempt(
                                    expectedBackgroundCount,
                                    backgroundCount,
                                    currentScreen == expectedScreen
                                )
                            ) return@launch
                            conversationStorageError = error.message ?: "Encrypted local storage is unavailable."
                            currentScreen = SetupScreen.Pairing
                        }
                    }
                }

                val returnToFocusHome: () -> Unit = {
                    localRemovalCandidateId = null
                    if (backupKey != null) {
                        backupFile?.fill(0)
                        backupFile = null
                        backupKey = null
                        backupKeyRecorded = false
                    }
                    restorePrompt = false
                    restoreKey = ""
                    focusStore.resetTimerToDefault()
                    currentScreen = SetupScreen.FocusHome
                }

                LaunchedEffect(backgroundCount) {
                    if (backgroundCount > 0 && currentScreen != SetupScreen.FocusHome) returnToFocusHome()
                }
                SideEffect {
                    closePrivateOnStop = {
                        if (currentScreen != SetupScreen.FocusHome) returnToFocusHome()
                    }
                    if (currentScreen == SetupScreen.FocusHome) {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    } else {
                        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }

                BackHandler(enabled = currentScreen != SetupScreen.FocusHome) {
                    when (currentScreen) {
                        SetupScreen.FocusHome -> Unit
                        SetupScreen.Pairing -> returnToFocusHome()
                        SetupScreen.Contacts, SetupScreen.SettingsHub -> returnToFocusHome()
                        SetupScreen.PrivateSettings -> currentScreen = privateSettingsReturnScreen
                        SetupScreen.CreateInvite, SetupScreen.JoinInvite -> currentScreen = SetupScreen.Pairing
                        SetupScreen.SecureChat -> currentScreen = SetupScreen.Contacts
                        SetupScreen.PinUnlock -> returnToFocusHome()
                        SetupScreen.PrivacyLimits -> if (reviewingPrivacy) {
                            reviewingPrivacy = false
                            currentScreen = privacyReviewReturnScreen
                        } else returnToFocusHome()
                        SetupScreen.PinSetup -> if (isChangingPin) currentScreen = pinChangeReturnScreen else returnToFocusHome()
                    }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    when (currentScreen) {
                        SetupScreen.FocusHome -> FocusHomeScreen(
                            store = focusStore,
                            privateEntryMinutes = privateEntryMinutes,
                            onPrivateEntry = {
                                protectedDestination = SetupScreen.Contacts
                                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                                currentScreen = when {
                                    !preferences.getBoolean("privacy_limits_acknowledged", false) -> SetupScreen.PrivacyLimits
                                    !pinStore.hasPin() -> SetupScreen.PinSetup
                                    else -> SetupScreen.PinUnlock
                                }
                            },
                            onOpenSettings = {
                                protectedDestination = SetupScreen.SettingsHub
                                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                                currentScreen = when {
                                    !preferences.getBoolean("privacy_limits_acknowledged", false) -> SetupScreen.PrivacyLimits
                                    !pinStore.hasPin() -> SetupScreen.PinSetup
                                    else -> SetupScreen.PinUnlock
                                }
                            },
                            modifier = Modifier.padding(innerPadding)
                        )

                        SetupScreen.PrivacyLimits -> PrivacyLimitsScreen(
                            modifier = Modifier.padding(innerPadding),
                            onContinue = {
                                preferences.edit()
                                    .putBoolean("privacy_limits_acknowledged", true)
                                    .apply()
                                currentScreen = if (reviewingPrivacy) privacyReviewReturnScreen else when {
                                    pinStore.hasPin() -> SetupScreen.PinUnlock
                                    else -> SetupScreen.PinSetup
                                }
                                reviewingPrivacy = false
                            }
                        )

                        SetupScreen.PinSetup -> PinEntryScreen(
                            title = if (isChangingPin) "Change your access PIN" else "Set an access PIN",
                            description = "Create a six-digit access PIN for this area. It is stored on this device.",
                            actionLabel = if (isChangingPin) "Save new PIN" else "Continue",
                            modifier = Modifier.padding(innerPadding),
                            onSavePin = { pin ->
                                val expectedBackgroundCount = backgroundCount
                                lifecycleScope.launch {
                                    withContext(Dispatchers.IO) { pinStore.savePin(pin) }
                                    if (!canContinuePinGateAttempt(
                                            expectedBackgroundCount,
                                            backgroundCount,
                                            currentScreen == SetupScreen.PinSetup
                                        )
                                    ) return@launch
                                    if (isChangingPin) {
                                        isChangingPin = false
                                        currentScreen = pinChangeReturnScreen
                                    } else onGatePassed()
                                }
                            },
                            onUnlock = null,
                            onDeviceAuthentication = null,
                            onBack = { if (isChangingPin) currentScreen = pinChangeReturnScreen else returnToFocusHome() }
                        )

                        SetupScreen.PinUnlock -> PinEntryScreen(
                            title = "Unlock Focaaa",
                            description = "Enter your six-digit access PIN.",
                            actionLabel = "Unlock",
                            allowDeviceAuthentication = false,
                            modifier = Modifier.padding(innerPadding),
                            onSavePin = {},
                            onUnlock = { pin, onResult ->
                                val expectedBackgroundCount = backgroundCount
                                lifecycleScope.launch {
                                    val result = withContext(Dispatchers.IO) { pinStore.verify(pin) }
                                    if (result == PinVerification.Success && canContinuePinGateAttempt(
                                            expectedBackgroundCount,
                                            backgroundCount,
                                            currentScreen == SetupScreen.PinUnlock
                                        )
                                    ) onGatePassed()
                                    onResult(result)
                                }
                            },
                            getLockSeconds = { pinStore.remainingLockSeconds() },
                            onDeviceAuthentication = null,
                            onBack = { returnToFocusHome() }
                        )

                        SetupScreen.Pairing -> PairingSetupScreen(
                            modifier = Modifier.padding(innerPadding),
                            activeConversationId = contacts.firstOrNull { !it.isRestored }?.conversationId,
                                onOpenChat = { currentScreen = SetupScreen.Contacts },
                                onManageSettings = { currentScreen = SetupScreen.SettingsHub },
                            onCreateInvite = {
                                currentScreen = if (hasActivePartner(contacts)) SetupScreen.Contacts else SetupScreen.CreateInvite
                            },
                            onJoinInvite = {
                                currentScreen = if (hasActivePartner(contacts)) SetupScreen.Contacts else SetupScreen.JoinInvite
                            },
                            onReviewPrivacyLimits = {
                                reviewingPrivacy = true
                                privacyReviewReturnScreen = SetupScreen.Pairing
                                currentScreen = SetupScreen.PrivacyLimits
                            },
                            onChangePin = {
                                isChangingPin = true
                                pinChangeReturnScreen = SetupScreen.Pairing
                                currentScreen = SetupScreen.PinSetup
                            }
                        )

                        SetupScreen.Contacts -> roomRepository?.let { repository ->
                            ContactListScreen(
                                contacts = contacts,
                                modifier = Modifier.padding(innerPadding),
                                onOpen = { id ->
                                    if (contacts.none { it.conversationId == id && it.isRestored }) {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                                        ) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        ChatSessionNotification.selectContact(applicationContext, id)
                                    }
                                    secureConversationId = id
                                    pairingFlow = PairingFlowCoordinator(applicationContext, repository, sessionId = id)
                                    currentScreen = SetupScreen.SecureChat
                                },
                                onAddPerson = { if (!hasActivePartner(contacts)) currentScreen = SetupScreen.Pairing },
                                onRename = { id ->
                                    secureConversationId = id
                                    privateSettingsReturnScreen = SetupScreen.Contacts
                                    currentScreen = SetupScreen.PrivateSettings
                                },
                                onUnpair = { id ->
                                    lifecycleScope.launch {
                                        runCatching {
                                            if (contacts.any { it.conversationId == id && it.isRestored }) {
                                                withContext(Dispatchers.IO) { repository.deletePrivateConversation(id) }
                                            } else PairingFlowCoordinator(applicationContext, repository, sessionId = id).unpair(id)
                                        }
                                            .onSuccess { ChatSessionNotification.forgetContact(applicationContext, id) }
                                            .onFailure { localRemovalCandidateId = id }
                                    }
                                }
                            )
                        }

                        SetupScreen.SettingsHub -> PrivateSettingsHubScreen(
                            privateEntryMinutes = privateEntryMinutes,
                            hasActivePartner = hasActivePartner(contacts),
                            onPrivateEntryMinutesChange = { minutes -> focusStore.setPrivateEntryMinutes(minutes); privateEntryMinutes = minutes },
                            onContacts = { currentScreen = SetupScreen.Contacts },
                            onCreateInvite = { currentScreen = if (hasActivePartner(contacts)) SetupScreen.Contacts else SetupScreen.CreateInvite },
                            onJoinInvite = { currentScreen = if (hasActivePartner(contacts)) SetupScreen.Contacts else SetupScreen.JoinInvite },
                            onAddTile = { requestQuickSettingsTile() },
                            onChangePin = { isChangingPin = true; pinChangeReturnScreen = SetupScreen.SettingsHub; currentScreen = SetupScreen.PinSetup },
                            onReviewPrivacy = { reviewingPrivacy = true; privacyReviewReturnScreen = SetupScreen.SettingsHub; currentScreen = SetupScreen.PrivacyLimits },
                            onBackupHistory = {
                                if (!backupBusy) {
                                    backupBusy = true
                                    backupError = null
                                    historyTransferResult = null
                                    val expectedBackgroundCount = backgroundCount
                                    lifecycleScope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            runCatching {
                                                val repository = roomRepository ?: RoomConversationRepository.open(applicationContext)
                                                val key = HistoryBackupCodec.generateRecoveryKey()
                                                key to HistoryBackupCodec.encrypt(repository.exportHistorySnapshot(), key)
                                            }
                                        }
                                        backupBusy = false
                                        result.onSuccess { (key, file) ->
                                            if (backgroundCount == expectedBackgroundCount && currentScreen == SetupScreen.SettingsHub) {
                                                backupKey = key
                                                backupFile = file
                                                backupKeyRecorded = false
                                            } else file.fill(0)
                                        }.onFailure { backupError = it.message ?: "Could not prepare history backup." }
                                    }
                                }
                            },
                            onRestoreHistory = { historyTransferResult = null; restorePrompt = true; restoreKey = "" },
                            onBack = { currentScreen = SetupScreen.Contacts },
                            modifier = Modifier.padding(innerPadding)
                        )

                        SetupScreen.PrivateSettings -> PrivateSettingsScreen(
                            privateEntryMinutes = privateEntryMinutes,
                            contactName = privateContactName,
                            onPrivateEntryMinutesChange = { minutes ->
                                focusStore.setPrivateEntryMinutes(minutes)
                                privateEntryMinutes = minutes
                            },
                            onSaveContactName = { name ->
                                val id = requireNotNull(secureConversationId)
                                val repository = requireNotNull(roomRepository)
                                withContext(Dispatchers.IO) { repository.renameConversation(id, name) }
                            },
                            onChangePin = {
                                isChangingPin = true
                                pinChangeReturnScreen = SetupScreen.PrivateSettings
                                currentScreen = SetupScreen.PinSetup
                            },
                            onReviewPrivacyLimits = {
                                reviewingPrivacy = true
                                privacyReviewReturnScreen = SetupScreen.PrivateSettings
                                currentScreen = SetupScreen.PrivacyLimits
                            },
                            onBack = { currentScreen = privateSettingsReturnScreen },
                            modifier = Modifier.padding(innerPadding)
                        )

                        SetupScreen.CreateInvite -> pairingFlow?.let { flow ->
                            PairingFlowScreen(
                                mode = PairingRole.INVITER,
                                coordinator = flow,
                                modifier = Modifier.padding(innerPadding),
                                onBack = { currentScreen = SetupScreen.Pairing },
                                onOpenSecureChat = { currentScreen = SetupScreen.Contacts }
                            )
                        } ?: PairingPlaceholderScreen(
                            title = "Encrypted storage unavailable",
                            message = conversationStorageError ?: "Focaaa is opening secure local storage.",
                            modifier = Modifier.padding(innerPadding),
                            onBack = { currentScreen = SetupScreen.Pairing }
                        )

                        SetupScreen.JoinInvite -> pairingFlow?.let { flow ->
                            PairingFlowScreen(
                                mode = PairingRole.JOINER,
                                coordinator = flow,
                                modifier = Modifier.padding(innerPadding),
                                onBack = { currentScreen = SetupScreen.Pairing },
                                onOpenSecureChat = { currentScreen = SetupScreen.Contacts }
                            )
                        } ?: PairingPlaceholderScreen(
                            title = "Encrypted storage unavailable",
                            message = conversationStorageError ?: "Focaaa is opening secure local storage.",
                            modifier = Modifier.padding(innerPadding),
                            onBack = { currentScreen = SetupScreen.Pairing }
                        )

                        SetupScreen.SecureChat -> when {
                            roomRepository != null && pairingFlow != null && secureConversationId != null -> SecureConversationScreen(
                                conversationId = secureConversationId!!,
                                isRestored = contacts.any { it.conversationId == secureConversationId && it.isRestored },
                                repository = roomRepository!!,
                                sendMessage = { text -> PairingFlowCoordinator(applicationContext, roomRepository!!, sessionId = secureConversationId!!).sendMessage(text) },
                                sendMedia = { uri, kind -> PairingFlowCoordinator(applicationContext, roomRepository!!, sessionId = secureConversationId!!).sendMedia(uri, kind) },
                                openMedia = { descriptor -> PairingFlowCoordinator(applicationContext, roomRepository!!, sessionId = secureConversationId!!).openMedia(secureConversationId!!, descriptor) },
                                refreshMessages = { PairingFlowCoordinator(applicationContext, roomRepository!!, sessionId = secureConversationId!!).syncActiveConversation(secureConversationId!!) },
                                reportVisibleReads = { PairingFlowCoordinator(applicationContext, roomRepository!!, sessionId = secureConversationId!!).reportVisibleReads(secureConversationId!!) },
                                modifier = Modifier.padding(innerPadding),
                                onBack = { currentScreen = SetupScreen.Contacts },
                                onPrivateSettings = { privateSettingsReturnScreen = SetupScreen.SecureChat; currentScreen = SetupScreen.PrivateSettings }
                            )
                            else -> PairingPlaceholderScreen(
                                title = "Secure conversation unavailable",
                                message = conversationStorageError ?: "Complete pairing verification before opening this conversation.",
                                modifier = Modifier.padding(innerPadding),
                                onBack = { returnToFocusHome() }
                            )
                        }
                    }
                }
                if (currentScreen == SetupScreen.SettingsHub && historyTransferResult != null) {
                    AlertDialog(
                        onDismissRequest = { historyTransferResult = null },
                        title = { Text("Saved history") },
                        text = { Text(historyTransferResult.orEmpty()) },
                        confirmButton = { TextButton(onClick = { historyTransferResult = null }) { Text("OK") } }
                    )
                }
                if (currentScreen == SetupScreen.SettingsHub && backupBusy) {
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text("Preparing history") },
                        text = { Text("Encrypting your saved conversations on this phone.") },
                        confirmButton = {}
                    )
                }
                if (currentScreen == SetupScreen.SettingsHub && backupError != null) {
                    AlertDialog(
                        onDismissRequest = { backupError = null },
                        title = { Text("Backup unavailable") },
                        text = { Text(backupError ?: "Could not prepare history backup.") },
                        confirmButton = { TextButton(onClick = { backupError = null }) { Text("OK") } }
                    )
                }
                if (currentScreen == SetupScreen.SettingsHub && backupKey != null && backupFile != null) {
                    AlertDialog(
                        onDismissRequest = { backupFile?.fill(0); backupFile = null; backupKey = null; backupKeyRecorded = false },
                        title = { Text("Save your recovery key") },
                        text = {
                            Column(
                                modifier = Modifier.verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text("Write down this key before saving the file. Focaaa cannot show it again or recover it later.")
                                Text("RECOVERY KEY", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                SelectionContainer { Text(backupKey.orEmpty(), fontFamily = FontFamily.Monospace) }
                                Text("If you copy the key, clear it from the clipboard afterward.")
                                Text("This saves messages as read-only history. It does not restore a pairing or encryption keys.")
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Checkbox(checked = backupKeyRecorded, onCheckedChange = { backupKeyRecorded = it })
                                    Text("I saved the recovery key separately.")
                                }
                            }
                        },
                        confirmButton = { TextButton(enabled = backupKeyRecorded, onClick = { backupKey = null; backupKeyRecorded = false; saveHistoryFile.launch("focaaa-history.fcb") }) { Text("Choose file") } },
                        dismissButton = { TextButton(onClick = { backupFile?.fill(0); backupFile = null; backupKey = null; backupKeyRecorded = false }) { Text("Cancel") } }
                    )
                }
                if (currentScreen == SetupScreen.SettingsHub && restorePrompt) {
                    AlertDialog(
                        onDismissRequest = { restorePrompt = false; restoreKey = "" },
                        title = { Text("Restore saved history") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Enter the recovery key for your encrypted backup. Imported messages stay read only; reconnect with a new invite to chat.")
                                OutlinedTextField(
                                    value = restoreKey,
                                    onValueChange = { restoreKey = it.trim().take(43) },
                                    label = { Text("Recovery key") },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation()
                                )
                            }
                        },
                        confirmButton = { TextButton(enabled = restoreKey.length == 43, onClick = { pendingRestoreKey = restoreKey; restoreKey = ""; restorePrompt = false; openHistoryFile.launch(arrayOf("application/octet-stream", "application/*")) }) { Text("Choose backup") } },
                        dismissButton = { TextButton(onClick = { restorePrompt = false; restoreKey = "" }) { Text("Cancel") } }
                    )
                }
                if (currentScreen == SetupScreen.Contacts) {
                    localRemovalCandidateId?.let { id ->
                        AlertDialog(
                            onDismissRequest = { localRemovalCandidateId = null },
                            title = { Text("Could not disconnect") },
                            text = { Text("The relay could not confirm unpairing. You can keep this contact and try again, or permanently remove this phone's messages and encryption keys. Removing locally cannot revoke a pair that still exists on the relay or erase the other person's copy.") },
                            confirmButton = {
                                TextButton(onClick = {
                                    localRemovalCandidateId = null
                                    lifecycleScope.launch {
                                        runCatching { PairingFlowCoordinator(applicationContext, roomRepository!!, sessionId = id).removeLocalPairing(id) }
                                            .onSuccess {
                                                ChatSessionNotification.forgetContact(applicationContext, id)
                                                Toast.makeText(this@MainActivity, "Removed from this phone. Remote unpair was not confirmed.", Toast.LENGTH_LONG).show()
                                            }
                                            .onFailure { Toast.makeText(this@MainActivity, it.message ?: "Local removal failed.", Toast.LENGTH_LONG).show() }
                                    }
                                }) { Text("Remove from this phone") }
                            },
                            dismissButton = { TextButton(onClick = { localRemovalCandidateId = null }) { Text("Keep and retry") } }
                        )
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_REQUIRE_HISTORY_AUTH = "com.example.focaaa.REQUIRE_HISTORY_AUTH"
    }
}

private fun deviceAuthenticators(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
} else {
    BiometricManager.Authenticators.BIOMETRIC_WEAK
}

private fun FragmentActivity.authenticateWithDeviceCredential(onSuccess: () -> Unit) {
    if (BiometricManager.from(this).canAuthenticate(deviceAuthenticators()) != BiometricManager.BIOMETRIC_SUCCESS) {
        Toast.makeText(this, "Device authentication is not available. Use your Focaaa PIN.", Toast.LENGTH_LONG).show()
        return
    }
    val prompt = BiometricPrompt(
        this,
        ContextCompat.getMainExecutor(this),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }
        }
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Unlock Focaaa")
        .setSubtitle("Use your device authentication")
        .setAllowedAuthenticators(deviceAuthenticators())
        .build()
    prompt.authenticate(info)
}

private fun FragmentActivity.requestQuickSettingsTile() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(this, "Open Android’s Quick Settings tile editor and add Focaaa there.", Toast.LENGTH_LONG).show()
        return
    }
    val manager = getSystemService(StatusBarManager::class.java)
    if (manager == null) {
        Toast.makeText(this, "Open Android’s Quick Settings tile editor and add Focaaa there.", Toast.LENGTH_LONG).show()
        return
    }
    manager.requestAddTileService(
        ComponentName(this, FocaaaTileService::class.java),
        getString(R.string.quick_settings_tile_label),
        Icon.createWithResource(this, R.drawable.ic_quick_settings),
        mainExecutor
    ) { result ->
        val message = when (result) {
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Quick Settings tile added."
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "The Quick Settings tile is already added."
            else -> "The Quick Settings tile was not added. You can add it from the system tile editor."
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun PrivacyLimitsScreen(
    modifier: Modifier = Modifier,
    onContinue: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = 32.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                text = "Focaaa",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Private messaging, with clear limits",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Focaaa is designed to reduce casual exposure. It cannot make the app invisible or protect you from every kind of access.",
                style = MaterialTheme.typography.bodyLarge
            )

            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Know what may still be visible",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    PrivacyLimit("Android Settings and Quick Settings can reveal that Focaaa is installed or used.")
                    PrivacyLimit("If you open the notification-panel chat, its expanded view shows a contact name and recent messages. Android may retain notification history.")
                    PrivacyLimit("A new private message may trigger a generic focus reminder with no contact name or message text.")
                    PrivacyLimit("Someone with access to your phone may still discover or open the app.")
                    PrivacyLimit("Private messages are saved on this phone. Encrypted relay copies of new media expire after seven days; downloaded media stays on each phone until deleted. The manual encrypted history backup includes messages but not photo or video files. A replacement phone needs a new pairing.")
                }
            }
        }

        Text(
            text = "By continuing, you confirm you understand these limits.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp)
        ) {
            Text("Continue")
        }
    }
}

@Composable
private fun PinEntryScreen(
    title: String,
    description: String,
    actionLabel: String,
    modifier: Modifier = Modifier,
    allowDeviceAuthentication: Boolean = false,
    onSavePin: (String) -> Unit,
    onUnlock: ((String, (PinVerification) -> Unit) -> Unit)?,
    getLockSeconds: () -> Int = { 0 },
    onDeviceAuthentication: (() -> Unit)?,
    onBack: (() -> Unit)?
) {
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var lockSeconds by remember { mutableIntStateOf(getLockSeconds()) }
    val keyboard = LocalSoftwareKeyboardController.current
    val isSetup = onUnlock == null

    LaunchedEffect(onUnlock != null) {
        while (onUnlock != null) {
            lockSeconds = getLockSeconds()
            delay(1_000)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = "Focaaa",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(description, style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
            value = pin,
            onValueChange = { value ->
                if (value.length <= 6 && value.all(Char::isDigit)) {
                    pin = value
                    error = ""
                }
            },
            label = { Text(if (confirming) "Confirm six-digit PIN" else "Six-digit PIN") },
            supportingText = { Text("Use the numeric keyboard or an accessible keyboard input.") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            enabled = !busy && lockSeconds == 0,
            modifier = Modifier.fillMaxWidth()
        )
        if (lockSeconds > 0) {
            Text(
                if (allowDeviceAuthentication) {
                    "Too many attempts. Wait $lockSeconds seconds or use device authentication."
                } else {
                    "Too many attempts. Wait $lockSeconds seconds before trying again."
                },
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        if (error.isNotBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Button(
            onClick = {
                keyboard?.hide()
                if (pin.length != 6) {
                    error = "Enter all six digits."
                } else if (isSetup) {
                    if (!confirming) {
                        confirmation = pin
                        pin = ""
                        confirming = true
                        error = "Enter the same PIN again to confirm it."
                    } else if (pin != confirmation) {
                        pin = ""
                        confirmation = ""
                        confirming = false
                        error = "Those PINs did not match. Start again."
                    } else {
                        busy = true
                        onSavePin(pin)
                    }
                } else if (lockSeconds > 0) {
                    error = "Too many attempts. Try again in $lockSeconds seconds."
                } else {
                    busy = true
                    onUnlock?.invoke(pin) { result ->
                        busy = false
                        pin = ""
                        when (result) {
                            PinVerification.Success -> error = ""
                            PinVerification.Invalid -> error = "PIN incorrect. Try again."
                            is PinVerification.Locked -> error = "Too many attempts. Try again in ${result.seconds} seconds."
                        }
                    }
                }
            },
            enabled = !busy && lockSeconds == 0,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (busy) "Please wait…" else if (confirming) "Save PIN" else actionLabel)
        }
        if (allowDeviceAuthentication && onDeviceAuthentication != null) {
            TextButton(onClick = onDeviceAuthentication, modifier = Modifier.fillMaxWidth()) {
                Text("Use device authentication instead")
            }
            Text(
                "Your device may offer fingerprint, face, or its screen-lock PIN. Focaaa's PIN remains available as an accessible fallback.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        onBack?.let { callback -> TextButton(onClick = callback) { Text("Back") } }
    }
}

@Composable
private fun PrivacyLimit(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .size(7.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
        )
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PairingSetupScreen(
    modifier: Modifier = Modifier,
    activeConversationId: String?,
    onOpenChat: () -> Unit,
    onManageSettings: () -> Unit,
    onCreateInvite: () -> Unit,
    onJoinInvite: () -> Unit,
    onReviewPrivacyLimits: () -> Unit,
    onChangePin: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = "Focaaa",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = if (activeConversationId != null) "Private settings" else "Pair with your partner",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        if (activeConversationId != null) {
            PairingOptionCard(
                title = "Your conversation",
                description = "Return to your private conversation.",
                buttonLabel = "Open private chat",
                onClick = onOpenChat
            )
        } else {
            Text(
                text = "Choose how you want to start. You and your partner will compare a verification code before pairing is complete.",
                style = MaterialTheme.typography.bodyLarge
            )
            PairingOptionCard(
                title = "Create an invite",
                description = "Prepare a short-lived invite for your partner to join.",
                buttonLabel = "Create invite",
                onClick = onCreateInvite
            )
            PairingOptionCard(
                title = "Join with an invite",
                description = "Scan or enter the invite your partner created.",
                buttonLabel = "Join invite",
                onClick = onJoinInvite
            )
        }
        TextButton(onClick = onManageSettings) { Text("Private settings") }
        TextButton(onClick = onChangePin) { Text("Change access PIN") }
        TextButton(onClick = onReviewPrivacyLimits) {
            Text("Review privacy limits")
        }
    }
}

@Composable
private fun PairingOptionCard(
    title: String,
    description: String,
    buttonLabel: String,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
                Text(buttonLabel)
            }
        }
    }
}

@Composable
private fun PairingPlaceholderScreen(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = "Focaaa",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(message, style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = onBack) {
            Text("Back")
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PrivacyLimitsPreview() {
    FocaaaTheme {
        PrivacyLimitsScreen(onContinue = {})
    }
}
