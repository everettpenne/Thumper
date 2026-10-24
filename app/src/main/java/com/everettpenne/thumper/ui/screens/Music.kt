package com.everettpenne.thumper.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.core.content.ContextCompat
import com.everettpenne.glass.*
import com.everettpenne.thumper.data.model.Playable
import com.everettpenne.thumper.graph
import com.everettpenne.thumper.music.data.Album
import com.everettpenne.thumper.music.data.Track
import com.everettpenne.thumper.music.data.albumsOf
import com.everettpenne.thumper.music.data.toPlayable
import com.everettpenne.thumper.music.ui.AlbumArt
import com.everettpenne.thumper.music.ui.FAVORITES
import com.everettpenne.thumper.music.ui.LocalTrackActions
import com.everettpenne.thumper.music.ui.MusicPlaylistScreen
import com.everettpenne.thumper.music.ui.MusicPlaylistsScreen
import com.everettpenne.thumper.music.ui.TrackActionSheet
import com.everettpenne.thumper.music.ui.TrackActionsState
import com.everettpenne.thumper.playback.LocalPlayback
import com.everettpenne.thumper.ui.components.EmptyState
import com.everettpenne.thumper.ui.components.SectionTitle
import com.everettpenne.thumper.ui.theme.Thumper
import com.everettpenne.thumper.util.Format

/**
 * The music library: albums and songs from the phone, and the place a server library will join them. It is a browser; playback
 * is the one Thumper player from Listen, with the same mini player and Now Playing screen.
 */
@Composable
fun MusicScreen() {
    val actions = remember { TrackActionsState() }
    CompositionLocalProvider(LocalTrackActions provides actions) {
        MusicContent()
        TrackActionSheet(actions)
    }
}

@Composable
private fun MusicContent() {
    val context = LocalContext.current
    val graph = context.graph
    val playback = LocalPlayback.current
    val snapshot by graph.music.snapshot.collectAsState()
    val scan by graph.music.scan.collectAsState()
    val albums = remember(snapshot.tracks) { albumsOf(snapshot.tracks) }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    var showJellyfin by remember { mutableStateOf(false) }
    var showPlaylists by remember { mutableStateOf(false) }
    var openPlaylist by remember { mutableStateOf<String?>(null) }
    if (showJellyfin) { com.everettpenne.thumper.music.ui.JellyfinLibrary(onBack = { showJellyfin = false }); return }
    if (showPlaylists) {
        MusicPlaylistsScreen(onBack = { showPlaylists = false }, onOpen = { openPlaylist = it; showPlaylists = false }, onFavorites = { openPlaylist = FAVORITES; showPlaylists = false })
        return
    }
    openPlaylist?.let { id -> MusicPlaylistScreen(id, onBack = { openPlaylist = null }); return }
    val selected = albums.firstOrNull { it.key == selectedKey }

    val chooseFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        graph.music.scanFolder(uri.toString())
    }
    val askAudio = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) graph.music.scanMedia()
    }
    fun usePhoneMusic() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askAudio.launch(Manifest.permission.READ_MEDIA_AUDIO)
        } else graph.music.scanMedia()
    }

    GlassScreen(bars = {
        if (selected != null) GlassCenteredTopBar(selected.title, onBack = { selectedKey = null }, subtitle = selected.artist)
        else GlassTopBar(
            "Music",
            subtitle = when {
                scan.running -> scan.phase.ifBlank { "Scanning…" }
                snapshot.tracks.isEmpty() -> "Nothing here yet"
                else -> "${albums.size} album" + (if (albums.size == 1) "" else "s") + " · ${snapshot.tracks.size} songs"
            },
            actions = {
                GlassIconButton(Icons.Rounded.QueueMusic, "Playlists and favourites", { showPlaylists = true })
                GlassIconButton(Icons.Rounded.Dns, "Jellyfin server", { showJellyfin = true })
                GlassIconButton(Icons.Rounded.CreateNewFolder, "Choose a music folder", { chooseFolder.launch(null) })
                GlassIconButton(Icons.Rounded.Refresh, "Scan again", {
                    val folder = graph.musicSettings.folder.value
                    if (folder != null) graph.music.scanFolder(folder) else usePhoneMusic()
                })
            },
        )
    }) { padding ->
        val top = padding.calculateTopPadding()
        val bottom = contentBottomPadding() + 8.dp
        if (selected != null) {
            MusicAlbum(selected, top, bottom, onPlay = { index ->
                val entries = selected.tracks.map { it.toPlayable(graph.levels.gainFor(it.id), com.everettpenne.thumper.music.data.Artwork.fileUri(context, it.albumKey)) }
                playback.playQueue(entries, index)
            })
            return@GlassScreen
        }
        if (snapshot.tracks.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    EmptyState(
                        Icons.Rounded.LibraryMusic,
                        "Bring your music in",
                        "Use the songs already on this phone, or choose the folder your library lives in. Nothing is uploaded.",
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassButton("Phone music", ::usePhoneMusic, prominent = true)
                        GlassButton("Choose folder", { chooseFolder.launch(null) })
                    }
                }
            }
            return@GlassScreen
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = top + 6.dp, bottom = bottom, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (scan.running) item("scan") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(scan.phase.ifBlank { "Scanning…" }, style = IosType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    GlassProgressBar(if (scan.total > 0) scan.done.toFloat() / scan.total else null)
                }
            }
            scan.error?.let { message -> item("error") { Text(message, style = IosType.footnote, color = Thumper.red) } }
            item("albums-title") { SectionTitle("Albums") }
            items(albums.chunked(2), key = { row -> row.joinToString { it.key } }) { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { album -> AlbumCard(album, Modifier.weight(1f)) { selectedKey = album.key } }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            item("songs-title") { SectionTitle("Songs") }
            items(snapshot.tracks.take(100), key = { it.id }) { track ->
                MusicRow(track, playing = playback.episodeId == "music:${track.id}") {
                    val entries = snapshot.tracks.map { it.toPlayable(graph.levels.gainFor(it.id), com.everettpenne.thumper.music.data.Artwork.fileUri(context, it.albumKey)) }
                    playback.playQueue(entries, snapshot.tracks.indexOf(track).coerceAtLeast(0))
                }
            }
        }
    }
}

@Composable
private fun AlbumCard(album: Album, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AlbumArt(album.key, Modifier.fillMaxWidth().aspectRatio(1f), corner = 14.dp, iconSize = 30.dp)
        Text(album.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(album.artist, style = IosType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MusicAlbum(album: Album, top: androidx.compose.ui.unit.Dp, bottom: androidx.compose.ui.unit.Dp, onPlay: (Int) -> Unit) {
    val playback = LocalPlayback.current
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = top + 6.dp, bottom = bottom, start = 20.dp, end = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item("header") {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AlbumArt(album.key, Modifier.fillMaxWidth(0.62f).aspectRatio(1f), corner = 18.dp, iconSize = 40.dp)
                Spacer(Modifier.height(10.dp))
                Text(album.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(album.artist, style = MaterialTheme.typography.bodyLarge, color = Thumper.accent)
                Text(listOfNotNull(album.year.takeIf { it > 0 }?.toString(), Format.duration((album.durationMs / 1000).toInt())).joinToString(" · "),
                    style = IosType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                GlassButton("Play", { onPlay(0) }, Modifier.fillMaxWidth(), prominent = true)
                Spacer(Modifier.height(14.dp))
            }
        }
        items(album.tracks, key = { it.id }) { track ->
            val index = album.tracks.indexOf(track)
            MusicRow(track, playing = playback.episodeId == "music:${track.id}", number = track.trackNo.takeIf { it > 0 } ?: (index + 1)) { onPlay(index) }
        }
    }
}

@Composable
private fun MusicRow(track: Track, playing: Boolean, number: Int? = null, onClick: () -> Unit) {
    val actions = LocalTrackActions.current
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = actions?.let { state -> { state.show(track) } }).padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (number != null) Text("$number", style = IosType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(18.dp))
        else AlbumArt(track.albumKey, Modifier.size(46.dp), corner = 10.dp, iconSize = 18.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(track.title, style = MaterialTheme.typography.titleSmall, color = if (playing) Thumper.accent else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" · "), style = IosType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(Format.duration((track.durationMs / 1000).toInt()), style = IosType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
