package com.everettpenne.thumper.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.everettpenne.glass.*
import com.everettpenne.thumper.data.model.Playable
import com.everettpenne.thumper.graph
import com.everettpenne.thumper.music.data.Artwork
import com.everettpenne.thumper.music.data.Playlist
import com.everettpenne.thumper.music.data.Track
import com.everettpenne.thumper.music.data.toPlayable
import com.everettpenne.thumper.playback.LocalPlayback
import com.everettpenne.thumper.ui.theme.Thumper

/** The long-press sheet for a song, shared by the Music library and playlists. */
class TrackActionsState {
    var track by mutableStateOf<Track?>(null); private set
    var visible by mutableStateOf(false); private set
    var playlistId by mutableStateOf<String?>(null); private set
    var onRemoveFromPlaylist: ((Track) -> Unit)? = null

    fun show(track: Track, playlistId: String? = null) {
        this.track = track; this.playlistId = playlistId; onRemoveFromPlaylist = null; visible = true
    }
    fun hide() { visible = false }
}

val LocalTrackActions = staticCompositionLocalOf<TrackActionsState?> { null }

@Composable
fun TrackActionSheet(state: TrackActionsState) {
    val track = state.track ?: return
    if (!state.visible) return
    val context = LocalContext.current
    val graph = context.graph
    val playback = LocalPlayback.current
    val favorites by graph.playlists.favorites.collectAsState()
    val playlists by graph.playlists.playlists.collectAsState()
    var adding by remember(state.visible, track.id) { mutableStateOf(false) }
    val favourite = track.id in favorites
    val ink = MaterialTheme.colorScheme.onSurface

    GlassSheet(onDismiss = state::hide) {
        if (adding) {
            AddToPlaylist(track, playlists) { state.hide() }
            return@GlassSheet
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AlbumArt(track.albumKey, Modifier.size(56.dp), corner = 12.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(track.title, style = IosType.headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist, style = IosType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        SheetAction(Icons.Rounded.QueueMusic, "Play next") {
            playback.playNext(track.toPlayable(graph.levels.gainFor(track.id), Artwork.fileUri(context, track.albumKey)))
            state.hide()
        }
        SheetAction(Icons.Rounded.PlaylistPlay, "Add to queue") {
            playback.addToQueue(track.toPlayable(graph.levels.gainFor(track.id), Artwork.fileUri(context, track.albumKey)))
            state.hide()
        }
        SheetAction(Icons.Rounded.PlaylistAdd, "Add to playlist") { adding = true }
        SheetAction(
            if (favourite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            if (favourite) "Remove from favourites" else "Add to favourites",
            tint = if (favourite) Thumper.red else null,
        ) { graph.playlists.toggleFavorite(track.id); state.hide() }
        val playlistId = state.playlistId
        if (state.onRemoveFromPlaylist != null && playlistId != null && track.id in (playlists.firstOrNull { it.id == playlistId }?.trackIds ?: emptyList())) {
            SheetAction(Icons.Rounded.RemoveCircleOutline, "Remove from this playlist", tint = Thumper.red) {
                state.onRemoveFromPlaylist?.invoke(track); state.hide()
            }
        }
    }
}

@Composable
private fun SheetAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color? = null, onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, null, tint = tint ?: ink, modifier = Modifier.size(22.dp))
        Text(label, style = IosType.body, color = tint ?: ink)
    }
}

@Composable
private fun AddToPlaylist(track: Track, playlists: List<Playlist>, onDone: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Add to playlist", style = IosType.title3.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
        Text("Done", style = IosType.subheadline.copy(fontWeight = FontWeight.SemiBold), color = Thumper.accent,
            modifier = Modifier.clickable(onClick = onDone).padding(8.dp))
    }
    if (creating) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f).height(46.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(23.dp)).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                if (name.isEmpty()) Text("Playlist name", style = IosType.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                BasicTextField(name, { name = it }, singleLine = true, textStyle = IosType.body.copy(color = glassPalette().ink), cursorBrush = SolidColor(Thumper.accent))
            }
            GlassButton("Create", {
                val playlist = graph.playlists.create(name)
                graph.playlists.add(playlist.id, listOf(track.id))
                onDone()
            }, enabled = name.isNotBlank(), prominent = true)
        }
    } else {
        SheetAction(Icons.Rounded.Add, "New playlist") { creating = true }
    }
    if (playlists.isEmpty() && !creating) Text("No playlists yet. Make one to keep this song.", style = IosType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp))
    playlists.forEach { playlist ->
        val contains = track.id in playlist.trackIds
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable {
                if (contains) graph.playlists.remove(playlist.id, track.id) else graph.playlists.add(playlist.id, listOf(track.id))
            }.padding(horizontal = 22.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AlbumArt(graph.music.tracks(playlist.trackIds).firstOrNull()?.albumKey, Modifier.size(36.dp), corner = 8.dp, iconSize = 16.dp)
            Text(playlist.name, style = IosType.body, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (contains) Icon(Icons.Rounded.Check, "Added", tint = Thumper.accent, modifier = Modifier.size(20.dp))
        }
    }
}

/** A one-field glass sheet for naming or renaming a playlist. */
@Composable
fun NameSheet(title: String, initial: String = "", confirmLabel: String = "Save", hint: String = "Name", onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    GlassSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(title, style = IosType.title3.copy(fontWeight = FontWeight.SemiBold))
            Box(Modifier.fillMaxWidth().height(50.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(25.dp)).padding(horizontal = 18.dp), contentAlignment = Alignment.CenterStart) {
                if (name.isEmpty()) Text(hint, style = IosType.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                BasicTextField(name, { name = it }, singleLine = true, textStyle = IosType.body.copy(color = glassPalette().ink), cursorBrush = SolidColor(Thumper.accent))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton("Cancel", onDismiss, Modifier.weight(1f))
                GlassButton(confirmLabel, { if (name.isNotBlank()) onDone(name.trim()) }, Modifier.weight(1f), enabled = name.isNotBlank(), prominent = true)
            }
            Spacer(Modifier.height(2.dp).imePadding())
        }
    }
}
