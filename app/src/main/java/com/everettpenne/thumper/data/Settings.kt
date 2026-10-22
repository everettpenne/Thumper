package com.everettpenne.thumper.data

import android.content.Context
import com.everettpenne.thumper.data.auth.SecureStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DirectoryChoice(val label: String) { APPLE("Apple Podcasts"), PODCAST_INDEX("Podcast Index") }

/** The preferences. The Podcast Index key and secret are kept encrypted ([SecureStore]); everything else is ordinary preferences. */
class Settings(context: Context, private val secure: SecureStore) {
    private val prefs = context.getSharedPreferences("thumper_settings", Context.MODE_PRIVATE)

    private val _directory = MutableStateFlow(runCatching { DirectoryChoice.valueOf(prefs.getString("directory", null) ?: "") }.getOrDefault(DirectoryChoice.APPLE))
    private val _skipBack = MutableStateFlow(prefs.getInt("skipBack", 15))
    private val _skipForward = MutableStateFlow(prefs.getInt("skipForward", 30))
    private val _speed = MutableStateFlow(prefs.getFloat("speed", 1f))
    private val _hasKeys = MutableStateFlow(keys() != null)
    private val _wifiOnly = MutableStateFlow(prefs.getBoolean("wifiOnlyDownloads", true))
    private val _deleteWhenPlayed = MutableStateFlow(prefs.getBoolean("deleteWhenPlayed", false))
    private val _autoplay = MutableStateFlow(prefs.getBoolean("autoplay", true))
    private val _linksInApp = MutableStateFlow(prefs.getBoolean("linksInApp", true))
    private val _readerScale = MutableStateFlow(prefs.getFloat("readerScale", 1f))
    private val _fullArticles = MutableStateFlow(prefs.getBoolean("fullArticles", true))

    /** Which directory the Search tab asks. Apple's needs nothing; Podcast Index needs the key and secret below. */
    val directory: StateFlow<DirectoryChoice> = _directory.asStateFlow()
    val skipBackSeconds: StateFlow<Int> = _skipBack.asStateFlow()
    val skipForwardSeconds: StateFlow<Int> = _skipForward.asStateFlow()
    /** The speed new episodes start at (1.0 is normal). */
    val speed: StateFlow<Float> = _speed.asStateFlow()
    val hasPodcastIndexKeys: StateFlow<Boolean> = _hasKeys.asStateFlow()
    /** Wait for Wi-Fi before downloading (on by default: episodes are big). */
    val wifiOnlyDownloads: StateFlow<Boolean> = _wifiOnly.asStateFlow()
    /** Delete a downloaded episode once it has been played. */
    val deleteWhenPlayed: StateFlow<Boolean> = _deleteWhenPlayed.asStateFlow()
    /** When an episode ends, play the next one (Up Next, or the next unplayed episode of the same show) by itself. On by default. */
    val autoplay: StateFlow<Boolean> = _autoplay.asStateFlow()
    /** Open links in the suite's built-in browser over the app (on by default), or in Vanadium or the phone's browser. */
    val linksInApp: StateFlow<Boolean> = _linksInApp.asStateFlow()
    /** The reader's text size, as a factor of the normal size (0.85 to 1.5). */
    val readerScale: StateFlow<Float> = _readerScale.asStateFlow()
    /** When a feed sends only a teaser, fetch the article from its page and show the whole text. */
    val fullArticles: StateFlow<Boolean> = _fullArticles.asStateFlow()

    fun setDirectory(v: DirectoryChoice) { _directory.value = v; prefs.edit().putString("directory", v.name).apply() }
    fun setSkipBack(v: Int) { _skipBack.value = v; prefs.edit().putInt("skipBack", v).apply() }
    fun setSkipForward(v: Int) { _skipForward.value = v; prefs.edit().putInt("skipForward", v).apply() }
    fun setSpeed(v: Float) { _speed.value = v; prefs.edit().putFloat("speed", v).apply() }

    fun setWifiOnlyDownloads(v: Boolean) { _wifiOnly.value = v; prefs.edit().putBoolean("wifiOnlyDownloads", v).apply() }
    fun setDeleteWhenPlayed(v: Boolean) { _deleteWhenPlayed.value = v; prefs.edit().putBoolean("deleteWhenPlayed", v).apply() }
    fun setAutoplay(v: Boolean) { _autoplay.value = v; prefs.edit().putBoolean("autoplay", v).apply() }
    fun setLinksInApp(v: Boolean) { _linksInApp.value = v; prefs.edit().putBoolean("linksInApp", v).apply() }
    fun setReaderScale(v: Float) { val c = v.coerceIn(.85f, 1.5f); _readerScale.value = c; prefs.edit().putFloat("readerScale", c).apply() }
    fun setFullArticles(v: Boolean) { _fullArticles.value = v; prefs.edit().putBoolean("fullArticles", v).apply() }

    fun keys(): Pair<String, String>? {
        val k = secure.get("pi_key"); val s = secure.get("pi_secret")
        return if (!k.isNullOrBlank() && !s.isNullOrBlank()) k to s else null
    }
    fun setKeys(key: String, secret: String) {
        if (key.isBlank() || secret.isBlank()) { secure.remove("pi_key"); secure.remove("pi_secret") } else { secure.put("pi_key", key.trim()); secure.put("pi_secret", secret.trim()) }
        _hasKeys.value = keys() != null
    }

    /** Where the directories are asked. The real services unless a test points them at its own server (see the instrumented tests). */
    fun appleBase(): String = prefs.getString("test_apple_base", null) ?: "https://itunes.apple.com"
    fun appleChartBase(): String = prefs.getString("test_apple_chart_base", null) ?: "https://rss.marketingtools.apple.com"
    fun podcastIndexBase(): String = prefs.getString("test_pi_base", null) ?: "https://api.podcastindex.org/api/1.0"
    fun country(): String = java.util.Locale.getDefault().country.lowercase().ifBlank { "us" }
}
