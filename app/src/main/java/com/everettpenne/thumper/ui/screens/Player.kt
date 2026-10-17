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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.everettpenne.thumper.graph
import com.everettpenne.thumper.playback.LocalPlayback
import com.everettpenne.thumper.ui.components.*
import com.everettpenne.glass.*
import com.everettpenne.media.FullscreenVideo
import com.everettpenne.media.VideoSurface
import androidx.compose.foundation.shape.RoundedCornerShape
import com.everettpenne.glass.IosType
import com.everettpenne.thumper.ui.theme.Thumper
import com.everettpenne.thumper.util.Format

private val SPEEDS = listOf(0.8f, 1f, 1.2f, 1.5f, 1.75f, 2f, 2.5f, 3f)
private val SLEEP_MINUTES = listOf(5, 15, 30, 45, 60)

/** The full player: big cover, the scrubber, skip and play, speed and sleep timer, and a way back to the episode's notes. */
@Composable
fun PlayerScreen(nav: Navigator, onBack: () -> Unit) {
    val pb = LocalPlayback.current
    val graph = LocalContext.current.graph
    val back by graph.settings.skipBackSeconds.collectAsState(); val forward by graph.settings.skipForwardSeconds.collectAsState()
    var scrub by remember { mutableStateOf<Float?>(null) }
    var fullscreen by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }; var sleepMenu by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { pb.tick() }
    val duration = pb.durationMs.coerceAtLeast(1)
    val shown = scrub?.let { (it * duration).toLong() } ?: pb.positionMs

    GlassScreen(bars = { GlassCenteredTopBar("Now Playing", onBack, Modifier.align(Alignment.TopCenter)) }) { contentPadding ->
        if (!pb.hasEpisode) { EmptyState(Icons.Rounded.Headphones, "Nothing playing", "Start an episode and it will appear here."); return@GlassScreen }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = contentPadding.calculateTopPadding() + 8.dp, bottom = 40.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(), start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            val video = pb.controller
            if (pb.hasVideo && video != null) {
                // a video podcast: the picture in place of the cover, with a button to take it full screen
                Box(Modifier.fillMaxWidth().aspectRatio(pb.videoRatio.coerceIn(.6f, 2.4f)).clip(RoundedCornerShape(20.dp)).background(androidx.compose.ui.graphics.Color.Black)) {
                    VideoSurface(video, Modifier.fillMaxSize(), enabled = !fullscreen)
                    GlassCircleButton({ fullscreen = true }, Modifier.align(Alignment.BottomEnd).padding(10.dp).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = .4f), CircleShape),
                        size = 40.dp, description = "Full screen", shadowed = false) { Icon(Icons.Rounded.Fullscreen, null, tint = androidx.compose.ui.graphics.Color.White) }
                }
                if (fullscreen) FullscreenVideo(video, pb.title) { fullscreen = false }
            } else BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Artwork(pb.artworkUrl, minOf(maxWidth, 320.dp), radius = 24.dp, zoomable = true) }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(pb.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(pb.showTitle, style = MaterialTheme.typography.bodyLarge, color = Thumper.accent, maxLines = 1, modifier = Modifier.clickable {
                    graph.library.feeds.value.firstOrNull { it.id == pb.feedId }?.let { nav.openFeed(it.url) } })
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
GlassSlider(value = scrub ?: (pb.positionMs.toFloat() / duration).coerceIn(0f, 1f), onValueChange = { scrub = it },
                    onValueChangeFinished = { scrub?.let { pb.seekTo((it * duration).toLong()) }; scrub = null }, description = "Seek")
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                    Text(Format.clock(shown), style = IosType.caption1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f)); Text("-" + Format.clock(pb.durationMs - shown), style = IosType.caption1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                SkipButton(false, back, pb::skipBack)
                GlassCircleButton(pb::toggle, size = 76.dp, tint = Thumper.accent, description = if (pb.isPlaying) "Pause" else "Play") {
                    if (pb.buffering) CircularProgressIndicator(Modifier.size(38.dp), color = androidx.compose.ui.graphics.Color.White, strokeWidth = 3.dp)
                    else Icon(if (pb.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(44.dp))
                }
                SkipButton(true, forward, pb::skipForward)
            }
            val upNext by graph.library.queue.collectAsState()
            if (upNext.isNotEmpty()) GlassButton({ pb.next() }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.SkipNext, null); Text("Next: ${upNext.first().episode.title}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box {
                    GlassChip(false, { speedMenu = true }) { ChipLabel(Icons.Rounded.Speed, Format.speed(pb.speed)) }
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
                val queued by graph.library.queue.collectAsState()
                if (queued.isNotEmpty()) GlassChip(false, { nav.openQueue() }) { ChipLabel(Icons.Rounded.QueueMusic, "Up Next ${queued.size}") }
                val ep = pb.episodeId; val fid = pb.feedId
                if (ep != null && fid != null) GlassChip(false, { nav.openEpisode(fid, ep) }) { ChipLabel(Icons.Rounded.Notes, "Notes") }
            }
        }
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
