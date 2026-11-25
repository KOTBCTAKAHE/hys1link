package io.github.kotbctakahe.hys1link.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DynamicFeed
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SlowMotionVideo
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.DynamicFeed
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SlowMotionVideo
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.kotbctakahe.hys1link.core.Core
import io.github.kotbctakahe.hys1link.ui.screens.ChatsScreen
import io.github.kotbctakahe.hys1link.ui.screens.FeedScreen
import io.github.kotbctakahe.hys1link.ui.screens.ProfileKey
import io.github.kotbctakahe.hys1link.ui.screens.ReelsScreen
import io.github.kotbctakahe.hys1link.ui.screens.SearchScreen
import io.github.kotbctakahe.hys1link.ui.screens.SocialProfileScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class Tab(val label: String, val on: ImageVector, val off: ImageVector)

private val tabs = listOf(
    Tab("Чаты", Icons.Rounded.ChatBubble, Icons.Outlined.ChatBubbleOutline),
    Tab("Лента", Icons.Rounded.DynamicFeed, Icons.Outlined.DynamicFeed),
    Tab("Рилсы", Icons.Rounded.SlowMotionVideo, Icons.Outlined.SlowMotionVideo),
    Tab("Поиск", Icons.Rounded.Search, Icons.Outlined.Search),
    Tab("Профиль", Icons.Rounded.AccountCircle, Icons.Outlined.AccountCircle),
)

/** Space screens must leave at the bottom so content clears the floating dock. */
val DockClearance = 96.dp

@Composable
fun HomeShell(
    core: Core, openChat: (String) -> Unit, openNewChat: () -> Unit, openSettings: () -> Unit,
    openPost: (String) -> Unit, openUser: (ProfileKey) -> Unit, openStory: (String) -> Unit = {},
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val version by core.db.version.collectAsState()
    val unread by produceState(0, version) {
        value = withContext(Dispatchers.IO) { core.repo.conversations().sumOf { it.unread } }
    }
    Box(Modifier.fillMaxSize()) {
        when (tab) {
            0 -> ChatsScreen(core, onOpen = openChat, onNewChat = openNewChat, onSettings = openSettings)
            1 -> FeedScreen(core, onOpenPost = openPost, onOpenUser = openUser, onOpenStory = openStory)
            2 -> ReelsScreen(core, onOpenUser = openUser, onOpenPost = openPost)
            3 -> SearchScreen(core, onOpenUser = openUser)
            else -> SocialProfileScreen(core, ProfileKey(null, core.account.aci), onBack = null, onOpenPost = openPost,
                onMessage = { u -> openChat(core.repo.ensureDirect(u, core.account.aci).id) }, onSettings = openSettings)
        }
        Dock(tab, onReels = tab == 2, unread = unread, modifier = Modifier.align(Alignment.BottomCenter)) { tab = it }
    }
}

@Composable
private fun Dock(selected: Int, onReels: Boolean, unread: Int, modifier: Modifier, onSelect: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val container = if (onReels) Color(0xE6101614) else scheme.surfaceContainerLowest
    Surface(
        modifier = modifier.navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp).fillMaxWidth(),
        shape = RoundedCornerShape(30.dp), color = container, shadowElevation = 10.dp, tonalElevation = 0.dp,
    ) {
        Row(Modifier.height(62.dp).padding(horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            tabs.forEachIndexed { i, t ->
                val active = i == selected
                val bg by animateColorAsState(if (active) scheme.primary else Color.Transparent, spring(stiffness = Spring.StiffnessMediumLow), label = "dock")
                val fg = when { active -> scheme.onPrimary; onReels -> Color.White.copy(alpha = 0.75f); else -> scheme.onSurfaceVariant }
                Row(
                    Modifier.clip(RoundedCornerShape(22.dp)).background(bg)
                        .clickable(remember { MutableInteractionSource() }, null, role = Role.Tab) { onSelect(i) }
                        .padding(horizontal = if (active) 16.dp else 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        Icon(if (active) t.on else t.off, t.label, tint = fg, modifier = Modifier.size(24.dp))
                        if (i == 0 && unread > 0 && !active) Box(
                            Modifier.align(Alignment.TopEnd).size(9.dp).clip(RoundedCornerShape(50)).background(scheme.error)
                        )
                    }
                    AnimatedVisibility(active, enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
                        Row {
                            Spacer(Modifier.width(8.dp))
                            Text(t.label, color = fg, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}
