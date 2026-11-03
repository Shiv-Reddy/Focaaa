package com.example.focaaa.chat.pairing

import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.net.Uri
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import com.example.focaaa.chat.ConversationMessage
import com.example.focaaa.R
import com.example.focaaa.chat.MessageSender
import com.example.focaaa.chat.MessageDeliveryStatus
import com.example.focaaa.chat.VisibleChatTracker
import com.example.focaaa.chat.FocusReminderNotification
import com.example.focaaa.chat.contactInitials
import com.example.focaaa.chat.data.RoomConversationRepository
import com.example.focaaa.chat.media.EncryptedMediaStore
import com.example.focaaa.chat.media.MediaDescriptor
import com.example.focaaa.chat.media.MediaKind
import com.example.focaaa.chat.media.MediaMessageCodec
import com.example.focaaa.chat.relay.RelayServiceGeneration
import com.example.focaaa.chat.relay.PairEndedException
import com.example.focaaa.chat.typing.TypingCoordinator
import com.example.focaaa.ui.theme.FocaaaPalette
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private val ChatCanvas = FocaaaPalette.Canvas
private val ChatForest = FocaaaPalette.Primary
private val OutgoingBubble = FocaaaPalette.Outgoing
private val IncomingBubble = Color.White

@Composable
fun SecureConversationScreen(
    conversationId: String,
    isRestored: Boolean = false,
    repository: RoomConversationRepository,
    sendMessage: suspend (String) -> Boolean,
    sendMedia: suspend (Uri, MediaKind) -> Boolean,
    openMedia: suspend (MediaDescriptor) -> ByteArray,
    refreshMessages: suspend () -> Unit,
    reportVisibleReads: suspend () -> Unit,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onPrivateSettings: () -> Unit
) {
    val messages by repository.messagesFor(conversationId).collectAsState(initial = emptyList())
    val contactName by repository.observeConversationTitle(conversationId).collectAsState(initial = "Private contact")
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = context as LifecycleOwner
    val currentServicePair = remember(conversationId, isRestored) { !isRestored && RelayServiceGeneration.isCurrent(context, conversationId) }
    val typing = remember(conversationId, currentServicePair) {
        if (currentServicePair) TypingCoordinator(context, conversationId) else null
    }
    val partnerTyping = typing?.partnerTyping?.collectAsState()?.value ?: false
    LaunchedEffect(typing, lifecycleOwner) {
        if (typing == null) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            typing.start()
            try { kotlinx.coroutines.awaitCancellation() } finally { typing.stop() }
        }
    }
    DisposableEffect(typing) { onDispose { typing?.dispose() } }
    DisposableEffect(conversationId, lifecycleOwner, currentServicePair) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (currentServicePair) {
                    VisibleChatTracker.enter(conversationId)
                    FocusReminderNotification.dismiss(context)
                }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY ->
                    VisibleChatTracker.leave(conversationId)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (currentServicePair && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            VisibleChatTracker.enter(conversationId)
            FocusReminderNotification.dismiss(context)
        }
        onDispose {
            VisibleChatTracker.leave(conversationId)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var connectionEnded by remember(conversationId) { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ConversationMessage?>(null) }
    var viewedImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var viewedVideo by remember { mutableStateOf<ByteArray?>(null) }
    var viewerError by remember { mutableStateOf<String?>(null) }
    var attachmentMenuOpen by remember { mutableStateOf(false) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && !busy) scope.launch {
            busy = true
            status = "Encrypting and sending photo…"
            try { status = if (sendMedia(uri, MediaKind.PHOTO)) null else "Photo saved. Delivery is waiting to retry." }
            catch (error: Exception) { status = error.message?.take(160) ?: "Photo could not be sent." }
            finally { busy = false }
        }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && !busy) scope.launch {
            busy = true
            status = "Encrypting and sending video…"
            try { status = if (sendMedia(uri, MediaKind.VIDEO)) null else "Video saved. Delivery is waiting to retry." }
            catch (error: Exception) { status = error.message?.take(160) ?: "Video could not be sent." }
            finally { busy = false }
        }
    }

    fun openPrivateMedia(descriptor: MediaDescriptor) {
        scope.launch {
            viewerError = null
            status = "Opening private media…"
            try {
                val plaintext = openMedia(descriptor)
                if (descriptor.kind == MediaKind.PHOTO) {
                    try {
                        val bitmap = BitmapFactory.decodeByteArray(plaintext, 0, plaintext.size)
                            ?: throw IllegalArgumentException("This photo cannot be displayed.")
                        viewedImage = bitmap.asImageBitmap()
                    } finally { plaintext.fill(0) }
                } else viewedVideo = plaintext
                status = null
            } catch (error: Exception) {
                status = null
                viewerError = error.message?.take(160) ?: "Private media is unavailable."
            }
        }
    }

    LaunchedEffect(conversationId, lifecycleOwner, currentServicePair) {
        if (!currentServicePair) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                try {
                    refreshMessages()
                    try {
                        reportVisibleReads()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (ended: PairEndedException) {
                        throw ended
                    } catch (_: Exception) {
                        // Retry this optional read report on the next visible sync.
                    }
                    if (status == "Couldn't refresh yet. Focaaa will try again.") status = null
                } catch (ended: PairEndedException) {
                    connectionEnded = true
                    status = "This connection has ended. Saved messages stay on this phone. Pair again to chat."
                    break
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    status = "Couldn't refresh yet. Focaaa will try again."
                }
                delay(5_000)
            }
        }
    }

    var previousMessageCount by remember(conversationId) { mutableIntStateOf(0) }
    LaunchedEffect(messages.size) {
        val oldCount = previousMessageCount
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        val followingLatest = oldCount == 0 || lastVisible >= oldCount - 2
        previousMessageCount = messages.size
        if (messages.size > oldCount && followingLatest) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    fun submitDraft() {
        val outgoing = draft.trim()
        if (outgoing.isEmpty() || busy || connectionEnded) return
        scope.launch {
            busy = true
            status = null
            try {
                val savedLocally = sendMessage(outgoing)
                draft = ""
                typing?.sent()
                status = if (savedLocally) null else "Saved on this phone. Delivery could not be confirmed."
            } catch (ended: PairEndedException) {
                connectionEnded = true
                draft = ""
                status = "This connection has ended. Your last message was saved only on this phone."
            } catch (error: Exception) {
                status = error.message?.take(160) ?: "Message could not be sent."
            } finally {
                busy = false
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize().background(ChatCanvas).imePadding(),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Text("‹", color = ChatForest, fontSize = 34.sp) }
            Surface(shape = CircleShape, color = FocaaaPalette.Pale, modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text(contactInitials(contactName), color = ChatForest, fontWeight = FontWeight.Bold)
                }
            }
            Column(modifier = Modifier.weight(1f).padding(start = 13.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(contactName, color = FocaaaPalette.Ink, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(if (partnerTyping) "typing…" else "Private conversation",
                    color = if (partnerTyping) ChatForest else FocaaaPalette.Muted,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (partnerTyping) FontWeight.SemiBold else FontWeight.Normal)
            }
            IconButton(onClick = onPrivateSettings) { Text("⋮", color = ChatForest, fontSize = 25.sp) }
        }
        if (!currentServicePair || connectionEnded) {
            Surface(color = FocaaaPalette.Pale, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (connectionEnded) "This connection has ended. Add this person again with a new invite to send messages."
                    else "Saved history is read only. Add this person again with a new invite to send messages.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = ChatForest
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                        Surface(shape = RoundedCornerShape(18.dp), color = Color.White) {
                            Column(
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text("A quiet place to talk", color = ChatForest, fontWeight = FontWeight.SemiBold)
                                Text("Messages stay private to this conversation.", color = FocaaaPalette.Muted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
                val zone = ZoneId.systemDefault()
                val day = Instant.ofEpochMilli(message.createdAtMillis).atZone(zone).toLocalDate()
                val previousDay = messages.getOrNull(index - 1)?.let {
                    Instant.ofEpochMilli(it.createdAtMillis).atZone(zone).toLocalDate()
                }
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (day != previousDay) DaySeparator(day)
                    MessageBubble(message, zone, showDeliveryStatus = currentServicePair,
                        onDelete = { deleteTarget = message }, onOpenMedia = ::openPrivateMedia,
                        loadMedia = openMedia)
                }
            }
        }

        if (status != null) {
            Text(
                status!!,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = FocaaaPalette.Muted
            )
        }

        if (currentServicePair && !connectionEnded) Row(
            modifier = Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.Bottom
        ) {
            Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(26.dp),
                color = ChatCanvas, shadowElevation = 0.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 14.dp)) {
                    Box {
                        IconButton(onClick = { attachmentMenuOpen = true }, enabled = !busy) {
                            Icon(painterResource(R.drawable.ic_chat_attach), contentDescription = "Attach photo or video", tint = ChatForest)
                        }
                        DropdownMenu(expanded = attachmentMenuOpen, onDismissRequest = { attachmentMenuOpen = false }) {
                            DropdownMenuItem(text = { Text("Photo · up to 2 MB") },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_chat_photo), null, tint = ChatForest) },
                                onClick = { attachmentMenuOpen = false; photoPicker.launch("image/*") })
                            DropdownMenuItem(text = { Text("Video · up to 10 MB") },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_chat_video), null, tint = ChatForest) },
                                onClick = { attachmentMenuOpen = false; videoPicker.launch("video/mp4") })
                        }
                    }
                    BasicTextField(
                        value = draft, onValueChange = {
                            draft = it.take(4000)
                            typing?.edited(draft.isNotBlank())
                        }, enabled = !busy,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = FocaaaPalette.Ink),
                        cursorBrush = SolidColor(ChatForest), maxLines = 5,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { submitDraft() }),
                        decorationBox = { inner ->
                            Box {
                                if (draft.isEmpty()) Text("Message", color = FocaaaPalette.Muted,
                                    style = MaterialTheme.typography.bodyLarge)
                                inner()
                            }
                        },
                        modifier = Modifier.weight(1f).padding(vertical = 14.dp)
                    )
                }
            }
            Button(onClick = ::submitDraft, enabled = !busy && draft.isNotBlank(),
                modifier = Modifier.size(50.dp), shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = ChatForest),
                contentPadding = PaddingValues(13.dp)) {
                Icon(painterResource(R.drawable.ic_chat_send), contentDescription = "Send message", tint = Color.White)
            }
        }
    }
    deleteTarget?.let { selected ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete on this phone?") },
            text = { Text("This removes the message only from this phone. It cannot remove a copy already delivered or queued for your contact.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching {
                            repository.deleteLocalMessage(conversationId, selected.id)
                            runCatching { MediaMessageCodec.decode(selected.text) }.getOrNull()?.let {
                                EncryptedMediaStore(context).delete(conversationId, it.mediaId)
                            }
                        }
                            .onSuccess { status = "Message deleted on this phone." }
                            .onFailure { status = "Could not delete this message." }
                        deleteTarget = null
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Keep") } }
        )
    }
    if (viewedImage != null || viewedVideo != null || viewerError != null) {
        Dialog(
            onDismissRequest = { viewedImage = null; viewedVideo?.fill(0); viewedVideo = null; viewerError = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Column(Modifier.fillMaxSize().background(FocaaaPalette.Deep).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(if (viewedVideo != null) "Private video" else "Private photo",
                        color = Color.White, fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewedImage = null; viewedVideo?.fill(0); viewedVideo = null; viewerError = null }) {
                        Text("Close", color = Color.White)
                    }
                }
                viewedImage?.let { image ->
                    Image(image, contentDescription = "Private photo", contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().weight(1f))
                }
                viewedVideo?.let { bytes -> PrivateVideoViewer(bytes, Modifier.fillMaxWidth().weight(1f)) }
                viewerError?.let { Text(it, color = Color.White, modifier = Modifier.padding(16.dp)) }
            }
        }
    }
}

@Composable
private fun DaySeparator(day: LocalDate) {
    val today = LocalDate.now()
    val label = when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.getDefault()))
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(14.dp), color = FocaaaPalette.Pale) {
            Text(label, modifier = Modifier.padding(horizontal = 13.dp, vertical = 6.dp), color = FocaaaPalette.Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun MessageBubble(
    message: ConversationMessage, zone: ZoneId, showDeliveryStatus: Boolean,
    onDelete: () -> Unit, onOpenMedia: (MediaDescriptor) -> Unit,
    loadMedia: suspend (MediaDescriptor) -> ByteArray
) {
    val isLocal = message.sender == MessageSender.LOCAL_USER
    val media = runCatching { MediaMessageCodec.decode(message.text) }.getOrNull()
    var photoPreview by remember(message.id) { mutableStateOf<ImageBitmap?>(null) }
    var previewBusy by remember(message.id) { mutableStateOf(false) }
    LaunchedEffect(media?.mediaId) {
        if (media?.kind != MediaKind.PHOTO || photoPreview != null) return@LaunchedEffect
        previewBusy = true
        try {
            val bytes = loadMedia(media)
            try {
                photoPreview = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            } finally { bytes.fill(0) }
        } catch (_: Exception) {
            // A missing or expired relay copy remains a tappable media placeholder.
        } finally { previewBusy = false }
    }
    val time = Instant.ofEpochMilli(message.createdAtMillis)
        .atZone(zone)
        .toLocalTime()
        .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isLocal) Arrangement.End else Arrangement.Start
    ) {
        Card(
            shape = if (isLocal) RoundedCornerShape(18.dp, 18.dp, 5.dp, 18.dp) else RoundedCornerShape(18.dp, 18.dp, 18.dp, 5.dp),
            colors = CardDefaults.cardColors(containerColor = if (isLocal) OutgoingBubble else IncomingBubble),
            modifier = if (media != null) Modifier.widthIn(min = 225.dp, max = 300.dp)
                else Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(start = 13.dp, top = 9.dp, end = 11.dp, bottom = 6.dp)) {
                if (media != null) {
                    Column(
                        Modifier.clickable { onOpenMedia(media) }.padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        if (media.kind == MediaKind.PHOTO && photoPreview != null) {
                            Image(photoPreview!!, contentDescription = "Private photo preview",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(12.dp)))
                        } else {
                            Box(Modifier.fillMaxWidth().height(122.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (media.kind == MediaKind.VIDEO) FocaaaPalette.Deep else FocaaaPalette.Pale),
                                contentAlignment = Alignment.Center) {
                                if (media.kind == MediaKind.VIDEO) {
                                    Text("▶", color = Color.White, fontSize = 36.sp)
                                } else Icon(painterResource(R.drawable.ic_chat_photo),
                                    contentDescription = null, tint = ChatForest, modifier = Modifier.size(36.dp))
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Icon(painterResource(if (media.kind == MediaKind.PHOTO) R.drawable.ic_chat_photo
                                else R.drawable.ic_chat_video), contentDescription = null, tint = ChatForest)
                            Text(if (media.kind == MediaKind.PHOTO) "Photo" else "Video",
                                color = ChatForest, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        }
                        Text((if (previewBusy) "Loading preview" else "Tap to view") +
                            " · ${media.plaintextSize / 1024} KB",
                            color = FocaaaPalette.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text(MediaMessageCodec.preview(message.text), color = FocaaaPalette.Ink,
                        style = MaterialTheme.typography.bodyLarge)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.align(Alignment.End)) {
                    Text(time, color = FocaaaPalette.Muted, style = MaterialTheme.typography.labelSmall)
                    if (isLocal && showDeliveryStatus) {
                        val (mark, description) = when (message.deliveryStatus) {
                            MessageDeliveryStatus.QUEUED -> "◷" to "Waiting to send"
                            MessageDeliveryStatus.SENT -> "✓" to "Sent to relay"
                            MessageDeliveryStatus.DELIVERED -> "✓✓" to "Delivered to partner"
                            MessageDeliveryStatus.READ -> "✓✓" to "Read by partner"
                        }
                        Text(
                            mark,
                            modifier = Modifier.semantics { contentDescription = description },
                            color = if (message.deliveryStatus == MessageDeliveryStatus.READ) FocaaaPalette.Read
                                else FocaaaPalette.Muted,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    var menuOpen by remember(message.id) { mutableStateOf(false) }
                    Box(Modifier.size(26.dp).clickable { menuOpen = true }, contentAlignment = Alignment.Center) {
                        Text("⋯", color = FocaaaPalette.Muted, style = MaterialTheme.typography.labelMedium)
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Delete on this phone") }, onClick = { menuOpen = false; onDelete() })
                        }
                    }
                }
            }
        }
    }
}

/** Plays decrypted video directly from memory, without creating a plaintext cache file. */
@Composable
private fun PrivateVideoViewer(bytes: ByteArray, modifier: Modifier = Modifier) {
    val source = remember(bytes) { object : MediaDataSource() {
        override fun getSize(): Long = bytes.size.toLong()
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position < 0 || position >= bytes.size) return -1
            val count = minOf(size, bytes.size - position.toInt())
            System.arraycopy(bytes, position.toInt(), buffer, offset, count)
            return count
        }
        override fun close() { bytes.fill(0) }
    } }
    val player = remember(bytes) { MediaPlayer() }
    var ready by remember(bytes) { mutableStateOf(false) }
    var playing by remember(bytes) { mutableStateOf(false) }
    var playbackError by remember(bytes) { mutableStateOf(false) }
    var sourceReady by remember(bytes) { mutableStateOf(false) }
    var preparedRequested by remember(bytes) { mutableStateOf(false) }
    var activeHolder by remember(bytes) { mutableStateOf<SurfaceHolder?>(null) }
    var durationMs by remember(bytes) { mutableIntStateOf(0) }
    var positionMs by remember(bytes) { mutableIntStateOf(0) }
    var scrubFraction by remember(bytes) { mutableStateOf<Float?>(null) }
    DisposableEffect(player, source) {
        player.setOnPreparedListener {
            it.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            durationMs = it.duration.coerceAtLeast(0)
            it.start(); ready = true; playing = true
        }
        player.setOnCompletionListener { playing = false; positionMs = durationMs }
        player.setOnErrorListener { _, _, _ -> playbackError = true; playing = false; true }
        runCatching { player.setDataSource(source) }
            .onSuccess { sourceReady = true }
            .onFailure { playbackError = true }
        onDispose { runCatching { player.release() }; source.close() }
    }
    LaunchedEffect(sourceReady, activeHolder) {
        val holder = activeHolder
        if (sourceReady && holder?.surface?.isValid == true && !preparedRequested) {
            preparedRequested = true
            runCatching { player.setDisplay(holder); player.prepareAsync() }
                .onFailure { playbackError = true }
        }
    }
    LaunchedEffect(player, ready, playing) {
        while (ready && playing) {
            positionMs = runCatching { player.currentPosition }.getOrDefault(positionMs)
            delay(300)
        }
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        AndroidView(factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) { activeHolder = holder }
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        activeHolder = null
                        if (ready && player.isPlaying) { player.pause(); playing = false }
                    }
                })
            }
        }, modifier = Modifier.fillMaxWidth().weight(1f))
        if (playbackError) Text("This video cannot be played on this phone.", color = Color.White)
        Surface(shape = RoundedCornerShape(18.dp), color = FocaaaPalette.Deep,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 7.dp)) {
                Slider(value = scrubFraction ?: if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                    onValueChange = { scrubFraction = it },
                    onValueChangeFinished = {
                        val seek = scrubFraction
                        if (seek != null && durationMs > 0) player.seekTo((durationMs * seek).toInt())
                        scrubFraction = null
                    }, enabled = ready && !playbackError && durationMs > 0,
                    colors = SliderDefaults.colors(thumbColor = FocaaaPalette.Pale,
                        activeTrackColor = FocaaaPalette.Pale, inactiveTrackColor = Color(0xFF6F9893)))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("${formatVideoTime(positionMs)} / ${formatVideoTime(durationMs)}",
                        color = Color(0xFFE5F1E8), style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f))
                    TextButton(enabled = ready && !playbackError, onClick = {
                        if (player.isPlaying) { player.pause(); playing = false }
                        else { player.start(); playing = true }
                    }) { Text(if (playing) "Pause" else if (positionMs >= durationMs && durationMs > 0) "Replay" else "Play",
                        color = Color.White, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

private fun formatVideoTime(milliseconds: Int): String {
    val seconds = (milliseconds.coerceAtLeast(0) / 1000)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
