package io.github.kotbctakahe.hys1link.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.kotbctakahe.hys1link.core.Core
import io.github.kotbctakahe.hys1link.store.ConvKind
import io.github.kotbctakahe.hys1link.store.Conversation
import io.github.kotbctakahe.hys1link.ui.components.Avatar
import io.github.kotbctakahe.hys1link.ui.components.AvatarKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator
import java.nio.ByteBuffer
import java.util.UUID

private fun uuidBytes(u: String): ByteArray = UUID.fromString(u).let { ByteBuffer.allocate(16).putLong(it.mostSignificantBits).putLong(it.leastSignificantBits).array() }

/** Safety number exactly as Link computes it (version 2, ACI bytes, 5200 iterations). */
fun safetyNumber(core: Core, peer: String): String? = runCatching {
    val mine = core.account.identityKeyPair!!.publicKey
    val theirs = core.store!!.getIdentity(SignalProtocolAddress(peer, 1)) ?: return null
    NumericFingerprintGenerator(5200).createFor(2, uuidBytes(core.account.aci!!), mine, uuidBytes(peer), theirs)
        .displayableFingerprint.displayText.chunked(5).joinToString(" ")
}.getOrNull()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoScreen(core: Core, convId: String, onBack: () -> Unit, onOpenUser: (ProfileKey) -> Unit, onOpenChat: (String) -> Unit, onDeleted: () -> Unit) {
    val version by core.db.version.collectAsState()
    val conv by produceState<Conversation?>(null, version) { value = withContext(Dispatchers.IO) { core.repo.conversation(convId) } }
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    val c = conv
    Scaffold(topBar = { TopAppBar(title = { Text(if (c?.kind == ConvKind.GROUP) "О группе" else "О контакте") },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") } }) }) { pad ->
        if (c == null) return@Scaffold
        val group = if (c.kind == ConvKind.GROUP) core.groups.cached(c.id) else null
        val contact = c.peer?.let { core.repo.contact(it) }
        LazyColumn(contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 32.dp)) {
            item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(c.title, c.peer ?: c.id, c.avatarPath, 104.dp,
                        when (c.kind) { ConvKind.GROUP -> AvatarKind.GROUP; ConvKind.NOTE_TO_SELF -> AvatarKind.SELF; else -> AvatarKind.PERSON })
                    Spacer(Modifier.height(12.dp))
                    Text(c.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    val sub = group?.let { "${it.members.size} участников" } ?: contact?.e164
                    sub?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    (group?.description ?: contact?.about)?.let { Spacer(Modifier.height(8.dp)); Text(it, textAlign = TextAlign.Center) }
                }
            }
            if (c.kind == ConvKind.DIRECT && c.peer != null) {
                item {
                    Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { onOpenUser(ProfileKey(null, c.peer)) }, modifier = Modifier.weight(1f)) { Text("Профиль в ленте") }
                        OutlinedButton(onClick = {
                            scope.launch(Dispatchers.IO) { core.sender.setBlocked(c.peer, !(contact?.blocked ?: false)) }
                        }, modifier = Modifier.weight(1f)) { Text(if (contact?.blocked == true) "Разблокировать" else "Заблокировать", color = MaterialTheme.colorScheme.error) }
                    }
                }
                item {
                    val sn = remember(c.peer, version) { safetyNumber(core, c.peer) }
                    Column(Modifier.padding(20.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(18.dp)) {
                        Text("Номер безопасности", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text("Сверьте его с собеседником при встрече или в другом канале. Совпадает — переписку никто не перехватывает.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Text(sn ?: "Появится после первого обмена сообщениями.", style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace))
                    }
                }
            }
            if (group != null) {
                item { Text("Участники", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
                items(group.members, key = { it.uuid }) { m ->
                    val ct = core.repo.contact(m.uuid)
                    val me = m.uuid == core.account.aci
                    Row(Modifier.fillMaxWidth().clickable(enabled = !me) { onOpenChat(core.repo.ensureDirect(m.uuid, core.account.aci).id) }
                        .padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(ct?.displayName ?: "?", m.uuid, ct?.avatarPath, 44.dp)
                        Spacer(Modifier.width(14.dp))
                        Text(if (me) "Вы" else ct?.displayName ?: "Участник", style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
            item {
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.padding(16.dp)) {
                    Text("Удалить чат на этом устройстве", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Удалить чат?") },
        text = { Text("Сообщения удалятся только здесь. На телефоне и у собеседника они останутся.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; scope.launch(Dispatchers.IO) { core.repo.deleteConversation(convId) }; onDeleted() }) { Text("Удалить", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } })
}
