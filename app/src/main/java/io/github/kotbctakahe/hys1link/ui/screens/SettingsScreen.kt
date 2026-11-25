package io.github.kotbctakahe.hys1link.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import io.github.kotbctakahe.hys1link.core.Core
import io.github.kotbctakahe.hys1link.service.ConnectionService
import io.github.kotbctakahe.hys1link.store.Contact
import io.github.kotbctakahe.hys1link.ui.components.Avatar
import io.github.kotbctakahe.hys1link.ui.theme.Accents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(core: Core, onBack: () -> Unit, onThemeChanged: () -> Unit) {
    val acct = core.account
    val version by core.db.version.collectAsState()
    val me by produceState<Contact?>(null, version) { value = withContext(Dispatchers.IO) { acct.aci?.let { core.repo.contact(it) } } }
    val scope = rememberCoroutineScope()
    var themeMode by remember { mutableStateOf(acct.themeMode) }
    var accent by remember { mutableStateOf(acct.accent) }
    var dynamic by remember { mutableStateOf(acct.dynamicColor) }
    var receipts by remember { mutableStateOf(acct.sendReadReceipts) }
    var confirmUnlink by remember { mutableStateOf(false) }
    var syncNote by remember { mutableStateOf<String?>(null) }
    val vdsCount = remember(version) { core.db.getLong("vds_link_requests") }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Настройки") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") } })
    }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                val name = me?.displayName ?: acct.e164 ?: "Вы"
                Avatar(name, acct.aci ?: "me", me?.avatarPath, 64.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(me?.profileName ?: name, style = MaterialTheme.typography.titleLarge)
                    Text(acct.e164 ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Устройство №${acct.deviceId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Section("Оформление")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Как в системе", "Светлая", "Тёмная").forEachIndexed { i, label ->
                    SegmentedButton(selected = themeMode == i, onClick = { themeMode = i; acct.themeMode = i; onThemeChanged() },
                        shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(label) }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("Акцент", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Accents.forEachIndexed { i, a ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(48.dp).clip(CircleShape).background(a.light)
                                .then(if (accent == i && !dynamic) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                .clickable { accent = i; acct.accent = i; dynamic = false; acct.dynamicColor = false; onThemeChanged() },
                            contentAlignment = Alignment.Center
                        ) { if (accent == i && !dynamic) Icon(Icons.Rounded.Check, null, tint = Color.White) }
                        Spacer(Modifier.height(4.dp))
                        Text(a.name, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            ToggleRow("Цвета обоев (Android 12+)", "Брать палитру из системы вместо акцента", dynamic) {
                dynamic = it; acct.dynamicColor = it; onThemeChanged()
            }

            Section("Приватность")
            ToggleRow("Отчёты о прочтении", "Собеседники видят, что вы прочитали сообщение", receipts) { receipts = it; acct.sendReadReceipts = it }
            Text(
                if (vdsCount == 0L) "Запросов серверной привязки устройств (vds-system) не поступало."
                else "Получено запросов серверной привязки устройств: $vdsCount. Этот клиент их игнорирует; официальный Link на телефоне выполняет такие запросы без подтверждения.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            Section("Синхронизация")
            OutlinedButton(onClick = {
                syncNote = "Запрос отправлен на телефон"
                scope.launch(Dispatchers.IO) { runCatching { core.sender.requestInitialSync() }.onFailure { syncNote = "Не удалось: ${it.message}" } }
            }, shape = RoundedCornerShape(14.dp)) { Text("Запросить контакты и группы") }
            syncNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }

            Section("Устройство")
            OutlinedButton(onClick = { confirmUnlink = true }, shape = RoundedCornerShape(14.dp)) {
                Text("Отвязать и удалить данные", color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(24.dp))
            Text("Hys1Link — неофициальный клиент для серверов Link Messenger. Исходный код: github.com/KOTBCTAKAHE/hys1link",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmUnlink) AlertDialog(
        onDismissRequest = { confirmUnlink = false },
        title = { Text("Отвязать устройство?") },
        text = { Text("Все сообщения и ключи на этом устройстве будут удалены без возможности восстановления. Аккаунт и переписка на телефоне не пострадают.") },
        confirmButton = {
            TextButton(onClick = {
                confirmUnlink = false
                ConnectionService.stop(core.context)
                scope.launch(Dispatchers.IO) { core.unlink() }
            }) { Text("Отвязать", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { confirmUnlink = false }) { Text("Отмена") } },
    )
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(24.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
