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
 * The app against tools/fake-feeds/server.py (run it on the host; the emulator reaches it at localhost:8097): finding a show, subscribing, listening
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
    /** How many times the fake server has been asked for audio (it counts them): a download counts once; playing the downloaded file must add none. */
    private fun audioRequests(): Int = okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url("http://localhost:8097/admin/audio-requests").build()).execute().use {
        Regex(""""count":\s*(\d+)""").find(it.body!!.string())!!.groupValues[1].toInt() }
    private fun shell(cmd: String) = device.executeShellCommand(cmd)
    private fun tab(name: String) = rule.onNodeWithContentDescription(name).performClick()

    private fun endPlayback() { context.stopService(android.content.Intent(context, com.everettpenne.thumper.playback.PlaybackService::class.java)) }

    @Before fun setUp() {
        endPlayback()         // a previous test may have left something playing in the service; every test starts from silence
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, "android.permission.POST_NOTIFICATIONS")
        context.getSharedPreferences("thumper_settings", 0).edit().putBoolean("wifiOnlyDownloads", false).putBoolean("deleteWhenPlayed", false).putString("directory", "APPLE").putString("test_apple_base", "http://localhost:8097").putString("test_apple_chart_base", "http://localhost:8097").commit()
        runBlocking { graph.library.reset() }
        graph.downloads.removeAll(); graph.settings.setWifiOnlyDownloads(false)
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
        rule.onNode(hasSetTextAction()).performTextInput("localhost:8097/blog")
        rule.onAllNodes(hasText("Add") and hasClickAction()).onFirst().performClick()
        waitFor("The Sand Report"); shot("6-blog-feed")
        rule.onNodeWithContentDescription("Back").performClick()
        tab("Read"); waitFor("Third post"); shot("7-read")
        rule.onNodeWithText("Third post").performClick()
        waitFor("Open original"); shot("8-article")
        assertTrue(has("Third post"))
    }

    @Test fun anArticlePictureOpensFullScreenAndCloses() {
        tab("Read"); waitFor("Nothing to read yet")
        rule.onAllNodes(hasText("Add a feed") and hasClickAction()).onFirst().performClick(); waitFor("Feed or website address")
        rule.onNode(hasSetTextAction()).performTextInput("localhost:8097/blog")
        rule.onAllNodes(hasText("Add") and hasClickAction()).onFirst().performClick()
        waitFor("The Sand Report"); rule.onNodeWithContentDescription("Back").performClick()
        tab("Read"); waitFor("Third post"); rule.onNodeWithText("Third post").performClick()
        waitFor("Open original"); waitForDesc("pic")
        rule.onNodeWithContentDescription("pic").performClick()
        waitForDesc("Close"); shot("i1-image-viewer")
        rule.onNodeWithContentDescription("Close").performClick()
        rule.waitUntil(15_000) { !hasDesc("Close") }
        assertTrue(has("Open original"))
    }

    @Test fun searchingFindsShowsAndAddressesAreRecognised() {
        tab("Search"); waitFor("Fake Show")
        rule.onNode(hasSetTextAction()).performTextInput("sand")
        waitFor("Sand Stories"); shot("9-search")
        rule.onNode(hasSetTextAction()).performTextReplacement("localhost:8097/feeds/show.xml")
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

    @Test fun aVideoPodcastShowsItsPictureAndGoesFullScreen() {
        // a feed whose episode is video/mp4: the player shows the picture, with a way into full screen and back, and playback carries on throughout
        rule.onNodeWithContentDescription("Add a feed").performClick()   // the + in the Listen bar
        rule.waitUntil(15_000) { rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasSetTextAction()).performTextInput("localhost:8097/feeds/video.xml")
        rule.onAllNodes(hasText("Add") and hasClickAction()).onFirst().performClick()
        waitFor("Episode 1: Test Pattern")
        rule.onAllNodesWithContentDescription("Play")[0].performClick()
        rule.waitUntil(40_000) { rule.onAllNodesWithTag("mini-player").fetchSemanticsNodes().isNotEmpty() && hasDesc("Pause") }
        rule.onNodeWithTag("mini-player").performClick(); waitFor("Now Playing")
        waitForDesc("Full screen"); shot("v1-player-with-video")
        rule.onNodeWithContentDescription("Full screen").performClick()
        // from here no Compose node lookups: a playing video keeps the Compose test rule from ever being idle, so the phase is checked from outside
        fun landscape() = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        rule.waitUntil(40_000) { landscape() }      // waitUntil is what lets Compose run frames in the test; a plain sleep would starve it
        Thread.sleep(1200); device.takeScreenshot(File(dir, "v2-full-screen.png"))
        assertTrue("landscape while full screen", landscape())
        // the player's own clock, through a controller of the playback service: it must keep moving while the picture is full screen
        val future = androidx.media3.session.MediaController.Builder(context, androidx.media3.session.SessionToken(context, android.content.ComponentName(context, com.everettpenne.thumper.playback.PlaybackService::class.java))).buildAsync()
        val controller = future.get(20, java.util.concurrent.TimeUnit.SECONDS)
        val first = runBlocking { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { controller.currentPosition } }
        Thread.sleep(2500)
        val second = runBlocking { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { controller.currentPosition } }
        runBlocking { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { androidx.media3.session.MediaController.releaseFuture(future) } }
        assertTrue("video keeps playing full screen ($first -> $second)", second > first + 1500)
        device.pressBack()      // Back leaves full screen
        rule.waitUntil(40_000) { !landscape() }
        rule.waitUntil(40_000) { hasDesc("Full screen") }
        assertTrue("portrait again", context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT)
        device.takeScreenshot(File(dir, "v3-back-in-player.png"))
    }

    private fun more(episodeTitle: String) = rule.onNodeWithContentDescription("More for $episodeTitle")

    @Test fun upNextPlaysInOrderAndAdvancesByItself() {
        subscribeToFakeShow(); rule.onNodeWithContentDescription("Back").performClick()
        tab("Listen"); waitFor("Episode 2: Worm Sign")
        // queue Episode 1 (a 6-second clip) from its menu, then play Episode 2 (also 6 seconds) by hand
        more("Episode 1: Pilot").performClick(); rule.onNodeWithText("Add to Up Next").performClick()
        waitFor("Up Next"); shot("11-up-next-section")
        assertTrue(graph.library.queue.value.map { it.episode.title } == listOf("Episode 1: Pilot"))
        rule.onNodeWithContentDescription("More for Episode 2: Worm Sign").performClick(); rule.onNodeWithText("Play Next").performClick()
        assertTrue("Play Next puts it first", graph.library.queue.value.first().episode.title == "Episode 2: Worm Sign")
        rule.onAllNodesWithText("See all")[0].performClick(); waitFor("Next"); shot("12-queue")
        // play the first queued entry: it leaves the queue, and when its short audio ends the next one starts by itself
        rule.onNodeWithContentDescription("Options for Episode 2: Worm Sign").performClick(); rule.onNodeWithText("Play Now").performClick()
        rule.waitUntil(30_000) { rule.onAllNodesWithContentDescription("Pause").fetchSemanticsNodes().isNotEmpty() }
        assertTrue("it left the queue as it started", graph.library.queue.value.map { it.episode.title } == listOf("Episode 1: Pilot"))
        rule.waitUntil(60_000) { graph.library.progress.value.values.any { it.played } }                       // Episode 2 finished...
        rule.waitUntil(30_000) { graph.library.queue.value.isEmpty() }                                         // ...and Episode 1 took its place by itself
        // the notification's own metadata follows the player: wait for it to name Episode 1
        var playing = ""
        rule.waitUntil(20_000) { playing = shell("dumpsys media_session").lines().filter { it.contains("metadata:") }.joinToString(" | "); playing.contains("Episode 1: Pilot") }
        assertTrue("Episode 1 should now be playing: $playing", playing.contains("Episode 1: Pilot"))
        assertTrue("Episode 2 was played to its end", graph.library.contents.value.values.first().episodes.first { it.number == 2 }.let { graph.library.progress(it.id)?.played == true })
    }

    @Test fun upNextCanBeReorderedAndEmptied() {
        subscribeToFakeShow(); rule.onNodeWithContentDescription("Back").performClick()
        tab("Listen"); waitFor("Episode 3: Spice Routes")
        for (t in listOf("Episode 4: The Thumper", "Episode 3: Spice Routes", "Episode 2: Worm Sign")) { more(t).performClick(); rule.onNodeWithText("Add to Up Next").performClick(); Thread.sleep(400) }
        assertTrue(graph.library.queue.value.map { it.episode.number } == listOf(4, 3, 2))
        rule.onAllNodesWithText("See all")[0].performClick(); waitFor("Next")
        rule.onNodeWithContentDescription("Options for Episode 2: Worm Sign").performClick(); rule.onNodeWithText("Move to Top").performClick()
        rule.waitUntil(10_000) { graph.library.queue.value.map { it.episode.number } == listOf(2, 4, 3) }
        rule.onNodeWithContentDescription("Options for Episode 4: The Thumper").performClick(); rule.onNodeWithText("Remove").performClick()
        rule.waitUntil(10_000) { graph.library.queue.value.map { it.episode.number } == listOf(2, 3) }
        rule.onNodeWithContentDescription("Clear Up Next").performClick()
        rule.waitUntil(10_000) { graph.library.queue.value.isEmpty() }; waitFor("Nothing up next")
    }

    @Test fun downloadAnEpisodePlayItWithoutTheNetworkAndRemoveIt() {
        subscribeToFakeShow(); rule.onNodeWithContentDescription("Back").performClick()
        tab("Listen"); waitFor("Episode 3: Spice Routes")
        more("Episode 3: Spice Routes").performClick(); rule.onNodeWithText("Download").performClick()
        val ep = graph.library.contents.value.values.first().episodes.first { it.number == 3 }
        rule.waitUntil(60_000) { graph.downloads.state.value[ep.id]?.status == com.everettpenne.thumper.data.DownloadStatus.DONE }
        val file = graph.downloads.fileFor(ep.id)
        assertTrue("the episode should be on the phone: $file", file != null && file.length() > 1_000_000)
        shot("13-downloaded-row")
        val before = audioRequests()
        // play it from its page: the audio must come from the file, so the server is never asked for it again
        rule.onNodeWithText("Episode 3: Spice Routes").performClick(); rule.waitUntil(20_000) { rule.onAllNodes(hasText("Downloaded", substring = true)).fetchSemanticsNodes().isNotEmpty() }; shot("14-episode-downloaded")
        rule.onAllNodes(hasText("Play") and hasClickAction()).onFirst().performClick()
        waitForDesc("Pause"); Thread.sleep(5000)
        assertTrue("audio should be playing", shell("dumpsys audio").lines().any { it.contains("AudioPlaybackConfiguration") && it.contains("state:started") && it.contains(" u/pid:${context.applicationInfo.uid}/") })
        assertTrue("playing a downloaded episode must not touch the network (was $before, now ${audioRequests()})", audioRequests() == before)
        // it is listed on the Downloads screen, and removing it deletes the file
        rule.onNodeWithContentDescription("Back").performClick()
        tab("Listen"); rule.onNodeWithContentDescription("Downloads").performClick(); waitFor("On This Phone"); waitFor("Delete All"); shot("15-downloads")
        rule.onNodeWithText("Delete All").performClick(); rule.onAllNodes(hasText("Delete All") and hasClickAction()).onLast().performClick()
        rule.waitUntil(20_000) { graph.downloads.records.value.isEmpty() }
        assertTrue("the file should be gone", graph.downloads.fileFor(ep.id) == null && graph.downloads.state.value.isEmpty())
        waitFor("No downloads")
    }
}
