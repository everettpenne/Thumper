package com.everettpenne.thumper.ui.screens

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.everettpenne.thumper.data.Derived
import com.everettpenne.thumper.data.model.Article
import com.everettpenne.thumper.data.model.Feed
import com.everettpenne.thumper.data.model.FeedKind
import com.everettpenne.thumper.graph
import com.everettpenne.thumper.ui.components.*
import com.everettpenne.thumper.ui.glass.*
import com.everettpenne.thumper.ui.theme.IosType
import com.everettpenne.thumper.ui.theme.Thumper
import com.everettpenne.thumper.util.Format
import com.everettpenne.thumper.util.Html
import kotlinx.coroutines.launch

/** One article in a list: where it is from and when, its title, the start of its text, and its picture. Unread ones are bold with a dot. */
@Composable
fun ArticleRow(article: Article, feed: Feed, read: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val snippet = remember(article.id) { Html.plain(article.contentHtml).replace('\n', ' ').take(180) }
    Row(modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!read) Box(Modifier.size(8.dp).background(Thumper.accent, CircleShape))
                Text(listOf(feed.title, Format.relativeDate(article.publishedAt)).filter { it.isNotEmpty() }.joinToString("  ·  "), style = IosType.caption1, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(article.title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (read) FontWeight.Normal else FontWeight.SemiBold), maxLines = 3, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (read) .6f else 1f))
            if (snippet.isNotEmpty()) Text(snippet, style = IosType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (article.imageUrl != null) Artwork(article.imageUrl, 72.dp, radius = 10.dp, placeholder = Icons.Rounded.Article)
    }
}

/** The Read tab: the newest articles from the feeds you read, newest first, filterable by feed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadScreen(nav: Navigator) {
    val graph = LocalContext.current.graph
    val library = graph.library
    val contents by library.contents.collectAsState(); val read by library.read.collectAsState(); val feeds by library.feeds.collectAsState()
    val refreshing by graph.refreshing.collectAsState()
    val scope = rememberCoroutineScope()
    var only by rememberSaveable { mutableStateOf<String?>(null) }
    val readingFeeds = feeds.filter { it.kind == FeedKind.ARTICLES }
    val articles = remember(contents, only) { Derived.articles(contents).filter { it.second.kind == FeedKind.ARTICLES && (only == null || it.second.id == only) } }
    LaunchedEffect(Unit) { graph.refreshAll() }

    GlassScreen(bars = {
        GlassTopBar("Read", Modifier.align(Alignment.TopCenter)) {
            GlassActionPill {
                PillIcon(Icons.Rounded.DoneAll, "Mark all as read", { library.markAllRead(articles.map { it.first.id }) })
                PillIcon(Icons.Rounded.Add, "Add a feed", { nav.addFeed() }); PillIcon(Icons.Rounded.Settings, "Settings", nav::openSettings)
            }
        }
    }) {
        PullToRefreshBox(refreshing, { scope.launch { graph.refreshAll(force = true) } }, Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = topContentPadding() + 4.dp, bottom = bottomContentPadding())) {
                if (readingFeeds.size > 1) item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { GlassChip(only == null, { only = null }) { Text("All") } }
                        items(readingFeeds, key = { it.id }) { f -> GlassChip(only == f.id, { only = f.id }) { Text(f.title, maxLines = 1) } }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                if (readingFeeds.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        androidx.compose.material3.Icon(Icons.Rounded.Article, null, tint = Thumper.accent, modifier = Modifier.size(56.dp))
                        Text("Nothing to read yet", style = MaterialTheme.typography.titleLarge)
                        Text("Add the address of a blog or news site's feed, or of its home page.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp)); GlassButton("Add a feed", { nav.addFeed() }, prominent = true)
                    }
                }
                items(articles, key = { it.first.id }) { (a, f) -> ArticleRow(a, f, a.id in read, { nav.openArticle(f.id, a.id) }) }
            }
        }
    }
}
