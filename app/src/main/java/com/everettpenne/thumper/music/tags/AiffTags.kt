package com.everettpenne.thumper.music.tags

import android.content.Context
import android.net.Uri
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** The tags and timing of an AIFF/AIFC file, as read from its chunks. */
data class AiffTags(
    val tags: TagValues,
    val durationMs: Long,
    val compression: String,
    val channels: Int = 0,
    val sampleRate: Int = 0,
    val bits: Int = 0,
    val dataStart: Long = 0L,
    val dataEnd: Long = 0L,
    val bigEndian: Boolean = true,
)

/** Loudness measured over the PCM, in dBFS relative to full scale. */
data class AudioStats(val rmsDb: Float, val peakDb: Float)

/** Random access to a file, for the chunk walker. Implemented over a channel on Android and a byte array in tests. */
interface RandomReader {
    val length: Long
    fun read(offset: Long, size: Int): ByteArray?
}

/**
 * Reads AIFF and AIFC files the way Android cannot: the COMM chunk gives channels, frames, bits and rate (and, for AIFC,
 * the compression), and the ID3 chunk (which Apple's Music app writes after the audio) gives the tags and cover.
 *
 * Only the headers and the ID3 chunk are read, never the audio, so scanning 790 forty-megabyte files is quick. The chunk
 * walk is pure and unit-tested against a hand-built file; [read] adds the Android file plumbing.
 */
object Aiff {
    private const val MAX_ID3 = 8 * 1024 * 1024

    fun read(context: Context, uri: Uri): AiffTags? = runCatching {
        if (uri.scheme == "file") {
            val path = uri.path ?: return null
            FileInputStream(path).use { stream -> parse(ChannelReader(stream.channel)) }
        } else {
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            descriptor.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { stream -> parse(ChannelReader(stream.channel)) }
            }
        }
    }.getOrNull()

    fun parse(reader: RandomReader): AiffTags? {
        val header = reader.read(0, 12) ?: return null
        if (header.size < 12 || String(header, 0, 4, Charsets.ISO_8859_1) != "FORM") return null
        val form = String(header, 8, 4, Charsets.ISO_8859_1)
        if (form != "AIFF" && form != "AIFC") return null

        var channels = 0
        var frames = 0L
        var bits = 0
        var rate = 0.0
        var compression = "NONE"
        var id3: TagValues? = null
        var name: String? = null
        var author: String? = null
        var position = 12L
        var audioStart = 0L
        var audioEnd = 0L

        while (position + 8 <= reader.length) {
            val chunk = reader.read(position, 8) ?: break
            if (chunk.size < 8) break
            val id = String(chunk, 0, 4, Charsets.ISO_8859_1)
            val size = readUInt32(chunk, 4)
            if (size < 0 || position + 8 + size > reader.length + 1) break
            val dataStart = position + 8
            when (id) {
                "COMM" -> {
                    val data = reader.read(dataStart, min(size, 64L).toInt()) ?: break
                    if (data.size >= 18) {
                        channels = readUInt16(data, 0)
                        frames = readUInt32(data, 2)
                        bits = readUInt16(data, 6)
                        rate = extended(data, 8)
                    }
                    if (form == "AIFC" && data.size >= 22) {
                        compression = String(data, 18, 4, Charsets.ISO_8859_1).trim('\u0000', ' ')
                    }
                }
                "SSND" -> {
                    val sound = reader.read(dataStart, min(size, 8L).toInt())
                    if (sound != null && sound.size >= 8) {
                        val offset = readUInt32(sound, 0)
                        audioStart = dataStart + 8 + offset
                        audioEnd = dataStart + size
                    }
                }
                "ID3 ", "id3 " -> {
                    val data = reader.read(dataStart, min(size, MAX_ID3.toLong()).toInt()) ?: break
                    id3 = Id3.parse(data)
                }
                "NAME" -> name = reader.read(dataStart, min(size, 4096L).toInt())?.toString(Charsets.UTF_8)?.trim()
                "AUTH" -> author = reader.read(dataStart, min(size, 4096L).toInt())?.toString(Charsets.UTF_8)?.trim()
            }
            position = dataStart + size + (size and 1L)
        }

        val duration = if (rate > 0 && frames > 0) (frames * 1000.0 / rate).toLong() else 0L
        if (channels <= 0 || bits <= 0 || rate <= 0) return null
        val merged = TagValues(
            title = id3?.title ?: name?.takeIf { it.isNotBlank() },
            artist = id3?.artist ?: author?.takeIf { it.isNotBlank() },
            album = id3?.album,
            year = id3?.year,
            track = id3?.track,
            picture = id3?.picture,
            albumArtist = id3?.albumArtist,
            disc = id3?.disc,
            genre = id3?.genre,
            trackGainDb = id3?.trackGainDb,
            trackPeak = id3?.trackPeak,
        )
        val bigEndian = !compression.equals("sowt", ignoreCase = true)
        return AiffTags(
            tags = merged,
            durationMs = duration,
            compression = compression,
            channels = channels,
            sampleRate = rate.toInt(),
            bits = bits,
            dataStart = audioStart,
            dataEnd = audioEnd,
            bigEndian = bigEndian,
        )
    }

    /**
     * Measures the PCM's RMS and peak, for tracks with no ReplayGain tag. Reads the file in chunks and never decodes more than
     * 16-bit linear PCM; the library is 16-bit, and other encodings are left unmeasured rather than guessed.
     */
    fun analyze(
        reader: RandomReader,
        info: AiffTags,
        onProgress: (Float) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): AudioStats? {
        if (info.bits != 16 || info.dataEnd <= info.dataStart) return null
        if (info.compression.uppercase() !in setOf("NONE", "TWOS", "SOWT")) return null
        var sum = 0.0
        var peak = 0.0
        var count = 0L
        val total = (info.dataEnd - info.dataStart).coerceAtLeast(1L)
        var offset = info.dataStart
        while (offset < info.dataEnd) {
            if (cancelled()) return null
            val chunk = reader.read(offset, min(CHUNK.toLong(), info.dataEnd - offset).toInt()) ?: break
            if (chunk.isEmpty()) break
            var i = 0
            while (i + 1 < chunk.size) {
                val first = chunk[i].toInt() and 0xff
                val second = chunk[i + 1].toInt() and 0xff
                val raw = if (info.bigEndian) (first shl 8) or second else (second shl 8) or first
                val value = raw.toShort().toInt() / 32768.0
                sum += value * value
                val magnitude = abs(value)
                if (magnitude > peak) peak = magnitude
                count++
                i += 2
            }
            offset += chunk.size
            onProgress(((offset - info.dataStart).toFloat() / total).coerceIn(0f, 1f))
        }
        if (count == 0L) return null
        val rms = sqrt(sum / count)
        val rmsDb = if (rms > 0.0) (20.0 * log10(rms)).toFloat() else -120f
        val peakDb = if (peak > 0.0) (20.0 * log10(peak)).toFloat() else -120f
        return AudioStats(rmsDb, peakDb)
    }

    fun analyze(context: Context, uri: Uri, onProgress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }): AudioStats? =
        runCatching {
            if (uri.scheme == "file") {
                val path = uri.path ?: return null
                FileInputStream(path).use { stream ->
                    val reader = ChannelReader(stream.channel)
                    val info = parse(reader) ?: return null
                    analyze(reader, info, onProgress, cancelled)
                }
            } else {
                val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
                descriptor.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).use { stream ->
                        val reader = ChannelReader(stream.channel)
                        val info = parse(reader) ?: return null
                        analyze(reader, info, onProgress, cancelled)
                    }
                }
            }
        }.getOrNull()

    private const val CHUNK = 1 shl 20

    private class ChannelReader(private val channel: FileChannel) : RandomReader {
        override val length: Long get() = channel.size()

        override fun read(offset: Long, size: Int): ByteArray? {
            if (size <= 0 || offset < 0 || offset >= length) return null
            val count = min(size.toLong(), length - offset).toInt()
            val buffer = ByteBuffer.allocate(count)
            channel.position(offset)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) break
            }
            return buffer.array().copyOf(buffer.position())
        }
    }

    /** The 80-bit IEEE extended value AIFF uses for its sample rate. */
    private fun extended(bytes: ByteArray, offset: Int): Double {
        if (offset + 10 > bytes.size) return 0.0
        val exponent = ((bytes[offset].toInt() and 0x7f) shl 8) or (bytes[offset + 1].toInt() and 0xff)
        // A Double mantissa, because the top bit is set for every normal value and would overflow a Long.
        var mantissa = 0.0
        for (i in 2..9) mantissa = mantissa * 256.0 + (bytes[offset + i].toInt() and 0xff)
        val sign = if (bytes[offset].toInt() and 0x80 != 0) -1.0 else 1.0
        return sign * mantissa * 2.0.pow(exponent - 16383 - 63)
    }

    private fun readUInt16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun readUInt32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xff) shl 24) or
            ((bytes[offset + 1].toLong() and 0xff) shl 16) or
            ((bytes[offset + 2].toLong() and 0xff) shl 8) or
            (bytes[offset + 3].toLong() and 0xff)
}
