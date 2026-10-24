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
 * Keeps the player's upcoming items equal to what comes next: Up Next when there is a queue, otherwise the next unplayed episode of the same show. The
 * library's queue is the one truth: this runs in the playback service, so the next episode starts by itself when one ends (and the notification's and a
 * headset's "next" work) even with the app closed.
 *
 * The playlist is the episode playing plus what follows; what has already played is kept in front of it, so the notification's and a headset's "previous"
 * step back to the episode you were just on. When a queued episode becomes the current one (it started by itself, or "next" was pressed) it leaves the
 * queue; one that was listened to part-way picks up where it was left.
 */
class QueueSync(
    private val player: Player,
    private val library: Library,
    private val local: LocalFiles?,
    private val music: MusicFiles? = null,
    private val active: () -> Boolean = { true },
) : Player.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        scope.launch { library.queue.collect { sync() } }
        // A refresh can bring the show's next episode into view; follow it so "next" is there the moment it exists.
        scope.launch { library.contents.collect { sync() } }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (!active()) return
        val id = mediaItem?.mediaId?.takeIf { it.isNotEmpty() } ?: return
        library.dequeue(id)
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
            library.progress(id)?.takeIf { !it.played && it.positionMs > 5_000 }?.let { player.seekTo(it.positionMs) }
        }
        sync()
    }

    fun refresh() { sync() }

    private fun sync() {
        if (!active() || player.mediaItemCount == 0) return
        val current = player.currentMediaItemIndex
        val currentId = player.currentMediaItem?.mediaId
        val queued = library.queue.value.filter { it.id != currentId }
        val wanted = when {
            queued.isNotEmpty() -> queued.map { PlayableMedia.item(it, local, music) }
            else -> {
                val feedId = player.currentMediaItem?.mediaMetadata?.extras?.getString(PlayableMedia.KEY_FEED)
                NextUp.next(currentId, feedId, library.queue.value, library.contents.value, library.progress.value)
                    ?.let { listOf(PlayableMedia.item(it, local, music)) } ?: emptyList()
            }
        }
        val have = (current + 1 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        if (have == wanted.map { it.mediaId }) return
        if (player.mediaItemCount > current + 1) player.removeMediaItems(current + 1, player.mediaItemCount)
        if (wanted.isNotEmpty()) player.addMediaItems(wanted)
    }

    fun release() { scope.cancel() }
}
