package com.everettpenne.thumper.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.everettpenne.glass.*
import com.everettpenne.thumper.graph
import com.everettpenne.thumper.music.data.Artwork
import com.everettpenne.thumper.music.data.Playlist
import com.everettpenne.thumper.music.data.Track
import com.everettpenne.thumper.music.data.toPlayable
import com.everettpenne.thumper.playback.LocalPlayback
import com.everettpenne.thumper.ui.components.EmptyState
import com.everettpenne.thumper.ui.theme.Thumper
import com.everettpenne.thumper.util.Format

/** Favourites and the person's own lists, in the Music tab. */
@Composable
fun MusicPlaylistsScreen(onBack: () -> Unit, onOpen: (String) -> Unit, onFavorites: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val favorites by graph.playlists.favorites.collectAsState()
    val playlists by graph.playlists.playlists.collectAsState()
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Playlist?>(null) }
    var deleting by remember { mutableStateOf<Playlist?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }

    GlassScreen(bars = {
        GlassCenteredTopBar(
            "Playlists",
            onBack = onBack,
            trailing = { GlassIconButton(Icons.Rounded.Add, "New playlist", { creating = true }) },
        )
    }) { padding ->
        if (favorites.isEmpty() && playlists.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                EmptyState(Icons.Rounded.PlaylistAdd, "No playlists yet", "Make a list for a mood or a drive. Then add songs from anywhere by holding one down.")
                GlassButton("New playlist", { creating = true }, prominent = true)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 6.dp, bottom = contentBottomPadding() + 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (favorites.isNotEmpty()) item("favorites") {
                    PlaylistRow("Favourites", favorites.size, null, true, onFavorites)
                }
                items(playlists, key = { it.id }) { playlist ->
                    val artwork = remember(playlist.trackIds) { graph.music.tracks(playlist.trackIds).firstOrNull()?.albumKey }
                    PlaylistRow(playlist.name, playlist.trackIds.size, artwork, false, { onOpen(playlist.id) }) {
                        Box {
                            GlassIconButton(Icons.Rounded.MoreVert, "Playlist actions", { menuFor = playlist.id }, size = 36)
                            GlassDropdownMenu(menuFor == playlist.id, { menuFor = null }) {
                                GlassMenuItem("Rename", { menuFor = null; renaming = playlist }, icon = Icons.Rounded.Edit)
                                GlassMenuItem("Delete", { menuFor = null; deleting = playlist }, icon = Icons.Rounded.Delete, destructive = true)
                            }
                        }
                    }
                }
            }
        }
    }

    if (creating) NameSheet("New playlist", hint = "Playlist name", confirmLabel = "Create", onDone = { name ->
        val playlist = graph.playlists.create(name); creating = false; onOpen(playlist.id)
    }, onDismiss = { creating = false })
    renaming?.let { playlist -> NameSheet("Rename playlist", initial = playlist.name, onDone = { name -> graph.playlists.rename(playlist.id, name); renaming = null }, onDismiss = { renaming = null }) }
    deleting?.let { playlist ->
        GlassAlert("Delete playlist?", "\"${playlist.name}\" will be removed. The songs stay in your library.", "Delete", {
            graph.playlists.delete(playlist.id); deleting = null
        }, { deleting = null }, destructive = true)
    }
}

@Composable
private fun PlaylistRow(name: String, count: Int, artKey: String?, favourites: Boolean, onClick: () -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 70.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (favourites) Box(Modifier.size(54.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .16f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Favorite, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        } else AlbumArt(artKey, Modifier.size(54.dp), corner = 12.dp, iconSize = 20.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = IosType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$count song" + (if (count == 1) "" else "s"), style = IosType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

/** Favourites, or one playlist. The same player and queue as the Music tab. */
@Composable
fun MusicPlaylistScreen(id: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val playback = LocalPlayback.current
    val actions = LocalTrackActions.current
    val playlists by graph.playlists.playlists.collectAsState()
    val favorites by graph.playlists.favorites.collectAsState()
    val snapshot by graph.music.snapshot.collectAsState()
    val isFavorites = id == FAVORITES
    val playlist = playlists.firstOrNull { it.id == id }
    val tracks = remember(isFavorites, favorites, playlists, snapshot.tracks) {
        if (isFavorites) favorites.mapNotNull { graph.music.find(it) }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        else graph.music.tracks(playlist?.trackIds.orEmpty())
    }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    if (!isFavorites && playlist == null) {
        GlassScreen(bars = { GlassCenteredTopBar("Playlist", onBack = onBack) }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { EmptyState(Icons.Rounded.PlaylistAdd, "Playlist not found", "It may have been deleted.") }
        }
        return
    }

    val title = if (isFavorites) "Favourites" else playlist!!.name
    GlassScreen(bars = {
        GlassCenteredTopBar(title, onBack = onBack, trailing = {
            if (!isFavorites) Box {
                GlassIconButton(Icons.Rounded.MoreVert, "Playlist actions", { menu = true }, size = 36)
                GlassDropdownMenu(menu, { menu = false }) {
                    GlassMenuItem("Rename", { menu = false; renaming = true }, icon = Icons.Rounded.Edit)
                    GlassMenuItem("Delete", { menu = false; deleting = true }, icon = Icons.Rounded.Delete, destructive = true)
                }
            }
        })
    }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 6.dp, bottom = contentBottomPadding() + 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item("header") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (isFavorites) Box(Modifier.size(150.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .16f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Favorite, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(60.dp))
                    } else AlbumArt(tracks.firstOrNull()?.albumKey, Modifier.size(150.dp), corner = 18.dp, iconSize = 36.dp)
                    Spacer(Modifier.height(16.dp))
                    Text(title, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${tracks.size} song" + (if (tracks.size == 1) "" else "s") + " · " + Format.duration((tracks.sumOf { it.durationMs } / 1000).toInt()),
                        style = IosType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassButton("Play", {
                            playback.playQueue(tracks.map { it.toPlayable(graph.levels.gainFor(it.id), Artwork.fileUri(context, it.albumKey)) }, 0)
                        }, Modifier.weight(1f), prominent = true, enabled = tracks.isNotEmpty())
                        GlassButton("Shuffle", {
                            playback.playQueue(tracks.shuffled().map { it.toPlayable(graph.levels.gainFor(it.id), Artwork.fileUri(context, it.albumKey)) }, 0)
                        }, Modifier.weight(1f), enabled = tracks.isNotEmpty())
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            if (tracks.isEmpty()) item("empty") {
                EmptyState(Icons.Rounded.PlaylistAdd, "Nothing here yet", if (isFavorites) "Tap the heart in the player, or hold a song anywhere, to keep it here." else "Hold a song anywhere and choose Add to playlist.")
            }
            items(tracks, key = { it.id }) { track ->
                PlaylistTrackRow(track, onPlay = {
                    playback.playQueue(tracks.map { it.toPlayable(graph.levels.gainFor(it.id), Artwork.fileUri(context, it.albumKey)) }, tracks.indexOf(track).coerceAtLeast(0))
                }, onLongClick = {
                    actions?.show(track, playlistId = if (isFavorites) null else id)
                    if (!isFavorites) actions?.onRemoveFromPlaylist = { removed -> graph.playlists.remove(id, removed.id) }
                })
            }
        }
    }

    if (renaming && playlist != null) NameSheet("Rename playlist", initial = playlist.name, onDone = { name -> graph.playlists.rename(playlist.id, name); renaming = false }, onDismiss = { renaming = false })
    if (deleting && playlist != null) GlassAlert("Delete playlist?", "\"${playlist.name}\" will be removed. The songs stay in your library.", "Delete", {
        graph.playlists.delete(playlist.id); deleting = false; onBack()
    }, { deleting = false }, destructive = true)
}

const val FAVORITES = "__favorites"

@Composable
internal fun PlaylistTrackRow(track: Track, onPlay: () -> Unit, onLongClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = onPlay, onLongClick = onLongClick).padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AlbumArt(track.albumKey, Modifier.size(46.dp), corner = 10.dp, iconSize = 18.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" · "), style = IosType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(Format.duration((track.durationMs / 1000).toInt()), style = IosType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
