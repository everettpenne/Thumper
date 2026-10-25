package com.everettpenne.thumper.music.data

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import com.everettpenne.thumper.music.util.guessName
import com.everettpenne.thumper.music.util.isAiffFileName
import com.everettpenne.thumper.music.util.isCoverFileName
import java.io.File

/**
 * The music on this phone, found either in Android's media index (one narrow permission, tags read for free) or in a folder the person
 * granted through the system picker (no permission at all). The result is saved as JSON, so opening the app again is instant; a rescan
 * replaces it.
 *
 * Scanning never touches the network and never writes outside the app's own storage. Artwork is extracted once per album during the
 * scan, which is the slow but polite part: the lists then scroll without reading files.
 */
class MusicLibrary(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val snapshotFile = File(context.filesDir, "library.json")
    private val _snapshot = MutableStateFlow(LibrarySnapshot())
    val snapshot: StateFlow<LibrarySnapshot> = _snapshot.asStateFlow()
    private val _scan = MutableStateFlow(ScanState())
    val scan: StateFlow<ScanState> = _scan.asStateFlow()

    private var byId: Map<String, Track> = emptyMap()
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val artMutex = Mutex()

    init { load() }

    private fun load() {
        if (!snapshotFile.isFile) return
        runCatching { json.decodeFromString<LibrarySnapshot>(snapshotFile.readText()) }
            .onSuccess { snapshot ->
                setSnapshot(snapshot)
                // A cover can be missing after an update changed the album key, or because a scan's extraction failed for the first
                // track. Repair it in the background: adopt an older cached cover, then read the files again.
                if (snapshot.tracks.isNotEmpty()) scope.launch { ensureArtwork(snapshot.tracks) }
            }
    }

    private fun setSnapshot(snapshot: LibrarySnapshot) {
        byId = snapshot.tracks.associateBy { it.id }
        _snapshot.value = snapshot
    }

    fun find(id: String?): Track? = id?.let { byId[it] }

    /** A one-off track for an audio file opened from another app (Sietch, a download): tags read now, artwork cached for the player. */
    suspend fun describe(uri: Uri): Track? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        // Only an audio file: a folder handed over by mistake (a directory link, a document tree) is not playable.
        val mime = runCatching { resolver.getType(uri) }.getOrNull()
        if (mime != null && !mime.startsWith("audio/")) return@withContext null
        val name = runCatching {
            resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "Audio"
        val track = readTags(uri, name, System.currentTimeMillis())
        if (track.durationMs <= 0L && track.title.isBlank()) return@withContext null
        runCatching { Artwork.extract(context, track.albumKey, track.uri, fileName = track.fileName, mime = track.mime) }
        track
    }
    fun tracks(ids: Collection<String>): List<Track> = ids.mapNotNull { byId[it] }
    val tracks: List<Track> get() = _snapshot.value.tracks

    fun scanMedia() = scan(source = LibrarySnapshot.SOURCE_MEDIA) { queryMediaStore() }

    /** The music permission was declined: the scan reports it, with the way that needs no permission. */
    fun permissionDeclined() {
        _scan.value = ScanState(error = "Thumper needs permission to read your music. Allow Music and audio in App info, or choose a folder instead.")
    }
    fun scanFolder(treeUri: String) = scan(source = LibrarySnapshot.SOURCE_FOLDER) {
        // A granted tree is a content:// address; a plain path (the app's own music folder, or a test) is walked directly.
        if (treeUri.startsWith("/")) queryDirectory(File(treeUri)) else queryTree(treeUri)
    }

    private fun scan(source: String, finder: suspend (progress: (Int) -> Unit) -> List<Track>) {
        if (_scan.value.running) return
        _scan.value = ScanState(running = true, phase = if (source == LibrarySnapshot.SOURCE_MEDIA) "Reading your music" else "Looking through the folder")
        scope.launch {
            try {
                val found = finder { count -> _scan.update { it.copy(found = count) } }
                _scan.update { it.copy(phase = "Preparing artwork", found = found.size, done = 0, total = 0) }
                ensureArtwork(found) { done, total -> _scan.update { it.copy(done = done, total = total) } }
                val snapshot = LibrarySnapshot(source = source, scannedAt = System.currentTimeMillis(), tracks = found)
                setSnapshot(snapshot)
                persist(snapshot)
                _scan.value = ScanState(found = found.size)
            } catch (error: SecurityException) {
                _scan.value = ScanState(error = if (source == LibrarySnapshot.SOURCE_FOLDER) "Thumper no longer has access to that folder. Choose it again in Settings." else "Thumper needs permission to read your music.")
            } catch (error: Exception) {
                _scan.value = ScanState(error = error.message ?: "The library could not be read.")
            }
        }
    }

    /** Makes sure every album has a cover: adopt one cached under an old key, then read the folder/embedded/AIFF art. */
    private suspend fun ensureArtwork(tracks: List<Track>, onProgress: ((Int, Int) -> Unit)? = null) {
        val albums = albumsOf(tracks)
        artMutex.withLock {
            albums.forEachIndexed { index, album ->
                ensureArt(album)
                onProgress?.invoke(index + 1, albums.size)
            }
        }
    }

    private suspend fun ensureArt(album: Album) {
        if (Artwork.has(context, album.key)) return
        val legacy = album.tracks.flatMap { legacyArtKeys(it) }.distinct()
        if (Artwork.adopt(context, album.key, legacy)) return
        // A merged album's first track may have no art while a later one does, so try every track before giving up.
        for (track in album.tracks) {
            if (Artwork.extract(context, album.key, track.uri, track.coverUri, track.fileName, track.mime)) return
        }
    }

    private suspend fun persist(snapshot: LibrarySnapshot) = withContext(Dispatchers.IO) {
        runCatching {
            val temporary = File(snapshotFile.parentFile, snapshotFile.name + ".tmp")
            temporary.writeText(json.encodeToString(snapshot))
            if (!temporary.renameTo(snapshotFile)) { snapshotFile.delete(); temporary.renameTo(snapshotFile) }
        }
    }

    /** Re-reads any cover that is missing, adopting older cached art first. */
    fun repairArtwork() {
        val current = tracks
        if (current.isEmpty()) return
        scope.launch { ensureArtwork(current) }
    }

    /** Forgets the saved library and its artwork; the files themselves are untouched. */
    fun clear() {
        setSnapshot(LibrarySnapshot())
        Artwork.clear(context)
        runCatching { snapshotFile.delete() }
        _scan.value = ScanState()
    }

    // ---- Android's media index ---------------------------------------------------------------------------------------------

    private fun queryMediaStore(): List<Track> {
        // The audio view is the obvious place, but a phone whose media index has not finished with a file can hide it there. The files
        // table with media_type = 2 is the same rows seen directly, and is the fallback when the first query comes back empty. Ringtones,
        // alarms, notification sounds and voice recordings are audio but not music, so neither query lists them.
        val notMusic = "${MediaStore.Audio.Media.IS_RINGTONE} = 0 AND ${MediaStore.Audio.Media.IS_ALARM} = 0 AND " +
            "${MediaStore.Audio.Media.IS_NOTIFICATION} = 0 AND ${MediaStore.Audio.Media.IS_RECORDING} = 0"
        return queryAudio(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), notMusic).ifEmpty {
            queryAudio(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO} AND $notMusic")
        }
    }

    private fun queryAudio(collection: Uri, selection: String?): List<Track> {
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.MediaColumns.ALBUM_ARTIST,
            MediaStore.MediaColumns.DISC_NUMBER,
            MediaStore.MediaColumns.GENRE,
        )
        // Every audio row Android has. Tags are taken from the index when it has them; a row the index has not finished reading
        // (or a file it never tagged) is read from the file itself, so the library is never empty just because the index is thin.
        val order = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        val out = ArrayList<Track>()
        resolver.query(collection, projection, selection, null, order)?.use { cursor ->
            val total = cursor.count
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val fileName = cursor.getString(10).orEmpty()
                val uri = ContentUris.withAppendedId(collection, id)
                var title = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: ""
                var artist = cursor.getString(2)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: UNKNOWN_ARTIST
                var album = cursor.getString(3)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: UNKNOWN_ALBUM
                var duration = cursor.getLong(5)
                var trackNo = cursor.getString(6)?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0
                var year = cursor.getInt(7)
                var albumArtist = cursor.getString(11)?.takeIf { it.isNotBlank() && it != "<unknown>" }
                var discNo = cursor.getString(12)?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0
                var genre = cursor.getString(13)?.takeIf { it.isNotBlank() && it != "<unknown>" }
                if (duration <= 0L || title.isBlank() || artist == UNKNOWN_ARTIST || album == UNKNOWN_ALBUM) {
                    val tags = readFileTags(uri, fileName)
                    if (title.isBlank()) title = tags.title
                    if (artist == UNKNOWN_ARTIST) artist = tags.artist
                    if (album == UNKNOWN_ALBUM) album = tags.album
                    if (duration <= 0L) duration = tags.durationMs
                    if (trackNo == 0) trackNo = tags.trackNo
                    if (year == 0) year = tags.year
                    if (albumArtist == null) albumArtist = tags.albumArtist
                    if (discNo == 0) discNo = tags.discNo
                    if (genre == null) genre = tags.genre
                }
                // A notification or ringtone is not music; a track Android has not measured yet is kept and measured on play.
                if (duration in 1..9_999) continue
                out += Track(
                    id = "m:$id",
                    uri = uri.toString(),
                    title = title.ifBlank { guessName(fileName).title },
                    artist = artist,
                    album = album,
                    albumId = cursor.getLong(4),
                    albumArtist = albumArtist,
                    discNo = discNo,
                    genre = genre,
                    durationMs = duration,
                    trackNo = trackNo,
                    year = year,
                    addedAt = cursor.getLong(8) * 1000L,
                    mime = cursor.getString(9),
                    fileName = fileName,
                )
                if (out.size % 40 == 0) _scan.update { it.copy(found = out.size, done = out.size, total = total) }
            }
        }
        return out
    }

    /** Title, artist, album, length and numbers read out of the file itself, for a row the media index did not fill in. */
    private data class FileTags(
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val trackNo: Int,
        val year: Int,
        val albumArtist: String? = null,
        val discNo: Int = 0,
        val genre: String? = null,
    )

    private fun readFileTags(uri: Uri, fileName: String): FileTags {
        val guess = guessName(fileName)
        // AIFF is not supported by Android's tag reader; the file's own COMM and ID3 chunks are read instead.
        if (isAiffFileName(fileName)) {
            com.everettpenne.thumper.music.tags.Aiff.read(context, uri)?.let { aiff ->
                return FileTags(
                    title = aiff.tags.title ?: guess.title,
                    artist = aiff.tags.artist ?: guess.artist,
                    album = aiff.tags.album ?: UNKNOWN_ALBUM,
                    durationMs = aiff.durationMs,
                    trackNo = aiff.tags.track ?: 0,
                    year = aiff.tags.year ?: 0,
                    albumArtist = aiff.tags.albumArtist,
                    discNo = aiff.tags.disc ?: 0,
                    genre = aiff.tags.genre,
                )
            }
        }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            FileTags(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: guess.title,
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() } ?: guess.artist,
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() } ?: UNKNOWN_ALBUM,
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                trackNo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0,
                year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.take(4)?.toIntOrNull() ?: 0,
                albumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)?.takeIf { it.isNotBlank() },
                discNo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0,
                genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)?.takeIf { it.isNotBlank() },
            )
        } catch (_: Exception) {
            FileTags(guess.title, guess.artist, UNKNOWN_ALBUM, 0L, 0, 0)
        } finally {
            runCatching { retriever.release() }
        }
    }

    // ---- A folder the person granted ---------------------------------------------------------------------------------------

    private data class Child(val id: String, val name: String, val mime: String?, val modified: Long)

    private fun queryTree(treeUri: String): List<Track> {
        val resolver = context.contentResolver
        val tree = Uri.parse(treeUri)
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val folders = ArrayDeque<String>().apply { add(rootId) }
        val out = ArrayList<Track>()
        var visited = 0
        while (folders.isNotEmpty() && visited < MAX_FOLDERS && out.size < MAX_TRACKS) {
            val folderId = folders.removeFirst()
            visited++
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId)
            runCatching {
                resolver.query(children, projection, null, null, null)?.use { cursor ->
                    val entries = ArrayList<Child>()
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0) ?: continue
                        val name = cursor.getString(1) ?: id
                        val mime = cursor.getString(2)
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) folders += id
                        else entries += Child(id, name, mime, cursor.getLong(3))
                    }
                    // One cover image in the folder is used for every track in it, without reading each file's tags first.
                    val cover = entries.firstOrNull { isCoverFileName(it.name) && it.mime?.startsWith("image/") != false }
                    val coverUri = cover?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it.id).toString() }
                    for (entry in entries) {
                        if (out.size >= MAX_TRACKS) break
                        if (!isAudio(entry.name, entry.mime)) continue
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, entry.id)
                        out += readTags(uri, entry.name, entry.modified, coverUri, folderId)
                        _scan.update { it.copy(found = out.size, done = out.size, total = 0) }
                    }
                }
            }.onFailure { if (visited == 1) throw it }
        }
        return out
    }

    private fun queryDirectory(dir: File): List<Track> {
        val out = ArrayList<Track>()
        val folders = ArrayDeque<File>().apply { add(dir) }
        while (folders.isNotEmpty() && out.size < MAX_TRACKS) {
            val folder = folders.removeFirst()
            val children = folder.listFiles() ?: continue
            val cover = children.firstOrNull { it.isFile && isCoverFileName(it.name) }?.let { Uri.fromFile(it).toString() }
            for (child in children) {
                if (child.isDirectory) folders += child
                else if (isAudio(child.name, null)) {
                    out += readTags(Uri.fromFile(child), child.name, child.lastModified(), cover, folder.absolutePath)
                    _scan.update { it.copy(found = out.size) }
                }
            }
        }
        return out
    }

    private fun isAudio(name: String, mime: String?): Boolean =
        mime?.startsWith("audio/") == true || name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS

    private fun readTags(uri: Uri, fileName: String, modified: Long, coverUri: String? = null, folder: String = ""): Track {
        val tags = readFileTags(uri, fileName)
        return Track(
            id = "d:${uri}",
            uri = uri.toString(),
            title = tags.title,
            artist = tags.artist,
            album = tags.album,
            albumArtist = tags.albumArtist,
            discNo = tags.discNo,
            genre = tags.genre,
            folder = folder,
            durationMs = tags.durationMs,
            trackNo = tags.trackNo,
            year = tags.year,
            addedAt = modified,
            mime = context.contentResolver.getType(uri),
            fileName = fileName,
            coverUri = coverUri,
        )
    }

    private companion object {
        const val MAX_FOLDERS = 4_000
        const val MAX_TRACKS = 20_000
        val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "m4b", "aac", "flac", "ogg", "oga", "opus", "wav", "wave", "wma", "aiff", "aif", "aifc", "alac", "mpc", "ape")
    }
}
