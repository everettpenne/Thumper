package com.everettpenne.thumper.music.data.jellyfin

import android.content.Context
import com.everettpenne.thumper.music.data.Artwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The Jellyfin server Thumper is signed in to: one encrypted session, one HTTP client (which adds the token only to the server's own
 * host), the URL builder, and the offline download store. Nothing here runs until the person connects a server.
 */
class Jellyfin(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val sessions = SessionStore(appContext, SecureStore(appContext))

    lateinit var api: JellyfinApi
        private set
    lateinit var urls: MediaUrls
        private set
    lateinit var downloads: Downloads
        private set

    /**
     * The API, the images and ExoPlayer all use this client. The Authorization header is added only when the request goes to the
     * signed-in server's host, so the token cannot leak to a CDN or a link the server returns.
     */
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            val session = sessions.session.value
            val serverHost = session?.serverUrl?.toHttpUrlOrNull()?.host
            if (session != null && request.header("Authorization") == null && request.url.host == serverHost) {
                chain.proceed(request.newBuilder().header("Authorization", api.authHeader(session.token)).build())
            } else {
                chain.proceed(request)
            }
        }
        .build()

    init {
        api = JellyfinApi(http, session = { sessions.session.value }, deviceId = { sessions.deviceId })
        urls = MediaUrls(server = { sessions.session.value?.serverUrl }, deviceId = { sessions.deviceId })
        val dir = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "jellyfin").apply { mkdirs() }
        downloads = Downloads(
            appContext,
            dir,
            http,
            auth = { sessions.session.value?.token?.let { api.authHeader(it) } },
            posterUrl = { urls.poster(it, 400) },
            scope = scope,
        )
    }

    /** Asks Android's DownloadManager to keep the original file for offline listening. */
    fun requestDownload(item: JfItem): Boolean {
        if (sessions.session.value == null) return false
        val url = urls.download(item.id) ?: return false
        return downloads.download(item, url)
    }

    /** Fetches a Jellyfin image into the same artwork cache the local library uses. */
    suspend fun ensureArt(albumKey: String, url: String?) {
        if (url.isNullOrBlank() || Artwork.has(appContext, albumKey)) return
        withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (response.isSuccessful) {
                        response.body?.bytes()?.let { Artwork.apply(appContext, albumKey, it) }
                    }
                }
            }
        }
    }
}
