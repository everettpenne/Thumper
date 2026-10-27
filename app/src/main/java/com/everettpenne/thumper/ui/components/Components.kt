package com.everettpenne.thumper.ui.components

import com.everettpenne.media.LocalImageViewer
import com.everettpenne.glass.pressSwell
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import com.everettpenne.thumper.graph
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.everettpenne.thumper.data.model.Episode
import com.everettpenne.thumper.data.model.Feed
import com.everettpenne.thumper.data.model.Progress
import com.everettpenne.thumper.playback.LocalPlayback
import com.everettpenne.glass.glassPalette
import com.everettpenne.glass.glassSurface
import com.everettpenne.glass.IosType
import com.everettpenne.thumper.ui.theme.Thumper
import com.everettpenne.thumper.util.Format
import com.everettpenne.thumper.util.Html

/** Cover art from the web, with a quiet placeholder while it loads or when a show has none. [aspectRatio] is width ÷ height (1 for a square). */
@Composable
fun Artwork(url: String?, size: Dp, modifier: Modifier = Modifier, radius: Dp = 12.dp, placeholder: ImageVector = Icons.Rounded.Podcasts, zoomable: Boolean = false, aspectRatio: Float = 1f) {
    val openImages = LocalImageViewer.current
    val height = size / aspectRatio
    Box(modifier.size(width = size, height = height).clip(RoundedCornerShape(radius)).background(MaterialTheme.colorScheme.surfaceVariant)
        .then(if (zoomable && url != null) Modifier.clickable { openImages(listOf(url), 0) } else Modifier), contentAlignment = Alignment.Center) {
        Icon(placeholder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .35f), modifier = Modifier.size(minOf(size, height) * .4f))
        if (url != null) AsyncImage(url, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
    }
}

/**
 * A feed's logo at a small size: the site's icon (or the feed's picture), drawn over a monogram of its first letter on a tint of the app's accent,
 * so a feed with no icon, or one that fails to load, still has a recognisable mark.
 */
@Composable
fun FeedLogo(feed: Feed, size: Dp, modifier: Modifier = Modifier) {
    val letter = feed.title.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "•"
    Box(modifier.size(size).clip(RoundedCornerShape(size * .24f)).background(Thumper.accent.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
        Text(letter, color = Thumper.accent, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, fontSize = (size.value * .5f).sp))
        if (feed.logoUrl != null) AsyncImage(feed.logoUrl, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
    }
}

/** A round play/pause button for a row: shows pause while this episode is the one playing. */
@Composable
fun PlayCircle(playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Box(modifier.size(size).clip(CircleShape).background(Thumper.accent.copy(alpha = .14f))
        .clickable { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove); onClick() }
        .semantics { contentDescription = if (playing) "Pause" else "Play" }, contentAlignment = Alignment.Center) {
        Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Thumper.accent, modifier = Modifier.size(size * .62f))
    }
}

/** One episode in a list: its art, the show (when [showShow]), its title, when it came out and how long it is, and a play button. */
@Composable
fun EpisodeRow(
    episode: Episode, feed: Feed, progress: Progress?, onClick: () -> Unit, onPlay: () -> Unit, modifier: Modifier = Modifier, showShow: Boolean = true, showArt: Boolean = true,
) {
    val playback = LocalPlayback.current
    val current = playback.episodeId == episode.id
    val played = progress?.played == true
    Row(modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showArt) Artwork(episode.imageUrl ?: feed.imageUrl, 56.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (showShow) Text(feed.title, style = IosType.caption1, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(episode.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (played) FontWeight.Normal else FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (current) Thumper.accent else MaterialTheme.colorScheme.onSurface.copy(alpha = if (played) .6f else 1f))
            val meta = buildList {
                add(Format.relativeDate(episode.publishedAt))
                val left = progress?.takeIf { !it.played && it.positionMs > 5_000 }?.let { Format.remaining(it.durationMs, it.positionMs) }
                add(if (!left.isNullOrEmpty()) left else Format.duration(episode.durationSec))
                if (played) add("Played")
            }.filter { it.isNotEmpty() }.joinToString("  ·  ")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                DownloadBadge(episode.id)
                Text(meta, style = IosType.caption1, color = if (progress?.fraction != null) Thumper.accent else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        PlayCircle(playback.isPlaying && current, onPlay)
        EpisodeMenuButton(episode, feed)
    }
}

/** A small mark before an episode's details: a tick-arrow when it is on the phone, a ring filling up while it downloads. */
@Composable
fun DownloadBadge(episodeId: String) {
    val state by androidx.compose.ui.platform.LocalContext.current.graph.downloads.state.collectAsState()
    when (val d = state[episodeId]) {
        null -> {}
        else -> when (d.status) {
            com.everettpenne.thumper.data.DownloadStatus.DONE -> Icon(Icons.Rounded.DownloadDone, "Downloaded", tint = Thumper.accent, modifier = Modifier.size(14.dp))
            com.everettpenne.thumper.data.DownloadStatus.FAILED -> Icon(Icons.Rounded.ErrorOutline, "Download failed", tint = Thumper.red, modifier = Modifier.size(14.dp))
            else -> androidx.compose.material3.CircularProgressIndicator(progress = { d.fraction ?: 0f }, modifier = Modifier.size(12.dp), strokeWidth = 2.dp, color = Thumper.accent)
        }
    }
}

/** The "•••" on an episode: play it next, queue it, download it, mark it played. */
@Composable
fun EpisodeMenuButton(episode: Episode, feed: Feed, modifier: Modifier = Modifier) {
    val graph = androidx.compose.ui.platform.LocalContext.current.graph
    val library = graph.library
    val queue by library.queue.collectAsState(); val progress by library.progress.collectAsState(); val downloads by graph.downloads.state.collectAsState()
    var open by remember { mutableStateOf(false) }
    val queued = queue.any { it.id == episode.id }
    val played = progress[episode.id]?.played == true
    Box(modifier) {
        Box(Modifier.size(40.dp).clip(CircleShape).pressSwell().clickable(interactionSource = null, indication = null) { open = true }.semantics { contentDescription = "More for ${episode.title}" }, Alignment.Center) {
            Icon(Icons.Rounded.MoreHoriz, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        com.everettpenne.glass.GlassDropdownMenu(open, { open = false }) {
            com.everettpenne.glass.GlassMenuItem("Play Next", { library.enqueue(episode, feed, next = true); open = false }, icon = Icons.Rounded.QueuePlayNext)
            if (queued) com.everettpenne.glass.GlassMenuItem("Remove from Up Next", { library.dequeue(episode.id); open = false }, icon = Icons.Rounded.PlaylistRemove)
            else com.everettpenne.glass.GlassMenuItem("Add to Up Next", { library.enqueue(episode, feed); open = false }, icon = Icons.Rounded.PlaylistAdd)
            when (downloads[episode.id]?.status) {
                null, com.everettpenne.thumper.data.DownloadStatus.FAILED -> com.everettpenne.glass.GlassMenuItem("Download", { graph.downloads.download(episode, feed); open = false }, icon = Icons.Rounded.Download)
                com.everettpenne.thumper.data.DownloadStatus.DONE -> com.everettpenne.glass.GlassMenuItem("Remove Download", { graph.downloads.remove(episode.id); open = false }, icon = Icons.Rounded.DeleteOutline)
                else -> com.everettpenne.glass.GlassMenuItem("Cancel Download", { graph.downloads.remove(episode.id); open = false }, icon = Icons.Rounded.Cancel)
            }
            com.everettpenne.glass.GlassMenuItem(if (played) "Mark as Unplayed" else "Mark as Played", { library.markPlayed(episode.id, !played); open = false }, icon = if (played) Icons.Rounded.RadioButtonUnchecked else Icons.Rounded.CheckCircleOutline)
        }
    }
}

/** A show's cover with its name beneath, for a row of shows. [progress] (0..1) draws a thin "where you are" line under the art, for Continue Listening. */
@Composable
fun ShowTile(feed: Feed, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 112.dp, progress: Float? = null) {
    Column(modifier.width(size).clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Artwork(feed.imageUrl, size, radius = 16.dp)
        if (progress != null) {
            val track = glassPalette().ink.copy(alpha = .16f)
            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(percent = 50)).background(track)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).clip(RoundedCornerShape(percent = 50)).background(Thumper.accent))
            }
        }
        Text(feed.title, style = IosType.footnote.copy(fontWeight = FontWeight.Medium), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f)); trailing()
    }
}

/** The playing episode, as a glass capsule above the tab bar. Tap it for the full player. */
@Composable
fun MiniPlayer(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val pb = LocalPlayback.current
    if (!pb.hasEpisode) return
    val ink = glassPalette().ink
    val fraction = if (pb.durationMs > 0) (pb.positionMs.toFloat() / pb.durationMs).coerceIn(0f, 1f) else 0f
    Box(modifier.fillMaxWidth().padding(horizontal = 16.dp).height(64.dp).testTag("mini-player")
        .glassSurface(RoundedCornerShape(percent = 50), blurRadius = 8f, lensHeight = 22f, lensAmount = 32f).clip(RoundedCornerShape(percent = 50)).clickable(onClick = onOpen)) {
        Row(Modifier.fillMaxSize().padding(start = 10.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Artwork(pb.artworkUrl, 44.dp, radius = 22.dp)
            Column(Modifier.weight(1f)) {
                Text(pb.title, style = MaterialTheme.typography.titleSmall, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(pb.showTitle, style = IosType.caption1, color = ink.copy(alpha = .65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            MiniButton(if (pb.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (pb.isPlaying) "Pause" else "Play", ink, pb::toggle)
            if (pb.isMusic) {
                if (pb.hasNext) MiniButton(Icons.Rounded.SkipNext, "Next track", ink, pb::next)
            } else {
                MiniButton(Icons.Rounded.Forward30, "Skip forward", ink, pb::skipForward)
                if (pb.hasNext) MiniButton(Icons.Rounded.SkipNext, "Next episode", ink, pb::next)
            }
        }
        // A hairline of where you are, so the capsule also shows progress at a glance (like Apple and Spotify's mini players).
        if (fraction > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(fraction).height(3.dp).background(Thumper.accent, RoundedCornerShape(percent = 50)))
    }
}

@Composable
private fun MiniButton(icon: ImageVector, description: String, tint: Color, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).pressSwell().clickable(interactionSource = null, indication = null, onClick = onClick).semantics { contentDescription = description }, Alignment.Center) { Icon(icon, null, tint = tint, modifier = Modifier.size(28.dp)) }
}

/** Show notes and article bodies: the blocks of [Html.blocks] drawn as text, with links you can tap, pictures, lists, quotes and code. */
@Composable
fun RichText(html: String, modifier: Modifier = Modifier, base: String? = null, scale: Float = 1f) {
    val blocks = androidx.compose.runtime.remember(html, base) { Html.blocks(html, base) }
    // The reader's text size: every style grows or shrinks together, and so does the line spacing.
    fun androidx.compose.ui.text.TextStyle.sized() = if (scale == 1f) this else copy(fontSize = fontSize * scale, lineHeight = lineHeight * scale)
    val body = MaterialTheme.typography.bodyLarge.sized()
    val link = TextLinkStyles(SpanStyle(color = Thumper.accent, textDecoration = TextDecoration.Underline))
    fun annotated(runs: List<Html.Run>) = buildAnnotatedString {
        for (r in runs) {
            val style = SpanStyle(fontWeight = if (r.bold) FontWeight.Bold else null, fontStyle = if (r.italic) FontStyle.Italic else null, fontFamily = if (r.code) FontFamily.Monospace else null)
            if (r.href != null) withLink(LinkAnnotation.Url(r.href, link)) { withStyle(style) { append(r.text) } } else withStyle(style) { append(r.text) }
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val openImages = LocalImageViewer.current
        val pictures = blocks.filterIsInstance<Html.Block.Image>().map { it.url }
        for (b in blocks) when (b) {
            is Html.Block.Paragraph -> Text(annotated(b.runs), style = body)
            is Html.Block.Heading -> Text(annotated(b.runs), Modifier.padding(top = if (b.level <= 2) 8.dp else 4.dp), style = if (b.level <= 2) MaterialTheme.typography.titleLarge.sized() else MaterialTheme.typography.titleMedium.sized())
            is Html.Block.ListItem -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Text(b.marker, style = body, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(annotated(b.runs), style = body) }
            is Html.Block.Quote -> Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(Thumper.accent.copy(alpha = .6f), RoundedCornerShape(2.dp)))
                Text(annotated(b.runs), Modifier.padding(start = 12.dp), style = body, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .8f))
            }
            is Html.Block.Code -> Text(b.text, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)).padding(10.dp), style = IosType.footnote.sized().copy(fontFamily = FontFamily.Monospace))
            is Html.Block.Image -> AsyncImage(b.url, b.alt, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { openImages(pictures, pictures.indexOf(b.url).coerceAtLeast(0)) }, contentScale = ContentScale.FillWidth)
            Html.Block.Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        }
    }
}
