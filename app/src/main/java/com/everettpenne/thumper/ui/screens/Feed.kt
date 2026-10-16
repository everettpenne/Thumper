package com.everettpenne.thumper.ui.screens

import com.everettpenne.thumper.ui.glass.GlassAlert
import androidx.compose.material3.Icon
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.everettpenne.thumper.data.api.ApiResult
import com.everettpenne.thumper.data.api.Failure
import com.everettpenne.thumper.data.feed.FeedFetch
import com.everettpenne.thumper.data.feed.FeedFetcher
import com.everettpenne.thumper.data.model.FeedContent
import com.everettpenne.thumper.data.model.FeedKind
import com.everettpenne.thumper.data.model.stableId
import com.everettpenne.thumper.graph
import com.everettpenne.thumper.playback.LocalPlayback
import com.everettpenne.thumper.ui.components.*
import com.everettpenne.thumper.ui.glass.*
import com.everettpenne.thumper.ui.theme.IosType
import com.everettpenne.thumper.util.Html

/** A show or a feed: its cover and description, a subscribe button, and its episodes (or articles). Works for feeds you have not subscribed to yet. */
@Composable
fun FeedScreen(url: String, nav: Navigator, onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val library = graph.library
    val playback = LocalPlayback.current
    val feedUrl = remember(url) { FeedFetcher.normalizeUrl(url) }
    val id = remember(feedUrl) { stableId(feedUrl) }
    val feeds by library.feeds.collectAsState(); val contents by library.contents.collectAsState(); val progress by library.progress.collectAsState(); val read by library.read.collectAsState()
    val subscribed = feeds.any { it.id == id }
    var preview by remember { mutableStateOf<FeedContent?>(graph.previews[id]) }
    var failure by remember { mutableStateOf<Failure?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var oldestFirst by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var confirmUnsubscribe by remember { mutableStateOf(false) }
    val content = contents[id] ?: preview

    LaunchedEffect(id, retry) {
        failure = null
        if (subscribed) { (library.refresh(id) as? ApiResult.Failed)?.let { if (contents[id] == null) failure = it.reason } }
        else if (preview == null) when (val r = graph.fetcher.fetch(feedUrl)) {
            is ApiResult.Ok -> (r.value as? FeedFetch.Updated)?.let { preview = it.content; graph.previews[id] = it.content }
            is ApiResult.Failed -> failure = r.reason
        }
    }

    GlassScreen(bars = { GlassCenteredTopBar("", onBack, Modifier.align(Alignment.TopCenter)) }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = topContentPadding(), bottom = bottomContentPadding()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (content == null) item { if (failure != null) ErrorState(failure!!) { retry++ } else LoadingRow() }
            content?.let { c ->
                item {
                    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
                            Artwork(c.feed.imageUrl, 120.dp, radius = 20.dp)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(c.feed.title, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                if (c.feed.author.isNotEmpty()) Text(c.feed.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                Text(if (c.feed.kind == FeedKind.PODCAST) "${c.episodes.size} episodes" else "${c.articles.size} articles", style = IosType.caption1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (subscribed) GlassButton({ confirmUnsubscribe = true }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Check, null); Text("Subscribed") }
                        else GlassButton({ library.subscribe(c) }, Modifier.fillMaxWidth(), prominent = true) { Icon(Icons.Rounded.Add, null); Text("Subscribe") }
                        if (subscribed && c.feed.kind == FeedKind.PODCAST) {
                            val auto = feeds.firstOrNull { it.id == id }?.autoDownload == true
                            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Download New Episodes", style = MaterialTheme.typography.bodyLarge)
                                    Text("When the app refreshes and finds one, it's saved for offline listening", style = IosType.caption1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                LiquidSwitch(auto, { library.setAutoDownload(id, it) })
                            }
                        }
                        val about = remember(c.feed.id) { Html.plain(c.feed.description) }
                        if (about.isNotEmpty()) Text(about, style = MaterialTheme.typography.bodyMedium, maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { expanded = !expanded })
                    }
                    Spacer(Modifier.height(10.dp))
                }
                if (c.feed.kind == FeedKind.PODCAST) {
                    item {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlassChip(!oldestFirst, { oldestFirst = false }) { Text("Newest") }; GlassChip(oldestFirst, { oldestFirst = true }) { Text("Oldest") }
                        }
                    }
                    val eps = if (oldestFirst) c.episodes.sortedBy { it.publishedAt } else c.episodes
                    items(eps, key = { it.id }) { ep ->
                        EpisodeRow(ep, c.feed, progress[ep.id], { nav.openEpisode(c.feed.id, ep.id) }, { if (playback.episodeId == ep.id) playback.toggle() else playback.play(ep, c.feed) }, showShow = false)
                    }
                } else items(c.articles, key = { it.id }) { a -> ArticleRow(a, c.feed, a.id in read, { nav.openArticle(c.feed.id, a.id) }) }
            }
        }
        if (confirmUnsubscribe) GlassAlert("Unsubscribe from ${content?.feed?.title.orEmpty()}?", "Its saved episodes are removed from this phone. Your listening history stays.", "Unsubscribe",
            { confirmUnsubscribe = false; content?.let { preview = it; graph.previews[id] = it }; library.unsubscribe(id) }, { confirmUnsubscribe = false }, destructive = true)
    }
}
