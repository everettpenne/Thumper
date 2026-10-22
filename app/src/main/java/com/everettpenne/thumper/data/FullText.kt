package com.everettpenne.thumper.data

import com.everettpenne.thumper.data.feed.ArticleText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The full text of articles whose feeds send only a teaser. The page is fetched from the article's own address (on the site the person
 * subscribed to; nothing goes anywhere else) and its article lifted out by [ArticleText]. Results are kept in the app's cache, so an article
 * opened twice, or offline after the first time, reads at once; Android may clear the cache when space is short, and then it is fetched again.
 */
class FullText(private val http: OkHttpClient, cacheDir: File) {
    private val dir = File(cacheDir, "fulltext").apply { mkdirs() }
    private val memory = ConcurrentHashMap<String, String>()

    /** What is known already, without fetching: for drawing the article at once when it was read before. */
    fun cached(articleId: String): String? = memory[articleId] ?: file(articleId).takeIf { it.isFile }?.readText()?.also { memory[articleId] = it }

    /** The article's full text, or null when the page could not be reached or held no article. */
    suspend fun load(articleId: String, link: String): String? = cached(articleId) ?: withContext(Dispatchers.IO) {
        val page = runCatching {
            http.newCall(Request.Builder().url(link).header("User-Agent", "Mozilla/5.0 (Android; Mobile) Thumper").header("Accept", "text/html").build()).execute().use { r ->
                if (!r.isSuccessful) null
                // A page bigger than any article (a file, a video) is not read in.
                else r.body?.takeIf { (it.contentType()?.subtype ?: "html").contains("html") && it.contentLength() < 6_000_000 }?.string()
            }
        }.getOrNull() ?: return@withContext null
        val text = ArticleText.extract(page, link) ?: return@withContext null
        memory[articleId] = text
        runCatching { file(articleId).writeText(text) }
        text
    }

    private fun file(articleId: String) = File(dir, articleId.hashCode().toUInt().toString(16) + "-" + articleId.length + ".html")
}
