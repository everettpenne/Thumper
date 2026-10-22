package com.everettpenne.thumper.ui.screens

import androidx.compose.material3.Icon
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.everettpenne.thumper.graph
import com.everettpenne.thumper.playback.LocalPlayback
import com.everettpenne.thumper.ui.components.*
import com.everettpenne.glass.*
import com.everettpenne.glass.IosType
import com.everettpenne.thumper.util.Format
import com.everettpenne.thumper.util.Html

/** An episode: its cover, a play button that says where you are, and its show notes with their links. */
@Composable
fun EpisodeScreen(feedId: String, episodeId: String, nav: Navigator, onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val playback = LocalPlayback.current
    val contents by graph.library.contents.collectAsState(); val progressMap by graph.library.progress.collectAsState()
    val content = contents[feedId] ?: graph.previews[feedId]
    val episode = content?.episodes?.firstOrNull { it.id == episodeId }
    val progress = progressMap[episodeId]
    GlassScreen(bars = { GlassCenteredTopBar("", onBack, Modifier.align(Alignment.TopCenter)) }) { contentPadding ->
        if (content == null || episode == null) { EmptyState(Icons.Rounded.ErrorOutline, "Episode not found", "It may have been removed from the feed."); return@GlassScreen }
        val feed = content.feed
        val current = playback.episodeId == episode.id
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding(), start = 20.dp, end = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
                Artwork(episode.imageUrl ?: feed.imageUrl, 104.dp, radius = 18.dp, zoomable = true)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(episode.title, style = MaterialTheme.typography.titleLarge, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Text(feed.title, style = MaterialTheme.typography.bodyMedium, color = com.everettpenne.thumper.ui.theme.Thumper.accent, maxLines = 1, modifier = Modifier.clickable { nav.openFeed(feed.url) })
                    Text(listOf(Format.relativeDate(episode.publishedAt), Format.duration(episode.durationSec)).filter { it.isNotEmpty() }.joinToString("  ·  "), style = IosType.caption1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val label = when {
                current && playback.isPlaying -> "Pause"
                current -> "Resume"
                progress?.fraction != null -> "Resume  ·  " + Format.remaining(progress.durationMs, progress.positionMs)
                progress?.played == true -> "Play again"
                else -> "Play"
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassButton({ if (current) playback.toggle() else playback.play(episode, feed) }, Modifier.weight(1f), prominent = true) {
                    Icon(if (current && playback.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null); Text(label, maxLines = 1)
                }
                GlassIconButton(if (progress?.played == true) Icons.Rounded.CheckCircle else Icons.Rounded.CheckCircleOutline, if (progress?.played == true) "Mark as unplayed" else "Mark as played",
                    { graph.library.markPlayed(episode.id, progress?.played != true) }, tint = if (progress?.played == true) com.everettpenne.thumper.ui.theme.Thumper.accent else null, size = 48)
                (episode.link ?: feed.siteUrl)?.let { link ->
                    GlassIconButton(Icons.Rounded.Share, "Share", { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "${episode.title} — $link"), null)) }, size = 48)
                }
            }
            val download = graph.downloads.state.collectAsState().value[episode.id]
            when (download?.status) {
                null, com.everettpenne.thumper.data.DownloadStatus.FAILED -> GlassButton({ graph.downloads.download(episode, feed) }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Download, null); Text(if (download != null) "Download failed. Try again" else "Download") }
                com.everettpenne.thumper.data.DownloadStatus.DONE -> GlassButton({ graph.downloads.remove(episode.id) }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.DownloadDone, null); Text("Downloaded  ·  ${Format.bytes(download.bytes)}  ·  Remove") }
                else -> GlassButton({ graph.downloads.remove(episode.id) }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Cancel, null); Text(download.problem ?: ("Downloading" + (download.fraction?.let { "  ${(it * 100).toInt()}%" } ?: "…")) + "  ·  Cancel") }
            }
            if (episode.summaryHtml.isNotBlank()) RichText(episode.summaryHtml, base = episode.link) else Text("No show notes.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * An article, in a reader: its source, date and reading time, title, picture and text, at the size chosen with Aa. When the feed sends only a
 * teaser, the whole article is fetched from its page (see FullText) and shown instead, with a note saying so. The menu saves it for later,
 * marks it unread, shares it or opens the original in the browser; the foot of the page goes on to the next article (or back to the previous
 * one) in the Read tab's order. Opening it marks it read.
 */
@Composable
fun ArticleScreen(feedId: String, articleId: String, onBack: () -> Unit, onOpen: (String, String) -> Unit = { _, _ -> }) {
    val context = LocalContext.current
    val graph = context.graph
    val uri = LocalUriHandler.current
    val contents by graph.library.contents.collectAsState()
    val saved by graph.library.saved.collectAsState()
    val scale by graph.settings.readerScale.collectAsState()
    val loadFull by graph.settings.fullArticles.collectAsState()
    val content = contents[feedId] ?: graph.previews[feedId]
    val article = content?.articles?.firstOrNull { it.id == articleId } ?: saved.firstOrNull { it.id == articleId }
    val feeds by graph.library.feeds.collectAsState()
    val feed = content?.feed ?: feeds.firstOrNull { it.id == feedId }
    LaunchedEffect(articleId) { graph.library.markRead(articleId) }
    var menu by remember { mutableStateOf(false) }
    var sizeMenu by remember { mutableStateOf(false) }
    val isSaved = saved.any { it.id == articleId }
    GlassScreen(bars = {
        GlassCenteredTopBar("", onBack, Modifier.align(Alignment.TopCenter)) {
            if (article != null) GlassActionPill {
                Box {
                    PillIcon(Icons.Rounded.TextFields, "Text size", { sizeMenu = true })
                    GlassDropdownMenu(sizeMenu, { sizeMenu = false }) {
                        READER_SIZES.forEach { (label, v) -> GlassMenuItem(label, { graph.settings.setReaderScale(v); sizeMenu = false }, selected = kotlin.math.abs(scale - v) < .01f) }
                    }
                }
                PillIcon(if (isSaved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, if (isSaved) "Remove from saved" else "Save for later",
                    { graph.library.setSaved(article, !isSaved) })
                Box {
                    PillIcon(Icons.Rounded.MoreHoriz, "More", { menu = true })
                    GlassDropdownMenu(menu, { menu = false }) {
                        article.link?.let { link ->
                            GlassMenuItem("Open Original", { uri.openUri(link); menu = false }, icon = Icons.Rounded.Language)
                            GlassMenuItem("Share", { com.everettpenne.browser.Browsers.share(context, link, article.title); menu = false }, icon = Icons.Rounded.IosShare)
                        }
                        GlassMenuItem("Mark as Unread", { graph.library.markRead(articleId, read = false); menu = false; onBack() }, icon = Icons.Rounded.MarkEmailUnread)
                    }
                }
            }
        }
    }) { contentPadding ->
        if (feed == null || article == null) { EmptyState(Icons.Rounded.ErrorOutline, "Article not found", "It may have been removed from the feed."); return@GlassScreen }
        val teaser = remember(article.id) { com.everettpenne.thumper.data.feed.ArticleText.isTeaser(article.contentHtml) }
        var full by remember(article.id) { mutableStateOf(graph.fullText.cached(article.id)) }
        var fetching by remember(article.id) { mutableStateOf(false) }
        var fetchFailed by remember(article.id) { mutableStateOf(false) }
        val link = article.link
        fun fetch() { if (link != null && !fetching) { fetching = true; fetchFailed = false } }
        LaunchedEffect(article.id, loadFull) { if (full == null && teaser && loadFull && link != null) fetch() }
        LaunchedEffect(fetching) {
            if (fetching && link != null) { val got = graph.fullText.load(article.id, link); full = got; fetchFailed = got == null; fetching = false }
        }
        val body = full ?: article.contentHtml
        val minutes = remember(body) { com.everettpenne.thumper.data.feed.ArticleText.readingMinutes(body) }
        // Everything in the Read tab, in its order, for the next and previous article.
        val order = remember(contents) { com.everettpenne.thumper.data.Derived.articles(contents).filter { it.second.kind == com.everettpenne.thumper.data.model.FeedKind.ARTICLES } }
        val index = order.indexOfFirst { it.first.id == articleId }
        val next = order.getOrNull(index + 1).takeIf { index >= 0 }
        val previous = order.getOrNull(index - 1).takeIf { index > 0 }
        val readerWidth = Modifier.widthIn(max = 680.dp).fillMaxWidth()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding() + 16.dp, start = 20.dp, end = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Column(readerWidth, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FeedLogo(feed, 32.dp)
                    Column {
                        Text(feed.title, style = IosType.subheadline.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOf(article.author.takeIf { it.isNotEmpty() }, Format.relativeDate(article.publishedAt).takeIf { it.isNotEmpty() }, "$minutes min read").filterNotNull().joinToString("  ·  "),
                            style = IosType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text(article.title, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = MaterialTheme.typography.headlineSmall.fontSize * scale,
                    lineHeight = MaterialTheme.typography.headlineSmall.lineHeight * scale))
                val openImages = com.everettpenne.media.LocalImageViewer.current
                // the lead picture, unless the text already opens with it
                if (article.imageUrl != null && !body.contains(article.imageUrl)) AsyncImage(article.imageUrl, null, Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).clickable { openImages(listOf(article.imageUrl), 0) }, contentScale = ContentScale.FillWidth)
                when {
                    fetching && full == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = com.everettpenne.thumper.ui.theme.Thumper.accent)
                        Text("Getting the full article…", style = IosType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    full != null -> Text("Full article from ${link?.let { com.everettpenne.browser.BrowserUrls.host(it) } ?: "its page"}", style = IosType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    teaser && link != null && !loadFull -> GlassButton({ fetch() }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Article, null); Text("Load the Full Article") }
                    fetchFailed -> Text("The full article couldn't be found on its page.", style = IosType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (body.isNotBlank()) RichText(body, base = link, scale = scale) else Text("This feed only sends the headline. Open the original to read it.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                link?.let { l -> GlassButton({ uri.openUri(l) }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Language, null); Text("Open Original") } }
                if (next != null || previous != null) {
                    Spacer(Modifier.height(4.dp))
                    next?.let { (a, f) -> NextArticle("Next", a, f) { onOpen(f.id, a.id) } }
                    previous?.let { (a, f) -> NextArticle("Previous", a, f) { onOpen(f.id, a.id) } }
                }
            }
        }
    }
}

/** The reader's text sizes, smallest to largest. */
private val READER_SIZES = listOf("Smaller" to .9f, "Normal" to 1f, "Larger" to 1.15f, "Largest" to 1.3f)

/** A card at the foot of an article for the one after (or before) it. */
@Composable
private fun NextArticle(label: String, article: com.everettpenne.thumper.data.model.Article, feed: com.everettpenne.thumper.data.model.Feed, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f))
        .clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label.uppercase(), style = IosType.caption1.copy(fontWeight = FontWeight.SemiBold), color = com.everettpenne.thumper.ui.theme.Thumper.accent)
                FeedLogo(feed, 14.dp)
                Text(feed.title, style = IosType.caption1, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(article.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (article.imageUrl != null) Artwork(article.imageUrl, 56.dp, radius = 8.dp, placeholder = Icons.Rounded.Article, aspectRatio = 1f)
        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
