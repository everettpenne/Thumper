package com.everettpenne.thumper

import com.everettpenne.thumper.util.Html
import org.junit.Assert.*
import org.junit.Test

class HtmlTest {
    @Test fun `plain text drops tags, decodes entities and tidies space`() {
        assertEquals("Tom & Jerry's “show”\nSecond line", Html.plain("<p>Tom &amp; Jerry&#39;s &ldquo;show&rdquo;</p>\n\n<p>  Second\n line </p>"))
        assertEquals("a\nb", Html.plain("a<br>b"))
        assertEquals("", Html.plain("<script>alert(1)</script><style>p{}</style>"))
    }

    @Test fun `links and emphasis are kept as runs`() {
        val b = Html.blocks("""<p>See <a href="https://x.example/a?b=1&amp;c=2">this <b>page</b></a> and <i>that</i>.</p>""").single() as Html.Block.Paragraph
        val link = b.runs.filter { it.href != null }
        assertEquals("https://x.example/a?b=1&c=2", link.first().href)
        assertEquals("this page", link.joinToString("") { it.text })
        assertTrue(b.runs.any { it.bold && it.text == "page" }); assertTrue(b.runs.any { it.italic && it.text == "that" })
    }

    @Test fun `headings lists quotes code and images become blocks`() {
        val blocks = Html.blocks("""<h2>Title</h2><ul><li>one</li><li>two</li></ul><ol><li>a</li><li>b</li></ol><blockquote>quoted</blockquote><pre>x = 1
y = 2</pre><img src="https://x.example/i.png" alt="pic"><hr>""")
        assertEquals(Html.Block.Heading(2, listOf(Html.Run("Title"))), blocks[0])
        assertEquals(listOf("•", "•", "1.", "2."), blocks.filterIsInstance<Html.Block.ListItem>().map { it.marker })
        assertTrue(blocks.any { it is Html.Block.Quote })
        assertEquals("x = 1\ny = 2", blocks.filterIsInstance<Html.Block.Code>().single().text)
        assertEquals(Html.Block.Image("https://x.example/i.png", "pic"), blocks.filterIsInstance<Html.Block.Image>().single())
        assertTrue(blocks.last() is Html.Block.Rule)
    }

    @Test fun `broken markup does not throw`() {
        Html.blocks("<p>unclosed <b>bold <a href=>x</p></div></b>< >&bogus; &#xZZ; <img><li>orphan")
        assertEquals("", Html.plain(""))
        assertEquals("plain text only", Html.plain("plain text only"))
    }

    @Test fun `data images and iframes are dropped`() {
        assertTrue(Html.blocks("""<img src="data:image/png;base64,AAAA"><iframe src="https://x"></iframe>after""").none { it is Html.Block.Image })
        assertEquals("after", Html.plain("""<iframe src="https://x">hidden</iframe>after"""))
    }
}
