package com.everettpenne.thumper.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.everettpenne.thumper.data.Library
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Keeps the player's upcoming items equal to Up Next. The library's queue is the one truth: this runs in the playback service, so the next episode starts
 * by itself when one ends (and the notification's and a headset's "next" work) even with the app closed.
 *
 * The playlist is always the episode playing plus what is queued after it. When a queued episode becomes the current one (it started by itself, or
 * "next" was pressed) it leaves the queue; one that was listened to part-way picks up where it was left.
 */
class QueueSync(private val player: Player, private val library: Library, private val local: LocalFiles?) : Player.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init { scope.launch { library.queue.collect { sync() } } }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val id = mediaItem?.mediaId?.takeIf { it.isNotEmpty() } ?: return
        library.dequeue(id)
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
            library.progress(id)?.takeIf { !it.played && it.positionMs > 5_000 }?.let { player.seekTo(it.positionMs) }
        }
        if (player.currentMediaItemIndex > 0) player.removeMediaItems(0, player.currentMediaItemIndex)       // what has been played is not kept
        sync()
    }

    private fun sync() {
        if (player.mediaItemCount == 0) return
        val current = player.currentMediaItemIndex
        val currentId = player.currentMediaItem?.mediaId
        val wanted = library.queue.value.filter { it.episode.id != currentId }.map { EpisodeMedia.item(it.episode, it.feed, local) }
        val have = (current + 1 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        if (have == wanted.map { it.mediaId }) return
        if (player.mediaItemCount > current + 1) player.removeMediaItems(current + 1, player.mediaItemCount)
        if (wanted.isNotEmpty()) player.addMediaItems(wanted)
    }

    fun release() { scope.cancel() }
}
