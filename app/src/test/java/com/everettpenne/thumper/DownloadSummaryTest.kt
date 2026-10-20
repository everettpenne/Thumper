package com.everettpenne.thumper

import com.everettpenne.thumper.data.*
import com.everettpenne.thumper.data.model.Episode
import com.everettpenne.thumper.data.model.Feed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadSummaryTest {
    private fun record(id: String, title: String) = DownloadRecord(
        Episode(id = id, feedId = "f", title = title, audioUrl = "https://example.com/$id.mp3"),
        Feed(id = "f", url = "https://example.com/feed", title = "The Show"), 1L, "$id.mp3", 0L)

    @Test fun nothingGoingMeansNoNotification() {
        assertNull(DownloadSummary.of(emptyList(), emptyMap()))
        assertNull(DownloadSummary.of(listOf(record("a", "A")), mapOf("a" to DownloadInfo(DownloadStatus.DONE, 10, 10))))
    }

    @Test fun oneEpisodeShowsItsTitleShowAndPercent() {
        val s = DownloadSummary.of(listOf(record("a", "Episode A")), mapOf("a" to DownloadInfo(DownloadStatus.ACTIVE, 25, 100)))!!
        assertEquals("Episode A", s.title); assertEquals("The Show", s.text); assertEquals(25, s.percent); assertEquals("25%", s.shortText)
    }

    @Test fun severalAddUpAndAnUnknownSizeIsIndeterminate() {
        val two = DownloadSummary.of(listOf(record("a", "A"), record("b", "B")),
            mapOf("a" to DownloadInfo(DownloadStatus.ACTIVE, 50, 100), "b" to DownloadInfo(DownloadStatus.ACTIVE, 150, 300)))!!
        assertEquals("Downloading 2 episodes", two.title); assertEquals(50, two.percent)
        val unknown = DownloadSummary.of(listOf(record("a", "A")), mapOf("a" to DownloadInfo(DownloadStatus.ACTIVE, 50, 0)))!!
        assertNull(unknown.percent)
    }

    @Test fun waitingForANetworkSaysSo() {
        val s = DownloadSummary.of(listOf(record("a", "A")), mapOf("a" to DownloadInfo(DownloadStatus.PAUSED, 0, 100, "Waiting for Wi-Fi")))!!
        assertEquals("Waiting for Wi-Fi", s.text); assertEquals("0%", s.shortText)
    }
}
