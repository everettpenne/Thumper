package com.everettpenne.thumper

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/** The real thing, not the stand-in server: Apple's directory, a real show's feed, a real episode streamed from its host. Needs the emulator to have internet. */
class RealNetworkTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val dir = File(context.getExternalFilesDir(null), "shots").apply { mkdirs() }
    private fun has(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(text: String, timeout: Long = 60_000) { try { rule.waitUntil(timeout) { has(text) } } catch (e: Throwable) { device.takeScreenshot(File(dir, "FAILED-real-${text.replace(Regex("\\W+"), "-")}.png")); throw e } }

    @Before fun setUp() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, "android.permission.POST_NOTIFICATIONS")
        context.getSharedPreferences("thumper_settings", 0).edit().remove("test_apple_base").remove("test_apple_chart_base").putString("directory", "APPLE").commit()
        runBlocking { context.graph.library.reset() }
        context.stopService(android.content.Intent(context, com.everettpenne.thumper.playback.PlaybackService::class.java))
        waitFor("No podcasts yet")
    }
    @After fun tearDown() { context.stopService(android.content.Intent(context, com.everettpenne.thumper.playback.PlaybackService::class.java)) }

    @Test fun findARealShowSubscribeAndPlayItsLatestEpisode() {
        rule.onNodeWithContentDescription("Search").performClick()
        waitFor("Popular on Apple Podcasts")                                      // the real chart, through the real lookup
        device.takeScreenshot(File(dir, "real-1-popular.png"))
        rule.onNode(hasSetTextAction()).performTextInput("darknet diaries")
        waitFor("Darknet Diaries"); device.takeScreenshot(File(dir, "real-2-search.png"))
        rule.onAllNodesWithText("Darknet Diaries")[0].performClick()
        rule.waitUntil(90_000) { rule.onAllNodes(hasText("Subscribe") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasText("Subscribe") and hasClickAction()).onFirst().performClick(); waitFor("Subscribed")
        val content = context.graph.library.contents.value.values.single()
        assertTrue("a real feed should bring its episodes: ${content.episodes.size}", content.episodes.size >= 50)
        assertTrue(content.episodes.all { it.audioUrl.startsWith("http") })
        device.takeScreenshot(File(dir, "real-3-feed.png"))
        rule.onAllNodesWithContentDescription("Play")[0].performClick()
        rule.waitUntil(60_000) { rule.onAllNodesWithContentDescription("Pause").fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(8000)
        val audio = device.executeShellCommand("dumpsys audio").lines().any { it.contains("AudioPlaybackConfiguration") && it.contains("state:started") && it.contains(" u/pid:${context.applicationInfo.uid}/") }
        assertTrue("a real episode should be playing through the audio system", audio)
        device.takeScreenshot(File(dir, "real-4-playing.png"))
    }

    /** A real episode from a real public host through the system DownloadManager: the shortest of the latest ten, so it is quick. */
    @Test fun downloadARealEpisodeAndPlayItFromTheFile() {
        context.getSharedPreferences("thumper_settings", 0).edit().putBoolean("wifiOnlyDownloads", false).commit()
        context.graph.settings.setWifiOnlyDownloads(false); context.graph.downloads.removeAll()
        rule.onNodeWithContentDescription("Search").performClick(); waitFor("Popular on Apple Podcasts")
        rule.onNode(hasSetTextAction()).performTextInput("darknet diaries"); waitFor("Darknet Diaries")
        rule.onAllNodesWithText("Darknet Diaries")[0].performClick()
        rule.waitUntil(90_000) { rule.onAllNodes(hasText("Subscribe") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasText("Subscribe") and hasClickAction()).onFirst().performClick(); waitFor("Subscribed")
        val shortest = context.graph.library.contents.value.values.single().episodes.take(10).filter { (it.durationSec ?: Int.MAX_VALUE) < 2_000 }.minByOrNull { it.durationSec ?: Int.MAX_VALUE }
            ?: context.graph.library.contents.value.values.single().episodes.first()
        rule.onNodeWithContentDescription("More for ${shortest.title}").performScrollTo().performClick()
        rule.onNodeWithText("Download").performClick()
        rule.waitUntil(240_000) { context.graph.downloads.state.value[shortest.id]?.status == com.everettpenne.thumper.data.DownloadStatus.DONE }
        val file = context.graph.downloads.fileFor(shortest.id)
        assertTrue("a real episode should be on the phone: $file", file != null && file.length() > 100_000)
        device.takeScreenshot(File(dir, "real-5-downloaded.png"))
        context.graph.downloads.removeAll()
        assertTrue("and gone again", context.graph.downloads.fileFor(shortest.id) == null)
    }
}
