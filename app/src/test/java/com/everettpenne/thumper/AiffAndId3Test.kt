package com.everettpenne.thumper

import com.everettpenne.thumper.music.tags.Aiff
import com.everettpenne.thumper.music.tags.Id3
import com.everettpenne.thumper.music.tags.RandomReader
import com.everettpenne.thumper.music.util.isAiffFileName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.min

class AiffAndId3Test {
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4)

    private fun syncSafe(value: Int) = byteArrayOf(
        ((value shr 21) and 0x7f).toByte(), ((value shr 14) and 0x7f).toByte(),
        ((value shr 7) and 0x7f).toByte(), (value and 0x7f).toByte(),
    )

    private fun be16(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())
    private fun be24(value: Int) = byteArrayOf((value shr 16).toByte(), (value shr 8).toByte(), value.toByte())
    private fun be32(value: Long) = byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())

    private fun textFrame(text: String) = byteArrayOf(3) + text.toByteArray(Charsets.UTF_8)

    private fun apic(mime: String = "image/jpeg", description: String = "") =
        byteArrayOf(0) +
            mime.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) +
            byteArrayOf(3) +
            description.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) +
            jpeg

    private fun id3v23(frames: List<Pair<String, ByteArray>>): ByteArray {
        val body = ByteArrayOutputStream()
        for ((id, data) in frames) {
            body.write(id.toByteArray(Charsets.ISO_8859_1))
            body.write(be32(data.size.toLong()))
            body.write(byteArrayOf(0, 0))
            body.write(data)
        }
        val bodyBytes = body.toByteArray()
        return "ID3".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(3, 0, 0) + syncSafe(bodyBytes.size) + bodyBytes
    }

    private fun id3v24(frames: List<Pair<String, ByteArray>>): ByteArray {
        val body = ByteArrayOutputStream()
        for ((id, data) in frames) {
            body.write(id.toByteArray(Charsets.ISO_8859_1))
            body.write(syncSafe(data.size))
            body.write(byteArrayOf(0, 0))
            body.write(data)
        }
        val bodyBytes = body.toByteArray()
        return "ID3".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(4, 0, 0) + syncSafe(bodyBytes.size) + bodyBytes
    }

    private fun id3v22(frames: List<Pair<String, ByteArray>>): ByteArray {
        val body = ByteArrayOutputStream()
        for ((id, data) in frames) {
            body.write(id.toByteArray(Charsets.ISO_8859_1))
            body.write(be24(data.size))
            body.write(data)
        }
        val bodyBytes = body.toByteArray()
        return "ID3".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(2, 0, 0) + syncSafe(bodyBytes.size) + bodyBytes
    }

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

    @Test fun id3v23ReadsTextAndFrontCover() {
        val tags = Id3.parse(id3v23(listOf(
            "TIT2" to textFrame("Let's Go To Bed"),
            "TPE1" to textFrame("The Cure"),
            "TALB" to textFrame("Staring At The Sea"),
            "TYER" to textFrame("1982"),
            "TRCK" to textFrame("11/18"),
            "TPE2" to textFrame("The Cure"),
            "TPOS" to textFrame("1/2"),
            "TCON" to textFrame("Rock"),
            "TXXX" to (byteArrayOf(0) + "REPLAYGAIN_TRACK_GAIN".toByteArray() + byteArrayOf(0) + "-7.25 dB".toByteArray()),
            "APIC" to apic(),
        )))
        assertNotNull(tags)
        assertEquals("Let's Go To Bed", tags!!.title)
        assertEquals("The Cure", tags.artist)
        assertEquals("Staring At The Sea", tags.album)
        assertEquals(1982, tags.year ?: 0)
        assertEquals(11, tags.track ?: 0)
        assertEquals("The Cure", tags.albumArtist)
        assertEquals(1, tags.disc)
        assertEquals("Rock", tags.genre)
        assertEquals(-7.25f, tags.trackGainDb ?: 0f, 0.001f)
        assertTrue(jpeg.contentEquals(tags.picture))
    }

    @Test fun id3v24UsesSyncSafeFrameSizes() {
        val tags = Id3.parse(id3v24(listOf(
            "TIT2" to textFrame("Young Lion"),
            "TPE1" to textFrame("Vampire Weekend"),
            "TDRC" to textFrame("2013-05-14"),
        )))
        assertEquals("Young Lion", tags?.title)
        assertEquals("Vampire Weekend", tags?.artist)
        assertEquals(2013, tags?.year ?: 0)
    }

    @Test fun id3v22ReadsPicturesAndText() {
        val pic = byteArrayOf(0) + "JPG".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(3, 0) + jpeg
        val tags = Id3.parse(id3v22(listOf(
            "TT2" to textFrame("Blue"),
            "TP1" to textFrame("Eiffel 65"),
            "TAL" to textFrame("Europop"),
            "PIC" to pic,
        )))
        assertEquals("Blue", tags?.title)
        assertEquals("Eiffel 65", tags?.artist)
        assertEquals("Europop", tags?.album)
        assertTrue(jpeg.contentEquals(tags?.picture))
    }

    @Test fun id3RejectsOtherData() {
        assertNull(Id3.parse(byteArrayOf(1, 2, 3)))
        assertNull(Id3.parse("NOTATAG".toByteArray()))
    }

    @Test fun readsAnAiffFileAndItsEmbeddedId3() {
        val comm = be16(2) + be32(44_100) + be16(16) +
            byteArrayOf(0x40, 0x0E, 0xAC.toByte(), 0x44, 0, 0, 0, 0, 0, 0)
        val id3 = id3v23(
            listOf(
                "TIT2" to textFrame("A Forest"),
                "TPE1" to textFrame("The Cure"),
                "TALB" to textFrame("Seventeen Seconds"),
                "TPE2" to textFrame("The Cure"),
                "TPOS" to textFrame("1/1"),
                "TCON" to textFrame("Post-punk"),
            ),
        )
        val body = chunk("COMM", comm) + chunk("SSND", ByteArray(8)) + chunk("ID3 ", id3)
        val file = "FORM".toByteArray() + be32(body.size.toLong()) + "AIFF".toByteArray() + body
        val parsed = Aiff.parse(Bytes(file))
        assertNotNull(parsed)
        assertEquals(1000L, parsed!!.durationMs)
        assertEquals("NONE", parsed.compression)
        assertEquals("A Forest", parsed.tags.title)
        assertEquals("The Cure", parsed.tags.artist)
        assertEquals("Seventeen Seconds", parsed.tags.album)
        assertEquals("The Cure", parsed.tags.albumArtist)
        assertEquals(1, parsed.tags.disc)
        assertEquals("Post-punk", parsed.tags.genre)
    }

    @Test fun readsAifcSowtCompressionWithoutChoking() {
        val comm = be16(2) + be32(44_100) + be16(16) +
            byteArrayOf(0x40, 0x0E, 0xAC.toByte(), 0x44, 0, 0, 0, 0, 0, 0) +
            "sowt".toByteArray() + byteArrayOf(0, 0)
        val body = chunk("FVER", byteArrayOf(0xA2.toByte(), 0x80.toByte(), 0x51, 0x40)) + chunk("COMM", comm) + chunk("SSND", ByteArray(8))
        val file = "FORM".toByteArray() + be32(body.size.toLong()) + "AIFC".toByteArray() + body
        val parsed = Aiff.parse(Bytes(file))
        assertNotNull(parsed)
        assertEquals("sowt", parsed!!.compression)
        assertEquals(1000L, parsed.durationMs)
    }

    @Test fun aiffNamesAreRecognised() {
        listOf("a.aif", "b.AIFF", "c.aifc").forEach { assertTrue(it, isAiffFileName(it)) }
        listOf("a.mp3", "b.wav", "c.aiffl").forEach { assertEquals(it, false, isAiffFileName(it)) }
    }
}
