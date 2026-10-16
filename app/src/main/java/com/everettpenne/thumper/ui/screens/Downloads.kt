package com.everettpenne.thumper.ui.screens

import com.everettpenne.thumper.ui.glass.pressSwell
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import com.everettpenne.thumper.ui.glass.GlassAlert
import com.everettpenne.thumper.ui.glass.GlassProgressBar
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
import com.everettpenne.thumper.data.DownloadInfo
import com.everettpenne.thumper.data.DownloadRecord
import com.everettpenne.thumper.data.DownloadStatus
import com.everettpenne.thumper.graph
import com.everettpenne.thumper.playback.LocalPlayback
import com.everettpenne.thumper.ui.components.*
import com.everettpenne.thumper.ui.glass.*
import com.everettpenne.thumper.ui.theme.IosType
import com.everettpenne.thumper.ui.theme.Thumper
import com.everettpenne.thumper.util.Format

/** Downloads: what is coming down and what is on the phone, how much space it takes, and quick ways to clear it. */
@Composable
fun DownloadsScreen(nav: Navigator, onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val downloads = graph.downloads
    val playback = LocalPlayback.current
    val records by downloads.records.collectAsState(); val state by downloads.state.collectAsState(); val total by downloads.totalBytes.collectAsState(0L)
    val progress by graph.library.progress.collectAsState()
    val coming = records.filter { state[it.episode.id]?.status != DownloadStatus.DONE }.sortedByDescending { it.requestedAt }
    val onPhone = records.filter { state[it.episode.id]?.status == DownloadStatus.DONE }.sortedByDescending { it.requestedAt }
    var confirmAll by remember { mutableStateOf(false) }

    GlassScreen(bars = { GlassCenteredTopBar("Downloads", onBack, Modifier.align(Alignment.TopCenter), subtitle = if (onPhone.isEmpty()) null else "${Format.bytes(total)} on this phone") }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = topContentPadding() + 4.dp, bottom = bottomContentPadding()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (records.isEmpty()) item { EmptyState(Icons.Rounded.DownloadForOffline, "No downloads", "Choose Download from the ••• on an episode to listen without a connection.") }
            if (onPhone.isNotEmpty()) item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassButton("Delete Played", { downloads.removePlayed(progress) })
                    GlassButton("Delete All", { confirmAll = true })
                }
            }
            if (coming.isNotEmpty()) {
                item { SectionTitle("Downloading") }
                items(coming, key = { "c-" + it.episode.id }) { r -> ComingRow(r, state[r.episode.id], onCancel = { downloads.remove(r.episode.id) }, onRetry = { downloads.download(r.episode, r.feed) }) }
            }
            if (onPhone.isNotEmpty()) {
                item { SectionTitle("On This Phone") }
                items(onPhone, key = { "d-" + it.episode.id }) { r ->
                    EpisodeRow(r.episode, r.feed, progress[r.episode.id], { nav.openEpisode(r.feed.id, r.episode.id) }, { if (playback.episodeId == r.episode.id) playback.toggle() else playback.play(r.episode, r.feed) })
                }
            }
        }
        if (confirmAll) GlassAlert("Delete all downloads?", "This frees ${Format.bytes(total)}. The episodes stay in their shows and can be downloaded again.", "Delete All",
            { confirmAll = false; downloads.removeAll() }, { confirmAll = false }, destructive = true)
    }
}

@Composable
private fun ComingRow(r: DownloadRecord, info: DownloadInfo?, onCancel: () -> Unit, onRetry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Artwork(r.episode.imageUrl ?: r.feed.imageUrl, 52.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(r.episode.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            when (info?.status) {
                DownloadStatus.FAILED -> Text("Download failed" + (info.problem?.let { ": $it" } ?: ""), style = IosType.caption1, color = Thumper.red)
                else -> {
                    GlassProgressBar(info?.fraction ?: 0f)
                    Text(info?.problem ?: (info?.fraction?.let { "${(it * 100).toInt()}%  ·  ${Format.bytes(info.bytes)} of ${Format.bytes(info.total)}" } ?: "Starting…"), style = IosType.caption1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (info?.status == DownloadStatus.FAILED) GlassButton("Retry", onRetry)
        Box(Modifier.size(40.dp).clip(CircleShape).pressSwell().clickable(interactionSource = null, indication = null, onClick = onCancel).semantics { contentDescription = "Cancel download of ${r.episode.title}" }, Alignment.Center) { Icon(Icons.Rounded.Close, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
