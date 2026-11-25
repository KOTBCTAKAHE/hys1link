package io.github.kotbctakahe.hys1link.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.kotbctakahe.hys1link.core.Core
import io.github.kotbctakahe.hys1link.ui.screens.ChatsScreen
import io.github.kotbctakahe.hys1link.ui.screens.FeedScreen
import io.github.kotbctakahe.hys1link.ui.screens.ProfileKey
import io.github.kotbctakahe.hys1link.ui.screens.ReelsScreen
import io.github.kotbctakahe.hys1link.ui.screens.SearchScreen
import io.github.kotbctakahe.hys1link.ui.screens.SocialProfileScreen

private data class Tab(val label: String, val on: ImageVector, val off: ImageVector)

private val tabs = listOf(
    Tab("Чаты", Icons.Rounded.ChatBubble, Icons.Outlined.ChatBubbleOutline),
    Tab("Лента", Icons.Rounded.DynamicFeed, Icons.Outlined.DynamicFeed),
    Tab("Рилсы", Icons.Rounded.SlowMotionVideo, Icons.Outlined.SlowMotionVideo),
    Tab("Поиск", Icons.Rounded.Search, Icons.Outlined.Search),
    Tab("Профиль", Icons.Rounded.AccountCircle, Icons.Outlined.AccountCircle),
)

@Composable
fun HomeShell(
    core: Core, openChat: (String) -> Unit, openNewChat: () -> Unit, openSettings: () -> Unit,
    openPost: (String) -> Unit, openUser: (ProfileKey) -> Unit,
) {
    var tab by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    Scaffold(bottomBar = {
        NavigationBar {
            tabs.forEachIndexed { i, t ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i },
                    icon = { Icon(if (tab == i) t.on else t.off, null) }, label = { Text(t.label) })
            }
        }
    }) { pad ->
        Box(Modifier.fillMaxSize().padding(bottom = pad.calculateBottomPadding()).consumeWindowInsets(pad)) {
            when (tab) {
                0 -> ChatsScreen(core, onOpen = openChat, onNewChat = openNewChat, onSettings = openSettings)
                1 -> FeedScreen(core, onOpenPost = openPost, onOpenUser = openUser)
                2 -> ReelsScreen(core, onOpenUser = openUser, onOpenPost = openPost)
                3 -> SearchScreen(core, onOpenUser = openUser)
                else -> SocialProfileScreen(core, ProfileKey(null, core.account.aci), onBack = null, onOpenPost = openPost,
                    onMessage = { u -> openChat(core.repo.ensureDirect(u, core.account.aci).id) })
            }
        }
    }
}
