package com.everettpenne.thumper.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Link
import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.everettpenne.thumper.data.api.ApiResult
import com.everettpenne.thumper.data.feed.FeedFetch
import com.everettpenne.thumper.data.feed.FeedFetcher
import com.everettpenne.thumper.graph
import com.everettpenne.thumper.playback.LocalPlayback
import com.everettpenne.thumper.playback.rememberPlaybackUi
import com.everettpenne.thumper.ui.components.GlassTextField
import com.everettpenne.thumper.ui.components.MiniPlayer
import com.everettpenne.thumper.ui.components.Navigator
import com.everettpenne.glass.*
import com.everettpenne.media.VideoSurface
import com.everettpenne.thumper.ui.screens.*
import com.everettpenne.thumper.ui.theme.ThumperTheme
import com.kyant.backdrop.backdrops.layerBackdrop
import kotlinx.coroutines.launch

object Routes {
    const val LISTEN = "listen"; const val READ = "read"; const val SEARCH = "search"; const val SETTINGS = "settings"; const val PLAYER = "player"; const val QUEUE = "queue"; const val DOWNLOADS = "downloads"
    fun feed(url: String) = "feed/${Uri.encode(url)}"
    fun episode(feedId: String, episodeId: String) = "episode/$feedId/$episodeId"
    fun article(feedId: String, articleId: String) = "article/$feedId/$articleId"
}

@Composable
fun ThumperRoot(deepLink: Uri?, inPictureInPicture: Boolean) {
    ThumperTheme {
        var viewer by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }
        val updater = com.everettpenne.updater.Updater.get(LocalContext.current, com.everettpenne.updater.UpdateConfig("everettpenne/Thumper", com.everettpenne.thumper.BuildConfig.VERSION_NAME))
        // Debug builds skip the launch check so the emulator tests never meet a dialog; Settings > Check for Updates still works there.
        LaunchedEffect(Unit) { if (!com.everettpenne.thumper.BuildConfig.DEBUG) updater.checkOnLaunch() }
        com.everettpenne.updater.UpdateDialog(updater, "Thumper")
        val context = LocalContext.current
        val graph = context.graph
        val navController = rememberNavController()
        val playback = rememberPlaybackUi()
        var addFeed by remember { mutableStateOf<String?>(null) }          // null: closed; "": open and empty; otherwise open with this address
        var openAfterAdd by remember { mutableStateOf<String?>(null) }     // a feed just added: opened from here, not from the closing sheet's own coroutine

        val nav = remember(navController) {
            object : Navigator {
                override fun openFeed(url: String) = navController.navigate(Routes.feed(url))
                override fun openEpisode(feedId: String, episodeId: String) = navController.navigate(Routes.episode(feedId, episodeId))
                override fun openArticle(feedId: String, articleId: String) = navController.navigate(Routes.article(feedId, articleId))
                override fun openPlayer() = navController.navigate(Routes.PLAYER) { launchSingleTop = true }
                override fun openQueue() = navController.navigate(Routes.QUEUE) { launchSingleTop = true }
                override fun openDownloads() = navController.navigate(Routes.DOWNLOADS) { launchSingleTop = true }
                override fun openSettings() = navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
                override fun addFeed(url: String?) { addFeed = url ?: "" }
                override fun openSearch() { navController.navigate(Routes.SEARCH) { popUpTo(Routes.LISTEN) { inclusive = false }; launchSingleTop = true } }
            }
        }
        LaunchedEffect(deepLink) { deepLink?.let { addFeed = it.toString() } }
        LaunchedEffect(openAfterAdd) { openAfterAdd?.let { nav.openFeed(it); openAfterAdd = null } }

        // The first time something plays, ask to show its notification (Android 13 and newer); sound in the background works either way.
        val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        LaunchedEffect(playback.episodeId) {
            if (playback.episodeId != null && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val backStack by navController.currentBackStackEntryAsState()
        val route = backStack?.destination?.route
        val tabs = Tab.entries.associateBy { it.route }
        var lastTab by remember { mutableStateOf(Tab.Listen) }
        LaunchedEffect(route) { tabs[route]?.let { lastTab = it } }
        val tabBar: (@Composable () -> Unit)? = if (route == Routes.PLAYER) null else ({
            Column {
                MiniPlayer(onOpen = nav::openPlayer, modifier = Modifier.padding(bottom = 2.dp))
                GlassTabBar(Tab.bar, selected = lastTab, search = Tab.Search, onSelect = { tab -> navController.navigate(tab.route) { popUpTo(Routes.LISTEN) { inclusive = false }; launchSingleTop = true } })
            }
        })

        CompositionLocalProvider(com.everettpenne.updater.LocalUpdater provides updater, com.everettpenne.media.LocalImageViewer provides { urls, i -> viewer = urls to i }, LocalTabBar provides tabBar, LocalPlayback provides playback) {
            // The plain page colour is also a glass backdrop, recorded in a sibling of the screens: recording the screens themselves would make any glass
            // control inside them sample a layer that contains it, which sends the renderer into endless recursion.
            val pageBackdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop()
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().layerBackdrop(pageBackdrop).background(MaterialTheme.colorScheme.background))
                CompositionLocalProvider(LocalGlassBackdrop provides pageBackdrop) {
                    NavHost(navController, startDestination = Routes.LISTEN, modifier = Modifier.fillMaxSize(),
                        enterTransition = { fadeIn() }, exitTransition = { fadeOut() }, popEnterTransition = { fadeIn() }, popExitTransition = { fadeOut() }) {
                        composable(Routes.LISTEN) { ListenScreen(nav) }
                        composable(Routes.READ) { ReadScreen(nav) }
                        composable(Routes.SEARCH) { SearchScreen(nav) }
                        composable(Routes.QUEUE) { QueueScreen(nav, onBack = { navController.popBackStack() }) }
                        composable(Routes.SETTINGS) { SettingsScreen(nav, onBack = { navController.popBackStack() }) }
                        composable(Routes.DOWNLOADS) { DownloadsScreen(nav, onBack = { navController.popBackStack() }) }
                        composable(Routes.PLAYER, enterTransition = { slideInVertically { it / 3 } + fadeIn() }, popExitTransition = { slideOutVertically { it / 3 } + fadeOut() }) { PlayerScreen(nav, onBack = { navController.popBackStack() }, inPictureInPicture = inPictureInPicture) }
                        composable("feed/{url}", listOf(navArgument("url") { type = NavType.StringType })) { e -> FeedScreen(e.arguments?.getString("url").orEmpty(), nav, onBack = { navController.popBackStack() }) }
                        composable("episode/{feedId}/{episodeId}", listOf(navArgument("feedId") { type = NavType.StringType }, navArgument("episodeId") { type = NavType.StringType })) { e ->
                            EpisodeScreen(e.arguments?.getString("feedId").orEmpty(), e.arguments?.getString("episodeId").orEmpty(), nav, onBack = { navController.popBackStack() })
                        }
                        composable("article/{feedId}/{articleId}", listOf(navArgument("feedId") { type = NavType.StringType }, navArgument("articleId") { type = NavType.StringType })) { e ->
                            ArticleScreen(e.arguments?.getString("feedId").orEmpty(), e.arguments?.getString("articleId").orEmpty(), onBack = { navController.popBackStack() })
                        }
                    }
                }
                // Picture-in-picture: only the video fills the small floating window; the player screen stays put underneath.
                if (inPictureInPicture && playback.hasVideo) {
                    playback.controller?.let { player -> Box(Modifier.fillMaxSize().background(Color.Black)) { VideoSurface(player, Modifier.fillMaxSize()) } }
                }
            }
            viewer?.let { (urls, i) -> com.everettpenne.media.ImageViewer(urls, i) { viewer = null } }
            addFeed?.let { initial -> AddFeedSheet(initial, onDismiss = { addFeed = null }, onAdded = { url -> addFeed = null; openAfterAdd = url }) }
        }
    }
}

/** Add a feed by its address (or a web page that links to one): fetch it, subscribe, and open it. */
@Composable
private fun AddFeedSheet(initial: String, onDismiss: () -> Unit, onAdded: (String) -> Unit) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf(initial) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun add() {
        if (busy || address.isBlank()) return
        busy = true; error = null
        scope.launch {
            when (val r = graph.fetcher.fetch(address)) {
                is ApiResult.Failed -> error = r.reason.message
                is ApiResult.Ok -> (r.value as? FeedFetch.Updated)?.content?.let { c ->
                    if (graph.library.isSubscribed(c.feed.id)) error = "You're already subscribed to ${c.feed.title}." else { graph.library.subscribe(c); onAdded(c.feed.url) }
                }
            }
            busy = false
        }
    }
    GlassSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Add a feed", style = MaterialTheme.typography.titleLarge)
            Text("Paste the address of a podcast's feed, or of a blog or news site: Thumper finds its feed.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            GlassTextField(address, { address = it }, "Feed or website address", leading = Icons.Rounded.Link, imeAction = ImeAction.Go, onSubmit = ::add)
            error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = com.everettpenne.thumper.ui.theme.Thumper.red) }
            GlassButton(if (busy) "Adding…" else "Add", ::add, Modifier.fillMaxWidth(), enabled = !busy && address.isNotBlank(), prominent = true)
            Spacer(Modifier.height(8.dp))
        }
    }
}
