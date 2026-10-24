package com.everettpenne.thumper

import com.everettpenne.thumper.music.data.Track
import com.everettpenne.thumper.music.data.albumsOf
import com.everettpenne.thumper.music.data.artistsOf
import com.everettpenne.thumper.music.data.legacyArtKeys
import com.everettpenne.thumper.music.util.primaryArtist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumGroupingTest {
    private fun track(
        id: String,
        artist: String,
        album: String = "Isolation",
        folder: String = "folder:isolation",
        disc: Int = 0,
        trackNo: Int = 0,
        albumArtist: String? = null,
    ) = Track(
        id = id,
        uri = "content://$id",
        title = id,
        artist = artist,
        album = album,
        albumArtist = albumArtist,
        discNo = disc,
        trackNo = trackNo,
        folder = folder,
        durationMs = 1_000,
    )

    @Test fun guestCreditsStayOnOneAlbum() {
        val tracks = listOf(
            track("1", "Kali Uchis"),
            track("2", "Kali Uchis Feat BIA"),
            track("3", "Kali Uchis Feat. Tyler, the Creator"),
            track("4", "Kali Uchis Feat. Reykon"),
            track("5", "Kali Uchis Feat. Jorja Smith"),
            track("6", "Kali Uchis Feat Steve Lacy"),
        )
        val albums = albumsOf(tracks)
        assertEquals(1, albums.size)
        assertEquals("Kali Uchis", albums.single().artist)
        assertEquals(6, albums.single().tracks.size)
        assertEquals(1, tracks.map { it.albumKey }.distinct().size)
    }

    @Test fun anAlbumArtistTagWinsOverTrackCredits() {
        val tracks = listOf(
            track("1", "Kali Uchis", albumArtist = "Kali Uchis"),
            track("2", "Tyler, the Creator", albumArtist = "Kali Uchis"),
        )
        assertEquals("Kali Uchis", albumsOf(tracks).single().artist)
    }

    @Test fun variousArtistsIsACompilation() {
        val album = albumsOf(
            listOf(
                track("1", "A", albumArtist = "Various Artists"),
                track("2", "B", albumArtist = "Various Artists"),
            ),
        ).single()
        assertEquals("Various Artists", album.artist)
        assertTrue(album.compilation)
    }

    @Test fun discsComeBeforeTrackNumbers() {
        val tracks = listOf(
            track("d2t1", "X", disc = 2, trackNo = 1),
            track("d1t2", "X", disc = 1, trackNo = 2),
            track("d1t1", "X", disc = 1, trackNo = 1),
        )
        assertEquals(listOf("d1t1", "d1t2", "d2t1"), albumsOf(tracks).single().tracks.map { it.id })
    }

    @Test fun differentFoldersWithTheSameTitleStayApart() {
        val albums = albumsOf(
            listOf(
                track("1", "A", folder = "folder:a"),
                track("2", "B", folder = "folder:b"),
            ),
        )
        assertEquals(2, albums.size)
    }

    @Test fun primaryArtistRemovesGuests() {
        assertEquals("Kali Uchis", primaryArtist("Kali Uchis Feat. Tyler, the Creator"))
        assertEquals("Kali Uchis", primaryArtist("Kali Uchis Feat BIA"))
        assertEquals("Kali Uchis", primaryArtist("Kali Uchis ft. Steve Lacy"))
        assertEquals("Earth, Wind & Fire", primaryArtist("Earth, Wind & Fire"))
    }

    @Test fun legacyArtKeysCoverTheOldAndNewAlbumIdentity() {
        val keys = legacyArtKeys(track("1", "Kali Uchis Feat BIA", folder = "folder:isolation"))
        assertTrue("d:Isolation|Kali Uchis Feat BIA" in keys)
        assertTrue("d:Isolation|Kali Uchis" in keys)
        assertTrue("d:folder:isolation|isolation" in keys)
    }

    @Test fun artistPagesGatherGuests() {
        val artists = artistsOf(listOf(track("1", "Kali Uchis"), track("2", "Kali Uchis Feat BIA")))
        assertEquals(1, artists.size)
        assertEquals(2, artists.single().tracks.size)
    }
}
