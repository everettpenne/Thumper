package com.everettpenne.thumper.data.api

import com.everettpenne.thumper.data.model.DirectoryShow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest

/** A place to look for podcasts. The shows' episodes never come from here: a directory only says where each show's feed is. */
interface Directory {
    val name: String
    suspend fun search(query: String): ApiResult<List<DirectoryShow>>
    suspend fun popular(): ApiResult<List<DirectoryShow>>
}

private val DirJson = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }

private suspend fun OkHttpClient.getJson(request: Request): ApiResult<String> = try {
    runInterruptible(Dispatchers.IO) {
        newCall(request).execute().use { r ->
            when {
                r.code == 401 || r.code == 403 -> ApiResult.Failed(Failure.KEYS_REJECTED, "HTTP ${r.code}")
                !r.isSuccessful -> ApiResult.Failed(Failure.BAD_RESPONSE, "HTTP ${r.code}")
                else -> ApiResult.Ok(r.body?.string().orEmpty())
            }
        }
    }
} catch (e: IOException) { ApiResult.Failed(Failure.OFFLINE, e.message) }

/**
 * Apple's podcast directory (the iTunes Search API): free, no sign-up and no key. Search asks Apple; "popular" is Apple's top-shows chart, whose entries
 * are then looked up in one request to learn their feed addresses. The queries are the only thing sent to Apple, and nothing identifies the person.
 */
class AppleDirectory(
    private val client: OkHttpClient, private val searchBase: String = "https://itunes.apple.com", private val chartBase: String = "https://rss.marketingtools.apple.com",
    private val country: () -> String = { "us" },
) : Directory {
    override val name = "Apple Podcasts"

    @Serializable private data class SearchResponse(val results: List<Hit> = emptyList())
    @Serializable private data class Hit(
        val collectionId: Long? = null, val collectionName: String? = null, val trackName: String? = null, val artistName: String? = null, val feedUrl: String? = null,
        val artworkUrl600: String? = null, val artworkUrl100: String? = null, val primaryGenreName: String? = null,
    )
    @Serializable private data class Chart(val feed: ChartFeed = ChartFeed())
    @Serializable private data class ChartFeed(val results: List<ChartItem> = emptyList())
    @Serializable private data class ChartItem(val id: String = "")

    private fun Hit.toShow(): DirectoryShow? {
        val feed = feedUrl?.takeIf { it.isNotBlank() } ?: return null          // a few shows have no public feed: they cannot be subscribed to
        return DirectoryShow(title = (collectionName ?: trackName ?: feed), author = artistName.orEmpty(), feedUrl = feed, imageUrl = artworkUrl600 ?: artworkUrl100, genre = primaryGenreName.orEmpty())
    }

    override suspend fun search(query: String): ApiResult<List<DirectoryShow>> {
        val url = "$searchBase/search".toHttpUrl().newBuilder().addQueryParameter("term", query).addQueryParameter("media", "podcast")
            .addQueryParameter("entity", "podcast").addQueryParameter("limit", "30").build()
        return parseHits(client.getJson(Request.Builder().url(url).build()))
    }

    override suspend fun popular(): ApiResult<List<DirectoryShow>> {
        val chart = client.getJson(Request.Builder().url("$chartBase/api/v2/${country()}/podcasts/top/25/podcasts.json").build())
        val ids = when (chart) {
            is ApiResult.Failed -> return chart
            is ApiResult.Ok -> try { DirJson.decodeFromString<Chart>(chart.value).feed.results.map { it.id }.filter { it.isNotBlank() } } catch (e: Exception) { return ApiResult.Failed(Failure.BAD_RESPONSE, e.message) }
        }
        if (ids.isEmpty()) return ApiResult.Ok(emptyList())
        val url = "$searchBase/lookup".toHttpUrl().newBuilder().addQueryParameter("id", ids.joinToString(",")).addQueryParameter("entity", "podcast").build()
        val raw = client.getJson(Request.Builder().url(url).build())
        // the chart's order is the point of a chart, and the lookup returns the shows in any order: put them back in the chart's
        val rank = ids.withIndex().associate { (i, id) -> id to i }
        return when (raw) {
            is ApiResult.Failed -> raw
            is ApiResult.Ok -> try {
                ApiResult.Ok(DirJson.decodeFromString<SearchResponse>(raw.value).results.sortedBy { rank[it.collectionId?.toString()] ?: Int.MAX_VALUE }.mapNotNull { it.toShow() }.distinctBy { it.feedUrl })
            } catch (e: Exception) { ApiResult.Failed(Failure.BAD_RESPONSE, e.message) }
        }
    }

    private fun parseHits(raw: ApiResult<String>): ApiResult<List<DirectoryShow>> = when (raw) {
        is ApiResult.Failed -> raw
        is ApiResult.Ok -> try { ApiResult.Ok(DirJson.decodeFromString<SearchResponse>(raw.value).results.mapNotNull { it.toShow() }.distinctBy { it.feedUrl }) }
                           catch (e: Exception) { ApiResult.Failed(Failure.BAD_RESPONSE, e.message) }
    }
}

/**
 * Podcast Index (podcastindex.org): an open, independent directory. Its API is free but needs a key and secret (from api.podcastindex.org), which
 * the person enters in Settings; every request is signed with them as Podcast Index describes: the key, the time, and the SHA-1 of key + secret + time.
 */
class PodcastIndexDirectory(
    private val client: OkHttpClient, private val credentials: () -> Pair<String, String>?, private val base: String = "https://api.podcastindex.org/api/1.0",
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 }, private val userAgent: String = "Thumper/0.1",
) : Directory {
    override val name = "Podcast Index"

    @Serializable private data class Response(val feeds: List<Item> = emptyList())
    @Serializable private data class Item(
        val title: String? = null, val url: String? = null, val author: String? = null, val ownerName: String? = null, val description: String? = null,
        val image: String? = null, val artwork: String? = null, val categories: Map<String, String>? = null,
    )

    internal fun signed(url: okhttp3.HttpUrl): Request? {
        val (key, secret) = credentials() ?: return null
        val time = clock().toString()
        val auth = MessageDigest.getInstance("SHA-1").digest((key + secret + time).toByteArray()).joinToString("") { "%02x".format(it) }
        return Request.Builder().url(url).header("User-Agent", userAgent).header("X-Auth-Date", time).header("X-Auth-Key", key).header("Authorization", auth).build()
    }

    private suspend fun call(path: String, vararg query: Pair<String, String>): ApiResult<List<DirectoryShow>> {
        val url = "$base$path".toHttpUrl().newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val request = signed(url) ?: return ApiResult.Failed(Failure.NEEDS_KEYS)
        return when (val raw = client.getJson(request)) {
            is ApiResult.Failed -> raw
            is ApiResult.Ok -> try {
                ApiResult.Ok(DirJson.decodeFromString<Response>(raw.value).feeds.mapNotNull { f ->
                    val feed = f.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    DirectoryShow(f.title ?: feed, f.author ?: f.ownerName ?: "", feed, (f.artwork?.takeIf { it.isNotBlank() } ?: f.image?.takeIf { it.isNotBlank() }),
                        f.description.orEmpty(), f.categories?.values?.firstOrNull().orEmpty())
                }.distinctBy { it.feedUrl })
            } catch (e: Exception) { ApiResult.Failed(Failure.BAD_RESPONSE, e.message) }
        }
    }

    override suspend fun search(query: String) = call("/search/byterm", "q" to query, "max" to "30")
    override suspend fun popular() = call("/podcasts/trending", "max" to "25", "lang" to "en")
}
