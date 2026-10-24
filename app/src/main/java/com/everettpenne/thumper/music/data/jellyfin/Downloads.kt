package com.everettpenne.thumper.music.data.jellyfin

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

enum class DownloadStatus { ACTIVE, PAUSED, DONE, FAILED }

/** Where one download is: how far, and how it ended if it did. */
data class DownloadInfo(val status: DownloadStatus, val bytes: Long = 0, val total: Long = 0, val problem: String? = null) {
    val fraction: Float? get() = if (status == DownloadStatus.ACTIVE && total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else null
}

/**
 * A song saved to the phone, with its own copy of the item (so it can be shown and played with no server) and of its poster.
 * [posterFile] is null until the poster has been fetched, or when the item has none.
 */
@Serializable
data class SavedItem(
    val item: JfItem,
    val downloadId: Long = 0,
    val fileName: String,
    val posterFile: String? = null,
    val requestedAt: Long = 0,
)

/**
 * Music saved for listening without the server. The file is the original from Jellyfin's `Items/{id}/Download`, fetched through the
 * same authenticated OkHttp client as the stream, so it works with the same headers and the same cleartext policy. Progress is in
 * memory and on disk; an interrupted download can be retried from the Downloads list.
 *
 * Downloads run while the app process is alive. The UI keeps the progress visible; a failed transfer says why and can be retried.
 */
class Downloads(
    private val context: Context,
    private val dir: File,
    private val http: OkHttpClient,
    private val auth: () -> String?,
    private val posterUrl: (JfItem) -> String?,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val filesDir = File(dir, "files").apply { mkdirs() }
    private val recordsFile = File(dir, "downloads.json")
    private val _saved = MutableStateFlow(readRecords())
    private val _state = MutableStateFlow<Map<String, DownloadInfo>>(emptyMap())
    private val jobs = mutableMapOf<String, Job>()

    val saved: StateFlow<List<SavedItem>> = _saved.asStateFlow()
    val state: StateFlow<Map<String, DownloadInfo>> = _state.asStateFlow()

    init {
        // A file that is already there is a finished download; a record without one is incomplete.
        _state.value = _saved.value.associate { record ->
            val file = File(filesDir, record.fileName)
            record.item.id to if (file.isFile && file.length() > 0L) {
                DownloadInfo(DownloadStatus.DONE, file.length(), file.length())
            } else {
                DownloadInfo(DownloadStatus.FAILED, problem = "The download did not finish. Tap to retry.")
            }
        }
        _saved.value.filter { it.posterFile == null }.forEach { record -> scope.launch { fetchPoster(record) } }
    }

    /** Starts saving [item], unless it is saved or already on its way. Returns false when there is no session or download URL. */
    fun download(item: JfItem, serverDownloadUrl: String): Boolean {
        val existing = _saved.value.firstOrNull { it.item.id == item.id }
        if (existing != null && _state.value[item.id]?.status != DownloadStatus.FAILED) return true
        existing?.let { remove(it.item.id) }
        val token = auth() ?: return false
        val name = fileNameFor(item)
        val record = SavedItem(item.copy(people = emptyList()), fileName = name, requestedAt = clock())
        _saved.update { listOf(record) + it }
        _state.update { it + (item.id to DownloadInfo(DownloadStatus.ACTIVE)) }
        saveRecords()
        jobs[item.id] = scope.launch {
            try {
                withContext(Dispatchers.IO) { fetch(record, serverDownloadUrl, token) }
                fetchPoster(record)
            } catch (cancelled: CancellationException) {
                _state.update { it + (item.id to DownloadInfo(DownloadStatus.FAILED, problem = "Download cancelled.")) }
                throw cancelled
            } catch (error: Exception) {
                _state.update {
                    it + (item.id to DownloadInfo(DownloadStatus.FAILED, problem = error.message ?: "The download failed."))
                }
            } finally {
                jobs.remove(item.id)
            }
        }
        return true
    }

    private fun fetch(record: SavedItem, url: String, token: String) {
        val itemId = record.item.id
        val target = File(filesDir, record.fileName)
        val temporary = File(filesDir, record.fileName + ".part")
        val request = Request.Builder().url(url).header("Authorization", token).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("The server answered ${response.code}.")
            val body = response.body ?: throw IOException("The server sent no file.")
            val total = body.contentLength().coerceAtLeast(0L)
            temporary.delete()
            body.byteStream().use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytes = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        bytes += read
                        _state.update { it + (itemId to DownloadInfo(DownloadStatus.ACTIVE, bytes, total)) }
                    }
                }
            }
            if (!temporary.renameTo(target)) {
                target.delete()
                if (!temporary.renameTo(target)) throw IOException("The download could not be saved.")
            }
        }
        val size = target.length()
        _state.update { it + (itemId to DownloadInfo(DownloadStatus.DONE, size, size)) }
    }

    private suspend fun fetchPoster(record: SavedItem) {
        val url = posterUrl(record.item) ?: return
        val fileName = "${record.item.id}.jpg"
        val file = File(filesDir, fileName)
        if (!file.isFile) {
            withContext(Dispatchers.IO) {
                runCatching {
                    http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                        if (response.isSuccessful) {
                            response.body?.bytes()?.let { file.writeBytes(it) }
                        }
                    }
                }
            }
        }
        if (file.isFile) {
            _saved.update { list -> list.map { if (it.item.id == record.item.id) it.copy(posterFile = fileName) else it } }
            saveRecords()
        }
    }

    /** Cancels a download in progress, or deletes a finished one (its file and poster go too). */
    fun remove(itemId: String) {
        val record = _saved.value.firstOrNull { it.item.id == itemId } ?: return
        jobs.remove(itemId)?.cancel()
        File(filesDir, record.fileName).delete()
        File(filesDir, record.fileName + ".part").delete()
        record.posterFile?.let { File(filesDir, it).delete() }
        _saved.update { list -> list.filterNot { it.item.id == itemId } }
        _state.update { it - itemId }
        saveRecords()
    }

    fun removeAll() { _saved.value.map { it.item.id }.forEach(::remove) }

    fun savedItem(itemId: String): SavedItem? = _saved.value.firstOrNull { it.item.id == itemId }

    /** The finished file for the player, or null while it is still downloading, or if it never finished. */
    fun fileFor(itemId: String): File? {
        val record = savedItem(itemId) ?: return null
        if (_state.value[itemId]?.status != DownloadStatus.DONE) return null
        return File(filesDir, record.fileName).takeIf { it.isFile && it.length() > 0 }
    }

    fun posterFor(itemId: String): File? = savedItem(itemId)?.posterFile?.let { File(filesDir, it) }?.takeIf { it.isFile }

    fun totalBytes(): Long = _state.value.values.filter { it.status == DownloadStatus.DONE }.sumOf { it.bytes }

    private fun readRecords(): List<SavedItem> = runCatching {
        if (!recordsFile.isFile) emptyList()
        else JellyfinJson.decodeFromString(ListSerializer(SavedItem.serializer()), recordsFile.readText())
    }.getOrDefault(emptyList())

    private fun saveRecords() {
        runCatching {
            val temporary = File(recordsFile.parentFile, recordsFile.name + ".tmp")
            temporary.writeText(JellyfinJson.encodeToString(ListSerializer(SavedItem.serializer()), _saved.value))
            if (!temporary.renameTo(recordsFile)) recordsFile.writeText(temporary.readText())
        }
    }

    private fun fileNameFor(item: JfItem): String {
        val container = item.mediaSources.firstOrNull()?.container?.split(',')?.firstOrNull()?.trim()?.lowercase()
            ?.takeIf { it.matches(Regex("[a-z0-9]{1,5}")) }
        return "${item.id}.${container ?: "mp3"}"
    }
}
