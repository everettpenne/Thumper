package com.everettpenne.thumper.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Applies the volume leveling gain to decoded PCM, after the decoder and before the audio sink.
 *
 * Only 16-bit and float PCM are scaled, because that is what the library decodes to; anything else passes through unchanged rather
 * than being mangled. The gain is volatile so the service can change it between tracks without rebuilding the player.
 */
@UnstableApi
class GainProcessor : BaseAudioProcessor() {
    @Volatile
    var gain: Float = 1f

    /** A focus ducking factor, kept separate from the leveling gain so the two do not overwrite each other. */
    @Volatile
    var duck: Float = 1f

    private var encoding = C.ENCODING_INVALID

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        encoding = inputAudioFormat.encoding
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size <= 0) return
        val output = replaceOutputBuffer(size)
        val factor = (gain * duck).coerceIn(0f, 4f)
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                while (inputBuffer.remaining() >= 2) {
                    val low = inputBuffer.get().toInt() and 0xff
                    val high = inputBuffer.get().toInt()
                    var sample = ((high shl 8) or low).toShort().toInt()
                    sample = (sample * factor).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    output.put((sample and 0xff).toByte())
                    output.put((sample shr 8).toByte())
                }
                while (inputBuffer.hasRemaining()) output.put(inputBuffer.get())
            }
            C.ENCODING_PCM_FLOAT -> {
                while (inputBuffer.remaining() >= 4) {
                    val a = inputBuffer.get().toInt() and 0xff
                    val b = inputBuffer.get().toInt() and 0xff
                    val c = inputBuffer.get().toInt() and 0xff
                    val d = inputBuffer.get().toInt() and 0xff
                    val bits = (d shl 24) or (c shl 16) or (b shl 8) or a
                    val value = (Float.fromBits(bits) * factor).coerceIn(-1f, 1f)
                    val out = java.lang.Float.floatToIntBits(value)
                    output.put((out and 0xff).toByte())
                    output.put(((out shr 8) and 0xff).toByte())
                    output.put(((out shr 16) and 0xff).toByte())
                    output.put(((out shr 24) and 0xff).toByte())
                }
                while (inputBuffer.hasRemaining()) output.put(inputBuffer.get())
            }
            else -> output.put(inputBuffer)
        }
        output.flip()
        inputBuffer.position(inputBuffer.limit())
    }
}
