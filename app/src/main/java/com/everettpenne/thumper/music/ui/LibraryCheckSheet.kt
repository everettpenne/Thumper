package com.everettpenne.thumper.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.everettpenne.glass.GlassButton
import com.everettpenne.glass.GlassProgressBar
import com.everettpenne.glass.GlassSheet
import com.everettpenne.glass.IosType
import com.everettpenne.thumper.graph
import com.everettpenne.thumper.ui.theme.Thumper

/** The music library audit: vanished files, formats Android cannot decode, duplicates and albums without a cover. Read-only. */
@Composable
fun MusicLibraryCheckSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val snapshot by graph.music.snapshot.collectAsState()
    val state by graph.check.state.collectAsState()

    LaunchedEffect(Unit) {
        if (!state.running && state.report == null && snapshot.tracks.isNotEmpty()) graph.check.run(snapshot.tracks)
    }

    GlassSheet(onDismiss = onDismiss) {
        Box(Modifier.fillMaxWidth().heightIn(max = 640.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha = .94f))) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Library check", style = IosType.title3.copy(fontWeight = FontWeight.SemiBold))
                val report = state.report
                when {
                    state.running -> {
                        Text(if (state.total > 0) "Checking ${state.checked} of ${state.total}" else "Checking…", style = IosType.subheadline, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        GlassProgressBar(if (state.total > 0) state.checked.toFloat() / state.total else null)
                    }
                    state.error != null -> Text(state.error!!, style = IosType.subheadline, color = MaterialTheme.colorScheme.error)
                    report == null -> Text("No report yet.", style = IosType.subheadline, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    report.clean -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = Thumper.accent, modifier = Modifier.size(22.dp))
                        Text("Everything checks out: files are present, formats are playable, and every album has a cover.", style = IosType.subheadline)
                    }
                    else -> {
                        Text("${report.issueCount} thing" + (if (report.issueCount == 1) "" else "s") + " to look at", style = IosType.subheadline, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Section("Missing files", report.missingFiles.map { it.title }, "They disappeared after the last scan; a rescan will forget them.")
                        Section("Unplayable formats", report.unplayable.map { "${it.first.title} — ${it.second}" }, "These cannot be decoded by Android; convert them to FLAC or MP3.")
                        Section("Duplicate groups", report.duplicates.map { group -> group.joinToString(" / ") { it.title } }, "Same title, artist and length; keep the copy you want.")
                        Section("Albums without a cover", report.withoutArt.map { "${it.title} — ${it.artist}" }, null)
                        Section("Tracks without tags", report.withoutTags.map { it.fileName.ifBlank { it.title } }, "These fall back to their file names.")
                        Section("Zero-length tracks", report.zeroLength.map { it.title }, "The file may be missing or still being written.")
                    }
                }

                if (report != null && report.withoutArt.isNotEmpty()) {
                    Text("Covers are read from the folders and files themselves.", style = IosType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    GlassButton("Re-read local covers", {
                        graph.music.repairArtwork()
                        graph.check.run(snapshot.tracks)
                    }, Modifier.fillMaxWidth())
                }

                if (state.running) GlassButton("Stop", { graph.check.cancel() }, Modifier.fillMaxWidth())
                else GlassButton("Check again", { graph.check.run(snapshot.tracks) }, Modifier.fillMaxWidth())
                GlassButton("Done", onDismiss, Modifier.fillMaxWidth(), prominent = true)
            }
        }
    }
}

@Composable
private fun Section(title: String, items: List<String>, help: String?) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("$title · ${items.size}", style = IosType.subheadline.copy(fontWeight = FontWeight.SemiBold))
        items.take(4).forEach { Text("• $it", style = IosType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (items.size > 4) Text("…and ${items.size - 4} more", style = IosType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (help != null) Text(help, style = IosType.caption1, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
