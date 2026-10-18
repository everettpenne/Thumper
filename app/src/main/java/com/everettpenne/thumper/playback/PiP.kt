package com.everettpenne.thumper.playback

/**
 * Picture-in-picture policy for video episodes. Kept free of Android and of the player, so the one "does leaving the app float this
 * episode?" rule lives in a single place the JVM tests can drive: a video episode floats, and only while it is actually playing.
 * Audio episodes never float — they already carry on through the media notification, and their player screen is audio-only.
 */
object PiP {
    /** True when leaving the app should float the playing episode in a small window. */
    fun shouldEnter(isVideo: Boolean, isPlaying: Boolean): Boolean = isVideo && isPlaying
}

/**
 * The one fact [com.everettpenne.thumper.MainActivity] reads in its onUserLeaveHint: a video episode is playing right now.
 * [PlaybackUi] mirrors it here on every player sync. It is process-wide so the activity can reach it without reaching into the
 * Compose tree, and it is only consulted at the moment the user leaves, so it is never a source of new state to persist.
 */
object PiPState {
    @Volatile var videoPlaying: Boolean = false
}
