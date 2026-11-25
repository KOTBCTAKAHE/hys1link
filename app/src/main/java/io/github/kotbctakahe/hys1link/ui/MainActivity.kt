package io.github.kotbctakahe.hys1link.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.kotbctakahe.hys1link.core.Core
import io.github.kotbctakahe.hys1link.service.ConnectionService
import io.github.kotbctakahe.hys1link.ui.screens.ChatScreen
import io.github.kotbctakahe.hys1link.ui.screens.ChatsScreen
import io.github.kotbctakahe.hys1link.ui.screens.LinkScreen
import io.github.kotbctakahe.hys1link.ui.screens.NewChatScreen
import io.github.kotbctakahe.hys1link.ui.screens.SettingsScreen
import io.github.kotbctakahe.hys1link.ui.theme.Hys1Theme
import java.net.URLDecoder
import java.net.URLEncoder

class MainActivity : ComponentActivity() {
    private val pendingConv = mutableStateOf<String?>(null)
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val core = Core.get(this)
        pendingConv.value = intent.getStringExtra(EXTRA_CONV)

        setContent {
            var themeTick by remember { mutableIntStateOf(0) }
            val linked by core.linked.collectAsState()
            val acct = core.account
            val mode = remember(themeTick) { acct.themeMode }
            val accent = remember(themeTick) { acct.accent }
            val dynamic = remember(themeTick) { acct.dynamicColor }
            Hys1Theme(mode, accent, dynamic) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    if (!linked) {
                        LinkScreen(core)
                    } else {
                        LaunchedEffect(Unit) {
                            if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            ConnectionService.start(this@MainActivity)
                        }
                        val nav = rememberNavController()
                        val target = pendingConv.value
                        LaunchedEffect(target) {
                            if (target != null) { nav.navigate("chat/" + URLEncoder.encode(target, "UTF-8")); pendingConv.value = null }
                        }
                        NavHost(
                            nav, startDestination = "chats",
                            enterTransition = { slideInHorizontally { it / 4 } + fadeIn() },
                            exitTransition = { fadeOut() },
                            popEnterTransition = { fadeIn() },
                            popExitTransition = { slideOutHorizontally { it / 4 } + fadeOut() },
                        ) {
                            composable("chats") {
                                ChatsScreen(core,
                                    onOpen = { nav.navigate("chat/" + URLEncoder.encode(it, "UTF-8")) },
                                    onNewChat = { nav.navigate("new") },
                                    onSettings = { nav.navigate("settings") })
                            }
                            composable("chat/{id}") { e ->
                                val id = URLDecoder.decode(e.arguments?.getString("id") ?: "", "UTF-8")
                                ChatScreen(core, id) { nav.popBackStack() }
                            }
                            composable("new") {
                                NewChatScreen(core, onBack = { nav.popBackStack() }) { id ->
                                    nav.popBackStack(); nav.navigate("chat/" + URLEncoder.encode(id, "UTF-8"))
                                }
                            }
                            composable("settings") {
                                SettingsScreen(core, onBack = { nav.popBackStack() }, onThemeChanged = { themeTick++ })
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_CONV)?.let { pendingConv.value = it }
    }

    override fun onResume() {
        super.onResume()
        Core.get(this).let { if (it.account.isLinked) it.startConnection() }
    }

    companion object { const val EXTRA_CONV = "conv" }
}
