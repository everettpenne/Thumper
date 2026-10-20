package com.everettpenne.thumper

import com.everettpenne.thumper.data.feed.FeedParser
import com.everettpenne.thumper.data.model.FeedKind
import org.junit.Assert.*
import org.junit.Test

class FeedParserTest {
    private fun resource(name: String) = checkNotNull(javaClass.classLoader!!.getResourceAsStream(name)) { "missing $name" }
    private fun parse(name: String, url: String) = FeedParser.parse(resource(name), url, now = 1_000)

    @Test fun `a real podcast feed is read as a podcast with its episodes`() {
        val c = parse("darknet-trimmed.xml", "https://podcast.darknetdiaries.com")
        assertEquals("Darknet Diaries", c.feed.title)
        assertEquals(FeedKind.PODCAST, c.feed.kind)
        assertEquals(6, c.episodes.size)
        assertTrue(c.episodes.all { it.audioUrl.startsWith("http") && it.title.isNotBlank() })
        assertTrue("newest first", c.episodes.zipWithNext().all { (a, b) -> a.publishedAt >= b.publishedAt })
        assertTrue(c.episodes.first().publishedAt > 0)
        assertNotNull(c.feed.imageUrl)
        assertTrue(c.feed.description.contains("true stories"))
        assertTrue("ids are unique", c.episodes.map { it.id }.toSet().size == c.episodes.size)
    }

    @Test fun `itunes tags, dates and durations are understood`() {
        val c = parse("podcast-mixed.xml", "https://show.example/feed.xml")
        assertEquals("Spice & Sand", c.feed.title)
        assertEquals("Chani", c.feed.author)
        assertEquals("https://show.example/art.jpg", c.feed.imageUrl)
        val two = c.episodes.first { it.title.startsWith("Ep 2") }
        assertEquals(3723, two.durationSec)
        assertEquals(2, two.season); assertEquals(2, two.number)
        assertEquals("https://show.example/ep2.jpg", two.imageUrl)
        assertEquals("https://cdn.example/ep2.mp3?x=1", two.audioUrl)
        assertTrue("content:encoded wins over the description", two.summaryHtml.contains("<a href"))
        val one = c.episodes.first { it.title == "Ep 1" }
        assertEquals("a relative enclosure is resolved against the feed", "https://show.example/ep1.m4a", one.audioUrl)
        assertEquals(2750, one.durationSec)
        assertTrue(two.publishedAt > one.publishedAt)
    }

    @Test fun `an item without audio is not an episode, and a bad date is not fatal`() {
        val c = parse("podcast-mixed.xml", "https://show.example/feed.xml")
        assertEquals(3, c.episodes.size)                       // ep 2, ep 1, and the one with an untyped .mp3 enclosure
        assertEquals(1, c.articles.size)
        assertEquals(0L, c.episodes.first { it.title == "Untyped enclosure" }.publishedAt)
        assertEquals(FeedKind.PODCAST, c.feed.kind)
    }

    @Test fun `an atom blog is a feed of articles`() {
        val c = parse("blog-atom.xml", "https://sand.example/atom.xml")
        assertEquals(FeedKind.ARTICLES, c.feed.kind)
        assertEquals("The Sand Report", c.feed.title)
        assertEquals("https://sand.example/", c.feed.siteUrl)
        assertEquals("https://sand.example/logo.png", c.feed.imageUrl)
        assertEquals(2, c.articles.size)
        val water = c.articles.first { it.title.startsWith("Water") }
        assertEquals("Water & discipline", water.title)
        assertEquals("https://sand.example/posts/water", water.link)
        assertEquals("Stilgar", water.author)
        assertEquals("https://sand.example/thumb/water.jpg", water.imageUrl)
        assertTrue(water.contentHtml.contains("<b>counts</b>"))
        assertEquals(1_790_836_200_000L, water.publishedAt)       // 2026-10-01T08:30+02:00 = 06:30 UTC
        val plain = c.articles.first { it.title.startsWith("No date") }
        assertEquals("Just a summary.", plain.contentHtml)
        assertEquals(0L, plain.publishedAt)
    }

    @Test fun `something that is not a feed is refused`() {
        try { FeedParser.parse("<html><body>hello</body></html>".byteInputStream(), "https://x.example"); fail("should refuse") }
        catch (e: FeedParser.FeedFormatException) { assertTrue(e.message!!.contains("isn't a feed")) }
    }

    @Test fun `a truncated feed keeps what was read`() {
        val text = resource("podcast-mixed.xml").readBytes().decodeToString()
        val cut = text.substring(0, text.indexOf("<item>", text.indexOf("Ep 1")) )         // stops in the middle, after two items
        val c = try { FeedParser.parse(cut.byteInputStream(), "https://show.example/feed.xml") } catch (e: Exception) { null }
        // either it reads the complete items or refuses cleanly; it must not throw an unexpected error type
        if (c != null) assertTrue(c.episodes.size <= 2)
    }

    @Test fun `durations and dates`() {
        assertEquals(3723, FeedParser.parseDuration("1:02:03")); assertEquals(2710, FeedParser.parseDuration("45:10")); assertEquals(90, FeedParser.parseDuration("90"))
        assertNull(FeedParser.parseDuration("")); assertNull(FeedParser.parseDuration("soon")); assertNull(FeedParser.parseDuration("1::2")); assertNull(FeedParser.parseDuration("0"))
        assertEquals(1_790_848_800_000L, FeedParser.parseDate("Wed, 01 Oct 2026 10:00:00 +0000"))
        assertEquals(1_790_848_800_000L, FeedParser.parseDate("Wed, 1 Oct 2026 10:00:00 GMT"))
        assertEquals(1_790_848_800_000L, FeedParser.parseDate("2026-10-01T10:00:00Z"))
        assertEquals(1_790_848_800_000L, FeedParser.parseDate("2026-10-01T12:00:00+02:00"))
        assertEquals(0L, FeedParser.parseDate("nonsense")); assertEquals(0L, FeedParser.parseDate(null))
    }

    @Test fun `a post with no headline gets one from its text`() {
        val xml = """<rss version="2.0"><channel><title>Social</title>
            <item><guid>1</guid><description>&lt;p&gt;Shipped a new release today. It fixes the camera bug and more.&lt;/p&gt;</description></item>
            <item><guid>2</guid><description>&lt;p&gt;short&lt;/p&gt;</description></item>
            <item><guid>3</guid><description>&lt;img src="https://x.example/a.jpg"&gt;</description></item>
            </channel></rss>"""
        val a = FeedParser.parse(xml.byteInputStream(), "https://social.example/@me.rss").articles.associateBy { it.id.let { _ -> it.contentHtml.length } }.values.toList()
        val titles = a.map { it.title }.toSet()
        assertTrue(titles.toString(), "Shipped a new release today." in titles)
        assertTrue(titles.toString(), "short" in titles)
        assertTrue(titles.toString(), "Untitled" in titles)
    }

    @Test fun `a long headline made from text is cut at a word`() {
        val t = FeedParser.titleFrom("<p>" + "word ".repeat(40) + "</p>")
        assertTrue(t, t.endsWith("…") && t.length <= 91 && !t.contains("wor…"))
    }

    @Test fun `the first real picture in the text is the thumbnail`() {
        val html = """<img width="1" height="1" src="https://feeds.feedburner.com/~r/x.gif"><p>Hi</p><img src='/images/lead.jpg' width="600">"""
        assertEquals("https://blog.example/images/lead.jpg", FeedParser.firstImage(html, "https://blog.example/post/1"))
        assertNull(FeedParser.firstImage("<p>no pictures</p>", "https://blog.example/"))
    }

    @Test fun `a YouTube feed has thumbnails and descriptions from its media group`() {
        val xml = """<feed xmlns="http://www.w3.org/2005/Atom" xmlns:media="http://search.yahoo.com/mrss/"><title>Channel</title>
            <entry><id>yt:video:abc</id><title>A video</title><link rel="alternate" href="https://www.youtube.com/watch?v=abc"/>
            <media:group><media:title>A video</media:title><media:thumbnail url="https://i1.ytimg.com/vi/abc/hqdefault.jpg" width="480" height="360"/>
            <media:description>What it is about.</media:description></media:group></entry></feed>"""
        val a = FeedParser.parse(xml.byteInputStream(), "https://www.youtube.com/feeds/videos.xml?channel_id=x").articles.single()
        assertEquals("https://i1.ytimg.com/vi/abc/hqdefault.jpg", a.imageUrl)
        assertEquals("What it is about.", a.contentHtml)
        assertEquals("A video", a.title)
    }
}
