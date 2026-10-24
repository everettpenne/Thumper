package com.everettpenne.thumper

import com.everettpenne.thumper.data.model.Episode
import com.everettpenne.thumper.data.model.Feed
import com.everettpenne.thumper.data.model.FeedContent
import com.everettpenne.thumper.data.model.Progress
import com.everettpenne.thumper.data.model.Playable
import com.everettpenne.thumper.playback.NextUp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The pure "what plays next / previous" rules, driven without the player or Android. */
class NextUpTest {
    private val feed = Feed("show", "https://show.example/feed", "The Show")

    private fun ep(id: String, at: Long) = Episode(id, feed.id, "Episode $id", "https://cdn/$id.mp3", publishedAt = at)
    private fun content(episodes: List<Episode>) = FeedContent(feed, episodes = episodes)
    private fun played(id: String) = Progress(id, played = true)

    /** A show's episodes are stored newest first, as Listen and a show's page present them. */
    private val newestFirst = listOf(ep("new", 300), ep("mid", 200), ep("old", 100))

    private val queueOf: (List<Episode>) -> List<Playable> = { eps -> eps.map { Playable.Podcast(it, feed) } }

    @Test fun `next is the queue's first item when there is one`() {
        val queue = queueOf(listOf(ep("mid", 200), ep("old", 100)))
        val next = NextUp.next("new", feed.id, queue, mapOf(feed.id to content(newestFirst)), emptyMap())
        assertEquals("mid", next!!.id)
    }

    @Test fun `next ignores a queue entry that is the current episode`() {
        val queue = queueOf(listOf(ep("new", 300), ep("mid", 200)))
        val next = NextUp.next("new", feed.id, queue, mapOf(feed.id to content(newestFirst)), emptyMap())
        assertEquals("mid", next!!.id)
    }

    @Test fun `next with an empty queue is the show's next episode in time, not the next older one`() {
        val next = NextUp.next("mid", feed.id, emptyList(), mapOf(feed.id to content(newestFirst)), emptyMap())
        assertEquals("autoplay moves forward in time", "new", next!!.id)
        assertEquals("it carries the show with it", feed.title, (next as Playable.Podcast).feed.title)
        assertEquals("old is followed by mid", "mid", NextUp.next("old", feed.id, emptyList(), mapOf(feed.id to content(newestFirst)), emptyMap())!!.id)
    }

    @Test fun `next skips played episodes`() {
        val progress = mapOf("mid" to played("mid"))
        val next = NextUp.next("old", feed.id, emptyList(), mapOf(feed.id to content(newestFirst)), progress)
        assertEquals("new", next!!.id)
    }

    @Test fun `next counts a part-listened episode as unplayed`() {
        val progress = mapOf("mid" to Progress("mid", positionMs = 60_000, durationMs = 3_600_000))
        val next = NextUp.next("old", feed.id, emptyList(), mapOf(feed.id to content(newestFirst)), progress)
        assertEquals("mid", next!!.id)
    }

    @Test fun `next is null when nothing newer is unplayed`() {
        val progress = mapOf("mid" to played("mid"), "new" to played("new"))
        assertNull(NextUp.next("old", feed.id, emptyList(), mapOf(feed.id to content(newestFirst)), progress))
    }

    @Test fun `next is null after the show's latest episode, so playback stops cleanly`() {
        assertNull(NextUp.next("new", feed.id, emptyList(), mapOf(feed.id to content(newestFirst)), emptyMap()))
    }

    @Test fun `next follows publication dates even when the feed lists episodes out of order`() {
        val shuffled = listOf(ep("mid", 200), ep("new", 300), ep("old", 100))
        assertEquals("mid", NextUp.next("old", feed.id, emptyList(), mapOf(feed.id to content(shuffled)), emptyMap())!!.id)
        assertEquals("new", NextUp.next("mid", feed.id, emptyList(), mapOf(feed.id to content(shuffled)), emptyMap())!!.id)
    }

    @Test fun `a feed without dates is taken as newest first`() {
        val undated = listOf(ep("c", 0), ep("b", 0), ep("a", 0))
        assertEquals("b", NextUp.next("a", feed.id, emptyList(), mapOf(feed.id to content(undated)), emptyMap())!!.id)
        assertEquals("a", NextUp.previous("b", feed.id, mapOf(feed.id to content(undated)))!!.id)
    }

    @Test fun `next is null when the current episode is not in a subscribed feed`() {
        assertNull(NextUp.next("ghost", feed.id, emptyList(), mapOf(feed.id to content(newestFirst)), emptyMap()))
        assertNull(NextUp.next("new", "missing", emptyList(), mapOf(feed.id to content(newestFirst)), emptyMap()))
    }

    @Test fun `previous is the episode published just before the current one`() {
        val prev = NextUp.previous("new", feed.id, mapOf(feed.id to content(newestFirst)))
        assertEquals("mid", prev!!.id)
    }

    @Test fun `previous does not skip played episodes`() {
        // played does not matter: previous is simply where you were
        val prev = NextUp.previous("new", feed.id, mapOf(feed.id to content(newestFirst)))
        assertEquals("mid", prev!!.id)
    }

    @Test fun `previous is null at the show's oldest episode, so it restarts instead`() {
        assertNull(NextUp.previous("old", feed.id, mapOf(feed.id to content(newestFirst))))
    }

    @Test fun `previous is null when the episode is not in a subscribed feed`() {
        assertNull(NextUp.previous("ghost", feed.id, mapOf(feed.id to content(newestFirst))))
        assertNull(NextUp.previous("new", "missing", mapOf(feed.id to content(newestFirst))))
    }

    @Test fun `the restart threshold is the podcast convention of ten seconds`() {
        assertEquals(10_000L, NextUp.RESTART_AFTER_MS)
    }
}
