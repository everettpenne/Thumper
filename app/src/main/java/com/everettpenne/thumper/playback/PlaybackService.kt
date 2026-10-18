package com.everettpenne.thumper.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.everettpenne.thumper.MainActivity
import com.everettpenne.thumper.graph
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

object PlaybackCommands {
    /** Sets the sleep timer: [MINUTES] > 0 pauses after that long, [END_OF_EPISODE] pauses when the episode ends, 0 cancels it. */
    val SLEEP = SessionCommand("thumper.sleep", Bundle.EMPTY)
    const val MINUTES = "minutes"
    const val END_OF_EPISODE = "endOfEpisode"
}

/**
 * Owns the player, so listening outlives the screen: switch apps or lock the phone and the sound carries on, with a media notification. It saves
 * where the listener is (to the library, every few seconds, on pause and on stop), so an episode resumes where it was left, and it runs the sleep
 * timer. Swiping the app away from recents ends playback.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private var sleepAtEnd = false
    private val sleepRunnable = Runnable { session?.player?.pause() }
    private var saver: ProgressSaver? = null
    private var queue: QueueSync? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        val graph = applicationContext.graph
        val player = ExoPlayer.Builder(this)
            // DefaultDataSource: the network through OkHttp, and files (a downloaded episode) from the phone
            .setMediaSourceFactory(DefaultMediaSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(this, OkHttpDataSource.Factory(graph.http))))
            // Speech, not music: the system treats it accordingly (another app's short sound ducks it rather than pausing it).
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(graph.settings.skipBackSeconds.value * 1000L).setSeekForwardIncrementMs(graph.settings.skipForwardSeconds.value * 1000L)
            // "Previous" restarts the current episode once past this point, matching the app's own previous-episode rule.
            .setMaxSeekToPreviousPositionMs(NextUp.RESTART_AFTER_MS)
            .build()
        // Autoplay ON lets the next episode start by itself; OFF pauses at the end of the current one (the next stays available to "next").
        player.setPauseAtEndOfMediaItems(!graph.settings.autoplay.value)
        scope.launch { graph.settings.autoplay.collect { player.setPauseAtEndOfMediaItems(!it) } }
        saver = ProgressSaver(player, graph.library).also { player.addListener(it) }
        queue = QueueSync(player, graph.library, graph.localFiles).also { player.addListener(it) }
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) cancelSleep() }
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) { if (sleepAtEnd && reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) { player.pause(); cancelSleep() } }
        })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setSessionActivity(open).setCallback(object : MediaSession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
                MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(PlaybackCommands.SLEEP).build()).build()

            /** Controllers send items without their address (it does not cross the session boundary); it travels in the request metadata. */
            override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> =
                Futures.immediateFuture(mediaItems.map { item -> item.requestMetadata.mediaUri?.let { item.buildUpon().setUri(it).build() } ?: item }.toMutableList())

            override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                if (customCommand.customAction == PlaybackCommands.SLEEP.customAction) setSleep(args.getInt(PlaybackCommands.MINUTES, 0), args.getBoolean(PlaybackCommands.END_OF_EPISODE, false))
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
        }).build()
    }

    private fun setSleep(minutes: Int, atEnd: Boolean) {
        cancelSleep()
        sleepAtEnd = atEnd
        if (minutes > 0) handler.postDelayed(sleepRunnable, minutes * 60_000L)
    }
    private fun cancelSleep() { handler.removeCallbacks(sleepRunnable); sleepAtEnd = false }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onTaskRemoved(rootIntent: Intent?) { pauseAllPlayersAndStopSelf() }

    override fun onDestroy() {
        cancelSleep(); saver?.finish(); queue?.release(); scope.cancel()
        session?.let { it.player.release(); it.release() }
        session = null
        super.onDestroy()
    }
}
