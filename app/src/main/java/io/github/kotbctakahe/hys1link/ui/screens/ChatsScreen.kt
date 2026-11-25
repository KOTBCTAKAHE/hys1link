package io.github.kotbctakahe.hys1link.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kotbctakahe.hys1link.core.Core
import io.github.kotbctakahe.hys1link.store.Contact
import io.github.kotbctakahe.hys1link.store.ConvKind
import io.github.kotbctakahe.hys1link.store.Conversation
import io.github.kotbctakahe.hys1link.ui.components.Avatar
import io.github.kotbctakahe.hys1link.ui.components.AvatarKind
import io.github.kotbctakahe.hys1link.ui.components.listTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ConnectionLine(core: Core) {
    val conn by core.connection.collectAsState()
    val (text, color) = when (conn) {
        Core.Conn.ONLINE -> "На связи" to MaterialTheme.colorScheme.primary
        Core.Conn.CONNECTING -> "Подключение…" to MaterialTheme.colorScheme.onSurfaceVariant
        Core.Conn.OFFLINE -> "Нет соединения, повторяю" to MaterialTheme.colorScheme.onSurfaceVariant
        Core.Conn.AUTH_FAILED -> "Устройство отвязано — войдите заново в настройках" to MaterialTheme.colorScheme.error
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(7.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatsScreen(core: Core, onOpen: (String) -> Unit, onNewChat: () -> Unit, onSettings: () -> Unit) {
    val version by core.db.version.collectAsState()
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val convs by produceState(emptyList<Conversation>(), version) { value = withContext(Dispatchers.IO) { core.repo.conversations() } }
    val typing by core.receiver.typing.collectAsState()
    val shown = if (query.isBlank()) convs else convs.filter { it.title.contains(query, true) || it.lastBody?.contains(query, true) == true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            value = query, onValueChange = { query = it }, placeholder = { Text("Поиск по чатам") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                focusedIndicatorColor = MaterialTheme.colorScheme.surface, unfocusedIndicatorColor = MaterialTheme.colorScheme.surface,
                            )
                        )
                    } else Column {
                        Text("Чаты", style = MaterialTheme.typography.headlineMedium)
                        ConnectionLine(core)
                    }
                },
                navigationIcon = {
                    if (searching) IconButton(onClick = { searching = false; query = "" }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Закрыть поиск") }
                },
                actions = {
                    if (!searching) IconButton(onClick = { searching = true }) { Icon(Icons.Rounded.Search, "Поиск") }
                    else if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Очистить") }
                    IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Настройки") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onNewChat, icon = { Icon(Icons.Rounded.Edit, null) }, text = { Text("Написать") },
                shape = RoundedCornerShape(20.dp))
        }
    ) { pad ->
        if (shown.isEmpty()) {
            EmptyChats(Modifier.padding(pad), query.isNotBlank())
        } else LazyColumn(contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 96.dp)) {
            items(shown, key = { it.id }) { c ->
                var menu by remember { mutableStateOf(false) }
                Box {
                    ConversationRow(c, typing[c.id]?.let { it > System.currentTimeMillis() } == true,
                        Modifier.combinedClickable(onClick = { onOpen(c.id) }, onLongClick = { menu = true }))
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(if (c.pinned) "Открепить" else "Закрепить") },
                            onClick = { menu = false; core.repo.setPinned(c.id, !c.pinned) })
                        DropdownMenuItem(text = { Text("Отметить прочитанным") }, onClick = { menu = false; core.repo.markConversationIncomingRead(c.id) })
                        DropdownMenuItem(text = { Text("Удалить на этом устройстве") }, onClick = { menu = false; core.repo.deleteConversation(c.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyChats(modifier: Modifier, searching: Boolean) {
    Column(modifier.fillMaxSize().padding(40.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (searching) "Ничего не найдено" else "Пока тихо", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            if (searching) "Попробуйте другое имя или слово из сообщения."
            else "Новые сообщения появятся здесь. Старая история с телефона не переносится — так устроены связанные устройства Signal.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun ConversationRow(c: Conversation, typing: Boolean, modifier: Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(c.title, c.peer ?: c.id, c.avatarPath, 54.dp,
            when (c.kind) { ConvKind.GROUP -> AvatarKind.GROUP; ConvKind.NOTE_TO_SELF -> AvatarKind.SELF; else -> AvatarKind.PERSON })
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                if (c.pinned) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Rounded.PushPin, "Закреплён", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                Text(listTime(c.lastTs), style = MaterialTheme.typography.labelMedium,
                    color = if (c.unread > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (typing) "печатает…" else c.lastBody ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (typing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (c.unread > 0) {
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.defaultMinSize(minWidth = 22.dp).height(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).padding(horizontal = 7.dp),
                        contentAlignment = Alignment.Center) {
                        Text(if (c.unread > 99) "99+" else c.unread.toString(), color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatScreen(core: Core, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val version by core.db.version.collectAsState()
    var query by remember { mutableStateOf("") }
    val contacts by produceState(emptyList<Contact>(), version) { value = withContext(Dispatchers.IO) { core.repo.contacts() } }
    val self = core.account.aci
    val shown = contacts.filter { it.uuid != self && (query.isBlank() || it.displayName.contains(query, true) || it.e164?.contains(query) == true) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Новый чат") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") } })
    }) { pad ->
        LazyColumn(contentPadding = pad) {
            item {
                TextField(
                    value = query, onValueChange = { query = it }, placeholder = { Text("Имя или номер") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    colors = TextFieldDefaults.colors(focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            item {
                ContactRow("Заметки", self ?: "self", null, "Сообщения себе — видны на всех ваших устройствах", AvatarKind.SELF) {
                    self?.let { onOpen(core.repo.ensureDirect(it, it).id) }
                }
            }
            if (contacts.isEmpty()) item {
                Text("Контакты появятся после синхронизации с телефоном. Если список пуст дольше пары минут, откройте Link на телефоне — он отправит контакты.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp))
            }
            items(shown, key = { it.uuid }) { c ->
                ContactRow(c.displayName, c.uuid, c.avatarPath, c.about ?: c.e164, AvatarKind.PERSON) {
                    onOpen(core.repo.ensureDirect(c.uuid, self).id)
                }
            }
        }
    }
}

@Composable
private fun ContactRow(name: String, key: String, avatar: String?, subtitle: String?, kind: AvatarKind, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(name, key, avatar, 46.dp, kind)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
