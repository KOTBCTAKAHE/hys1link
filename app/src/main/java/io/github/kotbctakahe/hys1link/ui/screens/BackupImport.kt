package io.github.kotbctakahe.hys1link.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.kotbctakahe.hys1link.backup.BackupImporter
import io.github.kotbctakahe.hys1link.core.Core
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Groups the 30-digit passphrase as "12345 67890 …" like Link shows it. */
private object GroupsOfFive : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val out = raw.chunked(5).joinToString(" ")
        return TransformedText(AnnotatedString(out), object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = if (offset <= 0) 0 else (offset + (offset - 1) / 5).coerceAtMost(out.length)
            override fun transformedToOriginal(offset: Int) = (offset - offset / 6).coerceIn(0, raw.length)
        })
    }
}

@Composable
fun BackupImportSection(core: Core) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var uri by remember { mutableStateOf<Uri?>(null) }
    var pass by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<BackupImporter.Progress?>(null) }
    var result by remember { mutableStateOf<String?>(null) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) { uri = u; pass = ""; result = null } }

    Text(
        "Перенесите историю из файла бэкапа Link (Настройки → Чаты → Резервное копирование на телефоне). " +
            "Понадобится пароль из 30 цифр. Ключи шифрования из бэкапа не используются — только сообщения, группы и фото.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))
    OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }, enabled = !running, shape = RoundedCornerShape(14.dp)) { Text("Выбрать файл бэкапа") }
    if (running) {
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(Modifier.fillMaxWidth())
        progress?.let { Text("${it.phase}. Кадров: ${it.frames}, сообщений: ${it.messages}, фото: ${it.attachments}",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
    }
    result?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }

    val chosen = uri
    if (chosen != null && !running) AlertDialog(
        onDismissRequest = { uri = null },
        title = { Text("Пароль бэкапа") },
        text = {
            Column {
                Text("30 цифр, которые Link показал при включении резервного копирования.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pass, onValueChange = { v -> pass = v.filter(Char::isDigit).take(30) },
                    singleLine = true, label = { Text("${pass.length} из 30") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = GroupsOfFive,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = pass.length == 30, onClick = {
                val p = pass; uri = null; running = true; result = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        runCatching {
                            ctx.contentResolver.openInputStream(chosen)!!.use { input ->
                                BackupImporter(core).run(input, p) { pr -> scope.launch(Dispatchers.Main) { progress = pr } }
                            }
                        }
                    }
                    running = false
                    result = r.fold(
                        { "Готово: ${it.messages} сообщений в ${it.conversations} чатах, фото: ${it.attachments}. Пропущено служебных: ${it.skipped}." },
                        { "Не удалось импортировать: ${it.message ?: it.javaClass.simpleName}" }
                    )
                }
            }) { Text("Импортировать") }
        },
        dismissButton = { TextButton(onClick = { uri = null }) { Text("Отмена") } },
    )
}
