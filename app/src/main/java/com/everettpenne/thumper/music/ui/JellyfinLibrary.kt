package com.everettpenne.thumper.music.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.everettpenne.glass.*
import com.everettpenne.thumper.data.model.Playable
import com.everettpenne.thumper.graph
import com.everettpenne.thumper.music.data.Artwork
import com.everettpenne.thumper.music.data.albumsOf
import com.everettpenne.thumper.music.data.jellyfin.ApiResult
import com.everettpenne.thumper.music.data.jellyfin.DownloadStatus
import com.everettpenne.thumper.music.data.jellyfin.Jellyfin
import com.everettpenne.thumper.music.data.jellyfin.JfItem
import com.everettpenne.thumper.music.data.jellyfin.PublicInfo
import com.everettpenne.thumper.music.data.jellyfin.SavedItem
import com.everettpenne.thumper.music.data.jellyfin.toTrack
import com.everettpenne.thumper.music.data.toPlayable
import com.everettpenne.thumper.playback.LocalPlayback
import com.everettpenne.thumper.ui.components.GlassTextField
import com.everettpenne.thumper.ui.components.EmptyState
import com.everettpenne.thumper.ui.components.SectionTitle
import com.everettpenne.thumper.ui.theme.Thumper
import com.everettpenne.thumper.util.Format

/**
 * The optional Jellyfin source for the Music tab. It signs in once, then shows albums, songs and downloads; everything it starts
 * goes through the same one player as the phone's music. Downloads are Jellyfin's original files and play through the same local-file
 * lookup, so a downloaded album works with the server off.
 */
@Composable
fun JellyfinLibrary(onBack: () -> Unit) {
    val context = LocalContext.current
    val jellyfin = context.graph.jellyfin
    val session by jellyfin.sessions.session.collectAsState()
    if (session == null) {
        JellyfinSignIn(onBack)
        return
    }
    JellyfinBrowse(onBack)
}

@Composable
private fun JellyfinSignIn(onBack: () -> Unit) {
    val context = LocalContext.current
    val jellyfin = context.graph.jellyfin
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<Pair<String, PublicInfo>?>(null) }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun connect() {
        if (busy || address.isBlank()) return
        busy = true; error = null
        scope.launch {
            when (val r = jellyfin.api.connect(address)) {
                is ApiResult.Ok -> found = r.value
                is ApiResult.Failed -> error = r.reason.message
            }
            busy = false
        }
    }
    fun signIn() {
        val (url, info) = found ?: return
        if (busy || user.isBlank()) return
        busy = true; error = null
        scope.launch {
            when (val r = jellyfin.api.login(url, info.serverName, user.trim(), pass)) {
                is ApiResult.Ok -> jellyfin.sessions.signIn(r.value)
                is ApiResult.Failed -> error = r.reason.message
            }
            busy = false
        }
    }

    GlassScreen(bars = {
        GlassCenteredTopBar(if (found == null) "Jellyfin" else "Sign in", onBack = {
            if (found != null) { found = null; error = null } else onBack()
        })
    }) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()
                .padding(top = padding.calculateTopPadding() + 24.dp, start = 22.dp, end = 22.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Rounded.Dns, null, tint = Thumper.accent, modifier = Modifier.size(54.dp))
            val info = found?.second
            if (info == null) {
                Text("Your Jellyfin server", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                Text("Enter its address, such as jellyfin.example.com or 192.168.1.20:8096. Thumper tries https first, then plain http for a server at home.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                GlassTextField(address, { address = it }, "Server address", leading = Icons.Rounded.Link, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, onSubmit = ::connect)
                GlassButton(if (busy) "Connecting…" else "Connect", ::connect, Modifier.fillMaxWidth(), enabled = !busy && address.isNotBlank(), prominent = true)
            } else {
                Text(info.serverName.ifBlank { "Jellyfin" }, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                Text(found!!.first + (if (info.version.isNotEmpty()) " · Jellyfin ${info.version}" else ""), style = IosType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (found!!.first.startsWith("http://")) Text(
                    "This address is not encrypted. Your password and what you play can be seen on the network between here and the server.",
                    style = MaterialTheme.typography.bodySmall, color = Thumper.red,
                )
                GlassTextField(user, { user = it }, "Username", leading = Icons.Rounded.Person, imeAction = ImeAction.Next)
                GlassTextField(pass, { pass = it }, "Password", leading = Icons.Rounded.Lock, password = true, imeAction = ImeAction.Go, onSubmit = ::signIn)
                GlassButton(if (busy) "Signing in…" else "Sign in", ::signIn, Modifier.fillMaxWidth(), enabled = !busy && user.isNotBlank(), prominent = true)
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        }
    }
}

private enum class JfTab { Albums, Songs, Downloads }

@Composable
private fun JellyfinBrowse(onBack: () -> Unit) {
    val context = LocalContext.current
    val jellyfin = context.graph.jellyfin
    val playback = LocalPlayback.current
    val scope = rememberCoroutineScope()
    val saved by jellyfin.downloads.saved.collectAsState()
    val states by jellyfin.downloads.state.collectAsState()
    var tab by remember { mutableStateOf(JfTab.Albums) }
    var library by remember { mutableStateOf<JfItem?>(null) }
    var albums by remember { mutableStateOf<List<JfItem>>(emptyList()) }
    var songs by remember { mutableStateOf<List<JfItem>>(emptyList()) }
    var selected by remember { mutableStateOf<JfItem?>(null) }
    var selectedTracks by remember { mutableStateOf<List<JfItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        loading = true; error = null
        when (val libs = jellyfin.api.musicLibraries()) {
            is ApiResult.Ok -> {
                val first = libs.value.firstOrNull()
                if (first == null) error = "No music library on this server." else {
                    library = first
                    when (val a = jellyfin.api.items(parentId = first.id, types = "MusicAlbum", limit = 300)) {
                        is ApiResult.Ok -> albums = a.value.items
                        is ApiResult.Failed -> error = a.reason.message
                    }
                    when (val s = jellyfin.api.items(parentId = first.id, types = "Audio", limit = 800)) {
                        is ApiResult.Ok -> songs = s.value.items
                        is ApiResult.Failed -> if (error == null) error = s.reason.message
                    }
                }
            }
            is ApiResult.Failed -> error = libs.reason.message
        }
        loading = false
    }
    LaunchedEffect(albums) { albums.forEach { jellyfin.ensureArt("jf:${it.id}", jellyfin.urls.poster(it, 500)) } }
    LaunchedEffect(selected) {
        val album = selected ?: return@LaunchedEffect
        loading = true; selectedTracks = emptyList()
        jellyfin.ensureArt("jf:${album.id}", jellyfin.urls.poster(album, 500))
        when (val r = jellyfin.api.albumTracks(album.id)) {
            is ApiResult.Ok -> selectedTracks = r.value
            is ApiResult.Failed -> error = r.reason.message
        }
        loading = false
    }

    fun entries(items: List<JfItem>): List<Playable.Music> = items.map { item ->
        val track = item.toTrack(jellyfin.urls)
        track.toPlayable(
            artworkUri = Artwork.fileUri(context, track.albumKey),
            artUrl = track.artUrl,
        )
    }
    fun play(items: List<JfItem>, index: Int) { if (items.isNotEmpty()) playback.playQueue(entries(items), index) }
    fun download(item: JfItem) { if (!jellyfin.requestDownload(item)) error = "The server did not allow that download." }

    GlassScreen(bars = {
        GlassCenteredTopBar(
            selected?.name ?: "Jellyfin",
            onBack = { if (selected != null) selected = null else onBack() },
            trailing = {
                Box {
                    GlassIconButton(Icons.Rounded.MoreVert, "Server", { menu = true })
                    GlassDropdownMenu(menu, { menu = false }) {
                        GlassMenuItem("Sign out", { menu = false; jellyfin.sessions.signOut() })
                    }
                }
            },
        )
    }) { padding ->
        if (selected != null) {
            JellyfinAlbumDetail(
                album = selected!!,
                tracks = selectedTracks,
                items = entries(selectedTracks),
                saved = saved,
                states = states,
                loading = loading,
                error = error,
                padding = padding,
                onPlay = { play(selectedTracks, it) },
                onDownload = { download(it) },
                onDownloadAlbum = { selectedTracks.forEach { download(it) } },
            )
            return@GlassScreen
        }
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                JfTab.entries.forEach { t ->
                    GlassButton(t.name, { tab = t }, Modifier.weight(1f), prominent = t == tab)
                }
            }
            if (loading && albums.isEmpty() && songs.isEmpty()) Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) { GlassProgressBar(null) }
            error?.let { Text(it, style = IosType.footnote, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
            when (tab) {
                JfTab.Albums -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = contentBottomPadding() + 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(albums.chunked(2), key = { row -> row.joinToString { it.id } }) { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { album ->
                                Column(Modifier.weight(1f).clickable { selected = album }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    AlbumArt("jf:${album.id}", Modifier.fillMaxWidth().aspectRatio(1f), corner = 14.dp, iconSize = 30.dp)
                                    Text(album.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(album.musicArtist.orEmpty(), style = IosType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    if (albums.isEmpty() && !loading) item { EmptyState(Icons.Rounded.Album, "No albums", "This server's music library is empty.") }
                }
                JfTab.Songs -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = contentBottomPadding() + 8.dp),
                ) {
                    items(songs, key = { it.id }) { song ->
                        JfSongRow(song, saved, states, onPlay = { play(songs, songs.indexOf(song)) }, onDownload = { download(song) })
                    }
                    if (songs.isEmpty() && !loading) item { EmptyState(Icons.Rounded.MusicNote, "No songs", "This server's music library is empty.") }
                }
                JfTab.Downloads -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = contentBottomPadding() + 8.dp),
                ) {
                    items(saved, key = { it.item.id }) { savedItem ->
                        val state = states[savedItem.item.id]
                        val done = state?.status == DownloadStatus.DONE
                        JfDownloadRow(savedItem, state, done, onPlay = {
                            val track = savedItem.toTrack(jellyfin.downloads, jellyfin.urls)
                            if (track != null) playback.playQueue(listOf(track.toPlayable(artworkUri = Artwork.fileUri(context, track.albumKey), artUrl = track.artUrl)), 0)
                        }, onDelete = { jellyfin.downloads.remove(savedItem.item.id) })
                    }
                    if (saved.isEmpty()) item { EmptyState(Icons.Rounded.Download, "No downloads", "Download an album to listen with the server off.") }
                }
            }
        }
    }
}

@Composable
private fun JellyfinAlbumDetail(
    album: JfItem,
    tracks: List<JfItem>,
    items: List<Playable.Music>,
    saved: List<SavedItem>,
    states: Map<String, com.everettpenne.thumper.music.data.jellyfin.DownloadInfo>,
    loading: Boolean,
    error: String?,
    padding: PaddingValues,
    onPlay: (Int) -> Unit,
    onDownload: (JfItem) -> Unit,
    onDownloadAlbum: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = padding.calculateTopPadding() + 6.dp, bottom = contentBottomPadding() + 8.dp),
    ) {
        item("header") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AlbumArt("jf:${album.id}", Modifier.fillMaxWidth(0.62f).aspectRatio(1f), corner = 18.dp, iconSize = 40.dp)
                Spacer(Modifier.height(16.dp))
                Text(album.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(album.musicArtist.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                Text(listOfNotNull(album.year?.toString(), "${tracks.size} songs").joinToString(" · "), style = IosType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassButton("Play", { onPlay(0) }, Modifier.weight(1f), prominent = true, enabled = tracks.isNotEmpty())
                    GlassButton("Download", { onDownloadAlbum() }, Modifier.weight(1f), enabled = tracks.isNotEmpty())
                }
                Spacer(Modifier.height(10.dp))
            }
        }
        if (loading) item("loading") { Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { GlassProgressBar(null) } }
        error?.let { item("error") { Text(it, style = IosType.footnote, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp)) } }
        itemsIndexedJf(tracks, items, saved, states, onPlay, onDownload)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexedJf(
    tracks: List<JfItem>,
    items: List<Playable.Music>,
    saved: List<SavedItem>,
    states: Map<String, com.everettpenne.thumper.music.data.jellyfin.DownloadInfo>,
    onPlay: (Int) -> Unit,
    onDownload: (JfItem) -> Unit,
) {
    items(tracks.size, key = { tracks[it].id }) { index ->
        val track = tracks[index]
        JfSongRow(track, saved, states, number = track.index ?: (index + 1), onPlay = { onPlay(index) }, onDownload = { onDownload(track) })
    }
}

@Composable
private fun JfSongRow(
    song: JfItem,
    saved: List<SavedItem>,
    states: Map<String, com.everettpenne.thumper.music.data.jellyfin.DownloadInfo>,
    number: Int? = null,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
) {
    val state = states[song.id]
    val done = state?.status == DownloadStatus.DONE
    Row(Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (number != null) Text("$number", style = IosType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(18.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(song.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.musicArtist ?: song.album.orEmpty(), style = IosType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(Format.duration(song.runtimeSeconds?.toInt()), style = IosType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            done || saved.any { it.item.id == song.id } -> Icon(Icons.Rounded.DownloadDone, "Downloaded", tint = Thumper.accent, modifier = Modifier.size(22.dp))
            state?.status == DownloadStatus.ACTIVE -> Text("${((state.fraction ?: 0f) * 100).toInt()}%", style = IosType.caption1, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> Icon(Icons.Rounded.Download, "Download", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp).clickable(onClick = onDownload))
        }
    }
}

@Composable
private fun JfDownloadRow(savedItem: SavedItem, state: com.everettpenne.thumper.music.data.jellyfin.DownloadInfo?, done: Boolean, onPlay: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AlbumArt("jf:${savedItem.item.albumId ?: savedItem.item.id}", Modifier.size(46.dp), corner = 10.dp, iconSize = 18.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(savedItem.item.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(savedItem.item.musicArtist ?: savedItem.item.album.orEmpty(), style = IosType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                state?.problem != null -> Text(state.problem, style = IosType.caption1, color = MaterialTheme.colorScheme.error)
                state?.status == DownloadStatus.ACTIVE -> Text("Downloading…", style = IosType.caption1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                done -> Text("Downloaded", style = IosType.caption1, color = Thumper.accent)
            }
        }
        if (done) GlassIconButton(Icons.Rounded.PlayArrow, "Play", onPlay)
        GlassIconButton(Icons.Rounded.DeleteOutline, "Delete", onDelete)
    }
}
