package io.github.kotbctakahe.hys1link.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import io.github.kotbctakahe.hys1link.core.Core
import io.github.kotbctakahe.hys1link.net.Http
import io.github.kotbctakahe.hys1link.social.SSegment
import io.github.kotbctakahe.hys1link.social.STrayEntry
import io.github.kotbctakahe.hys1link.social.SUser
import io.github.kotbctakahe.hys1link.ui.components.listTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Horizontal tray of people with active stories, shown above the feed. */
@Composable
fun StoriesRow(core: Core, onOpen: (String) -> Unit) {
    var entries by remember { mutableStateOf<List<STrayEntry>>(emptyList()) }
    LaunchedEffect(Unit) { runCatching { withContext(Dispatchers.IO) { core.social.storiesTray().first } }.onSuccess { entries = it } }
    if (entries.isEmpty()) return
    val ring = Brush.sweepGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.primary))
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(entries, key = { it.author.id }) { e ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(70.dp).clickable { onOpen(e.author.id) }) {
                Box(Modifier.size(66.dp).clip(CircleShape)
                    .then(if (e.hasUnseen) Modifier.border(3.dp, ring, CircleShape) else Modifier.border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
                    .padding(5.dp)) {
                    NetAvatar(e.author.avatar?.thumb, e.author.display, e.author.id, 56.dp)
                }
                Spacer(Modifier.height(4.dp))
                Text(e.author.username ?: e.author.display, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun parseColor(s: String?, fallback: Color): Color = runCatching { Color(android.graphics.Color.parseColor(s)) }.getOrDefault(fallback)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun StoryViewer(core: Core, authorId: String, onClose: () -> Unit, onOpenUser: (ProfileKey) -> Unit) {
    var author by remember { mutableStateOf<SUser?>(null) }
    var segs by remember { mutableStateOf<List<SSegment>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var index by remember { mutableIntStateOf(0) }
    var progress by remember { mutableFloatStateOf(0f) }
    var paused by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(ctx).setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(Http.client(ctx)))).build()
    }
    DisposableEffect(Unit) { onDispose { player.release() } }

    LaunchedEffect(authorId) {
        runCatching { withContext(Dispatchers.IO) { core.social.storyReel(authorId) } }
            .onSuccess { (a, s) -> author = a; segs = s; index = s.indexOfFirst { !it.seen }.coerceAtLeast(0) }
            .onFailure { error = it.message }
    }
    val list = segs
    val seg = list?.getOrNull(index)
    LaunchedEffect(seg?.id) {
        val s = seg ?: return@LaunchedEffect
        scope.launch(Dispatchers.IO) { runCatching { core.social.recordStoryViews(listOf(s)) } }
        progress = 0f
        player.stop(); player.clearMediaItems()
        if (s.videoUrl != null) { player.setMediaItem(MediaItem.fromUri(s.videoUrl)); player.prepare(); player.playWhenReady = true }
        val total = if (s.durationMs > 0) s.durationMs else 6000
        val started = System.currentTimeMillis()
        var pausedFor = 0L
        while (progress < 1f) {
            delay(50)
            if (paused) { pausedFor += 50; player.playWhenReady = false; continue } else if (s.videoUrl != null) player.playWhenReady = true
            progress = if (s.videoUrl != null && player.duration > 0 && player.playbackState != Player.STATE_IDLE)
                (player.currentPosition.toFloat() / player.duration).coerceIn(0f, 1f)
            else ((System.currentTimeMillis() - started - pausedFor).toFloat() / total).coerceIn(0f, 1f)
            if (s.videoUrl != null && player.playbackState == Player.STATE_ENDED) progress = 1f
        }
        if (index < (list.size - 1)) index++ else onClose()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            error != null -> Text("История недоступна: $error", color = Color.White, modifier = Modifier.align(Alignment.Center).padding(32.dp), textAlign = TextAlign.Center)
            list == null -> CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
            seg == null -> Text("Историй нет", color = Color.White, modifier = Modifier.align(Alignment.Center))
            else -> {
                if (seg.text != null && seg.imageUrl == null && seg.videoUrl == null) {
                    Box(Modifier.fillMaxSize().background(parseColor(seg.textBg, MaterialTheme.colorScheme.primary)), contentAlignment = Alignment.Center) {
                        Text(seg.text, color = parseColor(seg.textColor, Color.White), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
                    }
                } else {
                    AsyncImage(seg.imageUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    if (seg.videoUrl != null) AndroidView({ PlayerView(it).apply { useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT } },
                        Modifier.fillMaxSize(), update = { it.player = player })
                }
                // tap left/right to navigate, hold to pause
                Box(Modifier.fillMaxSize().pointerInput(seg.id) {
                    detectTapGestures(
                        onPress = { paused = true; tryAwaitRelease(); paused = false },
                        onTap = { o -> if (o.x < size.width / 3) { if (index > 0) index-- } else { if (index < list.size - 1) index++ else onClose() } },
                    )
                })
                seg.caption?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = Color.White, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 72.dp, start = 24.dp, end = 24.dp)
                            .clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(10.dp))
                }
                Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (seg.viewCount > 0) { Icon(Icons.Rounded.Visibility, "Просмотры", tint = Color.White, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                        Text(compact(seg.viewCount), color = Color.White, style = MaterialTheme.typography.labelLarge) }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = {
                        val now = !seg.liked
                        segs = list.map { if (it.id == seg.id) it.copy(liked = now) else it }
                        scope.launch(Dispatchers.IO) { runCatching { core.social.likeSegment(seg.id, now) } }
                    }) { Icon(if (seg.liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Нравится", tint = if (seg.liked) Color(0xFFE5484D) else Color.White) }
                }
            }
        }
        Column(Modifier.statusBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp)) {
            if (list != null && list.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                list.forEachIndexed { i, _ ->
                    LinearProgressIndicator(progress = { if (i < index) 1f else if (i == index) progress else 0f },
                        modifier = Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)), color = Color.White, trackColor = Color.White.copy(alpha = 0.3f))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                author?.let { a ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f).clickable { onOpenUser(ProfileKey(a.id.toIntOrNull(), a.uuid)) }) {
                        NetAvatar(a.avatar?.thumb, a.display, a.id, 34.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(a.display, color = Color.White, style = MaterialTheme.typography.titleSmall)
                        seg?.publishedAt?.takeIf { it > 0 }?.let { Spacer(Modifier.width(8.dp)); Text(listTime(it), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall) }
                    }
                } ?: Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Закрыть", tint = Color.White) }
            }
        }
    }
}
