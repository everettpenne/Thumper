package com.everettpenne.thumper.data

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import com.everettpenne.thumper.data.model.*
import com.everettpenne.thumper.playback.LocalFiles
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

enum class DownloadStatus { ACTIVE, PAUSED, DONE, FAILED }

/** Where one download is: how far, and how it ended if it did. */
data class DownloadInfo(val status: DownloadStatus, val bytes: Long = 0, val total: Long = 0, val problem: String? = null) {
    val fraction: Float? get() = if (status == DownloadStatus.ACTIVE && total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else null
}

/** A download the person asked for, with its own copy of the episode and show so the Downloads screen needs nothing else. */
@Serializable data class DownloadRecord(val episode: Episode, val feed: Feed, val downloadId: Long, val fileName: String, val requestedAt: Long)

/**
 * Episodes saved to the phone for listening without a network. The transfer is Android's DownloadManager's (it resumes after a lost connection, follows
 * redirects, waits for Wi-Fi if asked); its own notification is hidden in favour of Thumper's one Live Update for all downloads
 * ([DownloadLiveUpdate]); this keeps track of which download is which episode, learns how each is
 * getting on, and tells the player where the file is. Downloads live in the app's own storage and go when the app is uninstalled.
 */
class Downloads(
    private val context: Context, private val dir: File, private val library: Library, private val settings: Settings, private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : LocalFiles {
    private val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val filesDir = File(dir, "files").apply { mkdirs() }
    private val recordsFile = File(dir, "downloads.json")
    private val _records = MutableStateFlow(readRecords())
    private val _state = MutableStateFlow<Map<String, DownloadInfo>>(emptyMap())
    private var poller: Job? = null

    val records: StateFlow<List<DownloadRecord>> = _records.asStateFlow()
    val state: StateFlow<Map<String, DownloadInfo>> = _state.asStateFlow()
    /** Bytes taken by finished downloads. */
    val totalBytes: Flow<Long> = _state.map { s -> s.entries.filter { it.value.status == DownloadStatus.DONE }.sumOf { it.value.bytes } }

    init {
        val done = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { scope.launch { refresh() } } }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        // Exported: the completion broadcast comes from the system's download provider, another app.
        androidx.core.content.ContextCompat.registerReceiver(context, done, filter, androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        scope.launch { refresh() }
        // Auto-download: new episodes of shows that ask for it; and "delete after playing"
        scope.launch { library.newEpisodes.collect { (feed, episodes) -> if (feed.autoDownload) episodes.take(MAX_AUTO).forEach { download(it, feed) } } }
        scope.launch { library.progress.collect { p -> if (settings.deleteWhenPlayed.value) _records.value.filter { p[it.episode.id]?.played == true }.forEach { remove(it.episode.id) } } }
    }

    // ---- asking ------------------------------------------------------------------------------------------------------------

    fun status(episodeId: String): DownloadInfo? = _state.value[episodeId]

    /** Starts downloading an episode, unless it is already downloaded or on its way. */
    fun download(episode: Episode, feed: Feed) {
        if (_records.value.any { it.episode.id == episode.id } && _state.value[episode.id]?.status != DownloadStatus.FAILED) return
        _records.value.firstOrNull { it.episode.id == episode.id }?.let { remove(it.episode.id) }            // a failed one is replaced
        val name = fileNameFor(episode)
        val wifiOnly = settings.wifiOnlyDownloads.value
        val request = DownloadManager.Request(Uri.parse(episode.audioUrl))
            .setTitle(episode.title).setDescription(feed.title).addRequestHeader("User-Agent", "Thumper/0.1 (Android podcast and feed reader)")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN).setDestinationUri(Uri.fromFile(File(filesDir, name)))
            .setAllowedOverMetered(!wifiOnly).setAllowedOverRoaming(!wifiOnly)
        val id = manager.enqueue(request)
        _records.update { it + DownloadRecord(episode.copy(summaryHtml = episode.summaryHtml.take(Library.MAX_NOTES)), feed, id, name, clock()) }
        saveRecords(); scope.launch { refresh() }
    }

    /** Cancels a download in progress, or deletes a finished one (the file goes too). */
    fun remove(episodeId: String) {
        val record = _records.value.firstOrNull { it.episode.id == episodeId } ?: return
        runCatching { manager.remove(record.downloadId) }
        File(filesDir, record.fileName).delete()
        _records.update { list -> list.filterNot { it.episode.id == episodeId } }
        _state.update { it - episodeId }
        saveRecords()
    }

    fun removeAll() { _records.value.map { it.episode.id }.forEach(::remove) }
    fun removePlayed(progress: Map<String, Progress>) { _records.value.filter { progress[it.episode.id]?.played == true }.forEach { remove(it.episode.id) } }

    /** The downloaded file for the player (null while it is still downloading, or if it never finished). */
    override fun fileFor(episodeId: String): File? {
        val record = _records.value.firstOrNull { it.episode.id == episodeId } ?: return null
        if (_state.value[episodeId]?.status != DownloadStatus.DONE) return null
        return File(filesDir, record.fileName).takeIf { it.isFile && it.length() > 0 }
    }

    // ---- learning how they are doing ---------------------------------------------------------------------------------------

    /** Asks the DownloadManager about every download we started, and keeps asking, quickly, while any is still going. */
    suspend fun refresh() {
        val records = _records.value
        val next = HashMap<String, DownloadInfo>()
        if (records.isNotEmpty()) withContext(Dispatchers.IO) {
            val byId = records.associateBy { it.downloadId }
            manager.query(DownloadManager.Query().setFilterById(*records.map { it.downloadId }.toLongArray()))?.use { c ->
                val idCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID); val statusCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                val soFar = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR); val total = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val reason = c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
                while (c.moveToNext()) {
                    val record = byId[c.getLong(idCol)] ?: continue
                    val file = File(filesDir, record.fileName)
                    next[record.episode.id] = when (c.getInt(statusCol)) {
                        DownloadManager.STATUS_SUCCESSFUL -> if (file.isFile && file.length() > 0) DownloadInfo(DownloadStatus.DONE, file.length(), file.length()) else DownloadInfo(DownloadStatus.FAILED, problem = "The file is missing.")
                        DownloadManager.STATUS_FAILED -> DownloadInfo(DownloadStatus.FAILED, problem = "Error ${c.getInt(reason)}")
                        DownloadManager.STATUS_PAUSED -> DownloadInfo(DownloadStatus.PAUSED, c.getLong(soFar), c.getLong(total).coerceAtLeast(0), "Waiting for ${if (settings.wifiOnlyDownloads.value) "Wi-Fi" else "a connection"}")
                        else -> DownloadInfo(DownloadStatus.ACTIVE, c.getLong(soFar), c.getLong(total).coerceAtLeast(0))
                    }
                }
            }
            // a record the DownloadManager no longer knows (someone cleared it): finished if the file is there, otherwise gone
            for (r in records) if (r.episode.id !in next) { val f = File(filesDir, r.fileName); next[r.episode.id] = if (f.isFile && f.length() > 0) DownloadInfo(DownloadStatus.DONE, f.length(), f.length()) else DownloadInfo(DownloadStatus.FAILED, problem = "No longer available.") }
        }
        _state.value = next
        if (next.values.any { it.status == DownloadStatus.ACTIVE || it.status == DownloadStatus.PAUSED }) keepPolling()
    }

    private fun keepPolling() {
        if (poller?.isActive == true) return
        poller = scope.launch { while (_state.value.values.any { it.status == DownloadStatus.ACTIVE || it.status == DownloadStatus.PAUSED }) { delay(800); refresh() } }
    }

    // ---- files -------------------------------------------------------------------------------------------------------------

    private fun readRecords(): List<DownloadRecord> = runCatching { ThumperJson.decodeFromString(ListSerializer(DownloadRecord.serializer()), recordsFile.readText()) }.getOrDefault(emptyList())
    private fun saveRecords() {
        scope.launch(Dispatchers.IO) {
            val tmp = File(dir, "downloads.json.tmp"); tmp.writeText(ThumperJson.encodeToString(ListSerializer(DownloadRecord.serializer()), _records.value))
            if (!tmp.renameTo(recordsFile)) { recordsFile.delete(); tmp.renameTo(recordsFile) }
        }
    }

    companion object {
        const val MAX_AUTO = 3

        /** A file name for an episode: its id (unique and safe) and the kind of file its address says it is. */
        fun fileNameFor(episode: Episode): String {
            val ext = Regex("""\.(mp3|m4a|m4b|aac|ogg|oga|opus|wav|flac|mp4)(?=$|[?#])""", RegexOption.IGNORE_CASE).find(episode.audioUrl.substringBefore('#'))?.groupValues?.get(1)?.lowercase() ?: "mp3"
            return "${episode.id}.$ext"
        }
    }
}
