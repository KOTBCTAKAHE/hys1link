package io.github.kotbctakahe.hys1link.ui.screens

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import io.github.kotbctakahe.hys1link.core.Core
import io.github.kotbctakahe.hys1link.ui.DockClearance
import androidx.compose.material.icons.rounded.Settings
import io.github.kotbctakahe.hys1link.net.ApiException
import io.github.kotbctakahe.hys1link.net.Http
import io.github.kotbctakahe.hys1link.social.SComment
import io.github.kotbctakahe.hys1link.social.SPost
import io.github.kotbctakahe.hys1link.social.SProfile
import io.github.kotbctakahe.hys1link.social.SSearchUser
import io.github.kotbctakahe.hys1link.social.SUser
import io.github.kotbctakahe.hys1link.ui.components.avatarColor
import io.github.kotbctakahe.hys1link.ui.components.initials
import io.github.kotbctakahe.hys1link.ui.components.listTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where a profile is opened from: by numeric social id or by messenger UUID. */
data class ProfileKey(val userId: Int? = null, val uuid: String? = null)

fun compact(n: Long): String = when {
    n >= 1_000_000 -> "%.1f млн".format(n / 1_000_000f).replace(".0", "")
    n >= 10_000 -> "${n / 1000} тыс"
    n >= 1_000 -> "%.1f тыс".format(n / 1000f).replace(".0", "")
    else -> n.toString()
}

private fun errorText(e: Throwable): String = when {
    e is ApiException && e.code == 401 -> "Соцсеть не приняла вход этого устройства. Откройте Link на телефоне и повторите."
    e is ApiException -> "Сервер ответил ${e.code}. Потяните вниз, чтобы повторить."
    else -> "Нет связи с social.linkm.me: ${e.message ?: e.javaClass.simpleName}"
}

@Composable
fun NetAvatar(url: String?, name: String, key: String, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(avatarColor(key)), contentAlignment = Alignment.Center) {
        Text(initials(name), color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
        if (url != null) AsyncImage(url, null, Modifier.size(size), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun CenterMessage(title: String, body: String?, onRetry: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        if (onRetry != null) { Spacer(Modifier.height(16.dp)); OutlinedButton(onClick = onRetry) { Text("Повторить") } }
    }
}

// ===================================================== FEED

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(core: Core, onOpenPost: (String) -> Unit, onOpenUser: (ProfileKey) -> Unit, onOpenStory: (String) -> Unit = {}) {
    val posts = remember { mutableStateListOf<SPost>() }
    var cursor by remember { mutableStateOf<String?>(null) }
    var hasMore by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var forYou by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()

    fun load(reset: Boolean) {
        if (loading) return
        loading = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (forYou) core.social.forYou(15).let { it to null }
                    else core.social.homeFeed(if (reset) null else cursor).let { p -> p.posts to (p.nextCursor to p.hasMore) }
                }
            }.onSuccess { (items, page) ->
                if (reset) posts.clear()
                posts.addAll(items.filter { p -> posts.none { it.id == p.id } })
                if (page is Pair<*, *>) { cursor = page.first as String?; hasMore = page.second as Boolean && cursor != null }
                else hasMore = items.isNotEmpty()
                error = null
            }.onFailure { error = errorText(it) }
            loading = false
        }
    }

    LaunchedEffect(forYou) { cursor = null; hasMore = true; load(true) }
    LaunchedEffect(list) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged().filter { it >= posts.size - 3 }
            .collect { if (hasMore && posts.isNotEmpty()) load(false) }
    }

    Scaffold(topBar = {
        TopAppBar(title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Лента", style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.width(16.dp))
                FeedTab("Подписки", !forYou) { forYou = false }
                Spacer(Modifier.width(6.dp))
                FeedTab("Для вас", forYou) { forYou = true }
            }
        })
    }) { pad ->
        PullToRefreshBox(isRefreshing = loading && posts.isEmpty(), onRefresh = { load(true) }, modifier = Modifier.padding(pad).fillMaxSize()) {
            LazyColumn(state = list, contentPadding = PaddingValues(top = 4.dp, bottom = DockClearance + 16.dp)) {
                item(key = "stories") { StoriesRow(core, onOpenStory) }
                if (posts.isEmpty() && error != null) item { CenterMessage("Лента не загрузилась", error) { load(true) } }
                else if (posts.isEmpty() && !loading) item {
                    CenterMessage(if (forYou) "Рекомендаций пока нет" else "Здесь появятся посты тех, на кого вы подписаны", "Найдите людей во вкладке «Поиск».")
                }
                items(posts, key = { it.id }) { p ->
                    PostCard(core, p, onOpenPost = { onOpenPost(p.id) }, onOpenUser = onOpenUser) { updated ->
                        val i = posts.indexOfFirst { it.id == updated.id }; if (i >= 0) posts[i] = updated
                    }
                }
                if (loading && posts.isNotEmpty()) item {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
                }
            }
        }
    }
}

@Composable
private fun FeedTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, label = "tab")
    Text(label, style = MaterialTheme.typography.labelLarge,
        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(bg).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp))
}

@Composable
private fun AuthorRow(u: SUser?, ts: Long, onOpenUser: (ProfileKey) -> Unit, light: Boolean = false) {
    if (u == null) return
    val fg = if (light) Color.White else MaterialTheme.colorScheme.onSurface
    Row(Modifier.clickable { onOpenUser(ProfileKey(u.id.toIntOrNull(), u.uuid)) }.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        NetAvatar(u.avatar?.thumb, u.display, u.id, 38.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f, false)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(u.display, style = MaterialTheme.typography.titleSmall, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (u.verified) { Spacer(Modifier.width(4.dp)); Icon(Icons.Rounded.Verified, "Подтверждён", Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary) }
            }
            val sub = listOfNotNull(u.username?.let { "@$it" }, ts.takeIf { it > 0 }?.let { listTime(it) }).joinToString(", ")
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = fg.copy(alpha = 0.65f))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PostCard(core: Core, p: SPost, onOpenPost: (() -> Unit)?, onOpenUser: (ProfileKey) -> Unit, onChanged: (SPost) -> Unit) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(26.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(bottom = 12.dp)) {
        AuthorRow(p.author, p.createdAt, onOpenUser)
        if (p.medias.isNotEmpty()) {
            val ratio = p.medias.first().aspect.coerceIn(0.8f, 1.91f)
            val pager = rememberPagerState { p.medias.size }
            Box {
                HorizontalPager(pager, Modifier.padding(horizontal = 8.dp).fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)) { i ->
                    val m = p.medias[i]
                    Box(Modifier.fillMaxSize().clickable(enabled = onOpenPost != null) { onOpenPost?.invoke() }, contentAlignment = Alignment.Center) {
                        AsyncImage(m.imageUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        if (m.isVideo) Box(Modifier.size(56.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.PlayArrow, "Видео", tint = Color.White, modifier = Modifier.size(34.dp))
                        }
                    }
                }
                if (p.medias.size > 1) Row(Modifier.align(Alignment.BottomCenter).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    repeat(p.medias.size) { i ->
                        Box(Modifier.size(if (i == pager.currentPage) 8.dp else 6.dp).clip(CircleShape)
                            .background(Color.White.copy(alpha = if (i == pager.currentPage) 1f else 0.55f)))
                    }
                }
            }
        }
        Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            LikeButton(p.liked, p.likes, MaterialTheme.colorScheme.onSurface) {
                val now = !p.liked
                onChanged(p.copy(liked = now, likes = (p.likes + if (now) 1 else -1).coerceAtLeast(0)))
                scope.launch(Dispatchers.IO) { runCatching { if (now) core.social.like(p.id) else core.social.unlike(p.id) }
                    .onFailure { withContext(Dispatchers.Main) { onChanged(p) } } }
            }
            if (p.commentsAvailable) Row(Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = onOpenPost != null) { onOpenPost?.invoke() }.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.ChatBubbleOutline, "Комментарии", Modifier.size(22.dp))
                Spacer(Modifier.width(5.dp)); Text(compact(p.comments), style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.weight(1f))
            if (p.views > 0) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
                Icon(Icons.Rounded.Visibility, "Просмотры", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                Text(compact(p.views), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val text = listOfNotNull(p.description?.takeIf { it.isNotBlank() }, p.hashtags.takeIf { it.isNotEmpty() }?.joinToString(" ") { "#$it" }).joinToString("\n")
        if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = if (onOpenPost != null) 4 else Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 14.dp))
    }
}

@Composable
private fun LikeButton(liked: Boolean, count: Long, fg: Color, onClick: () -> Unit) {
    val scale by animateFloatAsState(if (liked) 1.12f else 1f, label = "like")
    Row(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (liked) "Убрать отметку" else "Нравится",
            Modifier.size((22 * scale).dp), tint = if (liked) Color(0xFFE5484D) else fg)
        Spacer(Modifier.width(5.dp)); Text(compact(count), style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

// ===================================================== REELS

@OptIn(ExperimentalFoundationApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun ReelsScreen(core: Core, onOpenUser: (ProfileKey) -> Unit, onOpenPost: (String) -> Unit) {
    val reels = remember { mutableStateListOf<SPost>() }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    fun more() {
        if (loading) return
        loading = true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { core.social.reels(8, reels.lastOrNull()?.id) } }
                .onSuccess { r -> reels.addAll(r.filter { p -> reels.none { it.id == p.id } && p.medias.isNotEmpty() }); error = null }
                .onFailure { error = errorText(it) }
            loading = false
        }
    }
    LaunchedEffect(Unit) { if (reels.isEmpty()) more() }

    val player = remember { buildPlayer(ctx) }
    DisposableEffect(Unit) { onDispose { player.release() } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (reels.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (error != null) Surface(color = Color.Transparent, contentColor = Color.White) { CenterMessage("Рилсы не загрузились", error) { more() } }
                else CircularProgressIndicator(color = Color.White)
            }
            return@Box
        }
        val pager = rememberPagerState { reels.size }
        LaunchedEffect(pager.currentPage, reels.size) {
            val p = reels.getOrNull(pager.currentPage) ?: return@LaunchedEffect
            val url = p.medias.firstOrNull()?.videoUrl
            player.stop(); player.clearMediaItems()
            if (url != null) { player.setMediaItem(MediaItem.fromUri(url)); player.prepare(); player.playWhenReady = true }
            if (pager.currentPage >= reels.size - 3) more()
        }
        VerticalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
            val p = reels[page]
            val media = p.medias.first()
            Box(Modifier.fillMaxSize()) {
                AsyncImage(media.imageUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                if (media.isVideo && page == pager.currentPage) AndroidView(
                    factory = { c -> PlayerView(c).apply { useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM; setShutterBackgroundColor(android.graphics.Color.TRANSPARENT) } },
                    update = { it.player = player }, modifier = Modifier.fillMaxSize().clickable { player.playWhenReady = !player.playWhenReady },
                )
                Box(Modifier.fillMaxWidth().height(340.dp).align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)))))
                Row(Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(bottom = DockClearance), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        AuthorRow(p.author, 0, onOpenUser, light = true)
                        p.description?.takeIf { it.isNotBlank() }?.let {
                            Text(it, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 14.dp))
                        }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(end = 6.dp)) {
                        LikeButton(p.liked, p.likes, Color.White) {
                            val now = !p.liked
                            reels[page] = p.copy(liked = now, likes = (p.likes + if (now) 1 else -1).coerceAtLeast(0))
                            scope.launch(Dispatchers.IO) { runCatching { if (now) core.social.like(p.id) else core.social.unlike(p.id) } }
                        }
                        IconButton(onClick = { onOpenPost(p.id) }) { Icon(Icons.Rounded.ChatBubbleOutline, "Комментарии", tint = Color.White) }
                        Text(compact(p.comments), color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        Text("Рилсы", color = Color.White, style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.statusBarsPadding().padding(16.dp))
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun buildPlayer(ctx: Context): ExoPlayer = ExoPlayer.Builder(ctx)
    .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(Http.client(ctx))))
    .build().apply { repeatMode = Player.REPEAT_MODE_ONE; volume = 1f }

// ===================================================== SEARCH

@OptIn(FlowPreview::class, ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(core: Core, onOpenUser: (ProfileKey) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SSearchUser>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        snapshotFlow { query.trim() }.debounce(350).distinctUntilChanged().collect { q ->
            if (q.length < 2) { results = emptyList(); error = null; return@collect }
            busy = true
            runCatching { withContext(Dispatchers.IO) { core.social.searchUsers(q).first } }
                .onSuccess { results = it; error = null }.onFailure { error = errorText(it) }
            busy = false
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Поиск", style = MaterialTheme.typography.displaySmall) }) }) { pad ->
        LazyColumn(contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = DockClearance + 16.dp)) {
            item {
                TextField(query, { query = it }, placeholder = { Text("Имя или @username") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = { if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) },
                    shape = RoundedCornerShape(18.dp),
                    colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            }
            error?.let { e -> item { CenterMessage("Поиск недоступен", e) } }
            if (query.trim().length >= 2 && results.isEmpty() && !busy && error == null) item { CenterMessage("Никого не нашли", "Проверьте написание или попробуйте username.") }
            items(results, key = { it.id }) { u ->
                Row(Modifier.fillMaxWidth().clickable { onOpenUser(ProfileKey(u.id)) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    NetAvatar(u.avatar, u.display, u.id.toString(), 48.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(u.display, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        u.username?.let { Text("@$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    if (u.isFollowing) Text("Вы подписаны", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

// ===================================================== PROFILE

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SocialProfileScreen(core: Core, key: ProfileKey, onBack: (() -> Unit)?, onOpenPost: (String) -> Unit, onMessage: (String) -> Unit, onSettings: (() -> Unit)? = null) {
    var profile by remember { mutableStateOf<SProfile?>(null) }
    val posts = remember { mutableStateListOf<SPost>() }
    var cursor by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var counts by remember { mutableStateOf<Triple<Long, Long, Long>?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        runCatching {
            withContext(Dispatchers.IO) {
                val pr = core.social.profile(userId = key.userId, uuid = key.uuid ?: if (key.userId == null) core.account.aci else null)
                val c = runCatching { core.social.counts(pr.id.takeIf { it > 0 }, key.uuid) }.getOrNull()
                val page = if (pr.isSelf) core.social.myPosts(null) else core.social.userPosts(pr.id.toString(), null)
                Triple(pr, c, page)
            }
        }.onSuccess { (pr, c, page) ->
            profile = pr; counts = c; posts.clear(); posts.addAll(page.posts); cursor = page.nextCursor.takeIf { page.hasMore }; error = null
        }.onFailure { error = errorText(it) }
    }
    LaunchedEffect(key) { load() }

    Scaffold(topBar = {
        TopAppBar(title = { Text(profile?.username?.let { "@$it" } ?: "Профиль") },
            navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") } },
            actions = { if (onSettings != null) IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Настройки") } })
    }) { pad ->
        val pr = profile
        LazyVerticalGrid(GridCells.Fixed(3), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = if (onBack == null) DockClearance + 16.dp else 16.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item(span = { GridItemSpan(3) }) {
                when {
                    pr == null && error != null -> CenterMessage("Профиль не загрузился", error) { scope.launch { load() } }
                    pr == null -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    else -> ProfileHeader(core, pr, counts, onMessage) { profile = it }
                }
            }
            items(posts, key = { it.id }) { p ->
                Box(Modifier.aspectRatio(0.8f).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable { onOpenPost(p.id) }) {
                    AsyncImage(p.medias.firstOrNull()?.imageUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    if (p.medias.firstOrNull()?.isVideo == true) Icon(Icons.Rounded.PlayArrow, "Видео", tint = Color.White,
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp))
                }
            }
            if (cursor != null) item(span = { GridItemSpan(3) }) {
                LaunchedEffect(cursor) {
                    val pr2 = profile ?: return@LaunchedEffect
                    runCatching { withContext(Dispatchers.IO) { if (pr2.isSelf) core.social.myPosts(cursor) else core.social.userPosts(pr2.id.toString(), cursor) } }
                        .onSuccess { page -> posts.addAll(page.posts.filter { n -> posts.none { it.id == n.id } }); cursor = page.nextCursor.takeIf { page.hasMore } }
                        .onFailure { cursor = null }
                }
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
            }
            if (pr != null && posts.isEmpty()) item(span = { GridItemSpan(3) }) { CenterMessage("Публикаций пока нет", null) }
        }
    }
}

@Composable
private fun ProfileHeader(core: Core, p: SProfile, counts: Triple<Long, Long, Long>?, onMessage: (String) -> Unit, onChanged: (SProfile) -> Unit) {
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().height(118.dp).padding(horizontal = 12.dp).clip(RoundedCornerShape(28.dp))
        .background(Brush.linearGradient(listOf(scheme.primary, scheme.tertiary.copy(alpha = 0.85f), scheme.primaryContainer))))
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 0.dp)) {
            Box(Modifier.offset(y = (-44).dp).size(96.dp).clip(CircleShape).background(scheme.surface).padding(5.dp)) {
                NetAvatar(p.avatar, p.display, p.id.toString(), 86.dp)
            }
            Spacer(Modifier.width(14.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                Stat(counts?.first ?: p.posts.toLong(), "публикаций")
                Stat(counts?.second ?: p.followers.toLong(), "подписчиков")
                Stat(counts?.third ?: p.following.toLong(), "подписок")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.offset(y = (-30).dp)) {
            Text(p.display, style = MaterialTheme.typography.headlineSmall)
            if (p.isVerified) { Spacer(Modifier.width(4.dp)); Icon(Icons.Rounded.Verified, "Подтверждён", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) }
        }
        p.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.offset(y = (-24).dp)) }
        if (!p.isSelf) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (p.isFollowing) FilledTonalButton(onClick = {
                    onChanged(p.copy(isFollowing = false, followers = (p.followers - 1).coerceAtLeast(0)))
                    scope.launch(Dispatchers.IO) { runCatching { core.social.unfollow(p.id) } }
                }, modifier = Modifier.weight(1f)) { Text("Вы подписаны") }
                else Button(onClick = {
                    onChanged(p.copy(isFollowing = true, followers = p.followers + 1))
                    scope.launch(Dispatchers.IO) { runCatching { core.social.follow(p.id) } }
                }, modifier = Modifier.weight(1f)) { Text("Подписаться") }
                if (p.chatUuid != null) OutlinedButton(onClick = { onMessage(p.chatUuid) }, modifier = Modifier.weight(1f)) { Text("Написать") }
                else {
                    var sent by remember { mutableStateOf(false) }
                    OutlinedButton(enabled = !sent, onClick = { sent = true; scope.launch(Dispatchers.IO) { runCatching { core.social.sendChatRequest(p.id) } } },
                        modifier = Modifier.weight(1f)) { Text(if (sent) "Запрос отправлен" else "Запросить чат") }
                }
            }
        }
    }
}

@Composable
private fun Stat(n: Long, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(compact(n), style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ===================================================== POST + COMMENTS

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostScreen(core: Core, postId: String, onBack: () -> Unit, onOpenUser: (ProfileKey) -> Unit) {
    var post by remember { mutableStateOf<SPost?>(null) }
    var comments by remember { mutableStateOf<List<SComment>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var commentsError by remember { mutableStateOf<String?>(null) }
    suspend fun reloadComments() {
        runCatching { withContext(Dispatchers.IO) { core.social.comments(postId).first } }
            .onSuccess { comments = it; commentsError = null }.onFailure { commentsError = errorText(it) }
    }
    LaunchedEffect(postId) {
        runCatching { withContext(Dispatchers.IO) { core.social.post(postId) } }.onSuccess { post = it }.onFailure { error = errorText(it) }
        reloadComments()
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Публикация") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") } }) },
        bottomBar = {
            if (post?.commentsAvailable != false) Surface(tonalElevation = 2.dp) {
                Row(Modifier.navigationBarsPadding().imePadding().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextField(draft, { draft = it }, placeholder = { Text("Комментарий") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(24.dp),
                        colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                    Spacer(Modifier.width(8.dp))
                    IconButton(enabled = draft.isNotBlank(), onClick = {
                        val t = draft.trim(); draft = ""
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { core.social.addComment(postId, t) } }
                                .onSuccess { post = post?.let { p -> p.copy(comments = p.comments + 1) }; reloadComments() }
                                .onFailure { commentsError = "Комментарий не отправлен: " + errorText(it) }
                        }
                    }) { Icon(Icons.AutoMirrored.Rounded.Send, "Отправить комментарий", tint = MaterialTheme.colorScheme.primary) }
                }
            }
        }
    ) { pad ->
        LazyColumn(contentPadding = pad) {
            item {
                when {
                    post != null -> PostCard(core, post!!, null, onOpenUser) { post = it }
                    error != null -> CenterMessage("Публикация не загрузилась", error)
                    else -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            }
            commentsError?.let { e -> item { CenterMessage("Комментарии не загрузились", e) { scope.launch { reloadComments() } } } }
            if (post != null && comments.isEmpty() && commentsError == null) item {
                Text("Комментариев пока нет", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp))
            }
            items(comments, key = { it.id }) { c ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                    val a = c.author
                    Box(Modifier.clickable(enabled = a != null) { a?.let { onOpenUser(ProfileKey(it.id)) } }) {
                        NetAvatar(a?.avatar, a?.display ?: "?", (a?.id ?: 0).toString(), 34.dp)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(a?.display ?: "Пользователь", style = MaterialTheme.typography.labelLarge)
                        Text(c.text, style = MaterialTheme.typography.bodyMedium)
                        if (c.repliesCount > 0) Text("Ответов: ${c.repliesCount}", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
