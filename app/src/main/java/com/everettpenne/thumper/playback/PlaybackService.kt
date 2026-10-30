package com.everettpenne.thumper.playback

import android.app.PendingIntent
import android.content.Intent
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.everettpenne.thumper.MainActivity
import com.everettpenne.thumper.data.model.Playable
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
 * Owns the sound for everything Thumper plays: episodes, downloaded episodes, music from the phone and music from Jellyfin.
 *
 * One MediaSession and one queue sit in front of two ExoPlayers. Normally the active player plays the current item and the idle one
 * waits. When a track is ending and the next thing is also music, the idle player starts that track under the last seconds and the
 * session switches to it when the fade is done; podcast transitions are ordinary transitions. The session, notification, lock screen,
 * mini player and player screen never see the two players, only one queue.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var playerA: ExoPlayer
    private lateinit var playerB: ExoPlayer
    private var active: ExoPlayer? = null
    private val gainA = GainProcessor()
    private val gainB = GainProcessor()
    private val handler = Handler(Looper.getMainLooper())
    private var sleepAtEnd = false
    private val sleepRunnable = Runnable { pauseAll() }
    private var saverA: ProgressSaver? = null
    private var saverB: ProgressSaver? = null
    private var queueA: QueueSync? = null
    private var queueB: QueueSync? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var crossfading = false
    private var fadingFrom: ExoPlayer? = null
    private var fadeStart = 0L
    private var fadeDuration = 0L

    private var hasFocus = false
    private var resumeAfterFocus = false

    private val musicAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    /** AIFF is not in Media3, so our extractor is tried before the standard ones. */
    private val extractors = object : ExtractorsFactory {
        private val defaults = DefaultExtractorsFactory()
        override fun createExtractors(): Array<Extractor> = arrayOf<Extractor>(AiffExtractor()) + defaults.createExtractors()
        override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
            arrayOf<Extractor>(AiffExtractor()) + defaults.createExtractors(uri, responseHeaders)
    }

    private val focusRequest: AudioFocusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> { resumeAfterFocus = false; pauseAll() }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { resumeAfterFocus = isAnythingPlaying(); pauseAll() }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { gainA.duck = 0.2f; gainB.duck = 0.2f }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        gainA.duck = 1f; gainB.duck = 1f
                        if (resumeAfterFocus) { resumeAfterFocus = false; active?.play() }
                    }
                }
            }
            .build()
    }

    private val ticker = object : Runnable {
        override fun run() {
            val current = active
            if (current != null && !crossfading) {
                val fadeMs = graph.musicSettings.crossfadeMs.value
                val next = current.currentMediaItemIndex.takeIf { it >= 0 }?.plus(1)?.takeIf { it < current.mediaItemCount }
                val bothMusic = current.currentMediaItem?.isMusic() == true && next?.let { current.getMediaItemAt(it).isMusic() } == true
                if (fadeMs > 0 && bothMusic && current.isPlaying && current.hasNextMediaItem() && graph.settings.autoplay.value) {
                    val duration = current.duration
                    val remaining = duration - current.currentPosition
                    if (duration > 0 && remaining in 1..fadeMs.toLong()) startFade(current, remaining)
                }
            }
            if (crossfading) advanceFade()
            if (isAnythingPlaying() || crossfading) handler.postDelayed(this, 50)
        }
    }

    private fun MediaItem.isMusic(): Boolean = mediaMetadata.extras?.getString(PlayableMedia.KEY_KIND) == Playable.Music.KIND

    override fun onCreate() {
        super.onCreate()
        playerA = buildPlayer(gainA)
        playerB = buildPlayer(gainB)
        active = playerA
        saverA = ProgressSaver(playerA, graph.library, active = { active === playerA }, music = graph.musicSettings).also { playerA.addListener(it) }
        saverB = ProgressSaver(playerB, graph.library, active = { active === playerB }, music = graph.musicSettings).also { playerB.addListener(it) }
        queueA = QueueSync(playerA, graph.library, graph.localFiles, MusicFiles { graph.jellyfin.downloads.fileFor(it) }, active = { active === playerA }).also { playerA.addListener(it) }
        queueB = QueueSync(playerB, graph.library, graph.localFiles, MusicFiles { graph.jellyfin.downloads.fileFor(it) }, active = { active === playerB }).also { playerB.addListener(it) }
        playerA.addListener(modeListener(playerA))
        playerB.addListener(modeListener(playerB))
        scope.launch { graph.settings.autoplay.collect { playerA.setPauseAtEndOfMediaItems(!it); playerB.setPauseAtEndOfMediaItems(!it) } }
        scope.launch { graph.musicSettings.leveling.collect { applyLevel(playerA, playerA.currentMediaItem); applyLevel(playerB, playerB.currentMediaItem) } }

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, playerA).setSessionActivity(open).setCallback(object : MediaSession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
                MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(PlaybackCommands.SLEEP).build()).build()

            override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> =
                Futures.immediateFuture(mediaItems.map { item -> item.requestMetadata.mediaUri?.let { item.buildUpon().setUri(it).build() } ?: item }.toMutableList())

            override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                if (customCommand.customAction == PlaybackCommands.SLEEP.customAction) setSleep(args.getInt(PlaybackCommands.MINUTES, 0), args.getBoolean(PlaybackCommands.END_OF_EPISODE, false))
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
        }).build()
    }

    private fun buildPlayer(gain: GainProcessor): ExoPlayer {
        // Bugfix, root cause: this factory sent OkHttp's default User-Agent ("okhttp/4.x"), which some podcast hosts treat as a bot and refuse. Buzzsprout's CDN
        // (Tech Won't Save Us and every other Buzzsprout show) answers it with 403 after the pscrb.fm tracking redirects, while the feed itself, the artwork and
        // downloads (all of which send "Thumper/0.1") load fine. Symptom: episodes list and look available, but playing one fails with "Source error".
        // Sending the same Thumper user agent as everywhere else makes the host answer 200 audio/mpeg.
        val dataSource = DefaultDataSource.Factory(this, OkHttpDataSource.Factory(graph.http).setUserAgent("Thumper/0.1 (Android podcast and feed reader)"))
        return ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource, extractors))
            .setRenderersFactory(LevelRenderersFactory(this, gain))
            .setAudioAttributes(musicAttributes, /* handleAudioFocus = */ false)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(graph.settings.skipBackSeconds.value * 1000L)
            .setSeekForwardIncrementMs(graph.settings.skipForwardSeconds.value * 1000L)
            .setMaxSeekToPreviousPositionMs(NextUp.RESTART_AFTER_MS)
            .setPauseAtEndOfMediaItems(false)
            .build()
    }

    private fun modeListener(player: ExoPlayer) = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            applyLevel(player, mediaItem)
            if (crossfading && player === fadingFrom) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) finishFade() else cancelFade()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                requestFocus(); startTicker()
            } else if (!isAnythingPlaying()) {
                abandonFocus(); handler.removeCallbacks(ticker)
            }
        }

        override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) cancelSleep() }
        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            if (sleepAtEnd && reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) { pauseAll(); cancelSleep() }
            if (crossfading && player === fadingFrom && reason == Player.DISCONTINUITY_REASON_SEEK) cancelFade()
        }
        override fun onTimelineChanged(timeline: Timeline, reason: Int) { if (crossfading && player === fadingFrom) cancelFade() }
    }

    private fun isAnythingPlaying(): Boolean = playerA.isPlaying || playerB.isPlaying

    private fun requestFocus() {
        if (hasFocus) return
        hasFocus = audioManager().requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        if (!hasFocus) return
        audioManager().abandonAudioFocusRequest(focusRequest)
        hasFocus = false
        gainA.duck = 1f; gainB.duck = 1f
    }

    private fun audioManager(): AudioManager = getSystemService(AudioManager::class.java)

    private fun applyLevel(player: ExoPlayer, item: MediaItem?) {
        val enabled = graph.musicSettings.leveling.value
        val music = item?.isMusic() == true
        val measured = item?.mediaMetadata?.extras?.getFloat(PlayableMedia.KEY_GAIN, 1f) ?: 1f
        val processor = if (player === playerA) gainA else gainB
        processor.gain = if (enabled && music) measured else 1f
    }

    private fun startTicker() { handler.removeCallbacks(ticker); handler.post(ticker) }

    private fun startFade(from: ExoPlayer, remainingMs: Long) {
        val to = if (from === playerA) playerB else playerA
        if (to.isPlaying || to.playbackState == Player.STATE_BUFFERING) return
        val next = from.nextMediaItemIndex
        if (next == C.INDEX_UNSET || next == from.currentMediaItemIndex) return
        if (from.currentMediaItem?.isMusic() != true || from.getMediaItemAt(next).isMusic() != true) return
        val items = (0 until from.mediaItemCount).map { from.getMediaItemAt(it) }
        runCatching {
            to.stop(); to.clearMediaItems()
            to.setMediaItems(items, next, 0L)
            to.repeatMode = from.repeatMode
            to.volume = 0f
            to.prepare(); to.play()
        }.onFailure { return }
        fadingFrom = from
        crossfading = true
        fadeStart = SystemClock.elapsedRealtime()
        fadeDuration = remainingMs.coerceAtLeast(250L)
        startTicker()
    }

    private fun advanceFade() {
        val from = fadingFrom ?: run { cancelFade(); return }
        val to = if (from === playerA) playerB else playerA
        val progress = ((SystemClock.elapsedRealtime() - fadeStart).toFloat() / fadeDuration).coerceIn(0f, 1f)
        from.volume = 1f - progress
        to.volume = progress
        if (progress >= 1f || !from.isPlaying) finishFade()
    }

    private fun finishFade() {
        val from = fadingFrom ?: return
        val to = if (from === playerA) playerB else playerA
        crossfading = false
        fadingFrom = null
        to.volume = 1f
        from.volume = 1f
        from.pause(); from.stop(); from.clearMediaItems()
        active = to
        to.currentMediaItem?.mediaId?.let { graph.library.dequeue(it) }
        applyLevel(to, to.currentMediaItem)
        session?.setPlayer(to)
        (if (to === playerA) queueA else queueB)?.refresh()
    }

    private fun cancelFade() {
        val from = fadingFrom ?: run { crossfading = false; return }
        val to = if (from === playerA) playerB else playerA
        crossfading = false
        fadingFrom = null
        from.volume = 1f
        to.pause(); to.volume = 1f; to.clearMediaItems()
    }

    private fun pauseAll() {
        if (crossfading) cancelFade()
        playerA.pause(); playerB.pause()
    }

    private fun setSleep(minutes: Int, atEnd: Boolean) {
        cancelSleep()
        sleepAtEnd = atEnd
        if (minutes > 0) handler.postDelayed(sleepRunnable, minutes * 60_000L)
    }

    private fun cancelSleep() { handler.removeCallbacks(sleepRunnable); sleepAtEnd = false }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Music keeps going when the app is swiped away; if nothing is playing there is no reason to stay. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!isAnythingPlaying()) pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        cancelSleep(); saverA?.finish(); saverB?.finish(); queueA?.release(); queueB?.release(); scope.cancel()
        playerA.release(); playerB.release()
        session?.release(); session = null
        super.onDestroy()
    }
}
