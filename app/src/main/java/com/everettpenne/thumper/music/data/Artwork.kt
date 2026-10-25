package com.everettpenne.thumper.music.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import android.util.Size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.everettpenne.thumper.music.tags.Aiff
import com.everettpenne.thumper.music.util.fileNameOf
import com.everettpenne.thumper.music.util.isAiffFileName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Album artwork, read once per album and kept as a small JPEG in the app's private storage.
 *
 * The order is deliberately local first: a cover image in the folder (cover.jpg, folder.jpg, …), then Android's thumbnail
 * (which gives an indexed file's embedded cover), then the file's own tags. A cover chosen from the Cover Art Archive is
 * written through [apply] and treated like any other, so a rescan does not replace it.
 */
object Artwork {
    private const val MAX_PX = 640
    private const val JPEG_QUALITY = 86
    private const val MEMORY_BITMAPS = 24

    /** Bumped whenever a cover is written or deleted, so rememberArtwork reloads without a rescan. */
    var revision by mutableStateOf(0L)
        private set

    private val memory = object : LruCache<String, Bitmap>(MEMORY_BITMAPS) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    private fun dir(context: Context): File = File(context.filesDir, "art").apply { if (!isDirectory) mkdirs() }

    private fun file(context: Context, albumKey: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(albumKey.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) }.take(24)
        return File(dir(context), "$name.jpg")
    }

    fun has(context: Context, albumKey: String): Boolean = file(context, albumKey).let { it.isFile && it.length() > 0L }

    /** A file:// address for a cached cover, so the player and notification can show it without reading the bytes again. */
    fun fileUri(context: Context, albumKey: String): String? = file(context, albumKey).takeIf { it.isFile && it.length() > 0L }?.toURI()?.toString()

    /** The decoded cover, from memory or the cache file; null while it has not been extracted. */
    suspend fun load(context: Context, albumKey: String): Bitmap? = withContext(Dispatchers.IO) {
        memory.get(albumKey)?.let { return@withContext it }
        val cached = file(context, albumKey)
        if (!cached.isFile) return@withContext null
        runCatching { BitmapFactory.decodeFile(cached.path) }.getOrNull()?.also { memory.put(albumKey, it) }
    }

    /** The JPEG bytes, for a media item's metadata (the notification and the island read these). */
    suspend fun bytes(context: Context, albumKey: String): ByteArray? = withContext(Dispatchers.IO) {
        val cached = file(context, albumKey)
        if (!cached.isFile) null else runCatching { cached.readBytes() }.getOrNull()
    }

    /** Reads and saves a cover for the album: folder image, then Android's thumbnail, then the file's own tags. */
    suspend fun extract(context: Context, albumKey: String, uri: String, coverUri: String? = null, fileName: String? = null,
        mime: String? = null): Boolean = withContext(Dispatchers.IO) {
        if (has(context, albumKey)) return@withContext true
        // AIFF is known by the track's own file name or type: a phone-music track's address is a number (content://media/.../123), so the
        // name in the address never said ".aiff" and AIFF covers (in the file's ID3 chunk, which Android's reader does not look at) were
        // never read; on a library of AIFF files nearly every album showed the placeholder.
        val isAiff = isAiffFileName(fileName?.takeIf { it.isNotBlank() } ?: fileNameOf(uri)) || isAiffMime(mime)
        val picture = coverUri?.let { decodeUri(context, it) }
            ?: (if (isAiff) aiffPicture(context, uri) else null)
            ?: runCatching { thumbnail(context, uri) }.getOrNull() ?: embedded(context, uri)
        if (picture == null) return@withContext false
        write(context, albumKey, picture)
    }

    /**
     * Adopts a cover that was cached under an older album key, so grouping changes (album artist, folder identity) do not orphan the
     * art that was already on the phone. The old file is left in place; a copy is written under the new key.
     */
    suspend fun adopt(context: Context, albumKey: String, legacyKeys: List<String>): Boolean = withContext(Dispatchers.IO) {
        if (has(context, albumKey)) return@withContext true
        for (legacy in legacyKeys.distinct()) {
            if (legacy == albumKey) continue
            val source = file(context, legacy)
            if (!source.isFile || source.length() == 0L) continue
            val target = file(context, albumKey)
            val copied = runCatching {
                val temporary = File(target.parentFile, target.name + ".tmp")
                source.copyTo(temporary, overwrite = true)
                temporary.renameTo(target) || run { target.delete(); temporary.renameTo(target) }
            }.getOrDefault(false)
            if (copied) {
                memory.remove(albumKey)
                revision++
                return@withContext true
            }
        }
        false
    }

    /** Writes cover bytes fetched from elsewhere (the Cover Art Archive) through the same scaling and cache. */
    suspend fun apply(context: Context, albumKey: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() ?: return@withContext false
        write(context, albumKey, bitmap)
    }

    /** Forgets one album's cover, so a reset or the next scan can read the file again. */
    fun delete(context: Context, albumKey: String) {
        memory.remove(albumKey)
        runCatching { file(context, albumKey).delete() }
        revision++
    }

    private fun write(context: Context, albumKey: String, picture: Bitmap): Boolean {
        val scaled = scaleDown(picture, MAX_PX)
        val bytes = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
        val target = file(context, albumKey)
        val written = runCatching {
            val temporary = File(target.parentFile, target.name + ".tmp")
            temporary.writeBytes(bytes)
            temporary.renameTo(target) || run { target.delete(); temporary.renameTo(target) }
        }.getOrDefault(false)
        if (written) {
            memory.put(albumKey, scaled)
            revision++
        }
        return written
    }

    /** The cover in an AIFF file's ID3 chunk; Android's retriever cannot see it. */
    /** audio/x-aiff, audio/aiff and audio/x-aifc, as Android's media index reports AIFF files. */
    fun isAiffMime(mime: String?): Boolean = mime?.lowercase()?.let { it.contains("aiff") || it.contains("aifc") } == true

    private fun aiffPicture(context: Context, uri: String): Bitmap? = runCatching {
        Aiff.read(context, Uri.parse(uri))?.tags?.picture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }.getOrNull()

    /** Decodes a cover file found beside the music (a SAF document or a plain file). */
    private fun decodeUri(context: Context, uri: String): Bitmap? = runCatching {
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }
    }.getOrNull()

    /** Android's own media thumbnail; for an audio row this is the album cover. */
    private fun thumbnail(context: Context, uri: String): Bitmap? =
        context.contentResolver.loadThumbnail(Uri.parse(uri), Size(MAX_PX, MAX_PX), null)

    /** The cover in the file's own tags, for a folder that Android has not indexed. */
    private fun embedded(context: Context, uri: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        val parsed = Uri.parse(uri)
        return try {
            if (parsed.scheme == "file") retriever.setDataSource(parsed.path) else retriever.setDataSource(context, parsed)
            retriever.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun scaleDown(source: Bitmap, max: Int): Bitmap {
        val largest = maxOf(source.width, source.height)
        if (largest <= max) return source
        val scale = max.toFloat() / largest
        return Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), true)
    }

    /** Forgets every cached cover, for "Remove library" and for tests. */
    fun clear(context: Context) {
        memory.evictAll()
        dir(context).listFiles()?.forEach { runCatching { it.delete() } }
        revision++
    }
}
