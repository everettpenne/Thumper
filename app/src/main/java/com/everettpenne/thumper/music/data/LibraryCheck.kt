package com.everettpenne.thumper.music.data

import android.content.Context
import android.net.Uri
import com.everettpenne.thumper.music.tags.Aiff
import com.everettpenne.thumper.music.util.isAiffFileName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** What a library check found; all lists are empty when the library is healthy. */
data class LibraryReport(
    val missingFiles: List<Track> = emptyList(),
    val unplayable: List<Pair<Track, String>> = emptyList(),
    val duplicates: List<List<Track>> = emptyList(),
    val withoutArt: List<Album> = emptyList(),
    val withoutTags: List<Track> = emptyList(),
    val zeroLength: List<Track> = emptyList(),
) {
    val issueCount: Int
        get() = missingFiles.size + unplayable.size + duplicates.sumOf { it.size - 1 } + withoutArt.size +
            withoutTags.size + zeroLength.size

    val clean: Boolean get() = issueCount == 0
}

data class LibraryCheckState(
    val running: Boolean = false,
    val checked: Int = 0,
    val total: Int = 0,
    val report: LibraryReport? = null,
    val error: String? = null,
)

/**
 * A read-only audit of the saved library: files that vanished, formats Android cannot decode, duplicate songs, albums without a
 * cover, and tracks that only have a file name to go on. It never deletes or edits anything, and it is only started when asked.
 */
class MusicLibraryCheck(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(LibraryCheckState())
    val state: StateFlow<LibraryCheckState> = _state.asStateFlow()
    private var job: Job? = null

    fun run(tracks: List<Track>) {
        if (job?.isActive == true) return
        val albums = albumsOf(tracks)
        val total = tracks.size + albums.size
        job = scope.launch {
            _state.value = LibraryCheckState(running = true, total = total)
            try {
                val missing = ArrayList<Track>()
                val unplayable = ArrayList<Pair<Track, String>>()
                val withoutTags = ArrayList<Track>()
                val zeroLength = ArrayList<Track>()
                tracks.forEachIndexed { index, track ->
                    if (!isActive) return@launch
                    if (track.id.startsWith("d:") && !exists(track)) {
                        missing += track
                    } else {
                        unplayableReason(track)?.let { unplayable += track to it }
                    }
                    if (track.durationMs <= 0L) zeroLength += track
                    if (track.title.isBlank() || track.artist == UNKNOWN_ARTIST || track.album == UNKNOWN_ALBUM) withoutTags += track
                    if (index % 10 == 0) _state.update { it.copy(checked = index + 1) }
                }
                val duplicates = tracks
                    .filter { it.durationMs > 0L }
                    .groupBy { "${it.title.trim().lowercase()}|${it.artist.trim().lowercase()}|${it.durationMs / 2000}" }
                    .values.filter { it.size > 1 }
                val withoutArt = albums.filterNot { Artwork.has(context, it.key) }
                _state.value = LibraryCheckState(
                    running = false,
                    checked = total,
                    total = total,
                    report = LibraryReport(
                        missingFiles = missing,
                        unplayable = unplayable,
                        duplicates = duplicates,
                        withoutArt = withoutArt,
                        withoutTags = withoutTags,
                        zeroLength = zeroLength,
                    ),
                )
            } catch (error: Exception) {
                _state.value = LibraryCheckState(error = error.message ?: "The check could not finish.")
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.update { it.copy(running = false) }
    }

    private fun exists(track: Track): Boolean {
        val uri = Uri.parse(track.uri)
        return when (uri.scheme) {
            "file" -> uri.path?.let { File(it).isFile } == true
            "content" -> runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
            }.getOrDefault(false)
            else -> true
        }
    }

    private fun unplayableReason(track: Track): String? = runCatching {
        val name = track.fileName.ifBlank { Uri.parse(track.uri).lastPathSegment.orEmpty() }
        val extension = name.substringAfterLast('.', "").lowercase()
        when (extension) {
            "m4p" -> "FairPlay DRM (.m4p) cannot be decoded"
            "wma", "ape", "mpc", "dsf", "dff", "wv", "tta" -> ".$extension is not playable on Android"
            "aif", "aiff", "aifc" -> {
                val info = Aiff.read(context, Uri.parse(track.uri)) ?: return "The AIFF header could not be read"
                val compression = info.compression.uppercase()
                if (compression !in setOf("NONE", "TWOS", "SOWT", "FL32")) {
                    "AIFF compression $compression is not supported"
                } else {
                    null
                }
            }
            else -> null
        }
    }.getOrNull()
}
