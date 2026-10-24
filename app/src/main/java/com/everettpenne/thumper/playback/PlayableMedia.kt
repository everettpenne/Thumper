package com.everettpenne.thumper.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.everettpenne.thumper.data.model.Playable
import java.io.File

/** Where a song is on this phone, if it has been downloaded, and the gain Baliset measured for it. */
fun interface MusicFiles {
    fun fileFor(trackId: String): File?
}

/**
 * Turns either kind of queue entry into the one kind of thing Media3 plays. The source (feed, phone, Jellyfin, download) is already
 * resolved into [Playable] and its local file lookup; after this, the player does not care where it came from.
 */
object PlayableMedia {
    const val KEY_KIND = "thumper.kind"
    const val KEY_FEED = "thumper.feed"
    const val KEY_EPISODE = "thumper.episode"
    const val KEY_TRACK = "thumper.track"
    const val KEY_ALBUM = "thumper.album"
    const val KEY_SOURCE = "thumper.source"
    const val KEY_GAIN = "thumper.gain"

    fun item(playable: Playable, episodes: LocalFiles? = null, music: MusicFiles? = null): MediaItem = when (playable) {
        is Playable.Podcast -> podcast(playable, episodes)
        is Playable.Music -> song(playable, music)
    }

    private fun podcast(entry: Playable.Podcast, local: LocalFiles?): MediaItem {
        val episode = entry.episode
        val file = local?.fileFor(episode.id)
        val uri = if (file != null) Uri.fromFile(file).toString() else episode.audioUrl
        val metadata = MediaMetadata.Builder()
            .setTitle(episode.title)
            .setArtist(entry.feed.title)
            .setAlbumTitle(entry.feed.title)
            .setArtworkUri((episode.imageUrl ?: entry.feed.imageUrl)?.let(Uri::parse))
            .setExtras(Bundle().apply {
                putString(KEY_KIND, Playable.Podcast.KIND)
                putString(KEY_FEED, entry.feed.id)
                putString(KEY_EPISODE, episode.id)
            })
            .build()
        return MediaItem.Builder().setMediaId(entry.id).setUri(uri).setMediaMetadata(metadata)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(uri)).build())
            .build()
    }

    private fun song(entry: Playable.Music, local: MusicFiles?): MediaItem {
        val file = local?.fileFor(entry.remoteId ?: entry.trackId)
        val uri = if (file != null) Uri.fromFile(file).toString() else entry.uri
        val artwork = (entry.artworkUrl ?: entry.artUrl)?.let(Uri::parse)
        val metadata = MediaMetadata.Builder()
            .setTitle(entry.title)
            .setArtist(entry.artist)
            .setAlbumTitle(entry.album)
            .setAlbumArtist(entry.artist)
            .setArtworkUri(artwork)
            .setExtras(Bundle().apply {
                putString(KEY_KIND, Playable.Music.KIND)
                putString(KEY_TRACK, entry.trackId)
                entry.albumId?.let { putString(KEY_ALBUM, it) }
                putString(KEY_SOURCE, entry.source)
                putFloat(KEY_GAIN, entry.gain)
            })
            .build()
        return MediaItem.Builder().setMediaId(entry.id).setUri(uri).setMediaMetadata(metadata)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(uri)).build())
            .build()
    }
}
