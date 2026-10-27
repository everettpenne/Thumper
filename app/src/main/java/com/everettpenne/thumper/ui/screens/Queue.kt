package com.everettpenne.thumper.ui.screens

import com.everettpenne.glass.pressSwell
import androidx.compose.foundation.clickable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.everettpenne.thumper.data.model.Playable
import com.everettpenne.thumper.graph
import com.everettpenne.thumper.playback.LocalPlayback
import com.everettpenne.thumper.ui.components.*
import com.everettpenne.glass.*
import com.everettpenne.glass.IosType
import com.everettpenne.thumper.ui.theme.Thumper
import com.everettpenne.thumper.util.Format

/** Up Next: what is playing and what follows, in order. Each entry can play now, move, or leave the queue. */
@Composable
fun QueueScreen(nav: Navigator, onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val library = graph.library
    val playback = LocalPlayback.current
    val queue by library.queue.collectAsState()
    // Drag to reorder: hold a row, then drag it over the others; they move aside as it passes, and it settles where it is let go.
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    val latestQueue by rememberUpdatedState(queue)

    GlassScreen(bars = {
        GlassCenteredTopBar("Up Next", onBack, Modifier.align(Alignment.TopCenter), subtitle = if (queue.isEmpty()) null else "${queue.size} item" + if (queue.size == 1) "" else "s",
            trailing = { if (queue.isNotEmpty()) GlassIconButton(Icons.Rounded.ClearAll, "Clear Up Next", { library.clearQueue() }, size = 44) })
    }) { contentPadding ->
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(top = contentPadding.calculateTopPadding() + 4.dp, bottom = contentPadding.calculateBottomPadding()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (playback.hasEpisode) item {
                SectionTitle("Now Playing"); Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth().clickable(onClick = nav::openPlayer).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Artwork(playback.artworkUrl, 52.dp)
                    Column(Modifier.weight(1f)) {
                        Text(playback.title, style = MaterialTheme.typography.titleSmall, color = Thumper.accent, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(playback.showTitle, style = IosType.caption1, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    PlayCircle(playback.isPlaying, playback::toggle)
                }
                Spacer(Modifier.height(10.dp))
            }
            if (queue.isEmpty()) item { EmptyState(Icons.Rounded.PlaylistAdd, "Nothing up next", "Add episodes with the ••• on any episode, or play an album from Music.") }
            else {
                item { SectionTitle("Next") { if (!playback.hasEpisode) GlassButton("Play", { queue.firstOrNull()?.let { playback.play(it) } }, prominent = true) } }
                itemsIndexed(queue, key = { _, e -> e.id }) { index, entry ->
                    val dragging = draggingId == entry.id
                    QueueRow(entry, index, queue.lastIndex, nav, Modifier
                        .then(if (dragging) Modifier.zIndex(1f) else Modifier.animateItem())
                        .graphicsLayer { translationY = if (dragging) dragDy else 0f; val s = if (dragging) 1.03f else 1f; scaleX = s; scaleY = s; shadowElevation = if (dragging) 24f else 0f }
                        .pointerInput(entry.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { draggingId = entry.id; dragDy = 0f; haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                                onDrag = { change, amount ->
                                    change.consume(); dragDy += amount.y
                                    val visible = listState.layoutInfo.visibleItemsInfo
                                    val me = visible.firstOrNull { it.key == entry.id } ?: return@detectDragGesturesAfterLongPress
                                    val centre = me.offset + me.size / 2f + dragDy
                                    val current = latestQueue
                                    val target = visible.firstOrNull { it.key != entry.id && centre >= it.offset && centre <= it.offset + it.size && current.any { q -> q.id == it.key } }
                                    if (target != null) {
                                        val from = current.indexOfFirst { it.id == entry.id }; val to = current.indexOfFirst { it.id == target.key }
                                        if (from >= 0 && to >= 0 && from != to) {
                                            library.moveInQueue(from, to)
                                            dragDy += (me.offset - target.offset).toFloat()
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                    }
                                },
                                onDragEnd = { draggingId = null; dragDy = 0f },
                                onDragCancel = { draggingId = null; dragDy = 0f })
                        })
                }
            }
        }
    }
}

@Composable
private fun QueueRow(entry: Playable, index: Int, last: Int, nav: Navigator, modifier: Modifier = Modifier) {
    val library = LocalContext.current.graph.library
    val playback = LocalPlayback.current
    var menu by remember { mutableStateOf(false) }
    val onClick: () -> Unit = when (entry) {
        is Playable.Podcast -> ({ nav.openEpisode(entry.feed.id, entry.episode.id) })
        is Playable.Music -> ({ playback.play(entry) })
    }
    Row(modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("${index + 1}", style = IosType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(18.dp))
        Artwork(entry.artworkUrl, 52.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            when (entry) {
                is Playable.Podcast -> {
                    Text(entry.feed.title, style = IosType.caption1, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(entry.episode.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOf(Format.relativeDate(entry.episode.publishedAt), Format.duration(entry.episode.durationSec)).filter { it.isNotEmpty() }.joinToString("  ·  "), style = IosType.caption1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is Playable.Music -> {
                    Text(entry.artist, style = IosType.caption1, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOf(entry.album, Format.duration((entry.durationMs / 1000L).toInt())).filter { it.isNotEmpty() }.joinToString("  ·  "), style = IosType.caption1, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Box {
            Box(Modifier.size(40.dp).clip(CircleShape).pressSwell().clickable(interactionSource = null, indication = null) { menu = true }.semantics { contentDescription = "Options for ${entry.title}" }, Alignment.Center) { Icon(Icons.Rounded.MoreVert, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            GlassDropdownMenu(menu, { menu = false }) {
                GlassMenuItem("Play Now", { menu = false; playback.play(entry) }, icon = Icons.Rounded.PlayArrow)
                if (index > 0) GlassMenuItem("Move to Top", { menu = false; library.moveInQueue(index, 0) }, icon = Icons.Rounded.VerticalAlignTop)
                if (index > 0) GlassMenuItem("Move Up", { menu = false; library.moveInQueue(index, index - 1) }, icon = Icons.Rounded.ArrowUpward)
                if (index < last) GlassMenuItem("Move Down", { menu = false; library.moveInQueue(index, index + 1) }, icon = Icons.Rounded.ArrowDownward)
                GlassMenuItem("Remove", { menu = false; library.dequeue(entry.id) }, icon = Icons.Rounded.Close, destructive = true)
            }
        }
    }
}
