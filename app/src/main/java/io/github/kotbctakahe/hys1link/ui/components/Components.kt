package io.github.kotbctakahe.hys1link.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val avatarHues = listOf(
    Color(0xFF2E7D6B), Color(0xFF3A6EA5), Color(0xFF8A4F9E), Color(0xFFB0602A),
    Color(0xFF5B7F2B), Color(0xFFA8445A), Color(0xFF2F7F95), Color(0xFF6D5BA8),
)

fun avatarColor(key: String): Color = avatarHues[(key.hashCode() and 0x7fffffff) % avatarHues.size]

fun initials(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() && it.first().isLetterOrDigit() }
    return when {
        parts.isEmpty() -> "•"
        parts.size == 1 -> parts[0].take(1).uppercase()
        else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
    }
}

enum class AvatarKind { PERSON, GROUP, SELF }

@Composable
fun Avatar(name: String, key: String, path: String?, size: Dp = 48.dp, kind: AvatarKind = AvatarKind.PERSON) {
    val bmp = remember(path) { path?.let { p -> runCatching { if (File(p).exists()) BitmapFactory.decodeFile(p)?.asImageBitmap() else null }.getOrNull() } }
    val bg = if (kind == AvatarKind.SELF) MaterialTheme.colorScheme.primary else avatarColor(key)
    Box(Modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        when {
            bmp != null -> Image(bmp, null, Modifier.size(size), contentScale = ContentScale.Crop)
            kind == AvatarKind.SELF -> Icon(Icons.Rounded.Bookmark, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(size * 0.46f))
            kind == AvatarKind.GROUP -> Icon(Icons.Rounded.Groups, null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
            else -> Text(initials(name), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.38f).sp)
        }
    }
}

/** Renders a QR code with rounded modules. */
@Composable
fun QrCode(data: String, modifier: Modifier, fg: Color, bg: Color) {
    val matrix = remember(data) {
        QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0))
    }
    Canvas(modifier.background(bg)) {
        val n = matrix.width
        val cell = size.minDimension / n
        val r = androidx.compose.ui.geometry.CornerRadius(cell * 0.42f)
        for (y in 0 until n) for (x in 0 until n) if (matrix.get(x, y)) {
            drawRoundRect(fg, Offset(x * cell, y * cell), Size(cell * 1.02f, cell * 1.02f), r)
        }
    }
}

fun Color.readableOn(): Color = if (luminance() > 0.5f) Color(0xFF111A17) else Color.White

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
private val dateFmt = SimpleDateFormat("d MMM", Locale("ru"))
private val fullDateFmt = SimpleDateFormat("d MMMM yyyy", Locale("ru"))

fun sameDay(a: Long, b: Long): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a }
    val cb = Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
}

fun listTime(ts: Long): String {
    if (ts == 0L) return ""
    val now = System.currentTimeMillis()
    return when {
        sameDay(ts, now) -> timeFmt.format(Date(ts))
        sameDay(ts, now - 86_400_000) -> "вчера"
        now - ts < 6 * 86_400_000L -> SimpleDateFormat("EE", Locale("ru")).format(Date(ts))
        else -> dateFmt.format(Date(ts))
    }
}

fun clock(ts: Long): String = timeFmt.format(Date(ts))

fun dayHeader(ts: Long): String {
    val now = System.currentTimeMillis()
    return when {
        sameDay(ts, now) -> "Сегодня"
        sameDay(ts, now - 86_400_000) -> "Вчера"
        else -> fullDateFmt.format(Date(ts))
    }
}
