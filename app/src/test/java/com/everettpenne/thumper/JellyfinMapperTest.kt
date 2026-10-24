package com.everettpenne.thumper

import com.everettpenne.thumper.music.data.SOURCE_JELLYFIN
import com.everettpenne.thumper.music.data.jellyfin.ImageTags
import com.everettpenne.thumper.music.data.jellyfin.JfItem
import com.everettpenne.thumper.music.data.jellyfin.MediaSourceDto
import com.everettpenne.thumper.music.data.jellyfin.MediaUrls
import com.everettpenne.thumper.music.data.jellyfin.toTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JellyfinMapperTest {
    private val urls = MediaUrls({ "http://server:8096" }, { "device-1" })

    @Test fun mapsAJellyfinAudioItemToALocalTrack() {
        val item = JfItem(
            id = "tr1",
            name = "First Song",
            type = "Audio",
            album = "Test Album",
            albumId = "al1",
            albumArtists = listOf("Test Artist"),
            artists = listOf("Test Artist"),
            index = 2,
            parentIndex = 1,
            runTimeTicks = 300_000_000,
            mediaSources = listOf(MediaSourceDto(id = "ms1", container = "mp3")),
            albumPrimaryTag = "tag-al1",
        )
        val track = item.toTrack(urls)
        assertEquals("jf:tr1", track.id)
        assertEquals(SOURCE_JELLYFIN, track.source)
        assertEquals("Test Artist", track.artist)
        assertEquals("Test Album", track.album)
        assertEquals(1, track.discNo)
        assertEquals(2, track.trackNo)
        assertEquals(1, track.discNo)
        assertEquals(30_000L, track.durationMs)
        assertEquals("jf:al1", track.albumKey)
        assertTrue(track.uri.contains("/Audio/tr1/stream"))
        assertFalse(track.uri.contains("test-token"))
        assertTrue(track.artUrl!!.contains("/Items/al1/Images/Primary"))
        assertTrue(track.artUrl!!.contains("tag=tag-al1"))
    }

    @Test fun anItemWithoutAnAlbumFallsBackToItself() {
        val item = JfItem(id = "tr9", name = "Loose Song", type = "Audio", imageTags = ImageTags(primary = "own"))
        val track = item.toTrack(urls)
        assertEquals("jf:tr9", track.albumKey)
        assertEquals("Loose Song", track.title)
        assertTrue(track.artUrl!!.contains("/Items/tr9/Images/Primary"))
    }
}
