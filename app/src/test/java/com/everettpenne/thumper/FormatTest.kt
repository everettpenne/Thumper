package com.everettpenne.thumper

import com.everettpenne.thumper.util.Format
import org.junit.Assert.*
import org.junit.Test

class FormatTest {
    private val now = 1_000_000_000_000L

    @Test fun `relative dates get finer as they age`() {
        assertEquals("Just now", Format.relativeDate(now - 30_000, now))
        assertEquals("1 min ago", Format.relativeDate(now - 60_000, now))
        assertEquals("5 min ago", Format.relativeDate(now - 5 * 60_000L, now))
        assertEquals("59 min ago", Format.relativeDate(now - 59 * 60_000L, now))
        assertEquals("1 hr ago", Format.relativeDate(now - 3_600_000, now))
        assertEquals("2 hr ago", Format.relativeDate(now - 2 * 3_600_000L, now))
        assertEquals("23 hr ago", Format.relativeDate(now - 23 * 3_600_000L, now))
        assertEquals("Yesterday", Format.relativeDate(now - 26 * 3_600_000L, now))
        assertEquals("3 days ago", Format.relativeDate(now - 3 * 86_400_000L, now))
    }

    @Test fun `relative dates answer the edges`() {
        assertEquals("nothing for an unknown date", "", Format.relativeDate(0, now))
        assertEquals("a future date is 'Soon'", "Soon", Format.relativeDate(now + 60_000, now))
        // a week old is a calendar date, not a count of days
        assertTrue(Format.relativeDate(now - 10 * 86_400_000L, now).isNotBlank())
        assertFalse(Format.relativeDate(now - 10 * 86_400_000L, now).contains("ago"))
    }

    @Test fun `snippet strips markup and collapses whitespace`() {
        assertEquals("Hello world", Format.snippet("<p>Hello\n  world</p>"))
        assertEquals("a b c", Format.snippet("a<br>b\n\n<p>c</p>"))
        assertEquals("", Format.snippet(""))
        assertEquals("", Format.snippet("<script>alert(1)</script>"))
    }

    @Test fun `snippet cuts at a word boundary and ends with an ellipsis`() {
        val long = List(30) { "word" }.joinToString(" ")
        assertEquals("word word word word…", Format.snippet(long, 20))
        // short enough: unchanged, no ellipsis
        assertEquals("short text", Format.snippet("short text", 20))
    }
}
