package com.everettpenne.thumper.music.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/**
 * Small remembered choices: where the library comes from, whether the welcome page has been seen, and what was played last.
 *
 * Kept in shared preferences because every value is one string or one number and the whole app reads it in the composition; there is
 * no account, no cloud and nothing here leaves the phone.
 */
class MusicSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _source = MutableStateFlow(prefs.getString(KEY_SOURCE, LibrarySnapshot.SOURCE_MEDIA) ?: LibrarySnapshot.SOURCE_MEDIA)
    val source: StateFlow<String> = _source.asStateFlow()

    private val _folder = MutableStateFlow(prefs.getString(KEY_FOLDER, null))
    val folder: StateFlow<String?> = _folder.asStateFlow()

    private val _folderName = MutableStateFlow(prefs.getString(KEY_FOLDER_NAME, null))
    val folderName: StateFlow<String?> = _folderName.asStateFlow()

    private val _welcomeSeen = MutableStateFlow(prefs.getBoolean(KEY_WELCOME, false))
    val welcomeSeen: StateFlow<Boolean> = _welcomeSeen.asStateFlow()

    private val _recents = MutableStateFlow(readRecents())
    val recents: StateFlow<List<String>> = _recents.asStateFlow()

    private val _lastTrackId = MutableStateFlow(prefs.getString(KEY_LAST_TRACK, null))
    val lastTrackId: StateFlow<String?> = _lastTrackId.asStateFlow()

    private val _lastPosition = MutableStateFlow(prefs.getLong(KEY_LAST_POSITION, 0L))
    val lastPosition: StateFlow<Long> = _lastPosition.asStateFlow()

    private val _mbConsent = MutableStateFlow(prefs.getBoolean(KEY_MB_CONSENT, false))
    /** Off until the person asks for a cover lookup; the only feature that talks to MusicBrainz. */
    val mbConsent: StateFlow<Boolean> = _mbConsent.asStateFlow()

    private val _sleepMinutes = MutableStateFlow(prefs.getInt(KEY_SLEEP, 30))
    val sleepMinutes: StateFlow<Int> = _sleepMinutes.asStateFlow()

    private val _leveling = MutableStateFlow(prefs.getBoolean(KEY_LEVELING, true))
    /** On by default, but it only does anything for tracks that have been measured or carry ReplayGain. */
    val leveling: StateFlow<Boolean> = _leveling.asStateFlow()

    private val _crossfadeMs = MutableStateFlow(prefs.getInt(KEY_CROSSFADE, 0))
    /** Overlap between tracks in milliseconds; 0 keeps the queue gapless. */
    val crossfadeMs: StateFlow<Int> = _crossfadeMs.asStateFlow()

    fun setSource(source: String) {
        prefs.edit().putString(KEY_SOURCE, source).apply()
        _source.value = source
    }

    fun setFolder(uri: String?, name: String?) {
        prefs.edit().putString(KEY_FOLDER, uri).putString(KEY_FOLDER_NAME, name).apply()
        _folder.value = uri
        _folderName.value = name
        if (uri != null) setSource(LibrarySnapshot.SOURCE_FOLDER)
    }

    fun setWelcomeSeen(seen: Boolean = true) {
        prefs.edit().putBoolean(KEY_WELCOME, seen).apply()
        _welcomeSeen.value = seen
    }

    fun setMbConsent(value: Boolean) {
        prefs.edit().putBoolean(KEY_MB_CONSENT, value).apply()
        _mbConsent.value = value
    }

    fun setSleepMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_SLEEP, minutes).apply()
        _sleepMinutes.value = minutes
    }

    fun setLeveling(value: Boolean) {
        prefs.edit().putBoolean(KEY_LEVELING, value).apply()
        _leveling.value = value
    }

    fun setCrossfadeMs(value: Int) {
        prefs.edit().putInt(KEY_CROSSFADE, value).apply()
        _crossfadeMs.value = value
    }

    /** Remembers a track as just played, newest first, without duplicates, and keeps the last ten. */
    fun rememberPlayed(trackId: String) {
        val next = (listOf(trackId) + _recents.value.filter { it != trackId }).take(10)
        _recents.value = next
        prefs.edit().putString(KEY_RECENTS, JSONArray(next).toString()).apply()
    }

    /** Saves where the last track was left, so "Jump back in" can offer it again. */
    fun rememberPosition(trackId: String?, positionMs: Long) {
        prefs.edit().putString(KEY_LAST_TRACK, trackId).putLong(KEY_LAST_POSITION, positionMs).apply()
        _lastTrackId.value = trackId
        _lastPosition.value = positionMs
    }

    fun forgetLast() {
        prefs.edit().remove(KEY_LAST_TRACK).remove(KEY_LAST_POSITION).apply()
        _lastTrackId.value = null
        _lastPosition.value = 0L
    }

    private fun readRecents(): List<String> = runCatching {
        val raw = prefs.getString(KEY_RECENTS, null) ?: return emptyList()
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
    }.getOrDefault(emptyList())

    private companion object {
        const val KEY_SOURCE = "source"
        const val KEY_FOLDER = "folder"
        const val KEY_FOLDER_NAME = "folder_name"
        const val KEY_WELCOME = "welcome_seen"
        const val KEY_RECENTS = "recents"
        const val KEY_LAST_TRACK = "last_track"
        const val KEY_LAST_POSITION = "last_position"
        const val KEY_SLEEP = "sleep_minutes"
        const val KEY_MB_CONSENT = "musicbrainz_consent"
        const val KEY_LEVELING = "leveling"
        const val KEY_CROSSFADE = "crossfade_ms"
    }
}
