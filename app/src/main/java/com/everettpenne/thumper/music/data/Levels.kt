package com.everettpenne.thumper.music.data

import android.content.Context
import android.net.Uri
import com.everettpenne.thumper.music.tags.Aiff
import com.everettpenne.thumper.music.tags.DecodedAudio
import com.everettpenne.thumper.music.tags.Id3
import com.everettpenne.thumper.music.util.isAiffFileName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.min
import kotlin.math.pow

/** The volume correction chosen for one track, and how it was found. */
@Serializable
data class Level(
    val gainDb: Float,
    val peakDb: Float = 0f,
    /** "tag" for ReplayGain written in the file, "analysis" for a PCM measurement. */
    val source: String = "analysis",
    val analyzedAt: Long = 0L,
)

@Serializable
private data class LevelFile(val version: Int = 1, val levels: Map<String, Level> = emptyMap())

/** Progress of a library-wide volume measurement. */
data class LevelState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val measured: Int = 0,
    val phase: String = "",
    val error: String? = null,
)

/** How a measured loudness becomes a gain: a target RMS, a ceiling to avoid clipping, and sane limits. */
object LevelMath {
    const val TARGET_RMS_DB = -18f
    const val MIN_GAIN_DB = -12f
    const val MAX_GAIN_DB = 12f
    const val PEAK_CEILING_DB = -0.5f

    fun gainDb(rmsDb: Float, peakDb: Float): Float {
        if (rmsDb.isNaN() || rmsDb <= -120f) return 0f
        var gain = TARGET_RMS_DB - rmsDb
        if (!peakDb.isNaN() && peakDb > -60f) gain = min(gain, PEAK_CEILING_DB - peakDb)
        return gain.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
    }

    fun linear(gainDb: Float): Float = 10.0.pow(gainDb / 20.0).toFloat()
}

/** The key a track's gain is carried under in its MediaItem metadata, so the service knows what to apply. */
const val LEVEL_GAIN_EXTRA = "thumper.level.gain"

/**
 * Measures loudness once per track and remembers the correction, so a quiet AIFF rip and a loud MP3 play at the same level.
 *
 * ReplayGain tags are used when a file has them (no decoding at all). AIFF files usually do not, so their raw 16-bit PCM is read
 * and measured; MP3s without a tag are left alone rather than decoded twice. The measurement is deliberately an explicit action:
 * a 33 GB library is a lot of reading, and a player should not start it behind the person's back.
 */
class MusicLevels(context: Context) {
    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, "levels.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _levels = MutableStateFlow(load())
    val levels: StateFlow<Map<String, Level>> = _levels.asStateFlow()

    private val _state = MutableStateFlow(LevelState(measured = _levels.value.size))
    val state: StateFlow<LevelState> = _state.asStateFlow()

    private var job: Job? = null

    /** The linear factor for a track, 1.0 when it has not been measured. */
    fun gainFor(trackId: String): Float = _levels.value[trackId]?.let { LevelMath.linear(it.gainDb) } ?: 1f

    fun has(trackId: String): Boolean = _levels.value.containsKey(trackId)

    fun analyze(tracks: List<Track>) {
        if (job?.isActive == true) return
        job = scope.launch {
            val pending = tracks.filter { it.id !in _levels.value }
            _state.value = LevelState(running = true, total = pending.size, measured = _levels.value.size, phase = "Preparing")
            var done = 0
            try {
                for (track in pending) {
                    if (!isActive) break
                    _state.value = _state.value.copy(done = done, phase = "Measuring " + track.title)
                    val measured = runCatching { measure(track) }.getOrNull()
                    if (measured != null) {
                        _levels.value = _levels.value + (track.id to measured)
                        save()
                    }
                    done++
                    _state.value = _state.value.copy(done = done, measured = _levels.value.size)
                }
                _state.value = _state.value.copy(
                    running = false,
                    phase = if (isActive) "Finished" else "Stopped",
                )
            } finally {
                _state.value = _state.value.copy(running = false)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = _state.value.copy(running = false, phase = "Stopped")
    }

    private fun measure(track: Track): Level? {
        val uri = Uri.parse(track.uri)
        val name = track.fileName.ifBlank { uri.lastPathSegment.orEmpty() }
        val now = System.currentTimeMillis()
        if (isAiffFileName(name)) {
            val tags = Aiff.read(appContext, uri)
            val tagGain = tags?.tags?.trackGainDb
            if (tagGain != null) return Level(tagGain, tags.tags.trackPeak ?: 0f, "tag", now)
            val stats = Aiff.analyze(appContext, uri) ?: return null
            return Level(LevelMath.gainDb(stats.rmsDb, stats.peakDb), stats.peakDb, "analysis", now)
        }
        val tags = if (name.substringAfterLast('.', "").equals("mp3", ignoreCase = true)) {
            Id3.read(appContext, uri)
        } else {
            null
        }
        val tagGain = tags?.trackGainDb
        if (tagGain != null) return Level(tagGain, tags.trackPeak ?: 0f, "tag", now)
        val stats = DecodedAudio.analyze(appContext, uri) ?: return null
        return Level(LevelMath.gainDb(stats.rmsDb, stats.peakDb), stats.peakDb, "analysis", now)
    }

    private fun load(): Map<String, Level> = runCatching {
        if (!file.exists()) emptyMap()
        else json.decodeFromString<LevelFile>(file.readText()).levels
    }.getOrDefault(emptyMap())

    private fun save() {
        runCatching {
            val data = json.encodeToString(LevelFile(levels = _levels.value))
            val temporary = File(file.parentFile, file.name + ".tmp")
            temporary.writeText(data)
            if (!temporary.renameTo(file)) file.writeText(data)
        }
    }
}
