package com.everettpenne.thumper.music.tags

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Measures the loudness of a compressed track (MP3, AAC, FLAC, Ogg...) by decoding it once with the platform codec.
 *
 * AIFF does not go through here — it has no `MediaExtractor`, and [Aiff.analyze] can read its PCM directly. The result is the same
 * RMS/peak pair, so a library of AIFF rips and MP3s gets one consistent target.
 */
object DecodedAudio {
    private const val TIMEOUT_US = 10_000L
    private const val MAX_MS = 120_000L

    fun analyze(context: Context, uri: Uri): AudioStats? = runCatching {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        descriptor.use {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(descriptor.fileDescriptor)
                val track = (0 until extractor.trackCount).firstOrNull { index ->
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: return null
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
                val codec = MediaCodec.createDecoderByType(mime)
                try {
                    codec.configure(format, null, null, 0)
                    codec.start()
                    decode(extractor, codec)
                } finally {
                    runCatching { codec.stop() }
                    runCatching { codec.release() }
                }
            } finally {
                runCatching { extractor.release() }
            }
        }
    }.getOrNull()

    private fun decode(extractor: MediaExtractor, codec: MediaCodec): AudioStats? {
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        var inputDone = false
        var outputDone = false
        var sum = 0.0
        var peak = 0.0
        var count = 0L
        val info = MediaCodec.BufferInfo()
        val started = System.currentTimeMillis()

        while (!outputDone && System.currentTimeMillis() - started < MAX_MS) {
            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val input = codec.getInputBuffer(inputIndex)
                    if (input != null) {
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
            }
            when (val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val output = codec.outputFormat
                    encoding = if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        output.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else {
                        AudioFormat.ENCODING_PCM_16BIT
                    }
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                else -> if (outputIndex >= 0) {
                    val buffer = codec.getOutputBuffer(outputIndex)
                    if (buffer != null && info.size > 0) {
                        buffer.order(ByteOrder.LITTLE_ENDIAN)
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val result = accumulate(buffer, encoding, sum, peak, count)
                        sum = result.first
                        peak = result.second
                        count = result.third
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }
        if (count == 0L) return null
        val rms = sqrt(sum / count)
        val rmsDb = if (rms > 0) (20.0 * log10(rms)).toFloat() else -120f
        val peakDb = if (peak > 0) (20.0 * log10(peak)).toFloat() else -120f
        return AudioStats(rmsDb, peakDb)
    }

    private fun accumulate(
        buffer: java.nio.ByteBuffer,
        encoding: Int,
        initialSum: Double,
        initialPeak: Double,
        initialCount: Long,
    ): Triple<Double, Double, Long> {
        var sum = initialSum
        var peak = initialPeak
        var count = initialCount
        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                while (buffer.remaining() >= 4) {
                    val value = buffer.float.toDouble()
                    sum += value * value
                    val magnitude = abs(value)
                    if (magnitude > peak) peak = magnitude
                    count++
                }
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                while (buffer.hasRemaining()) {
                    val value = (buffer.get().toInt() and 0xff) / 128.0 - 1.0
                    sum += value * value
                    val magnitude = abs(value)
                    if (magnitude > peak) peak = magnitude
                    count++
                }
            }
            else -> {
                while (buffer.remaining() >= 2) {
                    val value = buffer.short / 32768.0
                    sum += value * value
                    val magnitude = abs(value)
                    if (magnitude > peak) peak = magnitude
                    count++
                }
            }
        }
        return Triple(sum, peak, count)
    }
}
