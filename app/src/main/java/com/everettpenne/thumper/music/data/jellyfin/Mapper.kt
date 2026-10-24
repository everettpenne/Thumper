package com.everettpenne.thumper.music.data.jellyfin

import com.everettpenne.thumper.music.data.SOURCE_JELLYFIN
import com.everettpenne.thumper.music.data.Track

/** Turns a Jellyfin audio item into the player's own track, with the stream URL and the artwork address. */
fun JfItem.toTrack(urls: MediaUrls): Track = Track(
    id = "jf:$id",
    uri = urls.audioStream(id, mediaSources.firstOrNull()?.id).orEmpty(),
    title = name.ifBlank { "Untitled" },
    artist = musicArtist ?: albumArtists.firstOrNull() ?: "Unknown artist",
    album = album ?: name.ifBlank { "Unknown album" },
    albumArtist = albumArtists.firstOrNull(),
    discNo = parentIndex ?: 0,
    trackNo = index ?: 0,
    year = year ?: 0,
    durationMs = (runTimeTicks ?: 0L) / TICKS_PER_SECOND * 1000L,
    mime = "audio/*",
    fileName = name,
    source = SOURCE_JELLYFIN,
    remoteId = id,
    remoteAlbumId = albumId ?: id,
    artUrl = urls.poster(this, 500),
)

/** A downloaded copy of the same item: the file is local, so it plays with no server and no token. */
fun SavedItem.toTrack(downloads: Downloads, urls: MediaUrls): Track? {
    val file = downloads.fileFor(item.id) ?: return null
    return item.toTrack(urls).copy(uri = android.net.Uri.fromFile(file).toString())
}
