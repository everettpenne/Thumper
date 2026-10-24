package com.everettpenne.thumper

import com.everettpenne.thumper.data.model.Playable
import com.everettpenne.thumper.data.model.ThumperJson
import com.everettpenne.thumper.music.data.Track
import com.everettpenne.thumper.music.data.toPlayable
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bridge the merged app relies on: a library track becomes a queue entry, and a mixed queue survives a restart. */
class MusicMergeTest {
    private val track = Track(
        id = "m:7", uri = "content://media/audio/7", title = "Paper Moon", artist = "Aster Vale",
        album = "Baliset", albumId = 7, durationMs = 26_000, trackNo = 1, albumArtist = "Aster Vale",
    )

    @Test fun `a library track becomes one playable queue entry`() {
        val entry = track.toPlayable(gain = .7f, artworkUri = "file:///art/7.jpg")
        assertEquals("music:m:7", entry.id)
        assertEquals(Playable.Music.KIND, entry.kind)
        assertEquals("Paper Moon", entry.title)
        assertEquals("Aster Vale", entry.subtitle)
        assertEquals("file:///art/7.jpg", entry.artworkUrl)
        assertEquals(26_000L, entry.durationMs)
        assertEquals(.7f, entry.gain, 0.001f)
        assertTrue(entry.id != track.id)
    }

    @Test fun `podcasts and songs share one serialized queue`() {
        val episode = com.everettpenne.thumper.data.model.Episode("e1", "f", "An episode", "https://a/1.mp3", durationSec = 60)
        val feed = com.everettpenne.thumper.data.model.Feed("f", "https://f/feed", "A show")
        val queue: List<Playable> = listOf(Playable.Podcast(episode, feed), track.toPlayable())
        val json = ThumperJson.encodeToString(ListSerializer(Playable.serializer()), queue)
        val back = ThumperJson.decodeFromString(ListSerializer(Playable.serializer()), json)
        assertEquals(listOf("e1", "music:m:7"), back.map { it.id })
        assertEquals("A show", (back[0] as Playable.Podcast).feed.title)
        assertEquals("Aster Vale", (back[1] as Playable.Music).artist)
    }
}
