package com.everettpenne.thumper.ui.screens

import com.everettpenne.glass.pressSwell
import com.everettpenne.glass.GlassSlider
import com.everettpenne.glass.GlassCircleButton
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.everettpenne.thumper.graph
import com.everettpenne.thumper.playback.LocalPlayback
import com.everettpenne.thumper.playback.NextUp
import com.everettpenne.thumper.data.model.Playable
import com.everettpenne.thumper.ui.components.*
import com.everettpenne.glass.*
import com.everettpenne.player.FullscreenPlayer
import com.everettpenne.media.VideoSurface
import androidx.compose.foundation.shape.RoundedCornerShape
import com.everettpenne.glass.IosType
import com.everettpenne.thumper.ui.theme.Thumper
import com.everettpenne.thumper.util.Format

private val SPEEDS = listOf(0.5f, 0.8f, 0.9f, 1f, 1.1f, 1.2f, 1.3f, 1.5f, 1.75f, 2f, 2.5f, 3f)
private val SLEEP_MINUTES = listOf(5, 10, 15, 30, 45, 60)

/**
 * The full player. Behind it, the cover blurred into a wash of its colours; on it, the cover (which eases back a little while paused), the
 * title and show, the scrubber with when the episode will end at this speed, skip and play, the next episode as a card, speed, sleep timer and
 * Up Next, and the episode's notes below, with links that open in the app's browser. A menu marks it played, shares it or goes to the show.
 */
@Composable
fun PlayerScreen(nav: Navigator, onBack: () -> Unit, inPictureInPicture: Boolean = false) {
    val pb = LocalPlayback.current
    val context = LocalContext.current
    val graph = context.graph
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    fun tap(action: () -> Unit): () -> Unit = { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.ContextClick); action() }
    val back by graph.settings.skipBackSeconds.collectAsState(); val forward by graph.settings.skipForwardSeconds.collectAsState()
    val queue by graph.library.queue.collectAsState(); val contents by graph.library.contents.collectAsState(); val progress by graph.library.progress.collectAsState()
    val nextUp = remember(pb.episodeId, pb.feedId, queue, contents, progress) { NextUp.next(pb.episodeId, pb.feedId, queue, contents, progress) }
    val feeds by graph.library.feeds.collectAsState()
    val episode = remember(pb.episodeId, pb.feedId, contents) { contents[pb.feedId]?.episodes?.firstOrNull { it.id == pb.episodeId } }
    var fullscreen by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }; var sleepMenu by remember { mutableStateOf(false) }; var moreMenu by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { pb.tick() }
    // Entering the floating window drops a full-screen video back to the inline player, so the PiP window owns the surface alone.
    LaunchedEffect(inPictureInPicture) { if (inPictureInPicture) fullscreen = false }

    GlassScreen(bars = {
        GlassCenteredTopBar("Now Playing", onBack, Modifier.align(Alignment.TopCenter), subtitle = pb.showTitle.takeIf { it.isNotBlank() }) {
            if (pb.hasEpisode && !pb.isMusic) Box {
                GlassIconButton(Icons.Rounded.MoreHoriz, "More", { moreMenu = true })
                GlassDropdownMenu(moreMenu, { moreMenu = false }) {
                    val ep = pb.episodeId; val fid = pb.feedId
                    if (ep != null) GlassMenuItem("Mark as Played", { graph.library.markPlayed(ep, true); moreMenu = false; pb.next() }, icon = Icons.Rounded.CheckCircle)
                    (episode?.link ?: episode?.audioUrl)?.let { link -> GlassMenuItem("Share Episode", { com.everettpenne.browser.Browsers.share(context, link, pb.title); moreMenu = false }, icon = Icons.Rounded.IosShare) }
                    feeds.firstOrNull { it.id == fid }?.let { f -> GlassMenuItem("Go to Show", { moreMenu = false; nav.openFeed(f.url) }, icon = Icons.Rounded.Podcasts) }
                    if (ep != null && fid != null) GlassMenuItem("Episode Page", { moreMenu = false; nav.openEpisode(fid, ep) }, icon = Icons.Rounded.Notes)
                }
            }
        }
    }) { contentPadding ->
        if (!pb.hasEpisode) { EmptyState(Icons.Rounded.Headphones, "Nothing playing", "Start an episode and it will appear here."); return@GlassScreen }
        Box(Modifier.fillMaxSize()) {
            // The cover's colours as a soft wash behind everything, fading into the page toward the bottom.
            if (!pb.hasVideo && pb.artworkUrl != null) {
                coil3.compose.AsyncImage(pb.artworkUrl, null, Modifier.fillMaxSize().blur(90.dp).graphicsLayer { alpha = .55f },
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                val bg = MaterialTheme.colorScheme.background
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to bg.copy(alpha = .15f), .5f to bg.copy(alpha = .55f), .85f to bg)))
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = contentPadding.calculateTopPadding() + 8.dp, bottom = 40.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(), start = 24.dp, end = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                val video = pb.controller
                if (pb.hasVideo && video != null) {
                    // a video podcast: the picture in place of the cover, with a button to take it full screen
                    Box(Modifier.fillMaxWidth().aspectRatio(pb.videoRatio.coerceIn(.6f, 2.4f)).clip(RoundedCornerShape(20.dp)).background(androidx.compose.ui.graphics.Color.Black)) {
                        VideoSurface(video, Modifier.fillMaxSize(), enabled = !fullscreen && !inPictureInPicture)
                        GlassCircleButton({ fullscreen = true }, Modifier.align(Alignment.BottomEnd).padding(10.dp).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = .4f), CircleShape),
                            size = 40.dp, description = "Full screen", shadowed = false) { Icon(Icons.Rounded.Fullscreen, null, tint = androidx.compose.ui.graphics.Color.White) }
                    }
                    // Glass's player: the gestures, speed, tracks, subtitles and picture-in-picture. Thumper keeps its queue, its saved places and its notification, so
                    // the player only drives the playing item and shows no repeat button.
                    if (fullscreen && !inPictureInPicture) FullscreenPlayer(video, pb.title) { fullscreen = false }
                } else {
                    // Playing, the cover is full size; paused, it eases back, as Apple's players do, so the state reads from across the room.
                    val size by animateFloatAsState(if (pb.isPlaying || pb.buffering) 1f else .86f, spring(dampingRatio = .62f, stiffness = 260f), label = "cover")
                    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        val side = minOf(maxWidth, 320.dp)
                        Box(Modifier.graphicsLayer { scaleX = size; scaleY = size; shadowElevation = 28f * size; shape = RoundedCornerShape(24.dp); clip = false }) {
                            Artwork(pb.artworkUrl, side, radius = 24.dp, zoomable = true)
                        }
                    }
                }
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(pb.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(pb.showTitle, style = MaterialTheme.typography.bodyLarge, color = Thumper.accent, maxLines = 1, modifier = Modifier.clickable {
                        graph.library.feeds.value.firstOrNull { it.id == pb.feedId }?.let { nav.openFeed(it.url) } })
                    episode?.publishedAt?.takeIf { it > 0 }?.let { Text(Format.relativeDate(it), style = IosType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                pb.error?.let { msg ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(percent = 50)).background(Thumper.red.copy(alpha = .12f)).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.ErrorOutline, null, tint = Thumper.red, modifier = Modifier.size(20.dp))
                        Text(msg, style = MaterialTheme.typography.bodyMedium, color = Thumper.red, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        GlassButton("Retry", pb::retry)
                    }
                }
                Column(Modifier.fillMaxWidth()) {
                    // The suite's shared player controls (Glass media): the same scrubber and transport as Spice and Seekur, with Thumper's skips either side.
                    com.everettpenne.media.PlayerScrubber(pb.positionMs, pb.durationMs, onSeek = pb::seekTo)
                    val ends = Format.endsAt(pb.positionMs, pb.durationMs, pb.speed)
                    if (ends.isNotEmpty()) Text(ends + if (pb.speed != 1f) "  ·  ${Format.remaining(pb.positionMs, pb.durationMs, pb.speed)} at ${Format.speed(pb.speed)}" else "",
                        style = IosType.caption1, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
                }
                com.everettpenne.media.PlayerTransport(pb.isPlaying, tap(pb::toggle), accent = Thumper.accent, buffering = pb.buffering, size = 76.dp,
                    start = {
                        EpisodeSkipButton(previous = true, onClick = tap(pb::previous))
                        if (!pb.isMusic) SkipButton(false, back, tap(pb::skipBack))
                    },
                    end = {
                        if (!pb.isMusic) SkipButton(true, forward, tap(pb::skipForward))
                        EpisodeSkipButton(previous = false, enabled = nextUp != null, onClick = tap(pb::next))
                    })
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!pb.isMusic) Box {
                        GlassChip(pb.speed != 1f, { speedMenu = true }) { ChipLabel(Icons.Rounded.Speed, Format.speed(pb.speed)) }
                        GlassDropdownMenu(speedMenu, { speedMenu = false }) { SPEEDS.forEach { s -> GlassMenuItem(Format.speed(s), { pb.changeSpeed(s); speedMenu = false }, selected = s == pb.speed) } }
                    }
                    Box {
                        GlassChip(pb.sleepLabel != null, { sleepMenu = true }) { ChipLabel(Icons.Rounded.Bedtime, pb.sleepLabel ?: "Sleep") }
                        GlassDropdownMenu(sleepMenu, { sleepMenu = false }) {
                            GlassMenuItem("Off", { pb.sleep(); sleepMenu = false }, selected = pb.sleepLabel == null)
                            SLEEP_MINUTES.forEach { m -> GlassMenuItem("$m minutes", { pb.sleep(m); sleepMenu = false }) }
                            GlassMenuItem("End of episode", { pb.sleep(endOfEpisode = true); sleepMenu = false })
                        }
                    }
                    GlassChip(false, { nav.openQueue() }) { ChipLabel(Icons.Rounded.QueueMusic, if (queue.isNotEmpty()) "Up Next ${queue.size}" else "Up Next") }
                }
                nextUp?.let { entry -> UpNextCard(entry, onPlay = tap(pb::next)) }
                // The show notes, right here, as Apple Podcasts has them: scroll down from the controls to read about the episode.
                val notes = episode?.summaryHtml.orEmpty()
                if (notes.isNotBlank()) Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Episode Notes", style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
                    RichText(notes, base = episode?.link)
                }
            }
        }
    }
}

/** What plays next, as a card with its cover: tap to skip to it now. */
@Composable
private fun UpNextCard(entry: Playable, onPlay: () -> Unit) {
    val subtitle = when (entry) {
        is Playable.Podcast -> listOf(entry.feed.title, Format.relativeDate(entry.episode.publishedAt)).filter { it.isNotEmpty() }.joinToString("  ·  ")
        is Playable.Music -> listOf(entry.artist, entry.album).filter { it.isNotEmpty() }.joinToString("  ·  ")
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f)).clickable(onClick = onPlay).padding(10.dp)
        .semantics { contentDescription = "Play next: ${entry.title}" }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Artwork(entry.artworkUrl, 52.dp, radius = 10.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("UP NEXT", style = IosType.caption1.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = Thumper.accent)
            Text(entry.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = IosType.caption1, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Rounded.SkipNext, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Skip to the previous or next episode: the same glass circle as the seconds skips, with the kit's transport glyphs. */
@Composable
private fun EpisodeSkipButton(previous: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val ink = glassPalette().ink
    Box(Modifier.size(52.dp).clip(CircleShape).pressSwell().clickable(interactionSource = null, indication = null, enabled = enabled, onClick = onClick)
        .semantics { contentDescription = if (previous) "Previous episode" else "Next episode" }, Alignment.Center) {
        Icon(if (previous) Icons.Rounded.SkipPrevious else Icons.Rounded.SkipNext, null, Modifier.size(40.dp), tint = ink.copy(alpha = if (enabled) 1f else .35f))
    }
}

/** Skip back or forward by the number of seconds chosen in Settings: a curved arrow with the number in it. */
@Composable
private fun SkipButton(forward: Boolean, seconds: Int, onClick: () -> Unit) {
    Box(Modifier.size(64.dp).clip(CircleShape).pressSwell().clickable(interactionSource = null, indication = null, onClick = onClick).semantics { contentDescription = (if (forward) "Skip forward " else "Skip back ") + "$seconds seconds" }, Alignment.Center) {
        Icon(Icons.Rounded.Replay, null, Modifier.size(52.dp).graphicsLayer { scaleX = if (forward) -1f else 1f }, tint = glassPalette().ink)
        Text("$seconds", style = IosType.caption1.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = glassPalette().ink, modifier = Modifier.padding(top = 3.dp))
    }
}

/** A chip's contents: a small icon and its words side by side (a chip lays its label out as a stack, so the pair needs a row). */
@Composable
private fun ChipLabel(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Icon(icon, null, Modifier.size(16.dp)); Text(text, maxLines = 1) }
}
