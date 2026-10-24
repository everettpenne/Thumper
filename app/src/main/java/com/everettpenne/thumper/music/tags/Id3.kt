package com.everettpenne.thumper.music.tags

import android.content.Context
import android.net.Uri
import java.io.FileInputStream
import java.io.InputStream
import java.nio.charset.Charset
import kotlin.math.min

/** What an ID3 tag can tell us. Every field is optional; the picture is the front cover, when there is one. */
data class TagValues(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val year: Int? = null,
    val track: Int? = null,
    val picture: ByteArray? = null,
    /** The album artist (TPE2), which keeps guest tracks on their album. */
    val albumArtist: String? = null,
    val disc: Int? = null,
    val genre: String? = null,
    /** ReplayGain, when the file carries it; the fallback is analysing the PCM. */
    val trackGainDb: Float? = null,
    val trackPeak: Float? = null,
)

/**
 * A small ID3v2 reader, for the tags Apple's Music app writes into AIFF files (and anything else that carries ID3).
 *
 * It handles versions 2.2, 2.3 and 2.4: the text frames for title, artist, album, year and track, and the attached
 * picture (APIC in 2.3/2.4, PIC in 2.2). It is deliberately not a full tagger: no writing, no unsupported frames, no
 * network. Pure, so the parsing is unit-tested against hand-built tags.
 */
object Id3 {
    private const val MAX_READ = 8 * 1024 * 1024

    /** Reads the ID3 tag at the start of a file, for its ReplayGain fields (Android's retriever does not expose TXXX). */
    fun read(context: Context, uri: Uri): TagValues? = runCatching {
        if (uri.scheme == "file") {
            val path = uri.path ?: return null
            FileInputStream(path).use { stream -> readTag(stream) }
        } else {
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            descriptor.use { pfd -> FileInputStream(pfd.fileDescriptor).use { stream -> readTag(stream) } }
        }
    }.getOrNull()

    private fun readTag(stream: InputStream): TagValues? {
        val header = ByteArray(10)
        if (readFully(stream, header, header.size) < header.size) return null
        if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) return null
        val size = min(syncSafe(header, 6), MAX_READ)
        val body = ByteArray(size)
        if (readFully(stream, body, size) < size) return null
        return parse(header + body)
    }

    private fun readFully(stream: InputStream, target: ByteArray, length: Int): Int {
        var read = 0
        while (read < length) {
            val count = stream.read(target, read, length - read)
            if (count < 0) break
            read += count
        }
        return read
    }

    fun parse(data: ByteArray): TagValues? {
        if (data.size < 10) return null
        if (data[0] != 'I'.code.toByte() || data[1] != 'D'.code.toByte() || data[2] != '3'.code.toByte()) return null
        val major = data[3].toInt() and 0xff
        if (major !in 2..4) return null
        val flags = data[5].toInt() and 0xff
        val tagSize = syncSafe(data, 6)
        var body = if (flags and 0x80 != 0) deUnsynchronise(data, 10, min(data.size, 10 + tagSize)) else data
        var position = 10
        val end = min(body.size, 10 + tagSize)

        // Extended headers (2.3 and 2.4) are skipped; Apple's AIFF tags do not use one, but other taggers can.
        if (flags and 0x40 != 0 && major >= 3 && position + 4 <= end) {
            position += if (major == 3) 4 + readInt(body, position) else syncSafe(body, position)
        }

        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var year: Int? = null
        var track: Int? = null
        var picture: ByteArray? = null
        var pictureIsFront = false
        var albumArtist: String? = null
        var disc: Int? = null
        var genre: String? = null
        var trackGainDb: Float? = null
        var trackPeak: Float? = null

        while (position + 6 <= end) {
            if (body[position] == 0.toByte()) break
            val id: String
            val frameSize: Int
            val headerSize: Int
            when (major) {
                2 -> {
                    id = String(body, position, 3, Charsets.ISO_8859_1)
                    frameSize = read24(body, position + 3)
                    headerSize = 6
                }
                3 -> {
                    id = String(body, position, 4, Charsets.ISO_8859_1)
                    frameSize = readInt(body, position + 4)
                    headerSize = 10
                }
                else -> {
                    id = String(body, position, 4, Charsets.ISO_8859_1)
                    frameSize = syncSafe(body, position + 4)
                    headerSize = 10
                }
            }
            if (frameSize <= 0 || position + headerSize + frameSize > body.size) break
            val start = position + headerSize
            val frame = body.copyOfRange(start, start + frameSize)
            when (id) {
                "TT2", "TIT2" -> title = text(frame)
                "TP1", "TPE1" -> artist = text(frame)
                "TAL", "TALB" -> album = text(frame)
                "TYE", "TYER", "TDRC" -> year = text(frame)?.take(4)?.toIntOrNull()
                "TRK", "TRCK" -> track = text(frame)?.substringBefore('/')?.trim()?.toIntOrNull()
                "TP2", "TPE2" -> albumArtist = text(frame)
                "TPA", "TPOS" -> disc = text(frame)?.substringBefore('/')?.trim()?.toIntOrNull()
                "TCO", "TCON" -> genre = text(frame)
                "TXX", "TXXX" -> userText(frame)?.let { (description, value) ->
                    when (description.uppercase()) {
                        "REPLAYGAIN_TRACK_GAIN" -> trackGainDb = gain(value)
                        "REPLAYGAIN_TRACK_PEAK" -> trackPeak = value.toFloatOrNull()
                    }
                }
                "PIC" -> pictureFrame(frame, major = 2)?.let { (bytes, front) ->
                    if (picture == null || (front && !pictureIsFront)) { picture = bytes; pictureIsFront = front }
                }
                "APIC" -> pictureFrame(frame, major = major)?.let { (bytes, front) ->
                    if (picture == null || (front && !pictureIsFront)) { picture = bytes; pictureIsFront = front }
                }
            }
            position += headerSize + frameSize
        }
        if (title == null && artist == null && album == null && year == null && track == null && picture == null &&
            albumArtist == null && disc == null && genre == null && trackGainDb == null
        ) return null
        return TagValues(
            title = title,
            artist = artist,
            album = album,
            year = year,
            track = track,
            picture = picture,
            albumArtist = albumArtist,
            disc = disc,
            genre = genre,
            trackGainDb = trackGainDb,
            trackPeak = trackPeak,
        )
    }

    /** The first text byte is the encoding; the rest is the value, minus its null terminator. */
    private fun text(frame: ByteArray): String? {
        if (frame.isEmpty()) return null
        val value = decode(frame[0].toInt() and 0xff, frame, 1, frame.size - 1)
        return value.trim().takeIf { it.isNotEmpty() }
    }

    /** APIC (2.3/2.4) and PIC (2.2): encoding, type/format, description, then the image bytes. */
    private fun pictureFrame(frame: ByteArray, major: Int): Pair<ByteArray, Boolean>? {
        if (frame.size < 6) return null
        val encoding = frame[0].toInt() and 0xff
        var offset = 1
        if (major == 2) {
            if (frame.size < 4) return null
            offset += 3
        } else {
            val mimeEnd = indexOfZero(frame, offset)
            if (mimeEnd < 0) return null
            offset = mimeEnd + 1
        }
        if (offset >= frame.size) return null
        val type = frame[offset].toInt() and 0xff
        offset++
        // Description: a null (or a UTF-16 null) terminated string in the frame's encoding.
        val descriptionEnd = if (encoding == 1 || encoding == 2) indexOfWideZero(frame, offset) else indexOfZero(frame, offset)
        if (descriptionEnd < 0 || descriptionEnd + terminator(encoding) >= frame.size) return null
        offset = descriptionEnd + terminator(encoding)
        if (offset >= frame.size) return null
        return frame.copyOfRange(offset, frame.size) to (type == 3)
    }

    /** TXXX: encoding, a null-terminated description, then the value in the same encoding. */
    private fun userText(frame: ByteArray): Pair<String, String>? {
        if (frame.isEmpty()) return null
        val encoding = frame[0].toInt() and 0xff
        val end = if (encoding == 1 || encoding == 2) indexOfWideZero(frame, 1) else indexOfZero(frame, 1)
        if (end < 0) return null
        val description = decode(encoding, frame, 1, end - 1)
        val valueStart = end + terminator(encoding)
        if (valueStart >= frame.size) return null
        return description to decode(encoding, frame, valueStart, frame.size - valueStart)
    }

    private fun gain(value: String): Float? =
        value.trim().removeSuffix("dB").removeSuffix("db").trim().toFloatOrNull()

    private fun terminator(encoding: Int): Int = if (encoding == 1 || encoding == 2) 2 else 1

    private fun indexOfZero(bytes: ByteArray, from: Int): Int {
        for (i in from until bytes.size) if (bytes[i] == 0.toByte()) return i
        return -1
    }

    private fun indexOfWideZero(bytes: ByteArray, from: Int): Int {
        var i = from
        while (i + 1 < bytes.size) {
            if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte()) return i
            i += 2
        }
        return -1
    }

    private fun decode(encoding: Int, bytes: ByteArray, offset: Int, length: Int): String {
        if (offset >= bytes.size || length <= 0) return ""
        val slice = bytes.copyOfRange(offset, min(bytes.size, offset + length))
        val charset: Charset = when (encoding) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        return String(slice, charset).trimEnd('\u0000', ' ')
    }

    /** ID3v2.3 unsynchronisation: FF 00 becomes FF. */
    private fun deUnsynchronise(data: ByteArray, from: Int, to: Int): ByteArray {
        val out = ArrayList<Byte>(to - from)
        var i = from
        while (i < to) {
            val byte = data[i]
            out.add(byte)
            if (byte == 0xFF.toByte() && i + 1 < to && data[i + 1] == 0.toByte()) i++
            i++
        }
        return out.toByteArray()
    }

    private fun syncSafe(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0x7f) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7f) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7f) shl 7) or
            (bytes[offset + 3].toInt() and 0x7f)

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private fun read24(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 16) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            (bytes[offset + 2].toInt() and 0xff)
}
