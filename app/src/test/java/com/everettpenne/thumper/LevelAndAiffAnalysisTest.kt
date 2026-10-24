package com.everettpenne.thumper

import com.everettpenne.thumper.music.data.LevelMath
import com.everettpenne.thumper.music.tags.Aiff
import com.everettpenne.thumper.music.tags.RandomReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.math.min

class LevelAndAiffAnalysisTest {
    private fun be16(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())
    private fun be32(value: Long) =
        byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())

    private fun chunk(id: String, data: ByteArray): ByteArray =
        id.toByteArray(Charsets.ISO_8859_1) + be32(data.size.toLong()) + data + (if (data.size % 2 == 1) byteArrayOf(0) else byteArrayOf())

    private class Bytes(private val data: ByteArray) : RandomReader {
        override val length: Long get() = data.size.toLong()
        override fun read(offset: Long, size: Int): ByteArray? {
            if (offset < 0 || offset >= data.size) return null
            val start = offset.toInt()
            return data.copyOfRange(start, min(data.size, start + size))
        }
    }

    private fun aiff(bigEndian: Boolean): ByteArray {
        val frames = 1_000
        val comm = if (bigEndian) {
            be16(2) + be32(frames.toLong()) + be16(16) +
                byteArrayOf(0x40, 0x0E, 0xAC.toByte(), 0x44, 0, 0, 0, 0, 0, 0)
        } else {
            be16(2) + be32(frames.toLong()) + be16(16) +
                byteArrayOf(0x40, 0x0E, 0xAC.toByte(), 0x44, 0, 0, 0, 0, 0, 0) +
                "sowt".toByteArray() + byteArrayOf(0, 0)
        }
        val samples = ByteArray(frames * 2 * 2)
        var i = 0
        repeat(frames * 2) {
            // 0.5 full scale, so RMS and peak are both -6.02 dBFS.
            if (bigEndian) {
                samples[i] = 0x40
                samples[i + 1] = 0x00
            } else {
                samples[i] = 0x00
                samples[i + 1] = 0x40
            }
            i += 2
        }
        val ssnd = ByteArray(8) + samples
        val body = chunk("COMM", comm) + chunk("SSND", ssnd)
        val form = if (bigEndian) "AIFF" else "AIFC"
        return "FORM".toByteArray() + be32(body.size.toLong()) + form.toByteArray() + body
    }

    @Test fun measuredPcmBecomesTheExpectedLoudness() {
        listOf(true, false).forEach { bigEndian ->
            val reader = Bytes(aiff(bigEndian))
            val info = Aiff.parse(reader)
            assertNotNull(info)
            assertEquals(16, info!!.bits)
            assertEquals(2, info.channels)
            assertEquals(44_100, info.sampleRate)
            val stats = Aiff.analyze(reader, info)
            assertNotNull(stats)
            assertEquals(-6.02f, stats!!.rmsDb, 0.05f)
            assertEquals(-6.02f, stats.peakDb, 0.05f)
        }
    }

    @Test fun gainTargetsEighteenDbfsAndRespectsThePeakCeiling() {
        assertEquals(5.5f, LevelMath.gainDb(-24f, -6f), 0.01f)
        assertEquals(0.5f, LevelMath.gainDb(-24f, -1f), 0.01f)
        assertEquals(12f, LevelMath.gainDb(-40f, -30f), 0.01f)
        assertEquals(-12f, LevelMath.gainDb(-5f, -0.2f), 0.01f)
        assertEquals(0f, LevelMath.gainDb(-120f, -120f), 0.01f)
    }

    @Test fun gainIsLinear() {
        assertEquals(1f, LevelMath.linear(0f), 0.0001f)
        assertEquals(2f, LevelMath.linear(6.0206f), 0.0001f)
        assertEquals(0.5f, LevelMath.linear(-6.0206f), 0.0001f)
    }
}
