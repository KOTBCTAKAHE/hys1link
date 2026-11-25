package io.github.kotbctakahe.hys1link.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import io.github.kotbctakahe.hys1link.core.LinkStickers
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.EmojiEmotions
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kotbctakahe.hys1link.core.Core
import io.github.kotbctakahe.hys1link.proto.Quote
import io.github.kotbctakahe.hys1link.store.AttachmentInfo
import io.github.kotbctakahe.hys1link.store.ConvKind
import io.github.kotbctakahe.hys1link.store.Conversation
import io.github.kotbctakahe.hys1link.store.Message
import io.github.kotbctakahe.hys1link.store.MsgStatus
import io.github.kotbctakahe.hys1link.ui.components.Avatar
import io.github.kotbctakahe.hys1link.ui.components.AvatarKind
import io.github.kotbctakahe.hys1link.ui.components.avatarColor
import io.github.kotbctakahe.hys1link.ui.components.clock
import io.github.kotbctakahe.hys1link.ui.components.dayHeader
import io.github.kotbctakahe.hys1link.ui.components.sameDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val quickReactions = listOf("❤️", "👍", "😂", "😮", "😢", "🙏")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(core: Core, convId: String, onBack: () -> Unit) {
    val version by core.db.version.collectAsState()
    val conv by produceState<Conversation?>(null, version) { value = withContext(Dispatchers.IO) { core.repo.conversation(convId) } }
    val messages by produceState(emptyList<Message>(), version) { value = withContext(Dispatchers.IO) { core.repo.messages(convId) } }
    val typing by core.receiver.typing.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var draft by remember { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<Message?>(null) }
    val listState = rememberLazyListState()
    val self = core.account.aci
    var stickersOpen by remember { mutableStateOf(false) }
    val ctxPick = LocalContext.current
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val caption = draft.trim().takeIf { it.isNotEmpty() }
        draft = ""
        val q = replyTo?.let { Quote(it.sentTs, it.author, it.body) }; replyTo = null
        scope.launch(Dispatchers.IO) {
            runCatching {
                val files = uris.mapNotNull { u -> MediaPrep.image(ctxPick, u) }
                if (files.isEmpty()) error("Не удалось прочитать файл")
                core.sender.sendMessage(convId, caption, files, q)
            }.onFailure { e -> withContext(Dispatchers.Main) { snackbar.showSnackbar("Фото не отправлено: ${e.message}") } }
        }
    }
    LaunchedEffect(convId) { core.repo.conversation(convId)?.let { if (it.kind == ConvKind.GROUP) core.groups.refreshTitleAsync(it) } }

    DisposableEffect(convId) {
        core.notifications.visibleConversation = convId
        core.notifications.cancel(convId)
        onDispose { if (core.notifications.visibleConversation == convId) core.notifications.visibleConversation = null }
    }

    // read state + read receipts for newly seen incoming messages
    LaunchedEffect(messages.size) {
        val unread = messages.filter { !it.outgoing && it.status == MsgStatus.RECEIVED }
        if (unread.isNotEmpty()) withContext(Dispatchers.IO) {
            core.repo.markConversationIncomingRead(convId)
            unread.groupBy { it.author }.forEach { (author, list) -> if (author != null) core.sender.sendReadReceipts(author, list.map { it.sentTs }) }
        }
        if (messages.isNotEmpty()) listState.animateScrollToItem(0)
    }

    // typing indicator out
    LaunchedEffect(draft.isNotEmpty()) {
        if (draft.isNotEmpty()) withContext(Dispatchers.IO) { core.sender.sendTyping(convId, true) }
    }

    val c = conv
    val isTyping = typing[convId]?.let { it > System.currentTimeMillis() } == true
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") } },
                title = {
                    if (c != null) Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(c.title, c.peer ?: c.id, c.avatarPath, 38.dp,
                            when (c.kind) { ConvKind.GROUP -> AvatarKind.GROUP; ConvKind.NOTE_TO_SELF -> AvatarKind.SELF; else -> AvatarKind.PERSON })
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(c.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val sub = when {
                                isTyping -> "печатает…"
                                c.kind == ConvKind.GROUP -> core.groups.cached(c.id)?.let { "${it.members.size} участников" } ?: "группа"
                                c.kind == ConvKind.NOTE_TO_SELF -> "только вы"
                                else -> core.repo.contact(c.peer!!)?.e164
                            }
                            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall,
                                color = if (isTyping) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column {
            if (stickersOpen) StickerPanel(core) { item ->
                stickersOpen = false
                scope.launch(Dispatchers.IO) {
                    val ts = System.currentTimeMillis()
                    runCatching { core.sender.sendMessage(convId, LinkStickers.build(item, ts), emptyList(), null, ts) }
                        .onFailure { e -> withContext(Dispatchers.Main) { snackbar.showSnackbar("Стикер не отправлен: ${e.message}") } }
                }
            }
            Composer(
                draft = draft, onDraft = { draft = it }, replyTo = replyTo,
                stickersOpen = stickersOpen, onToggleStickers = { stickersOpen = !stickersOpen },
                onAttach = { pickMedia.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                replyAuthor = replyTo?.let { authorName(core, it) }, onCancelReply = { replyTo = null },
                onSend = {
                    val text = draft.trim()
                    if (text.isEmpty()) return@Composer
                    val q = replyTo?.let { Quote(it.sentTs, it.author, it.body) }
                    draft = ""; replyTo = null
                    scope.launch(Dispatchers.IO) {
                        runCatching { core.sender.sendText(convId, text, q) }
                            .onFailure { e -> withContext(Dispatchers.Main) { snackbar.showSnackbar("Не отправлено: ${e.message}") } }
                        core.sender.sendTyping(convId, false)
                    }
                }
            )
            }
        }
    ) { pad ->
        val reversed = messages.asReversed()
        LazyColumn(
            state = listState, reverseLayout = true,
            modifier = Modifier.fillMaxSize().background(
                androidx.compose.ui.graphics.Brush.verticalGradient(listOf(
                    MaterialTheme.colorScheme.surfaceContainerLow,
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                ))
            ),
            contentPadding = PaddingValues(top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 8.dp, start = 10.dp, end = 10.dp),
        ) {
            itemsIndexed(reversed, key = { _, m -> m.id }) { i, m ->
                val older = reversed.getOrNull(i + 1)
                val newer = reversed.getOrNull(i - 1)
                val groupedWithNewer = newer != null && newer.author == m.author && newer.sentTs - m.sentTs < 3 * 60_000 && sameDay(newer.sentTs, m.sentTs)
                Column {
                    if (older == null || !sameDay(older.sentTs, m.sentTs)) DayChip(dayHeader(m.sentTs))
                    Bubble(
                        core, m, isGroup = c?.kind == ConvKind.GROUP, self = self, tail = !groupedWithNewer,
                        showAuthor = c?.kind == ConvKind.GROUP && !m.outgoing && (older == null || older.author != m.author),
                        onReply = { replyTo = m },
                        onReact = { emoji ->
                            scope.launch(Dispatchers.IO) {
                                val mine = m.reactions[self] == emoji
                                runCatching { core.sender.sendReaction(convId, m.author ?: return@launch, m.sentTs, emoji, mine) }
                                    .onFailure { e -> withContext(Dispatchers.Main) { snackbar.showSnackbar("Реакция не отправлена: ${e.message}") } }
                            }
                        },
                    )
                    Spacer(Modifier.height(if (groupedWithNewer) 2.dp else 8.dp))
                }
            }
        }
    }
}

private fun authorName(core: Core, m: Message): String =
    if (m.outgoing) "Вы" else m.author?.let { core.repo.contact(it)?.displayName } ?: "Собеседник"

@Composable
private fun DayChip(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.85f))
                .padding(horizontal = 12.dp, vertical = 4.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    core: Core, m: Message, isGroup: Boolean, self: String?, tail: Boolean, showAuthor: Boolean,
    onReply: () -> Unit, onReact: (String) -> Unit,
) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val out = m.outgoing
    val scheme = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (out) scheme.primary else scheme.surfaceContainerLowest, label = "bubble")
    val fg = if (out) scheme.onPrimary else scheme.onSurface
    val big = 22.dp; val small = 6.dp
    val shape = if (out) RoundedCornerShape(big, big, if (tail) small else big, big)
                else RoundedCornerShape(big, big, big, if (tail) small else big)

    val linkSticker = remember(m.body, m.sentTs) { m.body?.let { LinkStickers.parse(it, m.sentTs) } }
    val signalSticker = m.attachments.singleOrNull()?.takeIf { it.fileName == "sticker.webp" }
    val isSticker = !m.deleted && (linkSticker != null || (signalSticker != null && m.body.isNullOrEmpty()))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (out) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
        if (isGroup && !out) {
            if (tail && m.author != null) {
                val ct = remember(m.author) { core.repo.contact(m.author) }
                Avatar(ct?.displayName ?: "?", m.author, ct?.avatarPath, 30.dp)
            } else Spacer(Modifier.width(30.dp))
            Spacer(Modifier.width(6.dp))
        }
        Box {
            if (isSticker) {
                Column(horizontalAlignment = if (out) Alignment.End else Alignment.Start,
                    modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { menu = true })) {
                    if (showAuthor && m.author != null) Text(core.repo.contact(m.author)?.displayName ?: "Участник",
                        style = MaterialTheme.typography.labelLarge, color = avatarColor(m.author))
                    val model: Any? = linkSticker?.url ?: signalSticker?.localPath?.let { File(it) }
                    if (model != null) coil.compose.AsyncImage(model, linkSticker?.emoji ?: "Стикер", Modifier.size(150.dp))
                    else Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) { Text(linkSticker?.emoji ?: "🧩", style = MaterialTheme.typography.displayMedium) }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(RoundedCornerShape(10.dp))
                        .background(scheme.surfaceContainerLowest.copy(alpha = 0.8f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Text(clock(m.sentTs), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                        if (out) { Spacer(Modifier.width(4.dp)); StatusIcon(m.status, scheme.onSurfaceVariant) }
                    }
                }
            } else
            Column(horizontalAlignment = if (out) Alignment.End else Alignment.Start) {
                Surface(
                    shape = shape, color = bg, tonalElevation = 0.dp, shadowElevation = if (out) 0.dp else 1.dp,
                    modifier = Modifier.widthIn(max = 320.dp).clip(shape)
                        .combinedClickable(onClick = {}, onLongClick = { menu = true }, onDoubleClick = { onReact("❤️") })
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        if (showAuthor && m.author != null) {
                            Text(core.repo.contact(m.author)?.displayName ?: "Участник", style = MaterialTheme.typography.labelLarge,
                                color = avatarColor(m.author), modifier = Modifier.padding(bottom = 2.dp))
                        }
                        if (m.quoteTs != null) QuoteBlock(core, m, self, fg)
                        if (m.deleted) {
                            Text("Сообщение удалено", color = fg.copy(alpha = 0.6f), fontStyle = FontStyle.Italic)
                        } else {
                            m.attachments.forEachIndexed { i, a -> AttachmentView(core, m.id, i, a, fg) }
                            if (!m.body.isNullOrEmpty()) Text(m.body, color = fg, style = MaterialTheme.typography.bodyLarge)
                        }
                        Row(Modifier.align(Alignment.End).padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(clock(m.sentTs), style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.6f))
                            if (out) {
                                Spacer(Modifier.width(4.dp))
                                StatusIcon(m.status, fg)
                            }
                        }
                    }
                }
                if (m.reactions.isNotEmpty()) {
                    Row(
                        Modifier.offset(y = (-6).dp).padding(horizontal = 8.dp).clip(RoundedCornerShape(12.dp))
                            .background(scheme.surfaceContainerHighest).padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        val counts = m.reactions.values.groupingBy { it }.eachCount()
                        counts.forEach { (e, n) -> Text(if (n > 1) "$e $n" else e, style = MaterialTheme.typography.labelMedium) ; Spacer(Modifier.width(4.dp)) }
                    }
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                Row(Modifier.padding(horizontal = 8.dp)) {
                    quickReactions.forEach { e ->
                        Text(e, style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.clip(CircleShape).clickable { menu = false; onReact(e) }.padding(6.dp))
                    }
                }
                DropdownMenuItem(text = { Text("Ответить") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Reply, null) },
                    onClick = { menu = false; onReply() })
                if (!m.body.isNullOrEmpty()) DropdownMenuItem(text = { Text("Копировать") }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                    onClick = {
                        menu = false
                        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("message", m.body))
                    })
            }
        }
    }
}

@Composable
private fun StatusIcon(status: Int, fg: Color) {
    val (icon, desc) = when (status) {
        MsgStatus.PENDING -> Icons.Rounded.Schedule to "Отправляется"
        MsgStatus.SENT -> Icons.Rounded.Done to "Отправлено"
        MsgStatus.DELIVERED -> Icons.Rounded.DoneAll to "Доставлено"
        MsgStatus.READ -> Icons.Rounded.DoneAll to "Прочитано"
        else -> Icons.Rounded.ErrorOutline to "Не отправлено"
    }
    val tint = when (status) {
        MsgStatus.READ -> fg
        MsgStatus.FAILED -> MaterialTheme.colorScheme.error
        else -> fg.copy(alpha = 0.6f)
    }
    Icon(icon, desc, Modifier.size(14.dp), tint = tint)
}

@Composable
private fun QuoteBlock(core: Core, m: Message, self: String?, fg: Color) {
    val name = if (m.quoteAuthor == self) "Вы" else m.quoteAuthor?.let { core.repo.contact(it)?.displayName } ?: "Сообщение"
    Row(Modifier.padding(bottom = 6.dp).clip(RoundedCornerShape(10.dp)).background(fg.copy(alpha = 0.07f))) {
        Box(Modifier.width(3.dp).height(40.dp).background(m.quoteAuthor?.let { avatarColor(it) } ?: fg))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = fg)
            Text(m.quoteText ?: "Вложение", style = MaterialTheme.typography.bodySmall, color = fg.copy(alpha = 0.75f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AttachmentView(core: Core, messageId: Long, index: Int, a: AttachmentInfo, fg: Color) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    val isImage = a.contentType?.startsWith("image/") == true
    val bmp = remember(a.localPath) {
        a.localPath?.takeIf { isImage }?.let { p -> runCatching {
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeFile(File(p).absolutePath, opts)?.asImageBitmap()
        }.getOrNull() }
    }
    if (bmp != null) {
        Image(bmp, null, contentScale = ContentScale.Crop,
            modifier = Modifier.padding(bottom = 4.dp).widthIn(max = 296.dp).heightIn(max = 360.dp).clip(RoundedCornerShape(14.dp)))
        return
    }
    val label = when {
        isImage -> "Фото"; a.contentType?.startsWith("video/") == true -> "Видео"
        a.contentType?.startsWith("audio/") == true -> "Голосовое сообщение"; else -> a.fileName ?: "Файл"
    }
    val size = a.size?.let { if (it > 1_000_000) "%.1f МБ".format(it / 1_000_000f) else "${it / 1000} КБ" } ?: ""
    Row(
        Modifier.padding(bottom = 4.dp).clip(RoundedCornerShape(12.dp)).background(fg.copy(alpha = 0.07f))
            .clickable(enabled = a.localPath == null && !loading) {
                loading = true
                scope.launch(Dispatchers.IO) { runCatching { core.attachments.download(messageId, index, a) }; loading = false }
            }.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
            Text(if (a.localPath != null) "Сохранено, $size" else if (loading) "Загрузка…" else "Нажмите, чтобы загрузить ($size)",
                style = MaterialTheme.typography.bodySmall, color = fg.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun Composer(
    draft: String, onDraft: (String) -> Unit, replyTo: Message?, replyAuthor: String?,
    onCancelReply: () -> Unit, onSend: () -> Unit,
    stickersOpen: Boolean = false, onToggleStickers: () -> Unit = {}, onAttach: () -> Unit = {},
) {
    Surface(color = Color.Transparent) {
        Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 8.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(30.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
            AnimatedVisibility(replyTo != null, enter = expandVertically(), exit = shrinkVertically()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(3.dp).height(36.dp).background(MaterialTheme.colorScheme.primary))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Ответ: ${replyAuthor ?: ""}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(replyTo?.body ?: "Вложение", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = onCancelReply) { Icon(Icons.Rounded.Close, "Отменить ответ") }
                }
            }
            Row(Modifier.padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = onToggleStickers, modifier = Modifier.padding(bottom = 2.dp)) {
                    Icon(if (stickersOpen) Icons.Rounded.Keyboard else Icons.Rounded.EmojiEmotions, if (stickersOpen) "Скрыть стикеры" else "Стикеры",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextField(
                    value = draft, onValueChange = onDraft, placeholder = { Text("Сообщение") },
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp, max = 160.dp),
                    shape = RoundedCornerShape(26.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                )
                if (draft.isBlank()) IconButton(onClick = onAttach, modifier = Modifier.padding(bottom = 2.dp)) {
                    Icon(Icons.Rounded.AddPhotoAlternate, "Отправить фото", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(4.dp))
                val enabled = draft.isNotBlank()
                val sendBg by animateColorAsState(if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest, label = "send")
                Box(
                    Modifier.size(52.dp).clip(CircleShape).background(sendBg).clickable(enabled = enabled, onClick = onSend),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, "Отправить",
                        tint = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
