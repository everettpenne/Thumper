package com.everettpenne.thumper.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.everettpenne.thumper.data.model.Episode
import com.everettpenne.thumper.data.model.Feed
import java.io.File

/** Where an episode's audio is on this phone, if it has been downloaded. */
fun interface LocalFiles { fun fileFor(episodeId: String): File? }

/** How an episode becomes something the player can play. One place, used by the screens and by the service, so a queued episode plays exactly as one started by hand. */
object EpisodeMedia {
    const val KEY_FEED = "thumper.feed"

    /** The downloaded file when there is one (so it plays with no network), otherwise the episode's own address. */
    fun item(episode: Episode, feed: Feed, local: LocalFiles?): MediaItem {
        val file = local?.fileFor(episode.id)
        val uri = if (file != null) Uri.fromFile(file).toString() else episode.audioUrl
        val metadata = MediaMetadata.Builder().setTitle(episode.title).setArtist(feed.title).setAlbumTitle(feed.title)
            .setArtworkUri((episode.imageUrl ?: feed.imageUrl)?.let { Uri.parse(it) })
            .setExtras(Bundle().apply { putString(KEY_FEED, feed.id) }).build()
        return MediaItem.Builder().setMediaId(episode.id).setUri(uri).setMediaMetadata(metadata)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(uri)).build()).build()
    }
}
