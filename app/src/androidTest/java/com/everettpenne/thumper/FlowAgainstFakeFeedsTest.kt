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

/**
 * The app against tools/fake-feeds/server.py (run it on the host; the emulator reaches it at 10.0.2.2:8097): finding a show, subscribing, listening
 * (including with the app in the background and picking up where you left off), and adding a blog by its web address and reading it.
 */
class FlowAgainstFakeFeedsTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val dir = File(context.getExternalFilesDir(null), "shots").apply { mkdirs() }
    private val graph get() = context.graph

    private fun shot(name: String) { rule.waitForIdle(); Thread.sleep(1200); device.takeScreenshot(File(dir, "$name.png")) }
    private fun has(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun hasDesc(desc: String) = rule.onAllNodesWithContentDescription(desc).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(text: String, timeout: Long = 40_000) { try { rule.waitUntil(timeout) { has(text) } } catch (e: Throwable) { shot("FAILED-" + text.replace(Regex("\\W+"), "-")); android.util.Log.e("FlowTest", "waiting for \"$text\"; on screen:\n" + rule.onAllNodes(isRoot()).fetchSemanticsNodes().joinToString("\n") { n -> n.config.toString() }); throw e } }
    private fun waitForDesc(desc: String, timeout: Long = 40_000) { try { rule.waitUntil(timeout) { hasDesc(desc) } } catch (e: Throwable) { shot("FAILED-" + desc.replace(Regex("\\W+"), "-")); throw e } }
    private fun shell(cmd: String) = device.executeShellCommand(cmd)
    private fun tab(name: String) = rule.onNodeWithContentDescription(name).performClick()

    private fun endPlayback() { context.stopService(android.content.Intent(context, com.everettpenne.thumper.playback.PlaybackService::class.java)) }

    @Before fun setUp() {
        endPlayback()         // a previous test may have left something playing in the service; every test starts from silence
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, "android.permission.POST_NOTIFICATIONS")
        context.getSharedPreferences("thumper_settings", 0).edit().putString("directory", "APPLE").putString("test_apple_base", "http://10.0.2.2:8097").putString("test_apple_chart_base", "http://10.0.2.2:8097").commit()
        runBlocking { graph.library.reset() }
        waitFor("No podcasts yet")
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("mini-player").fetchSemanticsNodes().isEmpty() }
    }
    @After fun tearDown() { device.setOrientationNatural(); endPlayback() }

    private fun subscribeToFakeShow() {
        tab("Search"); waitFor("Popular on Apple Podcasts"); waitFor("Fake Show")
        rule.onNodeWithText("Fake Show").performClick()
        rule.waitUntil(40_000) { rule.onAllNodes(hasText("Subscribe") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasText("Subscribe") and hasClickAction()).onFirst().performClick(); waitFor("Subscribed")
    }

    @Test fun findAShowSubscribeAndListen() {
        shot("1-empty")
        subscribeToFakeShow(); shot("2-feed")
        rule.onNodeWithContentDescription("Back").performClick()
        tab("Listen"); waitFor("Your Shows"); waitFor("Episode 4: The Thumper"); shot("3-listen")
        rule.onAllNodesWithContentDescription("Play")[0].performClick()
        rule.onNodeWithTag("mini-player").assertExists()
        rule.waitUntil(40_000) { rule.onAllNodesWithTag("mini-player").fetchSemanticsNodes().isNotEmpty() && hasDesc("Pause") }
        Thread.sleep(3000)
        assertTrue("an audio track for Thumper should be started:\n" + shell("dumpsys audio").lines().filter { it.contains("AudioPlaybackConfiguration") }.take(5).joinToString("\n"),
            shell("dumpsys audio").lines().any { it.contains("AudioPlaybackConfiguration") && it.contains("state:started") && it.contains(" u/pid:${context.applicationInfo.uid}/") })
        shot("4-mini-player")
        rule.onNodeWithTag("mini-player").performClick(); waitFor("Now Playing"); waitForDesc("Skip forward 30 seconds"); shot("5-player")
        rule.onNodeWithContentDescription("Skip forward 30 seconds").performClick()
        rule.onNodeWithContentDescription("Pause").performClick(); Thread.sleep(800)
        waitForDesc("Play")
    }

    @Test fun soundContinuesInTheBackgroundAndResumesWhereYouLeftOff() {
        subscribeToFakeShow(); rule.onNodeWithContentDescription("Back").performClick()
        tab("Listen"); waitFor("Episode 4: The Thumper")
        rule.onAllNodesWithContentDescription("Play")[0].performClick(); waitForDesc("Pause"); Thread.sleep(2000)
        device.pressHome(); Thread.sleep(7000)
        val dump = shell("dumpsys media_session")
        val first = Regex("""PlaybackState \{state=PLAYING\(3\), position=(\d+)""").find(dump)?.groupValues?.get(1)?.toLong()
        assertTrue("the session should be playing in the background:\n" + dump.lines().filter { it.contains("state=") }.take(4), first != null)
        assertTrue("a foreground service should keep it alive", shell("dumpsys activity services ${context.packageName}").contains("isForeground=true"))
        Thread.sleep(3000)
        val second = Regex("""PlaybackState \{state=PLAYING\(3\), position=(\d+)""").find(shell("dumpsys media_session"))?.groupValues?.get(1)?.toLong()
        assertTrue("position should advance ($first -> $second)", second != null && second > first!! + 1500)
        shell("am start -n ${context.packageName}/.MainActivity"); waitForDesc("Pause")
        rule.onAllNodesWithContentDescription("Pause")[0].performClick(); Thread.sleep(1500)
        runBlocking { graph.library.flush() }
        val ep = graph.library.contents.value.values.first().episodes.first()
        val saved = graph.library.progress(ep.id)
        assertTrue("where you were should be remembered: $saved", saved != null && saved.positionMs > 5_000 && !saved.played)
    }

    @Test fun addABlogByItsWebAddressAndReadIt() {
        tab("Read"); waitFor("Nothing to read yet")
        rule.onAllNodes(hasText("Add a feed") and hasClickAction()).onFirst().performClick(); waitFor("Feed or website address")
        rule.onNode(hasSetTextAction()).performTextInput("10.0.2.2:8097/blog")
        rule.onAllNodes(hasText("Add") and hasClickAction()).onFirst().performClick()
        waitFor("The Sand Report"); shot("6-blog-feed")
        rule.onNodeWithContentDescription("Back").performClick()
        tab("Read"); waitFor("Third post"); shot("7-read")
        rule.onNodeWithText("Third post").performClick()
        waitFor("Open original"); shot("8-article")
        assertTrue(has("Third post"))
    }

    @Test fun searchingFindsShowsAndAddressesAreRecognised() {
        tab("Search"); waitFor("Fake Show")
        rule.onNode(hasSetTextAction()).performTextInput("sand")
        waitFor("Sand Stories"); shot("9-search")
        rule.onNode(hasSetTextAction()).performTextReplacement("10.0.2.2:8097/feeds/show.xml")
        waitFor("Add this feed")
    }

    @Test fun settingsOffersTheDirectoriesPlaybackAndLibraryOptions() {
        rule.onNodeWithContentDescription("Settings").performClick()
        waitFor("Find Podcasts With"); waitFor("Skip back"); waitFor("Import Subscriptions"); waitFor("Export Subscriptions")
        assertTrue(!has("API key"))
        rule.onNodeWithText("Podcast Index").performClick(); waitFor("API key"); waitFor("Get a key"); shot("10-settings-podcast-index")
        rule.onNodeWithText("Apple Podcasts").performClick()
        rule.waitUntil(10_000) { !has("API key") }
    }
}
