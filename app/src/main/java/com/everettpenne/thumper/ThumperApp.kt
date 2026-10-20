package com.everettpenne.thumper

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.everettpenne.thumper.data.DirectoryChoice
import com.everettpenne.thumper.data.Library
import com.everettpenne.thumper.data.Settings
import com.everettpenne.thumper.data.api.AppleDirectory
import com.everettpenne.thumper.data.api.Directory
import com.everettpenne.thumper.data.api.PodcastIndexDirectory
import com.everettpenne.thumper.data.auth.SecureStore
import com.everettpenne.thumper.data.feed.FeedFetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** The app's shared parts, built once: the HTTP client, the library on disk, the feed fetcher, the directories and the preferences. */
class Graph(app: Context) {
    val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()
    val settings = Settings(app, SecureStore(app))
    val fetcher = FeedFetcher(http)
    /** Work that outlives a screen: refreshing, saving. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val library = Library(File(app.filesDir, "library"), fetcher, scope)
    val downloads = com.everettpenne.thumper.data.Downloads(app, File(app.getExternalFilesDir(null) ?: app.filesDir, "downloads").apply { mkdirs() }, library, settings, scope)
    /** Where downloaded episodes are on this phone: the player plays these in preference to the network. */
    val localFiles: com.everettpenne.thumper.playback.LocalFiles get() = downloads
    /** Downloads in progress as a Live Update (Android 16) or a progress notification. */
    val downloadLiveUpdate = com.everettpenne.thumper.data.DownloadLiveUpdate(app, downloads, scope).also { it.start() }

    val refreshing = kotlinx.coroutines.flow.MutableStateFlow(false)
    private var lastRefresh = 0L

    /** Refreshes every subscription unless that was done recently (or [force]); one refresh at a time. Returns what it did, or null if it didn't run. */
    suspend fun refreshAll(force: Boolean = false): com.everettpenne.thumper.data.RefreshReport? {
        if (refreshing.value || (!force && System.currentTimeMillis() - lastRefresh < 20 * 60_000L) || library.feeds.value.isEmpty()) return null
        refreshing.value = true
        return try { library.refreshAll().also { lastRefresh = System.currentTimeMillis() } } finally { refreshing.value = false }
    }

    /** Feeds opened from a search or an address but not subscribed to, for the screens that show them (episode pages) to find their content. */
    val previews = java.util.concurrent.ConcurrentHashMap<String, com.everettpenne.thumper.data.model.FeedContent>()

    /** The last list of popular shows for each directory, so the Search tab has something to show at once. */
    val popular = java.util.concurrent.ConcurrentHashMap<String, List<com.everettpenne.thumper.data.model.DirectoryShow>>()

    /** The directory the person chose, built at the moment of use so a change in Settings applies at once. */
    fun directory(): Directory = when (settings.directory.value) {
        DirectoryChoice.APPLE -> AppleDirectory(http, settings.appleBase(), settings.appleChartBase(), settings::country)
        DirectoryChoice.PODCAST_INDEX -> PodcastIndexDirectory(http, settings::keys, settings.podcastIndexBase())
    }
}

class ThumperApp : Application(), SingletonImageLoader.Factory {
    lateinit var graph: Graph
        private set

    override fun onCreate() { super.onCreate(); graph = Graph(this) }

    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { graph.http })) }.build()
}

val Context.graph: Graph get() = (applicationContext as ThumperApp).graph
