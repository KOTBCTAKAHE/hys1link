package io.github.kotbctakahe.hys1link.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.kotbctakahe.hys1link.LinkConfig
import io.github.kotbctakahe.hys1link.core.Core
import io.github.kotbctakahe.hys1link.core.Linker
import io.github.kotbctakahe.hys1link.ui.components.QrCode
import io.github.kotbctakahe.hys1link.ui.theme.slabColor

@Composable
fun LinkScreen(core: Core) {
    var state by remember { mutableStateOf<Linker.State?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var deviceName by remember { mutableStateOf(LinkConfig.DEVICE_NAME) }
    val slab = slabColor(core.account.accent)
    val mint = Color(0xFF7FE7C6)

    if (attempt > 0) {
        LaunchedEffect(attempt) { Linker(core).run(deviceName) { state = it } }
    }

    Column(
        Modifier.fillMaxSize().background(slab).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(mint))
            Spacer(Modifier.width(10.dp))
            Text("Hys1Link", color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(40.dp))
        Text(
            "Ваш аккаунт Link\nна втором экране",
            color = Color.White, style = MaterialTheme.typography.displaySmall
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Клиент подключается как связанное устройство. Номер, ключи и переписка остаются на вашем телефоне с Link — сюда приходит только копия новых сообщений.",
            color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(32.dp))

        AnimatedContent(targetState = state, label = "link", transitionSpec = { fadeIn() togetherWith fadeOut() }) { s ->
            when (s) {
                null -> Column {
                    OutlinedTextField(
                        value = deviceName, onValueChange = { deviceName = it.take(40) },
                        label = { Text("Имя устройства в списке Link") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                            focusedBorderColor = mint, unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                            focusedLabelColor = mint, unfocusedLabelColor = Color.White.copy(alpha = 0.6f), cursorColor = mint,
                        )
                    )
                    Spacer(Modifier.height(20.dp))
                    PrimaryButton("Показать QR-код", mint, slab) { attempt++ }
                }
                is Linker.State.Connecting, is Linker.State.Finishing -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(48.dp))
                    CircularProgressIndicator(color = mint)
                    Spacer(Modifier.height(16.dp))
                    Text(if (s is Linker.State.Finishing) "Получаю ключи и загружаю пре-ключи…" else "Подключаюсь к chat.linkmessenger.me…",
                        color = Color.White.copy(alpha = 0.8f))
                }
                is Linker.State.ShowQr -> Column {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(28.dp)).background(Color.White).padding(22.dp)
                    ) { QrCode(s.url, Modifier.fillMaxSize(), slab, Color.White) }
                    Spacer(Modifier.height(24.dp))
                    Steps(listOf(
                        "Откройте Link на телефоне",
                        "Настройки → Связанные устройства → «+»",
                        "Наведите камеру на этот код",
                    ))
                }
                is Linker.State.Failed -> Column {
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.08f)).padding(18.dp)) {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(s.message, color = Color(0xFFFFD7D1), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    PrimaryButton("Попробовать снова", mint, slab) { attempt++ }
                }
                is Linker.State.Done -> Text("Готово", color = mint, style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
private fun Steps(steps: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        steps.forEachIndexed { i, s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(14.dp))
                Text(s, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun PrimaryButton(text: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick, modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg)
    ) { Text(text, style = MaterialTheme.typography.titleMedium) }
}
