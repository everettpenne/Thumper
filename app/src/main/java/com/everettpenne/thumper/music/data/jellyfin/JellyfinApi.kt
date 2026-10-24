package com.everettpenne.thumper.music.data.jellyfin

import com.everettpenne.thumper.BuildConfig
import com.everettpenne.thumper.music.data.jellyfin.Session
import com.everettpenne.thumper.music.data.jellyfin.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume

/**
 * Talks to a Jellyfin server's REST API. [session] supplies the signed-in session (null before sign-in); every request carries Jellyfin's
 * `Authorization: MediaBrowser ...` header, built by [authHeader], so the token is never in a URL (and so never in a log or a history).
 */
class JellyfinApi(private val client: OkHttpClient, private val session: () -> Session?, private val deviceId: () -> String) {

    // ---- plumbing ---------------------------------------------------------------------------------------------------------

    /** Jellyfin's client identification, with the token once there is one. Quotes are not allowed inside the values. */
    fun authHeader(token: String? = session()?.token): String =
        "MediaBrowser Client=\"Thumper\", Device=\"Android\", DeviceId=\"${deviceId()}\", Version=\"${BuildConfig.VERSION_NAME}\"" +
            (token?.let { ", Token=\"$it\"" } ?: "")

    private class Raw(val code: Int, val body: String) { val ok get() = code in 200..299 }

    /** Cancelling a call closes its socket, which Android forbids on the main thread, so cancellation happens on IO. */
    private val cancelScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Reads and releases the response entirely on OkHttp's own thread (closing it on the main thread crashes: NetworkOnMainThreadException). */
    private suspend fun send(request: Request): Result<Raw> = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { cancelScope.launch { call.cancel() } }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resume(Result.failure(e)) }
            override fun onResponse(call: Call, response: Response) {
                val raw = runCatching { response.use { Raw(it.code, if (it.isSuccessful) it.body?.string().orEmpty() else "") } }
                if (cont.isActive) cont.resume(raw)
            }
        })
    }

    private fun failureFor(code: Int, signingIn: Boolean = false) = when (code) {
        401 -> if (signingIn) Failure.BAD_LOGIN else Failure.SIGNED_OUT
        403 -> Failure.FORBIDDEN
        404 -> Failure.NOT_FOUND
        else -> Failure.BAD_RESPONSE
    }

    private fun base(): HttpUrl? = session()?.serverUrl?.toHttpUrlOrNull()

    private fun url(server: HttpUrl, path: String, query: Array<out Pair<String, String?>>): HttpUrl {
        val builder = server.newBuilder()
        path.trim('/').split('/').forEach { builder.addPathSegment(it) }
        for ((k, v) in query) if (v != null) builder.addQueryParameter(k, v)
        return builder.build()
    }

    private suspend inline fun <reified T> call(request: Request, signingIn: Boolean = false): ApiResult<T> {
        val raw = send(request).getOrElse { return ApiResult.Failed(Failure.OFFLINE, it.message) }
        if (!raw.ok) return ApiResult.Failed(failureFor(raw.code, signingIn), "HTTP ${raw.code}")
        return try { ApiResult.Ok(JellyfinJson.decodeFromString<T>(raw.body)) } catch (e: Exception) { ApiResult.Failed(Failure.BAD_RESPONSE, e.message) }
    }

    private suspend inline fun <reified T> get(path: String, vararg query: Pair<String, String?>): ApiResult<T> {
        val server = base() ?: return ApiResult.Failed(Failure.SIGNED_OUT)
        return call(Request.Builder().url(url(server, path, query)).header("Authorization", authHeader()).header("Accept", "application/json").get().build())
    }

    private suspend fun post(path: String, json: String? = null, vararg query: Pair<String, String?>): ApiResult<Unit> {
        val server = base() ?: return ApiResult.Failed(Failure.SIGNED_OUT)
        val body = (json ?: "{}").toRequestBody("application/json".toMediaType())
        val raw = send(Request.Builder().url(url(server, path, query)).header("Authorization", authHeader()).post(body).build())
            .getOrElse { return ApiResult.Failed(Failure.OFFLINE, it.message) }
        return if (raw.ok) ApiResult.Ok(Unit) else ApiResult.Failed(failureFor(raw.code), "HTTP ${raw.code}")
    }

    private suspend fun delete(path: String, vararg query: Pair<String, String?>): ApiResult<Unit> {
        val server = base() ?: return ApiResult.Failed(Failure.SIGNED_OUT)
        val raw = send(Request.Builder().url(url(server, path, query)).header("Authorization", authHeader()).delete().build())
            .getOrElse { return ApiResult.Failed(Failure.OFFLINE, it.message) }
        return if (raw.ok) ApiResult.Ok(Unit) else ApiResult.Failed(failureFor(raw.code), "HTTP ${raw.code}")
    }

    private val userId get() = session()?.userId.orEmpty()

    // ---- connecting and signing in ----------------------------------------------------------------------------------------

    /** What a typed address could mean: as given, or with a scheme added (https first, then plain http for a server at home). */
    fun candidates(input: String): List<String> {
        val text = input.trim().trimEnd('/')
        if (text.isEmpty()) return emptyList()
        return if (text.startsWith("http://", true) || text.startsWith("https://", true)) listOf(text) else listOf("https://$text", "http://$text")
    }

    /** Finds the server behind [input] and reads its public info; returns the working base address with it. */
    suspend fun connect(input: String): ApiResult<Pair<String, PublicInfo>> {
        var last: ApiResult.Failed = ApiResult.Failed(Failure.OFFLINE)
        for (candidate in candidates(input)) {
            val server = candidate.toHttpUrlOrNull() ?: continue
            val request = Request.Builder().url(url(server, "System/Info/Public", emptyArray())).header("Accept", "application/json").get().build()
            when (val r = call<PublicInfo>(request)) {
                is ApiResult.Ok -> return if (r.value.id.isNotEmpty() || r.value.version.isNotEmpty()) ApiResult.Ok(candidate to r.value) else ApiResult.Failed(Failure.NOT_JELLYFIN)
                is ApiResult.Failed -> last = if (r.reason == Failure.BAD_RESPONSE) ApiResult.Failed(Failure.NOT_JELLYFIN, r.detail) else r
            }
        }
        return last
    }

    suspend fun login(serverUrl: String, serverName: String, username: String, password: String): ApiResult<Session> {
        val server = serverUrl.toHttpUrlOrNull() ?: return ApiResult.Failed(Failure.OFFLINE)
        val body = JellyfinJson.encodeToString(AuthRequest(username, password)).toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url(server, "Users/AuthenticateByName", emptyArray()))
            .header("Authorization", authHeader(token = null)).header("Accept", "application/json").post(body).build()
        return call<AuthResult>(request, signingIn = true).map { Session(serverUrl, it.user.id, it.user.name, it.accessToken, serverName) }
    }

    suspend fun logout(): ApiResult<Unit> = post("Sessions/Logout")

    // ---- browsing ---------------------------------------------------------------------------------------------------------

    private val listFields = "PrimaryImageAspectRatio,Overview,Genres,ChildCount"

    suspend fun libraries(): ApiResult<List<JfItem>> = get<ItemsResult>("Users/$userId/Views").map { it.items }

    suspend fun resume(limit: Int = 12): ApiResult<List<JfItem>> = get<ItemsResult>("Users/$userId/Items/Resume",
        "Limit" to "$limit", "MediaTypes" to "Video", "Fields" to listFields, "EnableTotalRecordCount" to "false").map { it.items }

    suspend fun nextUp(limit: Int = 12): ApiResult<List<JfItem>> = get<ItemsResult>("Shows/NextUp",
        "UserId" to userId, "Limit" to "$limit", "Fields" to listFields, "EnableTotalRecordCount" to "false").map { it.items }

    suspend fun latest(libraryId: String, limit: Int = 16): ApiResult<List<JfItem>> = get<List<JfItem>>("Users/$userId/Items/Latest",
        "ParentId" to libraryId, "Limit" to "$limit", "Fields" to listFields)

    suspend fun favorites(limit: Int = 30): ApiResult<List<JfItem>> = items(filters = "IsFavorite", limit = limit, types = "Movie,Series,Episode")
        .map { it.items }

    /** A page of a library or folder. [sortBy] and [sortOrder] are Jellyfin's own names (SortName, DateCreated, PremiereDate, CommunityRating). */
    suspend fun items(
        parentId: String? = null, types: String? = null, sortBy: String = "SortName", sortOrder: String = "Ascending", start: Int = 0, limit: Int = 60,
        search: String? = null, filters: String? = null, recursive: Boolean = true, genre: String? = null,
    ): ApiResult<ItemsResult> = get("Users/$userId/Items",
        "ParentId" to parentId, "IncludeItemTypes" to types, "SortBy" to sortBy, "SortOrder" to sortOrder, "StartIndex" to "$start", "Limit" to "$limit",
        "SearchTerm" to search, "Filters" to filters, "Recursive" to recursive.toString(), "Genres" to genre, "Fields" to listFields)

    suspend fun item(id: String): ApiResult<JfItem> = get("Users/$userId/Items/$id", "Fields" to "$listFields,MediaSources,MediaStreams,People,ExternalUrls")

    suspend fun seasons(seriesId: String): ApiResult<List<JfItem>> = get<ItemsResult>("Shows/$seriesId/Seasons", "UserId" to userId, "Fields" to listFields).map { it.items }

    suspend fun episodes(seriesId: String, seasonId: String?): ApiResult<List<JfItem>> =
        get<ItemsResult>("Shows/$seriesId/Episodes", "UserId" to userId, "SeasonId" to seasonId, "Fields" to "$listFields,MediaSources").map { it.items }

    suspend fun similar(id: String, limit: Int = 12): ApiResult<List<JfItem>> =
        get<ItemsResult>("Items/$id/Similar", "UserId" to userId, "Limit" to "$limit", "Fields" to listFields).map { it.items }

    // ---- music --------------------------------------------------------------------------------------------------------------

    /** The libraries that hold music (the "music" collection type), for the Music tab's landing. */
    suspend fun musicLibraries(): ApiResult<List<JfItem>> = libraries().map { libs -> libs.filter { it.collectionType == "music" } }

    /** The artists of a music library, from Jellyfin's Artists endpoint (a query result of MusicArtist items). */
    suspend fun artists(libraryId: String, limit: Int = 200): ApiResult<List<JfItem>> = get<ItemsResult>("Artists",
        "UserId" to userId, "ParentId" to libraryId, "SortBy" to "SortName", "SortOrder" to "Ascending", "Recursive" to "true",
        "Limit" to "$limit", "Fields" to listFields).map { it.items }

    /** The albums an artist appears on, newest first. */
    suspend fun albumsByArtist(artistId: String): ApiResult<List<JfItem>> = get<ItemsResult>("Users/$userId/Items",
        "ArtistIds" to artistId, "IncludeItemTypes" to "MusicAlbum", "Recursive" to "true", "SortBy" to "ProductionYear,SortName",
        "SortOrder" to "Descending", "Fields" to listFields).map { it.items }

    /** The newest albums in a music library. */
    suspend fun latestAlbums(libraryId: String, limit: Int = 16): ApiResult<List<JfItem>> = get<List<JfItem>>("Users/$userId/Items/Latest",
        "ParentId" to libraryId, "IncludeItemTypes" to "MusicAlbum", "Limit" to "$limit", "Fields" to listFields)

    /** The tracks of an album, in disc/track order. */
    suspend fun albumTracks(albumId: String): ApiResult<List<JfItem>> = get<ItemsResult>("Users/$userId/Items",
        "ParentId" to albumId, "IncludeItemTypes" to "Audio", "SortBy" to "ParentIndexNumber,IndexNumber", "SortOrder" to "Ascending",
        "Fields" to "$listFields,MediaSources").map { it.items }

    // ---- state changes ----------------------------------------------------------------------------------------------------

    suspend fun setPlayed(id: String, played: Boolean) = if (played) post("Users/$userId/PlayedItems/$id") else delete("Users/$userId/PlayedItems/$id")
    suspend fun setFavorite(id: String, favorite: Boolean) = if (favorite) post("Users/$userId/FavoriteItems/$id") else delete("Users/$userId/FavoriteItems/$id")

    suspend fun reportStart(r: PlaybackReport) = post("Sessions/Playing", JellyfinJson.encodeToString(r))
    suspend fun reportProgress(r: PlaybackReport) = post("Sessions/Playing/Progress", JellyfinJson.encodeToString(r))
    suspend fun reportStopped(r: PlaybackReport) = post("Sessions/Playing/Stopped", JellyfinJson.encodeToString(r))
}
