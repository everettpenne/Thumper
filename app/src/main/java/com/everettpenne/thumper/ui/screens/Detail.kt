package com.everettpenne.thumper.ui.screens

import androidx.compose.material3.Icon
import android.content.Intent
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.everettpenne.thumper.graph
import com.everettpenne.thumper.playback.LocalPlayback
import com.everettpenne.thumper.ui.components.*
import com.everettpenne.thumper.ui.glass.*
import com.everettpenne.thumper.ui.theme.IosType
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
    GlassScreen(bars = { GlassCenteredTopBar("", onBack, Modifier.align(Alignment.TopCenter)) }) {
        if (content == null || episode == null) { EmptyState(Icons.Rounded.ErrorOutline, "Episode not found", "It may have been removed from the feed."); return@GlassScreen }
        val feed = content.feed
        val current = playback.episodeId == episode.id
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = topContentPadding(), bottom = bottomContentPadding(), start = 20.dp, end = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
                Artwork(episode.imageUrl ?: feed.imageUrl, 104.dp, radius = 18.dp)
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
            if (episode.summaryHtml.isNotBlank()) RichText(episode.summaryHtml) else Text("No show notes.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** An article: its title, source and date, its picture, and its text with links, lists and images; and a button to open the original. Opening it marks it read. */
@Composable
fun ArticleScreen(feedId: String, articleId: String, onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val uri = LocalUriHandler.current
    val contents by graph.library.contents.collectAsState()
    val content = contents[feedId] ?: graph.previews[feedId]
    val article = content?.articles?.firstOrNull { it.id == articleId }
    LaunchedEffect(articleId) { graph.library.markRead(articleId) }
    GlassScreen(bars = { GlassCenteredTopBar("", onBack, Modifier.align(Alignment.TopCenter)) }) {
        if (content == null || article == null) { EmptyState(Icons.Rounded.ErrorOutline, "Article not found", "It may have been removed from the feed."); return@GlassScreen }
        val body = remember(article.id) { article.contentHtml }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = topContentPadding(), bottom = bottomContentPadding(), start = 20.dp, end = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(article.title, style = MaterialTheme.typography.headlineSmall)
            Text(listOf(content.feed.title, article.author.takeIf { it.isNotEmpty() }, Format.relativeDate(article.publishedAt).takeIf { it.isNotEmpty() }).filterNotNull().joinToString("  ·  "), style = IosType.caption1, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // the lead picture, unless the text already opens with it
            if (article.imageUrl != null && !body.contains(article.imageUrl)) AsyncImage(article.imageUrl, null, Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)), contentScale = ContentScale.FillWidth)
            if (body.isNotBlank()) RichText(body) else Text("This feed only sends the headline. Open the original to read it.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            article.link?.let { link -> GlassButton({ uri.openUri(link) }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.OpenInBrowser, null); Text("Open original") } }
        }
    }
}
