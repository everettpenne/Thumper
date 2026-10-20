package com.everettpenne.thumper.data.feed

import com.everettpenne.thumper.data.model.*
import com.everettpenne.thumper.util.Html
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Reads a feed: RSS 2.0 (with the iTunes and Podcasting tags), Atom, and RSS 1.0, from a stream, without holding the document in memory. Items with
 * audio are episodes; items without are articles; the feed is a podcast when most of its items have audio. Nothing here trusts the document:
 * every field may be missing, dates come in a dozen shapes, and a broken item is skipped rather than losing the feed.
 */
object FeedParser {
    private const val MAX_ITEMS = 500

    class FeedFormatException(message: String) : Exception(message)

    private class RawItem {
        var title = ""; var link: String? = null; var guid: String? = null; var date: String? = null
        var description = ""; var content = ""; var summary = ""; var duration: String? = null; var image: String? = null; var author = ""
        var audioUrl: String? = null; var audioType: String? = null; var imageEnclosure: String? = null; var season: Int? = null; var number: Int? = null
    }

    fun parse(input: InputStream, feedUrl: String, now: Long = System.currentTimeMillis()): FeedContent {
        val p = XmlPullParserFactory.newInstance().newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, null)
        var title = ""; var author = ""; var description = ""; var image: String? = null; var site: String? = null
        val items = ArrayList<RawItem>(); var sawRoot = false

        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val name = p.name
                when {
                    name == "rss" || name == "feed" || name == "rdf:RDF" || name == "RDF" -> sawRoot = true
                    name == "item" || name == "entry" -> { runCatching { items += readItem(p, feedUrl) }.onFailure { /* a broken item costs only itself */ } }
                    name == "title" && title.isEmpty() -> title = text(p).trim()
                    name == "link" -> {
                        val href = p.getAttributeValue(null, "href")
                        if (href != null) { if (site == null && (p.getAttributeValue(null, "rel") ?: "alternate") == "alternate") site = resolve(feedUrl, href) ; skip(p) }
                        else if (site == null) site = resolve(feedUrl, text(p).trim()) else skip(p)
                    }
                    name == "description" || name == "subtitle" || name == "itunes:summary" -> { val t = text(p).trim(); if (description.isEmpty() || name == "description") description = t.ifEmpty { description } }
                    name == "itunes:author" || name == "dc:creator" -> { val t = text(p).trim(); if (author.isEmpty()) author = t }
                    name == "name" -> { val t = text(p).trim(); if (author.isEmpty()) author = t }          // an Atom <author><name>
                    name == "itunes:image" -> { p.getAttributeValue(null, "href")?.let { image = resolve(feedUrl, it) }; skip(p) }
                    name == "image" -> image = image ?: readImageUrl(p, feedUrl)
                    name == "logo" || name == "icon" -> { val t = text(p).trim(); if (image == null && t.isNotEmpty()) image = resolve(feedUrl, t) }
                }
            }
            event = p.next()
        }
        if (!sawRoot) throw FeedFormatException("That isn't a feed: no RSS or Atom document was found.")

        val id = stableId(feedUrl)
        val episodes = ArrayList<Episode>(); val articles = ArrayList<Article>()
        for (item in items) {
            val key = item.guid ?: item.audioUrl ?: item.link ?: item.title
            val published = parseDate(item.date)
            if (item.audioUrl != null) episodes += Episode(
                id = stableId("$id|$key"), feedId = id, title = item.title.ifBlank { "Untitled episode" }, audioUrl = item.audioUrl!!, publishedAt = published,
                durationSec = parseDuration(item.duration), summaryHtml = item.content.ifBlank { item.description.ifBlank { item.summary } }, imageUrl = item.image,
                link = item.link, mimeType = item.audioType, season = item.season, number = item.number)
            else {
                val body = item.content.ifBlank { item.description.ifBlank { item.summary } }
                articles += Article(
                    id = stableId("$id|$key"), feedId = id, title = item.title.ifBlank { titleFrom(body) }, contentHtml = body,
                    link = item.link, publishedAt = published, imageUrl = item.image ?: item.imageEnclosure ?: firstImage(body, item.link ?: feedUrl), author = item.author)
            }
        }
        val kind = if (episodes.isNotEmpty() && episodes.size * 2 >= items.size) FeedKind.PODCAST else FeedKind.ARTICLES
        val feed = Feed(id = id, url = feedUrl, title = title.ifBlank { feedUrl }, author = author, description = description, imageUrl = image, siteUrl = site, kind = kind, addedAt = now)
        return FeedContent(feed,
            episodes = episodes.sortedByDescending { it.publishedAt }.take(MAX_ITEMS),
            articles = articles.sortedByDescending { it.publishedAt }.take(MAX_ITEMS), fetchedAt = now)
    }

    private fun readItem(p: XmlPullParser, base: String): RawItem {
        val it = RawItem(); val depth = p.depth
        while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            when (val n = p.name) {
                "title" -> it.title = text(p).trim()
                "link" -> {
                    val href = p.getAttributeValue(null, "href")
                    if (href != null) {
                        val rel = p.getAttributeValue(null, "rel") ?: "alternate"; val type = p.getAttributeValue(null, "type") ?: ""
                        if (rel == "enclosure") setEnclosure(it, href, type, base) else if (rel == "alternate" && it.link == null) it.link = resolve(base, href)
                        skip(p)
                    } else { val t = text(p).trim(); if (t.isNotEmpty() && it.link == null) it.link = resolve(base, t) }
                }
                "guid", "id" -> it.guid = text(p).trim().ifEmpty { null }
                "pubDate", "published", "updated", "dc:date" -> { val t = text(p).trim(); if (it.date == null || n == "pubDate" || n == "published") it.date = t }
                "description", "media:description" -> { val t = text(p); if (n == "description" || it.description.isEmpty()) it.description = t }
                // YouTube (and other Media RSS feeds) wrap the thumbnail and the description in a group: read inside it rather than skip it
                "media:group" -> Unit
                "summary", "itunes:summary" -> { val t = text(p); if (it.summary.isEmpty()) it.summary = t }
                "content:encoded", "content" -> it.content = text(p)
                "itunes:duration" -> it.duration = text(p).trim()
                "itunes:season" -> it.season = text(p).trim().toIntOrNull()
                "itunes:episode" -> it.number = text(p).trim().toIntOrNull()
                "itunes:author", "dc:creator", "author" -> { val t = text(p).trim(); if (it.author.isEmpty()) it.author = t }
                "itunes:image" -> { p.getAttributeValue(null, "href")?.let { h -> if (it.image == null) it.image = resolve(base, h) }; skip(p) }
                "media:thumbnail" -> { p.getAttributeValue(null, "url")?.let { u -> if (it.image == null) it.image = resolve(base, u) }; skip(p) }
                "media:content" -> {
                    val url = p.getAttributeValue(null, "url"); val type = p.getAttributeValue(null, "type") ?: ""; val medium = p.getAttributeValue(null, "medium") ?: ""
                    if (url != null) if (type.startsWith("image/") || medium == "image") { if (it.image == null) it.image = resolve(base, url) } else setEnclosure(it, url, if (medium == "audio" && type.isEmpty()) "audio/mpeg" else type, base)
                    skip(p)
                }
                "enclosure" -> { setEnclosure(it, p.getAttributeValue(null, "url"), p.getAttributeValue(null, "type") ?: "", base); skip(p) }
                else -> skip(p)
            }
        }
        return it
    }

    /**
     * A headline for a post that has none (Mastodon, micro.blog, Bluesky bridges and other short-form feeds send only the text): its first sentence,
     * or the start of its text, kept short enough for a list row. "Untitled" only when the post has no text at all (a bare picture).
     */
    internal fun titleFrom(html: String, maxChars: Int = 90): String {
        val flat = Html.plain(html).replace(Regex("\\s+"), " ").trim()
        if (flat.isEmpty()) return "Untitled"
        val sentence = Regex("""^(.+?[.!?…])(\s|$)""").find(flat)?.groupValues?.get(1)?.takeIf { it.length >= 12 } ?: flat
        if (sentence.length <= maxChars) return sentence
        val cut = sentence.substring(0, maxChars); val space = cut.lastIndexOf(' ')
        return (if (space > maxChars / 2) cut.substring(0, space) else cut).trimEnd(',', ';', ':', ' ') + "…"
    }

    private val IMG = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val SRC = Regex("""\bsrc\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)
    private val TINY = Regex("""\b(width|height)\s*=\s*["']?[0-3]["'\s>/]""", RegexOption.IGNORE_CASE)

    /** The first real picture in a post's HTML, for its thumbnail: tracking pixels (1x1, feedburner, stats counters) and emoji are passed over. */
    internal fun firstImage(html: String, base: String): String? {
        for (m in IMG.findAll(html)) {
            val tag = m.value
            if (TINY.containsMatchIn(tag)) continue
            val src = SRC.find(tag)?.let { it.groupValues[1].ifEmpty { it.groupValues[2].ifEmpty { it.groupValues[3] } } }?.let(Html::decode)?.trim() ?: continue
            if (src.isEmpty() || src.startsWith("data:")) continue
            val lower = src.lowercase()
            if (listOf("feedburner", "/stats", "pixel", "tracker", "emoji", "gravatar.com/avatar", "/smilies/").any { it in lower }) continue
            return resolve(base, src)
        }
        return null
    }

    private val AUDIO_EXT = Regex("""\.(mp3|m4a|aac|ogg|oga|opus|wav|flac|mp4|m4b)(\?|#|$)""", RegexOption.IGNORE_CASE)

    private fun setEnclosure(item: RawItem, url: String?, type: String, base: String) {
        if (url.isNullOrBlank()) return
        val abs = resolve(base, url.trim())
        when {
            type.startsWith("image/") -> if (item.imageEnclosure == null) item.imageEnclosure = abs
            type.startsWith("audio/") || type.startsWith("video/") || (type.isEmpty() || type == "application/octet-stream") && AUDIO_EXT.containsMatchIn(abs) ->
                if (item.audioUrl == null) { item.audioUrl = abs; item.audioType = type.ifEmpty { null } }
        }
    }

    private fun readImageUrl(p: XmlPullParser, base: String): String? {
        val depth = p.depth; var url: String? = null
        while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
            if (p.eventType == XmlPullParser.START_TAG) if (p.name == "url") url = text(p).trim().ifEmpty { null } else skip(p)
        }
        return url?.let { resolve(base, it) }
    }

    /** The text inside the element the parser is on (CDATA included), tags inside it dropped; leaves the parser on the element's end tag. */
    private fun text(p: XmlPullParser): String {
        val sb = StringBuilder(); val depth = p.depth
        while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
            if (p.eventType == XmlPullParser.TEXT || p.eventType == XmlPullParser.CDSECT || p.eventType == XmlPullParser.ENTITY_REF) sb.append(p.text)
            if (p.eventType == XmlPullParser.END_DOCUMENT) break
        }
        return sb.toString()
    }

    private fun skip(p: XmlPullParser) {
        if (p.eventType != XmlPullParser.START_TAG) return
        var depth = 1
        while (depth != 0) when (p.next()) { XmlPullParser.END_TAG -> depth--; XmlPullParser.START_TAG -> depth++; XmlPullParser.END_DOCUMENT -> return }
    }

    private fun resolve(base: String, ref: String): String = runCatching { URI(base).resolve(ref.trim()).toString() }.getOrDefault(ref.trim())

    // ---- values --------------------------------------------------------------------------------------------------------------

    /** "1:02:03", "45:10" or "2750" (seconds) to seconds; null for anything else. */
    fun parseDuration(text: String?): Int? {
        val t = text?.trim().orEmpty(); if (t.isEmpty()) return null
        val parts = t.split(":")
        if (parts.size > 3 || parts.any { it.isEmpty() }) return null
        val nums = parts.map { it.toDoubleOrNull() ?: return null }
        return nums.fold(0.0) { acc, v -> acc * 60 + v }.toInt().takeIf { it > 0 }
    }

    private val RFC_PATTERNS = listOf("EEE, dd MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm Z", "dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm:ss zzz", "EEE, d MMM yyyy HH:mm:ss zzz", "EEE dd MMM yyyy HH:mm:ss Z", "yyyy-MM-dd HH:mm:ss Z", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd")

    /** Dates as feeds write them: RFC 822 (with its many near-misses) or ISO 8601. Returns 0 when it can't tell. */
    fun parseDate(text: String?): Long {
        val t = text?.trim().orEmpty(); if (t.isEmpty()) return 0
        runCatching { return OffsetDateTime.parse(t).toInstant().toEpochMilli() }
        runCatching { return Instant.parse(t).toEpochMilli() }
        for (pattern in RFC_PATTERNS) {
            val f = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = true }
            runCatching { return f.parse(t)!!.time }
        }
        return 0
    }
}
