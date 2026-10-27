package com.everettpenne.thumper.ui.screens

import com.everettpenne.glass.GlassRefreshIndicator
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import com.everettpenne.glass.*
import com.everettpenne.glass.IosType
import com.everettpenne.thumper.ui.theme.Thumper
import com.everettpenne.thumper.util.Format
import kotlinx.coroutines.launch

/**
 * One article in a list: where it is from (the feed's logo and name) and when, its headline, the start of its text, and its picture when it has one.
 * Unread ones are bold with an accent dot.
 */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun ArticleRow(article: Article, feed: Feed, read: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, saved: Boolean = false, onLongClick: (() -> Unit)? = null) {
    val snippet = remember(article.id) { Format.snippet(article.contentHtml) }
    // a post whose headline was made from its text would only repeat it as the snippet
    val showSnippet = snippet.isNotEmpty() && !snippet.startsWith(article.title.removeSuffix("…"))
    Row(modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        // A quiet leading gutter holds the unread dot, so read and unread rows keep their titles on the same line.
        Box(Modifier.padding(top = 6.dp).size(8.dp)) { if (!read) Box(Modifier.fillMaxSize().background(Thumper.accent, CircleShape)) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FeedLogo(feed, 18.dp)
                Text(listOf(feed.title, Format.relativeDate(article.publishedAt)).filter { it.isNotEmpty() }.joinToString("  ·  "), style = IosType.footnote,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (saved) androidx.compose.material3.Icon(Icons.Rounded.Bookmark, "Saved", Modifier.size(14.dp), tint = Thumper.accent)
            }
            Text(article.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (read) FontWeight.Normal else FontWeight.SemiBold), maxLines = 3, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (read) .6f else 1f))
            if (showSnippet) Text(snippet, style = IosType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (article.imageUrl != null) Artwork(article.imageUrl, 88.dp, Modifier.padding(top = 2.dp), radius = 10.dp, placeholder = Icons.Rounded.Article, aspectRatio = 1f)
    }
}

/** What the Read tab is showing: everything, only unread, only saved, or one feed. */
private sealed interface ReadFilter { data object All : ReadFilter; data object Unread : ReadFilter; data object Saved : ReadFilter; data class Only(val feedId: String) : ReadFilter }

/**
 * The Read tab: the newest articles from the feeds you read, newest first, in sections by day. Chips narrow it to unread articles, saved ones or
 * one feed (each feed's chip counts its unread). Holding an article offers save, mark read or unread, share and open original.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadScreen(nav: Navigator) {
    val context = LocalContext.current
    val graph = context.graph
    val library = graph.library
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val contents by library.contents.collectAsState(); val read by library.read.collectAsState(); val feeds by library.feeds.collectAsState()
    val saved by library.saved.collectAsState()
    val refreshing by graph.refreshing.collectAsState()
    val scope = rememberCoroutineScope()
    var filterKey by rememberSaveable { mutableStateOf("all") }
    val filter: ReadFilter = when { filterKey == "all" -> ReadFilter.All; filterKey == "unread" -> ReadFilter.Unread; filterKey == "saved" -> ReadFilter.Saved; else -> ReadFilter.Only(filterKey) }
    val readingFeeds = feeds.filter { it.kind == FeedKind.ARTICLES }
    val all = remember(contents) { Derived.articles(contents).filter { it.second.kind == FeedKind.ARTICLES } }
    val articles = remember(all, filter, read, saved, feeds) {
        when (filter) {
            ReadFilter.All -> all
            ReadFilter.Unread -> all.filter { it.first.id !in read }
            ReadFilter.Saved -> saved.mapNotNull { a -> feeds.firstOrNull { it.id == a.feedId }?.let { a to it } }
            is ReadFilter.Only -> all.filter { it.second.id == filter.feedId }
        }
    }
    val unreadByFeed = remember(all, read) { all.filter { it.first.id !in read }.groupingBy { it.second.id }.eachCount() }
    val unread = unreadByFeed.values.sum()
    // Sections by day, except for saved articles, which keep the order they were saved in.
    val sections = remember(articles, filter) { if (filter == ReadFilter.Saved) listOf("" to articles) else articles.groupBy { Format.daySection(it.first.publishedAt) }.toList() }
    LaunchedEffect(Unit) { graph.refreshAll() }

    GlassScreen(bars = {
        GlassTopBar("Read", Modifier.align(Alignment.TopCenter), subtitle = if (unread > 0) "$unread unread" else null) {
            GlassActionPill {
                PillIcon(Icons.Rounded.DoneAll, "Mark all as read", { library.markAllRead(articles.map { it.first.id }) })
                PillIcon(Icons.Rounded.Add, "Add a feed", { nav.addFeed() }); PillIcon(Icons.Rounded.Settings, "Settings", nav::openSettings)
            }
        }
    }) { contentPadding ->
        val pullState = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
        PullToRefreshBox(refreshing, { scope.launch { graph.refreshAll(force = true) } }, Modifier.fillMaxSize(), state = pullState,
            indicator = { GlassRefreshIndicator(pullState, refreshing, contentPadding.calculateTopPadding()) }) {
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            com.everettpenne.thumper.ui.ScrollToTopOnReselect(com.everettpenne.thumper.ui.Tab.Read, listState)
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(top = contentPadding.calculateTopPadding() + 4.dp, bottom = contentPadding.calculateBottomPadding())) {
                if (readingFeeds.isNotEmpty()) item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { GlassChip(filter == ReadFilter.All, { filterKey = "all" }) { Text("All") } }
                        item { GlassChip(filter == ReadFilter.Unread, { filterKey = "unread" }) { Text(if (unread > 0) "Unread  $unread" else "Unread") } }
                        item { GlassChip(filter == ReadFilter.Saved, { filterKey = "saved" }) { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            androidx.compose.material3.Icon(Icons.Rounded.Bookmark, null, Modifier.size(16.dp)); Text(if (saved.isNotEmpty()) "Saved  ${saved.size}" else "Saved") } } }
                        if (readingFeeds.size > 1) items(readingFeeds, key = { it.id }) { f ->
                            GlassChip(filter == ReadFilter.Only(f.id), { filterKey = f.id }) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    FeedLogo(f, 18.dp); Text(f.title, maxLines = 1)
                                    unreadByFeed[f.id]?.let { n -> Text("$n", style = IosType.caption1, color = Thumper.accent) }
                                }
                            }
                        }
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
                } else if (articles.isEmpty()) item {
                    when (filter) {
                        ReadFilter.Unread -> EmptyState(Icons.Rounded.CheckCircle, "All caught up", "Every article has been read.")
                        ReadFilter.Saved -> EmptyState(Icons.Rounded.BookmarkBorder, "Nothing saved", "Tap the bookmark on an article, or hold it in the list, to keep it here.")
                        else -> EmptyState(Icons.Rounded.CheckCircle, "No articles yet", "New articles will appear here when these feeds update.")
                    }
                }
                sections.forEach { (title, list) ->
                    if (title.isNotEmpty()) item(key = "section-$title") {
                        Text(title, style = IosType.footnote.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(start = 36.dp, end = 16.dp, top = 14.dp, bottom = 2.dp))
                    }
                    items(list, key = { it.first.id }) { (a, f) ->
                        var held by remember { mutableStateOf(false) }
                        Box {
                            ArticleRow(a, f, a.id in read, { nav.openArticle(f.id, a.id) }, saved = saved.any { it.id == a.id }, onLongClick = { held = true })
                            GlassDropdownMenu(held, { held = false }) {
                                val isSaved = saved.any { it.id == a.id }
                                GlassMenuItem(if (isSaved) "Remove from Saved" else "Save for Later", { library.setSaved(a, !isSaved); held = false }, icon = if (isSaved) Icons.Rounded.BookmarkRemove else Icons.Rounded.BookmarkBorder)
                                if (a.id in read) GlassMenuItem("Mark as Unread", { library.markRead(a.id, read = false); held = false }, icon = Icons.Rounded.MarkEmailUnread)
                                else GlassMenuItem("Mark as Read", { library.markRead(a.id); held = false }, icon = Icons.Rounded.Drafts)
                                a.link?.let { link ->
                                    GlassMenuItem("Share", { com.everettpenne.browser.Browsers.share(context, link, a.title); held = false }, icon = Icons.Rounded.IosShare)
                                    GlassMenuItem("Open Original", { uri.openUri(link); held = false }, icon = Icons.Rounded.Language)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
