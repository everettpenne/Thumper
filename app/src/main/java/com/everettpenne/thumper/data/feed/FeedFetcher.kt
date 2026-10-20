package com.everettpenne.thumper.data.feed

import com.everettpenne.thumper.data.api.ApiResult
import com.everettpenne.thumper.data.api.Failure
import com.everettpenne.thumper.data.model.FeedContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI

/** The answer to "has this feed changed?": new content, or the server saying nothing has. */
sealed interface FeedFetch {
    data class Updated(val content: FeedContent) : FeedFetch
    data object Unchanged : FeedFetch
}

/**
 * Downloads and reads feeds. It asks politely (ETag / If-Modified-Since, so an unchanged feed costs a few hundred bytes), follows redirects, refuses
 * anything over [MAX_BYTES], and if the address is a web page that points to a feed (`<link rel="alternate" type="application/rss+xml">`) it follows
 * that, so pasting a blog's address works as well as pasting its feed's.
 */
class FeedFetcher(private val client: OkHttpClient, private val userAgent: String = "Thumper/0.1 (Android podcast and feed reader)") {

    /**
     * Fetches the feed at an address as typed. With no scheme (`example.com/feed`) https is tried first and, if nothing answers there, plain http: a
     * feed on a home server or an old site often has no certificate, and the person should not have to know to type `http://`.
     */
    suspend fun fetch(inputUrl: String, previous: FeedContent? = null, now: Long = System.currentTimeMillis()): ApiResult<FeedFetch> {
        val url = normalizeUrl(inputUrl)
        // With http as the fallback, the https try must not take long to give up: a server that doesn't speak TLS on its port may simply never answer.
        val quick = if (hasScheme(inputUrl)) client else client.newBuilder().connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS).readTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build()
        val first = fetchInternal(url, previous, now, followDiscovery = true, quick)
        if (hasScheme(inputUrl) || first !is ApiResult.Failed || first.reason != Failure.OFFLINE || !url.startsWith("https://")) return first
        return fetchInternal("http://" + url.removePrefix("https://"), previous, now, followDiscovery = true).let { if (it is ApiResult.Failed && it.reason == Failure.OFFLINE) first else it }
    }

    private fun hasScheme(input: String) = input.trim().let { t -> listOf("http://", "https://", "feed:", "pcast:", "podcast:", "itpc:").any { t.startsWith(it, true) } }

    private suspend fun fetchInternal(url: String, previous: FeedContent?, now: Long, followDiscovery: Boolean, http: OkHttpClient = client): ApiResult<FeedFetch> {
        val httpUrl = url.toHttpUrlOrNull() ?: return ApiResult.Failed(Failure.NOT_A_FEED, "not a web address")
        val request = Request.Builder().url(httpUrl).header("User-Agent", userAgent)
            .header("Accept", "application/rss+xml, application/atom+xml, application/xml;q=0.9, text/xml;q=0.9, */*;q=0.5")
            .apply { previous?.etag?.let { header("If-None-Match", it) }; previous?.lastModified?.let { header("If-Modified-Since", it) } }.build()
        val fetched = try {
            runInterruptible(Dispatchers.IO) {
                http.newCall(request).execute().use { r ->
                    when {
                        r.code == 304 -> Raw(304, ByteArray(0), null, null, r.request.url.toString())
                        !r.isSuccessful -> Raw(r.code, ByteArray(0), null, null, r.request.url.toString())
                        else -> Raw(r.code, readCapped(r.body!!.byteStream()), r.header("ETag"), r.header("Last-Modified"), r.request.url.toString())
                    }
                }
            }
        } catch (e: TooLarge) { return ApiResult.Failed(Failure.TOO_LARGE) } catch (e: IOException) { return ApiResult.Failed(Failure.OFFLINE, e.message) }

        if (fetched.code == 304) return ApiResult.Ok(FeedFetch.Unchanged)
        if (fetched.code == 404 || fetched.code == 410) return ApiResult.Failed(Failure.NOT_FOUND)
        if (fetched.code !in 200..299) return ApiResult.Failed(Failure.BAD_RESPONSE, "HTTP ${fetched.code}")
        return try {
            // the feed's address is the one asked for (not where a redirect ended), so a subscription stays the same subscription
            val parsed = FeedParser.parse(ByteArrayInputStream(fetched.body), url, now)
            // A reading feed gets its site's icon as its logo, looked up once (on the first fetch) and kept from then on.
            val icon = previous?.feed?.iconUrl ?: if (parsed.feed.kind == com.everettpenne.thumper.data.model.FeedKind.ARTICLES) siteIcon(parsed.feed.siteUrl ?: url, http) else null
            val content = parsed.copy(feed = parsed.feed.copy(iconUrl = icon))
            ApiResult.Ok(FeedFetch.Updated(content.copy(etag = fetched.etag, lastModified = fetched.lastModified)))
        } catch (e: Exception) {
            val alt = if (followDiscovery) discover(String(fetched.body, Charsets.UTF_8).take(400_000), fetched.finalUrl) else null
            if (alt != null && alt != url) fetchInternal(alt, null, now, followDiscovery = false) else ApiResult.Failed(Failure.NOT_A_FEED, e.message)
        }
    }

    /**
     * The icon a site advertises on its home page (its apple-touch-icon, else its largest declared icon), or its /favicon.ico. Best effort: quick
     * timeouts, a small read, and any failure just means the list shows the feed's monogram instead.
     */
    private suspend fun siteIcon(siteUrl: String, http: OkHttpClient): String? {
        val home = siteUrl.toHttpUrlOrNull()?.let { u -> u.newBuilder().encodedPath("/").query(null).fragment(null).build() } ?: return null
        val fallback = home.resolve("/favicon.ico")?.toString()
        val quick = http.newBuilder().connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS).readTimeout(6, java.util.concurrent.TimeUnit.SECONDS).build()
        return try {
            runInterruptible(Dispatchers.IO) {
                quick.newCall(Request.Builder().url(home).header("User-Agent", userAgent).header("Accept", "text/html").build()).execute().use { r ->
                    if (!r.isSuccessful) return@use fallback
                    val head = r.body?.source()?.let { src -> src.request(200_000); src.buffer.snapshot().utf8().take(200_000) }.orEmpty()
                    discoverIcon(head, r.request.url.toString()) ?: fallback
                }
            }
        } catch (_: Exception) { fallback }
    }

    private class Raw(val code: Int, val body: ByteArray, val etag: String?, val lastModified: String?, val finalUrl: String)
    private class TooLarge : IOException("too large")

    private fun readCapped(input: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16 * 1024); var total = 0
        while (true) { val n = input.read(buf); if (n < 0) break; total += n; if (total > MAX_BYTES) throw TooLarge(); out.write(buf, 0, n) }
        return out.toByteArray()
    }

    companion object {
        const val MAX_BYTES = 15 * 1024 * 1024

        /** What a person types or pastes to an address: `feed:https://x`, `feed://x`, `pcast://x`, or a bare `example.com/feed.xml`. */
        fun normalizeUrl(input: String): String {
            var t = input.trim()
            for (scheme in listOf("feed:", "pcast:", "podcast:", "itpc:")) if (t.startsWith(scheme, true)) { t = t.substring(scheme.length); break }
            if (t.startsWith("//")) t = "https:$t"
            if (!t.contains("://")) t = "https://$t"
            return t
        }

        private val LINK = Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE)

        /** A page's best icon for a small square logo: apple-touch-icon first (large and square), then the largest declared icon; SVG is passed over. */
        fun discoverIcon(html: String, baseUrl: String): String? {
            val found = LINK.findAll(html).mapNotNull { m ->
                val tag = m.value
                val rel = Regex("""\brel\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE).find(tag)?.let { it.groupValues[1].ifEmpty { it.groupValues[2].ifEmpty { it.groupValues[3] } } }?.lowercase() ?: return@mapNotNull null
                val href = Regex("""\bhref\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE).find(tag)?.let { it.groupValues[1].ifEmpty { it.groupValues[2].ifEmpty { it.groupValues[3] } } } ?: return@mapNotNull null
                if (href.isBlank() || href.lowercase().substringBefore('?').endsWith(".svg") || tag.contains("image/svg", true)) return@mapNotNull null
                val words = rel.split(' ')
                val size = Regex("""\bsizes\s*=\s*["']?(\d+)x\d+""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val score = when {
                    "apple-touch-icon" in words || "apple-touch-icon-precomposed" in words -> 10_000 + size
                    "icon" in words -> size
                    else -> return@mapNotNull null
                }
                href to score
            }.toList()
            val best = found.maxByOrNull { it.second } ?: return null
            return runCatching { URI(baseUrl).resolve(com.everettpenne.thumper.util.Html.decode(best.first).trim()).toString() }.getOrNull()
        }

        /** The first feed a web page advertises (RSS preferred over Atom, as in the page's own order), as an absolute address; null if none. */
        fun discover(html: String, baseUrl: String): String? {
            val found = LINK.findAll(html).mapNotNull { m ->
                val tag = m.value
                val rel = Regex("""\brel\s*=\s*["']?([^"'\s>]+)""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)?.lowercase()
                val type = Regex("""\btype\s*=\s*["']?([^"'\s>]+)""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)?.lowercase() ?: ""
                val href = Regex("""\bhref\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE).find(tag)?.let { it.groupValues[1].ifEmpty { it.groupValues[2].ifEmpty { it.groupValues[3] } } }
                if (rel == "alternate" && href != null && (type.contains("rss") || type.contains("atom") || type == "application/xml" || type == "text/xml")) href to type else null
            }.toList()
            val best = found.firstOrNull { it.second.contains("rss") } ?: found.firstOrNull() ?: return null
            return runCatching { URI(baseUrl).resolve(com.everettpenne.thumper.util.Html.decode(best.first)).toString() }.getOrNull()
        }
    }
}
