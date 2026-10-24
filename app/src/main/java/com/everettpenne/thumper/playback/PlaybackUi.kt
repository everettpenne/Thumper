// Media3 marks parts of its API "unstable" (they may change between versions); these are used on purpose and pinned by the version in the build.
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.everettpenne.thumper.data.Library
import com.everettpenne.thumper.data.Settings
import com.everettpenne.thumper.data.model.Episode
import com.everettpenne.thumper.data.model.Feed
import com.everettpenne.thumper.data.model.Playable
import com.everettpenne.thumper.graph
import kotlinx.coroutines.delay

/**
 * What the screens know about playback, and how they drive it. The player itself lives in [PlaybackService]; this holds a controller to it and mirrors
 * its state into Compose state, so the mini player, the player screen and the episode rows all read the same thing. Everything is derived from the
 * playing item's own metadata, so after the app is reopened with something still playing it picks that up.
 */
@Stable
class PlaybackUi(private val library: Library, private val settings: Settings, private val local: () -> LocalFiles?, private val musicFiles: () -> MusicFiles? = { null }) {
    var controller by mutableStateOf<MediaController?>(null); internal set
    var episodeId by mutableStateOf<String?>(null); private set
    var feedId by mutableStateOf<String?>(null); private set
    /** What the current item is: podcast or music. The player screen branches on this; the queue and service do not. */
    var kind by mutableStateOf<String?>(null); private set
    var trackId by mutableStateOf<String?>(null); private set
    var albumId by mutableStateOf<String?>(null); private set
    var title by mutableStateOf(""); private set
    var showTitle by mutableStateOf(""); private set
    var artworkUrl by mutableStateOf<String?>(null); private set
    var isPlaying by mutableStateOf(false); private set
    var buffering by mutableStateOf(false); private set
    /** Why playback last stopped, in words to show, or null when all is well. Set when the player fails, cleared when it recovers. */
    var error by mutableStateOf<String?>(null); private set
    var positionMs by mutableLongStateOf(0); private set
    var durationMs by mutableLongStateOf(0); private set
    var speed by mutableFloatStateOf(1f); private set
    var sleepLabel by mutableStateOf<String?>(null); private set
    /** When a minutes sleep timer runs out (wall clock), so its chip can count down; 0 when there is none. */
    private var sleepEndsAt = 0L
    /** True while the playing item has a picture (a video podcast), with its width over height. */
    var hasVideo by mutableStateOf(false); private set
    var videoRatio by mutableFloatStateOf(16f / 9f); private set
    /** True when there is a next episode to skip to (a queued item, or the next unplayed one of the same show). */
    var hasNext by mutableStateOf(false); private set

    /** True when something is loaded (playing or paused): the mini player shows. */
    val hasEpisode: Boolean get() = episodeId != null
    val hasMedia: Boolean get() = hasEpisode
    val isMusic: Boolean get() = kind == Playable.Music.KIND

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { sync(player) }
        override fun onPlayerError(error: PlaybackException) { this@PlaybackUi.error = error.message?.takeIf { it.isNotBlank() } ?: error.errorCodeName }
    }

    internal fun attach(c: MediaController) { controller = c; c.addListener(listener); sync(c) }
    internal fun detach() { controller?.removeListener(listener); controller = null }

    private fun sync(p: Player) {
        val item = p.currentMediaItem
        episodeId = item?.mediaId?.takeIf { it.isNotEmpty() }
        feedId = item?.mediaMetadata?.extras?.getString(KEY_FEED)
        kind = item?.mediaMetadata?.extras?.getString(PlayableMedia.KEY_KIND)
        trackId = item?.mediaMetadata?.extras?.getString(PlayableMedia.KEY_TRACK)
        albumId = item?.mediaMetadata?.extras?.getString(PlayableMedia.KEY_ALBUM)
        title = item?.mediaMetadata?.title?.toString().orEmpty(); showTitle = item?.mediaMetadata?.artist?.toString().orEmpty()
        artworkUrl = item?.mediaMetadata?.artworkUri?.toString()
        isPlaying = p.isPlaying; buffering = p.playbackState == Player.STATE_BUFFERING
        if (p.playbackState == Player.STATE_READY) error = null
        positionMs = p.currentPosition.coerceAtLeast(0); durationMs = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: durationMs
        speed = p.playbackParameters.speed
        hasVideo = p.currentTracks.containsType(C.TRACK_TYPE_VIDEO); videoRatio = com.everettpenne.media.videoAspectRatio(p)
        PiPState.videoPlaying = PiP.shouldEnter(hasVideo, isPlaying); PiPState.videoRatio = videoRatio
        hasNext = p.hasNextMediaItem()
        if (sleepEndsAt > 0) {
            val left = sleepEndsAt - System.currentTimeMillis()
            // The service pauses when the time is up; the chip then goes back to "Sleep".
            sleepLabel = if (left <= 0) { sleepEndsAt = 0; null } else "%d:%02d".format(left / 60_000, left / 1000 % 60)
        }
    }

    /** Called about once a second while something plays (and once when a screen that shows the clock opens). */
    fun tick() { controller?.let { sync(it) } }

    fun play(episode: Episode, feed: Feed) = play(Playable.Podcast(episode, feed))

    /** Plays a list from [startIndex], putting the rest of the list in the one Up Next. */
    fun playQueue(entries: List<Playable>, startIndex: Int = 0) {
        val c = controller ?: return
        if (entries.isEmpty()) return
        val index = startIndex.coerceIn(0, entries.lastIndex)
        error = null
        library.clearQueue()
        entries.drop(index + 1).forEach { library.enqueue(it) }
        val items = entries.map { PlayableMedia.item(it, local(), musicFiles()) }
        c.setMediaItems(items, index, 0L)
        c.setPlaybackSpeed(if (entries[index] is Playable.Podcast) settings.speed.value else 1f)
        c.prepare(); c.play()
    }

    /** Plays any entry from the one queue: an episode from a feed, a downloaded one, or a song. */
    fun play(entry: Playable) {
        val c = controller ?: return
        error = null
        if (c.currentMediaItem?.mediaId == entry.id) { if (!c.isPlaying) c.play(); return }
        val saved = (entry as? Playable.Podcast)?.let { library.progress(it.episode.id) }
        val start = saved?.takeIf { !it.played && it.durationMs > 0 && it.positionMs < it.durationMs - 10_000 }?.positionMs ?: 0L
        library.dequeue(entry.id)                                     // playing it now: it is no longer "up next"
        c.setMediaItem(PlayableMedia.item(entry, local(), musicFiles()), start)
        c.setPlaybackSpeed(if (entry is Playable.Podcast) settings.speed.value else 1f)
        c.prepare(); c.play()
    }

    /** Skips to the next episode: the queue's next item, or the next unplayed episode of the same show (the notification's and a headset's "next" do the same). */
    fun next() {
        val c = controller ?: return
        if (c.hasNextMediaItem()) { c.seekToNextMediaItem(); return }
        NextUp.next(episodeId, feedId, library.queue.value, library.contents.value, library.progress.value)?.let { play(it) }
    }

    /** Skips to the previous episode: restarts the current one once past a few seconds, otherwise the one before it (what just played, or the show's previous episode). */
    fun previous() {
        val c = controller ?: return
        if (c.hasPreviousMediaItem()) { c.seekToPreviousMediaItem(); return }   // restart when past the threshold, else step back
        if (c.currentPosition > NextUp.RESTART_AFTER_MS) { seekTo(0); return }
        NextUp.previous(episodeId, feedId, library.contents.value)?.let { play(it) } ?: seekTo(0)
    }

    fun toggle() { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun skipBack() { controller?.seekBack(); tick() }
    fun skipForward() { controller?.seekForward(); tick() }
    fun seekTo(ms: Long) { controller?.seekTo(ms); positionMs = ms }
    /** Try again after a playback failure: forget the error and ask the player to prepare and play once more. */
    fun retry() { error = null; controller?.let { it.prepare(); it.play() } }
    fun changeSpeed(v: Float) { controller?.setPlaybackSpeed(v); speed = v; settings.setSpeed(v) }
    fun stop() { controller?.let { it.stop(); it.clearMediaItems() } }

    /** minutes > 0: pause after that long; endOfEpisode: pause when it ends; neither: no timer. */
    fun sleep(minutes: Int = 0, endOfEpisode: Boolean = false) {
        controller?.sendCustomCommand(PlaybackCommands.SLEEP, Bundle().apply { putInt(PlaybackCommands.MINUTES, minutes); putBoolean(PlaybackCommands.END_OF_EPISODE, endOfEpisode) })
        sleepEndsAt = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0L
        sleepLabel = when { endOfEpisode -> "End of episode"; minutes > 0 -> "$minutes:00"; else -> null }
    }

    companion object { const val KEY_FEED = PlayableMedia.KEY_FEED }
}

val LocalPlayback = staticCompositionLocalOf<PlaybackUi> { error("no PlaybackUi provided") }

/** Connects to the playback service for as long as the app's UI is up, and keeps the clock moving while something plays. */
@Composable
fun rememberPlaybackUi(): PlaybackUi {
    val context = LocalContext.current
    val graph = context.graph
    val ui = remember { PlaybackUi(graph.library, graph.settings, { graph.localFiles }, { MusicFiles { graph.jellyfin.downloads.fileFor(it) } }) }
    DisposableEffect(Unit) {
        val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        future.addListener({ runCatching { future.get() }.getOrNull()?.let(ui::attach) }, ContextCompat.getMainExecutor(context))
        onDispose { ui.detach(); MediaController.releaseFuture(future) }
    }
    LaunchedEffect(ui.isPlaying) { while (ui.isPlaying) { ui.tick(); delay(1000) } }
    return ui
}
