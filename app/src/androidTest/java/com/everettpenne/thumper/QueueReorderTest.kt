package com.everettpenne.thumper

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.everettpenne.thumper.data.model.Playable
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Up Next: holding a row and dragging it over the others reorders the queue. */
class QueueReorderTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val graph get() = InstrumentationRegistry.getInstrumentation().targetContext.graph

    private fun song(n: Int) = Playable.Music("t$n", "Song $n", "Artist", "Album", "content://none/$n")

    @Test fun draggingARowDownMovesItPastItsNeighbour() {
        runBlocking { graph.library.reset() }
        (1..4).forEach { graph.library.enqueue(song(it)) }
        rule.waitForIdle()
        rule.activityRule.scenario.onActivity { it.onNewIntent(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("thumper://queue"))) }
        rule.waitUntil(15_000) { rule.onAllNodesWithText("Song 1").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf("music:t1", "music:t2", "music:t3", "music:t4"), graph.library.queue.value.map { it.id })
        val row = rule.onNodeWithText("Song 1")
        row.performTouchInput {
            down(center); advanceEventTime(700)           // held long enough to lift the row
            moveBy(androidx.compose.ui.geometry.Offset(0f, 60f)); advanceEventTime(60)
            moveBy(androidx.compose.ui.geometry.Offset(0f, 120f)); advanceEventTime(60)
            moveBy(androidx.compose.ui.geometry.Offset(0f, 120f)); advanceEventTime(60)
            up()
        }
        rule.waitForIdle()
        val order = graph.library.queue.value.map { it.id }
        org.junit.Assert.assertTrue("Song 1 should have moved down, queue is $order", order.indexOf("music:t1") > 0)
        org.junit.Assert.assertEquals(setOf("music:t1", "music:t2", "music:t3", "music:t4"), order.toSet())
        // Leave the screen so the activity can be closed (it is otherwise left resumed on Up Next).
        rule.activityRule.scenario.onActivity { it.finish() }
    }
}
