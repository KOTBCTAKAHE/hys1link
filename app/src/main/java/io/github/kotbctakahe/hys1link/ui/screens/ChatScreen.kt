package io.github.kotbctakahe.hys1link.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.EmojiEmotions
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shortcut
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import io.github.kotbctakahe.hys1link.core.Core
import io.github.kotbctakahe.hys1link.core.LinkStickers
import io.github.kotbctakahe.hys1link.core.Sender
import io.github.kotbctakahe.hys1link.proto.Mention
import io.github.kotbctakahe.hys1link.proto.Quote
import io.github.kotbctakahe.hys1link.store.AttachmentInfo
import io.github.kotbctakahe.hys1link.store.ConvKind
import io.github.kotbctakahe.hys1link.store.Conversation
import io.github.kotbctakahe.hys1link.store.Message
import io.github.kotbctakahe.hys1link.store.MsgKind
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
private const val OBJ = '￼' // Signal mention placeholder

private val timerChoices = listOf(0 to "Выключено", 30 to "30 секунд", 300 to "5 минут", 3600 to "1 час", 28800 to "8 часов",
    86400 to "1 день", 604800 to "1 неделя", 2419200 to "4 недели")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(core: Core, convId: String, onBack: () -> Unit, onOpenInfo: (String) -> Unit = {}) {
    val version by core.db.version.collectAsState()
    val conv by produceState<Conversation?>(null, version) { value = withContext(Dispatchers.IO) { core.repo.conversation(convId) } }
    val messages by produceState(emptyList<Message>(), version) { value = withContext(Dispatchers.IO) { core.repo.messages(convId) } }
    val typing by core.receiver.typing.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val ctx = LocalContext.current
    var draft by remember { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<Message?>(null) }
    var stickersOpen by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var timerDialog by remember { mutableStateOf(false) }
    var forwarding by remember { mutableStateOf<Message?>(null) }
    var viewing by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var attachMenu by remember { mutableStateOf(false) }
    var viewOnceNext by remember { mutableStateOf(false) }
    val mentionPicks = remember { mutableStateListOf<Pair<String, String>>() } // display name -> uuid
    val listState = rememberLazyListState()
    val self = core.account.aci

    fun fail(prefix: String, e: Throwable) { scope.launch { snackbar.showSnackbar("$prefix: ${e.message ?: e.javaClass.simpleName}") } }

    fun sendFiles(files: List<Sender.LocalFile>, viewOnce: Boolean = false) {
        val caption = draft.trim().takeIf { it.isNotEmpty() }
        draft = ""
        val q = replyTo?.let { Quote(it.sentTs, it.author, it.body) }; replyTo = null
        scope.launch(Dispatchers.IO) {
            runCatching { core.sender.sendMessage(convId, caption, files, q, viewOnce = viewOnce) }.onFailure { fail("Не отправлено", it) }
        }
    }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val vo = viewOnceNext; viewOnceNext = false
        scope.launch(Dispatchers.IO) {
            val files = uris.mapNotNull { u -> runCatching { FilePrep.file(ctx, u) }.onFailure { fail("Файл не прочитан", it) }.getOrNull() }
            withContext(Dispatchers.Main) { if (files.isNotEmpty()) sendFiles(files, vo) }
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            runCatching { FilePrep.file(ctx, uri) ?: error("не удалось прочитать") }
                .onSuccess { f -> withContext(Dispatchers.Main) { sendFiles(listOf(f)) } }.onFailure { fail("Файл не отправлен", it) }
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val recorder = remember { VoiceRecorder(ctx) }
    var recording by remember { mutableStateOf(false) }

    DisposableEffect(convId) {
        core.notifications.visibleConversation = convId
        core.notifications.cancel(convId)
        onDispose { if (core.notifications.visibleConversation == convId) core.notifications.visibleConversation = null; if (recording) recorder.stop(true) }
    }
    LaunchedEffect(convId) { core.repo.conversation(convId)?.let { if (it.kind == ConvKind.GROUP) core.groups.refreshTitleAsync(it) } }
    LaunchedEffect(messages.size) {
        val unread = messages.filter { !it.outgoing && it.status == MsgStatus.RECEIVED && it.kind == MsgKind.NORMAL }
        if (unread.isNotEmpty()) withContext(Dispatchers.IO) {
            core.repo.markConversationIncomingRead(convId)
            unread.groupBy { it.author }.forEach { (author, list) -> if (author != null) core.sender.sendReadReceipts(author, list.map { it.sentTs }) }
        }
        if (messages.isNotEmpty() && !searching) listState.animateScrollToItem(0)
    }
    LaunchedEffect(draft.isNotEmpty()) { if (draft.isNotEmpty()) withContext(Dispatchers.IO) { core.sender.sendTyping(convId, true) } }

    val c = conv
    val isGroup = c?.kind == ConvKind.GROUP
    val members = remember(c?.id, version) { if (isGroup) core.groups.cached(convId)?.members.orEmpty().filter { it.uuid != self } else emptyList() }
    val isTyping = typing[convId]?.let { it > System.currentTimeMillis() } == true
    val shown = if (searching && query.isNotBlank()) messages.filter { it.body?.contains(query, true) == true } else messages

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { if (searching) { searching = false; query = "" } else onBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") } },
                title = {
                    if (searching) TextField(query, { query = it }, placeholder = { Text("Поиск в чате") }, singleLine = true,
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent), modifier = Modifier.fillMaxWidth())
                    else if (c != null) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onOpenInfo(convId) }) {
                        Avatar(c.title, c.peer ?: c.id, c.avatarPath, 38.dp,
                            when (c.kind) { ConvKind.GROUP -> AvatarKind.GROUP; ConvKind.NOTE_TO_SELF -> AvatarKind.SELF; else -> AvatarKind.PERSON })
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(c.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val sub = when {
                                isTyping -> "печатает…"
                                c.expireTimer > 0 -> "исчезающие: " + (timerChoices.firstOrNull { it.first == c.expireTimer }?.second ?: "${c.expireTimer} с")
                                c.kind == ConvKind.GROUP -> core.groups.cached(c.id)?.let { "${it.members.size} участников" } ?: "группа"
                                c.kind == ConvKind.NOTE_TO_SELF -> "только вы"
                                else -> core.repo.contact(c.peer!!)?.e164
                            }
                            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall,
                                color = if (isTyping) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    if (searching) { if (query.isNotEmpty()) Text("${shown.size}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 12.dp)) }
                    else {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Rounded.Search, "Поиск в чате") }
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, "Ещё") }
                            DropdownMenu(menuOpen, { menuOpen = false }) {
                                DropdownMenuItem({ Text("Исчезающие сообщения") }, { menuOpen = false; timerDialog = true }, leadingIcon = { Icon(Icons.Rounded.Timer, null) })
                                DropdownMenuItem({ Text(if (isGroup) "О группе" else "О контакте") }, { menuOpen = false; onOpenInfo(convId) }, leadingIcon = { Icon(Icons.Rounded.Info, null) })
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column {
                // @mention suggestions in groups
                val token = Regex("@([\\p{L}\\p{N}_]*)$").find(draft)?.groupValues?.get(1)
                if (isGroup && token != null) {
                    val opts = members.mapNotNull { m -> core.repo.contact(m.uuid)?.let { it.displayName to m.uuid } }
                        .filter { it.first.contains(token, true) }.take(5)
                    if (opts.isNotEmpty()) Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.padding(horizontal = 10.dp).fillMaxWidth()) {
                        Column {
                            opts.forEach { (name, uuid) ->
                                Row(Modifier.fillMaxWidth().clickable {
                                    draft = draft.removeSuffix("@$token") + "@$name "
                                    mentionPicks += name to uuid
                                }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Avatar(name, uuid, core.repo.contact(uuid)?.avatarPath, 30.dp)
                                    Spacer(Modifier.width(10.dp)); Text(name)
                                }
                            }
                        }
                    }
                }
                if (stickersOpen) StickerPanel(core) { item ->
                    stickersOpen = false
                    scope.launch(Dispatchers.IO) {
                        val ts = System.currentTimeMillis()
                        runCatching { core.sender.sendMessage(convId, LinkStickers.build(item, ts), emptyList(), null, ts) }.onFailure { fail("Стикер не отправлен", it) }
                    }
                }
                Composer(
                    draft = draft, onDraft = { draft = it }, replyTo = replyTo,
                    replyAuthor = replyTo?.let { authorName(core, it) }, onCancelReply = { replyTo = null },
                    stickersOpen = stickersOpen, onToggleStickers = { stickersOpen = !stickersOpen },
                    attachMenu = attachMenu, onAttachMenu = { attachMenu = it },
                    onPickMedia = { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                    onPickViewOnce = { viewOnceNext = true; pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onPickFile = { pickFile.launch(arrayOf("*/*")) },
                    recording = recording,
                    onRecordStart = {
                        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            micPermission.launch(Manifest.permission.RECORD_AUDIO); false
                        } else runCatching { recorder.start(); recording = true; true }.getOrElse { fail("Микрофон недоступен", it); false }
                    },
                    onRecordStop = { cancel ->
                        recording = false
                        recorder.stop(cancel)?.let { sendFiles(listOf(it)) }
                    },
                    onSend = {
                        val text = draft.trim()
                        if (text.isEmpty()) return@Composer
                        val (body, mentions) = applyMentions(text, mentionPicks)
                        mentionPicks.clear()
                        val q = replyTo?.let { Quote(it.sentTs, it.author, it.body) }
                        draft = ""; replyTo = null
                        scope.launch(Dispatchers.IO) {
                            runCatching { core.sender.sendMessage(convId, body, emptyList(), q, mentions = mentions) }.onFailure { fail("Не отправлено", it) }
                            core.sender.sendTyping(convId, false)
                        }
                    }
                )
            }
        }
    ) { pad ->
        val reversed = shown.asReversed()
        LazyColumn(
            state = listState, reverseLayout = true,
            modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
                MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)))),
            contentPadding = PaddingValues(top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 8.dp, start = 10.dp, end = 10.dp),
        ) {
            itemsIndexed(reversed, key = { _, m -> m.id }) { i, m ->
                val older = reversed.getOrNull(i + 1)
                val newer = reversed.getOrNull(i - 1)
                val grouped = newer != null && newer.author == m.author && newer.kind == MsgKind.NORMAL && newer.sentTs - m.sentTs < 3 * 60_000 && sameDay(newer.sentTs, m.sentTs)
                Column {
                    if (older == null || !sameDay(older.sentTs, m.sentTs)) DayChip(dayHeader(m.sentTs))
                    if (m.kind == MsgKind.SYSTEM) SystemLine(m.body ?: "")
                    else Bubble(
                        core, m, isGroup = isGroup, self = self, tail = !grouped,
                        showAuthor = isGroup && !m.outgoing && (older == null || older.author != m.author),
                        highlight = if (searching) query else null,
                        onReply = { replyTo = m },
                        onReact = { emoji ->
                            scope.launch(Dispatchers.IO) {
                                val mine = m.reactions[self] == emoji
                                runCatching { core.sender.sendReaction(convId, m.author ?: return@launch, m.sentTs, emoji, mine) }.onFailure { fail("Реакция не отправлена", it) }
                            }
                        },
                        onForward = { forwarding = m },
                        onDeleteLocal = { scope.launch(Dispatchers.IO) { core.repo.deleteLocal(m.id) } },
                        onDeleteEveryone = { scope.launch(Dispatchers.IO) { runCatching { core.sender.deleteForEveryone(convId, m) }.onFailure { fail("Не удалено", it) } } },
                        onOpenMedia = { path, type -> viewing = path to type },
                        onViewOnce = { a ->
                            a.localPath?.let { viewing = it to a.contentType }
                            scope.launch(Dispatchers.IO) {
                                kotlinx.coroutines.delay(1500)
                                core.repo.markViewOnceViewed(m.author, m.sentTs)
                                m.author?.let { core.sender.syncViewOnceOpened(it, m.sentTs) }
                            }
                        },
                    )
                    Spacer(Modifier.height(if (grouped) 2.dp else 8.dp))
                }
            }
        }
    }

    viewing?.let { (path, type) -> MediaViewer(path, type) { viewing = null } }

    if (timerDialog) AlertDialog(onDismissRequest = { timerDialog = false }, title = { Text("Исчезающие сообщения") },
        text = {
            Column {
                Text("Новые сообщения в этом чате будут удаляться у всех через выбранное время после прочтения.",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
                timerChoices.forEach { (sec, label) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                        timerDialog = false
                        scope.launch(Dispatchers.IO) { runCatching { core.sender.setExpireTimer(convId, sec) }.onFailure { fail("Не удалось", it) } }
                    }.padding(vertical = 12.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, modifier = Modifier.weight(1f))
                        if ((c?.expireTimer ?: 0) == sec) Icon(Icons.Rounded.Done, "Выбрано", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { timerDialog = false }) { Text("Закрыть") } })

    forwarding?.let { fm ->
        ForwardDialog(core, onDismiss = { forwarding = null }) { target ->
            forwarding = null
            scope.launch(Dispatchers.IO) {
                runCatching {
                    val files = fm.attachments.mapNotNull { a -> a.localPath?.let { p -> Sender.LocalFile(File(p).readBytes(), a.contentType ?: "application/octet-stream", a.fileName, a.width, a.height) } }
                    if (fm.body.isNullOrEmpty() && files.isEmpty()) error("нечего пересылать — вложение не загружено")
                    core.sender.sendMessage(target, fm.body, files)
                }.onSuccess { withContext(Dispatchers.Main) { snackbar.showSnackbar("Переслано") } }.onFailure { fail("Не переслано", it) }
            }
        }
    }
}

/** Turns "@Name" picks into Signal body ranges with U+FFFC placeholders. */
private fun applyMentions(text: String, picks: List<Pair<String, String>>): Pair<String, List<Mention>> {
    if (picks.isEmpty()) return text to emptyList()
    var body = text
    val out = ArrayList<Mention>()
    picks.distinct().forEach { (name, uuid) ->
        var idx = body.indexOf("@$name")
        while (idx >= 0) {
            body = body.substring(0, idx) + OBJ + body.substring(idx + name.length + 1)
            out += Mention(idx, 1, uuid)
            idx = body.indexOf("@$name")
        }
    }
    // positions may shift as earlier replacements shrink the text; recompute from placeholders in order
    val positions = body.indices.filter { body[it] == OBJ }
    return body to out.sortedBy { it.start }.mapIndexed { i, m -> m.copy(start = positions.getOrElse(i) { m.start }) }
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

@Composable
private fun SystemLine(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)).padding(horizontal = 12.dp, vertical = 6.dp)) {
            Icon(Icons.Rounded.Timer, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

/** Renders body text with mentions (U+FFFC → @Name), links and search highlight. */
@Composable
private fun richBody(core: Core, m: Message, fg: Color, highlight: String?): AnnotatedString {
    val body = m.body ?: ""
    val accent = if (m.outgoing) fg else MaterialTheme.colorScheme.primary
    val hl = MaterialTheme.colorScheme.tertiaryContainer
    return remember(body, m.mentions, highlight, fg) {
        buildAnnotatedString {
            val ranges = m.mentions.associateBy { it.start }
            var i = 0
            while (i < body.length) {
                val mm = ranges[i]
                if (mm != null) {
                    val name = core.repo.contact(mm.uuid)?.displayName ?: "участник"
                    withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) { append("@$name") }
                    i += mm.length.coerceAtLeast(1); continue
                }
                append(body[i]); i++
            }
            val text = toAnnotatedString().text
            Regex("https?://\\S+").findAll(text).forEach { r ->
                addStyle(SpanStyle(color = accent, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline), r.range.first, r.range.last + 1)
                addStringAnnotation("url", r.value, r.range.first, r.range.last + 1)
            }
            if (!highlight.isNullOrBlank()) {
                var idx = text.indexOf(highlight, ignoreCase = true)
                while (idx >= 0) { addStyle(SpanStyle(background = hl), idx, idx + highlight.length); idx = text.indexOf(highlight, idx + 1, true) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    core: Core, m: Message, isGroup: Boolean, self: String?, tail: Boolean, showAuthor: Boolean, highlight: String?,
    onReply: () -> Unit, onReact: (String) -> Unit, onForward: () -> Unit, onDeleteLocal: () -> Unit, onDeleteEveryone: () -> Unit,
    onOpenMedia: (String, String?) -> Unit, onViewOnce: (AttachmentInfo) -> Unit,
) {
    val ctx = LocalContext.current
    val uri = LocalUriHandler.current
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val out = m.outgoing
    val scheme = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (out) scheme.primary else scheme.surfaceContainerLowest, label = "bubble")
    val fg = if (out) scheme.onPrimary else scheme.onSurface
    val big = 22.dp; val small = 6.dp
    val shape = if (out) RoundedCornerShape(big, big, if (tail) small else big, big) else RoundedCornerShape(big, big, big, if (tail) small else big)
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
            val clickMod = Modifier.combinedClickable(onClick = {}, onLongClick = { menu = true }, onDoubleClick = { onReact("❤️") })
            Column(horizontalAlignment = if (out) Alignment.End else Alignment.Start) {
                if (isSticker) {
                    Column(horizontalAlignment = if (out) Alignment.End else Alignment.Start, modifier = clickMod) {
                        if (showAuthor && m.author != null) Text(core.repo.contact(m.author)?.displayName ?: "Участник", style = MaterialTheme.typography.labelLarge, color = avatarColor(m.author))
                        val model: Any? = linkSticker?.url ?: signalSticker?.localPath?.let { File(it) }
                        if (model != null) AsyncImage(model, linkSticker?.emoji ?: "Стикер", Modifier.size(150.dp))
                        else Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) { Text(linkSticker?.emoji ?: "🧩", style = MaterialTheme.typography.displayMedium) }
                        MetaRow(m, scheme.onSurfaceVariant, Modifier.clip(RoundedCornerShape(10.dp)).background(scheme.surfaceContainerLowest.copy(alpha = 0.8f)).padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                } else Surface(shape = shape, color = bg, shadowElevation = if (out) 0.dp else 1.dp, modifier = Modifier.widthIn(max = 320.dp).clip(shape).then(clickMod)) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        if (showAuthor && m.author != null) Text(core.repo.contact(m.author)?.displayName ?: "Участник", style = MaterialTheme.typography.labelLarge,
                            color = avatarColor(m.author), modifier = Modifier.padding(bottom = 2.dp))
                        if (m.quoteTs != null) QuoteBlock(core, m, self, fg)
                        when {
                            m.deleted -> Text("Сообщение удалено", color = fg.copy(alpha = 0.6f), fontStyle = FontStyle.Italic)
                            m.viewOnce > 0 -> ViewOnceView(m, fg, out, onViewOnce)
                            else -> {
                                m.attachments.forEachIndexed { i, a -> AttachmentView(core, m.id, i, a, fg, onOpenMedia) }
                                m.preview?.let { p -> PreviewCard(p, fg) { runCatching { uri.openUri(p.url) } } }
                                if (!m.body.isNullOrEmpty()) {
                                    val text = richBody(core, m, fg, highlight)
                                    androidx.compose.foundation.text.ClickableText(text, style = MaterialTheme.typography.bodyLarge.copy(color = fg)) { off ->
                                        text.getStringAnnotations("url", off, off).firstOrNull()?.let { runCatching { uri.openUri(it.item) } }
                                    }
                                }
                            }
                        }
                        MetaRow(m, fg.copy(alpha = 0.6f), Modifier.align(Alignment.End).padding(top = 2.dp))
                    }
                }
                if (m.reactions.isNotEmpty()) Row(Modifier.offset(y = (-6).dp).padding(horizontal = 8.dp).clip(RoundedCornerShape(12.dp))
                    .background(scheme.surfaceContainerHighest).padding(horizontal = 8.dp, vertical = 2.dp)) {
                    m.reactions.values.groupingBy { it }.eachCount().forEach { (e, n) -> Text(if (n > 1) "$e $n" else e, style = MaterialTheme.typography.labelMedium); Spacer(Modifier.width(4.dp)) }
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (!m.deleted) Row(Modifier.padding(horizontal = 8.dp)) {
                    quickReactions.forEach { e -> Text(e, style = MaterialTheme.typography.titleLarge, modifier = Modifier.clip(CircleShape).clickable { menu = false; onReact(e) }.padding(6.dp)) }
                }
                if (!m.deleted) DropdownMenuItem({ Text("Ответить") }, { menu = false; onReply() }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Reply, null) })
                if (!m.deleted && m.viewOnce == 0) DropdownMenuItem({ Text("Переслать") }, { menu = false; onForward() }, leadingIcon = { Icon(Icons.Rounded.Shortcut, null) })
                if (!m.body.isNullOrEmpty() && linkSticker == null) DropdownMenuItem({ Text("Копировать") }, {
                    menu = false
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("message", m.body.replace(OBJ.toString(), "@")))
                }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) })
                DropdownMenuItem({ Text("Удалить у меня") }, { menu = false; onDeleteLocal() }, leadingIcon = { Icon(Icons.Rounded.Delete, null) })
                if (out && !m.deleted) DropdownMenuItem({ Text("Удалить у всех") }, { menu = false; confirmDelete = true }, leadingIcon = { Icon(Icons.Rounded.DeleteForever, null) })
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Удалить у всех?") },
        text = { Text("Сообщение исчезнет у всех участников чата. Это нельзя отменить.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDeleteEveryone() }) { Text("Удалить", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } })
}

@Composable
private fun MetaRow(m: Message, color: Color, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (m.expiresIn > 0) { Icon(Icons.Rounded.Timer, "Исчезающее", Modifier.size(12.dp), tint = color); Spacer(Modifier.width(3.dp)) }
        Text(clock(m.sentTs), style = MaterialTheme.typography.labelSmall, color = color)
        if (m.outgoing) { Spacer(Modifier.width(4.dp)); StatusIcon(m.status, color) }
    }
}

@Composable
private fun ViewOnceView(m: Message, fg: Color, out: Boolean, onOpen: (AttachmentInfo) -> Unit) {
    val a = m.attachments.firstOrNull()
    val viewed = m.viewOnce == 2 || a == null
    Row(Modifier.clip(RoundedCornerShape(12.dp)).background(fg.copy(alpha = 0.08f))
        .clickable(enabled = !viewed && !out && a?.localPath != null) { a?.let(onOpen) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.LocalFireDepartment, null, tint = fg)
        Spacer(Modifier.width(8.dp))
        Text(when {
            out -> "Фото на один просмотр"
            viewed -> "Просмотрено"
            a?.localPath == null -> "Загрузка…"
            else -> "Открыть фото (один раз)"
        }, color = fg, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun PreviewCard(p: io.github.kotbctakahe.hys1link.store.PreviewInfo, fg: Color, onClick: () -> Unit) {
    Column(Modifier.padding(bottom = 6.dp).widthIn(max = 296.dp).clip(RoundedCornerShape(14.dp)).background(fg.copy(alpha = 0.08f)).clickable(onClick = onClick)) {
        p.imagePath?.let { AsyncImage(File(it), null, Modifier.fillMaxWidth().heightIn(max = 160.dp), contentScale = ContentScale.Crop) }
        Column(Modifier.padding(10.dp)) {
            p.title?.let { Text(it, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            p.description?.let { Text(it, color = fg.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            Text(android.net.Uri.parse(p.url).host ?: p.url, color = fg.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
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
    val tint = when (status) { MsgStatus.READ -> fg.copy(alpha = 1f); MsgStatus.FAILED -> MaterialTheme.colorScheme.error; else -> fg }
    Icon(icon, desc, Modifier.size(14.dp), tint = tint)
}

@Composable
private fun QuoteBlock(core: Core, m: Message, self: String?, fg: Color) {
    val name = if (m.quoteAuthor == self) "Вы" else m.quoteAuthor?.let { core.repo.contact(it)?.displayName } ?: "Сообщение"
    Row(Modifier.padding(bottom = 6.dp).clip(RoundedCornerShape(10.dp)).background(fg.copy(alpha = 0.08f))) {
        Box(Modifier.width(3.dp).height(40.dp).background(m.quoteAuthor?.let { avatarColor(it) } ?: fg))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = fg)
            Text(m.quoteText?.replace(OBJ.toString(), "@") ?: "Вложение", style = MaterialTheme.typography.bodySmall, color = fg.copy(alpha = 0.75f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AttachmentView(core: Core, messageId: Long, index: Int, a: AttachmentInfo, fg: Color, onOpen: (String, String?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    val type = a.contentType ?: ""
    val isImage = type.startsWith("image/")
    val isVideo = type.startsWith("video/")
    val isAudio = type.startsWith("audio/")
    fun download(then: ((String) -> Unit)? = null) {
        if (loading) return
        loading = true
        scope.launch(Dispatchers.IO) {
            val p = runCatching { core.attachments.download(messageId, index, a) }.getOrNull()
            loading = false
            if (p != null && then != null) withContext(Dispatchers.Main) { then(p) }
        }
    }
    when {
        isImage && a.localPath != null -> {
            val ratio = if ((a.width ?: 0) > 0 && (a.height ?: 0) > 0) a.width!!.toFloat() / a.height!! else 1f
            AsyncImage(File(a.localPath), "Фото", contentScale = ContentScale.Crop,
                modifier = Modifier.padding(bottom = 4.dp).widthIn(max = 296.dp).heightIn(max = 380.dp).fillMaxWidth()
                    .aspectRatio(ratio.coerceIn(0.5f, 2f)).clip(RoundedCornerShape(16.dp)).clickable { onOpen(a.localPath, type) })
        }
        isVideo -> {
            Box(Modifier.padding(bottom = 4.dp).size(240.dp, 180.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black)
                .clickable { if (a.localPath != null) onOpen(a.localPath, type) else download { onOpen(it, type) } }, contentAlignment = Alignment.Center) {
                val thumb = remember(a.localPath) { a.localPath?.takeIf { android.os.Build.VERSION.SDK_INT >= 29 }?.let { p -> runCatching {
                    android.media.ThumbnailUtils.createVideoThumbnail(File(p), android.util.Size(480, 360), null).asImageBitmap() }.getOrNull() } }
                if (thumb != null) Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                    if (loading) androidx.compose.material3.CircularProgressIndicator(Modifier.size(26.dp), color = Color.White, strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.PlayArrow, "Смотреть видео", tint = Color.White, modifier = Modifier.size(32.dp))
                }
            }
        }
        isAudio && a.localPath != null -> AudioPlayer(a.localPath, fg)
        else -> {
            val size = a.size?.let { if (it > 1_000_000) "%.1f МБ".format(it / 1_000_000f) else "${(it / 1000).coerceAtLeast(1)} КБ" } ?: ""
            val label = when { isImage -> "Фото"; isAudio -> "Голосовое сообщение"; else -> a.fileName ?: "Файл" }
            Row(Modifier.padding(bottom = 4.dp).clip(RoundedCornerShape(12.dp)).background(fg.copy(alpha = 0.08f))
                .clickable { if (a.localPath != null) openFile(ctx, a.localPath, a.contentType) else download { p -> if (!isImage && !isAudio) openFile(ctx, p, a.contentType) } }
                .padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (isImage) Icons.Rounded.PhotoLibrary else if (isAudio) Icons.Rounded.Mic else Icons.AutoMirrored.Rounded.InsertDriveFile, null, tint = fg)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (loading) "Загрузка…" else if (a.localPath != null) "Открыть, $size" else "Нажмите, чтобы загрузить ($size)",
                        style = MaterialTheme.typography.bodySmall, color = fg.copy(alpha = 0.7f))
                }
            }
        }
    }
}


@Composable
private fun ForwardDialog(core: Core, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val convs by produceState(emptyList<Conversation>()) { value = withContext(Dispatchers.IO) { core.repo.conversations() } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Переслать в…") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(convs, key = { it.id }) { c ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(c.id) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(c.title, c.peer ?: c.id, c.avatarPath, 36.dp,
                            when (c.kind) { ConvKind.GROUP -> AvatarKind.GROUP; ConvKind.NOTE_TO_SELF -> AvatarKind.SELF; else -> AvatarKind.PERSON })
                        Spacer(Modifier.width(12.dp)); Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}

@Composable
private fun Composer(
    draft: String, onDraft: (String) -> Unit, replyTo: Message?, replyAuthor: String?, onCancelReply: () -> Unit, onSend: () -> Unit,
    stickersOpen: Boolean, onToggleStickers: () -> Unit, attachMenu: Boolean, onAttachMenu: (Boolean) -> Unit,
    onPickMedia: () -> Unit, onPickViewOnce: () -> Unit, onPickFile: () -> Unit,
    recording: Boolean, onRecordStart: () -> Boolean, onRecordStop: (cancel: Boolean) -> Unit,
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
                        Text(replyTo?.body?.replace(OBJ.toString(), "@") ?: "Вложение", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = onCancelReply) { Icon(Icons.Rounded.Close, "Отменить ответ") }
                }
            }
            Row(Modifier.padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = onToggleStickers, modifier = Modifier.padding(bottom = 2.dp)) {
                    Icon(if (stickersOpen) Icons.Rounded.Keyboard else Icons.Rounded.EmojiEmotions, if (stickersOpen) "Скрыть стикеры" else "Стикеры", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (recording) Row(Modifier.weight(1f).heightIn(min = 52.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
                    Spacer(Modifier.width(10.dp))
                    Text("Запись… отпустите, чтобы отправить", style = MaterialTheme.typography.bodyMedium)
                } else TextField(
                    value = draft, onValueChange = onDraft, placeholder = { Text("Сообщение") },
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp, max = 160.dp), shape = RoundedCornerShape(26.dp),
                    colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
                )
                if (draft.isBlank() && !recording) Box {
                    IconButton(onClick = { onAttachMenu(true) }, modifier = Modifier.padding(bottom = 2.dp)) {
                        Icon(Icons.Rounded.AddCircleOutline, "Прикрепить", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(attachMenu, { onAttachMenu(false) }) {
                        DropdownMenuItem({ Text("Фото или видео") }, { onAttachMenu(false); onPickMedia() }, leadingIcon = { Icon(Icons.Rounded.PhotoLibrary, null) })
                        DropdownMenuItem({ Text("Файл") }, { onAttachMenu(false); onPickFile() }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.InsertDriveFile, null) })
                        DropdownMenuItem({ Text("Фото на один просмотр") }, { onAttachMenu(false); onPickViewOnce() }, leadingIcon = { Icon(Icons.Rounded.LocalFireDepartment, null) })
                    }
                }
                Spacer(Modifier.width(4.dp))
                val hasText = draft.isNotBlank()
                val sendBg by animateColorAsState(if (hasText || recording) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer, label = "send")
                Box(
                    Modifier.size(52.dp).clip(CircleShape).background(sendBg)
                        .then(if (hasText) Modifier.clickable(onClick = onSend) else Modifier.pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown()
                                val started = onRecordStart()
                                val up = waitForUpOrCancellation()
                                if (started) onRecordStop(up == null)
                            }
                        }),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(if (hasText) Icons.AutoMirrored.Rounded.Send else Icons.Rounded.Mic, if (hasText) "Отправить" else "Удерживайте для записи голосового",
                        tint = if (hasText || recording) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
    }
}
