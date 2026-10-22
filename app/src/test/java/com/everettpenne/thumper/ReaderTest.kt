package com.everettpenne.thumper

import com.everettpenne.thumper.data.feed.ArticleText
import com.everettpenne.thumper.util.Format
import com.everettpenne.thumper.util.Html
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/** The reader: finding the article in a page, telling a teaser from a whole article, sections by day, and the player's end time. */
class ReaderTest {
    private val para = "This is a sentence of the article that goes on long enough to look like real writing, with commas, and more. "
    private fun page(article: String) = """
        <html><head><title>T</title><script>var x = 1;</script></head><body>
        <header><nav><a href="/">Home</a> <a href="/about">About</a> <a href="/blog">Blog</a></nav></header>
        <div class="sidebar"><p>Subscribe to my newsletter for weekly updates about everything I write here.</p></div>
        $article
        <footer><p>Copyright 2026. All rights reserved by the people who wrote this site and its parts.</p></footer>
        <div id="comments"><p>${para.repeat(3)}</p></div>
        </body></html>""".trimIndent()

    @Test fun theArticleIsFoundAndTheClutterLeftBehind() {
        val html = page("""<article><h1>Title</h1><p>${para.repeat(3)}</p><p>${para.repeat(3)}</p><img src="/pic.jpg" alt="a"><div class="share"><a href="x">Share on X</a></div></article>""")
        val text = ArticleText.extract(html, "https://blog.example/posts/1")!!
        val plain = Html.plain(text)
        assertTrue(plain.contains("This is a sentence"))
        listOf("newsletter", "Copyright", "Share on X", "var x").forEach { assertFalse("kept $it", plain.contains(it) || text.contains(it)) }
        assertTrue("addresses are made absolute", text.contains("https://blog.example/pic.jpg"))
    }

    @Test fun aPageWithoutAnArticleTagIsReadFromItsDensestText() {
        val html = page("""<div class="wrap"><div class="body"><p>${para.repeat(2)}</p><p>${para.repeat(2)}</p><p>${para.repeat(2)}</p></div></div>""")
        assertTrue(Html.plain(ArticleText.extract(html, "https://x.y/a")!!).contains("This is a sentence"))
    }

    @Test fun aPageThatIsAllLinksHasNoArticle() {
        val links = (1..40).joinToString(" ") { "<a href=\"/p$it\">A post title number $it about something</a>" }
        assertNull(ArticleText.extract(page("<main><p>$links</p></main>"), "https://x.y/"))
    }

    @Test fun aCaptionPrintedTwiceShowsOnce() {
        val cap = "Arcade pricing worked, in part, because you didn't have to buy the game up front."
        val html = page("""<article><p>${para.repeat(5)}</p><figure><img src="/a.jpg"><figcaption>$cap</figcaption></figure><p>$cap Credit: Getty Images</p><p>${para.repeat(3)}</p></article>""")
        assertEquals(1, Html.plain(ArticleText.extract(html, "https://x.y/a")!!).split("Arcade pricing").size - 1)
    }

    @Test fun lazyPicturesKeepTheirRealAddress() {
        val html = page("""<article><p>${para.repeat(5)}</p><img src="data:image/gif;base64,R0" data-src="https://cdn.x/real.jpg"></article>""")
        assertTrue(ArticleText.extract(html, "https://x.y/a")!!.contains("https://cdn.x/real.jpg"))
    }

    @Test fun teasersAreToldApartFromWholeArticles() {
        assertTrue(ArticleText.isTeaser("<p>A short summary.</p>"))
        assertTrue(ArticleText.isTeaser("<p>${para.repeat(8)} Continue reading</p>"))
        assertTrue(ArticleText.isTeaser("<p>${para.repeat(8)}…</p>"))
        assertTrue("Ars Technica's ending", ArticleText.isTeaser("<p>${para.repeat(8)}</p><p><a href=\"x\">Read full article</a></p><p><a href=\"y\">Comments</a></p>"))
        assertFalse(ArticleText.isTeaser("<p>${para.repeat(10)}</p>"))
        assertEquals(1, ArticleText.readingMinutes("<p>Short.</p>"))
        assertEquals(3, ArticleText.readingMinutes("<p>" + "word ".repeat(600) + "</p>"))
    }

    @Test fun relativeLinksAndPicturesInFeedsResolveAgainstTheArticle() {
        val blocks = Html.blocks("""<p><a href="../other">link</a></p><img src="img/a.png">""", base = "https://site.example/blog/post/")
        val link = (blocks[0] as Html.Block.Paragraph).runs.first().href
        assertEquals("https://site.example/blog/other", link)
        assertEquals("https://site.example/blog/post/img/a.png", (blocks[1] as Html.Block.Image).url)
    }

    @Test fun articlesAreSectionedByCalendarDay() {
        val utc = TimeZone.getTimeZone("UTC")
        val now = 1_700_000_000_000L      // 2023-11-14 22:13 UTC
        assertEquals("Today", Format.daySection(now - 3_600_000, now, utc))
        assertEquals("Yesterday", Format.daySection(now - 24 * 3_600_000L, now, utc))
        assertEquals("This Week", Format.daySection(now - 3 * 24 * 3_600_000L, now, utc))
        assertEquals("Earlier", Format.daySection(now - 30 * 24 * 3_600_000L, now, utc))
        assertEquals("Undated", Format.daySection(0, now, utc))
    }

    @Test fun thePlayerSaysWhenTheEpisodeEndsAtItsSpeed() {
        val utc = TimeZone.getTimeZone("UTC")
        val now = 1_700_000_000_000L      // 22:13 UTC
        // an hour left at 1.5x is forty minutes: 22:53
        assertEquals("Ends 10:53 PM", Format.endsAt(0, 3_600_000, 1.5f, now, Locale.US, utc).replace(' ', ' '))
        assertEquals("-40:00", Format.remaining(0, 3_600_000, 1.5f))
        assertEquals("-1:00:00", Format.remaining(0, 3_600_000, 1f))
        assertEquals("", Format.endsAt(0, 0, 1f, now))
    }
}
