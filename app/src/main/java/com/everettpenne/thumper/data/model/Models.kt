package com.everettpenne.thumper.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** Feeds are read from the web and saved to the phone, so be lenient reading what was saved by an older version, and never fail on a missing field. */
val ThumperJson = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false; encodeDefaults = true }

/** What a feed is for: shows you listen to, or articles you read. Decided from what is in the feed ([com.everettpenne.thumper.data.feed.FeedParser]). */
@Serializable enum class FeedKind { PODCAST, ARTICLES }

@Serializable data class Feed(
    val id: String,
    val url: String,
    val title: String,
    val author: String = "",
    val description: String = "",
    val imageUrl: String? = null,
    val siteUrl: String? = null,
    val kind: FeedKind = FeedKind.PODCAST,
    val addedAt: Long = 0,
    /** Download this show's new episodes as they arrive (on the next refresh while the app is open). */
    val autoDownload: Boolean = false,
)

/** One episode of a podcast: what to play and what to show. [summaryHtml] is the show notes as the publisher wrote them (HTML). */
@Serializable data class Episode(
    val id: String,
    val feedId: String,
    val title: String,
    val audioUrl: String,
    val publishedAt: Long = 0,
    val durationSec: Int? = null,
    val summaryHtml: String = "",
    val imageUrl: String? = null,
    val link: String? = null,
    val mimeType: String? = null,
    val season: Int? = null,
    val number: Int? = null,
)

/** One article from a feed that is read rather than listened to. [contentHtml] is the full text when the feed carries it, else its summary. */
@Serializable data class Article(
    val id: String,
    val feedId: String,
    val title: String,
    val contentHtml: String = "",
    val link: String? = null,
    val publishedAt: Long = 0,
    val imageUrl: String? = null,
    val author: String = "",
)

/** A feed and everything in it at the last fetch, with the validators for asking the server "has it changed?" next time. */
@Serializable data class FeedContent(
    val feed: Feed,
    val episodes: List<Episode> = emptyList(),
    val articles: List<Article> = emptyList(),
    val etag: String? = null,
    val lastModified: String? = null,
    val fetchedAt: Long = 0,
)

/** Where the listener is in an episode. [played] is set when it is finished or marked so; a played episode stays out of Up Next. */
@Serializable data class Progress(
    val episodeId: String,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val played: Boolean = false,
    val updatedAt: Long = 0,
) {
    /** How far through, 0..1, or null when not started or finished. */
    val fraction: Float? get() = if (!played && durationMs > 0 && positionMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else null
}

/**
 * One place in Up Next. It carries its own copy of the episode and its show, so it survives the feed trimming its old episodes, and works for a show you
 * queued from a search without subscribing.
 */
@Serializable data class QueueEntry(val episode: Episode, val feed: Feed)

/** A show found in a directory (Apple's, or Podcast Index): enough to show a row and to subscribe by its feed address. */
data class DirectoryShow(val title: String, val author: String, val feedUrl: String, val imageUrl: String?, val description: String = "", val genre: String = "")

/** A short stable id from text: the first 16 hex digits of its SHA-256. Feeds are identified by their address, episodes by the feed and the guid. */
fun stableId(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
