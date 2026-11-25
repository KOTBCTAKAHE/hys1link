package io.github.kotbctakahe.hys1link.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import io.github.kotbctakahe.hys1link.core.Sender
import kotlinx.coroutines.delay
import java.io.File

/** Fullscreen photo (pinch to zoom) or video. */
@Composable
fun MediaViewer(path: String, contentType: String?, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (contentType?.startsWith("video/") == true) {
                val ctx = LocalContext.current
                val player = remember { ExoPlayer.Builder(ctx).build().apply { setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path)))); prepare(); playWhenReady = true } }
                DisposableEffect(Unit) { onDispose { player.release() } }
                AndroidView({ PlayerView(it).apply { this.player = player } }, Modifier.fillMaxSize())
            } else {
                var scale by remember { mutableFloatStateOf(1f) }
                var offset by remember { mutableStateOf(Offset.Zero) }
                AsyncImage(File(path), "Фото", Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    }
                    .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y })
            }
            IconButton(onClick = onClose, modifier = Modifier.statusBarsPadding().padding(8.dp).align(Alignment.TopEnd)) {
                Icon(Icons.Rounded.Close, "Закрыть", tint = Color.White)
            }
        }
    }
}

/** Inline voice / audio player. */
@Composable
fun AudioPlayer(path: String, fg: Color) {
    var playing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var duration by remember { mutableIntStateOf(0) }
    val player = remember(path) { runCatching { MediaPlayer().apply { setDataSource(path); prepare() } }.getOrNull() }
    DisposableEffect(path) { onDispose { player?.release() } }
    LaunchedEffect(player) { duration = player?.duration ?: 0 }
    LaunchedEffect(playing) {
        while (playing && player != null) {
            progress = if (player.duration > 0) player.currentPosition.toFloat() / player.duration else 0f
            if (!player.isPlaying) { playing = false; progress = 0f }
            delay(200)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(fg.copy(alpha = 0.15f)).clickable(enabled = player != null) {
            if (player == null) return@clickable
            if (playing) { player.pause(); playing = false } else { player.start(); playing = true }
        }, contentAlignment = Alignment.Center) {
            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Пауза" else "Слушать", tint = fg)
        }
        Spacer(Modifier.width(10.dp))
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.width(140.dp).height(4.dp), color = fg, trackColor = fg.copy(alpha = 0.2f))
        Spacer(Modifier.width(8.dp))
        Text(fmtDuration(duration), color = fg.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall)
    }
}

fun fmtDuration(ms: Int): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

fun openFile(ctx: Context, path: String, contentType: String?) {
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", File(path))
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, contentType ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try { ctx.startActivity(Intent.createChooser(i, "Открыть").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: ActivityNotFoundException) {}
}

/** Reads any picked file (video, document…). Images go through MediaPrep.image instead. */
object FilePrep {
    private const val LIMIT = 95L * 1024 * 1024

    fun file(ctx: Context, uri: Uri): Sender.LocalFile? = runCatching {
        val cr = ctx.contentResolver
        val type = cr.getType(uri) ?: "application/octet-stream"
        if (type.startsWith("image/") && type != "image/gif") return@runCatching MediaPrep.image(ctx, uri)
        var name: String? = null; var size = -1L
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) { name = c.getString(0); size = c.getLong(1) }
        }
        if (size > LIMIT) error("Файл больше 95 МБ")
        val bytes = cr.openInputStream(uri)!!.use { it.readBytes() }
        if (bytes.size > LIMIT) error("Файл больше 95 МБ")
        var w: Int? = null; var h: Int? = null
        if (type.startsWith("video/")) runCatching {
            MediaMetadataRetriever().apply { setDataSource(ctx, uri) }.let { r ->
                w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                r.release()
            }
        }
        Sender.LocalFile(bytes, type, name, w, h)
    }.getOrElse { if (it.message?.contains("95") == true) throw it else null }
}

/** Hold-to-record voice notes (AAC/ADTS, like Signal's "audio/aac"). */
class VoiceRecorder(private val ctx: Context) {
    private var rec: MediaRecorder? = null
    private var file: File? = null
    private var started = 0L

    fun start() {
        val f = File(ctx.cacheDir, "voice_${System.currentTimeMillis()}.aac")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioSamplingRate(44100); r.setAudioEncodingBitRate(64000)
        r.setOutputFile(f.absolutePath)
        r.prepare(); r.start()
        rec = r; file = f; started = System.currentTimeMillis()
    }

    /** Returns the recording, or null if it was too short / cancelled. */
    fun stop(cancel: Boolean = false): Sender.LocalFile? {
        val r = rec ?: return null
        rec = null
        runCatching { r.stop() }; r.release()
        val f = file ?: return null
        if (cancel || System.currentTimeMillis() - started < 700) { f.delete(); return null }
        val bytes = f.readBytes(); f.delete()
        return Sender.LocalFile(bytes, "audio/aac", null, voice = true)
    }
}
