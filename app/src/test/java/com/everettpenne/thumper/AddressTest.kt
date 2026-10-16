package com.everettpenne.thumper

import com.everettpenne.thumper.ui.screens.looksLikeAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressTest {
    @Test fun `addresses are told from searches`() {
        for (a in listOf("https://x.example/feed.xml", "http://10.0.2.2:8097/blog", "feed://x.example/f", "pcast://x.example/f", "example.com", "example.com/feed.xml", "10.0.2.2:8097/blog", "blog.example.co.uk/rss", "localhost.example:8080"))
            assertTrue(a, looksLikeAddress(a))
        for (q in listOf("", "  ", "darknet diaries", "radiolab", "99% invisible", "this american life", "a.b c", "hello"))
            assertFalse(q, looksLikeAddress(q))
    }
}
