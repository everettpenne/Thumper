// Media3 marks parts of its API "unstable" (they may change between versions); these are used on purpose and pinned by the version in the build.
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.everettpenne.thumper.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.everettpenne.thumper.data.Library

/**
 * Remembers where the listener is: every few seconds while playing, when they pause or stop, when the episode changes and when it ends. It follows
 * the player rather than the screen, so it keeps working with the app in the background.
 */
class ProgressSaver(private val player: Player, private val library: Library) : Player.Listener {
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { save(); handler.postDelayed(this, 5_000) } }
    init { handler.postDelayed(tick, 5_000) }

    private fun save(item: MediaItem? = player.currentMediaItem, position: Long = player.currentPosition) {
        val id = item?.mediaId?.takeIf { it.isNotEmpty() } ?: return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        if (position > 0 || duration > 0) library.updateProgress(id, position, duration)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) { save() }
    override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) player.currentMediaItem?.mediaId?.takeIf { it.isNotEmpty() }?.let { library.updateProgress(it, player.duration.coerceAtLeast(0), player.duration.coerceAtLeast(0)) } }

    /** An item replaced or cleared: save the old one where it stopped. */
    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        val changedItem = oldPosition.mediaItem?.mediaId != newPosition.mediaItem?.mediaId
        if (reason == Player.DISCONTINUITY_REASON_REMOVE || reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION || (reason == Player.DISCONTINUITY_REASON_SEEK && changedItem)) save(oldPosition.mediaItem, oldPosition.positionMs)
    }

    fun finish() { handler.removeCallbacks(tick); save() }
}
