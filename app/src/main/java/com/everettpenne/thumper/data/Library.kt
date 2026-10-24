package com.everettpenne.thumper.data

import com.everettpenne.thumper.data.api.ApiResult
import com.everettpenne.thumper.data.api.Failure
import com.everettpenne.thumper.data.feed.FeedFetch
import com.everettpenne.thumper.data.feed.FeedFetcher
import com.everettpenne.thumper.data.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/** What a refresh of many feeds did: how many changed, and which could not be reached (with why). */
data class RefreshReport(val updated: Int, val unchanged: Int, val failed: List<Pair<Feed, Failure>>)

/**
 * The person's things, kept on the phone as plain JSON files: their subscriptions, what each feed held at its last fetch, how far they are through each
 * episode, and which articles they have read. Nothing here goes anywhere; there is no account and no server of ours. Writes are atomic (a temporary file,
 * then a rename), so a crash mid-save cannot leave a half-written library.
 */
class Library(
    private val dir: File,
    private val fetcher: FeedFetcher,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val saveDelayMs: Long = 1_500,
) {
    @Serializable private data class State(val progress: List<Progress> = emptyList(), val read: List<String> = emptyList(),
        /** The old podcast-only Up Next, kept so a downgrade still has its episodes. New builds read and write [mediaQueue]. */
        val queue: List<QueueEntry> = emptyList(), val saved: List<Article> = emptyList(),
        val mediaQueue: List<Playable> = emptyList())

    private val subscriptionsFile = File(dir, "subscriptions.json")
    private val stateFile = File(dir, "state.json")
    private val feedsDir = File(dir, "feeds").apply { mkdirs() }

    private val initial = readState()
    private val _feeds = MutableStateFlow(readSubscriptions())
    private val _contents = MutableStateFlow<Map<String, FeedContent>>(emptyMap())
    private val _progress = MutableStateFlow(initial.progress.associateBy { it.episodeId })
    private val _read = MutableStateFlow(initial.read.toSet())
    private val _saved = MutableStateFlow(initial.saved)
    private val _queue = MutableStateFlow(initial.mediaQueue.ifEmpty { initial.queue.map { Playable.Podcast(it.episode, it.feed) } })
    private var saveJob: Job? = null
    private val _newEpisodes = kotlinx.coroutines.flow.MutableSharedFlow<Pair<Feed, List<Episode>>>(extraBufferCapacity = 16)
    /** Emitted after a refresh finds episodes that were not there before (newest first), with the show: what auto-download listens for. */
    val newEpisodes: kotlinx.coroutines.flow.SharedFlow<Pair<Feed, List<Episode>>> = _newEpisodes

    /** The subscriptions, in the order they were added (newest last). */
    val feeds: StateFlow<List<Feed>> = _feeds.asStateFlow()
    /** What each subscribed feed held at its last fetch. Fills in shortly after start, then follows every refresh. */
    val contents: StateFlow<Map<String, FeedContent>> = _contents.asStateFlow()
    val progress: StateFlow<Map<String, Progress>> = _progress.asStateFlow()
    val read: StateFlow<Set<String>> = _read.asStateFlow()
    /** Articles saved to read later, newest saved first. A copy of each is kept, so it stays after its feed has moved on. */
    val saved: StateFlow<List<Article>> = _saved.asStateFlow()
    /** Up Next: everything that will play after the current item, in order. An item playing now is not in it. */
    val queue: StateFlow<List<Playable>> = _queue.asStateFlow()

    init { scope.launch { loadContents() } }

    // ---- subscriptions -----------------------------------------------------------------------------------------------------

    fun isSubscribed(feedId: String) = _feeds.value.any { it.id == feedId }

    /** Subscribes to a feed that has just been fetched, keeping what it holds. Subscribing twice does nothing. */
    fun subscribe(content: FeedContent) {
        if (isSubscribed(content.feed.id)) return
        val trimmed = trim(content).let { it.copy(feed = it.feed.copy(addedAt = clock())) }
        _feeds.update { it + trimmed.feed }
        _contents.update { it + (trimmed.feed.id to trimmed) }
        scope.launch(Dispatchers.IO) { writeContent(trimmed) }
        scheduleSave()
    }

    /** Subscribes by address without a successful fetch (an OPML import of a feed that is down right now): it is filled in at the next refresh. */
    fun subscribePlaceholder(url: String, title: String) {
        val id = stableId(url)
        if (isSubscribed(id)) return
        _feeds.update { it + Feed(id = id, url = url, title = title.ifBlank { url }, kind = FeedKind.PODCAST, addedAt = clock()) }
        scheduleSave()
    }

    fun unsubscribe(feedId: String) {
        _feeds.update { list -> list.filterNot { it.id == feedId } }
        _contents.update { it - feedId }
        scope.launch(Dispatchers.IO) { File(feedsDir, "$feedId.json").delete() }
        scheduleSave()
    }

    fun content(feedId: String): FeedContent? = _contents.value[feedId]

    /** Turns "download this show's new episodes" on or off. */
    fun setAutoDownload(feedId: String, on: Boolean) {
        _feeds.update { list -> list.map { if (it.id == feedId) it.copy(autoDownload = on) else it } }
        _contents.update { m -> m[feedId]?.let { c -> m + (feedId to c.copy(feed = c.feed.copy(autoDownload = on))) } ?: m }
        _contents.value[feedId]?.let { c -> scope.launch(Dispatchers.IO) { writeContent(c) } }
        scheduleSave()
    }

    // ---- refreshing --------------------------------------------------------------------------------------------------------

    /** Fetches one feed and stores what is new. A failure leaves the saved copy as it was. */
    suspend fun refresh(feedId: String): ApiResult<Boolean> {
        val feed = _feeds.value.firstOrNull { it.id == feedId } ?: return ApiResult.Failed(Failure.NOT_FOUND)
        val previous = _contents.value[feedId]
        return when (val r = fetcher.fetch(feed.url, previous, clock())) {
            is ApiResult.Failed -> r
            is ApiResult.Ok -> when (val f = r.value) {
                FeedFetch.Unchanged -> { previous?.let { store(it.copy(fetchedAt = clock())) }; ApiResult.Ok(false) }
                is FeedFetch.Updated -> {
                    val stored = trim(f.content).let { it.copy(feed = it.feed.copy(addedAt = feed.addedAt, autoDownload = feed.autoDownload, iconUrl = it.feed.iconUrl ?: previous?.feed?.iconUrl ?: feed.iconUrl)) }
                    store(stored)
                    if (previous != null) {                         // not the first fetch: whatever is now there that was not before is new
                        val seen = previous.episodes.map { it.id }.toSet()
                        stored.episodes.filter { it.id !in seen }.takeIf { it.isNotEmpty() }?.let { _newEpisodes.tryEmit(stored.feed to it) }
                    }
                    ApiResult.Ok(true)
                }
            }
        }
    }

    /** Refreshes every subscription, a few at a time. */
    suspend fun refreshAll(parallelism: Int = 4): RefreshReport = coroutineScope {
        val gate = Semaphore(parallelism)
        val results = _feeds.value.map { feed -> async { gate.withPermit { feed to refresh(feed.id) } } }.awaitAll()
        RefreshReport(
            updated = results.count { (it.second as? ApiResult.Ok)?.value == true }, unchanged = results.count { (it.second as? ApiResult.Ok)?.value == false },
            failed = results.mapNotNull { (f, r) -> (r as? ApiResult.Failed)?.let { f to it.reason } })
    }

    private fun store(content: FeedContent) {
        _feeds.update { list -> list.map { if (it.id == content.feed.id) content.feed else it } }
        _contents.update { it + (content.feed.id to content) }
        scope.launch(Dispatchers.IO) { writeContent(content) }
        scheduleSave()
    }

    /** A subscription keeps its newest episodes and articles (a long-running show has thousands); show notes are capped. */
    private fun trim(c: FeedContent) = c.copy(
        episodes = c.episodes.take(MAX_EPISODES).map { if (it.summaryHtml.length > MAX_NOTES) it.copy(summaryHtml = it.summaryHtml.take(MAX_NOTES)) else it },
        articles = c.articles.take(MAX_ARTICLES).map { if (it.contentHtml.length > MAX_BODY) it.copy(contentHtml = it.contentHtml.take(MAX_BODY)) else it })

    // ---- listening and reading state ---------------------------------------------------------------------------------------

    fun progress(episodeId: String): Progress? = _progress.value[episodeId]

    /** Records where the listener is. Within the last stretch (or at 97%) the episode counts as played. */
    fun updateProgress(episodeId: String, positionMs: Long, durationMs: Long) {
        if (positionMs < 0) return
        val finished = durationMs > 0 && (positionMs >= durationMs - FINISHED_WITHIN_MS || positionMs >= durationMs * 0.97)
        _progress.update { it + (episodeId to Progress(episodeId, positionMs, durationMs, played = finished, updatedAt = clock())) }
        scheduleSave()
    }

    fun markPlayed(episodeId: String, played: Boolean) {
        val old = _progress.value[episodeId]
        _progress.update { it + (episodeId to (old ?: Progress(episodeId)).copy(played = played, positionMs = if (played) old?.positionMs ?: 0 else 0, updatedAt = clock())) }
        scheduleSave()
    }

    fun markRead(articleId: String, read: Boolean = true) { _read.update { if (read) it + articleId else it - articleId }; scheduleSave() }
    fun markAllRead(articleIds: Collection<String>) { _read.update { it + articleIds }; scheduleSave() }
    fun isSaved(articleId: String) = _saved.value.any { it.id == articleId }
    /** Saves an article to read later, or takes it off the list. */
    fun setSaved(article: Article, saved: Boolean) {
        _saved.update { list -> if (saved) listOf(article) + list.filterNot { it.id == article.id } else list.filterNot { it.id == article.id } }; scheduleSave()
    }

    /** Removes every subscription, saved feed, listening position and read mark. (For a fresh start, and for tests.) */
    suspend fun reset() {
        _feeds.value = emptyList(); _contents.value = emptyMap(); _progress.value = emptyMap(); _read.value = emptySet(); _queue.value = emptyList(); _saved.value = emptyList()
        withContext(Dispatchers.IO) { feedsDir.listFiles()?.forEach { it.delete() } }
        flush()
    }

    // ---- up next -----------------------------------------------------------------------------------------------------------

    /** Adds an episode to Up Next: at the front ([next]) or the end. An episode already queued moves rather than appearing twice. */
    fun enqueue(episode: Episode, feed: Feed, next: Boolean = false) =
        enqueue(Playable.Podcast(episode.copy(summaryHtml = episode.summaryHtml.take(MAX_NOTES)), feed), next)

    /** Adds any playable (an episode or a song) to the one Up Next: at the front ([next]) or the end. */
    fun enqueue(entry: Playable, next: Boolean = false) {
        _queue.update { q -> val rest = q.filterNot { it.id == entry.id }; if (next) listOf(entry) + rest else rest + entry }
        scheduleSave()
    }

    fun dequeue(id: String) { if (_queue.value.any { it.id == id }) { _queue.update { q -> q.filterNot { it.id == id } }; scheduleSave() } }
    fun isQueued(id: String) = _queue.value.any { it.id == id }
    fun clearQueue() { _queue.value = emptyList(); scheduleSave() }

    /** Moves the entry at [from] to position [to] (both indices into the current queue; out-of-range moves are ignored). */
    fun moveInQueue(from: Int, to: Int) {
        _queue.update { q ->
            if (from !in q.indices || to !in q.indices || from == to) q else q.toMutableList().apply { add(to, removeAt(from)) }
        }
        scheduleSave()
    }

    // ---- files -------------------------------------------------------------------------------------------------------------

    private suspend fun loadContents() = withContext(Dispatchers.IO) {
        val loaded = HashMap<String, FeedContent>()
        for (feed in _feeds.value) runCatching { loaded[feed.id] = ThumperJson.decodeFromString<FeedContent>(File(feedsDir, "${feed.id}.json").readText()) }
        // only feeds that are still subscribed: the load runs in the background at start, and a subscription removed (or the library reset) meanwhile must stay gone
        _contents.update { current -> loaded.filterKeys { id -> _feeds.value.any { it.id == id } } + current }
    }

    private fun writeContent(c: FeedContent) = atomicWrite(File(feedsDir, "${c.feed.id}.json"), ThumperJson.encodeToString(FeedContent.serializer(), c))

    private fun readSubscriptions(): List<Feed> = runCatching { ThumperJson.decodeFromString(ListSerializer(Feed.serializer()), subscriptionsFile.readText()) }.getOrDefault(emptyList())
    private fun readState(): State = runCatching { ThumperJson.decodeFromString(State.serializer(), stateFile.readText()) }.getOrDefault(State())

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch { delay(saveDelayMs); flush() }
    }

    /** Writes the subscriptions and state now (the delayed save is for the busy moments, such as progress ticking while playing). */
    suspend fun flush() = withContext(Dispatchers.IO + NonCancellable) {
        atomicWrite(subscriptionsFile, ThumperJson.encodeToString(ListSerializer(Feed.serializer()), _feeds.value))
        val podcasts = _queue.value.filterIsInstance<Playable.Podcast>().map { QueueEntry(it.episode, it.feed) }
        atomicWrite(stateFile, ThumperJson.encodeToString(State.serializer(), State(
            progress = _progress.value.values.toList(), read = _read.value.toList(), queue = podcasts,
            saved = _saved.value, mediaQueue = _queue.value,
        )))
    }

    private fun atomicWrite(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text); if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
    }

    companion object {
        const val MAX_EPISODES = 200; const val MAX_ARTICLES = 150; const val MAX_NOTES = 6_000; const val MAX_BODY = 100_000
        const val FINISHED_WITHIN_MS = 15_000L
    }
}

/** The lists the screens show, derived from the library's contents and state. Pure functions: easy to test, nothing to keep in sync. */
object Derived {
    /** The newest unplayed episodes across all podcasts, newest first. */
    fun newEpisodes(contents: Map<String, FeedContent>, progress: Map<String, Progress>, limit: Int = 40): List<Pair<Episode, Feed>> =
        contents.values.filter { it.feed.kind == FeedKind.PODCAST }.flatMap { c -> c.episodes.filter { progress[it.id]?.played != true }.map { it to c.feed } }
            .sortedByDescending { it.first.publishedAt }.take(limit)

    /** Episodes started and not finished, most recently listened to first. */
    fun inProgress(contents: Map<String, FeedContent>, progress: Map<String, Progress>): List<Pair<Episode, Feed>> {
        val byId = contents.values.flatMap { c -> c.episodes.map { it.id to (it to c.feed) } }.toMap()
        return progress.values.filter { !it.played && it.positionMs > 5_000 }.sortedByDescending { it.updatedAt }.mapNotNull { byId[it.episodeId] }
    }

    /** The newest articles across all reading feeds, newest first (undated ones last). */
    fun articles(contents: Map<String, FeedContent>, limit: Int = 200): List<Pair<Article, Feed>> =
        contents.values.flatMap { c -> c.articles.map { it to c.feed } }.sortedByDescending { it.first.publishedAt }.take(limit)
}
