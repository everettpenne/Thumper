package com.everettpenne.thumper.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ConstantBitrateSeekMap
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.TrackOutput
import java.io.IOException
import kotlin.math.min

/**
 * Plays AIFF and AIFC, which Android and Media3 do not support.
 *
 * AIFF is uncompressed PCM; the container is a FORM with a COMM chunk (channels, frames, bits, rate, and for AIFC the
 * compression) and an SSND chunk with the samples. The samples are big-endian, while Android's audio pipeline wants
 * little-endian, so they are byte-swapped on the way through; `sowt` files are already little-endian and pass through.
 *
 * Only linear PCM (and 32-bit float in AIFC) is handled. Compressed AIFF variants (ima4, alaw, ulaw) are rejected with a
 * clear error rather than played as noise. Seeking is constant-bit-rate, which is exact for PCM.
 */
@UnstableApi
class AiffExtractor : Extractor {
    private val buffer = ParsableByteArray(BUFFER_SIZE)
    private var output: ExtractorOutput? = null
    private var trackOutput: TrackOutput? = null
    private var prepared = false
    private var dataStart = 0L
    private var dataEnd = 0L
    private var bytesPerFrame = 0
    private var bytesPerSecond = 0L
    private var bytesPerSample = 0
    private var bigEndian = true
    private var channels = 0
    private var sampleRate = 0
    private var bits = 0
    private var compression = "NONE"

    override fun sniff(input: ExtractorInput): Boolean {
        val header = ByteArray(12)
        input.peekFully(header, 0, 12)
        return text(header, 0) == "FORM" && (text(header, 8) == "AIFF" || text(header, 8) == "AIFC")
    }

    override fun init(output: ExtractorOutput) {
        this.output = output
        trackOutput = output.track(0, C.TRACK_TYPE_AUDIO)
        output.endTracks()
    }

    override fun seek(position: Long, timeUs: Long) {
        // The input is repositioned by the caller; the constant-bitrate seek map computed the byte position.
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (!prepared) {
            if (!parse(input)) return Extractor.RESULT_END_OF_INPUT
            prepared = true
        }
        val position = input.position
        if (position >= dataEnd) return Extractor.RESULT_END_OF_INPUT
        val remaining = (dataEnd - position).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val wanted = min(buffer.data.size, remaining)
        val aligned = if (bytesPerFrame > 0) wanted - (wanted % bytesPerFrame) else wanted
        if (aligned <= 0) return Extractor.RESULT_END_OF_INPUT
        try {
            input.readFully(buffer.data, 0, aligned)
        } catch (_: IOException) {
            return Extractor.RESULT_END_OF_INPUT
        }
        if (bigEndian && bytesPerSample > 1) swap(buffer.data, aligned, bytesPerSample)
        val timeUs = if (bytesPerSecond > 0) ((position - dataStart) * 1_000_000L) / bytesPerSecond else 0L
        buffer.setPosition(0)
        trackOutput?.sampleData(buffer, aligned)
        trackOutput?.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, aligned, 0, null)
        return Extractor.RESULT_CONTINUE
    }

    override fun release() {
        // Nothing to release: the input and the output belong to the player.
    }

    /** Walks the FORM chunks up to SSND, then sets up the format and seek map. */
    private fun parse(input: ExtractorInput): Boolean {
        val header = ByteArray(12)
        input.readFully(header, 0, 12)
        if (text(header, 0) != "FORM") throw malformed("Not an AIFF file.")
        val form = text(header, 8)
        if (form != "AIFF" && form != "AIFC") throw malformed("Unsupported AIFF form: $form.")

        val chunk = ByteArray(8)
        while (input.length == C.LENGTH_UNSET.toLong() || input.position + 8 <= input.length) {
            input.readFully(chunk, 0, 8)
            val id = text(chunk, 0)
            val size = readU32(chunk, 4)
            val chunkStart = input.position - 8
            when (id) {
                "COMM" -> {
                    val toRead = min(size, 64L).toInt()
                    val data = ByteArray(toRead)
                    input.readFully(data, 0, toRead)
                    if (toRead < size) input.skipFully((size - toRead).toInt())
                    if (data.size < 18) throw malformed("The AIFF COMM chunk is too short.")
                    channels = readU16(data, 0)
                    bits = readU16(data, 6)
                    sampleRate = readExtended(data, 8).toInt()
                    if (form == "AIFC" && data.size >= 22) compression = text(data, 18).trim()
                }
                "SSND" -> {
                    val soundHeader = ByteArray(8)
                    input.readFully(soundHeader, 0, 8)
                    val offset = readU32(soundHeader, 0)
                    if (offset > 0) if (offset > Int.MAX_VALUE) throw malformed("The AIFF sound offset is too large."); input.skipFully(offset.toInt())
                    dataStart = input.position
                    dataEnd = chunkStart + 8 + size
                    return prepare(input)
                }
                else -> {
                    if (size > 0) input.skipFully(min(size, Int.MAX_VALUE.toLong()).toInt())
                }
            }
            if (size % 2 == 1L) input.skipFully(1)
        }
        throw malformed("The AIFF file has no sound data.")
    }

    private fun prepare(input: ExtractorInput): Boolean {
        if (channels <= 0 || bits <= 0 || sampleRate <= 0) throw malformed("The AIFF header is incomplete.")
        bytesPerSample = when (bits) {
            16, 24, 32 -> bits / 8
            else -> throw malformed("Unsupported AIFF sample size: $bits bits.")
        }
        bigEndian = when (compression.uppercase()) {
            "NONE", "TWOS" -> true
            "SOWT" -> false
            "FL32" -> true
            else -> throw malformed("Unsupported AIFF compression: $compression.")
        }
        val pcmEncoding = when {
            compression.equals("fl32", ignoreCase = true) -> C.ENCODING_PCM_FLOAT
            bytesPerSample == 3 -> C.ENCODING_PCM_24BIT
            bytesPerSample == 4 -> C.ENCODING_PCM_32BIT
            else -> C.ENCODING_PCM_16BIT
        }
        bytesPerFrame = channels * bytesPerSample
        bytesPerSecond = sampleRate.toLong() * bytesPerFrame
        if (bytesPerSecond <= 0 || dataEnd <= dataStart) throw malformed("The AIFF sound data is empty.")
        val format = Format.Builder()
            .setContainerMimeType("audio/x-aiff")
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(pcmEncoding)
            .setChannelCount(channels)
            .setSampleRate(sampleRate)
            .setMaxInputSize(BUFFER_SIZE)
            .setAverageBitrate((bytesPerSecond * 8).toInt())
            .build()
        trackOutput?.format(format)
        // The audio ends before the trailing ID3 chunk, so the seek map gets the exact audio end.
        output?.seekMap(
            ConstantBitrateSeekMap(
                dataEnd,
                dataStart,
                (bytesPerSecond * 8).toInt(),
                bytesPerFrame,
            ),
        )
        trackOutput?.durationUs(((dataEnd - dataStart) * 1_000_000L) / bytesPerSecond)
        return true
    }

    private fun swap(bytes: ByteArray, size: Int, unit: Int) {
        var start = 0
        while (start + unit <= size) {
            var left = start
            var right = start + unit - 1
            repeat(unit / 2) {
                val temporary = bytes[left]
                bytes[left] = bytes[right]
                bytes[right] = temporary
                left++
                right--
            }
            start += unit
        }
    }

    private fun text(bytes: ByteArray, offset: Int): String =
        String(bytes, offset, 4, Charsets.ISO_8859_1)

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun readU32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xff) shl 24) or
            ((bytes[offset + 1].toLong() and 0xff) shl 16) or
            ((bytes[offset + 2].toLong() and 0xff) shl 8) or
            (bytes[offset + 3].toLong() and 0xff)

    /** The 80-bit IEEE extended sample rate. */
    private fun readExtended(bytes: ByteArray, offset: Int): Double {
        if (offset + 10 > bytes.size) return 0.0
        val exponent = ((bytes[offset].toInt() and 0x7f) shl 8) or (bytes[offset + 1].toInt() and 0xff)
        var mantissa = 0.0
        for (i in 2..9) mantissa = mantissa * 256.0 + (bytes[offset + i].toInt() and 0xff)
        val sign = if (bytes[offset].toInt() and 0x80 != 0) -1.0 else 1.0
        return sign * mantissa * Math.pow(2.0, (exponent - 16383 - 63).toDouble())
    }

    private fun malformed(message: String): ParserException =
        ParserException.createForMalformedContainer(message, null)

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}
