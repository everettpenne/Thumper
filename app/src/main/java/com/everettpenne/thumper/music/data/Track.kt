package com.everettpenne.thumper.music.data

import kotlinx.serialization.Serializable
import com.everettpenne.thumper.music.util.primaryArtist

const val SOURCE_LOCAL = "local"
const val SOURCE_JELLYFIN = "jellyfin"

/**
 * One piece of music, however it was found: a row in Android's media index or a document in a folder the person granted.
 *
 * [id] is stable across rescans (`m:<media id>` or `d:<document id>`), because playlists, favourites and "jump back in" all remember
 * tracks by it. [albumKey] is what album art is cached under: the media index's album id, or the album and artist for a folder.
 */
@Serializable
data class Track(
    val id: String,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long = 0L,
    /** The album's own artist (TPE2), when the tags have one; otherwise the album is gathered under the main artist of its tracks. */
    val albumArtist: String? = null,
    /** The disc number in a multi-disc set, 0 when unknown. */
    val discNo: Int = 0,
    val genre: String? = null,
    /** For a folder scan, the album folder; it is what keeps a guest-heavy album together. Empty for the media index. */
    val folder: String = "",
    val durationMs: Long = 0L,
    val trackNo: Int = 0,
    val year: Int = 0,
    val addedAt: Long = 0L,
    val mime: String? = null,
    val fileName: String = "",
    /** A cover image sitting beside the file (cover.jpg and friends), when the folder scan found one. */
    val coverUri: String? = null,
    /** "local" for the phone's own library, "jellyfin" for a track on a connected server. */
    val source: String = SOURCE_LOCAL,
    /** The Jellyfin item id, and the album/artwork ids that go with it; null for local tracks. */
    val remoteId: String? = null,
    val remoteAlbumId: String? = null,
    val artUrl: String? = null,
) {
    val albumKey: String
        get() = if (source == SOURCE_JELLYFIN) {
            "jf:${remoteAlbumId ?: remoteId}"
        } else {
            when {
                albumId > 0L -> "m$albumId"
                folder.isNotBlank() -> "d:$folder|${album.trim().lowercase()}"
                else -> "d:${album.trim().lowercase()}|${albumArtist?.takeIf { it.isNotBlank() } ?: primaryArtist(artist)}"
            }
        }
}

/**
 * The album keys this track's cover may have been cached under before album grouping changed. The first is the old exact
 * album/artist key; later ones cover the album artist, the main artist and the new folder key. Used only to migrate existing art.
 */
fun legacyArtKeys(track: Track): List<String> = buildList {
    if (track.source == SOURCE_JELLYFIN) return emptyList()
    if (track.albumId > 0L) add("m${track.albumId}")
    val album = track.album
    val lower = album.trim().lowercase()
    val artists = linkedSetOf<String>()
    if (track.artist.isNotBlank()) artists += track.artist
    track.albumArtist?.takeIf { it.isNotBlank() }?.let { artists += it }
    primaryArtist(track.artist).takeIf { it.isNotBlank() }?.let { artists += it }
    for (artist in artists) {
        add("d:$album|$artist")
        add("d:$lower|$artist")
    }
    if (track.folder.isNotBlank()) add("d:${track.folder}|$lower")
}

/** An album, gathered from its tracks, with the track whose file the artwork is read from. */
data class Album(
    val key: String,
    val title: String,
    val artist: String,
    val year: Int,
    val artTrack: Track,
    val tracks: List<Track>,
    /** True when the album is credited to several artists, so its artist line is not a link to one artist page. */
    val compilation: Boolean = false,
) {
    val durationMs: Long get() = tracks.sumOf { it.durationMs }
}

/** An artist, gathered from every track credited to them. */
data class Artist(val name: String, val tracks: List<Track>) {
    val albums: List<Album> get() = albumsOf(tracks)
}

/** The saved library: what was found, where, and when. */
@Serializable
data class LibrarySnapshot(
    val version: Int = 1,
    val source: String = SOURCE_MEDIA,
    val scannedAt: Long = 0L,
    val tracks: List<Track> = emptyList(),
) {
    companion object {
        const val SOURCE_MEDIA = "media"
        const val SOURCE_FOLDER = "folder"
    }
}

/** How far a scan has got, for the progress line on the welcome and settings pages. */
data class ScanState(
    val running: Boolean = false,
    val phase: String = "",
    val found: Int = 0,
    val done: Int = 0,
    val total: Int = 0,
    val error: String? = null,
)

/**
 * Albums in alphabetical order, each with its tracks in disc then track order. Only tracks with a playable duration are counted, so the
 * album count matches what the library can actually play.
 */
fun albumsOf(tracks: List<Track>): List<Album> = tracks
    .filter { it.album.isNotBlank() }
    .groupBy { it.albumKey }
    .map { (key, list) ->
        val sorted = list.sortedWith(compareBy({ it.discNo }, { it.trackNo }, { it.title.lowercase() }))
        val albumArtist = mostCommon(list.mapNotNull { it.albumArtist?.trim()?.takeIf(String::isNotBlank) })
        val artist = albumArtist ?: mostCommon(list.map { primaryArtist(it.artist) }) ?: sorted.first().artist
        Album(
            key = key,
            title = sorted.first().album,
            artist = artist,
            year = list.firstOrNull { it.year > 0 }?.year ?: 0,
            artTrack = sorted.first(),
            tracks = sorted,
            compilation = albumArtist?.equals("Various Artists", ignoreCase = true) == true,
        )
    }
    .sortedWith(compareBy({ it.title.lowercase() }, { it.artist.lowercase() }))

/** The value that appears most often, preferring the first one in a tie; null for an empty list. */
private fun mostCommon(values: List<String>): String? {
    if (values.isEmpty()) return null
    val counts = values.groupingBy { it }.eachCount()
    val best = counts.values.maxOrNull() ?: return null
    return values.firstOrNull { counts[it] == best }
}

/** Artists in alphabetical order, with "Unknown artist" last rather than filed under U. */
fun artistsOf(tracks: List<Track>): List<Artist> = tracks
    .filter { it.artist.isNotBlank() }
    .groupBy { primaryArtist(it.artist) }
    .map { (name, list) -> Artist(name, list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })) }
    .sortedWith(compareBy<Artist> { it.name == UNKNOWN_ARTIST }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })

const val UNKNOWN_ARTIST = "Unknown artist"
const val UNKNOWN_ALBUM = "Unknown album"

/** Songs in alphabetical order, the way a phone's music app lists them. */
fun songsOf(tracks: List<Track>): List<Track> =
    tracks.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })

/** This track as the one shared player sees it. The gain is the music library's measurement, or 1.0 when it has none. */
fun Track.toPlayable(gain: Float = 1f, artworkUri: String? = null, artUrl: String? = null): com.everettpenne.thumper.data.model.Playable.Music =
    com.everettpenne.thumper.data.model.Playable.Music(
        trackId = id,
        title = title,
        artist = artist,
        album = album,
        uri = uri,
        durationMs = durationMs,
        artworkUrl = artworkUri,
        albumId = albumKey,
        source = source,
        gain = gain,
        remoteId = remoteId,
        artUrl = artUrl ?: this.artUrl,
    )
