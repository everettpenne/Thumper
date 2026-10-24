package com.everettpenne.thumper

import com.everettpenne.thumper.data.Derived
import com.everettpenne.thumper.data.Library
import com.everettpenne.thumper.data.api.*
import com.everettpenne.thumper.data.feed.FeedFetch
import com.everettpenne.thumper.data.feed.FeedFetcher
import com.everettpenne.thumper.data.feed.Opml
import com.everettpenne.thumper.data.model.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataLayerTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient()
    private fun resource(name: String) = checkNotNull(javaClass.classLoader!!.getResourceAsStream(name)).readBytes().decodeToString()
    private fun url(path: String) = server.url(path).toString()
    private fun base() = server.url("/").toString().trimEnd('/')       // the form the directories are configured with: no trailing slash
    @get:Rule val tmp = TemporaryFolder()

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    // ---- fetcher -----------------------------------------------------------------------------------------------------------

    @Test fun `a feed is fetched, and asked for again with its validators`() = runBlocking {
        val fetcher = FeedFetcher(client)
        server.enqueue(MockResponse().setBody(resource("podcast-mixed.xml")).setHeader("ETag", "\"v1\"").setHeader("Last-Modified", "Wed, 07 Oct 2026 10:00:00 GMT"))
        val first = (fetcher.fetch(url("/feed.xml")) as ApiResult.Ok).value as FeedFetch.Updated
        assertEquals("\"v1\"", first.content.etag); assertEquals(3, first.content.episodes.size)
        assertEquals("the subscription keeps the address it was given", url("/feed.xml"), first.content.feed.url)
        server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(304))
        assertEquals(FeedFetch.Unchanged, (fetcher.fetch(url("/feed.xml"), first.content) as ApiResult.Ok).value)
        val second = server.takeRequest()
        assertEquals("\"v1\"", second.getHeader("If-None-Match")); assertEquals("Wed, 07 Oct 2026 10:00:00 GMT", second.getHeader("If-Modified-Since"))
        assertTrue(second.getHeader("User-Agent")!!.startsWith("Thumper"))
    }

    @Test fun `failures are told apart`() = runBlocking {
        val fetcher = FeedFetcher(client)
        server.enqueue(MockResponse().setResponseCode(404)); assertEquals(Failure.NOT_FOUND, (fetcher.fetch(url("/a")) as ApiResult.Failed).reason)
        server.enqueue(MockResponse().setResponseCode(503)); assertEquals(Failure.BAD_RESPONSE, (fetcher.fetch(url("/b")) as ApiResult.Failed).reason)
        server.enqueue(MockResponse().setBody("<html><body>nothing here</body></html>")); assertEquals(Failure.NOT_A_FEED, (fetcher.fetch(url("/c")) as ApiResult.Failed).reason)
        val dead = url("/d"); server.shutdown()
        assertEquals(Failure.OFFLINE, (fetcher.fetch(dead) as ApiResult.Failed).reason)
    }

    @Test fun `a web page that advertises a feed leads to it`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.path) {
                "/blog" -> MockResponse().setBody("""<html><head><link rel="stylesheet" href="/s.css"><link rel="alternate" type="application/atom+xml" href="/atom.xml"><link rel="alternate" type="application/rss+xml" href="/rss.xml"></head></html>""")
                "/rss.xml" -> MockResponse().setBody(resource("podcast-mixed.xml"))
                else -> MockResponse().setResponseCode(404)
            }
        }
        val r = (FeedFetcher(client).fetch(url("/blog")) as ApiResult.Ok).value as FeedFetch.Updated
        assertEquals("Spice & Sand", r.content.feed.title)
        assertEquals("the feed's own address is subscribed, not the page's", url("/rss.xml"), r.content.feed.url)
    }

    @Test fun `a feed over the size limit is refused`() = runBlocking {
        val big = okio.Buffer().apply { write(ByteArray(FeedFetcher.MAX_BYTES + 1024)) }
        server.enqueue(MockResponse().setBody(big))
        assertEquals(Failure.TOO_LARGE, (FeedFetcher(client).fetch(url("/big")) as ApiResult.Failed).reason)
    }

    @Test fun `addresses are tidied and feeds discovered in pages`() {
        assertEquals("https://x.example/f.xml", FeedFetcher.normalizeUrl(" feed:https://x.example/f.xml "))
        assertEquals("https://x.example/f", FeedFetcher.normalizeUrl("feed://x.example/f"))
        assertEquals("https://x.example/f", FeedFetcher.normalizeUrl("pcast://x.example/f"))
        assertEquals("https://x.example/f", FeedFetcher.normalizeUrl("x.example/f"))
        assertEquals("http://x.example/f", FeedFetcher.normalizeUrl("http://x.example/f"))
        assertEquals("https://x.example/feed?a=1&b=2", FeedFetcher.discover("""<link href='/feed?a=1&amp;b=2' rel='alternate' type='application/rss+xml'>""", "https://x.example/page"))
        assertNull(FeedFetcher.discover("<html>no links</html>", "https://x.example"))
        // a site's logo: the touch icon wins over smaller icons, SVG is passed over, and addresses are made absolute
        assertEquals("https://x.example/apple.png", FeedFetcher.discoverIcon("""<link rel="icon" href="/f.ico" sizes="32x32"><link rel="apple-touch-icon" href="/apple.png"><link rel="icon" href="/i.svg">""", "https://x.example/"))
        assertEquals("https://x.example/big.png", FeedFetcher.discoverIcon("""<link rel="icon" href="/small.png" sizes="16x16"><link rel="shortcut icon" href="big.png" sizes="192x192">""", "https://x.example/"))
        assertNull(FeedFetcher.discoverIcon("<link rel=\"stylesheet\" href=\"/a.css\">", "https://x.example/"))
    }

    @Test fun `a bare address falls back to plain http when https does not answer`() = runBlocking {
        server.enqueue(MockResponse().setBody(resource("podcast-mixed.xml")))
        val bare = "localhost:${server.port}/feed.xml"                      // no scheme: https is tried first, and the server only speaks http
        val ok = (FeedFetcher(client).fetch(bare) as ApiResult.Ok).value as FeedFetch.Updated
        assertEquals("http://$bare", ok.content.feed.url)
        // typed with a scheme, it is not second-guessed
        val dead = "https://localhost:${server.port}/feed.xml"
        assertEquals(Failure.OFFLINE, (FeedFetcher(client).fetch(dead) as ApiResult.Failed).reason)
    }

    // ---- directories -------------------------------------------------------------------------------------------------------

    @Test fun `apple search maps shows and drops those without a public feed`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"resultCount":3,"results":[
            {"collectionId":1,"collectionName":"Darknet Diaries","artistName":"Jack","feedUrl":"https://f.example/dd","artworkUrl600":"https://img/600.jpg","primaryGenreName":"Technology"},
            {"collectionId":2,"collectionName":"No Feed Show","artistName":"X"},
            {"collectionId":3,"trackName":"Dup","feedUrl":"https://f.example/dd"}]}"""))
        val shows = (AppleDirectory(client, searchBase = base()).search("dark net") as ApiResult.Ok).value
        assertEquals(1, shows.size); assertEquals("Darknet Diaries", shows[0].title); assertEquals("Technology", shows[0].genre)
        val q = server.takeRequest().path!!
        assertTrue(q.startsWith("/search?")); assertTrue(q.contains("term=dark+net") || q.contains("term=dark%20net")); assertTrue(q.contains("media=podcast"))
    }

    @Test fun `apple popular keeps the chart's order`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when {
                request.path!!.contains("/podcasts/top/") -> MockResponse().setBody("""{"feed":{"results":[{"id":"30"},{"id":"10"},{"id":"20"}]}}""")
                request.path!!.startsWith("/lookup") -> MockResponse().setBody("""{"results":[{"collectionId":10,"collectionName":"Ten","feedUrl":"https://f/10"},{"collectionId":20,"collectionName":"Twenty","feedUrl":"https://f/20"},{"collectionId":30,"collectionName":"Thirty","feedUrl":"https://f/30"}]}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val dir = AppleDirectory(client, searchBase = base(), chartBase = base(), country = { "gb" })
        assertEquals(listOf("Thirty", "Ten", "Twenty"), (dir.popular() as ApiResult.Ok).value.map { it.title })
        assertTrue(server.takeRequest().path!!.contains("/v2/gb/podcasts/top/"))
    }

    @Test fun `podcast index requests are signed as the service requires`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"true","feeds":[{"title":"Show","url":"https://f/x","author":"A","image":"https://i/1.jpg","artwork":"","categories":{"102":"Technology"}},{"title":"No url"}]}"""))
        val dir = PodcastIndexDirectory(client, credentials = { "KEY" to "SECRET" }, base = url("/api/1.0"), clock = { 1700000000 })
        val shows = (dir.search("hello world") as ApiResult.Ok).value
        assertEquals(1, shows.size); assertEquals("https://i/1.jpg", shows[0].imageUrl); assertEquals("Technology", shows[0].genre)
        val req = server.takeRequest()
        assertTrue(req.path!!.startsWith("/api/1.0/search/byterm?q=hello+world") || req.path!!.startsWith("/api/1.0/search/byterm?q=hello%20world"))
        assertEquals("KEY", req.getHeader("X-Auth-Key")); assertEquals("1700000000", req.getHeader("X-Auth-Date"))
        assertEquals("3f84a8bba4a7086d3c1fd8bcf7c97b8b4e1d1a6f".length, req.getHeader("Authorization")!!.length)
        assertEquals(java.security.MessageDigest.getInstance("SHA-1").digest("KEYSECRET1700000000".toByteArray()).joinToString("") { "%02x".format(it) }, req.getHeader("Authorization"))
    }

    @Test fun `podcast index without keys asks for them, and rejected keys are reported`() = runBlocking {
        assertEquals(Failure.NEEDS_KEYS, (PodcastIndexDirectory(client, credentials = { null }, base = url("")).popular() as ApiResult.Failed).reason)
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(Failure.KEYS_REJECTED, (PodcastIndexDirectory(client, credentials = { "a" to "b" }, base = url("")).search("x") as ApiResult.Failed).reason)
    }

    // ---- OPML --------------------------------------------------------------------------------------------------------------

    @Test fun `opml is read, flattened and written back`() {
        val xml = """<?xml version="1.0"?><opml version="2.0"><head/><body>
            <outline text="Tech"><outline type="rss" text="A &amp; B" xmlUrl="https://a.example/feed" htmlUrl="https://a.example"/>
            <outline type="rss" title="Titled" text="ignored" xmlUrl="https://c.example/feed"/></outline>
            <outline type="rss" text="Dup" xmlUrl="https://a.example/feed"/><outline text="Folder without a feed"/></body></opml>"""
        val entries = Opml.parse(xml.byteInputStream())
        assertEquals(listOf("https://a.example/feed", "https://c.example/feed"), entries.map { it.feedUrl })
        assertEquals("A & B", entries[0].title); assertEquals("Titled", entries[1].title); assertEquals("https://a.example", entries[0].siteUrl)
        assertEquals(entries, Opml.parse(Opml.write(entries).byteInputStream()))
        try { Opml.parse("<rss/>".byteInputStream()); fail() } catch (e: IllegalArgumentException) { }
    }

    // ---- library -----------------------------------------------------------------------------------------------------------

    private fun newLibrary(scope: CoroutineScope) = Library(tmp.root, FeedFetcher(client), scope, clock = { 5_000 }, saveDelayMs = 10_000)

    @Test fun `subscriptions, content and progress survive a restart`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        server.enqueue(MockResponse().setBody(resource("podcast-mixed.xml")))
        val lib = newLibrary(scope)
        val content = ((FeedFetcher(client).fetch(url("/feed.xml")) as ApiResult.Ok).value as FeedFetch.Updated).content
        lib.subscribe(content); lib.subscribe(content)                         // twice: once
        assertEquals(1, lib.feeds.value.size)
        val ep = content.episodes.first()
        lib.updateProgress(ep.id, 60_000, 3_723_000); lib.markRead("article-1"); lib.flush()
        delay(300)                                                              // the content file is written on another thread
        val again = newLibrary(scope)
        assertEquals(listOf(content.feed.id), again.feeds.value.map { it.id })
        withTimeout(5_000) { while (again.content(content.feed.id) == null) delay(20) }
        assertEquals(3, again.content(content.feed.id)!!.episodes.size)
        assertEquals(60_000L, again.progress(ep.id)!!.positionMs); assertTrue("article-1" in again.read.value)
        again.unsubscribe(content.feed.id); again.flush(); delay(200)
        assertTrue(newLibrary(scope).feeds.value.isEmpty())
        scope.cancel()
    }

    @Test fun `a reset right after start is not undone by the background load`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // a big saved feed, so the background load at start takes a while and a reset can land in the middle of it
        val big = FeedContent(Feed("big", "https://big.example/feed", "Big", kind = FeedKind.PODCAST),
            episodes = (1..200).map { Episode("e$it", "big", "Episode $it", "https://a/$it.mp3", publishedAt = it.toLong(), summaryHtml = "x".repeat(5_000)) })
        val first = newLibrary(scope); first.subscribe(big); first.flush(); delay(500)
        var undone = 0
        for (i in 0 until 60) {
            val again = newLibrary(scope)
            delay(i % 12L)                                                          // the reset lands at a different moment of the load each time
            again.reset(); delay(250)                                               // (the load ends within this wait, whichever way the race went)
            if (again.contents.value.isNotEmpty() || again.feeds.value.isNotEmpty()) undone++
            first.subscribe(big); first.flush(); delay(120)                         // put the saved feed back for the next round
        }
        assertEquals("a reset must never be undone by the load that was already under way", 0, undone)
        scope.cancel()
    }

    @Test fun `refresh adds what is new and leaves the library alone on failure`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        server.enqueue(MockResponse().setBody(resource("podcast-mixed.xml")).setHeader("ETag", "a"))
        val content = ((FeedFetcher(client).fetch(url("/feed.xml")) as ApiResult.Ok).value as FeedFetch.Updated).content
        val lib = newLibrary(scope); lib.subscribe(content)
        val extra = resource("podcast-mixed.xml").replace("</channel>", """<item><title>Ep 3</title><guid>ep-3</guid><pubDate>Wed, 14 Oct 2026 10:00:00 +0000</pubDate><enclosure url="https://cdn.example/ep3.mp3" type="audio/mpeg"/></item></channel>""")
        server.enqueue(MockResponse().setBody(extra))
        assertEquals(true, (lib.refresh(content.feed.id) as ApiResult.Ok).value)
        assertEquals(4, lib.content(content.feed.id)!!.episodes.size)
        server.enqueue(MockResponse().setResponseCode(304))
        assertEquals(false, (lib.refresh(content.feed.id) as ApiResult.Ok).value)
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(lib.refresh(content.feed.id) is ApiResult.Failed)
        assertEquals("a failed refresh keeps what was saved", 4, lib.content(content.feed.id)!!.episodes.size)
        server.enqueue(MockResponse().setResponseCode(500))
        val report = lib.refreshAll()
        assertEquals(0, report.updated); assertEquals(1, report.failed.size)
        scope.cancel()
    }

    @Test fun `an episode is played when finished, and listening again clears it`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO); val lib = newLibrary(scope)
        lib.updateProgress("e", 100_000, 3_600_000); assertEquals(false, lib.progress("e")!!.played); assertNotNull(lib.progress("e")!!.fraction)
        lib.updateProgress("e", 3_590_000, 3_600_000); assertEquals(true, lib.progress("e")!!.played); assertNull(lib.progress("e")!!.fraction)
        lib.updateProgress("e", 50_000, 3_600_000); assertEquals("listening again reopens it", false, lib.progress("e")!!.played)
        lib.markPlayed("e", true); assertTrue(lib.progress("e")!!.played)
        lib.markPlayed("e", false); assertEquals(0L, lib.progress("e")!!.positionMs)
        scope.cancel()
    }

    private fun entry(n: Int) = Playable.Podcast(Episode("e$n", "f", "Episode $n", "https://a/$n.mp3", publishedAt = n.toLong()), Feed("f", "https://f", "Show"))

    @Test fun `up next keeps its order, never repeats an episode, and survives a restart`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO); val lib = newLibrary(scope)
        val (a, b, c, d) = (1..4).map { entry(it) }
        lib.enqueue(a.episode, a.feed); lib.enqueue(b.episode, b.feed); lib.enqueue(c.episode, c.feed)
        assertEquals(listOf("e1", "e2", "e3"), lib.queue.value.map { it.id })
        lib.enqueue(d.episode, d.feed, next = true); assertEquals(listOf("e4", "e1", "e2", "e3"), lib.queue.value.map { it.id })
        lib.enqueue(c.episode, c.feed, next = true); assertEquals("queued again: it moves, it doesn't repeat", listOf("e3", "e4", "e1", "e2"), lib.queue.value.map { it.id })
        lib.enqueue(a.episode, a.feed); assertEquals(listOf("e3", "e4", "e2", "e1"), lib.queue.value.map { it.id })
        lib.moveInQueue(3, 0); assertEquals(listOf("e1", "e3", "e4", "e2"), lib.queue.value.map { it.id })
        lib.moveInQueue(0, 2); assertEquals(listOf("e3", "e4", "e1", "e2"), lib.queue.value.map { it.id })
        lib.moveInQueue(0, 9); lib.moveInQueue(-1, 1); lib.moveInQueue(2, 2); assertEquals("bad moves change nothing", listOf("e3", "e4", "e1", "e2"), lib.queue.value.map { it.id })
        lib.dequeue("e4"); lib.dequeue("nope"); assertEquals(listOf("e3", "e1", "e2"), lib.queue.value.map { it.id }); assertTrue(lib.isQueued("e1")); assertFalse(lib.isQueued("e4"))
        lib.flush(); delay(200)
        val again = newLibrary(scope)
        assertEquals(listOf("e3", "e1", "e2"), again.queue.value.map { it.id })
        assertEquals("a queued episode carries its own copy of the show", "Show", (again.queue.value[0] as Playable.Podcast).feed.title)
        again.clearQueue(); again.flush(); delay(200); assertTrue(newLibrary(scope).queue.value.isEmpty())
        lib.enqueue(a.episode, a.feed); lib.reset(); assertTrue("a reset empties the queue too", lib.queue.value.isEmpty())
        scope.cancel()
    }

    @Test fun `downloaded files are named by episode, with the kind of file the address says`() {
        fun ep(url: String) = Episode("abc123", "f", "t", url)
        assertEquals("abc123.mp3", com.everettpenne.thumper.data.Downloads.fileNameFor(ep("https://cdn.example/show/ep1.mp3?token=1")))
        assertEquals("abc123.m4a", com.everettpenne.thumper.data.Downloads.fileNameFor(ep("https://cdn.example/ep1.M4A")))
        assertEquals("abc123.mp3", com.everettpenne.thumper.data.Downloads.fileNameFor(ep("https://cdn.example/stream?id=9")))
        assertEquals("abc123.opus", com.everettpenne.thumper.data.Downloads.fileNameFor(ep("https://cdn.example/a/b.opus#frag")))
    }

    @Test fun `a refresh reports the new episodes, never the first fetch, and keeps a show's auto-download choice`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        server.enqueue(MockResponse().setBody(resource("podcast-mixed.xml")))
        val content = ((FeedFetcher(client).fetch(url("/feed.xml")) as ApiResult.Ok).value as FeedFetch.Updated).content
        val lib = newLibrary(scope)
        val seen = java.util.concurrent.CopyOnWriteArrayList<Pair<Feed, List<Episode>>>()
        val collector = scope.launch { lib.newEpisodes.collect { seen += it } }
        delay(100)
        lib.subscribe(content); lib.setAutoDownload(content.feed.id, true)
        assertTrue(lib.feeds.value.single().autoDownload); assertTrue(lib.content(content.feed.id)!!.feed.autoDownload)
        val extra = resource("podcast-mixed.xml").replace("</channel>", """<item><title>Ep 3</title><guid>ep-3</guid><pubDate>Wed, 14 Oct 2026 10:00:00 +0000</pubDate><enclosure url="https://cdn.example/ep3.mp3" type="audio/mpeg"/></item></channel>""")
        server.enqueue(MockResponse().setBody(extra))
        lib.refresh(content.feed.id); delay(300)
        assertEquals("only the episode that was not there before", listOf("Ep 3"), seen.single().second.map { it.title })
        assertTrue("the feed in the event still asks for auto-download", seen.single().first.autoDownload)
        assertTrue("and a refresh does not undo the choice", lib.feeds.value.single().autoDownload && lib.content(content.feed.id)!!.feed.autoDownload)
        server.enqueue(MockResponse().setBody(extra)); lib.refresh(content.feed.id); delay(200)
        assertEquals("nothing new the second time", 1, seen.size)
        collector.cancel(); scope.cancel()
    }

    @Test fun `the lists on the home screens`() {
        fun ep(id: String, at: Long) = Episode(id, "f", id, "https://a/$id.mp3", publishedAt = at)
        val pod = FeedContent(Feed("f", "https://f", "Pod", kind = FeedKind.PODCAST), episodes = listOf(ep("new", 300), ep("mid", 200), ep("old", 100)))
        val blog = FeedContent(Feed("b", "https://b", "Blog", kind = FeedKind.ARTICLES), articles = listOf(Article("a1", "b", "A1", publishedAt = 50), Article("a2", "b", "A2", publishedAt = 150)))
        val contents = mapOf("f" to pod, "b" to blog)
        val progress = mapOf("old" to Progress("old", 90_000, 600_000, updatedAt = 9), "mid" to Progress("mid", 0, 0, played = true), "new" to Progress("new", 2_000, 600_000, updatedAt = 8))
        assertEquals(listOf("new", "old"), Derived.newEpisodes(contents, progress).map { it.first.id })
        assertEquals("under five seconds in is not 'in progress'", listOf("old"), Derived.inProgress(contents, progress).map { it.first.id })
        assertEquals(listOf("a2", "a1"), Derived.articles(contents).map { it.first.id })
    }
}
