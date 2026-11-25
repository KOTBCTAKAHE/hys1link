package io.github.kotbctakahe.hys1link.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kotbctakahe.hys1link.core.Core
import io.github.kotbctakahe.hys1link.social.SChatRequest
import io.github.kotbctakahe.hys1link.store.Contact
import io.github.kotbctakahe.hys1link.store.Conversation
import io.github.kotbctakahe.hys1link.ui.components.Avatar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupScreen(core: Core, onBack: () -> Unit, onCreated: (String) -> Unit) {
    val contacts by produceState(emptyList<Contact>()) { value = withContext(Dispatchers.IO) { core.repo.contacts().filter { it.uuid != core.account.aci } } }
    val picked = remember { mutableStateListOf<String>() }
    var title by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Scaffold(topBar = { TopAppBar(title = { Text("Новая группа") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") } }) },
        bottomBar = {
            Button(enabled = !busy && title.isNotBlank() && picked.isNotEmpty(), onClick = {
                busy = true; error = null
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { core.groups.create(title.trim(), picked.toList()) } }
                        .onSuccess { r ->
                            busy = false
                            if (r.skipped.isNotEmpty()) error = "Не добавлены (нет ключа профиля): ${r.skipped.joinToString { core.repo.contact(it)?.displayName ?: it.take(6) }}"
                            onCreated(r.convId)
                        }.onFailure { busy = false; error = "Группа не создана: ${it.message}" }
                }
            }, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                if (busy) CircularProgressIndicator(Modifier.padding(end = 10.dp).width(18.dp), strokeWidth = 2.dp)
                Text("Создать${if (picked.isNotEmpty()) " (${picked.size})" else ""}")
            }
        }) { pad ->
        LazyColumn(contentPadding = pad) {
            item {
                OutlinedTextField(title, { title = it.take(32) }, label = { Text("Название группы") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(16.dp))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
                Text("Участники", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp))
            }
            items(contacts, key = { it.uuid }) { c ->
                val on = c.uuid in picked
                Row(Modifier.fillMaxWidth().clickable { if (on) picked.remove(c.uuid) else picked.add(c.uuid) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Avatar(c.displayName, c.uuid, c.avatarPath, 42.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (c.profileKey == null) Text("не сможет быть добавлен — нет ключа профиля", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Checkbox(on, { if (on) picked.remove(c.uuid) else picked.add(c.uuid) })
                }
            }
        }
    }
}

/** Admin controls on the group info screen. */
@Composable
fun GroupAdminSection(core: Core, conv: Conversation, onLeft: () -> Unit) {
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    val admin = core.groups.isAdmin(conv.id, core.account.aci)
    fun run(label: String, block: () -> Unit) {
        status = "$label…"
        scope.launch { status = runCatching { withContext(Dispatchers.IO) { block() } }.fold({ "Готово" }, { "Не удалось: ${it.message?.take(160)}" }) }
    }
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { text = conv.title; dialog = "title" }, modifier = Modifier.weight(1f)) { Text("Название") }
            OutlinedButton(onClick = { text = core.groups.cached(conv.id)?.description.orEmpty(); dialog = "desc" }, modifier = Modifier.weight(1f)) { Text("Описание") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { dialog = "add" }, modifier = Modifier.weight(1f)) { Text("Добавить") }
            OutlinedButton(onClick = { dialog = "leave" }, modifier = Modifier.weight(1f)) { Text("Выйти", color = MaterialTheme.colorScheme.error) }
        }
        if (!admin) Text("Часть действий доступна только администраторам группы.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    when (dialog) {
        "title", "desc" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text(if (dialog == "title") "Название группы" else "Описание") },
            text = { OutlinedTextField(text, { text = it }, singleLine = dialog == "title") },
            confirmButton = { TextButton(onClick = {
                val v = text.trim(); val d = dialog; dialog = null
                run("Сохраняю") { if (d == "title") core.groups.rename(conv, v) else core.groups.setDescription(conv, v) }
            }) { Text("Сохранить") } }, dismissButton = { TextButton(onClick = { dialog = null }) { Text("Отмена") } })
        "add" -> {
            val members = core.groups.cached(conv.id)?.members?.map { it.uuid }?.toSet().orEmpty()
            val candidates = remember { core.repo.contacts().filter { it.uuid !in members && it.uuid != core.account.aci } }
            AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Добавить участника") },
                text = {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(candidates, key = { it.uuid }) { c ->
                            Row(Modifier.fillMaxWidth().clickable { dialog = null; run("Добавляю") { core.groups.addMember(conv, c.uuid) } }.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Avatar(c.displayName, c.uuid, c.avatarPath, 36.dp); Spacer(Modifier.width(12.dp)); Text(c.displayName)
                            }
                        }
                    }
                }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("Отмена") } })
        }
        "leave" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Выйти из группы?") },
            text = { Text("Вы перестанете получать сообщения этой группы на всех устройствах.") },
            confirmButton = { TextButton(onClick = { dialog = null; run("Выхожу") { core.groups.leave(conv) }; onLeft() }) { Text("Выйти", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Отмена") } })
    }
}

/** Remove / promote a member (long-press on the member row). */
@Composable
fun MemberActionsDialog(core: Core, conv: Conversation, uuid: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val name = core.repo.contact(uuid)?.displayName ?: "Участник"
    val isAdmin = core.groups.isAdmin(conv.id, uuid)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(name) },
        text = {
            Column {
                TextButton(onClick = { onDismiss(); scope.launch(Dispatchers.IO) { runCatching { core.groups.makeAdmin(conv, uuid, !isAdmin) } } }) {
                    Text(if (isAdmin) "Снять права администратора" else "Сделать администратором")
                }
                TextButton(onClick = { onDismiss(); scope.launch(Dispatchers.IO) { runCatching { core.groups.removeMember(conv, uuid) } } }) {
                    Text("Удалить из группы", color = MaterialTheme.colorScheme.error)
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatRequestsScreen(core: Core, onBack: () -> Unit, onOpenChat: (String) -> Unit, onOpenUser: (ProfileKey) -> Unit) {
    var items by remember { mutableStateOf<List<SChatRequest>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun load() { runCatching { withContext(Dispatchers.IO) { core.social.chatRequests() } }.onSuccess { items = it; error = null }.onFailure { error = it.message } }
    LaunchedEffect(Unit) { load() }
    Scaffold(topBar = { TopAppBar(title = { Text("Запросы на переписку") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") } }) }) { pad ->
        LazyColumn(contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 24.dp)) {
            if (error != null) item { Text("Не загрузилось: $error", modifier = Modifier.padding(20.dp)) }
            else if (items?.isEmpty() == true) item { Text("Новых запросов нет.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp)) }
            items(items.orEmpty(), key = { it.id }) { r ->
                val u = r.user
                Row(Modifier.fillMaxWidth().clickable(enabled = u != null) { u?.let { onOpenUser(ProfileKey(it.id)) } }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    NetAvatar(u?.avatar, u?.display ?: "?", (u?.id ?: 0).toString(), 46.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(u?.display ?: "Пользователь", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { scope.launch { runCatching { withContext(Dispatchers.IO) { core.social.rejectChatRequest(r.id) } }; load() } }) { Text("Отклонить") }
                    Button(onClick = {
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { core.social.acceptChatRequest(r.id) } }
                            val uuid = u?.chatUuid ?: runCatching { withContext(Dispatchers.IO) { core.social.profile(userId = u?.id).chatUuid } }.getOrNull()
                            load()
                            if (uuid != null) onOpenChat(core.repo.ensureDirect(uuid, core.account.aci).id)
                        }
                    }, shape = RoundedCornerShape(14.dp)) { Text("Принять") }
                }
            }
        }
    }
}
