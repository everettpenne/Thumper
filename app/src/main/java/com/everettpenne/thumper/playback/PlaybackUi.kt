package com.everettpenne.thumper.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.everettpenne.thumper.data.Library
import com.everettpenne.thumper.data.Settings
import com.everettpenne.thumper.data.model.Episode
import com.everettpenne.thumper.data.model.Feed
import com.everettpenne.thumper.graph
import kotlinx.coroutines.delay

/**
 * What the screens know about playback, and how they drive it. The player itself lives in [PlaybackService]; this holds a controller to it and mirrors
 * its state into Compose state, so the mini player, the player screen and the episode rows all read the same thing. Everything is derived from the
 * playing item's own metadata, so after the app is reopened with something still playing it picks that up.
 */
@Stable
class PlaybackUi(private val library: Library, private val settings: Settings, private val local: () -> LocalFiles?) {
    var controller by mutableStateOf<MediaController?>(null); internal set
    var episodeId by mutableStateOf<String?>(null); private set
    var feedId by mutableStateOf<String?>(null); private set
    var title by mutableStateOf(""); private set
    var showTitle by mutableStateOf(""); private set
    var artworkUrl by mutableStateOf<String?>(null); private set
    var isPlaying by mutableStateOf(false); private set
    var buffering by mutableStateOf(false); private set
    var positionMs by mutableLongStateOf(0); private set
    var durationMs by mutableLongStateOf(0); private set
    var speed by mutableFloatStateOf(1f); private set
    var sleepLabel by mutableStateOf<String?>(null); private set
    /** True while the playing item has a picture (a video podcast), with its width over height. */
    var hasVideo by mutableStateOf(false); private set
    var videoRatio by mutableFloatStateOf(16f / 9f); private set

    /** True when an episode is loaded (playing or paused): the mini player shows. */
    val hasEpisode: Boolean get() = episodeId != null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { sync(player) }
    }

    internal fun attach(c: MediaController) { controller = c; c.addListener(listener); sync(c) }
    internal fun detach() { controller?.removeListener(listener); controller = null }

    private fun sync(p: Player) {
        val item = p.currentMediaItem
        episodeId = item?.mediaId?.takeIf { it.isNotEmpty() }
        feedId = item?.mediaMetadata?.extras?.getString(KEY_FEED)
        title = item?.mediaMetadata?.title?.toString().orEmpty(); showTitle = item?.mediaMetadata?.artist?.toString().orEmpty()
        artworkUrl = item?.mediaMetadata?.artworkUri?.toString()
        isPlaying = p.isPlaying; buffering = p.playbackState == Player.STATE_BUFFERING
        positionMs = p.currentPosition.coerceAtLeast(0); durationMs = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: durationMs
        speed = p.playbackParameters.speed
        hasVideo = p.currentTracks.containsType(C.TRACK_TYPE_VIDEO); videoRatio = com.everettpenne.media.videoAspectRatio(p)
    }

    /** Called about once a second while something plays (and once when a screen that shows the clock opens). */
    fun tick() { controller?.let { sync(it) } }

    fun play(episode: Episode, feed: Feed) {
        val c = controller ?: return
        if (c.currentMediaItem?.mediaId == episode.id) { if (!c.isPlaying) c.play(); return }
        val saved = library.progress(episode.id)
        val start = saved?.takeIf { !it.played && it.durationMs > 0 && it.positionMs < it.durationMs - 10_000 }?.positionMs ?: 0L
        library.dequeue(episode.id)                                   // playing it now: it is no longer "up next"
        c.setMediaItem(EpisodeMedia.item(episode, feed, local()), start)
        c.setPlaybackSpeed(settings.speed.value)
        c.prepare(); c.play()
    }

    /** Skips to the next queued episode (the notification's and a headset's "next" do the same). */
    fun next() { controller?.seekToNextMediaItem() }
    fun toggle() { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun skipBack() { controller?.seekBack(); tick() }
    fun skipForward() { controller?.seekForward(); tick() }
    fun seekTo(ms: Long) { controller?.seekTo(ms); positionMs = ms }
    fun changeSpeed(v: Float) { controller?.setPlaybackSpeed(v); speed = v; settings.setSpeed(v) }
    fun stop() { controller?.let { it.stop(); it.clearMediaItems() } }

    /** minutes > 0: pause after that long; endOfEpisode: pause when it ends; neither: no timer. */
    fun sleep(minutes: Int = 0, endOfEpisode: Boolean = false) {
        controller?.sendCustomCommand(PlaybackCommands.SLEEP, Bundle().apply { putInt(PlaybackCommands.MINUTES, minutes); putBoolean(PlaybackCommands.END_OF_EPISODE, endOfEpisode) })
        sleepLabel = when { endOfEpisode -> "End of episode"; minutes > 0 -> "$minutes min"; else -> null }
    }

    companion object { const val KEY_FEED = EpisodeMedia.KEY_FEED }
}

val LocalPlayback = staticCompositionLocalOf<PlaybackUi> { error("no PlaybackUi provided") }

/** Connects to the playback service for as long as the app's UI is up, and keeps the clock moving while something plays. */
@Composable
fun rememberPlaybackUi(): PlaybackUi {
    val context = LocalContext.current
    val graph = context.graph
    val ui = remember { PlaybackUi(graph.library, graph.settings) { graph.localFiles } }
    DisposableEffect(Unit) {
        val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        future.addListener({ runCatching { future.get() }.getOrNull()?.let(ui::attach) }, ContextCompat.getMainExecutor(context))
        onDispose { ui.detach(); MediaController.releaseFuture(future) }
    }
    LaunchedEffect(ui.isPlaying) { while (ui.isPlaying) { ui.tick(); delay(1000) } }
    return ui
}
