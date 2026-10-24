package com.everettpenne.thumper

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.everettpenne.thumper.playback.GainProcessor
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GainProcessorTest {
    @Test fun scalesSixteenBitPcmAndClips() {
        val processor = GainProcessor()
        processor.gain = 0.5f
        processor.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
        val input = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        input.putShort(1_000).putShort(-1_000).putShort(Short.MAX_VALUE).putShort(Short.MIN_VALUE)
        input.flip()
        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(500, output.short.toInt())
        assertEquals(-500, output.short.toInt())
        assertEquals(16_383, output.short.toInt())
        assertEquals(-16_384, output.short.toInt())
    }

    @Test fun ducksWithoutLosingTheLevelingGain() {
        val processor = GainProcessor()
        processor.gain = 1f
        processor.duck = 0.25f
        processor.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
        val input = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        input.putShort(1_000).putShort(-2_000)
        input.flip()
        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(250, output.short.toInt())
        assertEquals(-500, output.short.toInt())
    }

    @Test fun scalesFloatAndClipsAboveFullScale() {
        val processor = GainProcessor()
        processor.gain = 2f
        processor.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_FLOAT))
        val input = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        input.putFloat(0.5f).putFloat(-0.75f)
        input.flip()
        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1f, output.float, 0.0001f)
        assertEquals(-1f, output.float, 0.0001f)
    }
}
