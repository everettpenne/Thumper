package com.everettpenne.thumper.ui.screens

import com.everettpenne.glass.GlassRefreshIndicator
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.everettpenne.thumper.data.Derived
import com.everettpenne.thumper.data.model.FeedKind
import com.everettpenne.thumper.graph
import com.everettpenne.thumper.playback.LocalPlayback
import com.everettpenne.thumper.ui.components.*
import com.everettpenne.glass.*
import com.everettpenne.thumper.util.Format
import kotlinx.coroutines.launch

/** The Listen tab: what you were in the middle of, your shows, and the newest episodes across them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenScreen(nav: Navigator) {
    val graph = LocalContext.current.graph
    val library = graph.library
    val playback = LocalPlayback.current
    val feeds by library.feeds.collectAsState(); val contents by library.contents.collectAsState(); val progress by library.progress.collectAsState()
    val refreshing by graph.refreshing.collectAsState()
    val queue by library.queue.collectAsState()
    val scope = rememberCoroutineScope()
    val podcasts = feeds.filter { it.kind == FeedKind.PODCAST }
    val inProgress = remember(contents, progress) { Derived.inProgress(contents, progress) }
    val fresh = remember(contents, progress) { Derived.newEpisodes(contents, progress) }
    LaunchedEffect(Unit) { graph.refreshAll() }

    GlassScreen(bars = {
        GlassTopBar("Listen", Modifier.align(Alignment.TopCenter)) {
            GlassActionPill { PillIcon(Icons.Rounded.DownloadForOffline, "Downloads", nav::openDownloads); PillIcon(Icons.Rounded.Add, "Add a feed", { nav.addFeed() }); PillIcon(Icons.Rounded.Settings, "Settings", nav::openSettings) }
        }
    }) { contentPadding ->
        val pullState = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
        PullToRefreshBox(refreshing, { scope.launch { graph.refreshAll(force = true) } }, Modifier.fillMaxSize(), state = pullState,
            indicator = { GlassRefreshIndicator(pullState, refreshing, contentPadding.calculateTopPadding()) }) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = contentPadding.calculateTopPadding() + 4.dp, bottom = contentPadding.calculateBottomPadding()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (podcasts.isEmpty()) item { EmptyListen(nav) }
                if (inProgress.isNotEmpty()) item {
                    SectionTitle("Continue Listening")
                    LazyRow(Modifier.padding(top = 10.dp), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(inProgress, key = { it.first.id }) { (ep, feed) ->
                            ShowTile(feed.copy(title = ep.title, imageUrl = ep.imageUrl ?: feed.imageUrl), { nav.openEpisode(feed.id, ep.id) }, size = 132.dp, progress = progress[ep.id]?.fraction)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                if (queue.isNotEmpty()) {
                    item { SectionTitle("Up Next") { androidx.compose.material3.Text("See all", style = com.everettpenne.glass.IosType.subheadline.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = com.everettpenne.thumper.ui.theme.Thumper.accent, modifier = Modifier.clickable { nav.openQueue() }) } }
                    items(queue.filterIsInstance<com.everettpenne.thumper.data.model.Playable.Podcast>().take(3), key = { "q-" + it.id }) { entry ->
                        val ep = entry.episode
                        val feed = entry.feed
                        EpisodeRow(ep, feed, progress[ep.id], { nav.openEpisode(feed.id, ep.id) }, { playback.play(ep, feed) })
                    }
                    item { Spacer(Modifier.height(6.dp)) }
                }
                if (podcasts.isNotEmpty()) item {
                    SectionTitle("Your Shows")
                    LazyRow(Modifier.padding(top = 10.dp), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(podcasts, key = { it.id }) { feed -> ShowTile(feed, { nav.openFeed(feed.url) }) }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                if (fresh.isNotEmpty()) {
                    item { SectionTitle("New Episodes") }
                    items(fresh, key = { it.first.id }) { (ep, feed) ->
                        EpisodeRow(ep, feed, progress[ep.id], { nav.openEpisode(feed.id, ep.id) }, { if (playback.episodeId == ep.id) playback.toggle() else playback.play(ep, feed) })
                    }
                } else if (podcasts.isNotEmpty() && contents.isNotEmpty()) item { EmptyState(Icons.Rounded.CheckCircle, "All caught up", "New episodes will appear here.") }
            }
        }
    }
}

@Composable
private fun EmptyListen(nav: Navigator) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        androidx.compose.material3.Icon(Icons.Rounded.Podcasts, null, tint = com.everettpenne.thumper.ui.theme.Thumper.accent, modifier = Modifier.size(56.dp))
        Text("No podcasts yet", style = MaterialTheme.typography.titleLarge)
        Text("Search for a show, or add one by its feed address.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        GlassButton("Find podcasts", nav::openSearch, prominent = true)
        GlassButton("Add by address", { nav.addFeed() })
    }
}
